package au.org.ala.spatial.integration

import groovy.sql.Sql
import org.postgresql.ds.PGSimpleDataSource

import javax.sql.DataSource

/**
 * A throw-away PostGIS database created from docker/postgres/init_layersdb.sql (the schema used by
 * the docker setup), for the specs in this package.
 *
 * The specs run only when POSTGIS_URL points at a PostgreSQL server with PostGIS available, e.g.
 *
 *   POSTGIS_URL=jdbc:postgresql://localhost:5432/postgres POSTGIS_USER=postgres POSTGIS_PASSWORD=postgres ./gradlew test
 *
 * The user must be allowed to create databases and the postgis extension.
 */
class PostgisDatabase {

    static final File INIT_SQL = new File('docker/postgres/init_layersdb.sql')

    static boolean isAvailable() {
        System.getenv('POSTGIS_URL')
    }

    final String name = 'layersdb_it_' + UUID.randomUUID().toString().replace('-', '').substring(0, 12)
    final String url
    final String user = System.getenv('POSTGIS_USER') ?: 'postgres'
    final String password = System.getenv('POSTGIS_PASSWORD') ?: 'postgres'

    PostgisDatabase() {
        String admin = System.getenv('POSTGIS_URL')
        url = admin.replaceFirst('/[^/?]*(\\?|$)', '/' + name + '$1')
        withAdmin { it.execute('CREATE DATABASE ' + name) }
        withSql { Sql sql ->
            sql.connection.createStatement().withCloseable { it.execute(INIT_SQL.getText('UTF-8')) }
        }
    }

    DataSource getDataSource() {
        def ds = new PGSimpleDataSource()
        ds.url = url
        ds.user = user
        ds.password = password
        ds
    }

    /** GORM configuration for a HibernateDatastore on this database. */
    Map<String, Object> gormConfig(String dbCreate) {
        ['dataSource.url'            : url,
         'dataSource.username'       : user,
         'dataSource.password'       : password,
         'dataSource.driverClassName': 'org.postgresql.Driver',
         'dataSource.dbCreate'       : dbCreate,
         'hibernate.dialect'         : 'org.hibernate.spatial.dialect.postgis.PostgisPG9Dialect']
    }

    def withSql(Closure c) {
        Sql sql = Sql.newInstance(url, user, password, 'org.postgresql.Driver')
        try {
            c(sql)
        } finally {
            sql.close()
        }
    }

    private void withAdmin(Closure c) {
        Sql sql = Sql.newInstance(System.getenv('POSTGIS_URL'), user, password, 'org.postgresql.Driver')
        try {
            c(sql)
        } finally {
            sql.close()
        }
    }

    void drop() {
        withAdmin { it.execute('DROP DATABASE IF EXISTS ' + name + ' WITH (FORCE)') }
    }
}
