package au.org.ala.spatial

import grails.testing.web.UrlMappingsUnitTest
import spock.lang.Specification
import spock.lang.Unroll

/**
 * The /ws URLs used by spatial-hub (and documented in the OpenAPI annotations) reach the expected actions.
 */
class UrlMappingsSpec extends Specification implements UrlMappingsUnitTest<UrlMappings> {

    def setup() {
        mockController(DistributionController)
        mockController(FieldController)
        mockController(IntersectController)
        mockController(LayerController)
        mockController(ObjectController)
        mockController(SearchController)
        mockController(ShapesController)
        mockController(TabulationController)
        mockController(TasksController)
    }

    @Unroll
    def "#url is #controller.#action with #params"() {
        expect:
        verifyForwardUrlMapping(url, controller: controller, action: action) {
            def expected = delegate
            params.each { k, v -> expected."$k" = v }
        }

        where:
        url                                          | controller     | action             | params
        '/shape/wkt/123'                             | 'shapes'       | 'wkt'              | [pid: '123']
        '/shape/wkt/1~2~3'                           | 'shapes'       | 'wkt'              | [pid: '1~2~3']
        '/shape/kml/ENVELOPE42'                      | 'shapes'       | 'kml'              | [pid: 'ENVELOPE42']
        '/shape/geojson/123'                         | 'shapes'       | 'geojson'          | [pid: '123']
        '/shape/shp/123'                             | 'shapes'       | 'shp'              | [pid: '123']
        '/shape/shapefile/123'                       | 'shapes'       | 'shp'              | [pid: '123']
        '/shape/upload/wkt'                          | 'shapes'       | 'uploadWkt'        | [:]
        '/shape/upload/wkt/123'                      | 'shapes'       | 'updateWithWKT'    | [pid: '123']
        '/shape/upload/geojson'                      | 'shapes'       | 'uploadGeoJSON'    | [:]
        '/shape/upload/shp'                          | 'shapes'       | 'uploadShapeFile'  | [:]
        '/shape/upload/shp/abc/featureIndex'         | 'shapes'       | 'saveFeatureFromShapeFile' | [shapeId: 'abc']
        '/shape/upload/shp/abc/1,2'                  | 'shapes'       | 'saveFeatureFromShapeFile' | [shapeId: 'abc', featureIndex: '1,2']
        '/shape/upload/shp/image/abc/1,2'            | 'shapes'       | 'shapeImage'       | [shapeId: 'abc', featureIndexes: '1,2']
        '/shape/upload/123'                          | 'shapes'       | 'deleteShape'      | [pid: '123']
        '/distributions'                             | 'distribution' | 'index'            | [:]
        '/distribution/37001001'                     | 'distribution' | 'show'             | [id: '37001001']
        '/distribution/lsid/urn:lsid:biodiversity.org.au:afd.taxon:1' | 'distribution' | 'lsid' | [lsid: 'urn:lsid:biodiversity.org.au:afd.taxon:1']
        '/distribution/lsid/https:/biodiversity.org.au/afd/taxa/a-b' | 'distribution' | 'lsid' | [lsid: 'https:/biodiversity.org.au/afd/taxa/a-b']
        '/distribution/lsids/https:/x.org/1,https:/x.org/2' | 'distribution' | 'lsids' | [lsid: 'https:/x.org/1,https:/x.org/2']
        '/distribution/map/png/17'                   | 'distribution' | 'overviewMapPng'   | [imageId: '17']
        '/distribution/map/lsid/https:/x.org/1'      | 'distribution' | 'overviewMapPngLsid' | [lsid: 'https:/x.org/1']
        '/intersect/batch'                           | 'intersect'    | 'batch'            | [:]
        '/intersect/batch/1234'                      | 'intersect'    | 'batchStatus'      | [id: '1234']
        '/intersect/batch/download/1234'             | 'intersect'    | 'batchDownload'    | [id: '1234']
        '/intersect/cl22,el893/-35.3/149.1'          | 'intersect'    | 'intersect'        | [ids: 'cl22,el893', lat: '-35.3', lng: '149.1']
        '/intersect/pointradius/cl22/-35/149/10'     | 'intersect'    | 'pointRadius'      | [fid: 'cl22', lat: '-35', lng: '149', radius: '10']
        '/capabilities'                              | 'tasks'        | 'capabilities'     | [:]
        '/tasks/create'                              | 'tasks'        | 'create'           | [:]
        '/tasks/status/55'                           | 'tasks'        | 'status'           | [id: '55']
        '/tasks/cancel/55'                           | 'tasks'        | 'cancel'           | [id: '55']
        '/tasks/output/55/download.zip'              | 'tasks'        | 'output'           | [p1: '55']
        '/tasks/output/55/plots/a.png'               | 'tasks'        | 'output'           | [p1: '55', p2: 'plots', p3: 'a', format: 'png']
        '/layers'                                    | 'layer'        | 'index'            | [:]
        '/layer/cl22'                                | 'layer'        | 'show'             | [id: 'cl22']
        '/layers/search'                             | 'layer'        | 'search'           | [:]
        '/layers/layers.csv'                         | 'layer'        | 'csvlist'          | [:]
        '/layers/index'                              | 'layer'        | 'list'             | [:]
        '/fields'                                    | 'field'        | 'index'            | [:]
        '/field/cl22'                                | 'field'        | 'show'             | [id: 'cl22']
        '/fields/search'                             | 'field'        | 'search'           | [:]
        '/fields/layers/search'                      | 'field'        | 'searchLayers'     | [:]
        '/fieldsdb'                                  | 'field'        | 'db'               | [:]
        '/object/123'                                | 'object'       | 'show'             | [pid: '123']
        '/object/ENVELOPE42'                         | 'object'       | 'show'             | [pid: 'ENVELOPE42']
        '/objects/cl22'                              | 'object'       | 'fieldObjects'     | [fid: 'cl22']
        '/objects/cl22/-35.3/149.1'                  | 'object'       | 'listByLocation'   | [fid: 'cl22', lat: '-35.3', lng: '149.1']
        '/objects/inarea/cl22'                       | 'object'       | 'objectsInArea'    | [fid: 'cl22']
        '/object/intersect/123/-35.3/149.1'          | 'object'       | 'intersectObject'  | [pid: '123', lat: '-35.3', lng: '149.1']
        '/search'                                    | 'search'       | 'search'           | [:]
        '/tabulations'                               | 'tabulation'   | 'index'            | [:]
        '/tabulation/area/cl22/cl23/tabulation.csv'  | 'tabulation'   | 'show'             | [func1: 'area', fid1: 'cl22', fid2: 'cl23', type: 'csv']
        '/tabulation/data/cl22/cl23/tabulation.json' | 'tabulation'   | 'show'             | [func1: 'data', fid1: 'cl22', fid2: 'cl23', type: 'json']
        '/tabulation/cl22/123'                       | 'tabulation'   | 'single'           | [fid: 'cl22', pid: '123']
    }

    def "an id with a format extension keeps the id and sets the format"() {
        expect:
        verifyForwardUrlMapping('/field/cl22.json', controller: 'field', action: 'show') {
            id = 'cl22'
            format = 'json'
        }
    }
}
