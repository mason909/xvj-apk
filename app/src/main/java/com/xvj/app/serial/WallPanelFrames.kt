package com.xvj.app.serial

import org.json.JSONArray
import org.json.JSONObject

/**
 * 帧层的两样纯逻辑：收到的帧台账（排障与学习模式的候选列表）和学习来的「帧 → 动作」绑定表。
 * 都只认帧的 hex 指纹，不认语义 —— 语义随中控/墙板厂家变，字节不会（见记忆 xvj-wallpanel-protocol）。
 * 无 Android 依赖，与前端 wp-selftest 移植同一套口径。
 */
object WallPanelHex {

    /** 帧字节 → 大写十六进制串（无分隔）：指纹与云端绑定表都用这一种写法。 */
    fun of(frame: ByteArray): String {
        val sb = StringBuilder(frame.size * 2)
        for (b in frame) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_DIGITS[v shr 4]).append(HEX_DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    /** 合法指纹 = 偶数长度、1~64 字节的大写十六进制。云端 findWallpanelBindingsError 同一把闸。
     *  最小 1 字节：小麦那族功能码就是单字节（D3、B5 这种），别按"帧至少两字节"把现场按键滤掉。 */
    fun isValid(hex: String): Boolean =
        hex.length in 2..(WallPanelParser.MAX_FRAME * 2) && hex.length % 2 == 0 &&
            hex.all { it in '0'..'9' || it in 'A'..'F' }

    /** 文件夹格名 = 两位数字 01~30（与素材目录的格子键同一把尺） */
    fun isFolderCell(fid: String): Boolean = fid.length == 2 && fid.all { it.isDigit() } && fid in "01".."30"

    private val HEX_DIGITS = "0123456789ABCDEF"
}

/**
 * 收到过的帧去重台账：同一帧反复来只加计数，学习模式要的正是"这一段安静下来之后有哪几种帧"。
 * 只留 ttl 内有动静的，超出 capacity 淘汰最久没动的 —— 点歌机在合并总线上会连发，
 * 不设闸的话台账会被同一种状态帧刷满，把刚按下的墙板键顶出去。
 */
class WallPanelFrameLedger(
    private val capacity: Int = 24,
    private val ttlMs: Long = 60_000,
) {
    class Entry(val hex: String, var count: Int, var lastAt: Long)

    private val items = LinkedHashMap<String, Entry>()

    fun record(hex: String, nowMs: Long) {
        val hit = items[hex]
        if (hit != null) {
            hit.count++
            hit.lastAt = nowMs
            return
        }
        items[hex] = Entry(hex, 1, nowMs)
        if (items.size > capacity) prune(nowMs)
    }

    /** 快照：最近有动静的排前面，过期的顺手剔掉。上报与学习候选列表共用。 */
    fun snapshot(nowMs: Long, limit: Int = 8): List<Entry> {
        prune(nowMs)
        return items.values.sortedByDescending { it.lastAt }.take(limit)
    }

    /** 学习窗开始：清账，让候选列表只装"按下学习之后收到的帧"，操作员不必自己分辨哪条是旧的。 */
    fun clear() {
        items.clear()
    }

    fun isEmpty(): Boolean = items.isEmpty()

    private fun prune(nowMs: Long) {
        val dead = items.entries.filter { nowMs - it.value.lastAt > ttlMs }
        dead.forEach { items.remove(it.key) }
        // 全是活的还得削到容量：按"最久没动"削，保证刚按下的那一帧一定留着
        while (items.size > capacity) {
            val oldest = items.entries.minByOrNull { it.value.lastAt } ?: break
            items.remove(oldest.key)
        }
    }

    fun toJson(nowMs: Long, limit: Int = 8): JSONArray {
        val arr = JSONArray()
        snapshot(nowMs, limit).forEach {
            arr.put(JSONObject().put("hex", it.hex).put("n", it.count).put("age", maxOf(0L, nowMs - it.lastAt)))
        }
        return arr
    }
}

/**
 * 一条学习来的动作：把跟随窗口切到某一格。
 * 学习模式的主用法就是"选中格子 → 按键 → 这帧落进这格"，没有别的动作类型 ——
 * 切幕/特效/亮度要等设备端先有对应的 live 通道，不在这张表里放空壳。
 */
sealed class PanelAction {
    data class Folder(val folder: String) : PanelAction()
}

/**
 * 动了画面的那一次墙板触发源（只活在内存，重启退回"没按过"）。
 * 记的是"哪一帧让我切的"，具体切到哪一格交给学习表解析。
 */
sealed class PanelSource {
    /** 命中学习绑定表的帧指纹 */
    data class Frame(val hex: String) : PanelSource()
}

/**
 * 学习绑定表：房间 config.wallpanel_bindings 的 [{ frame, folder }] → hex→动作。
 * 一格一码、不分幕：同一颗按键在 A/B 两幕里切到同一格（现场就一块墙板，同一个键没理由两幕播不同目录）。
 * 脏规则（帧形状不对 / 格子号越界）当场丢掉：本机不赌云端，但也不因为一条脏规则罢工。
 */
class WallPanelBindings private constructor(
    private val byFrame: Map<String, PanelAction>,
) {
    fun actionFor(hex: String): PanelAction? = byFrame[hex]

    fun isEmpty(): Boolean = byFrame.isEmpty()

    /** 绑出去的帧总数（日志与"这台机配了几条"用） */
    fun size(): Int = byFrame.size

    companion object {
        val EMPTY = WallPanelBindings(emptyMap())

        fun parse(root: JSONArray?): WallPanelBindings {
            if (root == null) return EMPTY
            val table = HashMap<String, PanelAction>()
            for (i in 0 until root.length()) {
                val r = root.optJSONObject(i) ?: continue
                val hex = r.optString("frame").trim().uppercase()
                if (!WallPanelHex.isValid(hex)) continue
                // 云端落库用裸格名 "01"；带幕前缀的旧写法也认
                val fid = r.optString("folder").trim().replace(Regex("^[AB](?=\\d{2}$)"), "")
                if (!WallPanelHex.isFolderCell(fid)) continue
                table[hex] = PanelAction.Folder(fid)
            }
            return WallPanelBindings(table)
        }
    }
}
