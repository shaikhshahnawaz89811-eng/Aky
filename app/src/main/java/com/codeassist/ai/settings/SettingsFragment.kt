package com.codeassist.ai.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.codeassist.ai.ai.ActivityLog
import com.codeassist.ai.ai.BrainStatus
import com.codeassist.ai.ai.ConvKpi
import com.codeassist.ai.ai.TaskEngine
import com.codeassist.ai.ai.TaskGraph
import com.codeassist.ai.ai.TaskQueue
import com.codeassist.ai.ai.Trace
import com.codeassist.ai.data.Store

class SettingsFragment : Fragment() {

    private lateinit var container: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        Store.init(requireContext())
        ActivityLog.init(requireContext())
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
                "Gemma 4 E2B phone par hi chalta hai. Internet sirf tab use hota hai jab aap Gemini " +
                    "chunte ho ya Gemma 4 model download karte ho."
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

        section("Activity and debug")
        card {
            valueRow(R.drawable.ic_history, "Activity log", ActivityLog.all().size.toString() + " actions") { _, _ ->
                showActivityLog()
            }
            valueRow(R.drawable.ic_chart, "Debug: last turns", Trace.count().toString() + " turns") { _, _ ->
                showTrace()
            }
            valueRow(R.drawable.ic_chart, "Debug: conversation KPIs", "") { _, _ ->
                showKpi()
            }
            valueRow(R.drawable.ic_chart, "Debug: task engine", taskPlanCount()) { _, _ ->
                showTaskEngine()
            }
            noteRow(
                "Activity log mein har phone action (torch, alarm, timer, call, app) dikhta hai. " +
                    "Torch, alarm aur timer wapas kiye ja sakte hain. Debug mein sirf timing aur status hota hai, message ka text nahi."
            )
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

    private fun showActivityLog() {
        val ctx = requireContext()
        val entries = ActivityLog.all().take(50)
        if (entries.isEmpty()) {
            Toast.makeText(ctx, "Abhi koi phone action nahi hua.", Toast.LENGTH_SHORT).show()
            return
        }
        val fmt = java.text.SimpleDateFormat("d MMM, hh:mm a", java.util.Locale.ENGLISH)
        val labels = entries.map { e ->
            fmt.format(java.util.Date(e.time)) + " · " + e.tool + " · " + e.tier +
                (if (e.undone) " · wapas hua" else "") + "\n" + e.summary
        }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle("Activity log")
            .setItems(labels) { _, i ->
                val e = entries[i]
                if (e.undo != null && !e.undone) {
                    AlertDialog.Builder(ctx)
                        .setTitle("Undo?")
                        .setMessage(e.summary)
                        .setPositiveButton("Undo") { _, _ ->
                            Toast.makeText(ctx, ActivityLog.undo(ctx, e.id), Toast.LENGTH_LONG).show()
                            build()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    Toast.makeText(
                        ctx,
                        if (e.undone) "Ye pehle hi wapas ho chuka hai." else "Is action ka undo nahi hota.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNeutralButton("Clear log") { _, _ ->
                ActivityLog.clear()
                build()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun taskPlanCount(): String {
        TaskQueue.init(requireContext())
        return TaskQueue.count().toString() + " plans"
    }

    /** Phase 3: the stored plans with the state of every node, and the kill-process resume test. */
    private fun showTaskEngine() {
        val ctx = requireContext()
        TaskQueue.init(ctx)
        val plans = TaskQueue.all().take(10)
        val text = if (plans.isEmpty()) {
            "Abhi koi plan nahi. Ek saath kai kaam bolo, jaise: \"kal subah 8 baje alarm laga do aur battery batao\"."
        } else {
            plans.joinToString("\n\n") { TaskGraph.describe(it) }
        }
        AlertDialog.Builder(ctx)
            .setTitle("Task engine")
            .setMessage(text)
            .setPositiveButton("Copy") { _, _ ->
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("CodeAssist task engine", text))
                Toast.makeText(ctx, "Copy ho gaya", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Kill-test") { _, _ -> confirmKillTest() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun confirmKillTest() {
        AlertDialog.Builder(requireContext())
            .setTitle("Kill-process resume test")
            .setMessage(
                "Ye ek aisa plan likhega jo beech mein ruka tha (5 kaam, ek timer jo \"pata nahi\" banega), " +
                    "phir app ko band kar dega. App ko 2 minute ke andar dobara kholo: naye chat mein dikhna chahiye " +
                    "ki kya dobara chala, kya chhoda gaya. Chalao?"
            )
            .setPositiveButton("Chalao") { _, _ -> runKillTest() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runKillTest() {
        val app = requireContext().applicationContext
        Store.init(app)
        val chat = Store.newChat("Task engine test")
        Store.saveMessages(
            chat.id,
            listOf(com.codeassist.ai.data.Message(role = com.codeassist.ai.data.Role.USER, text = "Task engine kill-test"))
        )
        Store.activeChatId = chat.id
        TaskEngine.debugSeed(app, chat.id)
        Toast.makeText(app, "Plan likh diya. App band ho rahi hai, 2 minute ke andar dobara kholo.", Toast.LENGTH_LONG).show()
        // a short delay lets the app's own preference writes (chat list, active chat) reach the disk before the kill
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { android.os.Process.killProcess(android.os.Process.myPid()) }, 1800L
        )
    }

    private fun showKpi() {
        val ctx = requireContext()
        ConvKpi.init(ctx)
        val text = ConvKpi.report()
        AlertDialog.Builder(ctx)
            .setTitle("Conversation KPIs")
            .setMessage(text)
            .setPositiveButton("Copy") { _, _ ->
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("CodeAssist KPI", text))
                Toast.makeText(ctx, "Copy ho gaya", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Reset") { _, _ ->
                ConvKpi.reset()
                Toast.makeText(ctx, "KPI counters reset", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showTrace() {
        val ctx = requireContext()
        val text = Trace.dump()
        AlertDialog.Builder(ctx)
            .setTitle("Last turns (timings)")
            .setMessage(text)
            .setPositiveButton("Copy") { _, _ ->
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("CodeAssist trace", text))
                Toast.makeText(ctx, "Copy ho gaya", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
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
