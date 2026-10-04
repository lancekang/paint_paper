package kr.dfluid.paint.shortcut

import android.view.KeyEvent

enum class ActionKind {
    /** 누를 때 한 번 */
    PRESS,
    /** 누르고 있으면 키 반복마다 */
    REPEAT,
    /** 탭 = 도구 전환, 길게 누름(또는 누른 채 그리기) = 뗄 때 이전 도구로 복귀 */
    TOOL,
    /** 누르고 있는 동안만 유지 (이동/회전/확대/스포이드) */
    HOLD,
}

/** 키 조합. 왼쪽/오른쪽 수식키는 구분하지 않습니다. */
data class KeyBinding(val keyCode: Int, val ctrl: Boolean = false, val shift: Boolean = false, val alt: Boolean = false) {

    fun encode(): String = "$keyCode:${if (ctrl) 1 else 0}${if (shift) 1 else 0}${if (alt) 1 else 0}"

    fun label(): String = buildString {
        if (ctrl) append("Ctrl+")
        if (shift) append("Shift+")
        if (alt) append("Alt+")
        append(keyName(keyCode))
    }

    companion object {
        fun decode(s: String): KeyBinding? {
            val parts = s.split(":")
            if (parts.size != 2 || parts[1].length != 3) return null
            val code = parts[0].toIntOrNull() ?: return null
            return KeyBinding(code, parts[1][0] == '1', parts[1][1] == '1', parts[1][2] == '1')
        }

        fun normalizeKey(code: Int): Int = when (code) {
            KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.KEYCODE_CTRL_LEFT
            KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.KEYCODE_SHIFT_LEFT
            KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.KEYCODE_ALT_LEFT
            KeyEvent.KEYCODE_META_RIGHT -> KeyEvent.KEYCODE_META_LEFT
            KeyEvent.KEYCODE_NUMPAD_ENTER -> KeyEvent.KEYCODE_ENTER
            else -> code
        }

        fun isModifier(code: Int): Boolean = when (normalizeKey(code)) {
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_ALT_LEFT,
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_FUNCTION, KeyEvent.KEYCODE_CAPS_LOCK -> true
            else -> false
        }

        /** 이벤트 → 바인딩. 수식키 자체를 누른 경우 그 수식키의 플래그는 뺍니다 (Alt 단독 홀드 지원). */
        fun fromEvent(e: KeyEvent): KeyBinding? {
            val code = normalizeKey(e.keyCode)
            if (code == KeyEvent.KEYCODE_META_LEFT || code == KeyEvent.KEYCODE_UNKNOWN) return null
            return KeyBinding(
                code,
                ctrl = e.isCtrlPressed && code != KeyEvent.KEYCODE_CTRL_LEFT,
                shift = e.isShiftPressed && code != KeyEvent.KEYCODE_SHIFT_LEFT,
                alt = e.isAltPressed && code != KeyEvent.KEYCODE_ALT_LEFT,
            )
        }

        fun keyName(code: Int): String = when (code) {
            KeyEvent.KEYCODE_SPACE -> "Space"
            KeyEvent.KEYCODE_TAB -> "Tab"
            KeyEvent.KEYCODE_ENTER -> "Enter"
            KeyEvent.KEYCODE_ESCAPE -> "Esc"
            KeyEvent.KEYCODE_DEL -> "Backspace"
            KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
            KeyEvent.KEYCODE_LEFT_BRACKET -> "["
            KeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
            KeyEvent.KEYCODE_MINUS -> "-"
            KeyEvent.KEYCODE_EQUALS -> "="
            KeyEvent.KEYCODE_PLUS -> "+"
            KeyEvent.KEYCODE_COMMA -> ","
            KeyEvent.KEYCODE_PERIOD -> "."
            KeyEvent.KEYCODE_SLASH -> "/"
            KeyEvent.KEYCODE_BACKSLASH -> "\\"
            KeyEvent.KEYCODE_SEMICOLON -> ";"
            KeyEvent.KEYCODE_APOSTROPHE -> "'"
            KeyEvent.KEYCODE_GRAVE -> "`"
            KeyEvent.KEYCODE_CTRL_LEFT -> "Ctrl"
            KeyEvent.KEYCODE_SHIFT_LEFT -> "Shift"
            KeyEvent.KEYCODE_ALT_LEFT -> "Alt"
            KeyEvent.KEYCODE_DPAD_UP -> "↑"
            KeyEvent.KEYCODE_DPAD_DOWN -> "↓"
            KeyEvent.KEYCODE_DPAD_LEFT -> "←"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "→"
            KeyEvent.KEYCODE_PAGE_UP -> "PageUp"
            KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
            KeyEvent.KEYCODE_MOVE_HOME -> "Home"
            KeyEvent.KEYCODE_MOVE_END -> "End"
            KeyEvent.KEYCODE_INSERT -> "Insert"
            else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").let {
                when {
                    it.startsWith("NUMPAD_") -> "Num " + it.removePrefix("NUMPAD_")
                    it.length == 1 -> it
                    else -> it.lowercase().replaceFirstChar { c -> c.uppercase() }
                }
            }
        }
    }
}

private fun k(code: Int, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false) =
    KeyBinding(code, ctrl, shift, alt)

enum class Action(val label: String, val category: String, val kind: ActionKind, val defaults: List<KeyBinding>) {
    // 도구
    TOOL_PEN("펜", "도구", ActionKind.TOOL, listOf(k(KeyEvent.KEYCODE_P))),
    TOOL_PENCIL("연필", "도구", ActionKind.TOOL, listOf(k(KeyEvent.KEYCODE_N))),
    TOOL_AIRBRUSH("에어브러시", "도구", ActionKind.TOOL, listOf(k(KeyEvent.KEYCODE_B))),
    TOOL_MARKER("마커", "도구", ActionKind.TOOL, listOf(k(KeyEvent.KEYCODE_K))),
    TOOL_ERASER("지우개", "도구", ActionKind.TOOL, listOf(k(KeyEvent.KEYCODE_E))),
    TOOL_EYEDROPPER("스포이드", "도구", ActionKind.TOOL, listOf(k(KeyEvent.KEYCODE_I))),
    TOOL_HAND("손바닥", "도구", ActionKind.TOOL, listOf(k(KeyEvent.KEYCODE_H))),
    TOOL_SELECT("선택 영역", "도구", ActionKind.TOOL, listOf(k(KeyEvent.KEYCODE_M))),
    TOOL_MOVE("레이어 이동", "도구", ActionKind.TOOL, listOf(k(KeyEvent.KEYCODE_V))),
    TOOL_FILL("채우기", "도구", ActionKind.TOOL, listOf(k(KeyEvent.KEYCODE_G))),
    TOOL_GRADIENT("그라데이션", "도구", ActionKind.TOOL, listOf(k(KeyEvent.KEYCODE_G, shift = true))),
    HOLD_EYEDROPPER("임시 스포이드 (누르는 동안)", "도구", ActionKind.HOLD, listOf(k(KeyEvent.KEYCODE_ALT_LEFT))),

    // 브러시
    BRUSH_SIZE_DOWN("브러시 크기 줄이기", "브러시", ActionKind.REPEAT, listOf(k(KeyEvent.KEYCODE_LEFT_BRACKET))),
    BRUSH_SIZE_UP("브러시 크기 키우기", "브러시", ActionKind.REPEAT, listOf(k(KeyEvent.KEYCODE_RIGHT_BRACKET))),
    BRUSH_OPACITY_DOWN("불투명도 −10%", "브러시", ActionKind.REPEAT, listOf(k(KeyEvent.KEYCODE_LEFT_BRACKET, shift = true))),
    BRUSH_OPACITY_UP("불투명도 +10%", "브러시", ActionKind.REPEAT, listOf(k(KeyEvent.KEYCODE_RIGHT_BRACKET, shift = true))),
    COLOR_SWAP("주색·보조색 바꾸기", "브러시", ActionKind.PRESS, listOf(k(KeyEvent.KEYCODE_X))),
    COLOR_RESET("흑백으로 초기화", "브러시", ActionKind.PRESS, listOf(k(KeyEvent.KEYCODE_D))),
    BRUSH_PREV("이전 보조 도구", "브러시", ActionKind.PRESS, listOf(k(KeyEvent.KEYCODE_COMMA))),
    BRUSH_NEXT("다음 보조 도구", "브러시", ActionKind.PRESS, listOf(k(KeyEvent.KEYCODE_PERIOD))),
    BRUSH_EDIT("브러시 설정 열기", "브러시", ActionKind.PRESS, listOf(k(KeyEvent.KEYCODE_F5))),

    // 편집
    UNDO("실행취소", "편집", ActionKind.REPEAT, listOf(k(KeyEvent.KEYCODE_Z, ctrl = true))),
    REDO("다시실행", "편집", ActionKind.REPEAT, listOf(k(KeyEvent.KEYCODE_Y, ctrl = true), k(KeyEvent.KEYCODE_Z, ctrl = true, shift = true))),
    TRANSFORM("자유 변형", "편집", ActionKind.PRESS, listOf(k(KeyEvent.KEYCODE_T, ctrl = true))),
    FILL_SELECTION("선택 영역 채우기 (주색)", "편집", ActionKind.PRESS, listOf(k(KeyEvent.KEYCODE_DEL, alt = true), k(KeyEvent.KEYCODE_FORWARD_DEL, alt = true))),

    // 선택
    SELECT_ALL("모두 선택", "선택", ActionKind.PRESS, listOf(k(KeyEvent.KEYCODE_A, ctrl = true))),
    SELECT_NONE("선택 해제", "선택", ActionKind.PRESS, listOf(k(KeyEvent.KEYCODE_D, ctrl = true))),
    SELECT_INVERT("선택 반전", "선택", ActionKind.PRESS, listOf(k(KeyEvent.KEYCODE_I, ctrl = true, shift = true))),

    // 레이어
    LAYER_NEW("새 레이어", "레이어", ActionKind.PRESS, listOf(k(KeyEvent.KEYCODE_N, ctrl = true, shift = true))),
    LAYER_DUPLICATE("레이어 복제", "레이어", ActionKind.PRESS, listOf(k(KeyEvent.KEYCODE_J, ctrl = true))),
    LAYER_FOLDER("새 폴더", "레이어", ActionKind.PRESS, listOf(k(KeyEvent.KEYCODE_N, ctrl = true, alt = true))),
    LAYER_GROUP("폴더로 묶기", "레이어", ActionKind.PRESS, listOf(k(KeyEvent.KEYCODE_G, ctrl = true))),
    LAYER_CLIP("아래 레이어에서 클리핑", "레이어", ActionKind.PRESS, listOf(k(KeyEvent.KEYCODE_G, ctrl = true, alt = true))),
    LAYER_ALPHA_LOCK("투명 픽셀 잠금", "레이어", ActionKind.PRESS, listOf(k(KeyEvent.KEYCODE_SLASH))),
    LAYER_MERGE_DOWN("아래 레이어와 병합", "레이어", ActionKind.PRESS, listOf(k(KeyEvent.KEYCODE_E, ctrl = true))),
    LAYER_CLEAR("레이어 지우기", "레이어", ActionKind.PRESS, listOf(k(KeyEvent.KEYCODE_FORWARD_DEL), k(KeyEvent.KEYCODE_DEL))),
    LAYER_SELECT_UP("위 레이어 선택", "레이어", ActionKind.REPEAT, listOf(k(KeyEvent.KEYCODE_RIGHT_BRACKET, alt = true))),
    LAYER_SELECT_DOWN("아래 레이어 선택", "레이어", ActionKind.REPEAT, listOf(k(KeyEvent.KEYCODE_LEFT_BRACKET, alt = true))),

    // 보기
    HOLD_PAN("이동 (누른 채 드래그)", "보기", ActionKind.HOLD, listOf(k(KeyEvent.KEYCODE_SPACE))),
    HOLD_ROTATE("회전 (누른 채 드래그)", "보기", ActionKind.HOLD, listOf(k(KeyEvent.KEYCODE_R))),
    HOLD_ZOOM("확대/축소 (누른 채 드래그)", "보기", ActionKind.HOLD, listOf(k(KeyEvent.KEYCODE_Z))),
    VIEW_ZOOM_IN("확대", "보기", ActionKind.REPEAT, listOf(k(KeyEvent.KEYCODE_EQUALS, ctrl = true), k(KeyEvent.KEYCODE_PLUS, ctrl = true), k(KeyEvent.KEYCODE_NUMPAD_ADD))),
    VIEW_ZOOM_OUT("축소", "보기", ActionKind.REPEAT, listOf(k(KeyEvent.KEYCODE_MINUS, ctrl = true), k(KeyEvent.KEYCODE_NUMPAD_SUBTRACT))),
    VIEW_FIT("화면에 맞추기", "보기", ActionKind.PRESS, listOf(k(KeyEvent.KEYCODE_0, ctrl = true))),
    VIEW_ACTUAL("100% 보기", "보기", ActionKind.PRESS, listOf(k(KeyEvent.KEYCODE_1, ctrl = true))),
    VIEW_ROTATE_LEFT("왼쪽으로 15° 회전", "보기", ActionKind.REPEAT, listOf(k(KeyEvent.KEYCODE_4))),
    VIEW_ROTATE_RIGHT("오른쪽으로 15° 회전", "보기", ActionKind.REPEAT, listOf(k(KeyEvent.KEYCODE_6))),
    VIEW_ROTATE_RESET("회전 초기화", "보기", ActionKind.PRESS, listOf(k(KeyEvent.KEYCODE_5))),
    VIEW_FLIP("좌우 반전 보기", "보기", ActionKind.PRESS, listOf(k(KeyEvent.KEYCODE_H, shift = true))),
    TOGGLE_UI("UI 숨기기/보이기", "보기", ActionKind.PRESS, listOf(k(KeyEvent.KEYCODE_TAB))),

    // 파일
    FILE_NEW("새 캔버스", "파일", ActionKind.PRESS, listOf(k(KeyEvent.KEYCODE_N, ctrl = true))),
    FILE_OPEN("열기", "파일", ActionKind.PRESS, listOf(k(KeyEvent.KEYCODE_O, ctrl = true))),
    FILE_SAVE("저장", "파일", ActionKind.PRESS, listOf(k(KeyEvent.KEYCODE_S, ctrl = true))),
    FILE_SAVE_AS("다른 이름으로 저장", "파일", ActionKind.PRESS, listOf(k(KeyEvent.KEYCODE_S, ctrl = true, shift = true))),
    FILE_EXPORT_PNG("PNG로 내보내기", "파일", ActionKind.PRESS, listOf(k(KeyEvent.KEYCODE_E, ctrl = true, shift = true))),
    FILE_EXPORT_PSD("PSD로 내보내기", "파일", ActionKind.PRESS, listOf(k(KeyEvent.KEYCODE_E, ctrl = true, alt = true))),
}
