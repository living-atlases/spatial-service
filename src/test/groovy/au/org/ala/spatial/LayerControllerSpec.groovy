package au.org.ala.spatial

import com.opencsv.CSVReader
import grails.testing.gorm.DataTest
import grails.testing.web.controllers.ControllerUnitTest
import org.springframework.http.MediaType
import spock.lang.PendingFeature
import spock.lang.Specification

/**
 * Layer metadata: /layers, /layer/{id}, /layers/search, /layers/layers.csv, /layers/rif-cs, /layers/view/more/{id}
 */
class LayerControllerSpec extends Specification implements ControllerUnitTest<LayerController>, DataTest {

    LayerService layerService = Mock()
    FieldService fieldService = Mock()

    @Override
    Set<String> getIncludePlugins() {
        ['core', 'eventBus', 'converters'].toSet()
    }

    def setupSpec() {
        mockDomains(Layers, Fields)
    }

    def setup() {
        BootStrapMarshallers.register()
        controller.layerService = layerService
        controller.fieldService = fieldService
        controller.spatialConfig = new SpatialConfig(
                download: new SpatialConfig.DotDownload(layer: new SpatialConfig.DotLayer(licence_levels: ['1'])))
    }

    private static Layers layer(Map props = [:]) {
        Layers l = new Layers([name: 'aus1', displayname: 'Australian states', description: 'States and territories',
                               type: 'Contextual', classification1: 'Area management', licence_level: '1',
                               uid: '22', enabled: true] + props)
        l.id = (props.id ?: 22L) as Long
        l
    }

    def "index lists the enabled layers"() {
        when:
        controller.index()

        then:
        1 * layerService.getLayers() >> [layer(), layer(id: 23L, name: 'aus2')]
        response.json*.name == ['aus1', 'aus2']
    }

    @PendingFeature(reason = 'index calls show(id) but show() takes no arguments: MissingMethodException')
    def "index with an id shows that layer"() {
        given:
        params.id = '22'

        when:
        controller.index()

        then:
        1 * layerService.getLayerById(22, false) >> layer()
        response.json.name == 'aus1'
    }

    def "show finds a layer by its numeric id"() {
        given:
        params.id = '22'

        when:
        controller.show()

        then:
        1 * layerService.getLayerById(22, false) >> layer()
        response.status == 200
        response.json.name == 'aus1'
        response.json.displayname == 'Australian states'
    }

    def "show finds a layer by its name"() {
        given:
        params.id = 'aus1'

        when:
        controller.show()

        then:
        0 * layerService.getLayerById(*_)
        1 * layerService.getLayerByName('aus1', false) >> layer()
        response.json.id == 22
    }

    def "show finds the layer of a field id"() {
        given:
        params.id = 'cl22'

        when:
        controller.show()

        then:
        1 * layerService.getLayerByName('cl22', false) >> null
        1 * fieldService.getFieldById('cl22', false) >> new Fields(id: 'cl22', spid: '22')
        1 * layerService.getLayerById(22, false) >> layer()
        response.json.name == 'aus1'
    }

    def "show of an unknown layer is a 404"() {
        given:
        params.id = 'nothing'

        when:
        controller.show()

        then:
        1 * layerService.getLayerByName('nothing', false) >> null
        1 * fieldService.getFieldById('nothing', false) >> null
        response.status == 404
    }

    def "search uses the q parameter"() {
        given:
        params.q = 'rain'

        when:
        controller.search()

        then:
        1 * layerService.getLayersByCriteria('rain') >> [layer(name: 'rainfall')]
        response.json*.name == ['rainfall']
    }

    def "layers.csv has a row per layer with the metadata"() {
        given:
        def dt = new Date(Date.UTC(120, 0, 31, 12, 0, 0))

        when:
        controller.csvlist()
        List<String[]> rows = new CSVReader(new StringReader(response.contentAsString)).readAll()

        then:
        1 * layerService.getLayers() >> [layer(description: 'line 1\nline 2', keywords: 'states, boundaries', dt_added: dt)]
        rows.size() == 3
        rows[1][0] == 'ID'
        rows[1].length == 20
        rows[2][0] == '22'
        rows[2][1] == 'aus1'
        rows[2][3] == 'line 1 line 2'
        rows[2][18] == 'states, boundaries'
        rows[2][19] ==~ /2020\/01\/3[01]/
    }

    @PendingFeature(reason = "csvlist sets the content type 'text/csv charset=UTF-8' and the header 'inlinefilename=...' (missing ';')")
    def "layers.csv is served as UTF-8 text/csv with a file name"() {
        when:
        controller.csvlist()

        then:
        1 * layerService.getLayers() >> [layer()]
        MediaType.parseMediaType(response.contentType).isCompatibleWith(MediaType.parseMediaType('text/csv'))
        MediaType.parseMediaType(response.contentType).charset.name() == 'UTF-8'
        response.getHeader('Content-Disposition') ==~ /inline;\s*filename="?ALA_Spatial_Layers.csv"?/
    }

    def "rif-cs has a registry object per layer"() {
        when:
        controller.'rif-cs'()
        def xml = new XmlSlurper().parseText(response.contentAsString)

        then:
        1 * layerService.getLayers() >> [layer(), layer(id: 23L, name: 'aus2', uid: '23')]
        response.contentType.startsWith('text/xml')
        xml.registryObject.size() == 2
        xml.registryObject[0].key.text() == 'ala.org.au/uid_22'
        xml.registryObject[1].collection.name.find { it.@type == 'abbreviated' }.namePart.text() == 'aus2'
    }

    @PendingFeature(reason = "rif-cs writes the layer name and classifications into the XML without escaping '&' and '<'")
    def "rif-cs is well formed XML for metadata with '&' and '<'"() {
        when:
        controller.'rif-cs'()
        def xml = new XmlSlurper().parseText(response.contentAsString)

        then:
        1 * layerService.getLayers() >> [layer(classification1: 'Land & water', classification2: 'depth < 10m')]
        xml.registryObject.collection.subject.find { it.@type == 'local' }.text() == 'Land & water'
    }

    def "more shows the layer and whether its licence allows a download"() {
        given:
        params.id = 'aus1'

        when:
        controller.more()

        then:
        1 * layerService.getLayerByName('aus1') >> layer(licence_level: level)
        model.layer.name == 'aus1'
        model.downloadAllowed == allowed

        where:
        level | allowed
        '1'   | true
        '3'   | false
    }

    @PendingFeature(reason = 'more calls downloadAllowed(null) for an unknown layer: NullPointerException')
    def "more of an unknown layer does not fail"() {
        given:
        params.id = 'nothing'

        when:
        controller.more()

        then:
        1 * layerService.getLayerByName('nothing') >> null
        1 * fieldService.getFieldById('nothing', false) >> null
        model.layer == null
        !model.downloadAllowed
    }
}
