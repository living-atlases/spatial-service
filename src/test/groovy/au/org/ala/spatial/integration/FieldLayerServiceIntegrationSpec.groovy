package au.org.ala.spatial.integration

import au.org.ala.spatial.Fields
import au.org.ala.spatial.FieldService
import au.org.ala.spatial.LayerService
import au.org.ala.spatial.Layers
import au.org.ala.spatial.SpatialConfig
import groovy.sql.Sql
import org.grails.orm.hibernate.HibernateDatastore
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

/**
 * FieldService / LayerService against PostGIS: the GORM finders and the raw SQL keyword joins
 * behind /fields, /field, /layers, /layer and the layer/field search used by spatial-hub.
 */
@Requires({ PostgisDatabase.available })
class FieldLayerServiceIntegrationSpec extends Specification {

    static final String GEOSERVER = 'https://geoserver.example/geoserver'

    @Shared
    PostgisDatabase db

    @Shared
    HibernateDatastore datastore

    FieldService fieldService
    LayerService layerService

    def setupSpec() {
        db = new PostgisDatabase()
        db.withSql { Sql sql ->
            sql.execute("""INSERT INTO layers (id, name, enabled, displayname, displaypath, keywords, domain, type)
                VALUES (1, 'states', true,  'States', '/geoserver/states', 'administrative boundaries', 'terrestrial', 'Contextual'),
                       (2, 'rainfall', true, 'Annual rainfall', '/geoserver/rainfall', 'climate precipitation', 'terrestrial', 'Environmental'),
                       (3, 'disabled', false, 'Disabled layer', '/geoserver/x', 'climate', 'terrestrial', 'Environmental')""")
            sql.execute("""INSERT INTO fields (id, name, "desc", type, spid, sid, enabled, indb, namesearch, "intersect", analysis)
                VALUES ('cl1',  'Australian states', '', 'c', '1', 'ste',  true,  true,  true,  true,  true),
                       ('el2',  'Rainfall',          '', 'e', '2', 'rain', true,  false, false, true,  true),
                       ('cl3',  'From disabled',     '', 'c', '3', 'x',    true,  false, false, true,  true),
                       ('cl9',  'Disabled field',    '', 'c', '1', 'ste',  false, false, false, true,  true)""")
        }
        datastore = new HibernateDatastore(db.gormConfig('none'), Fields, Layers)
    }

    def cleanupSpec() {
        datastore?.close()
        db?.drop()
    }

    def setup() {
        def config = new SpatialConfig(geoserver: new SpatialConfig.DotGeoserver(url: GEOSERVER))
        fieldService = new FieldService(dataSource: db.dataSource, spatialConfig: config)
        layerService = new LayerService(dataSource: db.dataSource, fieldService: fieldService)
    }

    def inSession(Closure c) { Fields.withNewSession { c() } }

    def "getFieldsByDB returns only enabled in-database fields"() {
        expect:
        inSession { fieldService.getFieldsByDB() }*.id == ['cl1']
    }

    def "getFields(false) returns enabled fields, getFields(true) includes disabled"() {
        expect:
        inSession { fieldService.getFields(false) }*.id.sort() == ['cl1', 'cl3', 'el2']
        inSession { fieldService.getFields(true) }*.id.sort() == ['cl1', 'cl3', 'cl9', 'el2']
    }

    def "countBySpid counts fields of a layer regardless of enabled"() {
        expect:
        inSession { fieldService.countBySpid('1') } == 2
        inSession { fieldService.countBySpid('2') } == 1
    }

    @Unroll
    def "getByKeywords(#keywords) joins enabled fields and enabled layers and matches #expected"() {
        expect:
        inSession { fieldService.getByKeywords(keywords) }*.id.sort() == expected

        where:
        keywords        | expected
        'rainfall'      | ['el2']            // field name
        'precipitation' | ['el2']            // layer keywords
        'states'        | ['cl1']            // layer name
        'administrative'| ['cl1']            // layer keywords
        'climate'       | ['el2']            // layer 3 is disabled, so cl3 is excluded
        'nomatch'       | []
    }

    def "getByKeywords(null) returns every enabled field joined to an enabled layer"() {
        expect:
        inSession { fieldService.getByKeywords(null) }*.id.sort() == ['cl1', 'el2']
    }

    def "getByKeywords resolves the layer display path against the geoserver and sets the style"() {
        when:
        def field = inSession { fieldService.getByKeywords('rainfall') }[0]

        then:
        field.layer.displaypath == GEOSERVER + '/geoserver/rainfall&style=el2'
        field.layer.displayname == 'Rainfall'
    }

    def "getLayers returns only enabled layers with the geoserver display path"() {
        when:
        def layers = inSession { layerService.getLayers() }

        then:
        layers*.name.sort() == ['rainfall', 'states']
        layers.find { it.name == 'states' }.displaypath == GEOSERVER + '/geoserver/states'
    }

    def "getLayersByEnvironment and getLayersByContextual filter by type and enabled"() {
        expect:
        inSession { layerService.getLayersByEnvironment() }*.name == ['rainfall']
        inSession { layerService.getLayersByContextual() }*.name == ['states']
    }

    @Unroll
    def "getLayerById(#id, #enabledOnly) returns #name"() {
        expect:
        inSession { layerService.getLayerById(id, enabledOnly) }?.name == name

        where:
        id | enabledOnly | name
        1L | true        | 'states'
        3L | true        | null
        3L | false       | 'disabled'
        9L | false       | null
    }

    def "getLayerByName respects the enabled flag"() {
        expect:
        inSession { layerService.getLayerByName('rainfall', true) }?.id == 2
        inSession { layerService.getLayerByName('disabled', true) } == null
        inSession { layerService.getLayerByName('disabled', false) }?.id == 3
    }

    @Unroll
    def "getLayersByCriteria(#keywords) matches enabled layers #expected"() {
        expect:
        inSession { layerService.getLayersByCriteria(keywords) }*.name.sort() == expected

        where:
        keywords     | expected
        'rainfall'   | ['rainfall']
        'boundaries' | ['states']
        'climate'    | ['rainfall']   // layer 3 is disabled
        'terrestrial'| ['rainfall', 'states']
    }
}
