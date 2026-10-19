package com.codeassist.ai.settings

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import com.codeassist.ai.R
import com.codeassist.ai.ai.MemoryBook
import com.codeassist.ai.ai.MemoryItem
import com.codeassist.ai.ai.MemoryStore
import com.codeassist.ai.data.Store
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 4A, audit Sec 9.5 "Memory Manager": everything the assistant remembers about the user, on one screen. See it, search it,
 * change the words of one item, delete one item, delete the last few minutes, delete everything, export it as text, and the two
 * switches (Private mode, and whether Gemini may get the few relevant facts too). Built in code like [SettingsFragment].
 */
class MemoryFragment : Fragment() {
    private lateinit var container: LinearLayout
    private lateinit var search: EditText
    private var activeCard: LinearLayout? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_memory, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        MemoryStore.init(requireContext())
        container = view.findViewById(R.id.memoryContainer)
        search = view.findViewById(R.id.memorySearch)
        view.findViewById<View>(R.id.btnBack).setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (isAdded) build()
            }
        })
        build()
    }

    private fun build() {
        container.removeAllViews()
        val now = System.currentTimeMillis()
        val all = MemoryStore.all(now)
        val shown = MemoryBook.search(all, search.text.toString())
        val long = shown.filter { it.layer == MemoryBook.LONG }.sortedByDescending { it.createdAt }
        val work = shown.filter { it.layer == MemoryBook.WORK }.sortedByDescending { it.createdAt }

        section("CONTROLS")
        card {
            switchRow(R.drawable.ic_shield, "Private mode (kuch naya save nahi hoga)", Store.memoryPrivate) {
                Store.memoryPrivate = it
            }
            switchRow(R.drawable.ic_globe, "Gemini (cloud) ko bhi yaad bhejo", Store.memoryToCloud) {
                Store.memoryToCloud = it
            }
            valueRow(R.drawable.ic_history, "Pichhle kuch minute bhool jao", "") { askForgetRecent() }
            valueRow(R.drawable.ic_upload, "Export (text)", "") { export() }
            actionRow(R.drawable.ic_delete, "Sab bhool jao (" + all.size + ")") { confirmForgetAll(all.size) }
            noteRow(
                "Private mode on ho tab bhi jo pehle se yaad hai wo use hota hai. Gemini wala switch off ho toh yaad sirf phone par " +
                    "rehti hai (Gemma ke liye); on karne par Gemini ko bhi har message par 6 tak kaam ki baatein bheji jati hain. " +
                    "OTP, password, PIN, card, Aadhaar / PAN kabhi save nahi hote."
            )
        }

        section("HAMESHA KE LIYE (" + long.size + ")")
        card {
            if (long.isEmpty()) noteRow(if (all.isEmpty()) emptyText() else "Is dhundh se kuch nahi mila.")
            for (item in long) itemRow(item, now)
        }

        if (work.isNotEmpty()) {
            section("ABHI KE LIYE, 24 GHANTE (" + work.size + ")")
            card { for (item in work) itemRow(item, now) }
        }

        section("YAAD RAKHO")
        card {
            noteRow(
                "Ye list is phone par encrypted rakhi hai aur backup mein nahi jaati. Kisi yaad ko hatane se bhi is chat ke purane " +
                    "messages nahi hatenge: wo baat wahan likhi rahegi aur model use padh sakta hai. Chat delete karo ya naya chat shuru karo."
            )
        }
    }

    private fun emptyText(): String =
        "Abhi kuch yaad nahi hai. Chat mein bolo \"yaad rakho ki ...\" ya \"mera naam ... hai\"."

    // ---------- one item ----------

    private fun itemRow(item: MemoryItem, now: Long) {
        val row = inflateRow()
        row.findViewById<ImageView>(R.id.rowIcon).setImageResource(R.drawable.ic_text)
        val title = row.findViewById<TextView>(R.id.rowTitle)
        title.text = item.text
        title.maxLines = 3
        title.ellipsize = TextUtils.TruncateAt.END
        row.findViewById<TextView>(R.id.rowValue).text =
            if (item.layer == MemoryBook.WORK) MemoryBook.leftWords(item, now).orEmpty() else MemoryBook.ageWords(item.createdAt, now)
        row.setOnClickListener { showItem(item) }
        addRow(row)
    }

    private fun showItem(item: MemoryItem) {
        val now = System.currentTimeMillis()
        val extra = if (item.layer == MemoryBook.WORK) " · " + MemoryBook.leftWords(item, now).orEmpty() else ""
        val b = AlertDialog.Builder(requireContext())
            .setTitle("Yaad")
            .setMessage(item.text + "\n\n" + MemoryBook.sourceWords(item.source) + " · " + MemoryBook.ageWords(item.createdAt, now) + extra)
            .setPositiveButton("Badlo") { _, _ -> editItem(item) }
            .setNegativeButton("Hatao") { _, _ -> deleteItem(item) }
        if (item.layer == MemoryBook.WORK) {
            b.setNeutralButton("Hamesha rakho") { _, _ ->
                MemoryStore.promote(item.id)
                Toast.makeText(requireContext(), "Ab ye hamesha ke liye hai", Toast.LENGTH_SHORT).show()
                build()
            }
        } else {
            b.setNeutralButton("Close", null)
        }
        b.show()
    }

    private fun editItem(item: MemoryItem) {
        val input = EditText(requireContext()).apply {
            setText(item.text)
            setSelection(text.length)
            setTextColor(0xFFE8EEF5.toInt())
            textSize = 14f
            maxLines = 4
            filters = arrayOf(android.text.InputFilter.LengthFilter(MemoryBook.MAX_TEXT))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Yaad badlo")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val err = MemoryStore.edit(item.id, input.text.toString())
                Toast.makeText(requireContext(), err ?: "Badal diya", Toast.LENGTH_LONG).show()
                build()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteItem(item: MemoryItem) {
        val ok = MemoryStore.delete(item.id)
        Toast.makeText(
            requireContext(),
            if (ok) "Hata diya. Is chat ke purane messages mein ye baat bachi rahegi." else "Hataya, par jaanch fail hui. App band karke dobara kholo.",
            Toast.LENGTH_LONG
        ).show()
        build()
    }

    // ---------- many items ----------

    private fun askForgetRecent() {
        val labels = arrayOf("Pichhle 5 minute", "Pichhle 30 minute", "Pichhla 1 ghanta", "Pichhle 24 ghante")
        val minutes = intArrayOf(5, 30, 60, 24 * 60)
        AlertDialog.Builder(requireContext())
            .setTitle("Kitna bhoolu?")
            .setItems(labels) { _, which ->
                val n = MemoryStore.forgetRecent(minutes[which])
                Toast.makeText(
                    requireContext(),
                    if (n == 0) "Is dauran kuch save nahi hua tha" else "$n yaad hata di",
                    Toast.LENGTH_SHORT
                ).show()
                build()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmForgetAll(count: Int) {
        if (count == 0) {
            Toast.makeText(requireContext(), "Pehle se khaali hai", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Sab bhool jao")
            .setMessage("Saari $count yaad hata dun? Wapas nahi aayengi.")
            .setPositiveButton("Hatao") { _, _ ->
                val n = MemoryStore.forgetAll()
                Toast.makeText(requireContext(), "$n yaad hata di", Toast.LENGTH_SHORT).show()
                build()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun export() {
        if (MemoryStore.all().isEmpty()) {
            Toast.makeText(requireContext(), "Export karne ko kuch nahi hai", Toast.LENGTH_SHORT).show()
            return
        }
        val stamp = SimpleDateFormat("d MMM yyyy HH:mm", Locale.ENGLISH).format(Date())
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "CodeAssistAI memory")
            putExtra(Intent.EXTRA_TEXT, MemoryStore.exportText(stamp))
        }
        startActivity(Intent.createChooser(send, "Memory export"))
    }

    // ---------- small views (same look as Settings) ----------

    private fun section(title: String) {
        val tv = TextView(requireContext()).apply {
            text = title
            setTextColor(0xFF5D6B7C.toInt())
            textSize = 12.5f
            letterSpacing = 0.05f
            setPadding(dp(4), dp(26), 0, dp(12))
        }
        container.addView(tv)
    }

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

    private fun inflateRow(): View =
        LayoutInflater.from(requireContext()).inflate(R.layout.item_setting_row, activeCard, false)

    private fun addRow(v: View) {
        val card = activeCard ?: return
        if (card.childCount > 0) {
            val d = View(requireContext())
            d.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(58) }
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

    private fun valueRow(icon: Int, title: String, value: String, onClick: () -> Unit) {
        val row = inflateRow()
        row.findViewById<ImageView>(R.id.rowIcon).setImageResource(icon)
        row.findViewById<TextView>(R.id.rowTitle).text = title
        row.findViewById<TextView>(R.id.rowValue).text = value
        row.setOnClickListener { onClick() }
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
