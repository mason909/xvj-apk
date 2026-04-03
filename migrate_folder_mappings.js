/**
 * XVJ 数据库迁移脚本：folder_mappings 统一到 config.scenes
 * 
 * 执行时机：只需执行一次
 * 迁移内容：
 *   1. 根级 rooms.folder_mappings → config.scenes.A.folder_mappings（当 A 为空时）
 *   2. 清空根级 rooms.folder_mappings（设为 NULL 或 {}）
 *   3. 确保所有 rooms.config.scenes.B 存在且有 folder_mappings: {}
 * 
 * 回滚：git log 找到迁移前 commit，用 git diff 恢复
 */

const mysql = require('mysql2');
const config = require('./config');

const db = mysql.createPool({
  host: config.database.host,
  user: config.database.user,
  password: config.database.password,
  database: config.database.name,
  charset: 'utf8mb4'
});

async function migrate() {
  console.log('=== XVJ folder_mappings 迁移开始 ===\n');

  // Step 1: 查出所有 rooms
  const [rooms] = await db.promise().query('SELECT id, folder_mappings, config FROM rooms');
  console.log(`找到 ${rooms.length} 个房间\n`);

  let migrated = 0;
  let skipped = 0;
  let errors = 0;

  for (const room of rooms) {
    try {
      // 解析根级 folder_mappings
      let rootFm = {};
      try {
        rootFm = room.folder_mappings ? JSON.parse(room.folder_mappings) : {};
      } catch (e) { rootFm = {}; }

      // 解析 config
      let cfg = {};
      try {
        cfg = room.config ? JSON.parse(room.config) : {};
      } catch (e) { cfg = {}; }

      // 初始化 scenes 结构
      if (!cfg.scenes) {
        cfg.scenes = {
          A: { name: '第一幕', folder_mappings: {}, windows: [] },
          B: { name: '第二幕', folder_mappings: {}, windows: [] }
        };
      }
      if (!cfg.scenes.A) {
        cfg.scenes.A = { name: '第一幕', folder_mappings: {}, windows: [] };
      }
      if (!cfg.scenes.B) {
        cfg.scenes.B = { name: '第二幕', folder_mappings: {}, windows: [] };
      }

      // 检查根级是否有数据需要迁移
      const rootHasData = Object.keys(rootFm).length > 0;
      const sceneAHasData = cfg.scenes.A.folder_mappings && Object.keys(cfg.scenes.A.folder_mappings).length > 0;

      if (rootHasData && !sceneAHasData) {
        // 迁移：根级 → Scene A
        cfg.scenes.A.folder_mappings = rootFm;
        console.log(`  [迁移] 房间 ${room.id}: 根级 ${JSON.stringify(rootFm)} → Scene A`);

        await db.promise().query(
          'UPDATE rooms SET folder_mappings = NULL, config = ? WHERE id = ?',
          [JSON.stringify(cfg), room.id]
        );
        migrated++;
      } else if (rootHasData && sceneAHasData) {
        // 两边都有数据：保留 Scene A（它更新），根级废弃
        console.log(`  [跳过] 房间 ${room.id}: Scene A已有数据，根级废弃`);
        await db.promise().query(
          'UPDATE rooms SET folder_mappings = NULL WHERE id = ?',
          [room.id]
        );
        skipped++;
      } else {
        // 根级为空，确保 config 正确
        console.log(`  [无需迁移] 房间 ${room.id}`);
        skipped++;
      }

    } catch (e) {
      console.error(`  [错误] 房间 ${room.id}: ${e.message}`);
      errors++;
    }
  }

  console.log(`\n=== 迁移完成 ===`);
  console.log(`迁移: ${migrated} 个房间`);
  console.log(`跳过: ${skipped} 个房间`);
  console.log(`错误: ${errors} 个房间`);

  // 验证：确认所有 rooms.folder_mappings 为 NULL 或 {}
  const [remaining] = await db.promise().query(
    'SELECT id, folder_mappings FROM rooms WHERE folder_mappings IS NOT NULL AND folder_mappings != "{}" AND folder_mappings != "null"'
  );
  if (remaining.length > 0) {
    console.error(`\n⚠️ 还有 ${remaining.length} 个房间的 folder_mappings 未清空！`);
    remaining.forEach(r => console.log(`  ${r.id}: ${r.folder_mappings}`));
  } else {
    console.log('\n✅ 所有房间 folder_mappings 已迁移/清空');
  }

  process.exit(errors > 0 ? 1 : 0);
}

migrate();
