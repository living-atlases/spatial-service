package au.org.ala.spatial

import au.org.ala.spatial.dto.LayerFilter
import grails.testing.gorm.DataTest
import grails.testing.web.controllers.ControllerUnitTest
import spock.lang.PendingFeature
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Objects (areas) of contextual layers: /object/{pid}, /objects/{fid}, /objects/{fid}/{lat}/{lng},
 * /objects/inarea/{fid}, /object/intersect/{pid}/{lat}/{lng}
 */
class ObjectControllerSpec extends Specification implements ControllerUnitTest<ObjectController>, DataTest {

    FieldService fieldService = Mock()
    SpatialObjectsService spatialObjectsService = Mock()

    @Override
    Set<String> getIncludePlugins() {
        ['core', 'eventBus', 'converters'].toSet()
    }

    def setupSpec() {
        mockDomains(Task, InputParameter, OutputParameter, Fields, SpatialObjects)
    }

    def setup() {
        BootStrapMarshallers.register()
        controller.fieldService = fieldService
        controller.spatialObjectsService = spatialObjectsService
    }

    private static SpatialObjects object(String pid, String name = 'area ' + pid) {
        new SpatialObjects(pid: pid, name: name, fid: 'cl22')
    }

    def "show returns the object"() {
        given:
        params.pid = '123'

        when:
        controller.show()

        then:
        1 * spatialObjectsService.getObjectByPid('123') >> object('123', 'Canberra')
        response.status == 200
        response.json.name == 'Canberra'
    }

    def "show of an unknown object is a 404"() {
        given:
        params.pid = '999'

        when:
        controller.show()

        then:
        1 * spatialObjectsService.getObjectByPid('999') >> null
        response.status == 404
    }

    def "show of ENVELOPE{taskId} returns the area output of the envelope task"() {
        given:
        Task task = new Task(name: 'Envelope', status: 4)
        task.addToOutput(new OutputParameter(name: 'files', file: '["envelope.shp"]'))
        task.addToOutput(new OutputParameter(name: 'area', file: '{"name":"envelope","area_km":12.5}'))
        task.save(flush: true, failOnError: true)
        params.pid = 'ENVELOPE' + task.id

        when:
        controller.show()

        then:
        0 * spatialObjectsService.getObjectByPid(_)
        response.status == 200
        response.json.name == 'envelope'
        response.json.area_km == 12.5
    }

    def "show of an envelope task without an area is a 404"() {
        given:
        Task task = new Task(name: 'Envelope', status: 3).save(flush: true, failOnError: true)
        params.pid = 'ENVELOPE' + task.id

        when:
        controller.show()

        then:
        response.status == 404
    }

    @PendingFeature(reason = 'getEnvelope does not check that the task exists: NullPointerException on task.output')
    def "show of an unknown envelope task is a 404"() {
        given:
        params.pid = 'ENVELOPE987654'

        when:
        controller.show()

        then:
        response.status == 404
    }

    def "fieldObjects pages the objects of a field"() {
        given:
        params.fid = 'cl22'
        params.start = '10'
        params.pageSize = '5'

        when:
        controller.fieldObjects()

        then:
        1 * spatialObjectsService.getObjectsById('cl22', 10, 5, null) >> [object('1'), object('2')]
        response.json*.pid == ['1', '2']
    }

    def "fieldObjects returns every object by default"() {
        given:
        params.fid = 'cl22'

        when:
        controller.fieldObjects()

        then:
        1 * spatialObjectsService.getObjectsById('cl22', 0, -1, null) >> []
        response.contentAsString == '[]'
    }

    def "listByLocation returns the nearest objects of the field"() {
        given:
        params.fid = 'cl22'
        params.lat = '-35.3'
        params.lng = '149.1'

        when:
        controller.listByLocation()

        then:
        1 * fieldService.getFieldById('cl22', false) >> new Fields(id: 'cl22')
        1 * spatialObjectsService.getNearestObjectByIdAndLocation('cl22', 40, 149.1d, -35.3d) >> [object('1')]
        response.json*.pid == ['1']
    }

    def "listByLocation uses the limit parameter"() {
        given:
        params.fid = 'cl22'
        params.lat = '1'
        params.lng = '2'
        params.limit = '3'

        when:
        controller.listByLocation()

        then:
        1 * fieldService.getFieldById('cl22', false) >> new Fields(id: 'cl22')
        1 * spatialObjectsService.getNearestObjectByIdAndLocation('cl22', 3, 2d, 1d) >> []
    }

    def "listByLocation of an unknown field is a 404"() {
        given:
        params.fid = 'cl0'
        params.lat = '1'
        params.lng = '2'

        when:
        controller.listByLocation()

        then:
        1 * fieldService.getFieldById('cl0', false) >> null
        0 * spatialObjectsService._
        response.status == 404
    }

    @PendingFeature(reason = 'Double.parseDouble throws for a non numeric lat, the null check for the 400 is never reached')
    def "listByLocation with a non numeric lat is a 400"() {
        given:
        params.fid = 'cl22'
        params.lat = 'abc'
        params.lng = '149'

        when:
        controller.listByLocation()

        then:
        response.status == 400
        0 * spatialObjectsService._
    }

    @PendingFeature(reason = 'Double.parseDouble throws for a non numeric lng, the null check for the 400 is never reached')
    def "intersectObject with a non numeric lng is a 400"() {
        given:
        params.pid = '123'
        params.lat = '-35'
        params.lng = 'east'

        when:
        controller.intersectObject()

        then:
        response.status == 400
        0 * spatialObjectsService._
    }

    def "intersectObject returns the object at the point"() {
        given:
        params.pid = '123'
        params.lat = '-35.3'
        params.lng = '149.1'

        when:
        controller.intersectObject()

        then:
        1 * spatialObjectsService.intersectObject('123', -35.3d, 149.1d) >> object('123')
        response.json.pid == '123'
    }

    def "intersectObject outside the object is an empty object"() {
        given:
        params.pid = '123'
        params.lat = '0'
        params.lng = '0'

        when:
        controller.intersectObject()

        then:
        1 * spatialObjectsService.intersectObject('123', 0d, 0d) >> null
        response.status == 200
        response.contentAsString == '{}'
    }

    def "objectsInArea with a wkt returns the objects within it"() {
        given:
        params.fid = 'cl22'
        params.wkt = 'POLYGON((1 1,2 1,2 2,1 2,1 1))'
        params.limit = '5'

        when:
        controller.objectsInArea()

        then:
        1 * spatialObjectsService.getObjectsByIdAndArea('cl22', 5, 'POLYGON((1 1,2 1,2 2,1 2,1 1))') >> [object('1')]
        response.json*.pid == ['1']
    }

    def "objectsInArea with a geometry collection returns the objects within any of its parts, once"() {
        given:
        params.fid = 'cl22'
        params.wkt = 'GEOMETRYCOLLECTION(POLYGON((1 1,2 1,2 2,1 2,1 1)),POLYGON((5 5,6 5,6 6,5 6,5 5)))'
        def a = object('1')
        def b = object('2')

        when:
        controller.objectsInArea()

        then:
        2 * spatialObjectsService.getObjectsByIdAndArea('cl22', 40, { it.startsWith('POLYGON') }) >>> [[a, b], [b]]
        response.json*.pid.sort() == ['1', '2']
    }

    def "objectsInArea with an environmental envelope returns the objects in every layer filter"() {
        given:
        params.fid = 'cl22'
        params.wkt = 'ENVELOPE(el1,0,10:el2,5,6)'
        def a = object('1')
        def b = object('2')
        def c = object('3')

        when:
        controller.objectsInArea()

        then:
        1 * spatialObjectsService.getObjectsByIdAndIntersection('cl22', 40, { LayerFilter f -> f.layername == 'el1' }) >> [a, b, c]
        1 * spatialObjectsService.getObjectsByIdAndIntersection('cl22', 40, { LayerFilter f -> f.layername == 'el2' }) >> [c, a]
        response.json*.pid == ['1', '3']
    }

    @PendingFeature(reason = 'objectsInArea parses the pid as a LayerFilter (a layer named after the pid) instead of using the objects within that object')
    def "objectsInArea with a pid returns the objects within that object"() {
        given:
        params.fid = 'cl22'
        params.pid = '123'

        when:
        controller.objectsInArea()

        then:
        1 * spatialObjectsService.getObjectsByIdAndIntersection('cl22', 40, '123') >> [object('1')]
        response.json*.pid == ['1']
    }
}
