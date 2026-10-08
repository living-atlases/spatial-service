package au.org.ala.spatial.intersect

import spock.lang.Specification
import spock.lang.TempDir

/**
 * IniReader reads the DIVA grid headers (.grd) of every environmental layer.
 */
class IniReaderSpec extends Specification {

    @TempDir
    File tmp

    String write(String name, String text) {
        File f = new File(tmp, name)
        f.text = text
        f.path
    }

    static final String GRD = '''[General]
Title=bio01
; a comment
# another comment
[GeoReference]
Columns=4320
MinX=-180.0
MaxX=180
ResolutionX=0.08333333333333333
[Data]
DataType=FLT4BYTES
NoDataValue=-9999
Units=1/10 degC
Expression=a=b+c
'''

    def "values are read by section and key"() {
        when:
        IniReader ir = new IniReader(write('a.grd', GRD))

        then:
        ir.getStringValue('General', 'Title') == 'bio01'
        ir.getIntegerValue('GeoReference', 'Columns') == 4320
        ir.getDoubleValue('GeoReference', 'MinX') == -180d
        ir.getDoubleValue('GeoReference', 'ResolutionX') == 0.08333333333333333d
        ir.getDoubleValue('Data', 'NoDataValue') == -9999d
        ir.getStringValue('Data', 'Units') == '1/10 degC'
        ir.valueExists('Data', 'DataType')
    }

    def "a value may contain '='"() {
        expect:
        new IniReader(write('b.grd', GRD)).getStringValue('Data', 'Expression') == 'a=b+c'
    }

    def "CRLF line endings and surrounding whitespace are ignored"() {
        when:
        IniReader ir = new IniReader(write('c.grd', GRD.replace('\n', '\r\n').replace('Columns=4320', '   Columns=4320   ')))

        then:
        ir.getIntegerValue('GeoReference', 'Columns') == 4320
        ir.getStringValue('General', 'Title') == 'bio01'
    }

    def "missing keys, sections and files give the defaults"() {
        when:
        IniReader ir = new IniReader(write('d.grd', GRD))
        IniReader none = new IniReader(new File(tmp, 'does-not-exist.grd').path)

        then:
        ir.getStringValue('General', 'Nothing') == ''
        ir.getIntegerValue('Nothing', 'Columns') == 0
        ir.getDoubleValue('General', 'Nothing') == 0d
        !ir.valueExists('General', 'Nothing')
        // keys are per section
        !ir.valueExists('General', 'Columns')
        none.getStringValue('General', 'Title') == ''
    }

    def "values that are not numbers read as 0"() {
        when:
        IniReader ir = new IniReader(write('e.grd', GRD))

        then:
        ir.getIntegerValue('General', 'Title') == 0
        ir.getDoubleValue('Data', 'Units') == 0d
        ir.getIntegerValue('GeoReference', 'MinX') == 0
    }

    def "a section without the closing bracket is still a section"() {
        expect:
        new IniReader(write('f.grd', '[Data\nNoDataValue=-1\n')).getIntegerValue('Data', 'NoDataValue') == -1
    }

    def "values written with setValue and write can be read back"() {
        given:
        IniReader ir = new IniReader(write('g.grd', GRD))
        ir.setValue('Data', 'MinValue', '1.5')
        String out = new File(tmp, 'out.grd').path

        when:
        ir.write(out)
        IniReader back = new IniReader(out)

        then:
        back.document == ir.document
        back.getDoubleValue('Data', 'MinValue') == 1.5d
    }

    /**
     * The parser only accepts a section name when ']' is after position 2 and a key when '=' is
     * after position 1, so one character sections are ignored (their keys go to the previous
     * section) and one character keys are stored under an empty key.
     */
    def "one character section names and keys are read"() {
        when:
        IniReader ir = new IniReader(write('h.ini', '[A]\nx=1\n[Bb]\nyy=2\n'))

        then:
        ir.getIntegerValue('A', 'x') == 1
        ir.getIntegerValue('Bb', 'yy') == 2
    }
}
