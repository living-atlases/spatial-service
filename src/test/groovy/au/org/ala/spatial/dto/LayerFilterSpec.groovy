package au.org.ala.spatial.dto

import spock.lang.Specification
import spock.lang.Unroll

/**
 * Environmental envelope filters ("el600,1,4:el801,9.6,20.3") used by the envelope process,
 * scatterplots and grid cutting.
 */
class LayerFilterSpec extends Specification {

    def "parseLayerFilters reads environmental filters with their min and max"() {
        when:
        LayerFilter[] f = LayerFilter.parseLayerFilters('ENVELOPE(el600,1,4:el801,-9.6,20.3)')

        then:
        f.length == 2
        f[0].layername == 'el600'
        f[0].minimum_value == 1d
        f[0].maximum_value == 4d
        f[1].layername == 'el801'
        f[1].minimum_value == -9.6d
        f[1].maximum_value == 20.3d
        !f[0].contextual
    }

    @Unroll
    def "parseLayerFilters accepts '#input'"() {
        expect:
        LayerFilter.parseLayerFilters(input)*.layername == ['el600', 'el801']

        where:
        input << ['el600,1,4:el801,9.6,20.3', 'ENVELOPE(el600,1,4:el801,9.6,20.3)', 'envelope(el600,1,4:el801,9.6,20.3)']
    }

    def "parseLayerFilter reads a contextual filter with its object ids"() {
        when:
        LayerFilter f = LayerFilter.parseLayerFilter('ENVELOPE(cl904,pid1,pid2,pid3)')

        then:
        f.layername == 'cl904'
        f.ids as List == ['pid1', 'pid2', 'pid3']
        f.contextual
    }

    def "parseLayerFilter rejects an environmental filter without a max"() {
        when:
        LayerFilter.parseLayerFilter('el600,1')

        then:
        thrown(ArrayIndexOutOfBoundsException)
    }

    @Unroll
    def "isValid(#value) for [1, 4] is #valid"() {
        expect:
        new LayerFilter('el1', 1, 4).isValid(value) == valid

        where:
        value       | valid
        1d          | true
        4d          | true
        2.5d        | true
        0.9999999d  | true      // within the float conversion tolerance
        0.999d      | false
        4.001d      | false
        Double.NaN  | false
    }

    def "the filters used by the envelope process (field:[min TO max]) parse"() {
        given: 'Envelope.groovy converts "el1:[1 TO 4]" to "el1,1,4"'
        String term = 'el1:[1.5 TO 4]'.replace(':[', ',').replace(' TO ', ',').replace(']', '')

        when:
        LayerFilter f = LayerFilter.parseLayerFilter(term)

        then:
        f.layername == 'el1'
        f.minimum_value == 1.5d
        f.maximum_value == 4d
    }
}
