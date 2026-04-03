# CHANGELOG - XVJ 代码重构记录

## 2026-04-04 v2.0 - folder_mappings 一新必除旧

### 背景
AI编写代码时引入 `config.scenes.A/B.folder_mappings` 新结构，但未删除旧的根级 `rooms.folder_mappings` 列引用。导致：
- 三种键格式混用（无前缀 / A前缀 / B前缀）
- Scene B 同步完全失效
- 设备无法下载素材

### 清理操作

#### Step 0：备份
```bash
git add -A && git commit -m "BACKUP: before folder_mappings cleanup"
```

#### Step 1：数据库迁移脚本
- 文件：`migrate_folder_mappings.js`
- 操作：`rooms.folder_mappings` → `config.scenes.A.folder_mappings`（当A为空时）
- 结果：6个房间，1个迁移，5个跳过，0错误

#### Step 2a：删除废弃 API
- 删除 `GET /api/rooms/:id/materials`（返回空数据）

#### Step 2b：server.js 清理
**移除的根级 SELECT**：
- `sendAuthResponse` 行449：移除 `folder_mappings` 列
- `/api/rooms/:id/sync` 行753：移除 `folder_mappings` 列
- `/api/room-materials-v2` 行1497：移除 `folder_mappings` 列
- `/api/rooms/:id/folder/:folder` 行1945：移除未使用变量
- `/api/devices/:id/room-materials` 行1996：移除 `folder_mappings` 列
- `/api/devices/:id/command` 行690：移除 `folder_mappings` 列
- `handleMqttMessage` 行336：移除 `folder_mappings` 列
- `/api/rooms/:id/windows` 行1830：移除 `folder_mappings` 查询
- `DELETE preset_materials` 路由：移除根级清理
- `DELETE materials` 路由：移除根级清理

**移除的 INSERT**：
- `POST /api/rooms`：移除 `folder_mappings` 列

**代码行数**：
- `server.js`：-89行（净减少）
- 删除 `/api/rooms/:id/materials` API

#### Step 3：前端 index.html 清理
**修复的函数**：
- `dm()`：改读 `room.config.scenes.A.folder_mappings`
- `rmDevice()` ×2：改读 `r.config.scenes.A.folder_mappings`
- `toggleRM()`：改读 `r.config.scenes.A.folder_mappings`
- `mapPreset()`：改读 `r.config.scenes.A.folder_mappings`
- `rmRoomMat()`：改读 `r.config.scenes.A.folder_mappings`
- `addMaterialsToRoom()`：改读 `room.config.scenes.A.folder_mappings`

**代码行数**：
- `index.html`：-8行（净减少）

### 最终结构

```
rooms 表：
├── store_name
├── name
├── config（JSON）
│   └── scenes
│       ├── A
│       │   ├── name: "第一幕"
│       │   ├── folder_mappings: { "01": [id1], "02": [id2] }
│       │   └── windows: [...]
│       └── B
│           ├── name: "第二幕"
│           ├── folder_mappings: { "01": [...], "02": [...] }
│           └── windows: [...]
└──（根级 folder_mappings 列已废弃，列为 NULL）
```

### 键格式统一
MQTT/HTTP/APK 全部使用 **Scene 前缀格式**：`A01`, `B01`, `A02`, `B02`...

### commit 历史
```
f1bd886 CLEANUP: remove all root-level folder_mappings column references from server.js
6368d32 CLEANUP: frontend reads from room.config.scenes.A.folder_mappings
7d8dca2 CLEANUP: remove root-level folder_mappings references - migration complete
a87073f BACKUP: server.js before folder_mappings cleanup
9642584 Add database migration script for folder_mappings consolidation
```

---

## 2026-04-03 v1.0 - 初始版本
- 双 Scene 架构引入（Scene A / Scene B）
- MQTT 设备控制
- 素材管理基础功能
