package au.org.ala.spatial

import au.org.ala.spatial.dto.GridClass
import au.org.ala.spatial.dto.IntersectionFile
import au.org.ala.spatial.dto.SearchObject
import grails.testing.services.ServiceUnitTest
import spock.lang.PendingFeature
import spock.lang.Specification

/**
 * SearchService.findByCriteria: gazetteer search of objects and of the classes of 'grid as contextual' layers.
 */
class SearchServiceSpec extends Specification implements ServiceUnitTest<SearchService> {

    FakeJdbc jdbc = new FakeJdbc()
    LayerService layerService = Mock()
    FieldService fieldService = Mock()

    @Override
    Set<String> getIncludePlugins() {
        ['core', 'eventBus', 'converters'].toSet()
    }

    def setup() {
        service.dataSource = jdbc.dataSource
        service.layerService = layerService
        service.fieldService = fieldService
        layerService.getIntersectionFiles() >> [:]
        fieldService.getFields(true) >> ['cl22', 'cl23', 'cl1918'].collect { String fid -> new Fields().tap { id = fid } }
    }

    private static IntersectionFile gridAsClasses(String fid, Map<Integer, String> classes) {
        HashMap<Integer, GridClass> gc = new HashMap<>()
        classes.each { k, v -> gc.put(k, new GridClass(id: k, name: v)) }
        new IntersectionFile(fid, '/data/' + fid, null, fid, fid, 'Vegetation', '1918', 'a', gc)
    }

    private static Map<String, Object> row(String pid, String name) {
        [pid: pid, name: name, description: 'd', fid: 'cl22', fieldname: 'States', fields: '["cl22 | States"]']
    }

    def "the criteria is matched anywhere in the name and ranked by its position"() {
        given:
        jdbc.results << [row('1', 'Wales')]

        when:
        service.findByCriteria('wales', 20, 10)

        then:
        jdbc.statements.size() == 1
        jdbc.lastSql.contains('o.name ilike ? and o.namesearch=true')
        jdbc.lastSql.contains('position(? in lower(name)) as rank')
        jdbc.lastSql.endsWith('order by rank, name, pid limit ? offset ?')
        jdbc.lastParameters == ['%wales%', 'wales', 10, 20]
    }

    def "the rows are returned as search objects"() {
        given:
        jdbc.results << [row('1', 'New South Wales'), row('2', 'Wales')]

        when:
        List<SearchObject> result = service.findByCriteria('wales', 0, 10)

        then:
        result*.pid == ['1', '2']
        result*.name == ['New South Wales', 'Wales']
        result[0].fid == 'cl22'
        result[0].fieldname == 'States'
    }

    def "include restricts the search to known field ids"() {
        when:
        service.findByCriteria('x', 0, 10, ['cl22', 'cl23', 'nope'], [])

        then:
        jdbc.lastSql.contains(" and o.fid in ( 'cl22','cl23' ) ")
    }

    def "exclude removes known field ids from the search"() {
        when:
        service.findByCriteria('x', 0, 10, [], ['cl23', 'nope'])

        then:
        jdbc.lastSql.contains(" and o.fid not in ( 'cl23' ) ")
    }

    def "unknown include field ids search every field"() {
        when:
        service.findByCriteria('x', 0, 10, ['nope'], [])

        then:
        !jdbc.lastSql.contains('o.fid in')
    }

    def "classes of grid as contextual layers that match are added"() {
        when:
        List<SearchObject> result = service.findByCriteria('rain', 0, 10)

        then:
        layerService.getIntersectionFiles() >> [cl1918: gridAsClasses('cl1918', [1: 'Rainforest', 2: 'Desert', 3: 'Dry rainforest'])]
        result*.name.sort() == ['Dry rainforest', 'Rainforest']
        result*.pid.sort() == ['1918:1', '1918:3']
        result.every { it.fid == 'cl1918' }
    }

    def "classes of excluded fields are not added"() {
        when:
        List<SearchObject> result = service.findByCriteria('rain', 0, 10, [], ['cl1918'])

        then:
        layerService.getIntersectionFiles() >> [cl1918: gridAsClasses('cl1918', [1: 'Rainforest'])]
        result.empty
    }

    @PendingFeature(reason = 'addGridClassesToSearch adds every matching grid class when there is space left, ignoring limit')
    def "no more than limit results are returned"() {
        given:
        jdbc.results << [row('1', 'Rainbow Beach')]

        when:
        List<SearchObject> result = service.findByCriteria('rain', 0, 3)

        then:
        layerService.getIntersectionFiles() >> [cl1918: gridAsClasses('cl1918', (1..10).collectEntries { [it, 'Rainforest ' + it] })]
        result.size() == 3
        result[0].name == 'Rainbow Beach'
    }

    def "a page after the last result is empty"() {
        expect:
        service.findByCriteria('zzz', 100, 10).empty
    }
}
