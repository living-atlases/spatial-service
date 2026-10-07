package au.org.ala.spatial

import au.org.ala.spatial.dto.Tabulation
import grails.testing.gorm.DataTest
import grails.testing.web.controllers.ControllerUnitTest
import spock.lang.Specification

/**
 * Tabulations used by spatial-hub: /tabulations, /tabulation/{func}/{fid1}/{fid2}/tabulation.{type},
 * /tabulation/{fid}/{pid}
 */
class TabulationControllerSpec extends Specification implements ControllerUnitTest<TabulationController>, DataTest {

    TabulationService tabulationService = Mock()
    FieldService fieldService = Mock()
    SpatialObjectsService spatialObjectsService = Mock()

    @Override
    Set<String> getIncludePlugins() {
        ['core', 'eventBus', 'converters'].toSet()
    }

    def setupSpec() {
        mockDomain(Fields)
    }

    def setup() {
        BootStrapMarshallers.register()
        controller.tabulationService = tabulationService
        controller.fieldService = fieldService
        controller.spatialObjectsService = spatialObjectsService
        controller.spatialConfig = new SpatialConfig(controller: new SpatialConfig.DotTimeout(timeout: 5000))
    }

    private void route(String func, String fid1, String fid2, String type) {
        params.func1 = func
        params.fid1 = fid1
        params.fid2 = fid2
        params.type = type
    }

    def "index as JSON lists the available tabulations"() {
        given:
        params.format = 'json'

        when:
        controller.index()

        then:
        1 * tabulationService.listTabulations() >> [new Tabulation(fid1: 'cl1', fid2: 'cl2', name1: 'a', name2: 'b')]
        response.json*.fid1 == ['cl1']
    }

    def "data returns the tabulation rows"() {
        given:
        route('data', 'cl1', 'cl2', 'json')

        when:
        controller.show()

        then:
        1 * tabulationService.getTabulation('cl1', 'cl2', '') >> [new Tabulation(pid1: '1', pid2: '2', area: 3.5d)]
        response.json[0].area == 3.5
    }

    def "a numeric wkt is the pid of the area to tabulate within"() {
        given:
        route('data', 'cl1', 'cl2', 'json')
        params.wkt = '123'

        when:
        controller.show()

        then:
        1 * spatialObjectsService.getObjectsGeometryById('123', 'wkt') >> 'POLYGON((0 0,1 0,1 1,0 1,0 0))'
        1 * tabulationService.getTabulation('cl1', 'cl2', 'POLYGON((0 0,1 0,1 1,0 1,0 0))') >> []
    }

    def "csv is written as comma separated values"() {
        given:
        route('area', 'cl1', 'cl2', 'csv')

        when:
        controller.show()

        then:
        1 * tabulationService.generateTabulationCSVHTML('cl1', 'cl2', '', 'area', 'csv') >> '"Area",a\n"x",1.0\n'
        response.contentType.startsWith('text/comma-separated-values')
        response.contentAsString == '"Area",a\n"x",1.0\n'
    }

    def "json is written as application/json in UTF-8"() {
        given:
        route('occurrences', 'cl1', 'cl2', 'json')

        when:
        controller.show()

        then:
        1 * tabulationService.generateTabulationCSVHTML('cl1', 'cl2', '', 'occurrences', 'json') >> '{"Île":{"a":1}}'
        response.contentType.startsWith('application/json')
        new String(response.contentAsByteArray, 'UTF-8') == '{"Île":{"a":1}}'
    }

    def "html is the csv shown in a page with the field names"() {
        given:
        route('species', 'cl1', 'cl2', 'html')

        when:
        controller.show()

        then:
        1 * tabulationService.generateTabulationCSVHTML('cl1', 'cl2', '', 'species', 'csv') >> '"Number of species","A"\n"X",2\n'
        1 * fieldService.getFieldById('cl1') >> new Fields(name: 'States')
        1 * fieldService.getFieldById('cl2') >> new Fields(name: 'Vegetation')
        view == '/tabulation/show.gsp'
        model.label == 'Tabulation for "States" and "Vegetation" - Number of species'
        model.data*.toList() == [['Number of species', 'A'], ['X', '2']]
    }

    def "single tabulates the objects of a field within an object"() {
        given:
        params.fid = 'cl22'
        params.pid = '123.json'

        when:
        controller.single()

        then:
        1 * spatialObjectsService.getObjectsGeometryById('123', 'wkt') >> 'POLYGON((0 0,1 0,1 1,0 1,0 0))'
        1 * tabulationService.getTabulationSingle('cl22', 'POLYGON((0 0,1 0,1 1,0 1,0 0))') >> [new Tabulation(pid1: '1', area: 2d)]
        response.json[0].pid1 == '1'
    }

    def "single without results is a 404"() {
        given:
        params.fid = 'single'
        params.pid = 'cl22'
        params.wkt = 'POLYGON((0 0,1 0,1 1,0 1,0 0))'

        when:
        controller.single()

        then:
        0 * spatialObjectsService._
        1 * tabulationService.getTabulationSingle('cl22', 'POLYGON((0 0,1 0,1 1,0 1,0 0))') >> null
        response.status == 404
    }
}
