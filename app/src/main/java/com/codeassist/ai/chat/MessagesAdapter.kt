package com.codeassist.ai.chat

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.text.Selection
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.codeassist.ai.R
import com.codeassist.ai.data.AttachKind
import com.codeassist.ai.data.Attachment
import com.codeassist.ai.data.Message
import com.codeassist.ai.data.Role
import com.codeassist.ai.data.Store
import com.codeassist.ai.ui.AttachmentHelper
import com.codeassist.ai.ui.ImageLoader

class MessagesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val items = mutableListOf<Message>()
    private val messageViews = mutableMapOf<String, TextView>()
    var onMessageAction: ((Message) -> Unit)? = null
    var onImageClick: ((Attachment) -> Unit)? = null
    var onFileClick: ((Attachment) -> Unit)? = null

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
            is AiVH -> holder.bind(message)
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
        private var pulse: ObjectAnimator? = null

        fun bind(message: Message) {
            val busy = message.state == "thinking"
            val error = message.state == "error"
            val waiting = busy && message.text.isBlank()

            taskRow.visibility = if (message.engine == "tool") View.VISIBLE else View.GONE
            taskText.text = "Phone action · " + (message.note ?: "")

            if (waiting) {
                thinking.visibility = View.VISIBLE
                thinking.text = (message.note ?: "Soch raha hoon") + "…"
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

            val caption: String? = when {
                busy -> null
                error -> "Dobara try karne ke liye is message par dabake rakho, phir Regenerate."
                message.engine == "tool" -> null
                else -> message.note
            }
            note.visibility = if (caption.isNullOrBlank()) View.GONE else View.VISIBLE
            note.text = caption.orEmpty()
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
