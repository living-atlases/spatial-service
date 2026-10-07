package au.org.ala.spatial.intersect

import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.io.WKTReader
import spock.lang.PendingFeature
import spock.lang.Specification
import spock.lang.Unroll

/**
 * SimpleRegion / SimpleShapeFile WKT parsing and the polygon to grid cell rasterisation used by
 * GridCutterService (area cutting of environmental layers) and ComplexRegion.
 *
 * Grid cells are checked against JTS: a cell must be returned if the polygon covers part of it,
 * and must not be returned if the polygon does not touch it.
 */
class SimpleRegionSpec extends Specification {

    static final long TIMEOUT_MS = 10000

    static final GeometryFactory GF = new GeometryFactory()

    /** Runs the closure in a separate thread so an endless loop fails the test instead of hanging the build. */
    static Map runWithTimeout(Closure c) {
        Map out = [finished: false]
        Thread t = new Thread({
            out.result = c()
            out.finished = true
        })
        t.daemon = true
        t.start()
        t.join(TIMEOUT_MS)
        if (t.alive) {
            try {
                t.stop()
            } catch (Throwable ignored) {
                // Thread.stop is not supported on newer JVMs; the daemon thread is left behind
            }
        }
        out
    }

    static Set<List<Integer>> cellsOf(int[][] cells) {
        cells.collect { [it[0], it[1]] } as Set
    }

    /** Cells [x, y] that the polygon covers in part (intersection area > 0) and cells it touches. */
    static Map<String, Set<List<Integer>>> expectedCells(String wkt, double x1, double y1, double x2, double y2, int width, int height) {
        Geometry g = new WKTReader().read(wkt)
        double divx = (x2 - x1) / width
        double divy = (y2 - y1) / height
        Set<List<Integer>> covered = [] as Set
        Set<List<Integer>> touched = [] as Set
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                Geometry cell = GF.toGeometry(new Envelope(x1 + x * divx, x1 + (x + 1) * divx, y1 + y * divy, y1 + (y + 1) * divy))
                if (g.intersects(cell)) {
                    touched << [x, y]
                    if (g.intersection(cell).area > 1e-12) {
                        covered << [x, y]
                    }
                }
            }
        }
        [covered: covered, touched: touched]
    }

    static String toWkt(String simpleRegionPoints) {
        "POLYGON((${simpleRegionPoints}))"
    }

    // --- parsing -------------------------------------------------------------------------------

    def "parseSimpleRegion reads 'lon lat,lon lat,...' polygons"() {
        when:
        SimpleRegion r = SimpleRegion.parseSimpleRegion('0 0,10 0,5 10,0 0')

        then:
        r.type == SimpleRegion.POLYGON
        r.numberOfPoints == 4
        r.boundingBox == [[0d, 0d], [10d, 10d]] as double[][]
        r.isWithin(5, 2)
        !r.isWithin(9, 9)
    }

    /**
     * The box test compares points.size() (number of values) with 5 instead of 10, so closed rings
     * always take the polygon path. (Fixing only that exposes setBox's bounding box, see below.)
     */
    @PendingFeature(reason = 'parseSimpleRegion only detects boxes written with 4 points, not closed 5 point rings')
    def "parseSimpleRegion detects an axis aligned box"() {
        when:
        SimpleRegion r = SimpleRegion.parseSimpleRegion('1 2,1 4,3 4,3 2,1 2')

        then:
        r.type == SimpleRegion.BOUNDING_BOX
        r.boundingBox == [[1d, 2d], [3d, 4d]] as double[][]
    }

    /**
     * setBox stores the bounding box as [[minLon, maxLon], [minLat, maxLat]] while getBoundingBox()
     * users (GridCutterService, ComplexRegion, getOverlapGridCells_Box) expect [[minLon, minLat], [maxLon, maxLat]].
     */
    @PendingFeature(reason = 'setBox stores the bounding box transposed')
    def "a box written with 4 points has the same bounding box and grid cells as the polygon"() {
        given:
        SimpleRegion box = SimpleRegion.parseSimpleRegion('2 2,2 6,6 6,6 2')
        SimpleRegion polygon = SimpleRegion.parseSimpleRegion('2 2,2 6,6 6,6 2,2 2')

        expect:
        box.type == SimpleRegion.BOUNDING_BOX
        box.boundingBox == polygon.boundingBox
        cellsOf(box.getOverlapGridCells(0, 0, 10, 10, 10, 10, null)) == cellsOf(polygon.getOverlapGridCells(0, 0, 10, 10, 10, 10, null))
    }

    def "parseSimpleRegion returns null for 'none'"() {
        expect:
        SimpleRegion.parseSimpleRegion('none') == null
        SimpleRegion.parseSimpleRegion('NONE') == null
    }

    @Unroll
    def "parseWKT reads a POLYGON written as #description (#230)"() {
        when:
        SimpleRegion r = SimpleShapeFile.parseWKT(wkt)

        then:
        r != null
        r.boundingBox == [[143d, -44d], [148d, -39d]] as double[][]
        r.isWithin(145, -41)
        !r.isWithin(150, -41)

        where:
        description               | wkt
        'compact'                 | 'POLYGON((143 -39,148 -39,148 -44,143 -44,143 -39))'
        'spaces after commas'     | 'POLYGON((143 -39, 148 -39, 148 -44, 143 -44, 143 -39))'
        'a space before brackets' | 'POLYGON ((143 -39, 148 -39, 148 -44, 143 -44, 143 -39))'
        'new lines (#230)'        | 'POLYGON((\n  143 -39,\n  148 -39,\n  148 -44,\n  143 -44,\n  143 -39\n))'
    }

    def "parseWKT reads a MULTIPOLYGON into a ComplexRegion"() {
        when:
        SimpleRegion r = SimpleShapeFile.parseWKT('MULTIPOLYGON(((0 0,0 1,1 1,1 0,0 0)),((10 10,10 11,11 11,11 10,10 10)))')

        then:
        r instanceof ComplexRegion
        r.isWithin(0.5, 0.5)
        r.isWithin(10.5, 10.5)
        !r.isWithin(5, 5)
    }

    def "parseWKT reads a MULTIPOLYGON written by JTS (', ' between parts)"() {
        given:
        String wkt = new WKTReader().read('MULTIPOLYGON(((0 0,0 1,1 1,1 0,0 0)),((10 10,10 11,11 11,11 10,10 10)))').toText()

        when:
        SimpleRegion r = SimpleShapeFile.parseWKT(wkt)

        then:
        r.isWithin(0.5, 0.5)
        r.isWithin(10.5, 10.5)
        !r.isWithin(5, 5)
    }

    /**
     * parseGeometryCollection keeps the opening bracket of each ring ("(10 10,...") so the first
     * longitude fails to parse and silently becomes 0 (and the resulting sloped edge then hits #230).
     */
    @PendingFeature(reason = 'parseGeometryCollection passes the ring with its opening bracket, so the first coordinate is read as 0')
    def "parseWKT reads the polygons of a GEOMETRYCOLLECTION"() {
        when:
        def out = runWithTimeout {
            SimpleShapeFile.parseWKT('GEOMETRYCOLLECTION(POLYGON((10 10,10 11,11 11,11 10,10 10)),POLYGON((20 20,20 21,21 21,21 20,20 20)))')
        }

        then:
        out.finished
        SimpleRegion r = out.result as SimpleRegion
        r.isWithin(10.5, 10.5)
        r.isWithin(20.5, 20.5)
        !r.isWithin(5, 10.5)
        !r.isWithin(15, 15)
    }

    /**
     * parseWKT builds a ComplexRegion for multi part geometries and calls useMask, which rasterises
     * every polygon with getOverlapGridCells, so it hangs on the #230 loop below. This is the path
     * used by GeneratePoints, TabulationUtil and TabulationGeneratorService.
     */
    @PendingFeature(reason = '#230 regression: parseWKT of a multi part geometry with sloped edges never returns')
    def "parseWKT of a MULTIPOLYGON of triangles finishes (#230)"() {
        when:
        def out = runWithTimeout {
            SimpleShapeFile.parseWKT('MULTIPOLYGON(((140 -30,141 -30,140.5 -29,140 -30)),((150 -35,151 -35,150.5 -34,150 -35)))')
        }

        then:
        out.finished
        SimpleRegion r = out.result as SimpleRegion
        r.isWithin(140.5, -29.8)
        r.isWithin(150.5, -34.8)
        !r.isWithin(145, -32)
    }

    def "parseWKT returns null for null and for unsupported geometries"() {
        expect:
        SimpleShapeFile.parseWKT(null) == null
        SimpleShapeFile.parseWKT('POINT(1 2)') == null
    }

    // --- rasterisation (GridCutterService) -----------------------------------------------------

    /** Integer cell heights work: the latitude loop advances by (int) divy. */
    @Unroll
    def "getOverlapGridCells for a sloped polygon on a grid of #divy degree cells returns the cells it covers"() {
        given:
        String points = '1.3 1.1,17.9 3.3,9.1 18.7,1.3 1.1'
        SimpleRegion r = SimpleRegion.parseSimpleRegion(points)
        def expected = expectedCells(toWkt(points), 0, 0, 20, 20, width, height)

        when:
        def out = runWithTimeout { r.getOverlapGridCells(0, 0, 20, 20, width, height, null) }

        then:
        out.finished
        def actual = cellsOf(out.result as int[][])
        actual.containsAll(expected.covered)
        expected.touched.containsAll(actual)

        where:
        width | height | divy
        10    | 10     | 2
        20    | 20     | 1
    }

    /**
     * #230: with cells smaller than one degree the latitude loop "k += (int) divy" never advances.
     * PR #231 changed the step to max(divy, 1) (which avoided the hang but skips rows); that change
     * was then lost in 32a100b (#256 sandbox), so the endless loop is back.
     */
    @PendingFeature(reason = '#230 regression: k += (int) divy is 0 when cells are smaller than 1 degree')
    @Unroll
    def "getOverlapGridCells finishes for a sloped polygon on a grid of #divy degree cells (#230)"() {
        given:
        String points = '0.13 0.11,0.79 0.33,0.41 0.87,0.13 0.11'
        SimpleRegion r = SimpleRegion.parseSimpleRegion(points)

        when:
        def out = runWithTimeout { r.getOverlapGridCells(0, 0, 1, 1, width, height, null) }

        then:
        out.finished

        where:
        width | height | divy
        10    | 10     | 0.1
        100   | 100    | 0.01
    }

    @PendingFeature(reason = '#230 regression: the sloped edge loop does not advance by divy')
    @Unroll
    def "getOverlapGridCells for a sloped polygon on a grid of #divy degree cells returns the cells it covers (#230)"() {
        given:
        String points = "${0.13 * scale} ${0.11 * scale},${0.79 * scale} ${0.33 * scale},${0.41 * scale} ${0.87 * scale},${0.13 * scale} ${0.11 * scale}"
        SimpleRegion r = SimpleRegion.parseSimpleRegion(points)
        def expected = expectedCells(toWkt(points), 0, 0, scale, scale, 20, 20)

        when:
        def out = runWithTimeout { r.getOverlapGridCells(0, 0, scale, scale, 20, 20, null) }

        then:
        out.finished
        def actual = cellsOf(out.result as int[][])
        actual.containsAll(expected.covered)
        expected.touched.containsAll(actual)

        where:
        scale | divy
        2     | 0.1
        50    | 2.5
    }

    def "getOverlapGridCells for a box returns every cell of the box"() {
        given:
        SimpleRegion r = SimpleRegion.parseSimpleRegion('2 2,2 6,6 6,6 2,2 2')

        when:
        def cells = cellsOf(r.getOverlapGridCells(0, 0, 10, 10, 10, 10, null))

        then:
        cells.containsAll((2..5).collectMany { x -> (2..5).collect { y -> [x, y] } })
        cells.every { it[0] in 1..6 && it[1] in 1..6 }
    }
}
