package com.sqlitejs;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * SQLiteJS 通用配置。
 */
public final class Config {
    public static final ForgeConfigSpec SPEC;

    /** 数据库存放目录名（相对 kubejs 目录） */
    public static final ForgeConfigSpec.ConfigValue<String> DB_FOLDER;
    /** SQLite busy_timeout（毫秒）：写入被其他连接占用时的最长等待 */
    public static final ForgeConfigSpec.IntValue BUSY_TIMEOUT_MS;
    /** 日志模式：WAL / DELETE / TRUNCATE / PERSIST / MEMORY / OFF */
    public static final ForgeConfigSpec.ConfigValue<String> JOURNAL_MODE;
    /** 是否启用外键约束 */
    public static final ForgeConfigSpec.BooleanValue FOREIGN_KEYS;
    /** 服务器停止时自动关闭由服务器脚本打开的连接 */
    public static final ForgeConfigSpec.BooleanValue CLOSE_ON_SERVER_STOP;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("sqlite");
        DB_FOLDER = builder
                .comment("Databases are stored under <gameDir>/kubejs/<dbFolder>/")
                .define("dbFolder", "sqlite");
        BUSY_TIMEOUT_MS = builder
                .comment("SQLite busy_timeout in milliseconds, how long a write waits while the database is locked")
                .defineInRange("busyTimeoutMs", 5000, 0, 600000);
        JOURNAL_MODE = builder
                .comment("Journal mode: WAL (recommended), DELETE, TRUNCATE, PERSIST, MEMORY or OFF")
                .define("journalMode", "WAL");
        FOREIGN_KEYS = builder
                .comment("Enable foreign key constraints (PRAGMA foreign_keys)")
                .define("foreignKeys", true);
        CLOSE_ON_SERVER_STOP = builder
                .comment("Close connections opened by server scripts when the server stops")
                .define("closeOnServerStop", true);
        builder.pop();
        SPEC = builder.build();
    }

    private Config() {
    }
}
