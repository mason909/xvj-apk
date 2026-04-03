# XVJ 系统架构文档

> 创建时间：2026-04-03
> 最后更新：2026-04-03

## 一、系统概述

XVJ 是一个**云端素材管理与设备控制平台**，用于管理视频/图片素材，并将素材同步到多台 Android 设备上播放。

**典型场景**：多店铺广告屏管理、企业展厅屏幕控制

---

## 二、三端架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        用户浏览器                                  │
│                    (Chrome / Safari)                            │
│               http://47.102.106.237/                           │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                     Web 前端 (index.html)                        │
│  • 单文件 HTML + CSS + JavaScript                               │
│  • 静态部署在 nginx                                            │
│  • 通过 AJAX 调用后端 API                                      │
│  • 素材管理 / 设备管理 / 预设素材 / 版本管理                      │
└─────────────────────────────────────────────────────────────────┘
                              │
                    HTTP API / MQTT
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Node.js 后端 (server.js)                      │
│                     端口：3000                                   │
│  • Express 框架                                                │
│  • MySQL 数据库                                                │
│  • MQTT 消息代理                                               │
│  • 负责业务逻辑和数据存储                                       │
└─────────────────────────────────────────────────────────────────┘
                              │
                    MySQL / MQTT
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Android APK (xvj-app)                          │
│  • 运行在设备上                                                │
│  • 订阅 MQTT 主题接收指令                                       │
│  • 下载并播放素材                                               │
│  • GitHub: https://github.com/mason909/xvj-apk                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## 三、前端 (Web)

**文件位置**：`/var/www/xvj/public/index.html`

### 3.1 核心状态对象

```javascript
var s = {
    folders: {},        // 文件夹备注 { "01": "K歌", "02": "商务" }
    p: "materials",     // 当前面板 materials/devices/unregistered/presets/versions
    f: "01",           // 当前选中文件夹
    fs: [],             // 文件夹列表 ["01","02",..."30"]
    ms: [],            // 素材列表
    ds: [],             // 设备列表
    selected: [],       // 选中的素材ID
    stores: [],         // 店铺列表
    df: null,          // 当前选中店铺
    rf: null,           // 当前选中房间ID
    rooms: [],          // 房间列表
    pm: [],             // 预设素材
    pf: "01"           // 预设素材当前文件夹
};
```

### 3.2 五大功能模块

| 模块 | 函数前缀 | 功能 |
|------|----------|------|
| 素材管理 | `lm()`, `rm()`, `sf()` | 素材上传、删除、文件夹切换 |
| 设备管理 | `ld()`, `rd()`, `lr()` | 店铺/房间/设备管理 |
| 预设素材 | `lp()`, `prm()`, `spf()` | 预设素材库管理 |
| 未注册设备 | `lu()`, `ar()` | 设备授权 |
| 版本管理 | `lv()`, `lvd()` | APK 版本管理 |

### 3.3 核心 API 调用

```javascript
// 获取素材
fetch('/api/materials')

// 上传素材
POST /api/upload?folder=01

// 删除素材
DELETE /api/materials/:id

// 复制到预设
POST /api/preset/materials

// 获取设备列表
fetch('/api/devices')

// 授权设备
POST /api/devices/:id/authorize

// 获取房间列表
fetch('/api/rooms')

// 同步房间
POST /api/rooms/:id/sync
```

---

## 四、后端 (Node.js)

**文件位置**：`/var/www/xvj/server.js`

### 4.1 技术栈

| 组件 | 技术 |
|------|------|
| 框架 | Express.js |
| 数据库 | MySQL |
| 消息队列 | MQTT (mqtt.js) |
| 认证 | API Key (`xvj_secret_key_2024`) |

### 4.2 核心 API 路由

| 路由 | 方法 | 功能 |
|------|------|------|
| `/api/materials` | GET | 获取素材列表 |
| `/api/materials` | POST | 上传素材 |
| `/api/materials/:id` | DELETE | 删除素材 |
| `/api/preset/materials` | GET/POST | 预设素材 |
| `/api/rooms` | GET/POST | 房间管理 |
| `/api/rooms/:id` | PUT/DELETE | 更新/删除房间 |
| `/api/rooms/:id/sync` | POST | 同步房间到设备 |
| `/api/devices` | GET | 设备列表 |
| `/api/devices/:id/authorize` | POST | 授权设备 |
| `/api/unregistered` | GET | 未注册设备 |
| `/api/stores` | GET/POST | 店铺管理 |
| `/api/folders/notes` | GET/POST | 文件夹备注 |

### 4.3 MQTT 主题

| 主题 | 方向 | 用途 |
|------|------|------|
| `xvj/device/{id}/register` | 设备→服务器 | 设备注册 |
| `xvj/device/{id}/status` | 设备→服务器 | 设备状态心跳 |
| `xvj/device/{id}/command` | 服务器→设备 | 下发指令 |
| `xvj/device/{id}/log` | 设备→服务器 | 设备日志上报 |
| `xvj/auth/response` | 服务器→设备 | 授权响应 |

### 4.4 数据库表结构

```
materials          - 素材库（原始视频/图片）
preset_materials   - 预设素材（可跨房间复用）
rooms              - 房间配置（folder_mappings 是设备同步核心）
devices            - 设备注册表
operation_logs      - 前端操作日志
device_logs        - 设备日志
folder_notes        - 文件夹备注
stores             - 店铺列表
```

### 4.5 素材三层架构

```
素材库 [M] ──cp2p──→ 预设素材 [P] ──mapPreset──→ 房间 [D]
     │                         │                        │
  原始视频                   跨房间共享              设备同步配置
```

---

## 五、Android APK

**GitHub**: https://github.com/mason909/xvj-apk

**构建方式**：GitHub Actions（本地禁止构建）

### 5.1 核心流程

```
APP 启动
  ↓
MQTT 连接成功
  ↓
发送 register 消息
  ↓
收到 auth_result（含 folder_mappings）
  ↓
下载素材到本地
  ↓
按配置播放
```

### 5.2 场景系统

- **Scene A（第一幕）**：主屏幕
- **Scene B（第二幕）**：次屏幕

每个场景有独立的 `folder_mappings`，文件夹编号前缀为 `A`/`B`（如 A01、B01）

---

## 六、部署信息

| 项目 | 值 |
|------|-----|
| 前端服务器 | 47.102.106.237 |
| nginx root | `/var/www/xvj/public` |
| 后端端口 | 3000 |
| 数据库 | MySQL xvj_db |
| MQTT | 47.102.106.237:1883 |

### 6.1 重要路径

```
/var/www/xvj/public/index.html    ← 前端入口（实际服务路径）
/var/www/xvj/server.js            ← 后端代码
/var/www/xvj/config.js            ← 配置文件
/var/www/xvj/uploads/            ← 素材存储
/etc/nginx/conf.d/xvj.conf       ← nginx 配置
```

---

## 七、开发流程

### 7.1 前端开发

```bash
# 1. 本地修改
vim ~/xvj-project/index.html

# 2. Git commit
cd ~/xvj-project
git add . && git commit -m "fix: xxx"

# 3. 上传到服务器
scp ~/xvj-project/index.html root@47.102.106.237:/var/www/xvj/public/

# 4. 重启服务
ssh root@47.102.106.237 "systemctl restart xvj"
```

### 7.2 APK 开发

```bash
# 1. 修改代码后 push 到 GitHub
git add . && git commit -m "fix: xxx" && git push

# 2. GitHub Actions 自动构建
# 3. 从 GitHub Actions 下载 APK
```

---

## 八、已知问题与修复

| 问题 | 原因 | 解决方案 |
|------|------|----------|
| nginx 配置冲突 | 两套 nginx 配置指向不同目录 | 删除 `/etc/nginx/sites-enabled/xvj.conf`，保留 `conf.d` |
| 快速切换文件夹数据覆盖 | 竞态条件 | 简化 `sf()` 和 `lm()` 逻辑，`sf()` 直接调用 `rm()` |

---

## 九、铁律

1. **禁止直接在服务器上修改代码**
2. **所有代码修改在本地进行**
3. **代码必须通过 git 版本管理**
4. **APK 只能通过 GitHub Actions 构建**
5. **修改前必须先 `scp` 下载到本地**
