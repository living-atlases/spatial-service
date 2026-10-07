package au.org.ala.spatial.legend

import spock.lang.PendingFeature
import spock.lang.Specification

/**
 * Facet parses and tests the facet queries created by the web portal for legends, envelopes and
 * scatterplots.
 */
class FacetSpec extends Specification {

    def "a value facet is written as field:\"value\""() {
        expect:
        new Facet('species_group', 'Birds', true).toString() == 'species_group:"Birds"'
        new Facet('species_group', 'Birds', false).toString() == '-species_group:"Birds"'
        new Facet('species_group', '*', true).toString() == 'species_group:*'
    }

    def "a range facet writes whole numbers without decimals and infinite bounds as *"() {
        expect:
        new Facet('el1', 1d, 10.5d, true).toString() == 'el1:[1 TO 10.5]'
        new Facet('el1', Double.NEGATIVE_INFINITY, 3d, false).toString() == '-el1:[* TO 3]'
    }

    def "an occurrence_year range is written with dates"() {
        expect:
        new Facet('occurrence_year', '1990', '2000', true).toString() == 'occurrence_year:[1990-01-01T00:00:00Z TO 2000-12-31T00:00:00Z]'
        new Facet('occurrence_year', '1990-01-01T00:00:00Z', '*', true).toString() == 'occurrence_year:[1990-01-01T00:00:00Z TO *]'
    }

    def "parseFacet reads a value facet and tests values against it"() {
        when:
        Facet f = Facet.parseFacet('species_group:"Birds"')

        then:
        f.type == 1
        f.field == 'species_group'
        f.isValid('Birds')
        !f.isValid('Fish')
        Facet.parseFacet('-species_group:"Birds"').isValid('Fish')
    }

    def "parseFacet reads an OR of values"() {
        when:
        Facet f = Facet.parseFacet('species_group:"Birds" OR species_group:"Fish"')

        then:
        f.type == 2
        f.fields as List == ['species_group']
        f.isValid('Fish')
        f.isValid('Birds')
        !f.isValid('Mammals')
        f.toString() == 'species_group:"Birds" OR species_group:"Fish"'
    }

    def "parseFacet reads an AND of ranges and reports the overall min and max"() {
        when:
        Facet f = Facet.parseFacet('el1:[1 TO 10] AND el2:[-5 TO 2.5]')

        then:
        f.type == 2
        f.fields as Set == ['el1', 'el2'] as Set
        f.min == -5d
        f.max == 10d
    }

    def "parseFacet returns null for empty input"() {
        expect:
        Facet.parseFacet(null) == null
        Facet.parseFacet('a') == null
    }

    /**
     * The numeric branch of isValid is "try { return includeRange == inside } finally { return false }";
     * a return in finally overrides the try, so every value is outside every range.
     */
    @PendingFeature(reason = 'Facet.isValid returns false from a finally block for every numeric facet')
    def "a range facet accepts the values in its range"() {
        given:
        Facet f = Facet.parseFacet('el1:[1 TO 10]')

        expect:
        f.isValid(5d)
        f.isValid('5')
        !f.isValid(11d)
        Facet.parseFacet('-el1:[1 TO 10]').isValid(11d)
    }
}
