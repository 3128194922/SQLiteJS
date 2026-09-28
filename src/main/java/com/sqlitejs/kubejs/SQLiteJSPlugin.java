package com.sqlitejs.kubejs;

import com.sqlitejs.SQLiteJS;
import dev.latvian.mods.kubejs.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingsEvent;
import dev.latvian.mods.kubejs.script.ScriptType;
import dev.latvian.mods.kubejs.util.ClassFilter;

/**
 * KubeJS 插件入口，通过 jar 根目录的 kubejs.plugins.txt 注册。
 * 为 startup / server / client 三类脚本注册全局绑定 SQLite。
 */
public class SQLiteJSPlugin extends KubeJSPlugin {
    @Override
    public void registerBindings(BindingsEvent event) {
        ScriptType type = event.getType();
        String tag = type == ScriptType.SERVER ? "server" : type == ScriptType.CLIENT ? "client" : "startup";
        event.add("SQLite", new SQLiteBinding(tag));
        SQLiteJS.LOGGER.info("[SQLiteJS] Registered global binding 'SQLite' for {}", type.name);
    }

    @Override
    public void registerClasses(ScriptType type, ClassFilter filter) {
        // 允许脚本直接访问本 mod 的类（例如 instanceof DatabaseJS）
        filter.allow("com.sqlitejs");
    }
}
