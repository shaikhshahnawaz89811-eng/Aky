package com.codeassist.ai.chats

import android.os.Bundle
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.codeassist.ai.MainActivity
import com.codeassist.ai.R
import com.codeassist.ai.data.ChatMeta
import com.codeassist.ai.data.Store
import com.codeassist.ai.data.Role

class ChatsFragment : Fragment() {

    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private lateinit var search: EditText
    private var query = ""
    private val adapter = ChatsAdapter(
        onClick = { chat -> (activity as? MainActivity)?.openChat(chat.id) },
        onLongClick = { chat -> showChatActions(chat) }
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_chats, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recycler = view.findViewById(R.id.recyclerChats)
        empty = view.findViewById(R.id.emptyChats)
        search = view.findViewById(R.id.editChatSearch)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                query = s?.toString()?.trim().orEmpty()
                refreshList()
            }
        })
        search.setText(query)
        view.findViewById<View>(R.id.btnMenu).setOnClickListener {
            (activity as? MainActivity)?.openDrawer()
        }
        view.findViewById<View>(R.id.btnNewChat).setOnClickListener {
            (activity as? MainActivity)?.newChat()
        }
    }

    private fun showChatActions(chat: ChatMeta) {
        val actions = mutableListOf("Open", "Rename", if (chat.pinned) "Unpin" else "Pin", "Move to project", "Export", "Delete")
        AlertDialog.Builder(requireContext())
            .setTitle(chat.title)
            .setItems(actions.toTypedArray()) { _, index ->
                when (actions[index]) {
                    "Open" -> (activity as? MainActivity)?.openChat(chat.id)
                    "Rename" -> renameChat(chat)
                    "Pin", "Unpin" -> {
                        chat.pinned = !chat.pinned
                        Store.updateChat(chat)
                        refreshList()
                    }
                    "Move to project" -> moveChat(chat)
                    "Export" -> exportChat(chat)
                    "Delete" -> confirmDelete(chat)
                }
            }
            .show()
    }

    private fun confirmDelete(chat: ChatMeta) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete chat")
            .setMessage("Delete \"${chat.title}\" and its messages from this device?")
            .setPositiveButton("Delete") { _, _ -> Store.deleteChat(chat.id); refreshList() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameChat(chat: ChatMeta) {
        val input = EditText(requireContext()).apply {
            setSingleLine(true)
            setText(chat.title)
            setSelection(text.length)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Rename chat")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val title = input.text.toString().trim()
                if (title.isNotEmpty()) {
                    chat.title = title
                    Store.updateChat(chat)
                    refreshList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun moveChat(chat: ChatMeta) {
        val projects = Store.projects()
        val labels = arrayOf("No project") + projects.map { it.name }
        val selected = (projects.indexOfFirst { it.id == chat.projectId } + 1).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle("Move chat to project")
            .setSingleChoiceItems(labels, selected) { dialog, index ->
                Store.moveChat(chat.id, if (index == 0) null else projects[index - 1].id)
                refreshList()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportChat(chat: ChatMeta) {
        val transcript = buildString {
            appendLine(chat.title)
            appendLine()
            Store.messages(chat.id).forEach { message ->
                appendLine(if (message.role == Role.USER) "You:" else "Assistant:")
                appendLine(message.text)
                message.attachments.forEach { appendLine("[Attachment: ${it.name}]") }
                appendLine()
            }
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, chat.title)
            putExtra(Intent.EXTRA_TEXT, transcript)
        }
        startActivity(Intent.createChooser(intent, "Export chat"))
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        if (!::recycler.isInitialized) return
        val list = Store.chats().filter {
            query.isBlank() || it.title.contains(query, true) || it.snippet.contains(query, true)
        }
        adapter.submit(list)
        empty.text = if (query.isBlank()) "No chats yet.\nStart one from the home screen."
        else "No chats match \"$query\"."
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }
}

class ChatsAdapter(
    private val onClick: (ChatMeta) -> Unit,
    private val onLongClick: (ChatMeta) -> Unit
) : RecyclerView.Adapter<ChatsAdapter.VH>() {

    private val items = mutableListOf<ChatMeta>()

    fun submit(list: List<ChatMeta>) {
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
        diff.dispatchUpdatesTo(this)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.textTitle)
        val snippet: TextView = v.findViewById(R.id.textSnippet)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_chat, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val c = items[pos]
        h.itemView.setBackgroundResource(
            if (Store.glassEffect) R.drawable.glass_card_16 else R.drawable.glass_card_solid
        )
        h.title.text = c.title
        h.snippet.text = buildList {
            if (c.pinned) add("Pinned")
            add(c.snippet.ifBlank { "No messages yet" })
        }.joinToString(" · ")
        h.itemView.setOnClickListener { onClick(c) }
        h.itemView.setOnLongClickListener { onLongClick(c); true }
    }

    override fun getItemCount() = items.size
}
