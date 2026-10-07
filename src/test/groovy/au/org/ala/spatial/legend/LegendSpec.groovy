package au.org.ala.spatial.legend

import au.org.ala.spatial.intersect.Grid
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import java.awt.image.BufferedImage

/**
 * Numeric legends (classifications of grid and occurrence values into 10 colour classes) used for
 * layer SLDs/legend images (GridLegend, SlaveProcess) and scatterplot/occurrence colouring
 * (LegendBuilder, LegendObject).
 */
class LegendSpec extends Specification {

    static final int OPAQUE = 0xFF000000 as int
    static final int WHITE = 0xFFFFFFFF as int

    @TempDir
    File tmp

    static float[] sorted(List<Number> values) {
        float[] f = values.collect { it as float } as float[]
        Arrays.sort(f)
        f
    }

    static float[] range(int from, int to) {
        sorted((from..to).toList())
    }

    /** Linear interpolation of the red, green and blue channels of two colours. */
    static int[] rgb(int colour) {
        [(colour >> 16) & 0xFF, (colour >> 8) & 0xFF, colour & 0xFF] as int[]
    }

    static boolean between(int colour, int c1, int c2) {
        int[] c = rgb(colour), a = rgb(c1), b = rgb(c2)
        (0..2).every { Math.min(a[it], b[it]) - 1 <= c[it] && c[it] <= Math.max(a[it], b[it]) + 1 }
    }

    // ---- init / common values ----

    def "init counts records and unique values and ignores trailing NaN"() {
        given:
        Legend l = new LegendEvenInterval()

        when:
        l.generate(sorted([1, 1, 2, 3, 3, 3, Float.NaN, Float.NaN]))

        then:
        l.min == 1f
        l.max == 3f
        l.numberOfRecords == 6
        l.numberOfUniqueValues == 3
    }

    @Unroll
    def "#legend.class.simpleName does not generate cutoffs when every value is NaN"() {
        when:
        legend.generate(sorted([Float.NaN, Float.NaN]))

        then:
        Float.isNaN(legend.max)
        legend.cutoffs == null
        Double.isNaN(legend.evaluateStdDev(sorted([Float.NaN, Float.NaN])))

        where:
        legend << [new LegendEvenInterval(), new LegendEvenIntervalLog(), new LegendEvenIntervalLog10(), new LegendEqualSize(), new LegendEqualArea()]
    }

    // ---- LegendEvenInterval ----

    def "LegendEvenInterval cutoffs split [min, max] into equal intervals"() {
        given:
        Legend l = new LegendEvenInterval()

        when:
        l.generate(sorted([-5, 0, 3, 7, 15]), 4)

        then:
        l.cutoffs as List == [0f, 5f, 10f, 15f]
        l.typeName == 'Even Interval'
    }

    // ---- LegendEvenIntervalLog / Log10 ----

    @Unroll
    def "#legend.class.simpleName cutoffs are evenly spaced in log space for values >= 1"() {
        when:
        legend.generate(sorted([1, 10, 100, 1000, 10000]), 4)

        then:
        (0..3).every { Math.abs(legend.cutoffs[it] - Math.pow(10, it + 1)) < Math.pow(10, it + 1) * 1e-5 }

        where:
        legend << [new LegendEvenIntervalLog(), new LegendEvenIntervalLog10()]
    }

    /**
     * For min < 1 the values are shifted by offset = 1 - min before taking the log, and the offset
     * should be subtracted again. The subtraction is on its own line ("-offset"), which Groovy
     * parses as a separate statement, so every cutoff except the forced maximum is too high by
     * offset (for min = 0, the first class of 0..99 ends at 1.58 instead of 0.58).
     */
    def "LegendEvenIntervalLog and LegendEvenIntervalLog10 cutoffs are shifted back by the offset used for values < 1"() {
        given:
        float[] d = range(0, 99)
        List<Legend> legends = [new LegendEvenIntervalLog(), new LegendEvenIntervalLog10()]

        when:
        legends*.generate(d, 10)

        then:
        legends.every { legend -> (0..8).every { Math.abs(legend.cutoffs[it] - (Math.pow(100, (it + 1) / 10d) - 1)) < 1e-3 } }
        legends.every { it.cutoffs[9] == 99f }
    }

    // ---- LegendEqualSize ----

    def "LegendEqualSize puts the same number of unique values in each class"() {
        given:
        Legend l = new LegendEqualSize()
        float[] d = range(1, 100)

        when:
        l.generate(d, 10)
        l.determineGroupSizes(d)

        then:
        l.cutoffs as List == (1..10).collect { it * 10f }
        l.groupSizes as List == [10] * 10
    }

    def "LegendEqualSize classes depend on the unique values, not on how often they occur"() {
        given:
        Legend l = new LegendEqualSize()
        float[] d = sorted((1..100).collectMany { [it, it] })

        when:
        l.generate(d, 10)
        l.determineGroupSizes(d)

        then:
        l.cutoffs as List == (1..10).collect { it * 10f }
        l.groupSizes as List == [10] * 10
    }

    // ---- LegendEqualArea ----

    def "LegendEqualArea puts the same number of records in each class"() {
        given:
        Legend l = new LegendEqualArea()
        float[] d = range(1, 1000)

        when:
        l.generate(d, 10)
        l.determineGroupSizes(d)

        then: 'each class has 100 records, give or take the record at the class boundary'
        (0..9).every { Math.abs(l.cutoffs[it] - (it + 1) * 100f) <= 1 }
        l.cutoffs[9] == 1000f
        l.groupSizesArea.every { Math.abs(it - 100) <= 1 }
        l.groupSizesArea.sum() == 1000
        l.evaluateStdDevArea(d) < 1
        l.typeName == 'Equal Area'
    }

    def "LegendEqualArea gives a value that covers more than a class its own class"() {
        given:
        Legend l = new LegendEqualArea()
        float[] d = sorted((1..50).toList() + [60] * 500 + (61..110).toList())

        when:
        l.generate(d, 10)
        l.determineGroupSizes(d)
        int classOf60 = l.cutoffs.findIndexOf { it >= 60f }

        then:
        l.cutoffs[9] == 110f
        l.cutoffs[classOf60] == 60f
        l.groupSizesArea[classOf60] == 500
        (0..8).every { l.cutoffs[it] <= l.cutoffs[it + 1] }
        l.groupSizesArea.sum() == d.length
    }

    def "LegendEqualArea with fewer unique values than classes has a class for each value"() {
        given:
        Legend l = new LegendEqualArea()
        float[] d = sorted([1, 1, 2, 2, 2, 3])

        when:
        l.generate(d, 10)
        l.determineGroupSizes(d)

        then:
        l.cutoffs[9] == 3f
        (l.cutoffs as List).toSet() == [1f, 2f, 3f] as Set
        l.groupSizesArea.sum() == 6
    }

    // ---- LegendDecade ----

    def "LegendDecade has one class per decade"() {
        given:
        Legend l = new LegendDecade()

        when:
        l.generate(sorted([1950, 1960, 1990, Float.NaN]), 10)

        then:
        l.cutoffMins as List == [1950f, 1960f, 1990f]
        l.cutoffs as List == [1959f, 1969f, 1999f]
    }

    // ---- determineGroupSizes ----

    def "determineGroupSizes counts unique values (groupSizes) and records (groupSizesArea) per class"() {
        given:
        Legend l = new LegendEvenInterval()
        float[] d = sorted([0, 1, 1, 1, 2, 3, 3, 4, Float.NaN])

        when:
        l.generate(d, 2)        // cutoffs 2, 4
        l.determineGroupSizes(d)

        then:
        l.cutoffs as List == [2f, 4f]
        l.groupSizes as List == [3, 2]
        l.groupSizesArea as List == [5, 3]
        l.countOfNaN == 1
        l.cutoffMins[1] == 3f
    }

    // ---- getColour ----

    def "getColour(value) is the first colour at min, the last colour at max and white for NaN or above max"() {
        given:
        Legend l = new LegendEvenInterval()
        l.generate(range(0, 100), 10)

        expect:
        l.getColour(0f) == (Legend.colours[0] | OPAQUE)
        l.getColour(100f) == (Legend.colours[10] | OPAQUE)
        l.getColour(30f) == (Legend.colours[3] | OPAQUE)
        between(l.getColour(35f), Legend.colours[3], Legend.colours[4])
        l.getColour(Float.NaN) == WHITE
        l.getColour(101f) == WHITE
    }

    def "getColour(value) is opaque for values in a class"() {
        given:
        Legend l = new LegendEvenInterval()
        l.generate(range(0, 100), 10)

        expect:
        (0..100).every { (l.getColour(it as float) & OPAQUE) == OPAQUE }
    }

    /**
     * When a cutoff is repeated (e.g. a constant layer, or a value that fills several classes)
     * getColour returns colours[pos] without the alpha channel, and Legend.colours are all fully
     * transparent (0x00RRGGBB), so those cells disappear from images written with transparency.
     */
    def "getColour(value) is opaque when cutoffs are repeated"() {
        given:
        Legend l = new LegendEqualArea()
        float[] d = sorted([5] * 20)
        l.generate(d, 10)

        expect:
        (l.getColour(5f) & OPAQUE) == OPAQUE
    }

    /**
     * The static getColour(d, min, max) is documented as "10 colour steps" with pos = (int) a
     * "fit 0 to 10", but a = (d - min) / range is in [0, 1], so pos is always 0 (or 1 at max)
     * and the colour is extrapolated from the first two colours. FieldCreation uses (int) (a * 10).
     */
    def "getColour(d, min, max) goes through the 11 legend colours"() {
        expect:
        (0..10).every { Legend.getColour(it * 10d, 0, 100) == (Legend.colours[it] | OPAQUE) }
    }

    def "getColour(d, min, max) is white outside [min, max] and for NaN"() {
        expect:
        Legend.getColour(-1, 0, 100) == WHITE
        Legend.getColour(101, 0, 100) == WHITE
        Legend.getColour(Double.NaN, 0, 100) == WHITE
    }

    def "getLinearColour interpolates between the start and end colours"() {
        expect:
        Legend.getLinearColour(0, 0, 10, 0x000000, 0xFF8040) == OPAQUE
        Legend.getLinearColour(10, 0, 10, 0x000000, 0xFF8040) == (0xFFFF8040 as int)
        between(Legend.getLinearColour(5, 0, 10, 0x000000, 0xFF8040), 0x7F4020, 0x804020)
    }

    // ---- outputs ----

    def "getCutoffString lists the cutoffs with the group sizes once they are known"() {
        given:
        Legend l = new LegendEvenInterval()
        float[] d = sorted([0, 1, 1, 4])
        l.generate(d, 2)

        expect:
        l.cutoffString == '2.0\n4.0\n'

        when:
        l.determineGroupSizes(d)
        File out = new File(tmp, 'legend.txt')
        l.exportLegend(out.path)

        then:
        l.cutoffString == '2.0\t2\t3\n4.0\t1\t1\n'
        out.text == l.cutoffString
    }

    def "exportSLD writes a well formed colour map with increasing quantities"() {
        given:
        Legend l = new LegendEqualArea()
        float[] d = range(1, 1000)
        l.generate(d, 10)
        Grid g = new Grid()
        g.nodatavalue = -9999
        File sld = new File(tmp, 'layer.sld')

        when:
        l.exportSLD(g, sld.path, 'm', true, false)
        def doc = DocumentBuilderFactory.newInstance().with { namespaceAware = true; it }.newDocumentBuilder().parse(sld)
        def entries = doc.getElementsByTagNameNS('http://www.opengis.net/sld', 'ColorMapEntry')
        List<Double> quantities = (0..<entries.length).collect { entries.item(it).getAttribute('quantity') as double }

        then:
        quantities[0] == -9999d
        quantities[1] == 1d
        quantities.tail() == l.cutoffs.collect { it as double }.plus(0, [1d])
        (1..<quantities.size() - 1).every { quantities[it] < quantities[it + 1] }
        entries.item(1).getAttribute('label') == '1.0 m'
        entries.item(entries.length - 1).getAttribute('label') == '1000.0 m'
    }

    def "exportImage writes one pixel per value coloured with getColour"() {
        given:
        Legend l = new LegendEvenInterval()
        float[] d = range(0, 99)
        l.generate(d, 10)
        File png = new File(tmp, 'img.png')

        when:
        l.exportImage(d, 10, png.path, 1, false)
        BufferedImage image = ImageIO.read(png)

        then:
        image.width == 10
        image.height == 10
        (image.getRGB(0, 0) & 0xFFFFFF) == (l.getColour(0f) & 0xFFFFFF)
        (image.getRGB(9, 9) & 0xFFFFFF) == (l.getColour(99f) & 0xFFFFFF)
        (image.getRGB(5, 3) & 0xFFFFFF) == (l.getColour(35f) & 0xFFFFFF)
    }

    // ---- LegendEvaluate ----

    def "LegendEvaluate picks one of the even interval legends"() {
        when:
        Legend l = LegendEvaluate.buildFrom((1..100).collect { it as double } as double[])

        then:
        l.typeName in ['Even Interval', 'Even Interval Log', 'Even Interval Log 10']
        l.cutoffs[9] == 100f
    }
}
