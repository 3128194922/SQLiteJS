package com.sqlitejs.smoke;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.sqlitejs.Config;
import com.sqlitejs.db.DatabaseJS;
import com.sqlitejs.db.DatabaseManager;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ScriptableObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class SmokeTest {
    static int passed = 0, failed = 0;

    static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }

    public static void main(String[] args) throws Exception {
        Config.SPEC.setConfig(CommentedConfig.inMemory());
        Path root = Files.createTempDirectory("sqlitejs-smoke");

        // 路径安全：拒绝穿越
        try {
            DatabaseManager.resolve(root, "../evil");
            check("路径穿越被拒绝", false);
        } catch (IllegalArgumentException e) {
            check("路径穿越被拒绝", true);
        }

        // 打开 + 建表 + 插入
        DatabaseJS db = DatabaseManager.open(root, "server", "test");
        db.execute("CREATE TABLE IF NOT EXISTS players (name TEXT, diamonds INTEGER)");
        long id = db.insert("players", Map.of("name", "Steve", "diamonds", 10));
        check("insert 返回 rowid > 0", id > 0);

        // 参数化查询
        List<Map<String, Object>> rows = db.query("SELECT * FROM players WHERE name = ?", "Steve");
        Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);
        check("query 参数化查询", rows.size() == 1
                && "Steve".equals(row.get("name"))
                && ((Number) row.get("diamonds")).intValue() == 10);

        // Map 条件 update / count / has
        int n = db.update("players", Map.of("diamonds", 20), Map.of("name", "Steve"));
        check("update(Map 条件)", n == 1 && db.count("players", Map.of("diamonds", 20)) == 1);
        check("has 存在判断", db.has("players", Map.of("name", "Steve")) && !db.has("players", Map.of("name", "Alex")));

        // 回调式事务（真实 JS 函数；适配器必须在脚本调用栈内创建，与生产环境一致）
        Context cx = Context.enter();
        ScriptableObject scope = cx.initStandardObjects();
        scope.put(cx, "db", scope, db);
        List<Object> out = new ArrayList<>();
        scope.put(cx, "out", scope, out);

        Object txResult = cx.evaluateString(scope,
                "db.transaction(function(tx){ tx.insert('players', {name:'Alex', diamonds:5}); return 'ok'; })",
                "tx.js", 1, null);
        check("回调事务提交", "ok".equals(txResult) && db.count("players") == 2);

        // 事务异常回滚
        boolean rolledBack = false;
        try {
            cx.evaluateString(scope,
                    "db.transaction(function(tx){ tx.insert('players', {name:'Bad', diamonds:1}); throw new Error('boom'); })",
                    "tx2.js", 1, null);
        } catch (Throwable t) {
            rolledBack = db.count("players", Map.of("name", "Bad")) == 0;
        }
        check("事务异常回滚", rolledBack);

        // 批量
        List<List<Object>> batch = List.of(Arrays.asList("C", 1), Arrays.asList("D", 2));
        db.batch("INSERT INTO players (name, diamonds) VALUES (?, ?)", batch);
        check("batch 批量插入", db.count("players") == 4);

        // 异步取值（无回调）
        Object asyncCount = ((CompletableFuture<?>) db.asyncValue("SELECT COUNT(*) FROM players")).get(5, TimeUnit.SECONDS);
        check("asyncValue", ((Number) asyncCount).intValue() == 4);

        // 异步查询 + JS 回调（脚本内发起，回调跨线程触发，与生产一致）
        cx.evaluateString(scope,
                "db.asyncQuery('SELECT name FROM players WHERE name = ?', 'Alex', function(rows, err){ out.add(err ? ('ERR:' + err) : ('' + rows.length)); });",
                "cb.js", 1, null);
        long deadline = System.currentTimeMillis() + 10000;
        while (out.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        check("异步查询跨线程回调", out.size() == 1 && "1".equals(out.get(0)));

        // delete 自定义条件
        int d = db.delete("players", "diamonds < ?", 3);
        check("delete 自定义 WHERE", d == 2);

        // 关闭后自动重连
        DatabaseManager.closeAll();
        check("连接关闭", !db.isOpen());
        check("关闭后自动重连", db.count("players") > 0);

        // tables / columns
        check("tables 列表", db.tables().contains("players"));
        check("columns 表结构", db.columns("players").size() == 2);

        System.out.println("RESULT: passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
