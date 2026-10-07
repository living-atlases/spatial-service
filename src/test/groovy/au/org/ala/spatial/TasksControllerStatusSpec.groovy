package au.org.ala.spatial

import au.org.ala.web.AuthService
import grails.converters.JSON
import grails.testing.gorm.DataTest
import grails.testing.web.controllers.ControllerUnitTest
import spock.lang.PendingFeature
import spock.lang.Specification
import spock.lang.TempDir

import java.util.zip.ZipInputStream

/**
 * Task status polling and output downloads used by spatial-hub: /tasks/status/{id}, /tasks/show/{id},
 * /tasks/output/{taskId}/{file}
 */
class TasksControllerStatusSpec extends Specification implements ControllerUnitTest<TasksController>, DataTest {

    @TempDir
    File dataDir

    TasksService tasksService = Mock()

    @Override
    Set<String> getIncludePlugins() {
        ['core', 'eventBus', 'converters'].toSet()
    }

    def setupSpec() {
        mockDomains(Task, InputParameter, OutputParameter)
    }

    def setup() {
        BootStrapMarshallers.register()
        controller.tasksService = tasksService
        controller.authService = Mock(AuthService)
        controller.spatialAuthService = Mock(SpatialAuthService)
        controller.spatialConfig = new SpatialConfig(data: new SpatialConfig.DotDir(dir: dataDir.path))
    }

    private File publicFile(String path, String content) {
        File f = new File(dataDir, 'public/' + path)
        f.parentFile.mkdirs()
        f.text = content
        f
    }

    private static List<String> zipEntryNames(byte[] bytes) {
        List<String> names = []
        new ZipInputStream(new ByteArrayInputStream(bytes)).withCloseable { zis ->
            def e
            while ((e = zis.nextEntry) != null) names << e.name
        }
        names
    }

    def "status returns the task status with the history in time order"() {
        given:
        params.id = '5'

        when:
        controller.status()

        then:
        1 * tasksService.getStatus(5L) >> [status: 1, message: 'running', id: 5, name: 'Maxent',
                                            history: ['1700000000300': 'c', '1700000000100': 'a', '1700000000200': 'b']]
        response.json.status == 1
        response.json.message == 'running'
        response.contentAsString.indexOf('1700000000100') < response.contentAsString.indexOf('1700000000200')
        response.contentAsString.indexOf('1700000000200') < response.contentAsString.indexOf('1700000000300')
    }

    def "status with last only returns the history after that time"() {
        given:
        params.id = '5'
        params.last = '1700000000100'

        when:
        controller.status()

        then:
        1 * tasksService.getStatus(5L) >> [status: 1, history: ['1700000000300': 'c', '1700000000100': 'a', '1700000000200': 'b']]
        (response.json.history as Map) == ['1700000000200': 'b', '1700000000300': 'c']
    }

    def "status of a finished task includes the outputs"() {
        given:
        params.id = '6'

        when:
        controller.status()

        then:
        1 * tasksService.getStatus(6L) >> [status: 4, message: 'finished', output: [[name: 'files', file: '["a.csv"]']]]
        response.json.output[0].name == 'files'
    }

    @PendingFeature(reason = 'status does not check for an unknown task: NullPointerException on status.history')
    def "status of an unknown task is a 404"() {
        given:
        params.id = '404'

        when:
        controller.status()

        then:
        1 * tasksService.getStatus(404L) >> null
        response.status == 404
    }

    def "show of an unknown task is a 404"() {
        when:
        controller.show(404L)

        then:
        1 * tasksService.getStatus(404L) >> null
        response.status == 404
    }

    def "output streams a file of the task with a content type for its extension"() {
        given:
        publicFile('12/' + name, 'content')
        params.p1 = '12'
        params.p2 = name

        when:
        controller.output()

        then:
        response.status == 200
        response.contentType?.startsWith(contentType)
        response.getHeader('Content-Disposition') == 'attachment'
        response.contentAsString == 'content'

        where:
        name        | contentType
        'out.csv'   | 'application/csv'
        'out.txt'   | 'text/plain'
        'map.png'   | 'image/png'
        'map.jpg'   | 'image/jpeg'
        'report.pdf'| 'application/pdf'
    }

    def "output renders an html file as a page"() {
        given:
        publicFile('12/report.html', '<html><body>report</body></html>')
        params.p1 = '12'
        params.p2 = 'report'
        params.format = 'html'

        when:
        controller.output()

        then:
        response.contentType.startsWith('text/html')
        response.contentAsString == '<html><body>report</body></html>'
    }

    def "output of a file in a sub directory"() {
        given:
        publicFile('12/plots/a.png', 'png')
        params.p1 = '12'
        params.p2 = 'plots'
        params.p3 = 'a.png'

        when:
        controller.output()

        then:
        response.contentType.startsWith('image/png')
        response.contentAsString == 'png'
    }

    def "the filename parameter replaces the last path segment"() {
        given:
        publicFile('12/species.csv', 'csv')
        params.p1 = '12'
        params.p2 = 'download'
        params.filename = 'species.csv'

        when:
        controller.output()

        then:
        response.contentAsString == 'csv'
    }

    def "download.zip is built from the directories listed in spec.json when it is missing"() {
        given:
        publicFile('12/spec.json', ([download: ['plots']] as JSON).toString())
        publicFile('12/plots/a.png', 'a')
        publicFile('12/plots/b.png', 'b')
        params.p1 = '12'
        params.p2 = 'download.zip'

        when:
        controller.output()

        then:
        response.contentType.startsWith('application/zip')
        zipEntryNames(response.contentAsByteArray).sort() == ['a.png', 'b.png']
        new File(dataDir, 'public/12/download.zip').exists()
    }

    def "download.zip is built from the files listed in spec.json when it is missing"() {
        given:
        publicFile('12/spec.json', ([download: ['species.csv', 'plots']] as JSON).toString())
        publicFile('12/species.csv', 'csv')
        publicFile('12/plots/a.png', 'a')
        params.p1 = '12'
        params.p2 = 'download.zip'

        when:
        controller.output()

        then:
        zipEntryNames(response.contentAsByteArray).sort() == ['a.png', 'species.csv']
    }
}
