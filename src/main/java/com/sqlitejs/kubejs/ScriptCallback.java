package com.sqlitejs.kubejs;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.NativeJavaObject;
import dev.latvian.mods.rhino.ScriptableObject;
import dev.latvian.mods.rhino.Undefined;

/**
 * JS 函数 → Java 回调适配器。
 * <p>
 * 与 KubeJS 自身 ScheduledEvents.TimeoutJSFunction 的做法一致：
 * 在脚本调用栈内（存在活动 Context 时）通过
 * {@link NativeJavaObject#createInterfaceAdapter} 把 JS 函数包装为 Java SAM 接口，
 * 包装结果可以在之后任意线程调用（回调触发时由适配器自行进入 Context）。
 */
public final class ScriptCallback {
    /** 单参数回调，例如 transaction(fn) 中的 fn，参数为数据库对象本身。 */
    @FunctionalInterface
    public interface Callback1 {
        Object invoke(Object arg);
    }

    /** 双参数回调，异步查询完成时触发：result 为查询结果，error 为异常（成功时为 null）。 */
    @FunctionalInterface
    public interface Callback2 {
        Object invoke(Object result, Object error);
    }

    private ScriptCallback() {
    }

    public static Callback1 of1(Object fn) {
        return (Callback1) adapt(fn, Callback1.class);
    }

    public static Callback2 of2(Object fn) {
        return (Callback2) adapt(fn, Callback2.class);
    }

    private static Object adapt(Object fn, Class<?> iface) {
        if (fn == null || Undefined.instance.equals(fn)) {
            throw new IllegalArgumentException("SQLiteJS: 回调参数为空，需要一个 JS 函数");
        }
        if (!(fn instanceof ScriptableObject)) {
            throw new IllegalArgumentException("SQLiteJS: 期望一个 JS 函数，实际收到 " + fn.getClass().getName());
        }
        // 与 KubeJS ScriptManager.load() 相同：脚本执行期间线程上已有活动 Context，
        // enter() 会直接返回它，fork 版 Rhino 不需要配对 exit
        Context cx = Context.enter();
        return NativeJavaObject.createInterfaceAdapter(cx, iface, (ScriptableObject) fn);
    }
}
