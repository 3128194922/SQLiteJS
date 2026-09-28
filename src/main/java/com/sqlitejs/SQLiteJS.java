package com.sqlitejs;

import com.mojang.logging.LogUtils;
import com.sqlitejs.db.DatabaseManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * SQLiteJS：KubeJS 附属，让 KubeJS 脚本能直接操作 kubejs 目录下的 SQLite 数据库。
 * 脚本入口为全局绑定 SQLite，由 {@link com.sqlitejs.kubejs.SQLiteJSPlugin} 注册。
 */
@Mod(SQLiteJS.MOD_ID)
public class SQLiteJS {
    public static final String MOD_ID = "sqlitejs";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SQLiteJS(FMLJavaModLoadingContext context) {
        context.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("[SQLiteJS] Loaded. KubeJS present: {}", ModList.get().isLoaded("kubejs"));
    }

    /**
     * 服务器停止时关闭由服务器脚本打开的数据库连接，避免文件句柄泄漏。
     */
    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        if (!ModList.get().isLoaded("kubejs")) {
            return;
        }
        if (Config.CLOSE_ON_SERVER_STOP.get()) {
            int closed = DatabaseManager.closeAll("server");
            if (closed > 0) {
                LOGGER.info("[SQLiteJS] Server stopped, closed {} database connection(s)", closed);
            }
        }
    }
}
