package au.org.ala.spatial

import javax.sql.DataSource
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Statement
import java.sql.Types

/**
 * A minimal in-memory JDBC DataSource for services that use groovy.sql.Sql.
 *
 * Every statement is recorded with its SQL (named parameters already replaced by '?') and the bound
 * parameter values. Queries return the next result from {@link #results} (empty when there is none).
 */
class FakeJdbc {

    /** SQL of every executed statement */
    List<String> statements = []

    /** bound parameters (index 1..n) of every executed statement */
    List<Map<Integer, Object>> parameters = []

    /** rows returned by the next queries, in order. Each row is a column name to value map. */
    List<List<Map<String, Object>>> results = []

    final DataSource dataSource = proxy(DataSource) { Method m, Object[] args ->
        m.name == 'getConnection' ? connection() : null
    }

    String getLastSql() {
        statements ? statements.last() : null
    }

    List<Object> getLastParameters() {
        parameters ? parameters.last().sort { it.key }.collect { it.value } : []
    }

    private Connection connection() {
        proxy(Connection) { Method m, Object[] args ->
            switch (m.name) {
                case 'prepareStatement':
                case 'prepareCall':
                    return statement(args[0] as String)
                case 'createStatement':
                    return statement(null)
                case 'getAutoCommit':
                    return true
                default:
                    return null
            }
        }
    }

    private Statement statement(String preparedSql) {
        Map<Integer, Object> bound = [:]
        ResultSet current = null
        Class type = preparedSql == null ? Statement : PreparedStatement
        proxy(type) { Method m, Object[] args ->
            switch (m.name) {
                case 'setObject':
                case 'setString':
                case 'setInt':
                case 'setLong':
                case 'setDouble':
                case 'setBoolean':
                    bound.put(args[0] as Integer, args[1])
                    return null
                case 'setNull':
                    bound.put(args[0] as Integer, null)
                    return null
                case 'executeQuery':
                    record(preparedSql ?: args[0] as String, bound)
                    return current = resultSet()
                case 'execute':
                    record(preparedSql ?: args[0] as String, bound)
                    current = resultSet()
                    return true
                case 'executeUpdate':
                    record(preparedSql ?: args[0] as String, bound)
                    return 0
                case 'getResultSet':
                    return current
                case 'getUpdateCount':
                    return -1
                case 'getMoreResults':
                    return false
                default:
                    return null
            }
        }
    }

    private void record(String sql, Map<Integer, Object> bound) {
        statements << sql
        parameters << new LinkedHashMap<Integer, Object>(bound)
    }

    private ResultSet resultSet() {
        List<Map<String, Object>> rows = results ? results.remove(0) : []
        List<String> columns = rows ? rows[0].keySet().toList() : []
        int index = -1
        Object last = null

        def value = { Object key ->
            Map<String, Object> row = rows[index]
            if (key instanceof Number) {
                int i = key as int
                if (i < 1 || i > columns.size()) throw new java.sql.SQLException("column index out of range: " + i)
                last = row[columns[i - 1]]
            } else {
                String name = row.keySet().find { it.equalsIgnoreCase(key as String) }
                if (name == null) throw new java.sql.SQLException("no column " + key)
                last = row[name]
            }
            last
        }

        ResultSetMetaData metaData = proxy(ResultSetMetaData) { Method m, Object[] args ->
            switch (m.name) {
                case 'getColumnCount': return columns.size()
                case 'getColumnLabel':
                case 'getColumnName': return columns[(args[0] as int) - 1]
                case 'getColumnType': return Types.OTHER
                case 'getTableName': return ''
                default: return null
            }
        }

        proxy(ResultSet) { Method m, Object[] args ->
            switch (m.name) {
                case 'next': return ++index < rows.size()
                case 'getObject': return value(args[0])
                case 'getString': def v = value(args[0]); return v == null ? null : v.toString()
                case 'getInt': def v = value(args[0]); return v == null ? 0 : (v as Number).intValue()
                case 'getLong': def v = value(args[0]); return v == null ? 0L : (v as Number).longValue()
                case 'getDouble': def v = value(args[0]); return v == null ? 0d : (v as Number).doubleValue()
                case 'getBoolean': def v = value(args[0]); return v == null ? false : v as Boolean
                case 'wasNull': return last == null
                case 'getMetaData': return metaData
                case 'findColumn': return columns.findIndexOf { it.equalsIgnoreCase(args[0] as String) } + 1
                default: return null
            }
        }
    }

    private static <T> T proxy(Class<T> type, Closure handler) {
        (T) Proxy.newProxyInstance(FakeJdbc.classLoader, [type] as Class[], { Object p, Method m, Object[] args ->
            if (m.name == 'toString') return 'Fake' + type.simpleName
            if (m.name == 'hashCode') return System.identityHashCode(p)
            if (m.name == 'equals') return p.is(args[0])
            def result = handler.call(m, args)
            result != null ? result : defaultValue(m.returnType)
        } as InvocationHandler)
    }

    private static Object defaultValue(Class type) {
        if (!type.isPrimitive() || type == void.class) return null
        if (type == boolean.class) return false
        if (type == char.class) return (char) 0
        if (type == long.class) return 0L
        if (type == double.class) return 0d
        if (type == float.class) return 0f
        if (type == short.class) return (short) 0
        if (type == byte.class) return (byte) 0
        0
    }
}
