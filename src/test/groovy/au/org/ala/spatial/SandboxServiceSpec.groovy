package au.org.ala.spatial

import au.org.ala.spatial.dto.SandboxIngress
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Input checks of the sandbox (user uploaded occurrences): /sandbox/status, /sandbox/delete
 */
class SandboxServiceSpec extends Specification {

    @TempDir
    File dataDir

    SandboxService service

    /** the user that SOLR has for the data resource */
    String owner

    static final String DR = '0f4e6f02-2c6a-4ad8-a5b3-6bd6c1a1e2f1'

    def setup() {
        // getUserId reads the owner from SOLR with the WebService of ala-ws-plugin
        service = new SandboxService() {
            @Override
            String getUserId(String id) {
                owner
            }
        }
        service.spatialConfig = new SpatialConfig(data: new SpatialConfig.DotDir(dir: dataDir.path),
                sandboxSolrUrl: 'https://solr.example.org/solr', sandboxSolrCollection: 'sandbox')
    }

    def "isValidUUID(#value) is #valid"() {
        expect:
        service.isValidUUID(value) == valid

        where:
        value                                    | valid
        DR                                       | true
        DR.toUpperCase()                         | false
        'not-a-uuid'                             | false
        ''                                       | false
        null                                     | false
        '1-1-1-1-1'                              | false
        DR + 'x'                                 | false
    }

    def "the status of an invalid id is null"() {
        expect:
        service.getStatus('x') == null
    }

    def "the status of an unknown upload is null"() {
        expect:
        service.getStatus(DR) == null
    }

    def "an upload waiting in the queue reports how many uploads are before it"() {
        given:
        def ingress = new SandboxIngress(dataResourceUid: DR, requestId: 5, status: 'running')
        service.queueItemStatus.put(DR, new SandboxService.StatusItem(service, ingress, null))
        service.queuePosition.set(2)

        when:
        def status = service.getStatus(DR)

        then:
        status.status == 'queued'
        status.message == 'waiting for 3 items to finish'
    }

    def "delete of an invalid id is refused"() {
        when:
        boolean deleted = service.delete('x', '42', true)

        then:
        !deleted
    }

    def "a user cannot delete the data resource of another user"() {
        given:
        owner = '7'

        when:
        boolean deleted = service.delete(DR, '42', false)

        then:
        !deleted
    }

    def "a user cannot delete an unknown data resource"() {
        given:
        owner = null

        when:
        boolean deleted = service.delete(DR, '42', false)

        then:
        !deleted
    }
}
