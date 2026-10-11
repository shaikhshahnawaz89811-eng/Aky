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
import com.codeassist.ai.ai.EvalRunner
import com.codeassist.ai.ai.MemoryStore
import com.codeassist.ai.ai.Persona
import com.codeassist.ai.ai.Proactive
import com.codeassist.ai.ai.ProactiveRunner
import com.codeassist.ai.ai.TaskEngine
import com.codeassist.ai.ai.TaskGraph
import com.codeassist.ai.ai.TaskQueue
import com.codeassist.ai.ai.Trace
import com.codeassist.ai.data.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private lateinit var container: LinearLayout

    // Phase 3C: the eval run (30 cases) lives in this scope and ends with the screen
    private val evalScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var evalJob: Job? = null

    override fun onDestroyView() {
        evalJob?.cancel()
        super.onDestroyView()
    }

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
            valueRow(R.drawable.ic_database, "Memory (yaad)", memorySummary()) { _, _ ->
                (activity as? com.codeassist.ai.MainActivity)?.openMemory()
            }
            noteRow(
                "Gemma 4 E2B phone par hi chalta hai. Internet sirf tab use hota hai jab aap Gemini " +
                    "chunte ho ya Gemma 4 model download karte ho."
            )
        }

        section("Assistant style")
        card {
            valueRow(R.drawable.ic_palette, "Tone", Store.personaTone) { a, tv ->
                popup(a, Persona.TONES, Store.personaTone) { Store.personaTone = it; tv.text = it }
            }
            valueRow(R.drawable.ic_sparkle, "Humor", Store.personaHumor) { a, tv ->
                popup(a, Persona.HUMORS, Store.personaHumor) { Store.personaHumor = it; tv.text = it }
            }
            valueRow(R.drawable.ic_chat, "Aap ya tum", Store.personaAddress) { a, tv ->
                popup(a, Persona.ADDRESS, Store.personaAddress) { Store.personaAddress = it; tv.text = it }
            }
            valueRow(R.drawable.ic_mic, "Bolne ka lehja", Store.personaGender) { a, tv ->
                popup(a, Persona.GENDERS, Store.personaGender) { Store.personaGender = it; tv.text = it }
            }
            valueRow(R.drawable.ic_bell, "Apne aap suggestions", Store.proactiveLevel) { a, tv ->
                popup(a, Proactive.LEVELS, Store.proactiveLevel) { Store.proactiveLevel = it; tv.text = it }
            }
            valueRow(R.drawable.ic_history, "Band ki hui suggestions", mutedSummary()) { _, _ -> showMutedKinds() }
            noteRow(
                "Bolne ka lehja: Female = \"karti hoon\", Male = \"karta hoon\", Auto = jaisa abhi hai. Ye app ke apne likhe " +
                    "jawab aur status par lagta hai; model ko bhi bata diya jata hai. Serious baat (emergency, dard, accident) par " +
                    "mazaak hamesha band rehta hai. Suggestions: Low = din mein 1, Normal = din mein 2, kabhi isse zyada nahi, " +
                    "raat 10 se subah 8 tak nahi. Sirf app khula ho tab Home par ek card dikhta hai: battery 20% se kam, ya " +
                    "assistant ki jalayi torch 10 minute se zyada on."
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
            valueRow(R.drawable.ic_chart, "Debug: eval (30 Hinglish cases)", evalSummary()) { _, _ ->
                confirmEval()
            }
            actionRow(R.drawable.ic_delete, "Plans clear") {
                confirmClearPlans()
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

    private fun mutedSummary(): String {
        val muted = ProactiveRunner.mutedKinds(requireContext())
        return if (muted.isEmpty()) "koi nahi" else muted.joinToString(", ") { Proactive.kindLabel(it) }
    }

    /** Lets the user switch a muted kind of suggestion on again. */
    private fun showMutedKinds() {
        val ctx = requireContext()
        val kinds = Proactive.KINDS
        val muted = ProactiveRunner.mutedKinds(ctx)
        val checked = BooleanArray(kinds.size) { kinds[it] in muted }
        AlertDialog.Builder(ctx)
            .setTitle("Ye suggestions band hain")
            .setMultiChoiceItems(kinds.map { Proactive.kindLabel(it) }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Save") { _, _ ->
                ProactiveRunner.setMuted(ctx, kinds.filterIndexed { i, _ -> checked[i] })
                if (isAdded) build()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun memorySummary(): String {
        MemoryStore.init(requireContext())
        val n = MemoryStore.all().size
        return (if (Store.memoryPrivate) "Private · " else "") + n + " yaad"
    }

    private fun evalSummary(): String {
        ConvKpi.init(requireContext())
        val total = ConvKpi.get("eval_total")
        return if (total > 0) ConvKpi.get("eval_ok").toString() + "/" + total else "chala nahi"
    }

    private fun confirmEval() {
        val brain = if (Store.brainProvider == "gemini") "Gemini" else "Gemma 4 (phone par)"
        AlertDialog.Builder(requireContext())
            .setTitle("Eval chalao")
            .setMessage(
                "30 Hinglish cases brain ($brain) ko diye jayenge aur uska plan jaancha jayega. Koi tool nahi chalega: " +
                    "alarm set nahi hoga, call nahi lagegi. Gemini mein ek minute ke aas-paas, Gemma mein kai minute lagte hain. Chalao?"
            )
            .setPositiveButton("Chalao") { _, _ -> runEval() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runEval() {
        val app = requireContext().applicationContext
        var stopRequested = false
        val dlg = AlertDialog.Builder(requireContext())
            .setTitle("Eval chal raha hai")
            .setMessage("Shuru ho raha hai...")
            .setCancelable(false)
            .setNegativeButton("Roko") { _, _ -> stopRequested = true }
            .create()
        dlg.show()
        evalJob?.cancel()
        evalJob = evalScope.launch {
            try {
                val report = EvalRunner.run(
                    app,
                    { done, total, id -> dlg.setMessage("Case " + (done + 1) + "/" + total + " (" + id + ")\n\nKoi tool nahi chalta, sirf parser jaanch.") },
                    { stopRequested }
                )
                dlg.dismiss()
                showEvalResult(report.text())
            } catch (e: CancellationException) {
                dlg.dismiss()
                throw e
            } catch (e: Throwable) {
                dlg.dismiss()
                Toast.makeText(app, e.message ?: "Eval nahi chal paya", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showEvalResult(text: String) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle("Eval natija")
            .setMessage(text)
            .setPositiveButton("Copy") { _, _ ->
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("CodeAssist eval", text))
                Toast.makeText(ctx, "Copy ho gaya", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close") { _, _ -> if (isAdded) build() }
            .show()
    }

    private fun confirmClearPlans() {
        AlertDialog.Builder(requireContext())
            .setTitle("Plans clear")
            .setMessage(
                "Phone mein rakhe saare plans hata dun? Jo plan Haan / Nahi ka wait kar raha hai wo bhi jayega: " +
                    "uska Haan dabane par \"plan mila nahi\" aayega aur kuch nahi chalega. Chat ke messages nahi hatenge."
            )
            .setPositiveButton("Hatao") { _, _ ->
                val n = TaskEngine.clearPlans(requireContext().applicationContext)
                Toast.makeText(requireContext(), "$n plans hata diye", Toast.LENGTH_SHORT).show()
                build()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
