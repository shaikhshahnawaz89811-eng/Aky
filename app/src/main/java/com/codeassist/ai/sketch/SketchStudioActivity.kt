package com.codeassist.ai.sketch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.codeassist.ai.R
import com.codeassist.ai.ai.Sketch
import com.codeassist.ai.ai.SketchDesigns
import com.codeassist.ai.ai.SketchGen
import com.codeassist.ai.ai.SketchHistory
import com.codeassist.ai.data.Store
import com.codeassist.ai.ui.SketchFiles
import com.codeassist.ai.ui.SketchView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Sketch Studio stage 2: the studio screen. Two panes in one activity, both built in code (no layout files, so no resource ids to
 * get wrong without a compiler):
 *  - LIST: every saved design with a small preview, "+ Naya design", a menu per design (rename / duplicate / delete / share);
 *  - EDITOR: the drawing on an interactive [SketchView] (pinch-zoom, one-finger pan, double-tap resets), undo / redo
 *    ([SketchHistory]), a prompt field with two buttons: "Naya" (new drawing from the text) and "Badlo" (change the CURRENT
 *    drawing, the stage 1 edit prompt), Stop while the model works, and the SVG / PNG file export ([SketchFiles]).
 * Every accepted drawing is saved at once ([Store.saveSketchDesigns]); undo / redo are saved too. The model call is [SketchGen]
 * (offline Gemma only). Pure logic (list operations, history, zoom maths) is in [SketchDesigns] and covered by JVM tests; this
 * class itself has never been compiled or run (BUILD_NOTES, Sketch Studio stage 2).
 */
class SketchStudioActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DESIGN_ID = "design_id"

        /**
         * Keeps the drawing of a chat message as a design and returns its id; the same drawing is not saved twice. Null when the
         * drawing cannot be read or the list is full ([SketchDesigns.MAX_DESIGNS]).
         */
        fun saveFromChat(json: String?): String? {
            val d = Sketch.fromJson(json) ?: return null
            val clean = Sketch.toJson(d)
            val list = Store.sketchDesigns()
            list.firstOrNull { it.json == clean }?.let { return it.id }
            val design = SketchDesigns.newDesign(d.title, clean, System.currentTimeMillis())
            val next = SketchDesigns.add(list, design) ?: return null
            Store.saveSketchDesigns(next)
            return design.id
        }

        fun intent(ctx: Context, designId: String? = null): Intent =
            Intent(ctx, SketchStudioActivity::class.java).apply { if (designId != null) putExtra(EXTRA_DESIGN_ID, designId) }
    }

    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    @Volatile
    private var cancelled = false

    /** Counts started / cancelled runs: a run that finds a newer number when it ends was cancelled and must not touch the screen. */
    private var runId = 0

    private var openId: String? = null
    private var history: SketchHistory? = null

    private lateinit var listPane: LinearLayout
    private lateinit var listBody: LinearLayout
    private lateinit var listCount: TextView
    private lateinit var editorPane: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var canvas: SketchView
    private lateinit var status: TextView
    private lateinit var zoomText: TextView
    private lateinit var prompt: EditText
    private lateinit var undoBtn: TextView
    private lateinit var redoBtn: TextView
    private lateinit var newBtn: TextView
    private lateinit var editBtn: TextView
    private lateinit var stopBtn: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        val root = FrameLayout(this).apply {
            setBackgroundColor(color(R.color.bg))
            fitsSystemWindows = true
        }
        buildList()
        buildEditor()
        root.addView(listPane, FrameLayout.LayoutParams(match, match))
        root.addView(editorPane, FrameLayout.LayoutParams(match, match))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (editorPane.visibility == View.VISIBLE) {
                    cancelRun()
                    showList()
                } else {
                    finish()
                }
            }
        })

        val wanted = intent?.getStringExtra(EXTRA_DESIGN_ID)
        if (wanted != null && SketchDesigns.find(Store.sketchDesigns(), wanted) != null) openDesign(wanted) else showList()
    }

    override fun onDestroy() {
        cancelled = true
        scope.cancel()
        super.onDestroy()
    }

    // ---------- small helpers ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun text(value: String, sp: Float, colorRes: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = sp
        setTextColor(color(colorRes))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun button(value: String, background: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        text = value
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(color(R.color.text1))
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setBackgroundResource(background)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun setEnabledLook(v: View, on: Boolean) {
        v.isEnabled = on
        v.alpha = if (on) 1f else 0.4f
    }

    // ---------- list pane ----------

    private fun buildList() {
        listPane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(12), dp(8))
        }
        top.addView(button("\u2190", R.drawable.circle_btn) { finish() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text("Sketch Studio", 18f, R.color.text1, true))
        listCount = text("", 12f, R.color.text3)
        titles.addView(listCount)
        top.addView(titles, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = dp(12) })
        top.addView(button("+ Naya design", R.drawable.btn_primary) { newDesign() })
        listPane.addView(top, LinearLayout.LayoutParams(match, wrap))

        val scroll = ScrollView(this)
        listBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(24))
        }
        scroll.addView(listBody, FrameLayout.LayoutParams(match, wrap))
        listPane.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))
    }

    private fun showList() {
        openId = null
        history = null
        editorPane.visibility = View.GONE
        listPane.visibility = View.VISIBLE
        refreshList()
    }

    private fun refreshList() {
        listBody.removeAllViews()
        val items = SketchDesigns.sorted(Store.sketchDesigns())
        listCount.text = items.size.toString() + " / " + SketchDesigns.MAX_DESIGNS + " design"
        if (items.isEmpty()) {
            val note = text(
                "Abhi koi design nahi. \"+ Naya design\" dabao, ya chat me banaye chitra ke menu se \"Open in Studio\" chuno.",
                14f, R.color.text2
            )
            note.setPadding(dp(8), dp(24), dp(8), dp(8))
            listBody.addView(note)
            return
        }
        for (d in items) listBody.addView(designRow(d), LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(10) })
    }

    private fun designRow(d: SketchDesigns.Design): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(6), dp(10))
            setBackgroundResource(R.drawable.glass_card_16)
            isClickable = true
            isFocusable = true
            setOnClickListener { openDesign(d.id) }
        }
        val preview = SketchView(this).apply {
            emptyHint = "Khali"
            setDrawing(Sketch.fromJson(d.json))
        }
        row.addView(preview, LinearLayout.LayoutParams(dp(72), dp(72)))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(text(d.title, 15f, R.color.text1, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val shapes = Sketch.fromJson(d.json)?.shapes?.size
        val shapesText = if (d.json.isBlank()) "khali" else if (shapes == null) "padha nahi ja saka" else "$shapes shapes"
        info.addView(text(shapesText + " · " + SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(d.updated)), 12f, R.color.text3))
        row.addView(info, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = dp(12) })
        val more = button("\u22EE", R.drawable.circle_btn) { }
        more.setOnClickListener { showMenu(more, d.id) }
        row.addView(more, LinearLayout.LayoutParams(dp(40), dp(40)))
        return row
    }

    private fun newDesign() {
        val d = SketchDesigns.newDesign(null, "", System.currentTimeMillis())
        val next = SketchDesigns.add(Store.sketchDesigns(), d)
        if (next == null) {
            toast("" + SketchDesigns.MAX_DESIGNS + " design ho gaye. Pehle kuch delete karo.")
            return
        }
        Store.saveSketchDesigns(next)
        openDesign(d.id)
    }

    // ---------- editor pane ----------

    private fun buildEditor() {
        editorPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        top.addView(button("\u2190", R.drawable.circle_btn) {
            cancelRun()
            showList()
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        titleView = text("", 17f, R.color.text1, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { openId?.let { renameDialog(it) } }
        }
        top.addView(titleView, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = dp(12) })
        val more = button("\u22EE", R.drawable.circle_btn) { }
        more.setOnClickListener { openId?.let { id -> showMenu(more, id) } }
        top.addView(more, LinearLayout.LayoutParams(dp(44), dp(44)))
        editorPane.addView(top, LinearLayout.LayoutParams(match, wrap))

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(0), dp(12), dp(24))
        }
        scroll.addView(body, FrameLayout.LayoutParams(match, wrap))
        editorPane.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        undoBtn = button("\u21B6 Undo", R.drawable.glass_chip) { undo() }
        redoBtn = button("Redo \u21B7", R.drawable.glass_chip) { redo() }
        tools.addView(undoBtn)
        tools.addView(redoBtn, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(8) })
        zoomText = text("", 12f, R.color.text3).apply { gravity = Gravity.END }
        tools.addView(zoomText, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = dp(8) })
        body.addView(tools, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(8) })

        canvas = SketchView(this).apply {
            maxSideDp = 640
            interactive = true
            emptyHint = "Khali canvas. Neeche likho aur Naya dabao."
            onTransformChanged = { updateZoomText() }
        }
        body.addView(canvas, LinearLayout.LayoutParams(match, wrap))

        status = text("", 12f, R.color.text2)
        body.addView(status, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) })

        prompt = EditText(this).apply {
            hint = "Jaise: ek ghar banao  /  background dark kar do"
            setHintTextColor(color(R.color.text3))
            setTextColor(color(R.color.text1))
            textSize = 14f
            setBackgroundResource(R.drawable.field_bg)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minLines = 2
            maxLines = 5
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(300))
        }
        body.addView(prompt, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(10) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        newBtn = button("Naya", R.drawable.btn_primary) { startRun(false) }
        editBtn = button("Badlo", R.drawable.glass_chip) { startRun(true) }
        stopBtn = button("Stop", R.drawable.btn_danger) { cancelRun() }.apply { visibility = View.GONE }
        actions.addView(newBtn, LinearLayout.LayoutParams(0, wrap, 1f))
        actions.addView(editBtn, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = dp(8) })
        actions.addView(stopBtn, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = dp(8) })
        body.addView(actions, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(10) })

        body.addView(
            text(
                "Naya = text se naya chitra (purana undo se wapas aa sakta hai). Badlo = abhi wale chitra me badlav. " +
                    "Do ungli se zoom, ek ungli se khiskao, do baar tap = reset. Sirf phone ka Gemma chalta hai (offline).",
                12f, R.color.text3
            ),
            LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(12) }
        )
    }

    private fun openDesign(id: String) {
        val d = SketchDesigns.find(Store.sketchDesigns(), id)
        if (d == null) {
            toast("Ye design mila nahi.")
            showList()
            return
        }
        cancelRun()
        openId = id
        history = SketchHistory(d.json)
        titleView.text = d.title
        val drawing = if (d.json.isBlank()) null else Sketch.fromJson(d.json)
        canvas.setDrawing(drawing)
        canvas.resetZoom()
        status.text = if (d.json.isNotBlank() && drawing == null) "Ye design padha nahi ja saka." else ""
        prompt.setText("")
        setBusy(false)
        listPane.visibility = View.GONE
        editorPane.visibility = View.VISIBLE
        updateButtons()
    }

    private fun updateZoomText() {
        zoomText.text = if (canvas.isZoomed) "Zoom " + String.format(Locale.US, "%.1f", canvas.zoomLevel) + "x" else ""
    }

    private fun updateButtons() {
        val h = history
        setEnabledLook(undoBtn, h != null && h.canUndo && job?.isActive != true)
        setEnabledLook(redoBtn, h != null && h.canRedo && job?.isActive != true)
    }

    private fun setBusy(busy: Boolean) {
        setEnabledLook(newBtn, !busy)
        setEnabledLook(editBtn, !busy)
        prompt.isEnabled = !busy
        stopBtn.visibility = if (busy) View.VISIBLE else View.GONE
    }

    // ---------- model calls ----------

    private fun startRun(edit: Boolean) {
        if (openId == null || job?.isActive == true) return
        val request = prompt.text.toString().trim()
        if (request.isEmpty()) {
            toast("Pehle likho ki kya banana ya badalna hai.")
            return
        }
        val current = history?.current.orEmpty()
        // "Badlo" on an empty canvas is just a new drawing
        val previous = if (edit && current.isNotBlank()) current else null
        cancelled = false
        val mine = ++runId
        status.text = "Shuru ho raha hai..."
        setBusy(true)
        updateButtons()
        job = scope.launch {
            val result: SketchGen.Result? = try {
                SketchGen.run(request, previous, { cancelled }) { line -> status.text = line }
            } catch (e: CancellationException) {
                null
            }
            if (mine != runId) return@launch
            when (result) {
                null -> status.text = "Roka gaya. Chitra waisa hi hai."
                is SketchGen.Result.Fail -> status.text = result.message
                is SketchGen.Result.Ok -> {
                    history?.push(result.json)
                    canvas.setDrawing(result.drawing)
                    persist(result.json, result.drawing.title)
                    status.text = result.caption
                    if (previous != null) prompt.setText("")
                }
            }
            setBusy(false)
            updateButtons()
        }
    }

    private fun cancelRun() {
        val wasRunning = job?.isActive == true
        cancelled = true
        runId++
        job?.cancel()
        job = null
        if (::newBtn.isInitialized) {
            setBusy(false)
            updateButtons()
            if (wasRunning) status.text = "Roka gaya. Chitra waisa hi hai."
        }
    }

    // ---------- keeping, undo / redo ----------

    /** Saves [json] as the current state of the open design; an untitled design takes the drawing's title. */
    private fun persist(json: String, autoTitle: String? = null) {
        val id = openId ?: return
        val now = System.currentTimeMillis()
        var list: List<SketchDesigns.Design> = SketchDesigns.update(Store.sketchDesigns(), id, json, now)
        val cur = SketchDesigns.find(list, id)
        if (autoTitle != null && cur != null && cur.title == SketchDesigns.UNTITLED) {
            list = SketchDesigns.rename(list, id, autoTitle, now)
            titleView.text = SketchDesigns.cleanTitle(autoTitle)
        }
        Store.saveSketchDesigns(list)
    }

    private fun undo() {
        val json = history?.undo() ?: return
        showState(json)
    }

    private fun redo() {
        val json = history?.redo() ?: return
        showState(json)
    }

    private fun showState(json: String) {
        canvas.setDrawing(if (json.isBlank()) null else Sketch.fromJson(json))
        persist(json)
        status.text = ""
        updateButtons()
    }

    // ---------- menu: rename / duplicate / delete / share ----------

    private fun drawingOf(id: String): Sketch.Drawing? = Sketch.fromJson(SketchDesigns.find(Store.sketchDesigns(), id)?.json)

    private fun showMenu(anchor: View, id: String) {
        val popup = PopupMenu(this, anchor)
        val items = listOf("Naam badlo", "Duplicate", "Share SVG file", "Share PNG", "Copy SVG text", "Delete")
        for (label in items) popup.menu.add(label)
        popup.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Naam badlo" -> renameDialog(id)
                "Duplicate" -> duplicate(id)
                "Share SVG file" -> drawingOf(id)?.let { SketchFiles.shareSvg(this, it) } ?: toast("Khali canvas hai, pehle kuch banao.")
                "Share PNG" -> drawingOf(id)?.let { SketchFiles.sharePng(this, it) } ?: toast("Khali canvas hai, pehle kuch banao.")
                "Copy SVG text" -> drawingOf(id)?.let { copySvg(it) } ?: toast("Khali canvas hai, pehle kuch banao.")
                "Delete" -> confirmDelete(id)
            }
            true
        }
        popup.show()
    }

    private fun copySvg(d: Sketch.Drawing) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("SVG", Sketch.toSvg(d)))
        toast("SVG copy ho gaya")
    }

    private fun renameDialog(id: String) {
        val d = SketchDesigns.find(Store.sketchDesigns(), id) ?: return
        val input = EditText(this).apply {
            setText(d.title)
            setSelection(text.length)
            setSingleLine(true)
            setTextColor(color(R.color.text1))
            filters = arrayOf(InputFilter.LengthFilter(SketchDesigns.MAX_TITLE))
        }
        AlertDialog.Builder(this)
            .setTitle("Naam badlo")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                Store.saveSketchDesigns(SketchDesigns.rename(Store.sketchDesigns(), id, input.text.toString(), System.currentTimeMillis()))
                if (openId == id) titleView.text = SketchDesigns.find(Store.sketchDesigns(), id)?.title ?: ""
                if (listPane.visibility == View.VISIBLE) refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun duplicate(id: String) {
        val next = SketchDesigns.duplicate(Store.sketchDesigns(), id, System.currentTimeMillis())
        if (next == null) {
            toast("Duplicate nahi ho paya (limit " + SketchDesigns.MAX_DESIGNS + " design).")
            return
        }
        Store.saveSketchDesigns(next)
        toast("Copy ban gayi")
        if (listPane.visibility == View.VISIBLE) refreshList()
    }

    private fun confirmDelete(id: String) {
        val d = SketchDesigns.find(Store.sketchDesigns(), id) ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete design")
            .setMessage("\"" + d.title + "\" hamesha ke liye hata du?")
            .setPositiveButton("Delete") { _, _ ->
                if (openId == id) cancelRun()
                Store.saveSketchDesigns(SketchDesigns.delete(Store.sketchDesigns(), id))
                showList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
