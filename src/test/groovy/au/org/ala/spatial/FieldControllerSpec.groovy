package au.org.ala.spatial

import grails.testing.gorm.DataTest
import grails.testing.web.controllers.ControllerUnitTest
import spock.lang.Specification

/**
 * Fields: /fields, /field/{id}, /fieldsdb, /fields/search, /fields/layers/search
 */
class FieldControllerSpec extends Specification implements ControllerUnitTest<FieldController>, DataTest {

    FieldService fieldService = Mock()

    @Override
    Set<String> getIncludePlugins() {
        ['core', 'eventBus', 'converters'].toSet()
    }

    def setupSpec() {
        mockDomains(Fields, Layers, SpatialObjects)
    }

    def setup() {
        BootStrapMarshallers.register()
        controller.fieldService = fieldService
    }

    private static Fields field(String id, Map props = [:]) {
        Fields f = new Fields([name: 'field ' + id, spid: '22', type: 'c', enabled: true] + props)
        f.id = id
        f
    }

    def "index with q searches the fields"() {
        given:
        params.q = 'states'

        when:
        controller.index()

        then:
        1 * fieldService.getFieldsByCriteria('states') >> [field('cl22')]
        0 * fieldService.getFields(_)
        response.json*.id == ['cl22']
    }

    def "an empty q searches every field"() {
        given:
        params.q = ''

        when:
        controller.index()

        then:
        1 * fieldService.getFieldsByCriteria('') >> []
        response.contentAsString == '[]'
    }

    def "fieldsdb lists the fields in the database"() {
        when:
        controller.db()

        then:
        1 * fieldService.getFieldsByDB() >> [field('cl22'), field('cl23')]
        response.json*.id == ['cl22', 'cl23']
    }

    def "show returns the field with its objects"() {
        given:
        params.id = 'cl22'
        Fields f = field('cl22')
        f.objects = [new SpatialObjects(pid: '1', name: 'ACT'), new SpatialObjects(pid: '2', name: 'NSW')]

        when:
        controller.show()

        then:
        1 * fieldService.get('cl22', null, 0, -1) >> f
        response.status == 200
        response.json.id == 'cl22'
        response.json.objects*.name == ['ACT', 'NSW']
    }

    def "show pages and filters the objects"() {
        given:
        params.id = 'cl22'
        params.start = '100'
        params.pageSize = '50'
        params.q = 'south'

        when:
        controller.show()

        then:
        1 * fieldService.get('cl22', 'south', 100, 50) >> field('cl22')
        response.status == 200
    }

    def "show of an unknown field is a 404"() {
        given:
        params.id = 'cl0'

        when:
        controller.show()

        then:
        1 * fieldService.get('cl0', null, 0, -1) >> null
        response.status == 404
    }

    def "search finds fields by name"() {
        given:
        params.q = 'ibra'

        when:
        controller.search()

        then:
        1 * fieldService.getFieldsByCriteria('ibra') >> [field('cl1048')]
        response.json*.id == ['cl1048']
    }

    def "searchLayers finds the layers of the matching fields"() {
        given:
        params.q = 'ibra'
        Layers l = new Layers(name: 'ibra7')
        l.id = 1048L

        when:
        controller.searchLayers()

        then:
        1 * fieldService.getLayersByCriteria('ibra') >> [l]
        response.json*.name == ['ibra7']
    }
}
