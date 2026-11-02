package show.taps.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import show.taps.StEngine
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

@Serializable
data class StPresetSet(val items: List<StPreset> = emptyList())

/**
 * 预设/效果包的本地库。
 *
 * 目录结构（都在 filesDir 下，不需要任何存储权限）：
 *   filesDir/st_presets.json           ← 索引（本文件的持久化对象）
 *   filesDir/st_packs/<id>/...         ← 效果包解压出来的素材
 *
 * 导入支持两种文件：
 *   1. 单个 .json
 *        { "name": "...", "desc": "...", "config": { ...StConfig 字段... } }   → 预设
 *        或者直接就是一个 StConfig（含 pointSize / colors 字段）              → 预设
 *        或者带 layers / engine 字段（效果包 schema）                          → 效果包
 *   2. .zip 包：里面任意位置有一个 json（取层级最浅的），其余文件当素材解压
 *
 * 安全：只解析 JSON + 图片，不执行任何代码；单个包上限 32MB。
 */
object StPresetStore {

    private const val TAG = "StPresetStore"
    private const val FILE_NAME = "st_presets.json"
    private const val PACK_DIR = "st_packs"
    private const val MAX_PACK_BYTES = 32L * 1024 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private var file: File? = null

    private val _state = MutableStateFlow(StPresetSet())
    val state: StateFlow<StPresetSet> = _state.asStateFlow()

    /** FAB → 预设页 的「请打开文件选择器」信号（自增计数） */
    private val _importRequest = MutableStateFlow(0L)
    val importRequest: StateFlow<Long> = _importRequest.asStateFlow()

    fun requestImport() {
        _importRequest.value = _importRequest.value + 1
    }

    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        file = f
        val loaded = runCatching { json.decodeFromString(StPresetSet.serializer(), f.readText()) }
            .onFailure { if (f.exists()) Log.w(TAG, "load failed: $it") }
            .getOrNull()
        _state.value = loaded ?: StPresetSet()
    }

    fun byId(id: String): StPreset? = _state.value.items.firstOrNull { it.id == id }

    /* --------------------------- 导入 --------------------------- */

    fun import(context: Context, uri: Uri): Result<StPreset> {
        init(context)
        var bytes: ByteArray? = null
        return runCatching {
            val name = queryDisplayName(context, uri)
            val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("无法读取文件")
            bytes = data
            if (data.size < 2) error("文件为空")

            val isZip = data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte()
            val preset = if (isZip) readZip(context, data, name) else readJson(data, name, name)
            persist(preset)
            Log.d(TAG, "imported ${preset.kind} ${preset.name} from $name")
            preset
        }.onFailure { e ->
            Log.w(TAG, "import failed: ${e.message}")
            // 留一份原始文件 + 头部内容到 logcat，出问题能直接定位
            runCatching {
                val head = bytes?.let { String(it, 0, minOf(it.size, 200), Charsets.UTF_8) }
                    .orEmpty().replace("\n", " ")
                Log.w(TAG, "raw head: $head")
                bytes?.let { b ->
                    val dir = File(context.applicationContext.filesDir, "st_import_debug")
                    dir.mkdirs()
                    File(dir, "last_import.bin").writeBytes(b)
                    Log.w(TAG, "raw saved: ${File(dir, "last_import.bin").absolutePath} (${b.size} bytes)")
                }
            }
        }
    }

    private fun persist(preset: StPreset) {
        val cur = _state.value
        val list = cur.items.filterNot { it.id == preset.id } + preset
        val set = StPresetSet(list)
        _state.value = set
        runCatching { file?.writeText(json.encodeToString(StPresetSet.serializer(), set)) }
            .onFailure { Log.w(TAG, "save failed: $it") }
    }

    private fun readJson(bytes: ByteArray, hint: String?, displayName: String?): StPreset {
        val raw = bytes.toString(Charsets.UTF_8)
        if (raw.trimStart().startsWith("<")) {
            error("这是 HTML/XML 效果包，需要 WebView 渲染引擎，当前版本还不支持")
        }
        return parsePreset(tolerantJson(raw), hint, displayName, raw)
    }

    /**
     * 手写 JSON 常见的三件事：BOM、// 注释、尾随逗号。
     * 这里都在字符串状态机里处理，不碰字符串内部的内容。
     */
    private fun tolerantJson(text: String): String {
        val s = text.removePrefix("\uFEFF")
        val sb = StringBuilder(s.length)
        var inStr = false
        var esc = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                sb.append(c)
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
                i++
                continue
            }
            when {
                c == '"' -> {
                    inStr = true
                    sb.append(c)
                    i++
                }
                c == '/' && i + 1 < s.length && s[i + 1] == '/' -> {
                    while (i < s.length && s[i] != '\n') i++
                }
                c == '/' && i + 1 < s.length && s[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < s.length && !(s[i] == '*' && s[i + 1] == '/')) i++
                    i = minOf(s.length, i + 2)
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return stripTrailingCommas(sb.toString())
    }

    /** 去掉 `,}` 和 `,]`（同样跳过字符串内部） */
    private fun stripTrailingCommas(text: String): String {
        val out = StringBuilder(text.length)
        var inStr = false
        var esc = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inStr) {
                out.append(c)
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
                i++
                continue
            }
            if (c == '"') {
                inStr = true
                out.append(c)
                i++
                continue
            }
            if (c == ',') {
                var j = i + 1
                while (j < text.length && text[j].isWhitespace()) j++
                if (j < text.length && (text[j] == '}' || text[j] == ']')) {
                    i++ // 丢掉这个逗号
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun readZip(context: Context, bytes: ByteArray, displayName: String?): StPreset {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.replace('\\', '/')
                if (name.startsWith("__MACOSX/") || name.endsWith(".DS_Store")) continue
                val data = zis.readBytes()
                total += data.size
                if (total > MAX_PACK_BYTES) error("包超过 32MB")
                entries[name] = data
            }
        }
        if (entries.isEmpty()) error("压缩包是空的")

        val jsonEntry = entries.entries
            .filter { it.key.lowercase().endsWith(".json") }
            .minByOrNull { it.key.count { c -> c == '/' } }
            ?: error("包里没有 json 描述文件")

        val entryText = jsonEntry.value.toString(Charsets.UTF_8)
        if (entryText.trimStart().startsWith("<")) {
            error("包里的 ${jsonEntry.key} 是 HTML/XML，需要 WebView 渲染引擎，当前版本还不支持")
        }
        val parsed = parsePreset(tolerantJson(entryText), jsonEntry.key, displayName, entryText)

        // 素材落到 filesDir/st_packs/<id>/
        val dir = File(File(context.applicationContext.filesDir, PACK_DIR), parsed.id)
        dir.deleteRecursively()
        dir.mkdirs()
        entries.forEach { (name, data) ->
            if (name == jsonEntry.key) return@forEach
            val out = File(dir, name)
            out.parentFile?.mkdirs()
            runCatching { out.writeBytes(data) }
        }
        Log.d(TAG, "unpacked ${entries.size - 1} files -> ${dir.absolutePath}")
        return parsed.copy(assetDir = dir.absolutePath)
    }

    private fun parsePreset(
        text: String,
        hint: String?,
        displayName: String?,
        rawForError: String? = null,
    ): StPreset {
        val element = runCatching { json.parseToJsonElement(text) }.getOrElse { e ->
            val head = (rawForError ?: text).trim().take(80).replace("\n", " ")
            error("不是合法 JSON（${e.message?.take(60)}）开头：$head")
        }
        // 允许顶层是数组（取第一个对象）
        val root = when (element) {
            is JsonObject -> element
            is JsonArray -> element.firstOrNull() as? JsonObject
                ?: error("JSON 数组里没有对象")
            else -> error("JSON 顶层既不是对象也不是数组")
        }

        // 情况 A：文件本身就是一套 StConfig
        if (root.containsKey("pointSize") || root.containsKey("size") || root.containsKey("colors")) {
            val cfg = parseConfig(root)
            return StPreset(
                id = newId(cfg.name),
                name = cfg.name,
                kind = StPresetKind.PRESET,
                config = cfg,
                importedAt = System.currentTimeMillis(),
            )
        }

        val name = root["name"]?.jsonPrimitive?.contentOrNull
            ?: displayName?.substringBeforeLast('.')
            ?: hint?.substringAfterLast('/')?.substringBeforeLast('.')
            ?: "未命名预设"
        val desc = root["desc"]?.jsonPrimitive?.contentOrNull
            ?: root["description"]?.jsonPrimitive?.contentOrNull
            ?: ""

        // 情况 B：带 config 子对象的预设
        val cfgObj = root["config"] as? JsonObject
        if (cfgObj != null) {
            val cfg = parseConfig(cfgObj)
            return StPreset(
                id = newId(name),
                name = name,
                kind = StPresetKind.PRESET,
                desc = desc,
                config = cfg,
                importedAt = System.currentTimeMillis(),
            )
        }

        // 情况 C：效果包（有 layers / engine 声明），先只保存原文
        val isPack = root.containsKey("layers") || root.containsKey("engine") ||
                root.containsKey("params") || root.containsKey("render")
        if (!isPack) {
            error("缺少 config 或 layers 字段（可能不是预设文件）")
        }
        val kind = StPresetKind.PACK
        return StPreset(
            id = newId(name),
            name = name,
            kind = kind,
            desc = desc,
            packJson = text,
            importedAt = System.currentTimeMillis(),
        )
    }

    /**
     * 宽容解析一套 StConfig。
     *
     * 为什么不用 kotlinx 的 decodeFromJsonElement：手写预设最常见的颜色写法是
     * `#FF4CA7FF` 或 0xAARRGGBB 的无符号整数（4283214335），两者都会直接抛
     * 「Expected int32」，用户只看到"字段不合法"。这里改成逐字段读 + 自己解析颜色。
     */
    private fun parseConfig(obj: JsonObject): StConfig {
        val def = StConfig.DEFAULT
        val name = obj.strOr("name") ?: obj.strOr("title") ?: def.name
        val colors = obj.colorsOr("colors") ?: def.colors
        return StConfig(
            id = 0L,
            name = name,
            pointSize = obj.intOr("pointSize", obj.intOr("size", def.pointSize)).coerceIn(8, 400),
            fadeTime = obj.intOr("fadeTime", obj.intOr("fade", def.fadeTime)).coerceIn(0, 5000),
            circleStroke = obj.intOr("circleStroke", def.circleStroke).coerceIn(0, 50),
            lineStroke = obj.intOr("lineStroke", def.lineStroke).coerceIn(0, 50),
            alpha = obj.intOr("alpha", obj.intOr("opacity", def.alpha)).coerceIn(0, 100),
            colors = colors,
        )
    }

    private fun JsonObject.strOr(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.intOr(key: String, def: Int): Int {
        val p = this[key] as? JsonPrimitive ?: return def
        val raw = p.contentOrNull ?: return def
        raw.toIntOrNull()?.let { return it }
        raw.toLongOrNull()?.let { return it.toInt() }        // 无符号 ARGB 这类超出 Int 的
        raw.toDoubleOrNull()?.let { return it.toInt() }
        return def
    }

    private fun JsonObject.colorsOr(key: String): List<Int>? {
        val arr = this[key] as? JsonArray ?: return null
        val out = arr.mapNotNull { el ->
            val p = el as? JsonPrimitive ?: return@mapNotNull null
            val raw = p.contentOrNull ?: return@mapNotNull null
            if (p.isString) parseColorString(raw) else raw.toLongOrNull()?.toInt()
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** 支持 `#AARRGGBB` / `#RRGGBB` / `0xAARRGGBB` / `AARRGGBB` / `RRGGBB` */
    private fun parseColorString(raw: String): Int? {
        var t = raw.trim()
        if (t.startsWith("#")) t = t.substring(1)
        else if (t.startsWith("0x") || t.startsWith("0X")) t = t.substring(2)
        if (t.isEmpty() || t.length !in 6..8) return null
        val v = t.toLongOrNull(16) ?: return null
        return if (t.length == 6) (0xFF000000L or v).toInt() else v.toInt()
    }

    /* --------------------------- 应用 / 删除 --------------------------- */

    /** 套用预设：写入配置库并立即生效（引擎在跑就热更新） */
    fun apply(context: Context, preset: StPreset): Boolean {
        StConfigStore.init(context)
        val id = preset.config?.let { cfg ->
            // 纯预设：直接按它的参数建一套配置
            StConfigStore.upsert(cfg.copy(id = 0L, name = preset.name))
        } ?: run {
            // 效果包：沿用当前生效配置的绘制参数，只把 packId 挂上去
            val base = StConfigStore.snapshot.active ?: StConfig.DEFAULT
            StConfigStore.upsert(base.copy(id = 0L, name = preset.name, packId = preset.id))
        }
        StConfigStore.setActive(id)
        StConfigStore.byId(id)?.let { StEngine.onConfigChanged(it) }
        return true
    }

    fun delete(context: Context, id: String) {
        init(context)
        val target = byId(id)
        target?.assetDir?.let { dir -> runCatching { File(dir).deleteRecursively() } }
        val set = StPresetSet(_state.value.items.filterNot { it.id == id })
        _state.value = set
        runCatching { file?.writeText(json.encodeToString(StPresetSet.serializer(), set)) }
    }

    /* --------------------------- 工具 --------------------------- */

    private fun newId(name: String): String =
        "${System.currentTimeMillis().toString(36)}-${name.hashCode().toString(16)}"

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
}
