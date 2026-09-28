# SQLiteJS

SQLiteJS 是 Minecraft 1.20.1 Forge 模组，为 KubeJS 脚本提供 SQLite 数据库访问接口。数据库文件默认保存在游戏目录的 `kubejs/sqlite/` 下。

## 环境要求

- Minecraft 1.20.1
- Minecraft Forge 47.x（项目开发版本为 47.4.6）
- KubeJS 2001.0 或更高版本

SQLiteJS 的 KubeJS 依赖在模组元数据中声明为可选；未安装 KubeJS 时模组可以加载，但不会注册 `SQLite` 脚本绑定。
安装 KubeJS 后，`SQLite` 绑定会注册到 startup、server 和 client 三类脚本中；数据库业务通常建议放在 `server_scripts/`。

## 安装

1. 将 `sqlitejs-1.0.0.jar` 放入实例的 `mods/` 目录。
2. 确保实例已安装适用于 Minecraft 1.20.1 Forge 的 KubeJS。
3. 启动游戏或服务器。首次启动后，配置文件位于 `config/sqlitejs-common.toml`。
4. 将脚本放入 `kubejs/server_scripts/`；示例见 [`examples/sqlitejs_server.js`](examples/sqlitejs_server.js)。

SQLite JDBC 驱动已由 SQLiteJS 打包进发布模组 JAR，不需要另外安装驱动文件。

## 配置

配置文件中的选项位于 `sqlite` 分组：

| 配置项 | 默认值 | 作用 |
| --- | --- | --- |
| `dbFolder` | `sqlite` | 相对于 `kubejs/` 的数据库目录 |
| `busyTimeoutMs` | `5000` | 数据库繁忙时等待锁的毫秒数，范围为 0–600000 |
| `journalMode` | `WAL` | SQLite 日志模式：`WAL`、`DELETE`、`TRUNCATE`、`PERSIST`、`MEMORY` 或 `OFF` |
| `foreignKeys` | `true` | 是否启用外键约束 |
| `closeOnServerStop` | `true` | 服务器停止时关闭服务器脚本打开的连接 |

例如，将 `dbFolder` 改为 `data` 后，数据库默认存放在 `kubejs/data/`。

## 快速示例

```js
const db = SQLite.open("players"); // 创建或打开 kubejs/sqlite/players.db

db.execute(`
  CREATE TABLE IF NOT EXISTS players (
    name TEXT PRIMARY KEY,
    diamonds INTEGER NOT NULL DEFAULT 0
  )
`);

db.insert("players", { name: "Steve", diamonds: 10 });

const row = db.queryRow(
  "SELECT name, diamonds FROM players WHERE name = ?",
  "Steve"
);
console.log(row ? `${row.name}: ${row.diamonds}` : "玩家记录不存在");
```

查询参数使用 `?` 占位符传入。不要把玩家输入直接拼接到 SQL 字符串中。

## KubeJS API

### 全局对象 `SQLite`

| 方法 | 说明 |
| --- | --- |
| `SQLite.open(name)` | 打开或创建数据库，返回数据库操作对象；无扩展名时自动添加 `.db` |
| `SQLite.get(name)` | 获取已打开的数据库；未打开时返回 `null` |
| `SQLite.isOpen(name)` | 判断数据库是否已打开 |
| `SQLite.exists(name)` | 判断数据库文件是否存在 |
| `SQLite.close(name)` | 关闭数据库连接，返回是否关闭了连接 |
| `SQLite.delete(name)` | 关闭连接并删除数据库文件 |
| `SQLite.opened()` | 返回当前已打开数据库名称列表 |
| `SQLite.path(name)` | 返回数据库文件的绝对路径 |
| `SQLite.folder()` | 返回数据库根目录的绝对路径 |

数据库名称应使用相对于配置目录的安全路径；不要传入 `..` 路径穿越或绝对路径。子目录可用 `/` 分隔。

### 数据库对象

| 方法 | 说明 |
| --- | --- |
| `execute(sql, ...params)` | 执行 SQL，返回受影响行数；适用于建表、更新等语句 |
| `query(sql, ...params)` | 查询所有行，返回由列名和值组成的对象数组 |
| `queryRow(sql, ...params)` | 查询第一行；没有结果时返回 `null` |
| `queryValue(sql, ...params)` | 返回第一行第一列的值；没有结果时返回 `null` |
| `insert(table, row)` | 插入一行，返回 SQLite rowid |
| `update(table, values[, where])` | 更新数据；条件可传对象或 SQL 条件字符串及参数 |
| `delete(table[, where])` | 删除数据；条件可传对象或 SQL 条件字符串及参数 |
| `count(table[, where])` | 统计行数，可附加条件 |
| `has(table, where)` | 判断是否存在符合条件的行 |
| `tables()` | 返回数据库中的表名列表 |
| `columns(table)` | 返回表结构信息 |
| `batch(sql, rows)` | 用多组参数批量执行同一条 SQL |
| `begin()` / `commit()` / `rollback()` | 手动控制事务 |
| `transaction(fn)` | 在回调中执行事务；正常返回时提交，抛出异常时回滚 |
| `asyncQuery(sql, ...params[, callback])` | 异步查询；可选回调接收 `(rows, error)` |
| `asyncExecute(sql, ...params[, callback])` | 异步执行；可选回调接收 `(affected, error)` |
| `asyncValue(sql, ...params[, callback])` | 异步取值；可选回调接收 `(value, error)` |
| `close()` / `isOpen()` | 关闭此连接 / 检查连接状态 |

传给 `update` 或 `delete` 的条件可以写成 `{ name: "Steve" }`，也可以写成 `"name = ?"` 并将值作为后续参数传入。省略更新或删除条件会作用于整张表，请谨慎使用。

事务示例：

```js
db.transaction(function (tx) {
  tx.insert("players", { name: "Alex", diamonds: 5 });
  tx.update("players", { diamonds: 20 }, { name: "Steve" });
  return "saved";
});
```

异步回调示例：

```js
db.asyncQuery(
  "SELECT name FROM players WHERE diamonds >= ?",
  10,
  function (rows, error) {
    if (error) {
      console.error(`SQLiteJS 查询失败：${error}`);
      return;
    }
    console.log(`找到 ${rows.length} 位玩家`);
  }
);
```

异步操作也会返回 `CompletableFuture`。存在运行中的服务器时，回调会排入服务器主线程；服务器上下文不可用时，回调在完成线程执行。

## 数据位置与连接生命周期

默认数据库文件为 `<游戏目录>/kubejs/sqlite/<name>.db`。`SQLite.open()` 对相同数据库文件复用连接。服务器停止且 `closeOnServerStop=true` 时，会关闭由 `server_scripts` 打开的连接。

## 开发构建

在项目目录执行：

```powershell
./gradlew.bat build
```

发布 JAR 输出到 `build/libs/`。
