package com.codeassist.ai.home

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.codeassist.ai.MainActivity
import com.codeassist.ai.R
import com.codeassist.ai.ai.ActivityLog
import com.codeassist.ai.ai.BrainStatus
import com.codeassist.ai.ai.ChatRunner
import com.codeassist.ai.ai.Modules
import com.codeassist.ai.ai.PlanExecutor
import com.codeassist.ai.chat.MessagesAdapter
import com.codeassist.ai.data.Attachment
import com.codeassist.ai.data.ChatMeta
import com.codeassist.ai.data.Message
import com.codeassist.ai.data.Role
import com.codeassist.ai.data.Store
import com.codeassist.ai.ui.AttachSheet
import com.codeassist.ai.ui.AttachmentHelper
import com.codeassist.ai.ui.ComposerController
import com.codeassist.ai.ui.ZoomImageView
import com.codeassist.ai.voice.MicButtonView
import com.codeassist.ai.voice.VoiceController
import com.codeassist.ai.voice.WakeCoordinator

/**
 * Home = the chat itself (ChatGPT / Claude mobile pattern).
 *  - Fresh state: greeting + composer.
 *  - Sending a message fades the greeting away and the conversation happens
 *    right here — the user is never pushed to another screen.
 *  - The active chat is persisted, so leaving the app and coming back
 *    restores the exact same conversation (no refresh).
 *  - Smart auto-scroll: new messages scroll into view only while the user is
 *    near the bottom; scrolling up locks the position and reveals a
 *    jump-to-bottom button.
 */
class HomeFragment : Fragment() {

    private lateinit var helper: AttachmentHelper
    private var composer: ComposerController? = null
    private var recycler: RecyclerView? = null
    private var greetingBox: View? = null
    private var btnJump: View? = null
    private var textTitle: TextView? = null
    private var textModel: TextView? = null
    private lateinit var adapter: MessagesAdapter

    private var chat: ChatMeta? = null
    private var messages = mutableListOf<Message>()
    private var atBottom = true
    private val draftHandler = Handler(Looper.getMainLooper())
    private var draftSave: Runnable? = null
    private var logoPulse: AnimatorSet? = null
    private var voice: VoiceController? = null
    private lateinit var micPermission: ActivityResultLauncher<String>
    private var afterMicGrant: (() -> Unit)? = null

    private val runListener = object : ChatRunner.Listener {
        override fun onProgress(run: ChatRunner.Run) = updatePending(run)
        override fun onFinished(run: ChatRunner.Run, message: Message?) = finishPending(run, message)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Activity-result pickers must be registered before the fragment is created
        helper = AttachmentHelper(this)
        micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = afterMicGrant
            afterMicGrant = null
            if (granted) action?.invoke() else showMicDenied()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        Store.init(requireContext())

        textTitle = view.findViewById(R.id.textTitle)
        textModel = view.findViewById(R.id.textModel)
        greetingBox = view.findViewById(R.id.greetingBox)
        btnJump = view.findViewById(R.id.btnJumpDown)
        val messageList = view.findViewById<RecyclerView>(R.id.recyclerMessages)
        recycler = messageList

        view.findViewById<TextView>(R.id.textGreeting).text = "Hello"
        val logo = view.findViewById<View>(R.id.logoGlow)
        logoPulse = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(logo, View.ALPHA, 0.82f, 1f).apply {
                    duration = 1900L
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                },
                ObjectAnimator.ofFloat(logo, View.SCALE_X, 0.98f, 1.035f).apply {
                    duration = 1900L
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                },
                ObjectAnimator.ofFloat(logo, View.SCALE_Y, 0.98f, 1.035f).apply {
                    duration = 1900L
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                }
            )
        }
        logoPulse?.start()

        // Message list
        val lm = LinearLayoutManager(requireContext())
        // Start a brand-new conversation at the top. Once there is history,
        // keep the newest messages anchored at the bottom.
        lm.stackFromEnd = false
        messageList.layoutManager = lm
        adapter = MessagesAdapter()
        adapter.onMessageAction = { showMessageActions(it) }
        adapter.onImageClick = { showImagePreview(it) }
        adapter.onFileClick = { showAttachmentActions(it) }
        adapter.onUndo = { m ->
            val id = m.undoId
            val ctx = context
            if (id != null && ctx != null) {
                Toast.makeText(ctx, ActivityLog.undo(ctx, id), Toast.LENGTH_LONG).show()
                adapter.refresh()
            }
        }
        adapter.onConfirm = { m, yes ->
            val ctx = context
            val c = chat
            if (ctx != null && c != null) {
                val updated = PlanExecutor.confirm(ctx, c.id, m.id, yes)
                if (updated != null) {
                    val idx = messages.indexOfFirst { it.id == updated.id }
                    if (idx >= 0) messages[idx] = updated
                    adapter.update(updated)
                }
            }
        }
        messageList.adapter = adapter
        (messageList.itemAnimator as? DefaultItemAnimator)?.apply {
            addDuration = 160
            removeDuration = 120
            changeDuration = 0
            moveDuration = 0
        }
        messageList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = updateAtBottom()
        })
        // Keyboard open/close changes the list height — keep the tail pinned
        messageList.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom != oldBottom && atBottom) scrollToEnd(smooth = false)
        }
        btnJump?.setOnClickListener { scrollToEnd(smooth = true) }

        // Composer
        val chatComposer = ComposerController(view.findViewById(R.id.composer), helper) { text, atts ->
            send(text, atts)
        }
        composer = chatComposer
        chatComposer.hideBackendControls()
        chatComposer.onStop = {
            voice?.stopAll()
            ChatRunner.stop()
        }
        chatComposer.chipModel.setOnClickListener { (activity as? MainActivity)?.openVoiceAi() }
        Modules.init(requireContext())
        setupVoice(chatComposer)
        ChatRunner.listener = runListener
        refreshBrainLabels()
        chatComposer.onTextChanged = { draft ->
            draftSave?.let(draftHandler::removeCallbacks)
            draftSave = Runnable { persistDraft(draft) }
            draftHandler.postDelayed(draftSave!!, 350)
        }
        if (Store.activeChatId == null) chatComposer.edit.setText(Store.activeDraft)
        chatComposer.onPlusClick = { showAttachSheet() }
        parentFragmentManager.setFragmentResultListener(
            AttachSheet.RESULT_KEY,
            viewLifecycleOwner
        ) { _, result ->
            when (result.getString(AttachSheet.RESULT_ACTION)) {
                AttachSheet.ACTION_IMAGE -> helper.openImages()
                AttachSheet.ACTION_ZIP -> helper.openZip()
                AttachSheet.ACTION_FILE -> helper.openFiles()
            }
        }

        view.findViewById<View>(R.id.btnMenu).setOnClickListener {
            (activity as? MainActivity)?.openDrawer()
        }
        view.findViewById<View>(R.id.btnNewChat).setOnClickListener { startNewChat() }
        view.findViewById<View>(R.id.btnChatMenu).setOnClickListener { showChatMenu(it) }

        // Restore where the user left off (background / process-death safe)
        val activeId = Store.activeChatId
        val existing = activeId?.let { id -> Store.chats().firstOrNull { it.id == id } }
        if (existing != null) {
            loadChat(existing.id, animate = false)
        } else {
            showGreeting(animate = false)
        }
    }

    // ---------- Chat state ----------

    fun loadChat(id: String, animate: Boolean = true) {
        val c = Store.chats().firstOrNull { it.id == id } ?: return
        draftSave?.let(draftHandler::removeCallbacks)
        draftSave = null
        if (chat != null && chat?.id != id) {
            persistDraft(composer?.edit?.text?.toString().orEmpty())
        }
        chat = c
        Store.activeChatId = c.id
        composer?.edit?.setText(c.draft.orEmpty())
        messages = Store.messages(c.id)
        setStackFromEnd(messages.size > 1)
        if (this::adapter.isInitialized) adapter.submit(messages)
        enterChatMode(animate)
        recycler?.post { scrollToEnd(smooth = false) }
        syncRunState(c.id)
    }

    /** A reply may already be running (user left and came back) or waiting (chat started from a project). */
    private fun syncRunState(chatId: String) {
        if (view == null || !this::adapter.isInitialized) return
        val run = ChatRunner.current
        if (run != null && run.chatId == chatId) {
            showPending(run)
        } else if (Store.pendingReplyChatId == chatId) {
            Store.pendingReplyChatId = null
            startReply(false)
        } else {
            composer?.setGenerating(ChatRunner.isRunning())
        }
    }

    fun startNewChat() {
        draftSave?.let(draftHandler::removeCallbacks)
        draftSave = null
        persistDraft(composer?.edit?.text?.toString().orEmpty())
        chat = null
        Store.activeChatId = null
        Store.activeDraft = ""
        composer?.edit?.setText("")
        messages = mutableListOf()
        setStackFromEnd(false)
        if (this::adapter.isInitialized) adapter.submit(messages)
        composer?.setGenerating(ChatRunner.isRunning())
        showGreeting(animate = true)
    }

    /** Back press on home: leave the conversation, keep it saved. */
    fun closeChat(): Boolean {
        if (chat == null) return false
        startNewChat()
        return true
    }

    private fun send(text: String, atts: List<Attachment>, viaVoice: Boolean = false, voiceMs: Long = 0L) {
        if (!viaVoice) {
            val v = voice
            if (v != null && v.state != VoiceController.State.IDLE) v.stopAll()
        }
        var c = chat
        if (c == null) {
            val title = if (text.isNotBlank()) text.take(42) else (atts.firstOrNull()?.name ?: "New chat")
            c = Store.newChat(title)
            chat = c
            Store.activeChatId = c.id
            messages = Store.messages(c.id)
            setStackFromEnd(false)
            if (this::adapter.isInitialized) adapter.submit(messages)
            enterChatMode(animate = true)
        }
        val msg = Message(role = Role.USER, text = text, attachments = atts, viaVoice = viaVoice, voiceMs = voiceMs)
        messages.add(msg)
        setStackFromEnd(messages.size > 1)
        c.snippet = text.ifBlank { atts.firstOrNull()?.name ?: "" }
        c.time = System.currentTimeMillis()
        Store.updateChat(c)
        Store.saveMessages(c.id, messages)
        adapter.insert(msg)
        updateTopBar()
        maybeScrollEnd()
        startReply(viaVoice)
    }

    // ---------- Scroll ----------

    private fun updateAtBottom() {
        val list = recycler ?: return
        val jumpButton = btnJump ?: return
        val layoutManager = list.layoutManager as? LinearLayoutManager ?: return
        val lastPos = layoutManager.findLastVisibleItemPosition()
        var at = lastPos >= adapter.itemCount - 1
        if (at && lastPos >= 0) {
            val child = layoutManager.findViewByPosition(lastPos)
            if (child != null) {
                val visibleBottom = list.height - list.paddingBottom
                at = child.bottom <= visibleBottom + dp(64)
            }
        }
        atBottom = at
        val showJump = !at && adapter.itemCount > 0
        if (showJump && jumpButton.visibility != View.VISIBLE) {
            jumpButton.visibility = View.VISIBLE
            jumpButton.alpha = 0f
            jumpButton.animate().alpha(1f).setDuration(150).start()
        } else if (!showJump && jumpButton.visibility == View.VISIBLE) {
            jumpButton.animate().alpha(0f).setDuration(150).withEndAction {
                jumpButton.visibility = View.GONE
            }.start()
        }
    }

    private fun maybeScrollEnd() {
        if (atBottom) scrollToEnd(smooth = true) else updateAtBottom()
    }

    private fun setStackFromEnd(enabled: Boolean) {
        val layout = recycler?.layoutManager as? LinearLayoutManager ?: return
        if (layout.stackFromEnd != enabled) layout.stackFromEnd = enabled
    }

    private fun scrollToEnd(smooth: Boolean) {
        val count = adapter.itemCount
        if (count == 0) return
        recycler?.post {
            val list = recycler ?: return@post
            val layout = list.layoutManager as? LinearLayoutManager
            if (count == 1 && layout?.stackFromEnd == false) {
                layout.scrollToPositionWithOffset(0, dp(8))
                updateAtBottom()
                return@post
            }
            if (smooth) list.smoothScrollToPosition(count - 1)
            else list.scrollToPosition(count - 1)
        }
    }

    // ---------- Mode switching ----------

    private fun enterChatMode(animate: Boolean) {
        updateTopBar()
        val banner = greetingBox ?: return
        val list = recycler ?: return
        if (!animate) {
            banner.visibility = View.GONE
            list.visibility = View.VISIBLE
            list.alpha = 1f
            return
        }
        list.alpha = 0f
        list.visibility = View.VISIBLE
        banner.animate().cancel()
        banner.animate().alpha(0f).translationY(-dp(20).toFloat()).setDuration(170)
            .withEndAction {
                banner.visibility = View.GONE
                banner.alpha = 1f
                banner.translationY = 0f
            }.start()
        list.animate().cancel()
        list.animate().alpha(1f).setStartDelay(70).setDuration(200).start()
    }

    private fun showGreeting(animate: Boolean) {
        updateTopBar()
        val banner = greetingBox ?: return
        val list = recycler ?: return
        if (!animate) {
            list.visibility = View.GONE
            banner.visibility = View.VISIBLE
            banner.alpha = 1f
            return
        }
        banner.alpha = 0f
        banner.translationY = dp(14).toFloat()
        banner.visibility = View.VISIBLE
        list.animate().cancel()
        list.animate().alpha(0f).setDuration(150).withEndAction {
            list.visibility = View.GONE
        }.start()
        banner.animate().cancel()
        banner.animate().alpha(1f).translationY(0f).setStartDelay(60).setDuration(200).start()
    }

    private fun updateTopBar() {
        val c = chat
        // Keep the app identity in the header; the chat's first user message
        // is only used as its entry title in chat history.
        textTitle?.setText(R.string.app_name)
        textModel?.visibility = if (c != null) View.VISIBLE else View.GONE
        textModel?.text = BrainStatus.line()
        view?.findViewById<View>(R.id.btnChatMenu)?.visibility =
            if (c == null) View.GONE else View.VISIBLE
    }

    // ---------- Menus ----------

    private fun showAttachSheet() {
        AttachSheet().show(parentFragmentManager, "attach")
    }

    private fun showChatMenu(anchor: View) {
        val current = chat ?: return
        val actions = arrayOf("Rename", "Move to project", "Export chat", "Delete chat")
        PopupMenu(requireContext(), anchor).apply {
            actions.forEach { menu.add(it) }
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Rename" -> renameChat(current)
                    "Move to project" -> moveChat(current)
                    "Export chat" -> exportChat(current)
                    "Delete chat" -> confirmDeleteChat(current)
                }
                true
            }
            show()
        }
    }

    private fun renameChat(current: ChatMeta) {
        val input = EditText(requireContext()).apply {
            setSingleLine(true)
            setText(current.title)
            setSelection(text.length)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Rename chat")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val title = input.text.toString().trim()
                if (title.isNotEmpty()) {
                    current.title = title
                    current.time = System.currentTimeMillis()
                    Store.updateChat(current)
                    updateTopBar()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun moveChat(current: ChatMeta) {
        val projects = Store.projects()
        val labels = arrayOf("No project") + projects.map { it.name }
        val selected = (projects.indexOfFirst { it.id == current.projectId } + 1).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle("Move chat to project")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                val newProjectId = if (which == 0) null else projects[which - 1].id
                Store.moveChat(current.id, newProjectId)
                current.projectId = newProjectId
                current.time = System.currentTimeMillis()
                Store.updateChat(current)
                dialog.dismiss()
                (activity as? MainActivity)?.refreshDrawer()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportChat(current: ChatMeta) {
        val transcript = buildString {
            appendLine(current.title)
            appendLine()
            Store.messages(current.id).forEach { message ->
                appendLine(if (message.role == Role.USER) "You:" else "Assistant:")
                appendLine(message.text)
                message.attachments.forEach { appendLine("[Attachment: ${it.name}]") }
                appendLine()
            }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, current.title)
            putExtra(Intent.EXTRA_TEXT, transcript)
        }
        startActivity(Intent.createChooser(send, "Export chat"))
    }

    private fun confirmDeleteChat(current: ChatMeta) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete chat")
            .setMessage("Delete \"${current.title}\" and its messages?")
            .setPositiveButton("Delete") { _, _ ->
                Store.deleteChat(current.id)
                if (chat?.id == current.id) startNewChat()
                (activity as? MainActivity)?.refreshDrawer()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMessageActions(message: Message) {
        val chatId = chat?.id ?: return
        val actions = mutableListOf("Copy", "Select text", "Share")
        if (message.role == Role.USER) actions.add(2, "Edit")
        actions.add("Delete")
        if (message.role == Role.AI) actions.add("Regenerate")
        AlertDialog.Builder(requireContext())
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "Copy" -> copyText(message.text)
                    "Select text" -> if (this::adapter.isInitialized) adapter.selectText(message.id)
                    "Share" -> shareText(message.text)
                    "Edit" -> editMessage(chatId, message)
                    "Delete" -> deleteMessage(chatId, message)
                    "Regenerate" -> regenerate(chatId, message)
                }
            }
            .show()
    }

    private fun copyText(text: String) {
        (requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Message", text))
        Toast.makeText(requireContext(), "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Share message"))
    }

    private fun editMessage(chatId: String, message: Message) {
        val input = EditText(requireContext()).apply {
            setText(message.text)
            minLines = 3
            maxLines = 10
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Edit message")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val edited = message.copy(text = input.text.toString().trim())
                val index = messages.indexOfFirst { it.id == message.id }
                if (index >= 0) messages[index] = edited
                Store.saveMessages(chatId, messages)
                if (this::adapter.isInitialized) adapter.submit(messages)
                updateChatSnippet()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteMessage(chatId: String, message: Message) {
        messages.removeAll { it.id == message.id }
        Store.deleteMessage(chatId, message.id)
        if (this::adapter.isInitialized) adapter.submit(messages)
        updateChatSnippet()
    }

    private fun updateChatSnippet() {
        val current = chat ?: return
        current.snippet = messages.lastOrNull()?.text?.take(60).orEmpty()
        current.time = System.currentTimeMillis()
        Store.updateChat(current)
    }

    private fun showImagePreview(attachment: Attachment) {
        val dialog = Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val image = ZoomImageView(requireContext()).apply {
            scaleType = ImageView.ScaleType.MATRIX
            contentDescription = "Image preview; pinch to zoom"
        }
        dialog.setContentView(image)
        image.setOnClickListener { dialog.dismiss() }
        dialog.show()
        image.post {
            com.codeassist.ai.ui.ImageLoader.load(requireContext(), image, attachment.uri)
        }
    }

    private fun showAttachmentActions(attachment: Attachment) {
        AlertDialog.Builder(requireContext())
            .setTitle(attachment.name)
            .setItems(arrayOf("Open", "Share")) { _, which ->
                val uri = android.net.Uri.parse(attachment.uri)
                val send = Intent(if (which == 0) Intent.ACTION_VIEW else Intent.ACTION_SEND).apply {
                    setDataAndType(uri, attachment.mime.ifBlank { "*/*" })
                    if (which == 1) putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newUri(
                        requireContext().contentResolver,
                        attachment.name,
                        uri
                    )
                }
                if (which == 1) {
                    startActivity(Intent.createChooser(send, "Share ${attachment.name}"))
                } else {
                    try {
                        startActivity(send)
                    } catch (_: Exception) {
                        Toast.makeText(requireContext(), "No app can open this file.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun persistDraft(text: String) {
        val current = chat
        if (current == null) {
            Store.activeDraft = text
        } else {
            current.draft = text
            Store.updateChat(current)
        }
    }

    override fun onResume() {
        super.onResume()
        composer?.refreshAppearance()
        refreshBrainLabels()
        // hands-free: the wake phrase was heard while this screen was away or the app was closed
        WakeCoordinator.listener = wakeListener
        val pendingWake = WakeCoordinator.takePending()
        if (pendingWake != null) view?.post { handleWake(pendingWake) }
    }

    override fun onPause() {
        if (WakeCoordinator.listener === wakeListener) WakeCoordinator.listener = null
        super.onPause()
    }

    private val wakeListener = object : WakeCoordinator.Listener {
        override fun onWake(text: String) = handleWake(text)
    }

    /** The wake phrase was heard (service) or the user tapped the wake notification. */
    private fun handleWake(text: String) {
        val v = voice ?: return
        if (view == null || v.state != VoiceController.State.IDLE || ChatRunner.isRunning()) return
        if (!hasMicPermission()) return
        WakeCoordinator.buzz(requireContext())
        v.startFromWake(text)
    }

    override fun onDestroyView() {
        if (WakeCoordinator.listener === wakeListener) WakeCoordinator.listener = null
        if (ChatRunner.listener === runListener) ChatRunner.listener = null
        voice?.destroy()
        voice = null
        draftSave?.let(draftHandler::removeCallbacks)
        draftSave = null
        composer?.edit?.text?.toString()?.let(::persistDraft)
        logoPulse?.cancel()
        logoPulse = null
        helper.onChanged = null
        helper.clear()
        recycler?.adapter = null
        if (this::adapter.isInitialized) {
            adapter.onMessageAction = null
            adapter.onImageClick = null
            adapter.onFileClick = null
            adapter.onUndo = null
        }
        composer = null
        recycler = null
        greetingBox = null
        btnJump = null
        textTitle = null
        textModel = null
        super.onDestroyView()
    }

    // ---------- AI reply ----------

    private fun refreshBrainLabels() {
        composer?.setModelLabel(BrainStatus.chipLabel())
        updateTopBar()
    }

    private fun startReply(viaVoice: Boolean) {
        val c = chat ?: return
        val run = ChatRunner.start(requireContext(), c.id, viaVoice)
        if (run == null) {
            toast("Pehla jawab abhi chal raha hai. Stop dabao ya intezaar karo.")
            voice?.idle()
            return
        }
        showPending(run)
    }

    private fun pendingMessage(run: ChatRunner.Run) = Message(
        id = run.messageId, role = Role.AI, text = run.text, state = "thinking",
        engine = run.engine, note = run.phase
    )

    private fun showPending(run: ChatRunner.Run) {
        composer?.setGenerating(true)
        if (!this::adapter.isInitialized) return
        val pending = pendingMessage(run)
        val index = messages.indexOfFirst { it.id == run.messageId }
        if (index >= 0) {
            messages[index] = pending
            adapter.update(pending)
        } else {
            messages.add(pending)
            setStackFromEnd(messages.size > 1)
            adapter.insert(pending)
        }
        maybeScrollEnd()
    }

    private fun updatePending(run: ChatRunner.Run) {
        if (chat?.id != run.chatId || !this::adapter.isInitialized) return
        val index = messages.indexOfFirst { it.id == run.messageId }
        if (index < 0) {
            showPending(run)
            return
        }
        val pending = pendingMessage(run)
        messages[index] = pending
        adapter.update(pending)
        if (atBottom) scrollToEnd(smooth = false)
    }

    private fun finishPending(run: ChatRunner.Run, message: Message?) {
        composer?.setGenerating(false)
        if (chat?.id == run.chatId && this::adapter.isInitialized) {
            val index = messages.indexOfFirst { it.id == run.messageId }
            if (message != null) {
                if (index >= 0) {
                    messages[index] = message
                    adapter.update(message)
                } else {
                    messages.add(message)
                    adapter.insert(message)
                }
            } else if (index >= 0) {
                messages.removeAt(index)
                adapter.submit(messages)
            }
            chat = Store.chats().firstOrNull { it.id == run.chatId } ?: chat
            maybeScrollEnd()
        }
        val v = voice
        val speak = when (Store.speakReplies) {
            "always" -> true
            "voice" -> run.viaVoice
            else -> false
        }
        val spoken: String? =
            if (message != null && message.state == null && !run.cancelled && speak) message.text else null
        if (v != null) {
            if (run.viaVoice) {
                // a reply that ends in "?" opens a ~10 s window where a bare "haan / nahi" is an answer
                v.replyReady(spoken, spoken?.trimEnd()?.endsWith("?") == true, run.engine == "tool")
            } else if (spoken != null) {
                v.speakIfIdle(spoken)
            }
        }
    }

    /** Only the last reply can be regenerated; older ones would drop the messages after them. */
    private fun regenerate(chatId: String, message: Message) {
        if (ChatRunner.isRunning()) {
            toast("Pehla jawab abhi chal raha hai. Stop dabao ya intezaar karo.")
            return
        }
        if (messages.lastOrNull()?.id != message.id) {
            toast("Sirf aakhri reply dobara banti hai.")
            return
        }
        val index = messages.indexOfFirst { it.id == message.id }
        if (index < 0 || messages.none { it.role == Role.USER }) return
        messages.removeAt(index)
        Store.saveMessages(chatId, messages)
        adapter.submit(messages)
        startReply(false)
    }

    // ---------- Voice ----------

    private fun setupVoice(c: ComposerController) {
        val controller = VoiceController(requireContext(), object : VoiceController.Callbacks {
            override fun onState(state: VoiceController.State, mode: VoiceController.Mode) =
                applyVoiceVisual(state, mode)

            override fun onPartial(text: String) {
                composer?.setVoicePartial(text)
            }

            override fun onLevel(level: Float) {
                composer?.setVoiceLevel(level)
            }

            override fun onFinalText(text: String, durationMs: Long) = handleVoiceText(text, durationMs)

            override fun onMessage(text: String) = toast(text)

            override fun onNeedPermission() = askMicPermission(null)
        })
        voice = controller
        c.mic.listener = object : MicButtonView.Listener {
            override fun onTap() = onMicTap()
            override fun onHoldStart() = onMicHold()
            override fun onHoldEnd(cancelled: Boolean) {
                controller.releaseHold(cancelled)
            }

            override fun onHoldLock() {
                controller.lockToContinuous()
            }

            override fun onHoldDrag(cancelling: Boolean, locking: Boolean) {
                if (controller.state != VoiceController.State.LISTENING) return
                composer?.setVoiceHint(
                    when {
                        cancelling -> "Chhodo: ye cancel ho jayega"
                        locking -> "Lock ho raha hai…"
                        else -> "← cancel ke liye slide · ↑ lock karne ke liye"
                    }
                )
            }
        }
    }

    private fun applyVoiceVisual(state: VoiceController.State, mode: VoiceController.Mode) {
        val visual = when (state) {
            VoiceController.State.IDLE -> MicButtonView.Visual.IDLE
            VoiceController.State.THINKING -> MicButtonView.Visual.THINKING
            VoiceController.State.SPEAKING -> MicButtonView.Visual.SPEAKING
            VoiceController.State.LISTENING -> when (mode) {
                VoiceController.Mode.TAP -> MicButtonView.Visual.TAP
                VoiceController.Mode.HOLD -> MicButtonView.Visual.HOLD
                VoiceController.Mode.CONTINUOUS -> MicButtonView.Visual.CONTINUOUS
            }
        }
        if (state == VoiceController.State.LISTENING) hideKeyboard()
        composer?.setVoiceVisual(visual)
    }

    private fun handleVoiceText(text: String, durationMs: Long) {
        if (!Store.autoSendVoice) {
            composer?.edit?.setText(text)
            composer?.edit?.setSelection(text.length)
            voice?.idle()
            return
        }
        send(text, emptyList(), viaVoice = true, voiceMs = durationMs)
    }

    private fun onMicTap() {
        val v = voice ?: return
        when (v.state) {
            VoiceController.State.IDLE -> {
                if (ChatRunner.isRunning()) {
                    toast("Pehle jawab khatam hone do ya Stop dabao.")
                    return
                }
                val start = {
                    if (Store.micMode == "continuous") v.startContinuous() else v.startTap()
                }
                if (hasMicPermission()) start() else askMicPermission(start)
            }
            VoiceController.State.LISTENING ->
                if (v.mode == VoiceController.Mode.TAP) v.finishNow() else v.stopAll()
            VoiceController.State.THINKING -> {
                v.stopAll()
                ChatRunner.stop()
            }
            VoiceController.State.SPEAKING -> v.stopAll()
        }
    }

    private fun onMicHold() {
        val v = voice ?: return
        if (v.state != VoiceController.State.IDLE) return
        if (ChatRunner.isRunning()) {
            toast("Pehle jawab khatam hone do ya Stop dabao.")
            return
        }
        if (!hasMicPermission()) {
            askMicPermission(null)
            return
        }
        v.startHold()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun askMicPermission(afterGrant: (() -> Unit)?) {
        if (view == null) return
        afterMicGrant = afterGrant
        AlertDialog.Builder(requireContext())
            .setTitle("Mic ki permission")
            .setMessage(
                "Voice ke liye mic chahiye. Mic tabhi sunta hai jab aap mic dabate ho. " +
                    "Speech recognition Android / Google ki service se hoti hai; offline pack ho toh audio phone par hi rehti hai."
            )
            .setPositiveButton("Allow") { _, _ -> micPermission.launch(Manifest.permission.RECORD_AUDIO) }
            .setNegativeButton("Abhi nahi") { _, _ -> afterMicGrant = null }
            .show()
    }

    private fun showMicDenied() {
        if (view == null) return
        if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            AlertDialog.Builder(requireContext())
                .setTitle("Mic block hai")
                .setMessage("Android ne mic permission band rakhi hai. Settings mein Permissions, phir Microphone, phir Allow karo.")
                .setPositiveButton("Settings kholo") { _, _ ->
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", requireContext().packageName, null)
                        )
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            toast("Mic permission ke bina voice nahi chalega.")
        }
    }

    private fun hideKeyboard() {
        val host = view ?: return
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(host.windowToken, 0)
        composer?.edit?.clearFocus()
    }

    private fun toast(text: String) {
        if (context == null) return
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
