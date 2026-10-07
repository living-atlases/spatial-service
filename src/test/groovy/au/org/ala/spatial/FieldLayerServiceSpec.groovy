package au.org.ala.spatial

import au.org.ala.spatial.dto.IntersectionFile
import grails.testing.gorm.DataTest
import spock.lang.PendingFeature
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FieldService and LayerService helpers that do not need PostGIS.
 */
class FieldLayerServiceSpec extends Specification implements DataTest {

    @TempDir
    File dataDir

    FakeJdbc jdbc = new FakeJdbc()
    FieldService fieldService
    LayerService layerService

    def setupSpec() {
        mockDomains(Fields, Layers)
    }

    def setup() {
        SpatialConfig config = new SpatialConfig(
                geoserver: new SpatialConfig.DotGeoserver(url: 'https://spatial.example.org/geoserver'),
                data: new SpatialConfig.DotDir(dir: dataDir.path))
        fieldService = new FieldService(spatialConfig: config, dataSource: jdbc.dataSource)
        layerService = new LayerService(spatialConfig: config, dataSource: jdbc.dataSource, fieldService: fieldService)
        fieldService.layerService = layerService
        layerService.intersectionFiles.put('el1',
                new IntersectionFile('Temperature', '/x', null, 'temp', 'el1', 'Temperature', '1', 'e', null))
    }

    private static void field(String id, String spid) {
        new Fields(name: id, spid: spid, enabled: true).tap { it.id = id }.save(flush: true, failOnError: true)
    }

    def "updateDisplayPath replaces the geoserver placeholder"() {
        given:
        Layers layer = new Layers(displaypath: '<COMMON_GEOSERVER_URL>/gwc/service/wms?layers=ALA:aus1')

        when:
        fieldService.updateDisplayPath(layer)

        then:
        layer.displaypath == 'https://spatial.example.org/geoserver/gwc/service/wms?layers=ALA:aus1'
    }

    def "updateDisplayPath makes a relative path absolute and keeps other urls"() {
        given:
        def relative = new Layers(displaypath: '/wms?layers=ALA:aus1')
        def absolute = new Layers(displaypath: 'https://other.example.org/wms')

        when:
        fieldService.updateDisplayPaths([relative, absolute, null])

        then:
        relative.displaypath == 'https://spatial.example.org/geoserver/wms?layers=ALA:aus1'
        absolute.displaypath == 'https://other.example.org/wms'
    }

    def "the first field of a layer has no sequence number"() {
        expect:
        fieldService.calculateNextSequenceId('22') == ''
    }

    def "the next field of a layer gets the next sequence number"() {
        given:
        field('cl22', '22')
        field('cl122', '22')
        field('cl222', '22')

        expect:
        fieldService.calculateNextSequenceId('22') == 3
    }

    @PendingFeature(reason = "calculateNextSequenceId returns '' (the id of the existing default field) when the max sequence number is 0")
    def "the second field of a layer does not reuse the id of the first"() {
        given:
        field('cl22', '22')

        expect:
        fieldService.calculateNextSequenceId('22') == 1
    }

    @PendingFeature(reason = "calculateNextSequenceId removes every occurrence of the layer id with replaceAll, not only the suffix")
    def "the sequence number is the part of the field id before the layer id"() {
        given:
        field('cl1', '1')
        field('cl11', '1')

        expect:
        fieldService.calculateNextSequenceId('1') == 2
    }

    def "analysis layer ids map to their files: #id"() {
        when:
        String[] info = layerService.getAnalysisLayerInfo(id)

        then:
        info[0] == gid
        info[1] == dataDir.path + '/layer/' + path
        info[2] == name

        where:
        id                  | gid     | path                                          | name
        'species_123'       | '123'   | 'maxent/123/123'                               | 'Prediction'
        'aloc_123'          | '123'   | 'aloc/123/aloc'                                | 'Classification'
        'odensity_123'      | '123'   | 'sitesbyspecies/123/occurrence_density'        | 'Occurrence Density'
        'srichness_123'     | '123'   | 'sitesbyspecies/123/species_richness'          | 'Species Richness'
        '123_odensity'      | '123'   | 'sitesbyspecies/123/occurrence_density'        | 'Occurrence Density'
        '123_srichness'     | '123'   | 'sitesbyspecies/123/species_richness'          | 'Species Richness'
        'envelope_123'      | '123'   | 'envelope/123/envelope'                        | 'Environmental Envelope'
        'gdm_el1Tran_123'   | '123'   | 'gdm/123/el1Tran'                              | 'Transformed Temperature'
        '123_el1'           | '123'   | 'gdm/123/el1Tran'                              | 'Transformed Temperature'
    }

    def "analysis layer files of the current layout are found first"() {
        given:
        new File(dataDir, 'layer').mkdirs()
        new File(dataDir, 'layer/12345_occurrence_density.grd').text = ''

        when:
        String[] info = layerService.getAnalysisLayerInfo('12345_occurrence_density')

        then:
        info == ['12345', dataDir.path + '/layer/12345_occurrence_density', 'Occurrence density'] as String[]
    }

    def "an unknown analysis layer id is null"() {
        expect:
        layerService.getAnalysisLayerInfo('cl22') == null
    }

    def "intersection files are looked up by field id"() {
        expect:
        layerService.getIntersectionFile('el1').fieldName == 'Temperature'
    }

    @PendingFeature(reason = "LayerService.getLayersByCriteria casts the ResultSet 'as Layers' instead of reading its rows")
    def "getLayersByCriteria returns the matching layers"() {
        given:
        jdbc.results << [[id: 22L, name: 'aus1', displayname: 'States', displaypath: '/wms?layers=ALA:aus1', enabled: true],
                         [id: 23L, name: 'aus2', displayname: 'LGAs', displaypath: '/wms?layers=ALA:aus2', enabled: true]]

        when:
        List<Layers> layers = layerService.getLayersByCriteria('aus')

        then:
        jdbc.lastParameters == ['%aus%'] * 4
        layers*.name == ['aus1', 'aus2']
        layers[0].displaypath == 'https://spatial.example.org/geoserver/wms?layers=ALA:aus1'
    }
}
