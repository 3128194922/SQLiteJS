package com.sqlitejs.kubejs;

import com.sqlitejs.Config;
import com.sqlitejs.db.DatabaseJS;
import com.sqlitejs.db.DatabaseManager;
import dev.latvian.mods.kubejs.KubeJSPaths;

import java.nio.file.Path;
import java.util.List;

/**
 * 脚本全局绑定 SQLite 的入口。
 * 数据库文件存放在 {@code <gameDir>/kubejs/<配置的 dbFolder>/}（默认 kubejs/sqlite/）。
 * <p>
 * 每个脚本类型（startup / server / client）各有一个本类实例，
 * 归属标签用于服务器停止时精准回收服务器脚本打开的连接。
 */
public class SQLiteBinding {
    private final String tag;

    SQLiteBinding(String tag) {
        this.tag = tag;
    }

    /** 数据库根目录：<gameDir>/kubejs/<dbFolder> */
    private Path root() {
        return KubeJSPaths.DIRECTORY.resolve(Config.DB_FOLDER.get());
    }

    /**
     * 打开（不存在则创建）指定数据库并返回操作对象。
     * 同名数据库在脚本生命周期内复用同一连接。
     * 示例：let db = SQLite.open('shop')
     */
    public DatabaseJS open(String name) {
        return DatabaseManager.open(root(), tag, name);
    }

    /** 获取已打开的数据库；未打开返回 null（不会创建文件）。 */
    public DatabaseJS get(String name) {
        return DatabaseManager.get(root(), name);
    }

    /** 数据库当前是否已打开。 */
    public boolean isOpen(String name) {
        return get(name) != null;
    }

    /** 数据库文件是否已存在（不要求已打开）。 */
    public boolean exists(String name) {
        return DatabaseManager.exists(root(), name);
    }

    /** 关闭数据库连接，返回是否确实有连接被关闭。 */
    public boolean close(String name) {
        return DatabaseManager.close(root(), name);
    }

    /** 关闭并删除数据库文件。 */
    public boolean delete(String name) {
        return DatabaseManager.delete(root(), name);
    }

    /** 当前已打开的数据库名称列表。 */
    public List<String> opened() {
        return DatabaseManager.opened();
    }

    /** 某数据库文件的绝对路径（便于调试）。 */
    public String path(String name) {
        return DatabaseManager.resolve(root(), name).toAbsolutePath().toString();
    }

    /** 数据库根目录的绝对路径。 */
    public String folder() {
        return root().toAbsolutePath().toString();
    }
}
