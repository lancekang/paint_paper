package kr.dfluid.paint.shortcut

import android.content.Context
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/** 키맵 저장소. SharedPreferences에 JSON으로 저장합니다. 사용자가 바꾼 동작만 저장됩니다. */
class ShortcutStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("shortcuts", Context.MODE_PRIVATE)
    private val bindings = LinkedHashMap<Action, List<KeyBinding>>()
    private var lookup: Map<KeyBinding, Action> = emptyMap()

    init {
        load()
    }

    fun load() {
        bindings.clear()
        Action.entries.forEach { bindings[it] = it.defaults }
        val raw = prefs.getString(KEY, null)
        if (raw != null) {
            try {
                val o = JSONObject(raw)
                for (name in o.keys()) {
                    val action = Action.entries.firstOrNull { it.name == name } ?: continue
                    val arr = o.getJSONArray(name)
                    bindings[action] = (0 until arr.length()).mapNotNull { KeyBinding.decode(arr.getString(it)) }
                }
            } catch (e: Exception) {
                // 손상된 설정은 무시하고 기본값 사용
            }
        }
        rebuild()
    }

    private fun save() {
        val o = JSONObject()
        bindings.forEach { (action, list) ->
            if (list != action.defaults) o.put(action.name, JSONArray(list.map { it.encode() }))
        }
        prefs.edit().putString(KEY, o.toString()).apply()
    }

    private fun rebuild() {
        val m = HashMap<KeyBinding, Action>()
        bindings.forEach { (action, list) -> list.forEach { m.putIfAbsent(it, action) } }
        lookup = m
    }

    fun get(action: Action): List<KeyBinding> = bindings[action] ?: emptyList()

    fun find(binding: KeyBinding): Action? = lookup[binding]

    fun set(action: Action, list: List<KeyBinding>) {
        bindings[action] = list.distinct()
        rebuild()
        save()
    }

    /** 다른 동작에서 이 조합을 떼어냅니다. */
    fun unbindEverywhere(binding: KeyBinding) {
        bindings.keys.toList().forEach { a ->
            val l = bindings[a] ?: return@forEach
            if (binding in l) bindings[a] = l - binding
        }
        rebuild()
        save()
    }

    fun reset(action: Action) = set(action, action.defaults)

    fun resetAll() {
        prefs.edit().remove(KEY).apply()
        load()
    }

    companion object {
        private const val KEY = "bindings_v1"
    }
}

/**
 * KeyEvent → 동작. Activity.dispatchKeyEvent 맨 앞에서 호출합니다.
 *
 * TOOL: 누르는 즉시 전환. 뗄 때 400ms 이상 눌렀거나 그 사이 획을 그었다면 이전 도구로 복귀.
 * HOLD: 누르는 동안만 유지.
 */
class ShortcutDispatcher(private val store: ShortcutStore, private val handler: Handler) {

    interface Handler {
        fun onShortcut(action: Action)
        fun onToolKey(action: Action, down: Boolean, temporary: Boolean)
        fun onHoldKey(action: Action, down: Boolean)
    }

    private class Held(val action: Action, val downTime: Long) {
        var used = false
    }

    private val held = HashMap<Int, Held>()

    /** 처리했으면 true. */
    fun onKeyEvent(e: KeyEvent): Boolean {
        val code = KeyBinding.normalizeKey(e.keyCode)
        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (held.containsKey(code)) return true // 누르고 있는 중의 자동 반복
                val binding = KeyBinding.fromEvent(e) ?: return false
                val action = store.find(binding) ?: return false
                if (e.repeatCount > 0) {
                    if (action.kind == ActionKind.REPEAT) handler.onShortcut(action)
                    return true
                }
                when (action.kind) {
                    ActionKind.PRESS, ActionKind.REPEAT -> handler.onShortcut(action)
                    ActionKind.TOOL -> {
                        held[code] = Held(action, e.eventTime)
                        handler.onToolKey(action, down = true, temporary = false)
                    }
                    ActionKind.HOLD -> {
                        held[code] = Held(action, e.eventTime)
                        handler.onHoldKey(action, down = true)
                    }
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                val h = held.remove(code)
                if (h == null) {
                    val binding = KeyBinding.fromEvent(e) ?: return false
                    return store.find(binding) != null
                }
                release(h, e.eventTime)
                return true
            }
        }
        return false
    }

    private fun release(h: Held, time: Long) {
        when (h.action.kind) {
            ActionKind.TOOL -> handler.onToolKey(h.action, down = false, temporary = h.used || time - h.downTime > HOLD_MS)
            ActionKind.HOLD -> handler.onHoldKey(h.action, down = false)
            else -> Unit
        }
    }

    /** 도구 키를 누른 채 획을 그었을 때 호출 → 뗄 때 이전 도구로 복귀. */
    fun markUsed() {
        held.values.forEach { it.used = true }
    }

    /** 포커스를 잃으면 KEY_UP이 안 올 수 있으므로 모두 해제합니다. */
    fun releaseAll() {
        val list = held.values.toList()
        held.clear()
        list.forEach { release(it, Long.MAX_VALUE) }
    }

    val isAnyHeld: Boolean get() = held.isNotEmpty()

    companion object {
        const val HOLD_MS = 400L
    }
}
