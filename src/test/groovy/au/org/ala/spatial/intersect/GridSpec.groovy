package au.org.ala.spatial.intersect

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DIVA grids (.grd header + .gri data): the format of every environmental layer used by
 * intersections, grid cutting, tabulations, scatterplots and Maxent.
 */
class GridSpec extends Specification {

    @TempDir
    File tmp

    /** Writes a FLT4BYTES LSB grid with the given header values (row 0 is the northern row). */
    private String writeGrid(String name, Map header, float[] values) {
        String path = new File(tmp, name).path
        new File(path + '.grd').text = """[General]
Title=${name}
[GeoReference]
Projection=GEOGRAPHIC
Datum=WGS84
Mapunits=DEGREES
Columns=${header.ncols}
Rows=${header.nrows}
MinX=${header.xmin}
MaxX=${header.xmax}
MinY=${header.ymin}
MaxY=${header.ymax}
ResolutionX=${header.xres}
ResolutionY=${header.yres}
[Data]
DataType=FLT4BYTES
MinValue=${(values as List).min()}
MaxValue=${(values as List).max()}
NoDataValue=-9999
${header.units ? 'Units=' + header.units : ''}
""".replace('\n', '\r\n')
        ByteBuffer bb = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.each { bb.putFloat(it) }
        new File(path + '.gri').bytes = bb.array()
        path
    }

    def "a grid written with writeGrid can be read back"() {
        given:
        String path = new File(tmp, 'w').path
        float[] data = [1, 2, 3, 4, 5, 6] as float[]

        when:
        new Grid(null).writeGrid(path, data, 110, -40, 113, -38, 1, 1, 2, 3)
        Grid g = new Grid(path)

        then:
        g.ncols == 3
        g.nrows == 2
        g.xmin == 110
        g.ymax == -38
        g.grid == data
    }

    def "getValues returns the value of the cell that contains each point"() {
        given: '3 columns x 2 rows of 0.5 degree, first row is the northern one'
        String path = writeGrid('v', [ncols: 3, nrows: 2, xmin: 110, xmax: 111.5, ymin: -40, ymax: -39, xres: 0.5, yres: 0.5],
                [1, 2, 3, 4, 5, 6] as float[])
        Grid g = new Grid(path)

        when:
        float[] v = g.getValues([[110.25, -39.25], [111.25, -39.25], [110.25, -39.75], [111.45, -39.95]] as double[][])

        then:
        v == [1, 3, 4, 6] as float[]
    }

    def "getValues returns NaN outside the grid and for no data cells"() {
        given:
        String path = writeGrid('n', [ncols: 2, nrows: 1, xmin: 0, xmax: 1, ymin: 0, ymax: 0.5, xres: 0.5, yres: 0.5],
                [-9999, 7] as float[])
        Grid g = new Grid(path)

        when:
        float[] v = g.getValues([[0.25, 0.25], [0.75, 0.25], [5, 5], [-1, 0.25]] as double[][])

        then:
        Float.isNaN(v[0])
        v[1] == 7f
        Float.isNaN(v[2])
        Float.isNaN(v[3])
    }

    /** When the header says ResolutionX=1 the constructor recalculates the resolution from the extents. */
    def "the resolution is recalculated from the extents of a non square grid"() {
        given: 'a 4 x 2 degree extent with 2 columns and 1 row (2 degree cells) but ResolutionX=1'
        String path = writeGrid('r', [ncols: 2, nrows: 1, xmin: 0, xmax: 4, ymin: 0, ymax: 2, xres: 1, yres: 1],
                [1, 2] as float[])

        when:
        Grid g = new Grid(path)

        then:
        g.xres == 2
        g.yres == 2
    }

    /** The recalculation above also applies to real 1 degree grids. */
    def "getValues of a 1 degree grid that is not square"() {
        given: '3 columns x 2 rows of 1 degree'
        String path = writeGrid('one', [ncols: 3, nrows: 2, xmin: 110, xmax: 113, ymin: -40, ymax: -38, xres: 1, yres: 1],
                [1, 2, 3, 4, 5, 6] as float[])

        when:
        float[] v = new Grid(path).getValues([[110.5, -38.5], [112.5, -38.5], [110.5, -39.5], [112.9, -39.9]] as double[][])

        then:
        v == [1, 3, 4, 6] as float[]
    }

    def "units 'xN unit' rescale the values"() {
        given:
        String path = writeGrid('x', [ncols: 1, nrows: 1, xmin: 0, xmax: 0.5, ymin: 0, ymax: 0.5, xres: 0.5, yres: 0.5, units: 'x10 mm'],
                [25] as float[])

        when:
        Grid g = new Grid(path)

        then:
        g.units == 'mm'
        g.getValues([[0.25, 0.25]] as double[][]) == [250] as float[]
    }

    /** "1/10 degC" means the stored values are tenths. */
    def "units '1/N unit' rescale the values"() {
        given:
        String path = writeGrid('f', [ncols: 1, nrows: 1, xmin: 0, xmax: 0.5, ymin: 0, ymax: 0.5, xres: 0.5, yres: 0.5, units: '1/10 degC'],
                [250] as float[])

        when:
        Grid g = new Grid(path)

        then:
        g.units == 'degC'
        g.getValues([[0.25, 0.25]] as double[][]) == [25] as float[]
    }
}
