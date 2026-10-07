package au.org.ala.spatial

import au.org.ala.web.AuthService
import grails.converters.JSON
import grails.testing.web.controllers.ControllerUnitTest
import org.locationtech.jts.io.WKTReader
import spock.lang.PendingFeature
import spock.lang.Specification
import spock.lang.Unroll

import java.util.zip.ZipInputStream

/**
 * Area downloads used by spatial-hub: /shape/{wkt|kml|geojson|shp}/{pid}, where pid is an object pid,
 * several pids joined with '~' or ENVELOPE{taskId}; and the WKT upload.
 */
class ShapesControllerSpec extends Specification implements ControllerUnitTest<ShapesController> {

    FakeJdbc jdbc = new FakeJdbc()
    SpatialObjectsService spatialObjectsService = Mock()
    SpatialAuthService spatialAuthService = Mock()
    AuthService authService = Mock()

    @Override
    Set<String> getIncludePlugins() {
        ['core', 'eventBus', 'converters'].toSet()
    }

    def setup() {
        controller.spatialObjectsService = spatialObjectsService
        controller.spatialAuthService = spatialAuthService
        controller.authService = authService
        controller.dataSource = jdbc.dataSource
    }

    private static List<String> zipEntryNames(byte[] bytes) {
        List<String> names = []
        new ZipInputStream(new ByteArrayInputStream(bytes)).withCloseable { zis ->
            def e
            while ((e = zis.nextEntry) != null) names << e.name
        }
        names
    }

    def "wkt of an object"() {
        given:
        params.pid = '123'

        when:
        controller.wkt()

        then:
        1 * spatialObjectsService.wkt('123', _ as OutputStream) >> { String id, OutputStream os -> os.write('POINT (1 2)'.bytes) }
        response.status == 200
        response.contentType.startsWith('application/wkt')
        response.getHeader('Content-Disposition') == 'filename="123.wkt"'
        response.contentAsString == 'POINT (1 2)'
    }

    @Unroll
    def "#action uses the filename parameter, made safe for a Content-Disposition header: '#filename'"() {
        given:
        params.pid = '123'
        params.filename = filename

        when:
        controller."$action"()

        then:
        response.getHeader('Content-Disposition') == "filename=\"${expected}.${ext}\""

        where:
        action    | ext       | filename                              | expected
        'wkt'     | 'wkt'     | 'Murray (NSW) [2]-a'                  | 'Murray_(NSW)_[2]-a'
        'wkt'     | 'wkt'     | 'a"b\r\nSet-Cookie: x=y'              | 'a_b__Set-Cookie__x_y'
        'kml'     | 'kml'     | 'Région/Île.kml'                       | 'R_gion__le_kml'
        'geojson' | 'geojson' | '../../etc/passwd'                    | '______etc_passwd'
        'shp'     | 'zip'     | 'my area'                             | 'my_area'
    }

    def "the pid is the default filename and is made safe too"() {
        given:
        params.pid = '1~2'

        when:
        controller.wkt()

        then:
        response.getHeader('Content-Disposition') == 'filename="1_2.wkt"'
    }

    def "a failure to write the wkt is a 400"() {
        given:
        params.pid = 'x'

        when:
        controller.wkt()

        then:
        1 * spatialObjectsService.wkt('x', _) >> { throw new IllegalArgumentException('bad id') }
        response.status == 400
    }

    @Unroll
    def "#action of ENVELOPE42 streams the envelope of task 42 as #type"() {
        given:
        params.pid = 'ENVELOPE42'

        when:
        controller."$action"()

        then:
        1 * spatialObjectsService.streamEnvelope(_ as OutputStream, '42', type)
        0 * spatialObjectsService.streamObjectsGeometryById(*_)
        response.status == 200
        response.contentType.startsWith(contentType)

        where:
        action    | type      | contentType
        'kml'     | 'kml'     | 'application/vnd.google-earth.kml+xml'
        'geojson' | 'geojson' | 'application/json'
        'shp'     | 'shp'     | 'application/zip'
    }

    @Unroll
    def "#action of an object streams its geometry as #type"() {
        given:
        params.pid = '0123'

        when:
        controller."$action"()

        then:
        1 * spatialObjectsService.streamObjectsGeometryById(_ as OutputStream, '123', type) >> { OutputStream os, String id, String t -> os.write('geometry'.bytes) }
        response.status == 200
        response.contentAsString == 'geometry'

        where:
        action    | type
        'kml'     | 'kml'
        'geojson' | 'geojson'
        'shp'     | 'shp'
    }

    @Unroll
    def "#action of the non numeric pid '#pid' is a 400 and does not query the database"() {
        given:
        params.pid = pid

        when:
        controller."$action"()

        then:
        response.status == 400
        jdbc.statements.empty
        0 * spatialObjectsService._

        where:
        action    | pid
        'kml'     | '1~abc'
        'geojson' | '1~~2'
        'shp'     | 'x~1'
        'kml'     | 'abc'
        'geojson' | '1.5'
    }

    def "the shp error is reported as JSON"() {
        given:
        params.pid = 'abc'

        when:
        controller.shp()

        then:
        response.status == 400
        response.contentAsString.contains('failed to get shapefile for object: abc')
    }

    def "kml of several objects is one placemark with the collected geometry"() {
        given:
        params.pid = '1~02'
        params.filename = 'two areas'
        jdbc.results << [[kml: '<MultiGeometry><Point><coordinates>1,2</coordinates></Point></MultiGeometry>']]

        when:
        controller.kml()
        def kml = new XmlSlurper().parseText(response.contentAsString)

        then:
        response.status == 200
        jdbc.lastSql.contains("where pid in ('1','2')")
        jdbc.lastSql.contains('st_askml(st_collect(geom))')
        kml.Document.Placemark.name.text() == 'two_areas'
        kml.Document.Placemark.description.text() == '1,2'
        kml.Document.Placemark.MultiGeometry.Point.coordinates.text() == '1,2'
    }

    def "geojson of several objects is the collected geometry"() {
        given:
        params.pid = '5~6'
        jdbc.results << [[geojson: '{"type":"MultiPoint","coordinates":[[1,2],[3,4]]}']]

        when:
        controller.geojson()

        then:
        response.status == 200
        jdbc.lastSql.contains("where pid in ('5','6')")
        JSON.parse(response.contentAsString).type == 'MultiPoint'
    }

    def "shp of several objects is a zipped shapefile of the collected geometry"() {
        given:
        params.pid = '5~6'
        jdbc.results << [[wkt: 'MULTIPOLYGON(((1 1,1 2,2 2,2 1,1 1)),((3 3,3 4,4 4,4 3,3 3)))']]

        when:
        controller.shp()

        then:
        response.status == 200
        jdbc.lastSql.contains("where pid in ('5','6')")
        zipEntryNames(response.contentAsByteArray).containsAll(['area.shp', 'area.shx', 'area.dbf'])
    }

    def "an uploaded WKT is saved for the signed in user, whatever user_id is sent"() {
        given:
        request.json = [wkt: 'POLYGON((1 1,2 1,2 2,1 2,1 1))', name: 'my area', description: 'd', user_id: 'someone else']

        when:
        controller.uploadWkt()

        then:
        1 * spatialAuthService.isAdmin() >> false
        1 * authService.getUserId() >> 'me'
        1 * spatialObjectsService.createUserUploadedObject(_ as String, 'my area', 'd', 'me', false) >> '77'
        JSON.parse(response.contentAsString).id == 77
    }

    def "an admin can upload a WKT for another user"() {
        given:
        request.json = [wkt: 'POLYGON((1 1,2 1,2 2,1 2,1 1))', name: 'n', user_id: 'someone else']

        when:
        controller.uploadWkt()

        then:
        _ * spatialAuthService.isAdmin() >> true
        1 * spatialObjectsService.createUserUploadedObject(_, 'n', _, 'someone else', false) >> '78'
        JSON.parse(response.contentAsString).id == 78
    }

    @PendingFeature(reason = 'fixWkt calls union() on the invalid polygon before making it valid, union (and GeomMakeValid.makeValid) throw for a self intersecting polygon')
    def "an uploaded self intersecting polygon is made valid before it is saved"() {
        given:
        request.json = [wkt: 'POLYGON((0 0,2 2,2 0,0 2,0 0))', name: 'bow tie']
        String saved = null

        when:
        controller.uploadWkt()

        then:
        1 * spatialObjectsService.createUserUploadedObject(_, 'bow tie', _, _, false) >> { args -> saved = args[0]; '1' }
        new WKTReader().read(saved).valid
        new WKTReader().read(saved).area == 2.0d
    }

    def "an invalid WKT is not saved"() {
        given:
        request.json = [wkt: 'POLYGON((0 0,1 1', name: 'x']

        when:
        controller.uploadWkt()

        then:
        0 * spatialObjectsService.createUserUploadedObject(*_)
        JSON.parse(response.contentAsString).error.startsWith('Invalid WKT')
    }

    @PendingFeature(reason = 'saveFeatureFromShapeFile renders the 400 but does not return, so it renders a second response')
    def "saving a shapefile feature without a feature index is a 400"() {
        given:
        params.shapeId = UUID.randomUUID().toString()
        request.json = [name: 'x']

        when:
        controller.saveFeatureFromShapeFile()

        then:
        response.status == 400
        response.contentAsString == 'Feature Index is not provided'
        0 * spatialObjectsService.createUserUploadedObject(*_)
    }
}
