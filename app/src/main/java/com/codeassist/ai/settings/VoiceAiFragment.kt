package com.codeassist.ai.settings

import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import com.codeassist.ai.R
import com.codeassist.ai.ai.GeminiClient
import com.codeassist.ai.ai.LocalPhi
import com.codeassist.ai.ai.Modules
import com.codeassist.ai.data.Store
import com.codeassist.ai.voice.VoiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.UnknownHostException
import java.util.Locale

/**
 * Voice and AI: brain selection, the Phi-4 mini module card (download / import / load / unload /
 * delete), the Gemini key card, and the real options behind each card.
 */
class VoiceAiFragment : Fragment() {

    private class Card(val root: View) {
        val icon: ImageView = root.findViewById(R.id.moduleIcon)
        val title: TextView = root.findViewById(R.id.moduleTitle)
        val sub: TextView = root.findViewById(R.id.moduleSub)
        val badge: TextView = root.findViewById(R.id.moduleBadge)
        val detail: TextView = root.findViewById(R.id.moduleDetail)
        val progressBox: View = root.findViewById(R.id.progressBox)
        val progressLabel: TextView = root.findViewById(R.id.progressLabel)
        val progressPercent: TextView = root.findViewById(R.id.progressPercent)
        val progressBar: ProgressBar = root.findViewById(R.id.progressBar)
        val message: TextView = root.findViewById(R.id.moduleMessage)
        val buttons: LinearLayout = root.findViewById(R.id.moduleButtons)
    }

    private class Btn(
        val label: String,
        val kind: Int,
        val enabled: Boolean = true,
        val dim: Boolean = false,
        val onClick: () -> Unit
    )

    private companion object {
        const val PRIMARY = 0
        const val GHOST = 1
        const val DANGER = 2
        const val GREY = 0xFF93A1B3.toInt()
        const val BLUE = 0xFF6EC1FF.toInt()
        const val GREEN = 0xFF3FB950.toInt()
        const val PURPLE = 0xFFB49CFF.toInt()
        const val RED = 0xFFFF7B72.toInt()
        const val KEY_URL = "https://aistudio.google.com/apikey"
    }

    private lateinit var container: LinearLayout
    private var activeCard: LinearLayout? = null
    private var uiScope: CoroutineScope? = null
    private var voice: VoiceController? = null
    private var phiCard: Card? = null
    private var geminiCard: Card? = null
    private var segLocal: TextView? = null
    private var segGemini: TextView? = null
    private var brainHint: TextView? = null
    private var testingKey = false
    private var geminiMessage: String? = null
    private lateinit var pickModel: ActivityResultLauncher<Array<String>>
    private val moduleListener: () -> Unit = { renderPhi(); renderBrain() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) Modules.startImport(uri)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_voice_ai, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        Store.init(requireContext())
        Modules.init(requireContext())
        uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        container = view.findViewById(R.id.voiceAiContainer)
        view.findViewById<View>(R.id.btnBack).setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        Modules.addListener(moduleListener)
        build()
    }

    override fun onDestroyView() {
        Modules.removeListener(moduleListener)
        uiScope?.cancel()
        uiScope = null
        voice?.destroy()
        voice = null
        phiCard = null
        geminiCard = null
        segLocal = null
        segGemini = null
        brainHint = null
        super.onDestroyView()
    }

    // ---------- screen ----------

    private fun build() {
        container.removeAllViews()
        section("Brain", first = true)
        card { brainSelector() }
        phiCard = newCard()
        geminiCard = newCard()

        section("Conversation")
        card {
            valueRow(R.drawable.ic_mic, "Mic mode", micModeLabel()) { anchor, tv ->
                popup(anchor, listOf("Tap to talk", "Continuous"), micModeLabel()) {
                    Store.micMode = if (it == "Continuous") "continuous" else "tap"
                    tv.text = it
                }
            }
            valueRow(R.drawable.ic_sound, "Speak replies", speakLabel()) { anchor, tv ->
                popup(anchor, listOf("Off", "After voice messages", "Always"), speakLabel()) {
                    Store.speakReplies = when (it) {
                        "Off" -> "off"
                        "Always" -> "always"
                        else -> "voice"
                    }
                    tv.text = it
                }
            }
            switchRow(R.drawable.ic_send, "Send voice automatically", Store.autoSendVoice) {
                Store.autoSendVoice = it
            }
            valueRow(R.drawable.ic_text, "Reply length", Store.replyLength) { anchor, tv ->
                popup(anchor, listOf("Short", "Balanced", "Long"), Store.replyLength) {
                    Store.replyLength = it
                    tv.text = it
                }
            }
            noteRow(
                "Mic par tap = ek baar bolo. Mic dabaye rakho = push-to-talk: chhodte hi send, " +
                    "left slide = cancel, upar slide = continuous lock."
            )
        }

        section("Speech to text")
        card {
            infoRow(
                R.drawable.ic_mic, "Recognizer",
                if (VoiceController.recognitionAvailable(requireContext())) "Android · available" else "Not available"
            )
            valueRow(R.drawable.ic_globe, "Speech language", sttLabel()) { anchor, tv ->
                popup(anchor, sttOptions.map { it.first }, sttLabel()) { picked ->
                    Store.sttLang = sttOptions.first { it.first == picked }.second
                    tv.text = picked
                }
            }
            switchRow(R.drawable.ic_mobile, "Prefer offline recognition", Store.sttPreferOffline) {
                Store.sttPreferOffline = it
            }
            linkRow(R.drawable.ic_upload, "Offline language packs") { openVoiceInputSettings() }
        }

        section("Voice")
        card {
            valueRow(R.drawable.ic_sound, "Voice", voiceLabel()) { _, tv -> chooseVoice(tv) }
            valueRow(R.drawable.ic_tune, "Speaking speed", speedLabel()) { anchor, tv ->
                popup(anchor, speedOptions.map { it.first }, speedLabel()) { picked ->
                    Store.ttsSpeed = speedOptions.first { it.first == picked }.second
                    tv.text = picked
                }
            }
            linkRow(R.drawable.ic_sound, "Test voice") { testVoice() }
            linkRow(R.drawable.ic_settings, "System voice settings") { openTtsSettings() }
        }

        section("Privacy")
        card {
            noteRow(
                "Phi-4 mini: aapka text sirf is phone par process hota hai.\n\n" +
                    "Gemini: aapka text (aur bheji gayi image / text file) Google ko jaata hai. " +
                    "API key sirf is phone par Android Keystore se encrypted rehti hai.\n\n" +
                    "Voice: speech recognition Android / Google service karti hai. Offline pack ON ho toh " +
                    "audio phone par hi rehti hai. App audio kabhi save nahi karta."
            )
        }
        render()
    }

    private fun render() {
        renderBrain()
        renderPhi()
        renderGemini()
    }

    // ---------- brain selector ----------

    private fun brainSelector() {
        val ctx = requireContext()
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        wrap.addView(TextView(ctx).apply {
            text = "Active brain"
            setTextColor(GREY)
            textSize = 12.5f
        })
        val seg = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.seg_track)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val local = segment("On-device · Phi-4") { setProvider("phi4") }
        val cloud = segment("Cloud · Gemini") { setProvider("gemini") }
        seg.addView(local, LinearLayout.LayoutParams(0, dp(40), 1f))
        seg.addView(cloud, LinearLayout.LayoutParams(0, dp(40), 1f))
        segLocal = local
        segGemini = cloud
        wrap.addView(seg, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        val hint = TextView(ctx).apply {
            setTextColor(0xFF5D6B7C.toInt())
            textSize = 12f
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        brainHint = hint
        wrap.addView(hint, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        activeCard?.addView(wrap)
    }

    private fun segment(label: String, onClick: () -> Unit): TextView =
        TextView(requireContext()).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun setProvider(provider: String) {
        Store.brainProvider = provider
        renderBrain()
    }

    private fun renderBrain() {
        val local = Store.brainProvider != "gemini"
        styleSegment(segLocal, local)
        styleSegment(segGemini, !local)
        brainHint?.text = if (local) {
            "Offline aur private: text phone se bahar nahi jaata. Jawab CPU par bante hain, isliye thode dheere aate hain."
        } else {
            "Tez aur zyada smart, par internet chahiye aur aapka text (aur bheji gayi image / text file) Google ko jaata hai."
        }
    }

    private fun styleSegment(view: TextView?, selected: Boolean) {
        val v = view ?: return
        if (selected) {
            v.setBackgroundResource(R.drawable.seg_on)
            v.setTextColor(BLUE)
        } else {
            v.background = null
            v.setTextColor(GREY)
        }
    }

    // ---------- module cards ----------

    private fun newCard(): Card {
        val root = LayoutInflater.from(requireContext()).inflate(R.layout.item_module_card, container, false)
        root.setBackgroundResource(if (Store.glassEffect) R.drawable.glass_card else R.drawable.glass_card_solid)
        container.addView(root, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
        return Card(root)
    }

    private fun renderPhi() {
        val c = phiCard ?: return
        if (view == null) return
        val phase = Modules.phase
        c.icon.setImageResource(R.drawable.ic_sparkle)
        c.title.text = Modules.PHI_TITLE
        c.sub.text = Modules.PHI_QUANT + " · " + Modules.PHI_SIZE_LABEL + " · phone par hi chalta hai"

        when (phase) {
            Modules.Phase.NOT_IMPORTED -> setBadge(c.badge, "Not imported", GREY)
            Modules.Phase.DOWNLOADING -> setBadge(c.badge, "Downloading", BLUE)
            Modules.Phase.IMPORTING -> setBadge(c.badge, "Importing", BLUE)
            Modules.Phase.UNLOADED -> setBadge(c.badge, "Unloaded", GREY)
            Modules.Phase.LOADING -> setBadge(c.badge, "Loading", PURPLE)
            Modules.Phase.LOADED -> setBadge(c.badge, "Loaded", GREEN)
            Modules.Phase.ERROR -> setBadge(c.badge, "Error", RED)
        }

        val onDisk = phase == Modules.Phase.UNLOADED || phase == Modules.Phase.LOADING || phase == Modules.Phase.LOADED
        if (onDisk) {
            val file = Modules.modelFile()
            c.detail.visibility = View.VISIBLE
            c.detail.text = file.name + "\n" + Modules.fmt(file.length()) + " · " +
                "context " + Store.phiContext + " · " + Store.phiThreads + " threads"
        } else {
            c.detail.visibility = View.GONE
        }

        val busy = phase == Modules.Phase.DOWNLOADING || phase == Modules.Phase.IMPORTING
        c.progressBox.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) {
            val total = Modules.total
            val done = Modules.done
            val what = if (phase == Modules.Phase.DOWNLOADING) "Download" else "Copy aur check"
            if (total > 0) {
                c.progressLabel.text = what + " · " + Modules.fmt(done) + " / " + Modules.fmt(total)
                c.progressPercent.text = (done * 100 / total).toString() + "%"
                c.progressBar.progress = ((done * 1000) / total).toInt().coerceIn(0, 1000)
            } else {
                c.progressLabel.text = what + (if (done > 0) " · " + Modules.fmt(done) else "")
                c.progressPercent.text = ""
                c.progressBar.progress = 0
            }
        }

        val msg = Modules.message
        if (msg.isNullOrBlank()) {
            c.message.visibility = View.GONE
        } else {
            c.message.visibility = View.VISIBLE
            c.message.text = msg
            c.message.setTextColor(if (phase == Modules.Phase.ERROR) RED else GREY)
        }

        val options = Btn("Options", GHOST) { showPhiOptions() }
        val specs: List<Btn> = when (phase) {
            Modules.Phase.NOT_IMPORTED, Modules.Phase.ERROR -> listOf(
                Btn("Download " + Modules.PHI_SIZE_LABEL, PRIMARY) { confirmDownload() },
                Btn("Import file", GHOST) { pickModel.launch(arrayOf("*/*")) },
                options
            )
            Modules.Phase.DOWNLOADING, Modules.Phase.IMPORTING -> listOf(
                Btn("Cancel", GHOST) { Modules.cancel() }
            )
            Modules.Phase.UNLOADED -> listOf(
                Btn("Load", PRIMARY) { Modules.requestLoad() },
                Btn("Delete", DANGER) { confirmDelete() },
                options
            )
            Modules.Phase.LOADING -> listOf(
                Btn("Loading…", PRIMARY, enabled = false, dim = true) {},
                options
            )
            Modules.Phase.LOADED -> listOf(
                Btn("Unload", GHOST) { Modules.requestUnload()?.let { toast(it) } },
                Btn("Delete", DANGER, dim = true) { confirmDelete() },
                options
            )
        }
        setButtons(c.buttons, specs)
    }

    private fun confirmDownload() {
        val metered = Modules.isMetered()
        val builder = AlertDialog.Builder(requireContext())
            .setTitle("Phi-4 mini download")
            .setMessage(
                "Hugging Face se " + Modules.PHI_SIZE_LABEL + " ki file aayegi (bartowski GGUF, MIT licence). " +
                    "Android ka download manager ise chalayega: app band ho jaye tab bhi chalta rahega.\n\n" +
                    (if (metered) "Abhi mobile data par ho, " + Modules.PHI_SIZE_LABEL + " kharch hoga."
                    else "Abhi Wi-Fi par ho, theek hai.")
            )
            .setNegativeButton("Cancel", null)
        if (metered) {
            builder.setPositiveButton("Mobile data se") { _, _ -> Modules.startDownload(allowMobileData = true) }
            builder.setNeutralButton("Wi-Fi ka wait") { _, _ -> Modules.startDownload(allowMobileData = false) }
        } else {
            builder.setPositiveButton("Download") { _, _ -> Modules.startDownload(allowMobileData = false) }
        }
        builder.show()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(requireContext())
            .setTitle("Phi-4 mini delete karein?")
            .setMessage("Model file phone se hat jayegi. Dobara chahiye toh download ya import karna hoga.")
            .setPositiveButton("Delete") { _, _ -> Modules.requestDelete()?.let { toast(it) } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Gemini card ----------

    private fun renderGemini() {
        val c = geminiCard ?: return
        if (view == null) return
        val key = Store.geminiKey
        val hasKey = !key.isNullOrBlank()
        val model = Store.geminiModel.ifBlank { GeminiClient.FALLBACK_MODEL }
        c.icon.setImageResource(R.drawable.ic_globe)
        c.title.text = "Gemini API"
        c.sub.text = "Google AI · cloud · internet chahiye"

        when {
            testingKey -> setBadge(c.badge, "Testing", PURPLE)
            !hasKey -> setBadge(c.badge, "No key", GREY)
            Store.geminiKeyStatus == "verified" -> setBadge(c.badge, "Verified", GREEN)
            Store.geminiKeyStatus == "invalid" -> setBadge(c.badge, "Invalid", RED)
            else -> setBadge(c.badge, "Key saved", BLUE)
        }

        if (hasKey && key != null) {
            c.detail.visibility = View.VISIBLE
            c.detail.text = "••••••••" + key.takeLast(4) + "\nModel: " + model
        } else {
            c.detail.visibility = View.GONE
        }
        c.progressBox.visibility = View.GONE

        val msg = geminiMessage
        if (msg.isNullOrBlank()) {
            c.message.visibility = View.GONE
        } else {
            c.message.visibility = View.VISIBLE
            c.message.text = msg
            c.message.setTextColor(if (Store.geminiKeyStatus == "invalid") RED else GREY)
        }

        val specs: List<Btn> = if (!hasKey) {
            listOf(
                Btn("Add API key", PRIMARY) { showKeyDialog() },
                Btn("Get a key", GHOST) { openUrl(KEY_URL) }
            )
        } else {
            listOf(
                Btn(if (testingKey) "Testing…" else "Test key", PRIMARY, enabled = !testingKey, dim = testingKey) { testKey() },
                Btn("Change key", GHOST) { showKeyDialog() },
                Btn("Options", GHOST) { showGeminiOptions() }
            )
        }
        setButtons(c.buttons, specs)
    }

    private fun showKeyDialog() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = "Gemini API key"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(0xFFE8EEF5.toInt())
            setHintTextColor(0xFF5D6B7C.toInt())
        }
        val holder = FrameLayout(ctx).apply {
            setPadding(dp(22), dp(10), dp(22), 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Gemini API key")
            .setMessage("Key sirf is phone par encrypted save hogi. Save ke baad turant check hogi.")
            .setView(holder)
            .setPositiveButton("Save and test", null)
            .setNeutralButton("Paste", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                val clip = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                val pasted = clip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim().orEmpty()
                if (pasted.isNotEmpty()) input.setText(pasted) else toast("Clipboard khaali hai.")
            }
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val key = input.text.toString().trim()
                if (key.length < 20 || key.contains(' ')) {
                    toast("Ye API key jaisi nahi lag rahi. Poori key paste karo.")
                    return@setOnClickListener
                }
                try {
                    Store.geminiKey = key
                } catch (e: Exception) {
                    toast("Key save nahi ho payi: " + (e.message ?: "keystore error"))
                    return@setOnClickListener
                }
                Store.geminiKeyStatus = "saved"
                geminiMessage = null
                dialog.dismiss()
                renderGemini()
                testKey()
            }
        }
        dialog.show()
    }

    private fun testKey() {
        val key = Store.geminiKey
        if (key.isNullOrBlank()) {
            toast("Pehle API key daalo.")
            return
        }
        if (testingKey) return
        val scope = uiScope ?: return
        testingKey = true
        geminiMessage = "Key check ho rahi hai…"
        renderGemini()
        scope.launch {
            try {
                val models = withContext(Dispatchers.IO) { GeminiClient.listModels(key) }
                Store.geminiKeyStatus = "verified"
                Store.geminiModelList = models
                if (models.isEmpty()) {
                    geminiMessage = "Key chali, par isme koi chat model nahi mila."
                } else {
                    if (Store.geminiModel.isBlank() || !models.contains(Store.geminiModel)) {
                        Store.geminiModel = GeminiClient.pickDefault(models)
                    }
                    geminiMessage = "Key sahi hai. " + models.size + " model mile. Options mein model badal sakte ho."
                }
            } catch (e: GeminiClient.ApiError) {
                Store.geminiKeyStatus = if (e.http == 400 || e.http == 401 || e.http == 403) "invalid" else "saved"
                geminiMessage = e.message
            } catch (e: UnknownHostException) {
                geminiMessage = "Internet nahi mil raha. Connection check karke dobara Test key dabao."
            } catch (e: Exception) {
                geminiMessage = "Test fail hua: " + (e.message ?: e.javaClass.simpleName)
            }
            testingKey = false
            if (view != null) {
                renderGemini()
                renderBrain()
            }
        }
    }

    private fun showGeminiOptions() {
        val items = listOf(
            "Model · " + Store.geminiModel.ifBlank { GeminiClient.FALLBACK_MODEL },
            "Temperature · " + fmtF(Store.geminiTemp),
            "Reply length · " + Store.replyLength,
            "System prompt · edit",
            "Remove API key"
        )
        AlertDialog.Builder(requireContext())
            .setTitle("Gemini options")
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> chooseGeminiModel()
                    1 -> choose("Temperature", listOf("0.2", "0.7", "1.0"), fmtF(Store.geminiTemp)) {
                        Store.geminiTemp = it.toFloat()
                        showGeminiOptions()
                    }
                    2 -> choose("Reply length", listOf("Short", "Balanced", "Long"), Store.replyLength) {
                        Store.replyLength = it
                        showGeminiOptions()
                    }
                    3 -> editSystemPrompt { showGeminiOptions() }
                    4 -> confirmRemoveKey()
                }
            }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun chooseGeminiModel() {
        val models = Store.geminiModelList
        if (models.isEmpty()) {
            toast("Pehle Test key chalao, tab model ki list aati hai.")
            return
        }
        choose("Gemini model", models, Store.geminiModel.ifBlank { GeminiClient.FALLBACK_MODEL }) {
            Store.geminiModel = it
            renderGemini()
            showGeminiOptions()
        }
    }

    private fun confirmRemoveKey() {
        AlertDialog.Builder(requireContext())
            .setTitle("API key hata dein?")
            .setMessage("Key is phone se delete ho jayegi. Gemini dobara chahiye toh nayi key daalni hogi.")
            .setPositiveButton("Remove") { _, _ ->
                Store.geminiKey = null
                Store.geminiKeyStatus = "none"
                Store.geminiModelList = emptyList()
                geminiMessage = null
                renderGemini()
                renderBrain()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Phi options ----------

    private fun showPhiOptions() {
        val items = listOf(
            "Context size · " + Store.phiContext,
            "CPU threads · " + Store.phiThreads,
            "Temperature · " + fmtF(Store.phiTemp),
            "Reply length · " + Store.replyLength,
            "System prompt · edit"
        )
        AlertDialog.Builder(requireContext())
            .setTitle("Phi-4 mini options")
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> choose("Context size (tokens)", listOf("1024", "2048", "3072", "4096"), Store.phiContext.toString()) {
                        Store.phiContext = it.toInt()
                        needsReload()
                    }
                    1 -> choose("CPU threads", listOf("2", "3", "4", "5", "6", "8"), Store.phiThreads.toString()) {
                        Store.phiThreads = it.toInt()
                        needsReload()
                    }
                    2 -> choose("Temperature", listOf("0.2", "0.5", "0.7", "1.0"), fmtF(Store.phiTemp)) {
                        Store.phiTemp = it.toFloat()
                        needsReload()
                    }
                    3 -> choose("Reply length", listOf("Short", "Balanced", "Long"), Store.replyLength) {
                        Store.replyLength = it
                        showPhiOptions()
                    }
                    4 -> editSystemPrompt { showPhiOptions() }
                }
            }
            .setNegativeButton("Done", null)
            .show()
    }

    /** Context size, threads and temperature are baked in when the model is loaded. */
    private fun needsReload() {
        if (LocalPhi.loaded) {
            toast("Ye agle Load par apply hoga. Pehle Unload, phir Load karo.")
        }
        renderPhi()
        showPhiOptions()
    }

    // ---------- shared dialogs ----------

    private fun choose(title: String, options: List<String>, current: String, onPick: (String) -> Unit) {
        val index = options.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), index) { dialog, which ->
                dialog.dismiss()
                onPick(options[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editSystemPrompt(after: () -> Unit) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            setText(Store.systemPrompt)
            minLines = 4
            maxLines = 10
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(0xFFE8EEF5.toInt())
            textSize = 13.5f
        }
        val holder = FrameLayout(ctx).apply {
            setPadding(dp(22), dp(10), dp(22), 0)
            addView(input)
        }
        AlertDialog.Builder(ctx)
            .setTitle("System prompt")
            .setView(holder)
            .setPositiveButton("Save") { _, _ ->
                Store.systemPrompt = input.text.toString().trim()
                after()
            }
            .setNeutralButton("Reset") { _, _ ->
                Store.systemPrompt = ""
                after()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- voice rows ----------

    private val sttOptions = listOf(
        "English (India) · Roman Hinglish" to "en-IN",
        "Hindi · Devanagari" to "hi-IN",
        "English (US)" to "en-US"
    )

    private val speedOptions = listOf(
        "Slow (0.75x)" to 0.75f,
        "Normal (1x)" to 1.0f,
        "Fast (1.25x)" to 1.25f,
        "Faster (1.5x)" to 1.5f
    )

    private fun micModeLabel() = if (Store.micMode == "continuous") "Continuous" else "Tap to talk"

    private fun speakLabel() = when (Store.speakReplies) {
        "off" -> "Off"
        "always" -> "Always"
        else -> "After voice messages"
    }

    private fun sttLabel() = sttOptions.firstOrNull { it.second == Store.sttLang }?.first ?: sttOptions[0].first

    private fun speedLabel(): String =
        speedOptions.minByOrNull { kotlin.math.abs(it.second - Store.ttsSpeed) }?.first ?: speedOptions[1].first

    private fun voiceLabel(): String = if (Store.ttsVoice.isBlank()) "Auto" else Store.ttsVoice.take(22)

    private fun chooseVoice(valueView: TextView) {
        toast("Voices dhoondh raha hoon…")
        VoiceController.queryVoices(requireContext()) { list ->
            if (view == null) return@queryVoices
            if (list.isEmpty()) {
                toast("Koi Hindi / English voice nahi mili. Phone ki TTS settings check karo.")
                return@queryVoices
            }
            val labels = listOf("Auto (Hindi / Indian English)") + list.map { it.label }
            val names = listOf("") + list.map { it.name }
            val current = names.indexOf(Store.ttsVoice).coerceAtLeast(0)
            AlertDialog.Builder(requireContext())
                .setTitle("Voice")
                .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
                    Store.ttsVoice = names[which]
                    valueView.text = voiceLabel()
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun testVoice() {
        val v = voice ?: VoiceController(requireContext(), object : VoiceController.Callbacks {
            override fun onState(state: VoiceController.State, mode: VoiceController.Mode) {}
            override fun onPartial(text: String) {}
            override fun onLevel(level: Float) {}
            override fun onFinalText(text: String, durationMs: Long) {}
            override fun onMessage(text: String) = toast(text)
            override fun onNeedPermission() {}
        }).also { voice = it }
        v.sample("Namaste, main CodeAssist AI hoon. Ye meri awaaz ka test hai.")
    }

    private fun openVoiceInputSettings() {
        try {
            startActivity(Intent("android.settings.VOICE_INPUT_SETTINGS"))
        } catch (_: Exception) {
            toast("Is phone par ye settings screen nahi mili. Settings mein Google, phir Voice dekho.")
        }
    }

    private fun openTtsSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (_: Exception) {
            toast("Is phone par TTS settings screen nahi mili.")
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            toast("Browser nahi mila.")
        }
    }

    // ---------- small UI helpers ----------

    private fun setBadge(view: TextView, label: String, color: Int) {
        view.text = label
        view.setTextColor(color)
        view.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.argb(38, Color.red(color), Color.green(color), Color.blue(color)))
            setStroke(dp(1), Color.argb(90, Color.red(color), Color.green(color), Color.blue(color)))
        }
    }

    private fun setButtons(box: LinearLayout, specs: List<Btn>) {
        box.removeAllViews()
        for ((i, spec) in specs.withIndex()) {
            val button = TextView(requireContext()).apply {
                text = spec.label
                textSize = 13f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(8), 0, dp(8), 0)
                isClickable = true
                isFocusable = true
                when (spec.kind) {
                    PRIMARY -> {
                        setBackgroundResource(R.drawable.btn_primary)
                        setTextColor(0xFFEAF6FF.toInt())
                    }
                    DANGER -> {
                        setBackgroundResource(R.drawable.btn_danger)
                        setTextColor(RED)
                    }
                    else -> {
                        setBackgroundResource(R.drawable.chip_small)
                        setTextColor(0xFF93A1B3.toInt())
                    }
                }
                alpha = if (spec.dim || !spec.enabled) 0.5f else 1f
                setOnClickListener { if (spec.enabled) spec.onClick() }
            }
            val lp = LinearLayout.LayoutParams(0, dp(42), 1f)
            if (i < specs.size - 1) lp.marginEnd = dp(8)
            box.addView(button, lp)
        }
    }

    private fun section(title: String, first: Boolean = false) {
        container.addView(TextView(requireContext()).apply {
            text = title
            setTextColor(0xFF5D6B7C.toInt())
            textSize = 12.5f
            letterSpacing = 0.05f
            setPadding(dp(4), if (first) dp(10) else dp(30), 0, dp(14))
        })
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
            val divider = View(requireContext())
            divider.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                marginStart = dp(58)
            }
            divider.setBackgroundResource(R.drawable.divider)
            card.addView(divider)
        }
        card.addView(v)
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

    private fun linkRow(icon: Int, title: String, onClick: () -> Unit) {
        val row = inflateRow()
        row.findViewById<ImageView>(R.id.rowIcon).setImageResource(icon)
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
            setTextColor(GREY)
        }
        row.findViewById<ImageView>(R.id.rowChevron).visibility = View.GONE
        row.findViewById<SwitchCompat>(R.id.rowSwitch).visibility = View.GONE
        row.isClickable = false
        addRow(row)
    }

    private fun noteRow(text: String) {
        val note = TextView(requireContext()).apply {
            this.text = text
            setTextColor(GREY)
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
            onPick(options[it.itemId])
            true
        }
        menu.show()
    }

    private fun fmtF(value: Float): String = String.format(Locale.US, "%.1f", value)

    private fun toast(text: String) {
        if (context == null) return
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
