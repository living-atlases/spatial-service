package au.org.ala.spatial

import au.org.ala.spatial.dto.GridClass
import au.org.ala.spatial.dto.IntersectionFile
import au.org.ala.spatial.dto.Tabulation
import grails.testing.services.ServiceUnitTest
import spock.lang.Specification

/**
 * TabulationService.getTabulation / getTabulationSingle: query parameters and the mapping of the rows.
 */
class TabulationServiceQuerySpec extends Specification implements ServiceUnitTest<TabulationService> {

    FakeJdbc jdbc = new FakeJdbc()
    LayerService layerService = Mock()

    @Override
    Set<String> getIncludePlugins() {
        ['core', 'eventBus', 'converters'].toSet()
    }

    def setup() {
        service.dataSource = jdbc.dataSource
        service.layerService = layerService
        service.spatialObjectsService = Mock(SpatialObjectsService)
        service.tabulationGeneratorService = Mock(TabulationGeneratorService)
        service.spatialConfig = new SpatialConfig()
    }

    private static IntersectionFile file(String fid, String type, Map<Integer, String> classes = null) {
        HashMap<Integer, GridClass> gc = null
        if (classes != null) {
            gc = new HashMap<>()
            classes.each { k, v -> gc.put(k, new GridClass(id: k, name: v)) }
        }
        new IntersectionFile(fid, '/data/' + fid, 'name', fid, fid, 'field ' + fid, '1', type, gc)
    }

    private static Map<String, Object> row(String pid1, String pid2, double area, String name1, String name2) {
        [pid1: pid1, pid2: pid2, fid1: 'cl1', fid2: 'cl2', area: area, name1: name1, name2: name2,
         occurrences: 3, species: 2, speciest1: 5, speciest2: 6]
    }

    def "the field ids are queried in order, whatever the order of the request"() {
        given:
        layerService.getIntersectionFile(_) >> { String id -> file(id, 'c') }

        when:
        service.getTabulation('cl2', 'cl1', null)

        then:
        jdbc.lastParameters == ['cl1', 'cl2', 'cl1', 'cl2']
    }

    def "the rows are mapped to tabulations"() {
        given:
        layerService.getIntersectionFile(_) >> { String id -> file(id, 'c') }
        jdbc.results << [row('1', '2', 12.5d, 'ACT', 'Grassland'), row('3', '4', 0.5d, 'NSW', 'Forest')]

        when:
        List<Tabulation> list = service.getTabulation('cl1', 'cl2', '')

        then:
        list.size() == 2
        list[0].pid1 == '1'
        list[0].name1 == 'ACT'
        list[0].name2 == 'Grassland'
        list[0].area == 12.5d
        list[0].occurrences == 3
        list[0].species == 2
        list[1].speciest1 == 5
        list[1].speciest2 == 6
    }

    def "the names of grid classes come from the classes of the grid"() {
        given:
        layerService.getIntersectionFile('cl1') >> file('cl1', 'c')
        layerService.getIntersectionFile('cl2') >> file('cl2', 'a', [1: 'Rainforest', 2: 'Desert'])
        jdbc.results << [row('10', '2:1', 1d, 'ACT', null), row('10', '2:2', 1d, 'ACT', null)]

        when:
        List<Tabulation> list = service.getTabulation('cl1', 'cl2', null)

        then:
        list*.name2 == ['Rainforest', 'Desert']
    }

    def "a tabulation within an area recalculates the area of each intersection"() {
        given:
        layerService.getIntersectionFile(_) >> { String id -> file(id, 'c') }
        jdbc.results << [[fid1: 'cl1', pid1: '1', fid2: 'cl2', pid2: '2', geometry: 'POLYGON((0 0,1 0,1 1,0 1,0 0))',
                          name1: 'a', name2: 'b', occurrences: 0, species: 0, speciest1: 0, speciest2: 0]]

        when:
        List<Tabulation> list = service.getTabulation('cl1', 'cl2', 'POLYGON((0 0,2 0,2 2,0 2,0 0))')

        then:
        jdbc.lastParameters == ['POLYGON((0 0,2 0,2 2,0 2,0 0))', 'cl1', 'cl2', 'cl1', 'cl2']
        list.size() == 1
        list[0].area > 12000 && list[0].area < 12500 // sq km of a 1 degree cell at the equator
    }

    def "a single tabulation of an unknown field is empty"() {
        given:
        layerService.getIntersectionFile('cl0') >> null

        expect:
        service.getTabulationSingle('cl0', 'POLYGON((0 0,1 0,1 1,0 1,0 0))') == []
    }

    def "a single tabulation of a contextual field without an area lists the area of each object"() {
        given:
        layerService.getIntersectionFile('cl22') >> file('cl22', 'c')
        jdbc.results << [[fid1: 'cl22', pid1: '1', name1: 'ACT', fid2: 'world', pid2: 'world', area: 2358d]]

        when:
        List<Tabulation> list = service.getTabulationSingle('cl22', '')

        then:
        jdbc.lastParameters == ['cl22']
        list.size() == 1
        list[0].name1 == 'ACT'
        list[0].area == 2358d
    }

    def "a single tabulation of a contextual field within a pid has the area of each intersection"() {
        given:
        layerService.getIntersectionFile('cl22') >> file('cl22', 'c')
        jdbc.results << [[fid1: 'cl22', pid1: '1', name1: 'ACT', fid2: 'x', pid2: 'x', name2: 'x',
                          geometry: 'POLYGON((0 0,1 0,1 1,0 1,0 0))']]

        when:
        List<Tabulation> list = service.getTabulationSingle('cl22', '123')

        then:
        list.size() == 1
        list[0].name1 == 'ACT'
        list[0].area > 12000 && list[0].area < 12500
        list[0].geometry == null
    }

    def "a single tabulation of a contextual field within a wkt has the area of each intersection"() {
        given:
        layerService.getIntersectionFile('cl22') >> file('cl22', 'c')
        jdbc.results << [[fid1: 'cl22', pid1: '1', name1: 'ACT', fid2: 'x', pid2: 'x', name2: 'x',
                          geometry: 'POLYGON((0 0,1 0,1 1,0 1,0 0))']]

        when:
        List<Tabulation> list = service.getTabulationSingle('cl22', 'POLYGON((0 0,2 0,2 2,0 2,0 0))')

        then:
        list.size() == 1
        list[0].name1 == 'ACT'
        list[0].area > 12000 && list[0].area < 12500
        list[0].geometry == null
    }

    def "a single tabulation of a grid field within an object is calculated from its geometry"() {
        given:
        layerService.getIntersectionFile('el1') >> file('el1', 'e')
        List<Tabulation> expected = [new Tabulation(pid1: '1')]

        when:
        def list = service.getTabulationSingle('el1', '123')

        then:
        1 * service.spatialObjectsService.getObjectsGeometryById('123', 'wkt') >> 'POLYGON((0 0,1 0,1 1,0 1,0 0))'
        1 * service.tabulationGeneratorService.calc('el1', 'POLYGON((0 0,1 0,1 1,0 1,0 0))') >> expected
        list.is(expected)
    }
}
