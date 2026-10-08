package au.org.ala.spatial

import au.org.ala.spatial.intersect.Grid
import groovy.io.FileType
import spock.lang.PendingFeature
import spock.lang.Specification
import spock.lang.TempDir

/**
 * #254 / #247: decimal numbers written to grid headers, CSVs, WKT and reports must not depend on the
 * server default locale (a comma decimal separator breaks every consumer of those files).
 */
class LocaleIndependenceSpec extends Specification {

    @TempDir
    File tmp

    Locale defaultLocale

    def setup() {
        defaultLocale = Locale.default
        Locale.default = Locale.GERMANY
    }

    def cleanup() {
        Locale.default = defaultLocale
    }

    /** "%.2f" style formats of String.format / sprintf / .format without an explicit Locale. */
    static final DECIMAL_FORMAT_WITHOUT_LOCALE = ~/(String\.format|sprintf)\(\s*(['"])[^'"]*%[-+ 0#,(]*\d*(\.\d+)?[fFeEgG]/

    def "no source file formats a decimal number without an explicit Locale (#254)"() {
        given:
        List<String> offenders = []
        ['grails-app', 'src/main'].each { root ->
            new File(root).eachFileRecurse(FileType.FILES) { File f ->
                if (f.name.endsWith('.groovy') || f.name.endsWith('.java')) {
                    f.eachLine { String line, int n ->
                        if (!line.trim().startsWith('//') && line =~ DECIMAL_FORMAT_WITHOUT_LOCALE) {
                            offenders << "${f.path}:${n}: ${line.trim()}".toString()
                        }
                    }
                }
            }
        }

        expect:
        offenders == []
    }

    def "distribution CSV areas use a dot decimal separator with a German default locale"() {
        given:
        def d = new Distributions(spcode: 1, scientific: 'Macropus rufus', area_name: 'Australia', area_km: 1234.5d)
        d.intersectArea = 2500000d // transient, not bound by the map constructor

        when:
        String[] lines = Util.getDistributionsOrChecklists([d])

        then:
        lines[1].contains(',1234.50,')
        lines[1].endsWith(',2.50')
    }

    def "grid headers use a dot decimal separator with a German default locale"() {
        given:
        String name = new File(tmp, 'g').path

        when:
        new Grid(null).writeHeader(name, 112.5, -44.25, 154.75, -9.0, 0.25, 0.25, 141, 169, 0, 10)
        String header = new File(name + '.grd').text

        then:
        header =~ /MinX=112\.50?\r\n/
        header =~ /MinY=-44\.25\r\n/
        header.contains('ResolutionX=0.25')
        !header.contains(',')
    }

    /**
     * writeHeader rounds the extents to 2 decimals, so grids whose edges are not on a 0.01 degree
     * boundary (e.g. 30 arc second grids) are shifted when they are written and read back.
     */
    @PendingFeature(reason = 'Grid.writeHeader writes MinX/MaxX/MinY/MaxY with "%.2f"')
    def "a grid header keeps the extents of a 30 arc second grid"() {
        given:
        String name = new File(tmp, 'g30').path
        double res = 1 / 120d
        double xmin = 112.9041667
        double ymin = -43.7458333

        when:
        new Grid(null).writeHeader(name, xmin, ymin, xmin + res * 100, ymin + res * 100, res, res, 100, 100, 0, 10)
        new File(name + '.gri').bytes = new byte[100 * 100 * 4]
        Grid g = new Grid(name)

        then:
        Math.abs(g.xmin - xmin) < 1e-6
        Math.abs(g.ymin - ymin) < 1e-6
    }
}
