package au.org.ala.spatial

import au.org.ala.spatial.dto.SearchObject
import grails.testing.web.controllers.ControllerUnitTest
import spock.lang.PendingFeature
import spock.lang.Specification

/**
 * Gazetteer search used by spatial-hub: /search?q=...&start=&limit=&include=&exclude=
 */
class SearchControllerSpec extends Specification implements ControllerUnitTest<SearchController> {

    SearchService searchService = Mock()

    @Override
    Set<String> getIncludePlugins() {
        ['core', 'eventBus', 'converters'].toSet()
    }

    def setup() {
        BootStrapMarshallers.register()
        controller.searchService = searchService
    }

    def "q is trimmed and lower cased and paging defaults to the first 20"() {
        given:
        params.q = '  Canberra '

        when:
        controller.search()

        then:
        1 * searchService.findByCriteria('canberra', 0, 20) >> [new SearchObject(pid: '1', name: 'Canberra')]
        response.json*.name == ['Canberra']
    }

    def "start and limit page the results"() {
        given:
        params.q = 'act'
        params.start = '40'
        params.limit = '10'

        when:
        controller.search()

        then:
        1 * searchService.findByCriteria('act', 40, 10) >> []
    }

    def "a URL encoded q is decoded"() {
        given:
        params.q = 'new%20south'

        when:
        controller.search()

        then:
        1 * searchService.findByCriteria('new south', 0, 20) >> []
    }

    def "include restricts the search to those fields"() {
        given:
        params.q = 'act'
        params.include = 'cl22,cl23'

        when:
        controller.search()

        then:
        1 * searchService.findByCriteria('act', 0, 20, ['cl22', 'cl23'], []) >> []
    }

    def "exclude removes those fields from the search"() {
        given:
        params.q = 'act'
        params.exclude = 'cl22'

        when:
        controller.search()

        then:
        1 * searchService.findByCriteria('act', 0, 20, [], ['cl22']) >> []
    }

    @PendingFeature(reason = 'search renders the 404 for a missing q but does not return, so it searches for "null"')
    def "a search without q is a 404 and does not search"() {
        when:
        controller.search()

        then:
        0 * searchService._
        response.status == 404
    }

    @PendingFeature(reason = "the already decoded q is decoded again, UriUtils.decode throws IllegalArgumentException for a '%' that is not an escape and only UnsupportedEncodingException is caught")
    def "a q with a percent sign is searched"() {
        given:
        params.q = '50% cover'

        when:
        controller.search()

        then:
        1 * searchService.findByCriteria('50% cover', 0, 20) >> []
        response.status == 200
    }
}
