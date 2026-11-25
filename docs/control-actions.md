# 受控 VJ 动作（设备侧契约）

2026-11-25 定。PAD / 云端下发的动作在盒子上的落点、回执与去重口径。
上层设计见 `xvj-pad/docs/architecture.md` §3/§10，DMX 特效数值见 `docs/dmx-fx-channels.md`。

## 1. 信封

命令经私有主题 `xvj/device/<id>/command`（qos1，id 与 fingerprint 双发）：

```json
{
  "cmd_id": "pc_m3k9x_1a2b3c",
  "room_id": "room_...",
  "src": "tablet",
  "action": "fx",
  "kind": "STROBE",
  "value": 8.0,
  "ts": 1764050000000,
  "ttl_ms": 1500
}
```

- `cmd_id`：服务端生成、全局唯一。设备按 LRU 256 去重，重复下发直接回放上次回执。
- `ts` + `ttl_ms`：`now - ts > ttl_ms` 直接回 `TIMEOUT`，不执行（断网积压的旧命令不能在现场突然生效）。
- 没有 `cmd_id` 的旧命令照常执行、不回执（管理台现有命令不受影响）。

回执走 `xvj/device/<id>/ack`（qos1）：

```json
{ "cmd_id": "pc_m3k9x_1a2b3c", "ok": true, "rev": "1234", "ts": 1764050000123 }
{ "cmd_id": "pc_...", "ok": false, "err": "BUSY", "ts": 1764050000123 }
```

`err` 取值：`TIMEOUT` / `OFFLINE` / `AUTH` / `ACL` / `BUSY` / `CONFLICT` / `RATE_LIMITED` / `UNKNOWN`。
`rev` 是设备已应用的配置版本（没有就不带）；**回执只代表动作已落地，最终态一律以 rev 为准**。

## 2. 动作表

| action | 参数 | 落点 | 备注 |
| --- | --- | --- | --- |
| `apply_material` | `scene` A/B、`folder` 1..99、`material` 文件名、`window` main/second | 目标屏上在播这一格的窗口（共享组取基准）换成单只素材 | 只换 playlist，不开第二路解码；切格/重建/墙板照常恢复轮播 |
| `gift_play` | `scene` A/B、`folder` 1..99、`material` 文件名 | 主/副两块根各一层 luma-key 画布 + 独立解码 | 播完或 12s 自动收层；墙板全关中回 `BUSY` |
| `fx` | `kind` ∈ BLACKOUT/STROBE/ROTATE/ZOOM/LOOP/SPEED、`value` 浮点 | `FxController.apply(..., REMOTE)` | 与 DMX 同一状态机；DMX 正持有该特效时回 `BUSY` |
| `fx_clear` | — | `FxController.releaseAll()` | 全部特效回基线 |
| `pause_all` | — | 所有窗口播放器 `playWhenReady = false` | 无播放器回 `OFFLINE` |
| `resume_all` | — | 撤远端黑幕 + 恢复所有播放器 | 黑幕被 DMX 持有回 `BUSY` |
| `stop_all` | — | 远端黑场 alpha = 1（播放继续） | 与 PAD「全关」同义；被 DMX 持有时回 `BUSY` |

数值口径（`fx` 的 `value`，与 DMX 的 raw 同一套语义）：

| kind | value | 释放值 |
| --- | --- | --- |
| BLACKOUT | 0..1（alpha） | 0 |
| STROBE | 0..20（Hz，50% 占空） | 0 |
| ROTATE | -90..90（°/s，负=逆时针） | 0 |
| ZOOM | 1..2（倍，绕窗口中心） | 1 |
| LOOP | ≥0.5 = 开（只循环当前素材） | <0.5 |
| SPEED | 0.25..2.0 | 1 |

未实现（本期不给入口）：`switch_scene`（设备侧没有运行时切幕入口）、任何配置写入（apply_material 不写 config/rev）。

## 3. 设备侧行为

- **单一落点**：特效只经 `FxController`（`app/src/main/java/com/xvj/app/FxController.kt`），DMX 通道 2..7 与 PAD 的 `fx` 共用它；不存在第二份实现。
- **层级**（自下而上）：素材窗 < LOGO < 礼物 < 全关黑布/特效黑幕 < 运维文字。礼物与黑幕都是运行期加进两块根，收层/重建后按账重排（`restackFxLayers`）。
- **黑幕合成**：有效 alpha = max(墙板全关 ? 1 : 0, 特效黑场)；频闪在此基础上 50% 占空。墙板全关期间频闪不生效（全关始终盖得住）。
- **旋转/缩放**：作用在窗口容器（配置的裁剪/旋转/镜像是 TextureView 内部的另一层），绕窗口中心；窗口重建后按覆盖表重放。
- **LOOP / 速度**：改播放器参数，换清单/重建后由 `playFolderInWindow` 重新落一次；LOOP 释放后回 `loopPlay ? ALL : OFF`。
- **不落 config、不进 rev**；退出到桌面 / `stop` / 释放全部窗口时特效与礼物一并释放。
- **DMX 所有权**：链路 5s 内收过帧且该通道非零 ⇒ DMX 持有，此时 PAD 对同一特效的写回 `BUSY`；链路 3s 收不到帧 ⇒ 全部特效释放并记 `DMX LOST`。

## 4. 云端入口

`POST /api/pad/command`（`Authorization: Bearer <PAD token>`）：

```json
{ "action": "fx", "kind": "STROBE", "value": 8.0 }
```

- 只认上表 7 个动作；参数在云端先校验（scene/folder/material/window/kind/value），非法直接 400。
- 房间当前执行盒必须在线，否则 503 `OFFLINE`；同一 PAD 限 45 次/秒（XY 垫 30Hz 合并后仍有余量）。
- 云端生成 `cmd_id` 并挂起 HTTP 响应，等设备 ack（2s 超时）；回执原样带回 `{ok, err, cmd_id, rev}`，并记一条 `operation_logs.pad_command`。
- 管理台直测可走既有 `POST /api/devices/:id/command`（`DEVICE_ACTIONS` 已加入这 7 个动作，会原样下发参数）。

## 5. 验证清单（台架）

1. 配对 PAD → `fx STROBE 8` 看频闪、`fx STROBE 0` 立刻停；
2. DMX 注帧 STROBE 90 时 PAD 再写 STROBE → 回 `BUSY` 且画面不变；
3. 停注 3s → 全部特效回基线、格子不动、日志一条 `DMX LOST`；
4. 持特效时切幕/重建 → 旋转缩放与黑幕仍在（覆盖表重放）；
5. `apply_material` 点 A30 某只 → 目标屏换单只；墙板切格后恢复正常轮播；
6. `gift_play` → 两屏都出礼物层，播完自动收；全关期间送礼回 `BUSY`；
7. 断网 2s 后点按 → 云端 503/超时，现场画面不动。