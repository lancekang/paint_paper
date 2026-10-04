package kr.dfluid.paint.shortcut

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * 단축키 목록과 재지정 화면.
 * 행을 누르면 "바꾸기 / 추가 / 지우기 / 기본값" 선택 → 키 입력 대기 다이얼로그.
 */
class ShortcutSettingsActivity : Activity() {

    private lateinit var store: ShortcutStore
    private lateinit var adapter: RowAdapter

    private sealed class Row {
        data class Header(val title: String) : Row()
        data class Item(val action: Action) : Row()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 앱 테마(라이트/다크)를 따라갑니다.
        setTheme(if (kr.dfluid.paint.ui.Ui.dark) android.R.style.Theme_Material else android.R.style.Theme_Material_Light)
        super.onCreate(savedInstanceState)
        store = ShortcutStore(this)
        adapter = RowAdapter(buildRows())
        val list = ListView(this).apply {
            this.adapter = this@ShortcutSettingsActivity.adapter
            setOnItemClickListener { _, _, position, _ ->
                val row = this@ShortcutSettingsActivity.adapter.getItem(position)
                if (row is Row.Item) showOptions(row.action)
            }
        }
        setContentView(list)
        actionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun buildRows(): List<Row> {
        val rows = ArrayList<Row>()
        Action.entries.groupBy { it.category }.forEach { (cat, actions) ->
            rows.add(Row.Header(cat))
            actions.forEach { rows.add(Row.Item(it)) }
        }
        return rows
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_RESET, 0, "모두 기본값으로").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish(); return true
            }
            MENU_RESET -> {
                kr.dfluid.paint.ui.Ui.dialog(this)
                    .setMessage("모든 단축키를 기본값으로 되돌릴까요?")
                    .setPositiveButton("되돌리기") { _, _ ->
                        store.resetAll()
                        adapter.notifyDataSetChanged()
                    }
                    .setNegativeButton("취소", null)
                    .show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showOptions(action: Action) {
        val options = arrayOf("단축키 바꾸기", "단축키 추가", "모두 지우기", "기본값으로")
        kr.dfluid.paint.ui.Ui.dialog(this)
            .setTitle(action.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> capture(action, replace = true)
                    1 -> capture(action, replace = false)
                    2 -> {
                        store.set(action, emptyList()); adapter.notifyDataSetChanged()
                    }
                    3 -> {
                        store.reset(action); adapter.notifyDataSetChanged()
                    }
                }
            }
            .show()
    }

    /** 키 입력 대기. 수식키만 눌렀다 떼면 그 수식키 단독 바인딩(예: Alt 홀드)이 됩니다. */
    private fun capture(action: Action, replace: Boolean) {
        var pendingModifier: Int? = null
        val dialog = kr.dfluid.paint.ui.Ui.dialog(this)
            .setTitle(action.label)
            .setMessage("새 키 조합을 누르세요.\n(Esc = 취소)")
            .setNegativeButton("취소", null)
            .create()
        dialog.setOnKeyListener { d, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                if (event.action == KeyEvent.ACTION_UP) d.dismiss()
                return@setOnKeyListener true
            }
            val isMod = KeyBinding.isModifier(keyCode)
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (isMod) {
                        pendingModifier = KeyBinding.normalizeKey(keyCode)
                    } else {
                        pendingModifier = null
                        KeyBinding.fromEvent(event)?.let {
                            d.dismiss()
                            apply(action, it, replace)
                        }
                    }
                }
                KeyEvent.ACTION_UP -> {
                    if (isMod && pendingModifier == KeyBinding.normalizeKey(keyCode)) {
                        val code = pendingModifier!!
                        pendingModifier = null
                        if (code == KeyEvent.KEYCODE_CTRL_LEFT || code == KeyEvent.KEYCODE_SHIFT_LEFT || code == KeyEvent.KEYCODE_ALT_LEFT) {
                            d.dismiss()
                            apply(action, KeyBinding(code), replace)
                        }
                    }
                }
            }
            true
        }
        dialog.show()
    }

    private fun apply(action: Action, binding: KeyBinding, replace: Boolean) {
        val owner = store.find(binding)
        val commit = {
            if (owner != null && owner != action) store.unbindEverywhere(binding)
            val list = if (replace) listOf(binding) else store.get(action) + binding
            store.set(action, list)
            adapter.notifyDataSetChanged()
            Toast.makeText(this, "${action.label}: ${binding.label()}", Toast.LENGTH_SHORT).show()
        }
        if (owner != null && owner != action) {
            kr.dfluid.paint.ui.Ui.dialog(this)
                .setMessage("${binding.label()}은(는) 이미 '${owner.label}'에 지정되어 있습니다.\n'${action.label}'(으)로 옮길까요?")
                .setPositiveButton("옮기기") { _, _ -> commit() }
                .setNegativeButton("취소", null)
                .show()
        } else {
            commit()
        }
    }

    private inner class RowAdapter(private val rows: List<Row>) : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int): Row = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun isEnabled(position: Int) = rows[position] is Row.Item

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val density = resources.displayMetrics.density
            val pad = (16 * density).toInt()
            return when (val row = rows[position]) {
                is Row.Header -> {
                    val tv = (convertView as? TextView) ?: TextView(this@ShortcutSettingsActivity).apply {
                        setPadding(pad, pad, pad, pad / 3)
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Color.rgb(0x3D, 0x8B, 0xFF))
                        textSize = 13f
                    }
                    tv.text = row.title
                    tv
                }
                is Row.Item -> {
                    val layout = (convertView as? LinearLayout) ?: LinearLayout(this@ShortcutSettingsActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(pad, pad * 3 / 4, pad, pad * 3 / 4)
                        addView(TextView(context).apply { textSize = 15f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(TextView(context).apply {
                            textSize = 14f
                            setTypeface(Typeface.MONOSPACE)
                        })
                    }
                    (layout.getChildAt(0) as TextView).text = row.action.label
                    val keys = store.get(row.action)
                    (layout.getChildAt(1) as TextView).apply {
                        text = if (keys.isEmpty()) "없음" else keys.joinToString("  ·  ") { it.label() }
                        alpha = if (keys.isEmpty()) 0.5f else 1f
                    }
                    layout
                }
            }
        }
    }

    companion object {
        private const val MENU_RESET = 1
    }
}
