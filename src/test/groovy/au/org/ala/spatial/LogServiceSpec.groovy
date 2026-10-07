package au.org.ala.spatial

import grails.testing.services.ServiceUnitTest
import spock.lang.PendingFeature
import spock.lang.Specification

/**
 * LogService builds the SQL of /log/search (usage reports of spatial-hub tools).
 */
class LogServiceSpec extends Specification implements ServiceUnitTest<LogService> {

    FakeJdbc jdbc = new FakeJdbc()

    def setup() {
        service.dataSource = jdbc.dataSource
        // normally read from the Log table by init()
        service.category1 = ['Tool', 'Import']
        service.category2 = ['Area', 'Tabulation']
    }

    def "columnFormat maps the request names to the column names"() {
        expect:
        service.columnFormat('sessionId') == 'session_id'
        service.columnFormat('userId') == 'user_id'
        service.columnFormat('category1') == 'category1'
        service.columnFormat(null) == null
    }

    def "count columns for #countBy"() {
        expect:
        service.count(countBy) as Set == expected as Set

        where:
        countBy                | expected
        'record'               | ['count(*) AS records']
        'session,user'         | ['count(distinct session_id) AS sessions', 'count(distinct user_id) AS users']
        'category1'            | ["SUM(CASE WHEN category1 = 'Tool' THEN 1 ELSE 0 END) AS Tool", "SUM(CASE WHEN category1 = 'Import' THEN 1 ELSE 0 END) AS Import"]
        'unknown'              | []
        null                   | []
    }

    def "a user only sees their own logs"() {
        expect:
        service.where([admin: 'true'], 'u1', false).startsWith("WHERE user_id = 'u1' AND ")
        service.where([:], 'u1', true).startsWith("WHERE user_id = 'u1' AND ")
    }

    def "an admin with admin=true sees every log"() {
        expect:
        !service.where([admin: 'true'], 'u1', true).contains('user_id =')
    }

    def "only known categories are used as filters"() {
        when:
        String where = service.where([category1: 'Tool', category2: 'Unknown'], 'u1', false)

        then:
        where.contains("category1 = 'Tool'")
        !where.contains('category2 =')
    }

    def "a numeric sessionId filters by session"() {
        expect:
        service.where([sessionId: '12345'], 'u1', false).contains("session_id = '12345'")
    }

    def "a date range filters by creation date"() {
        expect:
        service.where([startDate: '2023-01-01', endDate: '2023-12-31'], 'u1', false)
                .contains("created between '2023-01-01' and '2023-12-31'")
    }

    @PendingFeature(reason = "endDate=now formats an Instant with ISO_LOCAL_DATE: UnsupportedTemporalTypeException")
    def "endDate=now is today"() {
        when:
        String where = service.where([startDate: '2023-01-01', endDate: 'now'], 'u1', false)

        then:
        where.contains("created between '2023-01-01' and '" + java.time.LocalDate.now() + "'")
    }

    def "the count SQL counts the distinct group by columns"() {
        expect:
        service.buildCountSql([groupBy: 'category2'], 'u1', false) ==
                "SELECT COUNT(DISTINCT category2) FROM Log " + service.where([:], 'u1', false)
    }

    def "a grouped search selects the group by and count columns with paging"() {
        when:
        service.search([groupBy: 'category2', countBy: 'record,user', max: '5', offset: '10'], 'u1', true)

        then:
        jdbc.lastSql.startsWith('SELECT category2,')
        jdbc.lastSql.contains('count(*) AS records')
        jdbc.lastSql.contains('count(distinct user_id) AS users FROM Log WHERE ') || jdbc.lastSql.contains('count(distinct user_id) AS users,')
        jdbc.lastSql.endsWith(' GROUP BY category2 ORDER BY category2 DESC LIMIT ? OFFSET ?')
        jdbc.lastParameters == [5, 10]
    }

    def "a search for a type of log is bound as a parameter"() {
        when:
        service.search([category2: 'Area'], 'u1', true)

        then:
        jdbc.lastSql == 'SELECT user_id, category2, session_id, data, created FROM Log WHERE category2 = ? ORDER BY created DESC LIMIT ? OFFSET ?'
        jdbc.lastParameters == ['Area', 10, 0]
    }

    def "a grouped search maps the columns of a row"() {
        given:
        jdbc.results << [[category2: 'Area', records: 3]]

        when:
        def result = service.search([groupBy: 'category2', countBy: 'record'], 'u1', true)

        then:
        result == [[category2: 'Area', records: 3]]
    }

    @PendingFeature(reason = 'LogService.search reads the ResultSet with if (rs.next()) instead of while, so only the first row is returned')
    def "a search returns every row"() {
        given:
        jdbc.results << [[category2: 'Area', records: 3], [category2: 'Tabulation', records: 2]]

        when:
        def result = service.search([groupBy: 'category2', countBy: 'record'], 'u1', true)

        then:
        result == [[category2: 'Area', records: 3], [category2: 'Tabulation', records: 2]]
    }

    def "searchCount returns the count"() {
        given:
        jdbc.results << [[count: 42]]

        expect:
        service.searchCount([:], 'u1', false) == 42
    }
}
