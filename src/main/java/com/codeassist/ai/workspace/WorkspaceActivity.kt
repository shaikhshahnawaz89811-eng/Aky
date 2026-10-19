package com.codeassist.ai.workspace

import android.content.Intent
import android.content.ClipboardManager
import android.content.Context
import android.content.ClipData
import android.os.Bundle
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.codeassist.ai.MainActivity
import com.codeassist.ai.R
import com.codeassist.ai.data.Attachment
import com.codeassist.ai.data.Message
import com.codeassist.ai.data.Project
import com.codeassist.ai.data.Role
import com.codeassist.ai.projects.CreateProjectDialog
import com.codeassist.ai.data.Store
import com.codeassist.ai.projects.ProjectsFragment
import com.codeassist.ai.ui.AttachSheet
import com.codeassist.ai.ui.AttachmentHelper
import com.codeassist.ai.ui.ComposerController
import java.io.File

class WorkspaceActivity : AppCompatActivity() {

    companion object { const val EXTRA_PROJECT_ID = "project_id" }

    private lateinit var project: Project
    private lateinit var filePicker: AttachmentHelper
    private lateinit var composerAttachments: AttachmentHelper
    private lateinit var recycler: RecyclerView
    private lateinit var welcome: LinearLayout
    private lateinit var tabs: List<TextView>
    private var tab = 0
    private var attachmentTarget = "message"
    private var clearingImportedFiles = false
    private val listAdapter = SimpleAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        setContentView(R.layout.activity_workspace)

        val id = intent.getStringExtra(EXTRA_PROJECT_ID)
        project = Store.project(id) ?: run { finish(); return }
        Store.lastProjectId = project.id

        findViewById<TextView>(R.id.textName).text = project.name
        findViewById<ImageView>(R.id.iconProject).apply {
            setImageResource(ProjectsFragment.ICONS[project.iconIdx.coerceIn(0, 5)])
            setColorFilter(ProjectsFragment.COLORS[project.colorIdx.coerceIn(0, 5)])
        }

        recycler = findViewById(R.id.recyclerTab)
        welcome = findViewById(R.id.welcomeState)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = listAdapter

        tabs = listOf(
            findViewById(R.id.tabChats), findViewById(R.id.tabFiles),
            findViewById(R.id.tabInstructions), findViewById(R.id.tabCode)
        )
        tabs.forEachIndexed { i, tv -> tv.setOnClickListener { selectTab(i) } }

        filePicker = AttachmentHelper(this)
        composerAttachments = AttachmentHelper(this)
        filePicker.onChanged = {
            if (!clearingImportedFiles) {
                val picked = filePicker.current.toList()
                if (picked.isNotEmpty()) {
                    project.fileUris = project.fileUris ?: mutableListOf()
                    while (project.fileUris!!.size < project.files.size) {
                        project.fileUris!!.add("")
                    }
                    for (file in picked) {
                        if (project.fileUris!!.none { it == file.uri }) {
                            project.files.add(file.name)
                            project.fileUris!!.add(file.uri)
                        }
                    }
                    Store.updateProject(project)
                    clearingImportedFiles = true
                    filePicker.clear()
                    clearingImportedFiles = false
                    selectTab(1)
                }
            }
        }
        supportFragmentManager.setFragmentResultListener(
            AttachSheet.RESULT_KEY,
            this
        ) { _, result ->
            val helper = if (attachmentTarget == "project") filePicker else composerAttachments
            when (result.getString(AttachSheet.RESULT_ACTION)) {
                AttachSheet.ACTION_IMAGE -> helper.openImages()
                AttachSheet.ACTION_ZIP -> helper.openZip()
                AttachSheet.ACTION_FILE -> helper.openFiles()
            }
        }

        val composer = ComposerController(findViewById(R.id.composer), composerAttachments) { text, atts ->
            val title = text.take(42).ifBlank { atts.firstOrNull()?.name ?: "Project chat" }
            val chat = Store.newChat(title, project.id)
            Store.saveMessages(chat.id, mutableListOf(Message(role = Role.USER, text = text, attachments = atts)))
            chat.snippet = text.ifBlank { atts.firstOrNull()?.name.orEmpty() }
            chat.time = System.currentTimeMillis()
            Store.updateChat(chat)
            // the home screen answers it as soon as the chat opens
            Store.pendingReplyChatId = chat.id
            openChatInPlace(chat.id)
        }
        composer.hint = "Message..."
        composer.hideBackendControls()
        composer.hideMic()
        composer.setModelLabel(com.codeassist.ai.ai.BrainStatus.chipLabel())
        composer.onPlusClick = { showAttachmentSheet("message") }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnMore).setOnClickListener { anchor ->
            val popup = PopupMenu(this, anchor)
            popup.menu.add(0, 0, 0, "Edit project")
            popup.menu.add(0, 1, 1, "Add item")
            popup.menu.add(0, 2, 2, "Delete project")
            popup.setOnMenuItemClickListener {
                when (it.itemId) {
                    0 -> CreateProjectDialog.newInstance(project.id)
                        .show(supportFragmentManager, "edit-project")
                    1 -> addCurrentTabItem()
                    2 -> confirmDelete()
                }
                true
            }
            popup.show()
        }

        findViewById<View>(R.id.btnNewChat).setOnClickListener {
            val chat = Store.newChat("${project.name} chat", project.id)
            openChatInPlace(chat.id)
        }
        findViewById<View>(R.id.btnUploadFile).setOnClickListener {
            attachmentTarget = "project"
            filePicker.openFiles()
        }
        findViewById<View>(R.id.btnAddInstruction).setOnClickListener { instructionDialog() }
        findViewById<View>(R.id.btnAddCodeFile).setOnClickListener { createFileDialog() }

        supportFragmentManager.setFragmentResultListener(
            CreateProjectDialog.RESULT_KEY,
            this
        ) { _, _ -> refreshProjectHeader() }
        selectTab(0)
    }

    private fun instructionDialog(index: Int? = null) {
        val edit = EditText(this).apply {
            hint = "e.g. Always use Kotlin and Material 3"
            setHintTextColor(0xFF5D6B7C.toInt())
            setTextColor(0xFFE8EEF5.toInt())
            setBackgroundResource(R.drawable.field_bg)
            val pad = (18 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            minLines = 2
            filters = arrayOf(android.text.InputFilter.LengthFilter(500))
            setText(index?.let { project.instructions.getOrNull(it) }.orEmpty())
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val wrap = FrameLayout(this).apply { setPadding(pad, pad, pad, 0); addView(edit) }
        AlertDialog.Builder(this)
            .setTitle(if (index == null) "Add instruction" else "Edit instruction")
            .setView(wrap)
            .setPositiveButton(if (index == null) "Add" else "Save") { _, _ ->
                val t = edit.text.toString().trim()
                if (t.isNotEmpty()) {
                    if (index == null) project.instructions.add(t)
                    else if (index in project.instructions.indices) project.instructions[index] = t
                    Store.updateProject(project)
                    selectTab(2)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun selectTab(i: Int) {
        tab = i
        tabs.forEachIndexed { idx, tv ->
            if (idx == i) {
                tv.setBackgroundResource(R.drawable.tab_selected)
                tv.setTextColor(0xFFE8EEF5.toInt())
            } else {
                tv.background = null
                tv.setTextColor(0xFF5D6B7C.toInt())
            }
        }
        refreshList()
    }

    private fun refreshList() {
        val rows = mutableListOf<Row>()
        when (tab) {
            0 -> Store.chats().filter { it.projectId == project.id }.forEach {
                rows.add(Row(
                    it.title,
                    it.snippet.ifBlank { "No messages yet" },
                    R.drawable.ic_chat,
                    0xFF6EC1FF.toInt(),
                    onClick = { openChatInPlace(it.id) }
                ))
            }
            1 -> project.files.forEachIndexed { index, f ->
                rows.add(Row(
                    f, "Project file", R.drawable.ic_file, 0xFFB49CFF.toInt(),
                    onClick = { openFile(index) },
                    onLongClick = { fileActions(index) }
                ))
            }
            2 -> project.instructions.forEachIndexed { index, ins ->
                rows.add(Row(
                    ins, "Project instruction", R.drawable.ic_edit, 0xFF7BE3A8.toInt(),
                    onClick = { instructionDialog(index) },
                    onLongClick = { instructionActions(index) }
                ))
            }
            3 -> project.files.forEachIndexed { index, f ->
                rows.add(Row(
                    f, "Open source file", R.drawable.ic_code, 0xFFFFB37B.toInt(),
                    onClick = { openFile(index) },
                    onLongClick = { fileActions(index) }
                ))
            }
        }
        listAdapter.submit(rows)
        val isEmpty = rows.isEmpty()
        welcome.visibility = if (isEmpty) View.VISIBLE else View.GONE
        recycler.visibility = if (isEmpty) View.GONE else View.VISIBLE
        welcome.findViewById<TextView>(R.id.emptyTitle).text = when (tab) {
            0 -> "Your project chats"
            1 -> "No files yet"
            2 -> "No instructions yet"
            else -> "No code files yet"
        }
        welcome.findViewById<TextView>(R.id.emptyDescription).text = when (tab) {
            0 -> "Start a local chat in this project."
            1 -> "Choose a file from your device to add it here."
            2 -> "Add notes to keep project requirements together."
            else -> "Create a local file or add one from your device."
        }
        welcome.findViewById<View>(R.id.btnNewChat).visibility = if (tab == 0) View.VISIBLE else View.GONE
        welcome.findViewById<View>(R.id.btnUploadFile).visibility = if (tab == 1) View.VISIBLE else View.GONE
        welcome.findViewById<View>(R.id.btnAddInstruction).visibility = if (tab == 2) View.VISIBLE else View.GONE
        welcome.findViewById<View>(R.id.btnAddCodeFile).visibility = if (tab == 3) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        project = Store.project(project.id) ?: run { finish(); return }
        refreshProjectHeader()
        refreshList()
    }

    private fun refreshProjectHeader() {
        project = Store.project(project.id) ?: return
        findViewById<TextView>(R.id.textName).text = project.name
        findViewById<ImageView>(R.id.iconProject).apply {
            setImageResource(ProjectsFragment.ICONS[project.iconIdx.coerceIn(0, 5)])
            setColorFilter(ProjectsFragment.COLORS[project.colorIdx.coerceIn(0, 5)])
        }
    }

    private fun showAttachmentSheet(target: String) {
        attachmentTarget = target
        AttachSheet().show(supportFragmentManager, "attach")
    }

    private fun addCurrentTabItem() {
        when (tab) {
            0 -> findViewById<View>(R.id.btnNewChat).performClick()
            1 -> filePicker.openFiles()
            2 -> instructionDialog()
            3 -> createFileDialog()
        }
    }

    private fun instructionActions(index: Int) {
        AlertDialog.Builder(this)
            .setTitle("Instruction")
            .setItems(arrayOf("Edit", "Delete")) { _, which ->
                if (which == 0) instructionDialog(index)
                else confirmDeleteInstruction(index)
            }
            .show()
    }

    private fun confirmDeleteInstruction(index: Int) {
        AlertDialog.Builder(this)
            .setTitle("Delete instruction")
            .setMessage("Remove this project instruction?")
            .setPositiveButton("Delete") { _, _ ->
                if (index in project.instructions.indices) {
                    project.instructions.removeAt(index)
                    Store.updateProject(project)
                    refreshList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fileActions(index: Int) {
        if (index !in project.files.indices) return
        AlertDialog.Builder(this)
            .setTitle(project.files[index])
            .setItems(arrayOf("Open", "Delete")) { _, which ->
                if (which == 0) openFile(index) else confirmDeleteFile(index)
            }
            .show()
    }

    private fun confirmDeleteFile(index: Int) {
        AlertDialog.Builder(this)
            .setTitle("Delete file")
            .setMessage("Remove \"${project.files.getOrNull(index).orEmpty()}\" from this workspace? The original device file will not be deleted.")
            .setPositiveButton("Delete") { _, _ ->
                val uri = project.fileUris?.getOrNull(index)?.let(Uri::parse)
                if (uri?.scheme == "file") uri.path?.let(::File)?.delete()
                if (index in project.files.indices) project.files.removeAt(index)
                project.fileUris = project.fileUris?.toMutableList()?.apply {
                    if (index in indices) removeAt(index)
                }
                Store.updateProject(project)
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createFileDialog() {
        val input = EditText(this).apply {
            hint = "e.g. README.md"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val content = FrameLayout(this).apply {
            setPadding(pad, pad, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Create file")
            .setView(content)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                    .substringAfterLast('/')
                    .substringAfterLast('\\')
                if (name.isNotBlank() && name != "." && name != ".." &&
                    name.length <= 120 && name.none { it.isISOControl() }
                ) {
                    try {
                        val dir = File(filesDir, "project-files/${project.id}").apply { mkdirs() }
                        val file = File(dir, name)
                        if (file.exists()) {
                            Toast.makeText(this, "A file with that name already exists.", Toast.LENGTH_LONG).show()
                        } else {
                            file.createNewFile()
                            project.files.add(name)
                            project.fileUris = (project.fileUris ?: mutableListOf()).apply {
                                while (size < project.files.lastIndex) add("")
                                add(Uri.fromFile(file).toString())
                            }
                            Store.updateProject(project)
                            selectTab(3)
                            Toast.makeText(this, "File created locally", Toast.LENGTH_SHORT).show()
                        }
                    } catch (_: Exception) {
                        Toast.makeText(this, "Couldn't create the file.", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this, "Enter a valid file name.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openFile(index: Int) {
        val name = project.files.getOrNull(index) ?: return
        val uri = project.fileUris?.getOrNull(index)
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::parse)
        if (uri == null) {
            Toast.makeText(this, "This file reference is no longer available.", Toast.LENGTH_LONG).show()
            return
        }
        if (!isTextFile(name, contentResolver.getType(uri))) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, contentResolver.getType(uri) ?: "application/octet-stream")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } catch (_: Exception) {
                Toast.makeText(this, "No app can open this file type.", Toast.LENGTH_LONG).show()
            }
            return
        }
        Thread {
            val content = try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText().take(500_000) }
                    ?: ""
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                if (content == null) {
                    Toast.makeText(this, "Could not open this file.", Toast.LENGTH_LONG).show()
                } else {
                    showFileContent(name, uri, content)
                }
            }
        }.start()
    }

    private fun showFileContent(name: String, uri: Uri, content: String) {
        val code = TextView(this).apply {
            text = content.ifBlank { "(Empty file)" }
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            setTextColor(0xFFE8EEF5.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            addView(code)
            setBackgroundResource(R.drawable.glass_card_solid)
        }
        AlertDialog.Builder(this)
            .setTitle(name)
            .setView(scroll)
            .setNeutralButton("Copy") { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText(name, content))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Share") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    if (uri.scheme == "file") {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, content)
                    } else {
                        setDataAndType(uri, contentResolver.getType(uri) ?: "text/plain")
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TEXT, content)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                startActivity(Intent.createChooser(send, "Share $name"))
            }
            .setPositiveButton(if (uri.scheme == "file") "Edit" else "Close") { _, _ ->
                if (uri.scheme == "file") editLocalFile(name, uri, content)
            }
            .show()
    }

    private fun editLocalFile(name: String, uri: Uri, initial: String) {
        val editor = EditText(this).apply {
            setText(initial)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            minLines = 10
            maxLines = 24
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setTextColor(0xFFE8EEF5.toInt())
            setBackgroundResource(R.drawable.field_bg)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val scroll = ScrollView(this).apply { addView(editor) }
        AlertDialog.Builder(this)
            .setTitle("Edit $name")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                try {
                    val path = uri.path ?: throw IllegalStateException("Missing file path")
                    File(path).writeText(editor.text.toString())
                    Toast.makeText(this, "File saved", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(this, "Could not save the file.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun isTextFile(name: String, mime: String?): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return mime?.startsWith("text/") == true ||
            ext in setOf(
                "kt", "kts", "java", "js", "jsx", "ts", "tsx", "json", "xml", "md",
                "txt", "csv", "html", "css", "py", "gradle", "properties", "yaml",
                "yml", "sh", "sql", "c", "h", "cpp", "go", "rs", "toml"
            )
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Bring the main screen forward with this chat open — no separate chat screen. */
    private fun openChatInPlace(chatId: String) {
        Store.activeChatId = chatId
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_CHAT, chatId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete project")
            .setMessage("Delete \"${project.name}\" and its chats and local workspace files? Original device files will not be deleted.")
            .setPositiveButton("Delete") { _, _ ->
                Store.deleteProject(project.id)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    data class Row(
        val title: String,
        val sub: String,
        val icon: Int,
        val tint: Int,
        val onClick: () -> Unit,
        val onLongClick: (() -> Unit)? = null
    )

    class SimpleAdapter : RecyclerView.Adapter<SimpleAdapter.VH>() {
        private val items = mutableListOf<Row>()

        fun submit(list: List<Row>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.iconProject)
            val name: TextView = v.findViewById(R.id.textName)
            val meta: TextView = v.findViewById(R.id.textMeta)
            val time: TextView = v.findViewById(R.id.textTime)
            val more: View = v.findViewById(R.id.btnMore)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_project, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val r = items[pos]
            h.itemView.setBackgroundResource(
                if (Store.glassEffect) R.drawable.glass_card_16 else R.drawable.glass_card_solid
            )
            h.icon.setImageResource(r.icon)
            h.icon.setColorFilter(r.tint)
            h.name.text = r.title
            h.meta.text = r.sub
            h.time.text = ""
            h.more.visibility = if (r.onLongClick == null) View.GONE else View.VISIBLE
            h.more.setOnClickListener { r.onLongClick?.invoke() }
            h.itemView.setOnClickListener { r.onClick() }
            h.itemView.setOnLongClickListener {
                r.onLongClick?.invoke()
                r.onLongClick != null
            }
        }

        override fun getItemCount() = items.size
    }
}
