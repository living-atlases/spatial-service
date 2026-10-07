package au.org.ala.spatial.process

import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.io.WKTReader
import spock.lang.Specification
import spock.lang.Unroll

import java.awt.geom.Point2D

/**
 * The calculations behind the AOO/EOO tool: grid cells for the Area Of Occupancy and the alpha hull.
 */
class AooEooCalculationSpec extends Specification {

    // float precision of the grid cell corners
    static final double EPS = 1e-4

    AooEoo proc = new AooEoo() {
        @Override
        def taskLog(String msg) {}
    }

    /** True if [lng, lat] falls in the AOO cell that AooEoo assigns to it. */
    static boolean inOwnCell(double lng, double lat, double gridSize) {
        Set<Point2D> cells = AooEoo.aooProcess(["$lat,$lng".toString()] as String[], gridSize)
        Point2D c = cells.first()
        c.x - EPS <= lng && lng < c.x + gridSize + EPS && c.y - EPS <= lat && lat < c.y + gridSize + EPS
    }

    static List<List<Double>> randomPoints(double minLng, double maxLng, double minLat, double maxLat, int n) {
        Random r = new Random(42)
        (1..n).collect { [minLng + r.nextDouble() * (maxLng - minLng), minLat + r.nextDouble() * (maxLat - minLat)] }
    }

    @Unroll
    def "points with positive coordinates fall in their own #gridSize degree AOO cell"() {
        expect:
        randomPoints(0.001, 179, 0.001, 89, 500).findAll { !inOwnCell(it[0], it[1], gridSize) } == []

        where:
        gridSize << [0.02, 0.1, 1]
    }

    /** Points west of Greenwich or south of the equator (all of Australia). */
    @Unroll
    def "points with negative coordinates fall in their own #gridSize degree AOO cell"() {
        expect:
        randomPoints(-179, -0.001, -89, -0.001, 500).findAll { !inOwnCell(it[0], it[1], gridSize) } == []

        where:
        gridSize << [0.02, 0.1, 1]
    }

    @Unroll
    def "round(#d, #by) is the start of the cell that contains #d"() {
        expect:
        AooEoo.round(d, by) == (float) expected

        where:
        d    | by | expected
        -0.5 | 1  | -1
        -1.0 | 1  | -1
        -2.0 | 1  | -2
    }

    @Unroll
    def "round(#d, #by) is the start of the cell that contains #d (positive coordinates)"() {
        expect:
        AooEoo.round(d, by) == (float) expected

        where:
        d    | by | expected
        0.5  | 1  | 0
        1.0  | 1  | 1
        -1.5 | 1  | -2
        2.75 | 0.5 | 2.5
    }

    def "two points on either side of the equator are two AOO cells"() {
        expect:
        AooEoo.aooProcess(['-0.5,10.5', '0.5,10.5'] as String[], 1).size() == 2
    }

    def "points in the same cell are counted once"() {
        expect:
        AooEoo.aooProcess(['10.1,20.1', '10.9,20.9', '"10.5,20.5"', 'not a point'] as String[], 1).size() == 1
    }

    def "the AOO WKT has one square of gridSize per cell"() {
        given:
        Set<Point2D> cells = AooEoo.aooProcess(['10.5,20.5', '12.5,20.5'] as String[], 1)

        when:
        Geometry g = new WKTReader().read(AooEoo.aooWkt(cells, 1))

        then:
        g.numGeometries == 2
        Math.abs(g.area - 2) < EPS
    }

    // #274 alpha hull: the mean edge length was summed as (int) lengths, so it was 0 for points
    // closer than one degree and no triangle was kept.

    def "the alpha hull of points closer than one degree is not empty (#274)"() {
        given:
        Geometry points = new WKTReader().read('MULTIPOINT((0 0),(0.3 0),(0.3 0.3),(0 0.3),(0.15 0.15),(0.1 0.2))')

        when:
        Geometry hull = AooEoo.buildConcaveHull(points, 2, proc)

        then:
        hull != null
        hull.area > 0
        hull.area <= points.convexHull().area + EPS
    }

    def "a large alpha keeps every triangle, so the alpha hull is the convex hull"() {
        given:
        Geometry points = new WKTReader().read('MULTIPOINT((140 -30),(141 -30),(141 -31),(140 -31),(140.5 -30.5))')

        when:
        Geometry hull = AooEoo.buildConcaveHull(points, 100, proc)

        then:
        Math.abs(hull.area - points.convexHull().area) < EPS
    }

    def "a small alpha drops the long edges of an outlier"() {
        given: 'a tight cluster and one far away point'
        Geometry points = new WKTReader().read('MULTIPOINT((0 0),(0.1 0),(0.1 0.1),(0 0.1),(0.05 0.05),(0.02 0.07),(0.07 0.02),(5 5))')

        when:
        Geometry hull = AooEoo.buildConcaveHull(points, 1, proc)

        then:
        hull != null
        hull.area < points.convexHull().area / 10
    }
}
