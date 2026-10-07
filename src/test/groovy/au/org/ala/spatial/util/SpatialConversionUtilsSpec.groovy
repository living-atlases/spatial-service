package au.org.ala.spatial.util

import org.geotools.data.FileDataStore
import org.geotools.data.FileDataStoreFinder
import org.geotools.data.simple.SimpleFeatureIterator
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.MultiLineString
import org.locationtech.jts.geom.MultiPoint
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.io.WKTReader
import org.opengis.feature.simple.SimpleFeature
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

import java.util.zip.ZipFile

/**
 * Conversions between WKT, GeoJSON and shapefiles used by the shape upload/download endpoints,
 * object downloads (SpatialObjectsService) and AOO/EOO (createCircleJs).
 *
 * Geometries are compared with JTS (WKTReader + equalsTopo) and shapefiles are read back with
 * GeoTools, so the expectations do not depend on the code under test.
 */
class SpatialConversionUtilsSpec extends Specification {

    static final double R = 6378137

    @TempDir
    File tmp

    WKTReader reader = new WKTReader()

    Geometry wkt(String s) {
        reader.read(s)
    }

    /** Reads every feature of a shapefile as [geometry, name, desc]. */
    static List<Map> readShapefile(File shp) {
        FileDataStore store = FileDataStoreFinder.getDataStore(shp)
        try {
            SimpleFeatureIterator it = store.featureSource.features.features()
            List<Map> result = []
            try {
                while (it.hasNext()) {
                    SimpleFeature f = it.next()
                    result << [geom: f.defaultGeometry as Geometry, name: f.getAttribute('name'), desc: f.getAttribute('desc')]
                }
            } finally {
                it.close()
            }
            result
        } finally {
            store.dispose()
        }
    }

    /** Great circle (haversine) distance in metres on the sphere used by createCircleJs. */
    static double haversine(double lon1, double lat1, double lon2, double lat2) {
        double dLat = Math.toRadians(lat2 - lat1)
        double dLon = Math.toRadians(lon2 - lon1)
        double a = Math.sin(dLat / 2) ** 2 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) ** 2
        return 2 * R * Math.asin(Math.sqrt(a))
    }

    // ---- getGeometryCollectionParts ----

    def "getGeometryCollectionParts splits the top level geometries of a GEOMETRYCOLLECTION"() {
        given:
        String gc = 'GEOMETRYCOLLECTION(POLYGON((0 0,0 1,1 1,1 0,0 0)),POINT(5 5),MULTIPOLYGON(((10 10,10 11,11 11,10 10)),((20 20,20 21,21 21,20 20))),LINESTRING(1 1,2 2))'
        Geometry expected = wkt(gc)

        when:
        List<String> parts = SpatialConversionUtils.getGeometryCollectionParts(gc)

        then:
        parts.size() == expected.numGeometries
        (0..<parts.size()).every { wkt(parts[it]).equalsExact(expected.getGeometryN(it)) }
    }

    def "getGeometryCollectionParts returns the only geometry of a collection with one part"() {
        expect:
        SpatialConversionUtils.getGeometryCollectionParts('GEOMETRYCOLLECTION(POLYGON((0 0,0 1,1 1,0 0)))') == ['POLYGON((0 0,0 1,1 1,0 0))']
    }

    def "getGeometryCollectionParts keeps a polygon with holes in one part"() {
        given:
        String withHole = 'POLYGON((0 0,0 10,10 10,10 0,0 0),(2 2,2 3,3 3,3 2,2 2))'

        expect:
        SpatialConversionUtils.getGeometryCollectionParts("GEOMETRYCOLLECTION($withHole,POINT(1 1))") == [withHole, 'POINT(1 1)']
    }

    @Unroll
    def "getGeometryCollectionParts rejects '#input'"() {
        when:
        SpatialConversionUtils.getGeometryCollectionParts(input)

        then:
        thrown(IllegalArgumentException)

        where:
        input << ['POLYGON((0 0,0 1,1 1,0 0))', 'GEOMETRYCOLLECTION()', 'GEOMETRYCOLLECTION EMPTY']
    }

    // ---- isWKTValid / geoJsonToWkt ----

    @Unroll
    def "isWKTValid('#input') is #valid"() {
        expect:
        SpatialConversionUtils.isWKTValid(input) == valid

        where:
        input                                     | valid
        'POLYGON((0 0,0 1,1 1,1 0,0 0))'          | true
        'POINT(1 2)'                              | true
        'POLYGON((0 0,1 1,0 1,1 0,0 0))'          | false // self intersecting bow tie
        'POLYGON((0 0,0 1,1 1'                    | false // truncated
        'not wkt'                                 | false
    }

    def "geoJsonToWkt converts a GeoJSON polygon to the same geometry"() {
        given:
        String geoJson = '{"type":"Polygon","coordinates":[[[100.0,-10.0],[101.0,-10.0],[101.0,-11.0],[100.0,-11.0],[100.0,-10.0]]]}'

        expect:
        wkt(SpatialConversionUtils.geoJsonToWkt(geoJson)).equalsTopo(wkt('POLYGON((100 -10,101 -10,101 -11,100 -11,100 -10))'))
    }

    def "geoJsonToWkt returns null for an invalid (self intersecting) polygon"() {
        expect:
        SpatialConversionUtils.geoJsonToWkt('{"type":"Polygon","coordinates":[[[0,0],[1,1],[0,1],[1,0],[0,0]]]}') == null
    }

    // ---- createFeatureType ----

    @Unroll
    def "createFeatureType('#type') has a #binding.simpleName geometry and name/desc attributes"() {
        when:
        def ft = SpatialConversionUtils.createFeatureType(type)

        then:
        ft.geometryDescriptor.type.binding == binding
        ft.getDescriptor('name').type.binding == String
        ft.getDescriptor('desc').type.binding == String
        ft.coordinateReferenceSystem != null

        where:
        type                 | binding
        'POLYGON'            | Polygon
        'MULTIPOLYGON'       | MultiPolygon
        'GEOMETRYCOLLECTION' | MultiPolygon
        'POINT'              | Point
        'MULTIPOINT'         | MultiPoint
        'LINESTRING'         | LineString
        'MULTILINESTRING'    | MultiLineString
        'point'              | Point
    }

    // ---- saveShapefile / buildZippedShapeFile (#237) ----

    @Unroll
    def "saveShapefile writes a #type that reads back as the same geometry (#237)"() {
        given:
        File shp = new File(tmp, 'out.shp')

        when:
        File result = SpatialConversionUtils.saveShapefile(shp, wktString, 'my name', 'my desc')
        List<Map> features = readShapefile(shp)

        then:
        result == shp
        features.size() == 1
        features[0].geom.equalsTopo(wkt(wktString))
        features[0].name == 'my name'
        features[0].desc == 'my desc'

        where:
        type           | wktString
        'POLYGON'      | 'POLYGON((110 -40,110 -30,120 -30,120 -40,110 -40))'
        'MULTIPOLYGON' | 'MULTIPOLYGON(((110 -40,110 -30,120 -30,110 -40)),((130 -40,130 -30,140 -30,130 -40)))'
        'POINT'        | 'POINT(130 -25)'
        'LINESTRING'   | 'LINESTRING(110 -40,120 -30,130 -35)'
    }

    /**
     * saveShapefile picks the geometry type with wktString.contains(...), so "MULTIPOINT" and
     * "MULTILINESTRING" select the POINT and LINESTRING schemas, and multi geometries other than
     * MULTIPOLYGON are written one feature per part. Whatever the split, every part must be written.
     */
    @Unroll
    def "saveShapefile writes every part of a #type (#237)"() {
        given:
        File shp = new File(tmp, 'multi.shp')

        when:
        SpatialConversionUtils.saveShapefile(shp, wktString, 'n', 'd')
        List<Map> features = readShapefile(shp)
        Geometry union = features*.geom.inject { Geometry a, Geometry b -> a.union(b) }

        then:
        !features.isEmpty()
        union.equalsTopo(wkt(wktString))
        features.every { it.desc == 'd' }

        where:
        type              | wktString
        'MULTIPOINT'      | 'MULTIPOINT((130 -25),(131 -26),(140 -20))'
        'MULTILINESTRING' | 'MULTILINESTRING((110 -40,120 -30),(130 -35,135 -36))'
    }

    def "saveShapefile writes each polygon of a GEOMETRYCOLLECTION as a numbered feature"() {
        given:
        File shp = new File(tmp, 'gc.shp')
        String wktString = 'GEOMETRYCOLLECTION(POLYGON((110 -40,110 -30,120 -30,110 -40)),POLYGON((130 -40,130 -30,140 -30,130 -40)))'
        Geometry gc = wkt(wktString)

        when:
        SpatialConversionUtils.saveShapefile(shp, wktString, 'area', null)
        List<Map> features = readShapefile(shp)

        then:
        features.size() == 2
        Map byName = features.collectEntries { [(it.name): it.geom] }
        byName.keySet() == ['area 1', 'area 2'] as Set
        byName['area 1'].equalsTopo(gc.getGeometryN(0))
        byName['area 2'].equalsTopo(gc.getGeometryN(1))
    }

    def "saveShapefile still writes the feature when the description is longer than the 100 character field"() {
        given:
        File shp = new File(tmp, 'long.shp')
        String desc = (1..60).join(',')

        when:
        SpatialConversionUtils.saveShapefile(shp, 'POINT(1 2)', 'n', desc)
        List<Map> features = readShapefile(shp)

        then:
        features.size() == 1
        desc.startsWith(features[0].desc as String)
    }

    def "saveShapefile returns null for WKT that cannot be parsed"() {
        expect:
        SpatialConversionUtils.saveShapefile(new File(tmp, 'bad.shp'), 'POLYGON((1 2', 'n', 'd') == null
    }

    def "buildZippedShapeFile zips the shapefile parts, and they can be extracted and read back"() {
        given:
        String wktString = 'POLYGON((110 -40,110 -30,120 -30,120 -40,110 -40))'

        when:
        File zip = SpatialConversionUtils.buildZippedShapeFile(wktString, 'area', 'my area', 'my description')
        List<String> entries = new ZipFile(zip).withCloseable { z -> z.entries().collect { it.name } }

        then:
        zip.name == 'area.zip'
        entries.containsAll(['area.shp', 'area.shx', 'area.dbf', 'area.prj'])
        !entries.contains('area.zip')

        when:
        def pair = SpatialConversionUtils.extractZippedShapeFile(zip)
        File shp = pair.right

        then:
        shp.name == 'area.shp'
        pair.left == shp.parentFile.name

        when:
        def manifest = SpatialConversionUtils.getShapeFileManifest(shp)
        String extracted = SpatialConversionUtils.getShapeFileFeatureAsWKT(shp.parentFile, 0)

        then:
        manifest.size() == 1
        manifest[0].collectEntries { [(it.left): it.right] } == [name: 'my area', desc: 'my description']
        wkt(extracted).equalsTopo(wkt(wktString))

        cleanup:
        zip?.parentFile?.deleteDir()
        shp?.parentFile?.deleteDir()
    }

    def "getShapeFileFeatureAsWKT returns null for a feature index that does not exist"() {
        given:
        File dir = new File(tmp, 'one')
        dir.mkdirs()
        SpatialConversionUtils.saveShapefile(new File(dir, 'one.shp'), 'POINT(1 2)', 'n', 'd')

        expect:
        SpatialConversionUtils.getShapeFileFeatureAsWKT(dir, 5) == null
    }

    def "getShapeFileFeatureAsWKT rejects a directory without a .shp file"() {
        when:
        SpatialConversionUtils.getShapeFileFeatureAsWKT(new File(tmp, 'missing'), 0)

        then:
        thrown(IllegalArgumentException)
    }

    /**
     * extractZippedShapeFile throws "Invalid archive. Must contain .shp, .shx and .dbf" but catches
     * its own exception and then returns the .shp anyway, so the check has no effect.
     */
    def "extractZippedShapeFile returns null for an archive without .shx and .dbf"() {
        given:
        File zip = new File(tmp, 'only-shp.zip')
        new java.util.zip.ZipOutputStream(new FileOutputStream(zip)).withCloseable { zos ->
            zos.putNextEntry(new java.util.zip.ZipEntry('a.shp'))
            zos.write([1, 2, 3] as byte[])
            zos.closeEntry()
        }

        expect:
        SpatialConversionUtils.extractZippedShapeFile(zip) == null
    }

    // ---- createCircleJs ----

    @Unroll
    def "createCircleJs(#lon, #lat, #radius m) is a closed ring of 361 points at #radius m from the centre"() {
        when:
        Geometry circle = wkt(SpatialConversionUtils.createCircleJs(lon, lat, radius))
        def coords = circle.coordinates

        then:
        circle instanceof Polygon
        circle.isValid()
        coords.length == 361
        coords[0] == coords[360]
        coords.every { Math.abs(haversine(lon, lat, it.x, it.y) - radius) < radius * 1e-6 }
        circle.contains(circle.factory.createPoint(new org.locationtech.jts.geom.Coordinate(lon, lat)))

        where:
        lon    | lat   | radius
        0      | 0     | 1000
        149.1  | -35.3 | 10000
        130    | -12   | 50000
        -70    | 60    | 2000
    }

    def "createCircleJs starts due north of the centre"() {
        when:
        def first = wkt(SpatialConversionUtils.createCircleJs(149, -35, 10000)).coordinates[0]

        then:
        Math.abs(first.x - 149) < 1e-9
        first.y > -35
    }

    def "createCircleJs shifts a circle that crosses -180 east by 360 degrees so the ring stays continuous"() {
        when:
        Geometry circle = wkt(SpatialConversionUtils.createCircleJs(-179.95, 10, 20000))

        then:
        circle.isValid()
        circle.envelopeInternal.minX > 179
        circle.envelopeInternal.maxX > 180
        circle.envelopeInternal.width < 1
    }
}
