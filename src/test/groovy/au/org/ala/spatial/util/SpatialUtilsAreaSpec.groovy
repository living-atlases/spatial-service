package au.org.ala.spatial.util

import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.io.WKTReader
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Area calculations used by area reports, tabulations, AOO/EOO, field creation and grid cutting.
 *
 * The expected values come from the closed form area of a latitude band cell on a sphere of
 * radius 6378137 m (the radius SpatialUtils uses): R^2 * dLon * |sin(lat2) - sin(lat1)|.
 */
class SpatialUtilsAreaSpec extends Specification {

    static final double R = 6378137

    /** Area in sq km of the cell [lon, lon + w] x [lat, lat + h] on the sphere. */
    static double sphericalCellKm2(double lon, double lat, double w, double h) {
        R * R * Math.toRadians(w) * Math.abs(Math.sin(Math.toRadians(lat + h)) - Math.sin(Math.toRadians(lat))) / 1e6
    }

    static String boxWkt(double x1, double y1, double x2, double y2) {
        "POLYGON(($x1 $y1,$x1 $y2,$x2 $y2,$x2 $y1,$x1 $y1))"
    }

    static boolean near(double actual, double expected, double relTolerance = 0.005) {
        Math.abs(actual - expected) <= Math.abs(expected) * relTolerance
    }

    @Unroll
    def "calculateArea(double[][]) of a #size degree cell at latitude #lat is the spherical cell area (sq km)"() {
        given:
        double[][] ring = [[0, lat], [0, lat + size], [size, lat + size], [size, lat], [0, lat]] as double[][]

        expect:
        near(SpatialUtils.calculateArea(ring), sphericalCellKm2(0, lat, size, size))

        where:
        lat   | size
        0     | 1
        -35   | 1
        60    | 1
        -35   | 0.1
        -35.5 | 0.01
    }

    @Unroll
    def "calculateArea(wkt) of a #size degree box at latitude #lat is the spherical cell area (sq m)"() {
        expect:
        near(SpatialUtils.calculateArea(boxWkt(10, lat, 10 + size, lat + size)), sphericalCellKm2(10, lat, size, size) * 1e6)

        where:
        lat | size
        0   | 1
        -35 | 1
        -35 | 0.1
        45  | 2
    }

    def "calculateArea(wkt) does not depend on the ring orientation"() {
        expect:
        near(SpatialUtils.calculateArea('POLYGON((0 0,0 1,1 1,1 0,0 0))'),
                SpatialUtils.calculateArea('POLYGON((0 0,1 0,1 1,0 1,0 0))'), 1e-9)
    }

    def "calculateArea(wkt) accepts WKT with spaces after commas and before brackets"() {
        expect:
        near(SpatialUtils.calculateArea('POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0))'),
                SpatialUtils.calculateArea('POLYGON((0 0,0 1,1 1,1 0,0 0))'), 1e-9)
    }

    def "calculateArea(wkt) of a MULTIPOLYGON written without spaces is the sum of its parts"() {
        given:
        double a = SpatialUtils.calculateArea(boxWkt(0, 0, 1, 1))
        double b = SpatialUtils.calculateArea(boxWkt(10, -30, 11, -29))

        expect:
        near(SpatialUtils.calculateArea('MULTIPOLYGON(((0 0,0 1,1 1,1 0,0 0)),((10 -30,10 -29,11 -29,11 -30,10 -30)))'), a + b)
    }

    /**
     * JTS writes MULTIPOLYGON parts separated by ", " (e.g. Geometry.toText()), and that is what
     * AooEoo, FieldCreation, GridClassBuilder and the area report pass to calculateArea.
     */
    def "calculateArea(wkt) of a MULTIPOLYGON written by JTS is the sum of its parts"() {
        given:
        Geometry g = new WKTReader().read('MULTIPOLYGON(((0 0,0 1,1 1,1 0,0 0)),((10 -30,10 -29,11 -29,11 -30,10 -30)))')
        double a = SpatialUtils.calculateArea(boxWkt(0, 0, 1, 1))
        double b = SpatialUtils.calculateArea(boxWkt(10, -30, 11, -29))

        expect:
        g.toText().contains(')), ((')
        near(SpatialUtils.calculateArea(g.toText()), a + b)
    }

    def "calculateArea(wkt) subtracts a hole that has the opposite orientation to the shell"() {
        given:
        double shell = SpatialUtils.calculateArea(boxWkt(0, 0, 2, 2))
        double hole = SpatialUtils.calculateArea(boxWkt(0.5, 0.5, 1.5, 1.5))

        expect: 'shell clockwise, hole anticlockwise'
        near(SpatialUtils.calculateArea('POLYGON((0 0,0 2,2 2,2 0,0 0),(0.5 0.5,1.5 0.5,1.5 1.5,0.5 1.5,0.5 0.5))'), shell - hole)
    }

    /** WKT does not mandate a ring orientation, so a hole may be drawn in the same direction as the shell. */
    def "calculateArea(wkt) subtracts a hole that has the same orientation as the shell"() {
        given:
        double shell = SpatialUtils.calculateArea(boxWkt(0, 0, 2, 2))
        double hole = SpatialUtils.calculateArea(boxWkt(0.5, 0.5, 1.5, 1.5))

        expect:
        near(SpatialUtils.calculateArea('POLYGON((0 0,0 2,2 2,2 0,0 0),(0.5 0.5,0.5 1.5,1.5 1.5,1.5 0.5,0.5 0.5))'), shell - hole)
    }

    def "calculateArea(wkt) returns the area of the whole world for a world bounding box"() {
        expect:
        SpatialUtils.calculateArea('POLYGON((-180 -90,-180 90,180 90,180 -90,-180 -90))') == 510000000000000L
    }

    def "calculateArea(wkt) of a GEOMETRYCOLLECTION is the sum of its polygons"() {
        given:
        double a = SpatialUtils.calculateArea(boxWkt(0, 0, 1, 1))
        double b = SpatialUtils.calculateArea(boxWkt(10, -30, 11, -29))

        expect:
        near(SpatialUtils.calculateArea('GEOMETRYCOLLECTION(POLYGON((0 0,0 1,1 1,1 0,0 0)),POLYGON((10 -30,10 -29,11 -29,11 -30,10 -30)))'), a + b)
    }

    // cellArea is used for the area of grid cells in tabulations, grid cutting, Grid stats,
    // species by layer and scatterplots.

    @Unroll
    def "cellArea(#res, #lat) is the area of the #res degree cell that contains latitude #lat"() {
        given:
        double cellStart = Math.floor(Math.abs(lat) / res) * res

        expect:
        near(SpatialUtils.cellArea(res, lat), sphericalCellKm2(0, cellStart, res, res))

        where:
        res | lat
        1   | 0.5
        1   | -35.5
        1   | 60.2
    }

    @Unroll
    def "cellArea(#res, #lat) is the area of the #res degree cell that contains latitude #lat (res != 1)"() {
        given:
        double cellStart = Math.floor(Math.abs(lat) / res) * res

        expect:
        near(SpatialUtils.cellArea(res, lat), sphericalCellKm2(0, cellStart, res, res))

        where:
        res  | lat
        0.5  | -35.25
        0.1  | -35.05
        0.01 | -35.005
        2    | 61
        2    | 89
    }

    def "the cellArea cache for res = 1 has one entry per degree of latitude"() {
        expect:
        SpatialUtils.buildCommonGridLatitudeArea(1).length == 90
        SpatialUtils.buildCommonGridLatitudeArea(0.1).length == 900
    }
}
