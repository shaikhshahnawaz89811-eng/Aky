package com.codeassist.ai.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import com.codeassist.ai.R
import com.codeassist.ai.ai.BrainStatus
import com.codeassist.ai.data.Store

class SettingsFragment : Fragment() {

    private lateinit var container: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        Store.init(requireContext())
        container = view.findViewById(R.id.settingsContainer)
        view.findViewById<View>(R.id.btnMenu).setOnClickListener {
            (activity as? com.codeassist.ai.MainActivity)?.openDrawer()
        }
        build()
    }

    private fun build() {
        container.removeAllViews()

        section("Appearance")
        card {
            infoRow(R.drawable.ic_moon, "Theme", "Dark")
            switchRow(R.drawable.ic_sparkle, "Glass Effect", Store.glassEffect) {
                Store.glassEffect = it
                build()
                Toast.makeText(requireContext(), if (it) "Glass cards on" else "Flat cards on", Toast.LENGTH_SHORT).show()
            }
            valueRow(R.drawable.ic_text, "Text Size", Store.textSize) { anchor, tv ->
                popup(anchor, listOf("Small", "Medium", "Large"), Store.textSize) {
                    Store.textSize = it; tv.text = it
                }
            }
        }

        section("AI service")
        card {
            valueRow(R.drawable.ic_sparkle, "Voice and AI", BrainStatus.chipLabel()) { _, _ ->
                (activity as? com.codeassist.ai.MainActivity)?.openVoiceAi()
            }
            noteRow(
                "Phi-4 mini phone par hi chalta hai. Internet sirf tab use hota hai jab aap Gemini " +
                    "chunte ho ya Phi-4 mini download karte ho."
            )
        }

        section("On-device data")
        card {
            noteRow("Chats, messages, and project details are stored locally. Export or share only when you choose an action.")
            actionRow(R.drawable.ic_delete, "Clear Chat History") {
                AlertDialog.Builder(requireContext())
                    .setTitle("Clear chat history")
                    .setMessage("This removes all chats and messages from this device.")
                    .setPositiveButton("Clear") { _, _ ->
                        Store.clearHistory()
                        (activity as? com.codeassist.ai.MainActivity)?.onHistoryCleared()
                        Toast.makeText(requireContext(), "History cleared", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        section("App Behavior")
        card {
            switchRow(R.drawable.ic_history, "Open Last Project", Store.openLastProject) {
                Store.openLastProject = it
            }
        }

        val version = TextView(requireContext()).apply {
            text = "CodeAssistAI v1.1 · Android client"
            setTextColor(0xFF5D6B7C.toInt())
            textSize = 11.5f
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(40), 0, dp(8))
        }
        container.addView(version)
    }

    private fun section(title: String) {
        val tv = TextView(requireContext()).apply {
            text = title
            setTextColor(0xFF5D6B7C.toInt())
            textSize = 12.5f
            letterSpacing = 0.05f
            setPadding(dp(4), dp(34), 0, dp(16))
        }
        container.addView(tv)
    }

    private var activeCard: LinearLayout? = null

    private fun card(content: () -> Unit) {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(if (Store.glassEffect) R.drawable.glass_card else R.drawable.glass_card_solid)
        }
        container.addView(card)
        activeCard = card
        content()
        activeCard = null
    }

    private fun inflateRow(): View {
        return LayoutInflater.from(requireContext()).inflate(R.layout.item_setting_row, activeCard, false)
    }

    private fun addRow(v: View) {
        val card = activeCard ?: return
        if (card.childCount > 0) {
            val d = View(requireContext())
            d.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                marginStart = dp(58)
            }
            d.setBackgroundResource(R.drawable.divider)
            card.addView(d)
        }
        card.addView(v)
    }

    private fun switchRow(icon: Int, title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = inflateRow()
        row.findViewById<ImageView>(R.id.rowIcon).setImageResource(icon)
        row.findViewById<TextView>(R.id.rowTitle).text = title
        row.findViewById<TextView>(R.id.rowValue).visibility = View.GONE
        row.findViewById<ImageView>(R.id.rowChevron).visibility = View.GONE
        val sw = row.findViewById<SwitchCompat>(R.id.rowSwitch)
        sw.visibility = View.VISIBLE
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, b -> onChange(b) }
        row.setOnClickListener { sw.toggle() }
        addRow(row)
    }

    private fun valueRow(icon: Int, title: String, value: String, onClick: (View, TextView) -> Unit) {
        val row = inflateRow()
        row.findViewById<ImageView>(R.id.rowIcon).setImageResource(icon)
        row.findViewById<TextView>(R.id.rowTitle).text = title
        val tv = row.findViewById<TextView>(R.id.rowValue)
        tv.text = value
        row.setOnClickListener { onClick(it, tv) }
        addRow(row)
    }

    private fun actionRow(icon: Int, title: String, onClick: () -> Unit) {
        val row = inflateRow()
        row.findViewById<ImageView>(R.id.rowIcon).apply {
            setImageResource(icon)
            setColorFilter(0xFFFF7B72.toInt())
        }
        row.findViewById<TextView>(R.id.rowTitle).text = title
        row.findViewById<TextView>(R.id.rowValue).visibility = View.GONE
        row.setOnClickListener { onClick() }
        addRow(row)
    }

    private fun infoRow(icon: Int, title: String, value: String) {
        val row = inflateRow()
        row.findViewById<ImageView>(R.id.rowIcon).setImageResource(icon)
        row.findViewById<TextView>(R.id.rowTitle).text = title
        row.findViewById<TextView>(R.id.rowValue).apply {
            text = value
            setTextColor(0xFF93A1B3.toInt())
        }
        row.findViewById<ImageView>(R.id.rowChevron).visibility = View.GONE
        row.findViewById<SwitchCompat>(R.id.rowSwitch).visibility = View.GONE
        row.isClickable = false
        addRow(row)
    }

    private fun noteRow(text: String) {
        val note = TextView(requireContext()).apply {
            this.text = text
            setTextColor(0xFF93A1B3.toInt())
            textSize = 13f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        activeCard?.addView(note)
    }

    private fun popup(anchor: View, options: List<String>, current: String, onPick: (String) -> Unit) {
        val menu = PopupMenu(requireContext(), anchor)
        options.forEachIndexed { i, o ->
            val label = if (o == current) "✓  $o" else "    $o"
            menu.menu.add(0, i, i, label)
        }
        menu.setOnMenuItemClickListener {
            onPick(options[it.itemId]); true
        }
        menu.show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
