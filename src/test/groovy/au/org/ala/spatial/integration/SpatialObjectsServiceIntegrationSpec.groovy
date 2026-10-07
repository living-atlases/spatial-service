package au.org.ala.spatial.integration

import au.org.ala.spatial.Fields
import au.org.ala.spatial.LayerIntersectService
import au.org.ala.spatial.LayerService
import au.org.ala.spatial.Layers
import au.org.ala.spatial.SpatialObjects
import org.grails.orm.hibernate.HibernateDatastore
import au.org.ala.spatial.SpatialConfig
import au.org.ala.spatial.SpatialObjectsService
import groovy.sql.Sql
import org.locationtech.jts.io.WKTReader
import spock.lang.PendingFeature
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

/**
 * SpatialObjectsService queries (/object, /objects, /shape exports and user uploaded areas)
 * against PostGIS with the schema of docker/postgres/init_layersdb.sql.
 */
@Requires({ PostgisDatabase.available })
class SpatialObjectsServiceIntegrationSpec extends Specification {

    static final String USER_FIELD = 'cl_user'

    @Shared
    PostgisDatabase db

    @Shared
    HibernateDatastore datastore

    SpatialObjectsService service

    def setupSpec() {
        db = new PostgisDatabase()
        db.withSql { Sql sql ->
            sql.execute("""INSERT INTO fields (id, name, "desc", type, spid, sid, enabled, namesearch, "intersect")
                VALUES ('cl1', 'States', 'States and territories', 'c', '1', 'ste', true, true, true),
                       ('cl2', 'Places', 'Points of interest', 'c', '2', 'name', true, false, false),
                       ('$USER_FIELD', 'User areas', 'Uploaded areas', 'c', '3', 'name', true, false, false)""".toString())
            [
                    ['101', 'NSW', 'cl1', 'POLYGON((140 -37,140 -29,150 -29,150 -37,140 -37))'],
                    ['102', 'VIC', 'cl1', 'POLYGON((140 -39,140 -37,150 -37,150 -39,140 -39))'],
                    ['201', 'Canberra', 'cl2', 'POINT(149.13 -35.28)'],
                    ['202', 'Sydney', 'cl2', 'POINT(151.21 -33.87)'],
                    ['203', 'Melbourne', 'cl2', 'POINT(144.96 -37.81)'],
            ].each { pid, name, fid, wkt ->
                sql.execute("""INSERT INTO objects (pid, id, name, "desc", fid, the_geom, bbox, area_km)
                    VALUES (?, ?, ?, ?, ?, ST_GeomFromText(?, 4326), ST_AsText(Box2D(ST_GeomFromText(?, 4326))),
                            CASE WHEN GeometryType(ST_GeomFromText(?, 4326)) = 'POINT' THEN 0 ELSE 1000 END)""",
                        [pid, pid, name, name + ' description', fid, wkt, wkt, wkt])
            }
        }
        datastore = new HibernateDatastore(db.gormConfig('none'), SpatialObjects, Fields, Layers)
    }

    def cleanupSpec() {
        datastore?.close()
        db?.drop()
    }

    def setup() {
        service = new SpatialObjectsService()
        service.dataSource = db.dataSource
        service.spatialConfig = new SpatialConfig(userObjectsField: USER_FIELD,
                geoserver: new SpatialConfig.DotGeoserver(url: 'https://geoserver.example/geoserver'))
        service.layerService = Mock(LayerService)
        service.layerIntersectService = Mock(LayerIntersectService)
    }

    /** GORM (SpatialObjects.findAllByPid) is used by the geometry exports. */
    private <T> T inSession(Closure<T> c) {
        SpatialObjects.withNewSession { c() }
    }

    def "getObjectsById lists the objects of a field with the field name, bbox, centroid and type"() {
        when:
        def objects = service.getObjectsById('cl1', 0, -1, null)

        then:
        objects*.pid == ['101', '102']
        objects*.name == ['NSW', 'VIC']
        objects.every { it.fieldname == 'States' && it.fid == 'cl1' }
        objects[0].featureType == 'POLYGON'
        new WKTReader().read(objects[0].centroid).coordinate.x == 145d
        objects[0].wmsurl.startsWith('https://geoserver.example/geoserver')
    }

    @Unroll
    def "getObjectsById pages (start #start, pageSize #pageSize) and filters by name (#filter)"() {
        expect:
        service.getObjectsById('cl2', start, pageSize, filter)*.name == names

        where:
        start | pageSize | filter  | names
        0     | 2        | null    | ['Canberra', 'Sydney']
        1     | 2        | null    | ['Sydney', 'Melbourne']
        0     | -1       | 'MEL'   | ['Melbourne']
        0     | -1       | 'berra' | ['Canberra']
    }

    def "getObjectByPid returns one object, or null"() {
        expect:
        service.getObjectByPid('201').name == 'Canberra'
        service.getObjectByPid('201').fieldname == 'Places'
        service.getObjectByPid('999') == null
    }

    def "points get the Points WMS layer and polygons the Objects layer"() {
        expect:
        service.getObjectByPid('201').wmsurl.contains('ALA:Points')
        service.getObjectByPid('101').wmsurl.contains('ALA:Objects')
    }

    @Unroll
    def "getObjectByIdAndLocation finds the polygon of #fid at (#lng, #lat)"() {
        expect:
        service.getObjectByIdAndLocation(fid, lng, lat)?.name == name

        where:
        fid   | lng   | lat   | name
        'cl1' | 149.1 | -35.3 | 'NSW'
        'cl1' | 145.0 | -38.0 | 'VIC'
        'cl1' | 120.0 | -30.0 | null
    }

    /** Azimuth() was a PostGIS 1.x alias; PostGIS 2+ only has ST_Azimuth. */
    @PendingFeature(reason = 'getNearestObjectByIdAndLocation calls Azimuth(), which PostGIS 3 does not have')
    def "getNearestObjectByIdAndLocation orders points by distance and returns distance and bearing"() {
        when:
        def nearest = service.getNearestObjectByIdAndLocation('cl2', 2, 149.0, -35.0)

        then:
        nearest*.name == ['Canberra', 'Sydney']
        nearest[0].distance > 0 && nearest[0].distance < 50000
        nearest[0].degrees != null
    }

    def "getObjectsByIdAndArea returns the objects within a WKT area"() {
        expect:
        service.getObjectsByIdAndArea('cl2', 10, 'POLYGON((140 -39,140 -29,150 -29,150 -39,140 -39))')*.name.sort() ==
                ['Canberra', 'Melbourne']
    }

    @PendingFeature(reason = 'the SQL of getObjectsByIdAndIntersection(String, Integer, String) has "AND and"')
    def "getObjectsByIdAndIntersection returns the objects within another object"() {
        expect:
        service.getObjectsByIdAndIntersection('cl2', 10, '101')*.name == ['Canberra']
    }

    def "getObjectsWithinRadius uses kilometres"() {
        expect:
        service.getObjectsWithinRadius('cl2', -35.28, 149.13, 10)*.name == ['Canberra']
        service.getObjectsWithinRadius('cl2', -35.28, 149.13, 300)*.name.sort() == ['Canberra', 'Sydney']
    }

    def "getObjectsIntersectingWithGeometry returns the objects that intersect a WKT"() {
        expect:
        service.getObjectsIntersectingWithGeometry('cl1', 'POINT(145 -38)')*.name == ['VIC']
        service.getObjectsIntersectingWithGeometry('cl1', 'LINESTRING(145 -38,145 -30)')*.name.sort() == ['NSW', 'VIC']
    }

    def "getObjectsIntersectingWithObject returns the objects that intersect another object"() {
        expect:
        service.getObjectsIntersectingWithObject('cl2', '102')*.name == ['Melbourne']
    }

    def "intersectObject tells whether a point is in an object"() {
        expect:
        service.intersectObject('101', -35.3, 149.1)?.pid == '101'
        service.intersectObject('101', -38.0, 145.0) == null
    }

    @Unroll
    def "getObjectsGeometryById returns the geometry as #type"() {
        when:
        String out = inSession { service.getObjectsGeometryById('101', type) }

        then:
        out.contains(expected)

        where:
        type      | expected
        'wkt'     | 'POLYGON ((140 -37, 140 -29'
        'geojson' | '"type":"Polygon"'
        'kml'     | 'coordinates>140.0,-37.0'
    }

    def "wkt streams the geometry of an object"() {
        given:
        def out = new ByteArrayOutputStream()

        when:
        inSession { service.wkt('201', out) }

        then:
        new WKTReader().read(out.toString()).equalsTopo(new WKTReader().read('POINT(149.13 -35.28)'))
    }

    def "a user area can be created, read, updated and deleted"() {
        when: 'created'
        String pid = service.createUserUploadedObject('POLYGON((150 -35,150 -34,151 -34,151 -35,150 -35))', 'My area', 'desc', 'user1')
        def created = service.getObjectByPid(pid)

        then:
        created.name == 'My area'
        created.fid == USER_FIELD
        created.area_km > 10000 && created.area_km < 11000
        new WKTReader().read(created.bbox).envelopeInternal == new WKTReader().read('POLYGON((150 -35,150 -34,151 -34,151 -35,150 -35))').envelopeInternal
        service.getUserUploadedObjectUserId(pid as int) == 'user1'

        when: 'updated'
        service.updateUserUploadedObject(pid as int, 'POLYGON((150 -35,150 -33,152 -33,152 -35,150 -35))', 'Renamed', 'desc 2', 'user2')

        then:
        service.getObjectByPid(pid).name == 'Renamed'
        service.getObjectByPid(pid).area_km > 40000
        service.getUserUploadedObjectUserId(pid as int) == 'user2'

        when: 'deleted'
        boolean deleted = service.deleteUserUploadedObject(pid as int)

        then:
        deleted
        service.getObjectByPid(pid) == null
        service.getUserUploadedObjectUserId(pid as int) == null
    }

    /** Sql.execute returns whether the first result is a ResultSet, which is false for an UPDATE. */
    @PendingFeature(reason = 'updateUserUploadedObject returns the result of Sql.execute, always false for an UPDATE')
    def "updateUserUploadedObject reports success"() {
        given:
        String pid = service.createUserUploadedObject('POLYGON((150 -35,150 -34,151 -34,151 -35,150 -35))', 'A', 'd', 'user1')

        expect:
        service.updateUserUploadedObject(pid as int, 'POLYGON((150 -35,150 -33,152 -33,152 -35,150 -35))', 'B', 'd', 'user1')
    }

    def "an object that was not uploaded by a user cannot be updated or deleted"() {
        when:
        service.deleteUserUploadedObject(101)

        then:
        thrown(IllegalArgumentException)
        service.getObjectByPid('101') != null
    }

    def "an invalid WKT is rejected when creating a user area"() {
        when:
        service.createUserUploadedObject('POLYGON((150 -35,150 -34', 'Bad', 'd', 'user1')

        then:
        thrown(Exception)
    }
}
