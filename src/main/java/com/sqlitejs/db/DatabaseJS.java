package com.sqlitejs.db;

import com.sqlitejs.Config;
import com.sqlitejs.SQLiteJS;
import com.sqlitejs.kubejs.ScriptCallback;
import dev.latvian.mods.rhino.Function;
import dev.latvian.mods.rhino.NativeArray;
import dev.latvian.mods.rhino.Undefined;
import dev.latvian.mods.rhino.Wrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 单个 SQLite 数据库的脚本侧操作对象，由全局绑定 {@code SQLite} 创建。
 * <p>
 * 所有同步操作在对象内部锁上串行化，异步操作与同步操作共享同一 JDBC 连接，
 * 因此脚本无需关心并发问题。连接关闭后再次调用任意操作会自动重连。
 */
public final class DatabaseJS {
    private final String name;
    private final Path file;
    private final Object lock = new Object();
    private final Set<String> owners = new CopyOnWriteArraySet<>();

    private Connection connection;

    DatabaseJS(String name, Path file) {
        this.name = name;
        this.file = file;
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return file.toAbsolutePath().toString();
    }

    Path getFile() {
        return file;
    }

    void addOwner(String tag) {
        owners.add(tag);
    }

    boolean isOwnedBy(String tag) {
        return owners.contains(tag);
    }

    // ------------------------------------------------------------------
    // 连接管理
    // ------------------------------------------------------------------

    /** 获取连接；未打开时懒加载打开并应用 PRAGMA 配置。 */
    private Connection conn() {
        if (connection == null) {
            try {
                Files.createDirectories(file.getParent());
                connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
                applyPragmas();
            } catch (Exception e) {
                connection = null;
                throw new RuntimeException("SQLiteJS: 无法打开数据库 '" + name + "': " + e.getMessage(), e);
            }
        }
        return connection;
    }

    private void applyPragmas() throws SQLException {
        execRaw("PRAGMA busy_timeout = " + Config.BUSY_TIMEOUT_MS.get());
        String journalMode = Config.JOURNAL_MODE.get().trim().toUpperCase();
        if (List.of("WAL", "DELETE", "TRUNCATE", "PERSIST", "MEMORY", "OFF").contains(journalMode)) {
            execRaw("PRAGMA journal_mode = " + journalMode);
        }
        if (Config.FOREIGN_KEYS.get()) {
            execRaw("PRAGMA foreign_keys = ON");
        }
    }

    /** 执行不需要读取结果的 PRAGMA 语句。 */
    private void execRaw(String sql) throws SQLException {
        try (Statement st = conn().createStatement()) {
            st.execute(sql);
        }
    }

    public boolean isOpen() {
        return connection != null;
    }

    /** 关闭连接；下次任意操作会自动重新打开。 */
    public void close() {
        synchronized (lock) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    SQLiteJS.LOGGER.warn("[SQLiteJS] Error while closing database '{}': {}", name, e.getMessage());
                } finally {
                    connection = null;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 通用 SQL
    // ------------------------------------------------------------------

    /**
     * 执行任意 SQL（DDL / INSERT / UPDATE / DELETE），返回受影响行数。
     * 示例：db.execute('CREATE TABLE IF NOT EXISTS players (name TEXT, diamonds INTEGER)')
     */
    public int execute(String sql, Object... params) {
        Object[] p = normalizeParams(params);
        synchronized (lock) {
            try (PreparedStatement ps = conn().prepareStatement(sql)) {
                bind(ps, p);
                return ps.executeUpdate();
            } catch (SQLException e) {
                throw wrap(sql, e);
            }
        }
    }

    /**
     * 执行查询，返回行列表。每行是 Map：列名 -> 值，
     * 脚本中可用 row.name 或 row.get('name') 取值。
     */
    public List<Map<String, Object>> query(String sql, Object... params) {
        Object[] p = normalizeParams(params);
        synchronized (lock) {
            try (PreparedStatement ps = conn().prepareStatement(sql)) {
                bind(ps, p);
                try (ResultSet rs = ps.executeQuery()) {
                    return readRows(rs);
                }
            } catch (SQLException e) {
                throw wrap(sql, e);
            }
        }
    }

    /** 查询第一行，无结果返回 null。 */
    public Map<String, Object> queryRow(String sql, Object... params) {
        List<Map<String, Object>> rows = query(sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询第一行第一列的值（如 COUNT/SUM/MAX），无结果返回 null。 */
    public Object queryValue(String sql, Object... params) {
        Map<String, Object> row = queryRow(sql, params);
        if (row == null || row.isEmpty()) {
            return null;
        }
        return row.values().iterator().next();
    }

    // ------------------------------------------------------------------
    // CRUD 便捷方法
    // ------------------------------------------------------------------

    /**
     * 插入一行，返回自增 rowid（无 rowid 表返回 -1）。
     * 示例：db.insert('players', {name: 'Steve', diamonds: 10})
     */
    public long insert(String table, Map<String, Object> row) {
        String t = ident(table, "表");
        if (row == null || row.isEmpty()) {
            throw new IllegalArgumentException("SQLiteJS: insert 需要至少一个列值");
        }
        StringBuilder cols = new StringBuilder();
        StringBuilder marks = new StringBuilder();
        Object[] values = new Object[row.size()];
        int i = 0;
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (i > 0) {
                cols.append(", ");
                marks.append(", ");
            }
            cols.append(ident(entry.getKey(), "列"));
            marks.append('?');
            values[i++] = normalize(entry.getValue());
        }
        String sql = "INSERT INTO " + t + " (" + cols + ") VALUES (" + marks + ")";
        synchronized (lock) {
            try (PreparedStatement ps = conn().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bind(ps, values);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        return rs.getLong(1);
                    }
                }
                return -1L;
            } catch (SQLException e) {
                throw wrap(sql, e);
            }
        }
    }

    /** 更新全表：db.update('players', {diamonds: 0}) */
    public int update(String table, Map<String, Object> set) {
        return update(table, set, (Map<String, Object>) null);
    }

    /**
     * 按等值条件更新：db.update('players', {diamonds: 20}, {name: 'Steve'})
     * where 值为 null 表示 IS NULL。
     */
    public int update(String table, Map<String, Object> set, Map<String, Object> where) {
        String t = ident(table, "表");
        if (set == null || set.isEmpty()) {
            throw new IllegalArgumentException("SQLiteJS: update 需要至少一个 SET 列值");
        }
        StringBuilder sql = new StringBuilder("UPDATE ").append(t).append(" SET ");
        List<Object> values = new ArrayList<>();
        appendAssignments(sql, set, values);
        appendWhere(sql, where, values);
        return executeUpdate(sql.toString(), values.toArray());
    }

    /** 按自定义 WHERE 更新：db.update('players', {diamonds: 20}, 'diamonds < ?', [10]) */
    public int update(String table, Map<String, Object> set, String where, Object... params) {
        String t = ident(table, "表");
        if (set == null || set.isEmpty()) {
            throw new IllegalArgumentException("SQLiteJS: update 需要至少一个 SET 列值");
        }
        StringBuilder sql = new StringBuilder("UPDATE ").append(t).append(" SET ");
        List<Object> values = new ArrayList<>();
        appendAssignments(sql, set, values);
        if (where != null && !where.isBlank()) {
            values.addAll(Arrays.asList(normalizeParams(params)));
            sql.append(" WHERE ").append(where);
        }
        return executeUpdate(sql.toString(), values.toArray());
    }

    /** 删除全表数据。 */
    public int delete(String table) {
        return executeUpdate("DELETE FROM " + ident(table, "表"), new Object[0]);
    }

    /** 按等值条件删除：db.delete('players', {name: 'Steve'}) */
    public int delete(String table, Map<String, Object> where) {
        String t = ident(table, "表");
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(t);
        List<Object> values = new ArrayList<>();
        appendWhere(sql, where, values);
        return executeUpdate(sql.toString(), values.toArray());
    }

    /** 按自定义 WHERE 删除：db.delete('players', 'diamonds < ?', [10]) */
    public int delete(String table, String where, Object... params) {
        String t = ident(table, "表");
        List<Object> values = new ArrayList<>();
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(t);
        if (where != null && !where.isBlank()) {
            values.addAll(Arrays.asList(normalizeParams(params)));
            sql.append(" WHERE ").append(where);
        }
        return executeUpdate(sql.toString(), values.toArray());
    }

    /** 全表行数。 */
    public int count(String table) {
        return asInt(queryValue("SELECT COUNT(*) FROM " + ident(table, "表")));
    }

    /** 按等值条件计数。 */
    public int count(String table, Map<String, Object> where) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ").append(ident(table, "表"));
        List<Object> values = new ArrayList<>();
        appendWhere(sql, where, values);
        return asInt(queryValue(sql.toString(), values.toArray()));
    }

    /** 按自定义条件计数。 */
    public int count(String table, String where, Object... params) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ").append(ident(table, "表"));
        if (where != null && !where.isBlank()) {
            sql.append(" WHERE ").append(where);
        }
        return asInt(queryValue(sql.toString(), params));
    }

    /** 按等值条件判断行是否存在。 */
    public boolean has(String table, Map<String, Object> where) {
        return count(table, where) > 0;
    }

    /** 按自定义条件判断行是否存在。 */
    public boolean has(String table, String where, Object... params) {
        return count(table, where, params) > 0;
    }

    /** 所有用户表名（不含 sqlite_ 内部表）。 */
    public List<String> tables() {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> row : query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            Object v = row.get("name");
            names.add(v == null ? null : v.toString());
        }
        return names;
    }

    /** 表结构：每列为 PRAGMA table_info 的一行（cid/name/type/notnull/dflt_value/pk）。 */
    public List<Map<String, Object>> columns(String table) {
        return query("PRAGMA table_info(" + ident(table, "表") + ")");
    }

    private int executeUpdate(String sql, Object[] params) {
        synchronized (lock) {
            try (PreparedStatement ps = conn().prepareStatement(sql)) {
                bind(ps, params);
                return ps.executeUpdate();
            } catch (SQLException e) {
                throw wrap(sql, e);
            }
        }
    }

    // ------------------------------------------------------------------
    // 事务
    // ------------------------------------------------------------------

    /** 开启手动事务。 */
    public void begin() {
        synchronized (lock) {
            try {
                conn().setAutoCommit(false);
            } catch (SQLException e) {
                throw wrap("BEGIN", e);
            }
        }
    }

    /** 提交事务并恢复自动提交。 */
    public void commit() {
        synchronized (lock) {
            try {
                if (!conn().getAutoCommit()) {
                    connection.commit();
                    connection.setAutoCommit(true);
                }
            } catch (SQLException e) {
                throw wrap("COMMIT", e);
            }
        }
    }

    /** 回滚事务并恢复自动提交。 */
    public void rollback() {
        synchronized (lock) {
            try {
                if (!conn().getAutoCommit()) {
                    connection.rollback();
                    connection.setAutoCommit(true);
                }
            } catch (SQLException e) {
                throw wrap("ROLLBACK", e);
            }
        }
    }

    /** 当前是否处于手动事务中。 */
    public boolean inTransaction() {
        synchronized (lock) {
            try {
                return !conn().getAutoCommit();
            } catch (SQLException e) {
                throw wrap("IN TRANSACTION", e);
            }
        }
    }

    /**
     * 回调式事务：begin → 执行 fn（参数为本数据库对象）→ commit；
     * fn 抛出任何异常时回滚并继续向上抛出，返回 fn 的返回值。
     * <p>
     * 示例：let ok = db.transaction(tx => { tx.insert('a', {...}); tx.update('b', {...}, {...}); })
     */
    public Object transaction(Object fn) {
        ScriptCallback.Callback1 callback = ScriptCallback.of1(fn);
        synchronized (lock) {
            try {
                conn().setAutoCommit(false);
                Object result = callback.invoke(this);
                connection.commit();
                connection.setAutoCommit(true);
                return result;
            } catch (Throwable t) {
                try {
                    if (!connection.getAutoCommit()) {
                        connection.rollback();
                        connection.setAutoCommit(true);
                    }
                } catch (SQLException e) {
                    SQLiteJS.LOGGER.error("[SQLiteJS] Rollback failed for '{}': {}", name, e.getMessage());
                }
                if (t instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (t instanceof Error error) {
                    throw error;
                }
                throw new RuntimeException("SQLiteJS: 事务执行失败: " + t.getMessage(), t);
            }
        }
    }

    // ------------------------------------------------------------------
    // 批量
    // ------------------------------------------------------------------

    /**
     * 批量执行同一条 SQL，rows 为参数数组的列表。
     * 示例：db.batch('INSERT INTO players (name, diamonds) VALUES (?, ?)', [['A', 1], ['B', 2]])
     * 元素也可以不是数组（视为单参数）：db.batch('UPDATE t SET x = 1 WHERE id = ?', [1, 2, 3])
     */
    public int[] batch(String sql, Object rows) {
        List<Object[]> list = toParamList(rows);
        synchronized (lock) {
            try (PreparedStatement ps = conn().prepareStatement(sql)) {
                for (Object[] p : list) {
                    bind(ps, p);
                    ps.addBatch();
                }
                return ps.executeBatch();
            } catch (SQLException e) {
                throw wrap(sql, e);
            }
        }
    }

    // ------------------------------------------------------------------
    // 异步（后台线程执行，回调回到主线程）
    // ------------------------------------------------------------------

    /**
     * 异步查询。可选的最后一个参数为回调函数 fn(result, error)，
     * 结果回到服务器主线程后触发；返回 CompletableFuture 可用于链式处理。
     */
    public CompletableFuture<List<Map<String, Object>>> asyncQuery(String sql, Object... params) {
        Split split = splitCallback(params);
        CompletableFuture<List<Map<String, Object>>> future =
                CompletableFuture.supplyAsync(() -> query(sql, split.params()), DatabaseManager.asyncExecutor());
        return attach(future, split.callback());
    }

    /** 异步执行写 SQL，回调 fn(affected, error)。 */
    public CompletableFuture<Integer> asyncExecute(String sql, Object... params) {
        Split split = splitCallback(params);
        CompletableFuture<Integer> future =
                CompletableFuture.supplyAsync(() -> execute(sql, split.params()), DatabaseManager.asyncExecutor());
        return attach(future, split.callback());
    }

    /** 异步取单值，回调 fn(value, error)。 */
    public CompletableFuture<Object> asyncValue(String sql, Object... params) {
        Split split = splitCallback(params);
        CompletableFuture<Object> future =
                CompletableFuture.supplyAsync(() -> queryValue(sql, split.params()), DatabaseManager.asyncExecutor());
        return attach(future, split.callback());
    }

    private record Split(Object[] params, ScriptCallback.Callback2 callback) {
    }

    /**
     * 解析异步方法的参数：支持末尾附加回调函数，
     * 且兼容把 JS 数组作为唯一实参传入的调用方式。
     */
    private static Split splitCallback(Object[] given) {
        Object[] a = given == null ? new Object[0] : given;
        ScriptCallback.Callback2 callback = null;
        for (int round = 0; round < 2; round++) {
            if (a.length > 0 && a[a.length - 1] instanceof Function) {
                callback = ScriptCallback.of2(a[a.length - 1]);
                a = Arrays.copyOf(a, a.length - 1);
            }
            a = spreadSingle(a);
        }
        return new Split(normalizeParams(a), callback);
    }

    /**
     * 查询完成后触发回调：优先跳回服务器主线程执行（脚本可安全访问游戏对象），
     * 没有服务器上下文（启动脚本阶段等）时在完成线程直接调用。
     */
    private <T> CompletableFuture<T> attach(CompletableFuture<T> future, ScriptCallback.Callback2 callback) {
        if (callback == null) {
            return future;
        }
        return future.whenComplete((result, error) -> {
            Runnable task = () -> {
                try {
                    callback.invoke(error == null ? result : null, error);
                } catch (Throwable t) {
                    SQLiteJS.LOGGER.error("[SQLiteJS] Async callback failed for '{}'", name, t);
                }
            };
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                server.execute(task);
            } else {
                task.run();
            }
        });
    }

    // ------------------------------------------------------------------
    // 参数与结果转换
    // ------------------------------------------------------------------

    /**
     * 整理可变参数：当唯一实参是 JS 数组 / Java List 时展开为参数列表
     * （兼容 db.query(sql, [a, b]) 与 db.query(sql, a, b) 两种写法）。
     */
    private static Object[] normalizeParams(Object[] given) {
        return normalizeEach(spreadSingle(given == null ? new Object[0] : given));
    }

    private static Object[] spreadSingle(Object[] a) {
        if (a != null && a.length == 1) {
            if (a[0] instanceof NativeArray nativeArray) {
                return nativeArray.toArray();
            }
            if (a[0] instanceof List<?> list) {
                return list.toArray();
            }
        }
        return a;
    }

    private static Object[] normalizeEach(Object[] params) {
        Object[] out = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            out[i] = normalize(params[i]);
        }
        return out;
    }

    /** 单个值转换为 JDBC 可绑定的形式。 */
    private static Object normalize(Object value) {
        if (value == null || value == Undefined.instance || Undefined.SCRIPTABLE_UNDEFINED.equals(value)) {
            return null;
        }
        if (Undefined.isUndefined(value)) {
            return null;
        }
        if (value instanceof Wrapper wrapper) {
            return wrapper.unwrap();
        }
        return value;
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    private static List<Object[]> toParamList(Object rows) {
        List<Object[]> list = new ArrayList<>();
        Object[] elements = spreadSingle(new Object[]{rows});
        if (elements.length == 0) {
            return list;
        }
        if (!(elements[0] instanceof List<?>)
                && !(elements[0] instanceof NativeArray)
                && !(elements[0] instanceof Object[])) {
            throw new IllegalArgumentException("SQLiteJS: batch 的第二个参数需要是数组（每项为一组参数）");
        }
        for (Object element : elements) {
            Object[] params;
            if (element instanceof Object[] arr) {
                params = normalizeEach(arr);
            } else if (element instanceof NativeArray nativeArray) {
                params = normalizeEach(nativeArray.toArray());
            } else if (element instanceof List<?> l) {
                params = normalizeEach(l.toArray());
            } else {
                params = new Object[]{normalize(element)};
            }
            list.add(params);
        }
        return list;
    }

    private static List<Map<String, Object>> readRows(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int count = meta.getColumnCount();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>(count * 2);
            for (int i = 1; i <= count; i++) {
                row.put(meta.getColumnLabel(i), rs.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }

    private void appendAssignments(StringBuilder sql, Map<String, Object> set, List<Object> values) {
        int i = 0;
        for (Map.Entry<String, Object> entry : set.entrySet()) {
            if (i++ > 0) {
                sql.append(", ");
            }
            sql.append(ident(entry.getKey(), "列")).append(" = ?");
            values.add(normalize(entry.getValue()));
        }
    }

    private void appendWhere(StringBuilder sql, Map<String, Object> where, List<Object> values) {
        if (where == null || where.isEmpty()) {
            return;
        }
        sql.append(" WHERE ");
        int i = 0;
        for (Map.Entry<String, Object> entry : where.entrySet()) {
            if (i++ > 0) {
                sql.append(" AND ");
            }
            sql.append(ident(entry.getKey(), "列"));
            Object value = normalize(entry.getValue());
            if (value == null) {
                sql.append(" IS NULL");
            } else {
                sql.append(" = ?");
                values.add(value);
            }
        }
    }

    /** 表名 / 列名校验：只允许字母、数字、下划线和中文，且不以数字开头，杜绝拼接注入。 */
    private static String ident(String s, String what) {
        if (s == null || !s.matches("[A-Za-z_\\u4e00-\\u9fa5][A-Za-z0-9_\\u4e00-\\u9fa5]*")) {
            throw new IllegalArgumentException("SQLiteJS: 非法" + what + "名称: '" + s + "'");
        }
        return s;
    }

    private static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static RuntimeException wrap(String sql, SQLException e) {
        return new RuntimeException("SQLiteJS: SQL 执行失败: " + e.getMessage() + " | SQL: " + sql, e);
    }
}
