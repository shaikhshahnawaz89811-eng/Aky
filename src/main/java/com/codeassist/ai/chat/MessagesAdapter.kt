package com.codeassist.ai.chat

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.Selection
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.codeassist.ai.R
import com.codeassist.ai.ai.ActivityLog
import com.codeassist.ai.data.AttachKind
import com.codeassist.ai.data.Attachment
import com.codeassist.ai.data.Message
import com.codeassist.ai.data.Role
import com.codeassist.ai.data.Store
import com.codeassist.ai.ai.LinkDownloader
import com.codeassist.ai.search.UrlTools
import com.codeassist.ai.search.WebCard
import com.codeassist.ai.search.WebInfo
import com.codeassist.ai.search.WebSource
import com.codeassist.ai.ui.AttachmentHelper
import com.codeassist.ai.ui.ImageLoader
import com.codeassist.ai.ui.SketchView

class MessagesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val items = mutableListOf<Message>()
    private val messageViews = mutableMapOf<String, TextView>()
    var onMessageAction: ((Message) -> Unit)? = null
    var onImageClick: ((Attachment) -> Unit)? = null
    var onFileClick: ((Attachment) -> Unit)? = null
    var onUndo: ((Message) -> Unit)? = null
    /** (message, true = Haan / false = Nahi) for a pending T2 phone action. */
    var onConfirm: ((Message, Boolean) -> Unit)? = null

    init {
        setHasStableIds(true)
    }

    fun submit(list: List<Message>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                items[oldItemPosition].id == list[newItemPosition].id
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                items[oldItemPosition] == list[newItemPosition]
        })
        items.clear()
        items.addAll(list)
        messageViews.clear()
        diff.dispatchUpdatesTo(this)
    }

    fun insert(message: Message) {
        items.add(message)
        notifyItemInserted(items.lastIndex)
    }

    /** Replaces one message in place (used while a reply streams in). The payload keeps the same view, no flicker. */
    fun update(message: Message) {
        val index = items.indexOfFirst { it.id == message.id }
        if (index < 0) return
        items[index] = message
        notifyItemChanged(index, "update")
    }

    /** Re-binds every row (used after an Undo so the button turns into "Wapas ho gaya"). */
    fun refresh() {
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
    }

    fun selectText(messageId: String) {
        messageViews[messageId]?.let { view ->
            view.requestFocus()
            (view.text as? Spannable)?.let(Selection::selectAll)
        }
    }

    override fun getItemId(position: Int) = items[position].id.hashCode().toLong()
    override fun getItemViewType(position: Int) = if (items[position].role == Role.USER) 0 else 1
    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layout = if (viewType == 0) R.layout.item_msg_user else R.layout.item_msg_ai
        return if (viewType == 0) {
            UserVH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
        } else {
            AiVH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = items[position]
        when (holder) {
            is UserVH -> holder.bind(message, onImageClick, onFileClick)
            is AiVH -> holder.bind(message, onUndo, onConfirm)
        }
        val textView = holder.itemView.findViewById<TextView>(R.id.textMsg)
        messageViews[message.id] = textView
        textView.setOnLongClickListener {
            onMessageAction?.invoke(message)
            true
        }
        holder.itemView.setOnLongClickListener {
            onMessageAction?.invoke(message)
            true
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        (holder as? AiVH)?.resumePulse()
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        (holder as? AiVH)?.stopPulse()
        super.onViewDetachedFromWindow(holder)
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        (holder as? AiVH)?.stopPulse()
        val text = holder.itemView.findViewById<TextView>(R.id.textMsg)
        messageViews.entries.removeAll { it.value === text }
        super.onViewRecycled(holder)
    }

    companion object {
        fun applyTextSize(text: TextView) {
            text.textSize = when (Store.textSize) {
                "Small" -> 12f
                "Large" -> 16f
                else -> 14f
            }
        }

        fun renderMarkdown(source: String): CharSequence {
            val output = SpannableStringBuilder()
            val fencedCode = Regex("(?s)```([A-Za-z0-9_+.-]*)\\s*\\n?(.*?)```")
            var cursor = 0
            fencedCode.findAll(source).forEach { match ->
                output.append(source.substring(cursor, match.range.first))
                val code = match.groupValues[2].trimEnd()
                val start = output.length
                output.append(code)
                val end = output.length
                if (end > start) {
                    output.setSpan(TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    output.setSpan(BackgroundColorSpan(Color.rgb(25, 35, 48)), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    output.setSpan(ForegroundColorSpan(Color.rgb(205, 224, 244)), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                output.append("\n")
                cursor = match.range.last + 1
            }
            output.append(source.substring(cursor))

            Regex("\\*\\*(.+?)\\*\\*").findAll(output.toString()).toList().asReversed().forEach { match ->
                val contentStart = match.range.first + 2
                val contentEnd = match.range.last - 1
                output.setSpan(StyleSpan(Typeface.BOLD), contentStart, contentEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                output.delete(match.range.last - 1, match.range.last + 1)
                output.delete(match.range.first, match.range.first + 2)
            }
            Regex("`([^`]+)`").findAll(output.toString()).toList().asReversed().forEach { match ->
                val contentStart = match.range.first + 1
                val contentEnd = match.range.last
                output.setSpan(TypefaceSpan("monospace"), contentStart, contentEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                output.delete(match.range.last, match.range.last + 1)
                output.delete(match.range.first, match.range.first + 1)
            }
            return output
        }
    }

    class UserVH(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view.findViewById(R.id.textMsg)
        private val attachBox: LinearLayout = view.findViewById(R.id.attachBox)
        private val voiceRow: View = view.findViewById(R.id.voiceRow)
        private val voiceText: TextView = view.findViewById(R.id.textVoice)

        fun bind(
            message: Message,
            onImageClick: ((Attachment) -> Unit)?,
            onFileClick: ((Attachment) -> Unit)?
        ) {
            text.visibility = if (message.text.isBlank()) View.GONE else View.VISIBLE
            text.text = renderMarkdown(message.text)
            text.setTextIsSelectable(true)
            applyTextSize(text)
            voiceRow.visibility = if (message.viaVoice) View.VISIBLE else View.GONE
            if (message.viaVoice) {
                val secs = message.voiceMs / 1000
                voiceText.text = "Voice · " + (secs / 60) + ":" + (secs % 60).toString().padStart(2, '0')
            }
            attachBox.removeAllViews()
            attachBox.visibility = if (message.attachments.isEmpty()) View.GONE else View.VISIBLE
            val context = attachBox.context
            message.attachments.forEach { attachment ->
                if (attachment.kind == AttachKind.IMAGE) {
                    val root = LayoutInflater.from(context).inflate(R.layout.item_attachment_msg, attachBox, false)
                    val image = root.findViewById<ImageView>(R.id.imageAttach)
                    ImageLoader.load(context, image, attachment.uri)
                    image.setOnClickListener { onImageClick?.invoke(attachment) }
                    attachBox.addView(root)
                } else {
                    val chip = LayoutInflater.from(context).inflate(R.layout.item_attachment, attachBox, false)
                    chip.findViewById<TextView>(R.id.textAttachName).text = attachment.name
                    chip.findViewById<TextView>(R.id.textAttachSize).text =
                        AttachmentHelper.formatSize(attachment.size)
                    chip.findViewById<ImageView>(R.id.iconAttach).setImageResource(
                        if (attachment.kind == AttachKind.ZIP) R.drawable.ic_zip else R.drawable.ic_file
                    )
                    chip.findViewById<View>(R.id.btnRemoveAttach).visibility = View.GONE
                    chip.setOnClickListener { onFileClick?.invoke(attachment) }
                    attachBox.addView(chip)
                }
            }
        }
    }

    class AiVH(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view.findViewById(R.id.textMsg)
        private val thinking: TextView = view.findViewById(R.id.textThinking)
        private val note: TextView = view.findViewById(R.id.textNote)
        private val taskRow: View = view.findViewById(R.id.taskRow)
        private val taskText: TextView = view.findViewById(R.id.textTask)
        private val undo: TextView = view.findViewById(R.id.textUndo)
        private val confirmRow: View = view.findViewById(R.id.confirmRow)
        private val confirmYes: TextView = view.findViewById(R.id.btnConfirmYes)
        private val confirmNo: TextView = view.findViewById(R.id.btnConfirmNo)
        private val sketchView: SketchView = view.findViewById(R.id.sketchView)
        private val sourcesScroll: View = view.findViewById(R.id.sourcesScroll)
        private val sourcesBox: LinearLayout = view.findViewById(R.id.sourcesBox)
        private val cardsBox: LinearLayout = view.findViewById(R.id.cardsBox)
        private var pulse: ObjectAnimator? = null

        fun bind(message: Message, onUndo: ((Message) -> Unit)?, onConfirm: ((Message, Boolean) -> Unit)?) {
            val busy = message.state == "thinking"
            val error = message.state == "error"
            val waiting = busy && message.text.isBlank()

            // a phone action is either a Tier-0 reply (engine "tool") or a brain reply that ran tools (actions chip)
            val isTool = message.engine == "tool"
            val hasChip = isTool || !message.actions.isNullOrBlank()
            taskRow.visibility = if (hasChip) View.VISIBLE else View.GONE
            taskText.text = "Phone action · " + (if (isTool) (message.note ?: "") else (message.actions ?: ""))
            val undoId = message.undoId
            if (!hasChip || undoId == null) {
                undo.visibility = View.GONE
                undo.setOnClickListener(null)
            } else {
                undo.visibility = View.VISIBLE
                if (ActivityLog.isUndone(undoId)) {
                    undo.text = "Wapas ho gaya"
                    undo.setTextColor(ContextCompat.getColor(undo.context, R.color.text3))
                    undo.isClickable = false
                    undo.setOnClickListener(null)
                } else {
                    undo.text = "Undo"
                    undo.setTextColor(ContextCompat.getColor(undo.context, R.color.accent))
                    undo.setOnClickListener { onUndo?.invoke(message) }
                }
            }

            val pendingConfirm = !message.pending.isNullOrBlank() && !busy && !error
            confirmRow.visibility = if (pendingConfirm) View.VISIBLE else View.GONE
            if (pendingConfirm) {
                confirmYes.setOnClickListener { onConfirm?.invoke(message, true) }
                confirmNo.setOnClickListener { onConfirm?.invoke(message, false) }
            } else {
                confirmYes.setOnClickListener(null)
                confirmNo.setOnClickListener(null)
            }

            if (waiting) {
                thinking.visibility = View.VISIBLE
                thinking.text = com.codeassist.ai.ai.Persona.fit(message.note ?: "Soch raha hoon", Store.personaGender) + "…"
                resumePulse()
            } else {
                thinking.visibility = View.GONE
                stopPulse()
            }

            text.visibility = if (waiting) View.GONE else View.VISIBLE
            text.text = renderMarkdown(message.text)
            text.setTextColor(
                if (error) 0xFFFF7B72.toInt() else ContextCompat.getColor(text.context, R.color.text1)
            )
            text.setTextIsSelectable(true)
            applyTextSize(text)

            // T4: a drawing from the sketch path sits under the text
            val drawing = if (busy || error) null else message.sketch
            if (drawing.isNullOrBlank()) {
                sketchView.visibility = View.GONE
            } else {
                sketchView.visibility = View.VISIBLE
                sketchView.setSketch(drawing)
            }

            // Net search (stage S2): the sources of a web answer sit under the text as tappable chips (text has no links)
            val webInfo = if (busy || error) null else WebInfo.parse(message.web)
            sourcesBox.removeAllViews()
            if (webInfo == null || webInfo.sources.isEmpty()) {
                sourcesScroll.visibility = View.GONE
            } else {
                sourcesScroll.visibility = View.VISIBLE
                addSourceChips(webInfo)
            }
            // stage S3: link and file cards (a file card has a Download button that asks first; the row itself never opens a file address)
            cardsBox.removeAllViews()
            if (webInfo == null || webInfo.cards.isEmpty()) {
                cardsBox.visibility = View.GONE
            } else {
                cardsBox.visibility = View.VISIBLE
                for (card in webInfo.cards) addCard(card)
            }

            val caption: String? = when {
                busy -> null
                error -> "Dobara try karne ke liye is message par dabake rakho, phir Regenerate."
                isTool -> null
                else -> message.note
            }
            note.visibility = if (caption.isNullOrBlank()) View.GONE else View.VISIBLE
            note.text = caption.orEmpty()
        }

        private fun addSourceChips(info: WebInfo) {
            val ctx = sourcesBox.context
            val d = ctx.resources.displayMetrics.density
            val label = TextView(ctx).apply {
                text = if (info.citedNone) "Mile sources:" else "Sources:"
                setTextColor(ContextCompat.getColor(ctx, R.color.text3))
                textSize = 12f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, (8 * d).toInt(), 0)
            }
            sourcesBox.addView(label)
            for (source in info.sources) {
                val chip = TextView(ctx).apply {
                    val date = if (source.date != null) " · " + source.date else ""
                    text = "[" + source.id + "] " + source.domain + date + (if (source.old) " · purana" else "")
                    contentDescription = source.title.ifBlank { source.domain }
                    setTextColor(ContextCompat.getColor(ctx, R.color.accent))
                    textSize = 12f
                    maxLines = 1
                    background = ContextCompat.getDrawable(ctx, R.drawable.chip_small)
                    setPadding((10 * d).toInt(), (5 * d).toInt(), (12 * d).toInt(), (5 * d).toInt())
                    setOnClickListener { openSource(it, source) }
                }
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (6 * d).toInt() }
                sourcesBox.addView(chip, params)
            }
        }

        private fun addCard(card: WebCard) {
            val ctx = cardsBox.context
            val d = ctx.resources.displayMetrics.density
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(ctx, R.drawable.chip_small)
                setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            }
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val title = TextView(ctx).apply {
                text = card.title.ifBlank { card.domain }
                setTextColor(ContextCompat.getColor(ctx, R.color.text1))
                textSize = 14f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val low = card.trust == "LOW"
            val sub = TextView(ctx).apply {
                text = card.badge + " · " + card.domain
                setTextColor(ContextCompat.getColor(ctx, if (low) R.color.error_text else if (card.trust == "LIKELY_OFFICIAL") R.color.accent else R.color.text2))
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            col.addView(title)
            col.addView(sub)
            val detail = card.detail
            if (!detail.isNullOrBlank()) {
                col.addView(TextView(ctx).apply {
                    text = detail
                    setTextColor(ContextCompat.getColor(ctx, R.color.text3))
                    textSize = 12f
                    maxLines = 4
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
            if (card.role == "file" && card.checksum == null) {
                col.addView(TextView(ctx).apply {
                    text = "checksum nahi mila"
                    setTextColor(ContextCompat.getColor(ctx, R.color.warn))
                    textSize = 11f
                })
            }
            row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (card.role == "file") {
                if (card.downloadable) {
                    val button = TextView(ctx).apply {
                        text = "Download"
                        setTextColor(ContextCompat.getColor(ctx, R.color.accent))
                        textSize = 13f
                        setPadding((12 * d).toInt(), (8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt())
                        contentDescription = "Download " + card.title
                        setOnClickListener { LinkDownloader.ask(it.context, card) }
                    }
                    row.addView(button)
                }
            } else {
                row.contentDescription = card.title.ifBlank { card.domain }
                row.setOnClickListener { openCard(it, card.url) }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (6 * d).toInt() }
            cardsBox.addView(row, params)
        }

        private fun openCard(v: View, url: String) {
            if (!UrlTools.isHttps(url)) {
                Toast.makeText(v.context, "Ye link https nahi hai, isliye nahi khola.", Toast.LENGTH_SHORT).show()
                return
            }
            try {
                v.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {
                Toast.makeText(v.context, "Browser nahi mila.", Toast.LENGTH_SHORT).show()
            }
        }

        /** Only an https page is opened; the address comes from a real search result, never from the model's text. */
        private fun openSource(v: View, source: WebSource) {
            if (!UrlTools.isHttps(source.url)) {
                Toast.makeText(v.context, "Ye link https nahi hai, isliye nahi khola.", Toast.LENGTH_SHORT).show()
                return
            }
            try {
                v.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.url)))
            } catch (_: Exception) {
                Toast.makeText(v.context, "Browser nahi mila.", Toast.LENGTH_SHORT).show()
            }
        }

        fun resumePulse() {
            if (thinking.visibility != View.VISIBLE) return
            if (pulse?.isRunning == true) return
            pulse = ObjectAnimator.ofFloat(thinking, View.ALPHA, 0.55f, 1f).apply {
                duration = 1700L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        }

        fun stopPulse() {
            pulse?.cancel()
            pulse = null
            thinking.alpha = 1f
        }
    }
}
