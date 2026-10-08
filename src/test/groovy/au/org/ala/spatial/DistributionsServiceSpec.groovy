package au.org.ala.spatial

import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import spock.lang.Specification

/**
 * DistributionsService.queryDistributions builds the SQL behind /distributions, /distribution/lsid(s)/{lsid}
 * (expert distributions in spatial-hub) and the distribution lookups of the processes.
 */
class DistributionsServiceSpec extends Specification implements ServiceUnitTest<DistributionsService>, DataTest {

    FakeJdbc jdbc = new FakeJdbc()

    def setupSpec() {
        mockDomain(Distributions)
    }

    def setup() {
        service.dataSource = jdbc.dataSource
        service.spatialObjectsService = Mock(SpatialObjectsService)
        service.spatialConfig = new SpatialConfig(
                grails: new SpatialConfig.DotGrails(serverURL: 'https://spatial.example.org/ws'),
                geoserver: new SpatialConfig.DotGeoserver(url: 'https://spatial.example.org/geoserver'))
    }

    private static Map<String, Object> row(Map values) {
        // queryDistributions reads the columns of the select clause by position
        Map<String, Object> row = new LinkedHashMap<>()
        ['gid', 'spcode', 'scientific', 'authority_', 'common_nam', 'family', 'genus_name', 'specific_n', 'min_depth',
         'max_depth', 'pelagic_fl', 'coastal_fl', 'desmersal_fl', 'estuarine_fl', 'family_lsid', 'genus_lsid',
         'caab_species_number', 'caab_family_number', 'group_name', 'metadata_u', 'wmsurl', 'lsid', 'type',
         'area_name', 'pid', 'checklist_name', 'area_km', 'notes', 'geom_idx', 'image_quality', 'data_resource_uid',
         'endemic'].each { row.put(it, values[it]) }
        values.findAll { !row.containsKey(it.key) }.each { row.put(it.key, it.value) }
        row
    }

    def "without filters only the distribution type is used"() {
        when:
        service.queryDistributions([:], true, Distributions.EXPERT_DISTRIBUTION)

        then:
        jdbc.lastSql.contains(' from Distributions WHERE  type = ? ')
        jdbc.lastParameters == ['e']
        !jdbc.lastSql.contains('the_geom')
    }

    def "the geometry is selected unless nowkt"() {
        when:
        service.queryDistributions([:], false, Distributions.EXPERT_DISTRIBUTION)

        then:
        jdbc.lastSql.contains(', the_geom from Distributions')
    }

    def "comma separated lsids are OR'ed and bound as parameters"() {
        when:
        service.queryDistributions([lsids: 'https://id.example.org/1,https://id.example.org/2'], true, 'e')

        then:
        jdbc.lastSql.contains('WHERE ( lsid = ?  OR  lsid = ? ) AND  type = ? ')
        jdbc.lastParameters == ['https://id.example.org/1', 'https://id.example.org/2', 'e']
    }

    def "an lsid with quotes is bound, not concatenated"() {
        when:
        service.queryDistributions([lsids: "x' or '1'='1"], true, 'e')

        then:
        !jdbc.lastSql.contains("'1'='1")
        jdbc.lastParameters[0] == "x' or '1'='1"
    }

    def "geom_idx filters by geometry index"() {
        when:
        service.queryDistributions([geom_idx: '12'], true, 'e')

        then:
        jdbc.lastSql.contains('geom_idx = ?')
        jdbc.lastParameters == [12, 'e']
    }

    def "a list of data resource uids is OR'ed"() {
        when:
        service.queryDistributions([data_resource_uids: ['dr1', 'dr2'] as String[]], true, 'e')

        then:
        jdbc.lastSql.contains('( data_resource_uid = ?  OR  data_resource_uid = ? )')
        jdbc.lastParameters == ['dr1', 'dr2', 'e']
    }

    def "a single data resource uid filters by that uid"() {
        when:
        service.queryDistributions([data_resource_uids: 'dr123'], true, 'e')

        then:
        jdbc.lastParameters == ['dr123', 'e']
    }

    def "a wkt filter is bound and adds the intersection area"() {
        given:
        String wkt = 'POLYGON((110 -40,155 -40,155 -10,110 -10,110 -40))'

        when:
        service.queryDistributions([wkt: wkt], true, 'e')

        then:
        jdbc.lastSql.contains('ST_INTERSECTS(the_geom, ST_GEOMFROMTEXT( ? , 4326))')
        jdbc.lastSql.contains('as intersectArea')
        jdbc.lastParameters.count(wkt) == 2
        jdbc.lastParameters.contains('e')
    }

    def "a numeric wkt is the pid of an area"() {
        when:
        service.queryDistributions([wkt: '12345'], true, 'e')

        then:
        1 * service.spatialObjectsService.getObjectsGeometryById('12345', 'wkt') >> 'POINT(1 2)'
        jdbc.lastParameters.contains('POINT(1 2)')
    }

    def "pid is the pid of an area"() {
        when:
        service.queryDistributions([pid: '987'], true, 'e')

        then:
        1 * service.spatialObjectsService.getObjectsGeometryById('987', 'wkt') >> 'POINT(3 4)'
        jdbc.lastParameters.contains('POINT(3 4)')
    }

    def "pageSize and start page the results (#252)"() {
        when:
        service.queryDistributions([pageSize: '10', start: '20'], true, 'e')

        then:
        jdbc.lastSql.endsWith(' LIMIT ? OFFSET ?')
        jdbc.lastParameters == ['e', 10, 20]
    }

    def "start defaults to 0"() {
        when:
        service.queryDistributions([pageSize: 5], true, 'e')

        then:
        jdbc.lastParameters == ['e', 5, 0]
    }

    def "without pageSize all results are returned"() {
        when:
        service.queryDistributions([start: '20'], true, 'e')

        then:
        !jdbc.lastSql.contains('LIMIT')
    }

    def "paged results are sorted so that pages do not overlap (#252)"() {
        when:
        service.queryDistributions([pageSize: '10', start: '20'], true, 'e')

        then:
        jdbc.lastSql.contains('ORDER BY spcode LIMIT')
    }

    def "rows are returned as distributions with an image url and the geoserver url in the wms url"() {
        given:
        jdbc.results << [row(gid: 1L, spcode: 37001001L, scientific: 'Galaxias maculatus', lsid: 'urn:lsid:1',
                type: 'e', geom_idx: 7, wmsurl: '<COMMON_GEOSERVER_URL>/wms?layers=ALA:Distributions')]

        when:
        List<Distributions> list = service.queryDistributions([lsids: 'urn:lsid:1'], true, 'e')

        then:
        list.size() == 1
        list[0].spcode == 37001001L
        list[0].scientific == 'Galaxias maculatus'
        list[0].imageUrl == 'https://spatial.example.org/ws/distribution/map/png/7'
        list[0].wmsurl == 'https://spatial.example.org/geoserver/wms?layers=ALA:Distributions'
    }

    def "a wms url that starts with / is made absolute"() {
        given:
        def d = new Distributions(wmsurl: '/wms?layers=x')

        when:
        service.updateWMSUrl(d)

        then:
        d.wmsurl == 'https://spatial.example.org/geoserver/wms?layers=x'
    }

    def "an unknown distribution is not found"() {
        expect:
        service.show(1L, [:], 'e') == null
    }

    def "show finds a distribution by spcode and type"() {
        given:
        new Distributions(spcode: 42L, type: 'e', scientific: 'x', geom_idx: 3).tap { id = 42L }.save(validate: false)

        when:
        Distributions d = service.show(42L, [:], 'e')

        then:
        d.scientific == 'x'
        d.imageUrl.endsWith('/distribution/map/png/3')
    }

    def "an unknown distribution with nowkt is not found"() {
        expect:
        service.show(1L, [nowkt: 'true'], 'e') == null
    }
}
