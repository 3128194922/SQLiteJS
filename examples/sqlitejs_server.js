// 将本文件复制到 kubejs/server_scripts/ 后使用。
// 数据库默认位于 <游戏目录>/kubejs/sqlite/players.db。
const db = SQLite.open("players");

db.execute(`
  CREATE TABLE IF NOT EXISTS players (
    name TEXT PRIMARY KEY,
    diamonds INTEGER NOT NULL DEFAULT 0
  )
`);

// 使用参数绑定传值，避免把数据拼接进 SQL。
const existing = db.queryRow(
  "SELECT name, diamonds FROM players WHERE name = ?",
  "Steve"
);

if (existing) {
  db.update("players", { diamonds: existing.diamonds + 1 }, { name: "Steve" });
} else {
  db.insert("players", { name: "Steve", diamonds: 1 });
}

const totalPlayers = db.count("players");
console.log(`[SQLiteJS] 当前记录数：${totalPlayers}`);

// transaction(fn) 在正常返回时提交；回调抛错时自动回滚。
db.transaction(function (tx) {
  tx.insert("players", { name: "Alex", diamonds: 5 });
});

// 异步查询的回调在服务器运行时会排入服务器主线程。
db.asyncQuery(
  "SELECT name FROM players WHERE diamonds >= ?",
  5,
  function (rows, error) {
    if (error) {
      console.error(`[SQLiteJS] 查询失败：${error}`);
      return;
    }
    console.log(`[SQLiteJS] 达到条件的记录数：${rows.length}`);
  }
);
