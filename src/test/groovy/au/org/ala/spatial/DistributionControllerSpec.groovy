package au.org.ala.spatial

import au.org.ala.spatial.dto.MapDTO
import grails.converters.JSON
import grails.testing.gorm.DataTest
import grails.testing.web.controllers.ControllerUnitTest
import spock.lang.Specification

/**
 * Expert distributions: /distributions, /distribution/{spcode}, /distribution/lsid(s)/{lsid}, /distribution/map/...
 */
class DistributionControllerSpec extends Specification implements ControllerUnitTest<DistributionController>, DataTest {

    DistributionsService distributionsService = Mock()
    AttributionService attributionService = Mock()

    @Override
    Set<String> getIncludePlugins() {
        ['core', 'eventBus', 'converters'].toSet()
    }

    def setupSpec() {
        mockDomain(Distributions)
    }

    def setup() {
        BootStrapMarshallers.register()
        controller.distributionsService = distributionsService
        controller.attributionService = attributionService
    }

    private static Distributions distribution(long spcode, String lsid) {
        new Distributions(spcode: spcode, lsid: lsid, scientific: 'species ' + spcode, type: 'e')
    }

    def "index passes the request parameters (paging, wkt, lsids) to the query without geometries"() {
        given:
        params.pageSize = '10'
        params.start = '20'
        params.wkt = 'POINT(1 2)'

        when:
        controller.index()

        then:
        1 * distributionsService.queryDistributions({ it.pageSize == '10' && it.start == '20' && it.wkt == 'POINT(1 2)' }, true, 'e') >> [distribution(1, 'a'), distribution(2, 'b')]
        response.json*.spcode == [1, 2]
    }

    def "lsid restores the '//' of an https lsid that the URL mapping collapses"() {
        given:
        params.lsid = 'https:/biodiversity.org.au/afd/taxa/0c3e2403'

        when:
        controller.lsid()

        then:
        1 * distributionsService.queryDistributions([lsids: 'https://biodiversity.org.au/afd/taxa/0c3e2403'], false, 'e') >> [distribution(1, 'x'), distribution(2, 'x')]
        response.status == 200
        response.json.spcode == 1
    }

    def "lsid of an urn is used as it is"() {
        given:
        params.lsid = 'urn:lsid:biodiversity.org.au:afd.taxon:1'

        when:
        controller.lsid()

        then:
        1 * distributionsService.queryDistributions([lsids: 'urn:lsid:biodiversity.org.au:afd.taxon:1'], false, 'e') >> [distribution(1, 'x')]
        response.status == 200
    }

    def "lsid leaves an https lsid that already has '//' unchanged"() {
        given:
        params.lsid = 'https://biodiversity.org.au/afd/taxa/0c3e2403'

        when:
        controller.lsid()

        then:
        1 * distributionsService.queryDistributions([lsids: 'https://biodiversity.org.au/afd/taxa/0c3e2403'], false, 'e') >> [distribution(1, 'x')]
    }

    def "lsid with nowkt=true does not return the geometry"() {
        given:
        params.lsid = 'urn:x'
        params.nowkt = 'true'

        when:
        controller.lsid()

        then:
        1 * distributionsService.queryDistributions(_, true, 'e') >> [distribution(1, 'x')]
    }

    def "lsid with nowkt=false returns the geometry"() {
        given:
        params.lsid = 'urn:x'
        params.nowkt = 'false'

        when:
        controller.lsid()

        then:
        1 * distributionsService.queryDistributions(_, false, 'e') >> [distribution(1, 'x')]
    }

    def "an lsid without distributions is a 404"() {
        given:
        params.lsid = 'urn:none'

        when:
        controller.lsid()

        then:
        1 * distributionsService.queryDistributions(*_) >> []
        response.status == 404
    }

    def "lsids returns every distribution of every lsid"() {
        given:
        params.lsid = 'https:/x.org/1,https:/x.org/2'

        when:
        controller.lsids()

        then:
        1 * distributionsService.queryDistributions([lsids: 'https://x.org/1,https://x.org/2'], false, 'e') >> [distribution(1, 'https://x.org/1'), distribution(2, 'https://x.org/2')]
        response.json*.lsid == ['https://x.org/1', 'https://x.org/2']
    }

    def "lsids without distributions is a 404"() {
        given:
        params.lsid = 'urn:none'

        when:
        controller.lsids()

        then:
        1 * distributionsService.queryDistributions(*_) >> []
        response.status == 404
    }

    def "show returns the distribution with that spcode"() {
        given:
        params.id = '37001001'

        when:
        controller.show()

        then:
        1 * distributionsService.show(37001001L, _, 'e') >> distribution(37001001, 'x')
        response.json.spcode == 37001001
    }

    def "show of an unknown spcode is a 404"() {
        given:
        params.id = '1'

        when:
        controller.show()

        then:
        1 * distributionsService.show(1L, _, 'e') >> null
        response.status == 404
    }

    def "show of a non numeric id is a 400"() {
        given:
        params.id = 'abc'

        when:
        controller.show()

        then:
        response.status == 400
    }

    def "the map of the first distribution of an lsid"() {
        given:
        params.lsid = 'urn:x'
        def d = distribution(1, 'urn:x')

        when:
        controller.lsidMapFirst()

        then:
        1 * distributionsService.queryDistributions([lsids: 'urn:x'], true, 'e') >> [d]
        1 * attributionService.getMapDTO(d) >> new MapDTO(dataResourceName: 'resource')
        response.json.dataResourceName == 'resource'
    }

    def "the map of an lsid without distributions is a 404"() {
        given:
        params.lsid = 'urn:x'

        when:
        controller.lsidMapFirst()

        then:
        1 * distributionsService.queryDistributions(*_) >> []
        response.status == 404
    }

    def "the maps of an lsid without distributions is an empty list"() {
        given:
        params.lsid = 'urn:x'

        when:
        controller.lsidMaps()

        then:
        1 * distributionsService.queryDistributions(*_) >> []
        response.contentAsString == '[]'
    }

    def "a point radius search error is a 404 with the message"() {
        when:
        controller.pointRadius()

        then:
        1 * distributionsService.pointRadius(_, 'e') >> 'invalid point'
        response.status == 404
        response.contentAsString == 'invalid point'
    }
}
