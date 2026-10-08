package au.org.ala.spatial

import spock.lang.Specification

/**
 * SpatialObjectsService queries that can be checked without PostGIS.
 */
class SpatialObjectsServiceSqlSpec extends Specification {

    FakeJdbc jdbc = new FakeJdbc()
    SpatialObjectsService service

    def setup() {
        service = new SpatialObjectsService()
        service.dataSource = jdbc.dataSource
        service.spatialConfig = new SpatialConfig()
    }

    def "wkt of several objects is the collected geometry"() {
        given:
        jdbc.results << [[wkt: 'MULTIPOLYGON(((1 1,1 2,2 2,2 1,1 1)),((3 3,3 4,4 4,4 3,3 3)))']]
        def os = new ByteArrayOutputStream()

        when:
        service.wkt('1~2', os)

        then:
        jdbc.lastSql.contains("where pid in ('1','2')")
        os.toString() == 'MULTIPOLYGON(((1 1,1 2,2 2,2 1,1 1)),((3 3,3 4,4 4,4 3,3 3)))'
    }

    def "objects of a field within an area are limited"() {
        when:
        service.getObjectsByIdAndArea('cl22', 10, 'POLYGON((0 0,1 0,1 1,0 1,0 0))')

        then:
        jdbc.lastSql.contains('WHERE o.fid = ? AND ST_Within(the_geom, ST_GeomFromText( ? , 4326)) limit ?')
        jdbc.lastParameters == ['cl22', 'POLYGON((0 0,1 0,1 1,0 1,0 0))', 10]
    }

    def "objects of a field within another object: the SQL has no repeated AND"() {
        when:
        service.getObjectsByIdAndIntersection('cl22', 10, '123')

        then:
        jdbc.lastParameters == ['123', 'cl22', 10]
        !(jdbc.lastSql =~ /(?i)\band\s+and\b/)
    }
}
