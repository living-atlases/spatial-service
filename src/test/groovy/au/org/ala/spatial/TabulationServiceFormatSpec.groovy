package au.org.ala.spatial

import au.org.ala.spatial.dto.Tabulation
import com.opencsv.CSVReader
import grails.converters.JSON
import grails.testing.services.ServiceUnitTest
import spock.lang.PendingFeature
import spock.lang.Specification

/**
 * CSV and JSON of /tabulation/{area|occurrences|species}/{fid1}/{fid2}/tabulation.{csv|json}
 * (TabulationService.generateTabulationCSVHTML) for a fixed set of intersections.
 */
class TabulationServiceFormatSpec extends Specification implements ServiceUnitTest<TabulationService> {

    List<Tabulation> tabulations

    Locale defaultLocale

    @Override
    Set<String> getIncludePlugins() {
        ['core', 'eventBus', 'converters'].toSet()
    }

    def setup() {
        defaultLocale = Locale.default
        // field 1 (columns): A, B; field 2 (rows): X, Y, Z
        tabulations = [
                tab('a', 'A', 'x', 'X', 0.14d, 3, 2, 7, 5),
                tab('a', 'A', 'y', 'Y', 0.2d, 0, 0, 7, 0),
                tab('b', 'B', 'z', 'Z', 1.25d, 10, 4, 4, 4)
        ]
    }

    def cleanup() {
        Locale.default = defaultLocale
    }

    private static Tabulation tab(String pid1, String name1, String pid2, String name2, double area, int occurrences,
                                  int species, int speciest1, int speciest2) {
        new Tabulation(fid1: 'cl1', pid1: pid1, name1: name1, fid2: 'cl2', pid2: pid2, name2: name2, area: area,
                occurrences: occurrences, species: species, speciest1: speciest1, speciest2: speciest2)
    }

    /** the service with getTabulation answering from {@link #tabulations} */
    private TabulationService service() {
        List<Tabulation> list = tabulations
        new TabulationService() {
            @Override
            List<Tabulation> getTabulation(String fid1, String fid2, String wkt) {
                list
            }
        }
    }

    private static List<List<String>> csv(String text) {
        new CSVReader(new StringReader(text)).readAll().collect { it.toList() }
    }

    def "area CSV: a column per object of the field with fewer objects and a row per object of the other"() {
        given:
        Locale.default = Locale.GERMANY

        when:
        def rows = csv(service().generateTabulationCSVHTML('cl1', 'cl2', null, 'area', 'csv'))

        then:
        rows[0] == ['Area (square kilometres)', 'A', 'B', 'Total area']
        rows[1].subList(0, 3) == ['X', '0.1', '']
        rows[2].subList(0, 3) == ['Y', '0.2', '']
        rows[3].subList(0, 3) == ['Z', '', '1.3']
        rows[4][0] == 'Total area'
        rows.collect { it.join(',') }.containsAll(['Blanks = no intersection', '0 = no records in intersection'])
    }

    def "area CSV row and column totals"() {
        when:
        def rows = csv(service().generateTabulationCSVHTML('cl1', 'cl2', null, 'area', 'csv'))

        then:
        rows[1][3] as double == 0.1d
        rows[2][3] as double == 0.2d
        rows[3][3] as double == 1.3d
        rows[4].subList(1, 4).collect { (it as double).round(6) } == [0.3d, 1.3d, 1.6d]
    }

    @PendingFeature(reason = 'the area totals are the sum of the rounded cells written with Double.toString, e.g. 0.30000000000000004')
    def "area CSV totals are written with one decimal like the cells"() {
        when:
        def rows = csv(service().generateTabulationCSVHTML('cl1', 'cl2', null, 'area', 'csv'))

        then:
        rows[4].subList(1, 4) == ['0.3', '1.3', '1.6']
    }

    def "the field with more objects is always the rows"() {
        given:
        tabulations = tabulations.collect {
            tab(it.pid2, it.name2, it.pid1, it.name1, it.area, it.occurrences, it.species, it.speciest2, it.speciest1)
        }

        when:
        def rows = csv(service().generateTabulationCSVHTML('cl2', 'cl1', null, 'occurrences', 'csv'))

        then:
        rows[0] == ['Number of occurrences', 'A', 'B', 'Total occurrences']
        rows[1].subList(0, 3) == ['X', '3', '']
        rows[3].subList(0, 3) == ['Z', '', '10']
    }

    def "occurrences CSV totals"() {
        when:
        def rows = csv(service().generateTabulationCSVHTML('cl1', 'cl2', null, 'occurrences', 'csv'))

        then:
        rows[0] == ['Number of occurrences', 'A', 'B', 'Total occurrences']
        rows[1][3] as double == 3
        rows[4][0] == 'Total occurrences'
        rows[4].subList(1, 4).collect { it as double } == [3d, 10d, 13d]
    }

    def "species totals are the species in each object, not the sum of the cells"() {
        when:
        def rows = csv(service().generateTabulationCSVHTML('cl1', 'cl2', null, 'species', 'csv'))

        then:
        rows[0] == ['Number of species', 'A', 'B', 'Total species']
        rows[1].subList(0, 4).collect { it } == ['X', '2', '', '5.0']
        rows[3].subList(0, 4).collect { it } == ['Z', '', '4', '4.0']
        rows[4].subList(0, 3) == ['Total species', '7.0', '4.0']
    }

    def "occurrences JSON has a map per row and the totals"() {
        when:
        def json = JSON.parse(service().generateTabulationCSVHTML('cl1', 'cl2', null, 'occurrences', 'json'))

        then:
        json.X.A == 3
        json.X.'Total occurrences' == 3
        json.Z.B == 10
        !json.Y.containsKey('B')
        json.'Total occurrences'.A == 3
        json.'Total occurrences'.B == 10
        json.'Total occurrences'.'Total occurrences' == 13
    }

    def "area JSON uses a dot decimal separator with a German default locale"() {
        given:
        Locale.default = Locale.GERMANY

        when:
        def json = JSON.parse(service().generateTabulationCSVHTML('cl1', 'cl2', null, 'area', 'json'))

        then:
        json.X.A == 0.1
        json.Z.B == 1.3
    }

    @PendingFeature(reason = 'the JSON escapes a double quote in an object name as "" (CSV style) instead of \\"')
    def "JSON is valid for object names with double quotes"() {
        given:
        tabulations[0].name1 = tabulations[1].name1 = 'Murray "Darling"'

        when:
        def json = JSON.parse(service().generateTabulationCSVHTML('cl1', 'cl2', null, 'occurrences', 'json'))

        then:
        json.X.'Murray "Darling"' == 3
    }

    def "CSV quotes object names with commas and double quotes"() {
        given:
        tabulations[0].name1 = tabulations[1].name1 = 'Murray "Darling", NSW'

        when:
        def rows = csv(service().generateTabulationCSVHTML('cl1', 'cl2', null, 'occurrences', 'csv'))

        then:
        rows[0][1] == 'Murray "Darling", NSW'
    }

    def "objects without a name use their pid"() {
        given:
        tabulations[2].name2 = null

        when:
        def rows = csv(service().generateTabulationCSVHTML('cl1', 'cl2', null, 'occurrences', 'csv'))

        then:
        rows[3][0] == 'z'
    }
}
