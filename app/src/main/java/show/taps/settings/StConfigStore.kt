package show.taps.settings

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File

/**
 * ST 配置的持久化（JSON 落在 filesDir），以及"当前生效的配置"。
 * 故意不用 Room/DataStore：这里只有几十字节数据，JSON 文件最省事。
 */
object StConfigStore {

    private const val TAG = "StConfigStore"
    private const val FILE_NAME = "st_configs.json"

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private var file: File? = null

    private val _state = MutableStateFlow(StConfigSet())
    val state: StateFlow<StConfigSet> = _state.asStateFlow()

    val snapshot: StConfigSet get() = _state.value

    /** 幂等；建议在 Composition 里第一次用到时调用（见 ensureInit） */
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        file = f
        val loaded = runCatching { json.decodeFromString(StConfigSet.serializer(), f.readText()) }
            .onFailure { Log.w(TAG, "load failed: $it") }
            .getOrNull()

        val set = if (loaded != null && loaded.configs.isNotEmpty()) {
            val active = loaded.activeId.takeIf { id -> loaded.configs.any { it.id == id } }
                ?: loaded.configs.first().id
            loaded.copy(activeId = active)
        } else {
            StConfigSet(listOf(StConfig.DEFAULT), StConfig.DEFAULT.id)
        }
        _state.value = set
        save(set)
    }

    fun byId(id: Long?): StConfig? =
        _state.value.configs.firstOrNull { it.id == id }

    fun setActive(id: Long) {
        val cur = _state.value
        if (cur.configs.none { it.id == id }) return
        update(cur.copy(activeId = id))
    }

    /** 新增或更新，返回 id */
    fun upsert(cfg: StConfig): Long {
        val cur = _state.value
        val id = if (cfg.id != 0L && cur.configs.any { it.id == cfg.id }) cfg.id else nextId(cur)
        Log.d(TAG, "upsert in: id=${cfg.id} name=${cfg.name} size=${cfg.pointSize} -> out id=$id")
        val stored = cfg.copy(id = id)
        val list = if (cur.configs.any { it.id == id }) {
            cur.configs.map { if (it.id == id) stored else it }
        } else {
            cur.configs + stored
        }
        update(cur.copy(configs = list, activeId = if (cur.activeId == 0L) id else cur.activeId))
        return id
    }

    fun delete(id: Long) {
        val cur = _state.value
        val left = cur.configs.filterNot { it.id == id }
        if (left.isEmpty()) {
            // 至少留一条
            val def = StConfig.DEFAULT.copy(id = nextId(StConfigSet()))
            update(StConfigSet(listOf(def), def.id))
            return
        }
        update(cur.copy(configs = left, activeId = if (cur.activeId == id) left.first().id else cur.activeId))
    }

    private fun nextId(cur: StConfigSet): Long = (cur.configs.maxOfOrNull { it.id } ?: 0L) + 1L

    private fun update(newValue: StConfigSet) {
        _state.value = newValue
        save(newValue)
    }

    private fun save(set: StConfigSet) {
        val f = file ?: run {
            Log.e(TAG, "save skipped: store not initialized")
            return
        }
        runCatching { f.writeText(json.encodeToString(StConfigSet.serializer(), set)) }
            .onSuccess { Log.d(TAG, "saved activeId=${set.activeId} count=${set.configs.size} -> ${f.absolutePath}") }
            .onFailure { Log.e(TAG, "save failed: $it") }
    }
}
