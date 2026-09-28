package com.sqlitejs.db;

import com.sqlitejs.SQLiteJS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 数据库连接管理：按解析后的数据库文件路径缓存 {@link DatabaseJS} 实例，
 * 负责名称合法性校验、按脚本类型归档连接、异步执行线程池。
 */
public final class DatabaseManager {
    /** key = 数据库文件的规范路径 */
    private static final Map<String, DatabaseJS> DATABASES = new ConcurrentHashMap<>();

    /** 异步查询专用单线程池（守护线程，不阻止 JVM 退出） */
    private static final ExecutorService ASYNC = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SQLiteJS-Async");
        thread.setDaemon(true);
        return thread;
    });

    private DatabaseManager() {
    }

    public static ExecutorService asyncExecutor() {
        return ASYNC;
    }

    /**
     * 把脚本传入的数据库名称解析为 kubejs 数据目录下的真实文件。
     * 只允许安全的相对路径（子目录用 / 分隔），禁止 ".."、绝对路径与盘符，防止越界访问。
     * 无扩展名时自动补 .db。
     */
    public static Path resolve(Path root, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("SQLiteJS: 数据库名称不能为空");
        }
        String normalized = name.trim().replace('\\', '/');
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        for (String part : normalized.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("SQLiteJS: 非法数据库名称 '" + name + "'，不允许空段、'.' 或 '..'");
            }
            if (part.length() > 1 && part.charAt(1) == ':') {
                throw new IllegalArgumentException("SQLiteJS: 非法数据库名称 '" + name + "'，不允许盘符路径");
            }
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (!Character.isLetterOrDigit(c) && "-_. ()（）".indexOf(c) < 0) {
                    throw new IllegalArgumentException("SQLiteJS: 数据库名称 '" + name + "' 含有非法字符 '" + c + "'");
                }
            }
        }
        if (!normalized.contains(".")) {
            normalized = normalized + ".db";
        }
        return root.resolve(normalized).normalize();
    }

    /** 打开（或复用已打开的）数据库，并登记归属的脚本类型标签。 */
    public static synchronized DatabaseJS open(Path root, String tag, String name) {
        Path file = resolve(root, name);
        String key = file.toString();
        DatabaseJS db = DATABASES.get(key);
        if (db == null) {
            db = new DatabaseJS(name, file);
            DATABASES.put(key, db);
            SQLiteJS.LOGGER.info("[SQLiteJS] Opened database '{}' at {}", name, file.toAbsolutePath());
        }
        db.addOwner(tag);
        return db;
    }

    /** 获取已打开的数据库；未打开返回 null，不会创建文件。 */
    public static DatabaseJS get(Path root, String name) {
        return DATABASES.get(resolve(root, name).toString());
    }

    /** 判断数据库文件是否已存在（不要求已打开）。 */
    public static boolean exists(Path root, String name) {
        return Files.exists(resolve(root, name));
    }

    /** 关闭并移除指定数据库的连接。返回是否确实关闭了一个连接。 */
    public static synchronized boolean close(Path root, String name) {
        String key = resolve(root, name).toString();
        DatabaseJS db = DATABASES.remove(key);
        if (db == null) {
            return false;
        }
        db.close();
        return true;
    }

    /** 关闭指定脚本类型打开的所有连接，返回关闭数量。 */
    public static synchronized int closeAll(String tag) {
        int closed = 0;
        for (DatabaseJS db : List.copyOf(DATABASES.values())) {
            if (db.isOwnedBy(tag)) {
                DATABASES.remove(db.getFile().toString());
                db.close();
                closed++;
            }
        }
        return closed;
    }

    /** 关闭所有连接（JVM 关闭等场景）。 */
    public static synchronized void closeAll() {
        for (DatabaseJS db : List.copyOf(DATABASES.values())) {
            db.close();
        }
        DATABASES.clear();
    }

    /** 关闭并删除数据库文件（连带释放连接）。 */
    public static synchronized boolean delete(Path root, String name) {
        Path file = resolve(root, name);
        DatabaseJS db = DATABASES.remove(file.toString());
        if (db != null) {
            db.close();
        }
        try {
            return Files.deleteIfExists(file);
        } catch (Exception e) {
            throw new RuntimeException("SQLiteJS: 删除数据库 '" + name + "' 失败（文件可能仍被占用）: " + e.getMessage(), e);
        }
    }

    /** 当前已打开的数据库名称列表。 */
    public static List<String> opened() {
        List<String> names = new ArrayList<>();
        for (DatabaseJS db : DATABASES.values()) {
            names.add(db.getName());
        }
        return names;
    }
}
