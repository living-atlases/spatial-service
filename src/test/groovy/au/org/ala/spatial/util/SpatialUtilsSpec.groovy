package au.org.ala.spatial.util

import org.geotools.data.DefaultTransaction
import org.geotools.data.Transaction
import org.geotools.data.shapefile.ShapefileDataStore
import org.geotools.data.shapefile.ShapefileDataStoreFactory
import org.geotools.data.simple.SimpleFeatureStore
import org.geotools.feature.DefaultFeatureCollection
import org.geotools.feature.simple.SimpleFeatureBuilder
import org.geotools.feature.simple.SimpleFeatureTypeBuilder
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.geotools.referencing.crs.DefaultGeographicCRS
import org.locationtech.jts.algorithm.distance.DiscreteHausdorffDistance
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.io.WKTReader
import org.opengis.feature.simple.SimpleFeatureType
import org.opengis.referencing.crs.CoordinateReferenceSystem
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

/**
 * SpatialUtils helpers other than the area calculations (see SpatialUtilsAreaSpec): web mercator
 * pixel and metre conversions, KML parsing and reading uploaded shapefiles as WKT.
 */
class SpatialUtilsSpec extends Specification {

    /** Pixels per degree of longitude for a 256 px tile world at the given zoom. */
    static double pxPerDegree(int zoom) {
        256 * Math.pow(2, zoom) / 360
    }

    @TempDir
    File tmp

    WKTReader reader = new WKTReader()

    // ---- pixel / metre conversions ----

    def "convertLngToPixel maps -180, 0 and 180 to the edges and the centre of the zoom 21 world"() {
        expect:
        SpatialUtils.convertLngToPixel(-180) == 0
        SpatialUtils.convertLngToPixel(0) == 268435456
        SpatialUtils.convertLngToPixel(180) == 2 * 268435456
        SpatialUtils.convertLatToPixel(0) == 268435456
    }

    @Unroll
    def "longitude #lng and latitude #lat survive a round trip through pixels"() {
        expect:
        Math.abs(SpatialUtils.convertPixelToLng(SpatialUtils.convertLngToPixel(lng)) - lng) < 1e-5
        Math.abs(SpatialUtils.convertPixelToLat(SpatialUtils.convertLatToPixel(lat)) - lat) < 1e-5

        where:
        lng    | lat
        0      | 0
        149.13 | -35.28
        -70.5  | 60.1
        179.9  | -84
    }

    def "convertLatToPixel increases to the south (screen coordinates)"() {
        expect:
        SpatialUtils.convertLatToPixel(-35) > SpatialUtils.convertLatToPixel(0)
        SpatialUtils.convertLatToPixel(35) < SpatialUtils.convertLatToPixel(0)
        SpatialUtils.convertLatToPixel(35) + SpatialUtils.convertLatToPixel(-35) == 2 * 268435456
    }

    @Unroll
    def "planeDistance between #lng1 and #lng2 on the equator at zoom #zoom is the pixel distance of a 256 px tile world"() {
        expect:
        SpatialUtils.planeDistance(0, lng1, 0, lng2, zoom) == (int) Math.floor(Math.abs(lng2 - lng1) * pxPerDegree(zoom))

        where:
        lng1 | lng2 | zoom
        0    | 1    | 10
        0    | 10   | 5
        -20  | 30   | 3
        100  | 101  | 21
    }

    def "convertMetersToPixels is the inverse of convertPixelsToMeters"() {
        expect:
        Math.abs(SpatialUtils.convertMetersToPixels(SpatialUtils.convertPixelsToMeters(37, -35, 9), -35, 9) - 37) < 1e-9
    }

    def "at zoom 0 one pixel on the equator is 156543.03 m"() {
        expect:
        Math.abs(SpatialUtils.convertPixelsToMeters(1, 0, 0) - 156543.03392) < 1e-3
        Math.abs(SpatialUtils.convertPixelsToMeters(1, 60, 0) - 156543.03392 / 2) < 1e-3
    }

    @Unroll
    def "lng/lat (#lng, #lat) <-> web mercator metres agrees with GeoTools EPSG:3857"() {
        given:
        def transform = CRS.findMathTransform(DefaultGeographicCRS.WGS84, CRS.decode('EPSG:3857', true), true)
        double[] expected = new double[2]
        transform.transform([lng, lat] as double[], 0, expected, 0, 1)

        expect:
        Math.abs(SpatialUtils.convertLngToMeters(lng) - expected[0]) < 0.01
        Math.abs(SpatialUtils.convertLatToMeters(lat) - expected[1]) < 0.01
        Math.abs(SpatialUtils.convertMetersToLng(expected[0]) - lng) < 1e-8
        Math.abs(SpatialUtils.convertMetersToLat(expected[1]) - lat) < 1e-8

        where:
        lng    | lat
        0      | 0
        149.13 | -35.28
        -120   | 45
        180    | 85
    }

    // ---- convertGeoToPoints ----

    def "convertGeoToPoints converts a POLYGON to 'lng:lat' pairs separated by commas"() {
        expect:
        SpatialUtils.convertGeoToPoints('POLYGON((1 2,3 4,5 6,1 2))') == '1:2,3:4,5:6,1:2'
    }

    def "convertGeoToPoints separates the polygons of a MULTIPOLYGON with 'S'"() {
        expect:
        SpatialUtils.convertGeoToPoints('MULTIPOLYGON(((1 2,3 4,5 6,1 2)),((7 8,9 10,11 12,7 8)))') == '1:2,3:4,5:6,1:2S7:8,9:10,11:12,7:8'
    }

    def "convertGeoToPoints returns an empty string for null"() {
        expect:
        SpatialUtils.convertGeoToPoints(null) == ''
    }

    // ---- getKMLPolygonAsWKT ----

    static String kml(String body) {
        """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
${body}
</kml>"""
    }

    static final String PLACEMARK = '''<Placemark><name>area</name><Polygon><outerBoundaryIs><LinearRing>
<coordinates>110,-40 110,-30 120,-30 120,-40 110,-40</coordinates>
</LinearRing></outerBoundaryIs></Polygon></Placemark>'''

    static final String EXPECTED_KML_WKT = 'POLYGON((110 -40,110 -30,120 -30,120 -40,110 -40))'

    @Unroll
    def "getKMLPolygonAsWKT reads a polygon placemark #where"() {
        when:
        String wkt = SpatialUtils.getKMLPolygonAsWKT(kml(body))

        then:
        reader.read(wkt).equalsExact(reader.read(EXPECTED_KML_WKT))
        !wkt.contains(', ')
        !wkt.contains(' (')

        where:
        where                     | body
        'in a Document'           | "<Document>${PLACEMARK}</Document>"
        'in a Folder'             | "<Folder>${PLACEMARK}</Folder>"
        'in a Document/Folder'    | "<Document><Folder>${PLACEMARK}</Folder></Document>"
    }

    /**
     * A kml element may hold a single Placemark without a Document or Folder. The parser then
     * returns the placemark itself, which has no "Feature" attribute, and
     * placemarks.isEmpty() throws a NullPointerException (the upload fails with "KML parsing failure").
     */
    def "getKMLPolygonAsWKT reads a polygon placemark that is not in a Document or Folder"() {
        expect:
        reader.read(SpatialUtils.getKMLPolygonAsWKT(kml(PLACEMARK))).equalsExact(reader.read(EXPECTED_KML_WKT))
    }

    def "getKMLPolygonAsWKT returns an empty string when there is no geometry"() {
        expect:
        SpatialUtils.getKMLPolygonAsWKT(kml('<Document><name>empty</name><Placemark><name>no geometry</name></Placemark></Document>')) == ''
    }

    // ---- getShapeFileFeaturesAsWkt ----

    private File shapefileDir(String name, String wkt) {
        File dir = new File(tmp, name)
        dir.mkdirs()
        SpatialConversionUtils.saveShapefile(new File(dir, name + '.shp'), wkt, 'n', 'd')
        dir
    }

    static final String TWO_POLYGONS = 'GEOMETRYCOLLECTION(POLYGON((110 -40,110 -30,120 -30,120 -40,110 -40)),POLYGON((130 -40,130 -30,140 -30,140 -40,130 -40)))'

    def "getShapeFileFeaturesAsWkt('all') merges every feature"() {
        given:
        File dir = shapefileDir('all', TWO_POLYGONS)

        expect:
        reader.read(SpatialUtils.getShapeFileFeaturesAsWkt(dir, 'all')).equalsTopo(reader.read(TWO_POLYGONS).union())
    }

    def "getShapeFileFeaturesAsWkt with a list of indexes returns only those features"() {
        given:
        File dir = shapefileDir('some', TWO_POLYGONS)
        Geometry gc = reader.read(TWO_POLYGONS)

        when:
        Geometry first = reader.read(SpatialUtils.getShapeFileFeaturesAsWkt(dir, '0'))
        Geometry second = reader.read(SpatialUtils.getShapeFileFeaturesAsWkt(dir, '1'))
        Geometry both = reader.read(SpatialUtils.getShapeFileFeaturesAsWkt(dir, '0,1'))

        then:
        [first, second].every { it instanceof Polygon || it.numGeometries == 1 }
        // the shapefile does not have to keep the order of the GEOMETRYCOLLECTION
        (first.equalsTopo(gc.getGeometryN(0)) && second.equalsTopo(gc.getGeometryN(1))) ||
                (first.equalsTopo(gc.getGeometryN(1)) && second.equalsTopo(gc.getGeometryN(0)))
        both.equalsTopo(gc.union())
    }

    def "getShapeFileFeaturesAsWkt rejects a directory that does not exist"() {
        when:
        SpatialUtils.getShapeFileFeaturesAsWkt(new File(tmp, 'nope'), 'all')

        then:
        thrown(IllegalArgumentException)
    }

    /** Writes a polygon shapefile in the given CRS (with its .prj). */
    private File projectedShapefile(String name, CoordinateReferenceSystem crs, Geometry g) {
        File dir = new File(tmp, name)
        dir.mkdirs()
        File shp = new File(dir, name + '.shp')

        SimpleFeatureTypeBuilder b = new SimpleFeatureTypeBuilder()
        b.setName(name)
        b.setCRS(crs)
        b.add('the_geom', Polygon)
        SimpleFeatureType type = b.buildFeatureType()

        ShapefileDataStore ds = (ShapefileDataStore) new ShapefileDataStoreFactory().createNewDataStore([url: shp.toURI().toURL()])
        ds.createSchema(type)
        Transaction t = new DefaultTransaction('create')
        SimpleFeatureStore store = (SimpleFeatureStore) ds.getFeatureSource(ds.typeNames[0])
        store.transaction = t
        DefaultFeatureCollection c = new DefaultFeatureCollection()
        SimpleFeatureBuilder fb = new SimpleFeatureBuilder(type)
        fb.add(g)
        c.add(fb.buildFeature(null))
        store.addFeatures(c)
        t.commit()
        t.close()
        ds.dispose()
        dir
    }

    def "an uploaded shapefile in web mercator (EPSG:3857) is returned in longitude/latitude"() {
        given:
        Geometry lonLat = reader.read('POLYGON((149 -35,149 -34,150 -34,150 -35,149 -35))')
        CoordinateReferenceSystem mercator = CRS.decode('EPSG:3857', true)
        Geometry metres = JTS.transform(lonLat, CRS.findMathTransform(DefaultGeographicCRS.WGS84, mercator, true))
        File dir = projectedShapefile('merc', mercator, metres)

        when:
        Geometry all = reader.read(SpatialUtils.getShapeFileFeaturesAsWkt(dir, 'all'))
        Geometry one = reader.read(SpatialConversionUtils.getShapeFileFeatureAsWKT(dir, 0))

        then:
        DiscreteHausdorffDistance.distance(all, lonLat) < 1e-6
        DiscreteHausdorffDistance.distance(one, lonLat) < 1e-6
    }

    def "an ArcGIS Online export (WGS_1984_Web_Mercator_Auxiliary_Sphere .prj) is returned in longitude/latitude"() {
        given:
        Geometry lonLat = reader.read('POLYGON((149 -35,149 -34,150 -34,150 -35,149 -35))')
        CoordinateReferenceSystem mercator = CRS.decode('EPSG:3857', true)
        Geometry metres = JTS.transform(lonLat, CRS.findMathTransform(DefaultGeographicCRS.WGS84, mercator, true))
        File dir = projectedShapefile('arcgis', mercator, metres)
        new File(dir, 'arcgis.prj').text = 'PROJCS["WGS_1984_Web_Mercator_Auxiliary_Sphere",GEOGCS["GCS_WGS_1984",DATUM["D_WGS_1984",SPHEROID["WGS_1984",6378137.0,298.257223563]],PRIMEM["Greenwich",0.0],UNIT["Degree",0.0174532925199433]],PROJECTION["Mercator_Auxiliary_Sphere"],PARAMETER["False_Easting",0.0],PARAMETER["False_Northing",0.0],PARAMETER["Central_Meridian",0.0],PARAMETER["Standard_Parallel_1",0.0],PARAMETER["Auxiliary_Sphere_Type",0.0],UNIT["Meter",1.0]]'

        when:
        Geometry all = reader.read(SpatialUtils.getShapeFileFeaturesAsWkt(dir, 'all'))
        Geometry one = reader.read(SpatialConversionUtils.getShapeFileFeatureAsWKT(dir, 0))

        then:
        DiscreteHausdorffDistance.distance(all, lonLat) < 1e-6
        DiscreteHausdorffDistance.distance(one, lonLat) < 1e-6
    }
}
