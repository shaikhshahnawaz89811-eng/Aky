package com.codeassist.ai.settings

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.codeassist.ai.R
import com.codeassist.ai.ai.ConvKpi
import com.codeassist.ai.ai.ElevenLabsClient
import com.codeassist.ai.ai.GeminiClient
import com.codeassist.ai.ai.LocalLlm
import com.codeassist.ai.ai.Modules
import com.codeassist.ai.data.Store
import com.codeassist.ai.service.AssistantService
import com.codeassist.ai.service.BatterySetup
import com.codeassist.ai.service.HandsFree
import com.codeassist.ai.service.HealthCheck
import com.codeassist.ai.voice.VoiceController
import com.codeassist.ai.voice.WakeCoordinator
import com.codeassist.ai.voice.WakeMatcher
import com.codeassist.ai.voice.WakeSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.UnknownHostException
import java.util.Locale

/**
 * Voice and AI: brain selection, the Qwen2.5 1.5B module card (download / import / load / unload /
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
        const val WAKE_CONSENT_VERSION = 1
        const val PRIMARY = 0
        const val GHOST = 1
        const val DANGER = 2
        const val GREY = 0xFF93A1B3.toInt()
        const val BLUE = 0xFF6EC1FF.toInt()
        const val GREEN = 0xFF3FB950.toInt()
        const val PURPLE = 0xFFB49CFF.toInt()
        const val RED = 0xFFFF7B72.toInt()
        const val KEY_URL = "https://aistudio.google.com/apikey"
        const val ELEVEN_KEY_URL = "https://elevenlabs.io/app/settings/api-keys"
    }

    private lateinit var container: LinearLayout
    private var activeCard: LinearLayout? = null
    private var uiScope: CoroutineScope? = null
    private var voice: VoiceController? = null
    private var localCard: Card? = null
    private var geminiCard: Card? = null
    private var elevenCard: Card? = null
    private var testingEleven = false
    private var voiceValue: TextView? = null
    private var voiceTypeValue: TextView? = null
    private var elevenMessage: String? = null
    private var segLocal: TextView? = null
    private var segGemini: TextView? = null
    private var brainHint: TextView? = null
    private var testingKey = false
    private var geminiMessage: String? = null
    private lateinit var pickModel: ActivityResultLauncher<Array<String>>
    private lateinit var wakePermissions: ActivityResultLauncher<Array<String>>
    private var wakeSwitch: SwitchCompat? = null
    private var settingWakeSwitch = false
    private val moduleListener: () -> Unit = { renderLocal(); renderBrain() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) Modules.startImport(uri)
        }
        wakePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            if (view != null) {
                if (HandsFree.hasMic(requireContext())) {
                    finishEnableWake()
                } else {
                    toast("Mic permission ke bina wake word nahi chalega.")
                    setWakeSwitch(false)
                }
            }
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
        localCard = null
        geminiCard = null
        elevenCard = null
        voiceValue = null
        voiceTypeValue = null
        wakeSwitch = null
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
        localCard = newCard()
        geminiCard = newCard()

        section("Reliability and local reading")
        card {
            switchRow(R.drawable.ic_globe, "Gemini se Gemma automatic fallback", Store.automaticFallback) {
                Store.automaticFallback = it
            }
            noteRow(
                "Sirf timeout, network, rate-limit ya server failure par is turn ke liye installed Gemma chalta hai. " +
                    "Aapka selected brain Gemini hi rahega; agli turn par Gemini phir try hoga."
            )
            switchRow(R.drawable.ic_image, "Gemma ke liye attached-image OCR", Store.localScreenshotOcr) {
                Store.localScreenshotOcr = it
            }
            noteRow(
                "Optional Latin / English text OCR phone par hota hai. OCR text sirf Gemma prompt mein jaata hai; " +
                    "attachments par phone-action tools phir bhi band rehte hain."
            )
        }

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

        section("Natural conversation")
        card {
            valueRow(R.drawable.ic_history, "Follow-up window", followUpLabel()) { anchor, tv ->
                popup(anchor, followUpOptions.map { it.first }, followUpLabel()) { picked ->
                    Store.followUp = followUpOptions.first { it.first == picked }.second
                    tv.text = picked
                }
            }
            valueRow(R.drawable.ic_sound, "Beech mein bolna (barge-in)", bargeLabel()) { anchor, tv ->
                popup(anchor, bargeOptions.map { it.first }, bargeLabel()) { picked ->
                    Store.bargeIn = bargeOptions.first { it.first == picked }.second
                    tv.text = picked
                }
            }
            switchRow(R.drawable.ic_tune, "Adhoora sentence ruk kar sunna", Store.smartEndpoint) {
                Store.smartEndpoint = it
            }
            switchRow(R.drawable.ic_chat, "Soch rahe ho toh \"ek second\" bolna", Store.fillers) {
                Store.fillers = it
            }
            noteRow(
                "Follow-up: jawab bolne ke baad mic khud thodi der khula rehta hai (silence = chup-chaap band). " +
                    "Barge-in: assistant bol raha ho tab aap bolo toh wo ruk jaata hai; \"aage batao\" se wahin se dobara. " +
                    "Is phone par echo cancellation: " + (if (com.codeassist.ai.voice.BargeInDetector.aecAvailable()) "hai" else "nahi hai") +
                    ". Speaker par aur AEC ke bina false interrupt ho sakta hai: headset ya Strict use karo. " +
                    "Barge-in ke dauran mic sirf naapa jaata hai, record ya save nahi hota."
            )
        }

        section("Hands-free (wake word)")
        card {
            wakeSwitchRow()
            valueRow(R.drawable.ic_mic, "Wake phrase", Store.wakePhrase) { _, tv -> editWakePhrase(tv) }
            valueRow(R.drawable.ic_tune, "Sensitivity", wakeLevelLabel()) { anchor, tv ->
                popup(anchor, wakeLevelOptions.map { it.first }, wakeLevelLabel()) { picked ->
                    Store.wakeSensitivity = wakeLevelOptions.first { it.first == picked }.second
                    tv.text = picked
                }
            }
            infoRow(R.drawable.ic_history, "Status", wakeStatusText())
            ConvKpi.init(requireContext())
            infoRow(R.drawable.ic_bell, "Health", HealthCheck.summary())
            valueRow(
                R.drawable.ic_shield, "Battery setup",
                if (BatterySetup.isUnrestricted(requireContext())) "Theek" else "Zaroori"
            ) { _, _ -> BatteryDialogs.showGuide(requireContext()) }
            valueRow(R.drawable.ic_mic, "Wake test", "10 try") { _, _ -> WakeTestDialog.show(requireContext()) }
            noteRow(
                "Opt-in. Awaaz sirf phone ke on-device recognizer se suni jaati hai: record nahi hoti, network par nahi jaati " +
                    "(Android 13+ aur offline English India speech pack chahiye). Mic tab tak chalu rehta hai jab tak notification " +
                    "dikhti hai; Stop wahin se dabao. Ye asli keyword-spotter nahi hai: phrase ka pehla shabd aksar kat jaata hai, " +
                    "isliye Normal level \"code assist\" ya sirf \"assist\" bhi pakad leta hai. False trigger zyada lagein toh Strict karo. " +
                    "Xiaomi / Oppo / Vivo / Samsung ka battery saver service band kar sakta hai: app kholte hi wo dobara chalu ho jaati hai, " +
                    "aur ~15 minute mein ek notification bhi aati hai. Health line batati hai Android ne kitni baar band kiya; " +
                    "Battery setup se phone ki settings sahi karo. Wake test se pata chalta hai phrase 10 mein se kitni baar pakda gaya."
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
            voiceValue = valueRow(R.drawable.ic_sound, "Voice", voiceLabel()) { _, tv ->
                if (Store.elevenActive()) chooseElevenVoice() else chooseVoice(tv)
            }
            voiceTypeValue = valueRow(R.drawable.ic_sound, "Voice type (ElevenLabs)", voiceTypeLabel()) { anchor, _ ->
                popup(anchor, voiceTypeOptions.map { it.first }, voiceTypeLabel()) { picked ->
                    setVoiceType(voiceTypeOptions.first { it.first == picked }.second)
                }
            }
            valueRow(R.drawable.ic_tune, "Speaking speed", speedLabel()) { anchor, tv ->
                popup(anchor, speedOptions.map { it.first }, speedLabel()) { picked ->
                    Store.ttsSpeed = speedOptions.first { it.first == picked }.second
                    tv.text = picked
                }
            }
            linkRow(R.drawable.ic_sound, "Test voice") { testVoice() }
            linkRow(R.drawable.ic_settings, "System voice settings") { openTtsSettings() }
        }
        elevenCard = newCard()

        section("Privacy")
        card {
            noteRow(
                "Gemma 4 (on-device): aapka text sirf is phone par process hota hai.\n\n" +
                    "Gemini: aapka text (aur bheji gayi image / text file) Google ko jaata hai. " +
                    "API key sirf is phone par Android Keystore se encrypted rehti hai.\n\n" +
                    "Gemma screenshot OCR ON ho toh image ka pehchana hua text isi phone par banta hai. " +
                    "Ye OCR setting Gemini ko bheje gaye original attachments ko nahi badalti.\n\n" +
                    "ElevenLabs voice (ON ho toh): jo reply bolna hai wo text ElevenLabs ko jaata hai. Key sirf is phone par encrypted rehti hai, " +
                    "app mein build ke saath nahi aati.\n\n" +
                    "Voice: speech recognition Android / Google service karti hai. Offline pack ON ho toh " +
                    "audio phone par hi rehti hai. App audio kabhi save nahi karta."
            )
        }
        render()
    }

    private fun render() {
        renderBrain()
        renderLocal()
        renderGemini()
        renderEleven()
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
        val local = segment("On-device · Gemma") { setProvider("local") }
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
            "Offline aur private: text phone se bahar nahi jaata. Chhota 1.5B model hai: jawab CPU par bante hain (dheere), tasveer nahi padhta, aur Hinglish / lambi coding mein Gemini jitna pakka nahi."
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

    private fun renderLocal() {
        val c = localCard ?: return
        if (view == null) return
        val phase = Modules.phase
        c.icon.setImageResource(R.drawable.ic_sparkle)
        c.title.text = Modules.MODEL_TITLE
        c.sub.text = Modules.MODEL_QUANT + " · " + Modules.MODEL_SIZE_LABEL + " · phone par hi chalta hai"

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
                "context " + Store.localContext + " · " + Store.localThreads + " threads"
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
        val oldPhi = Modules.legacyPhiBytes()
        if (!msg.isNullOrBlank()) {
            c.message.visibility = View.VISIBLE
            c.message.text = msg
            c.message.setTextColor(if (phase == Modules.Phase.ERROR) RED else GREY)
        } else if (oldPhi > 0L) {
            c.message.visibility = View.VISIBLE
            c.message.text = "Purani Phi-4 file (" + Modules.fmt(oldPhi) + ") phone mein padi hai, ab kaam nahi aati. " +
                "Options mein se delete karke jagah khaali karo."
            c.message.setTextColor(GREY)
        } else {
            c.message.visibility = View.GONE
        }

        val options = Btn("Options", GHOST) { showLocalOptions() }
        val specs: List<Btn> = when (phase) {
            Modules.Phase.NOT_IMPORTED, Modules.Phase.ERROR -> listOf(
                Btn("Download " + Modules.MODEL_SIZE_LABEL, PRIMARY) { confirmDownload() },
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
            .setTitle("Gemma 4 E2B download")
            .setMessage(
                "Hugging Face se " + Modules.MODEL_SIZE_LABEL + " ki file aayegi (Google litert-community, Apache-2.0 licence). " +
                    "Android ka download manager ise chalayega: app band ho jaye tab bhi chalta rahega.\n\n" +
                    (if (metered) "Abhi mobile data par ho, " + Modules.MODEL_SIZE_LABEL + " kharch hoga."
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
            .setTitle("Gemma 4 E2B delete karein?")
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

    // ---------- ElevenLabs card ----------

    private fun elevenModelLabel(): String = when (Store.elevenModel) {
        "eleven_flash_v2_5" -> "Flash v2.5"
        "eleven_multilingual_v2" -> "Multilingual v2"
        else -> Store.elevenModel
    }

    private fun renderEleven() {
        val c = elevenCard ?: return
        if (view == null) return
        val key = Store.elevenKey
        val hasKey = !key.isNullOrBlank()
        c.icon.setImageResource(R.drawable.ic_sound)
        c.title.text = "ElevenLabs voice"
        c.sub.text = "Natural awaaz · cloud · internet chahiye"

        when {
            testingEleven -> setBadge(c.badge, "Testing", PURPLE)
            !hasKey -> setBadge(c.badge, "No key", GREY)
            Store.elevenKeyStatus == "invalid" -> setBadge(c.badge, "Invalid", RED)
            Store.elevenActive() -> setBadge(c.badge, "In use", GREEN)
            Store.elevenKeyStatus == "verified" -> setBadge(c.badge, "Verified", BLUE)
            else -> setBadge(c.badge, "Key saved", BLUE)
        }

        if (hasKey && key != null) {
            c.detail.visibility = View.VISIBLE
            c.detail.text = "••••••••" + key.takeLast(4) + "\nVoice: " + Store.elevenVoiceName + " · Model: " + elevenModelLabel() +
                "\nAb replies bolega: " + (if (Store.elevenActive()) "ElevenLabs" else "Phone ki awaaz")
        } else {
            c.detail.visibility = View.GONE
        }
        c.progressBox.visibility = View.GONE

        val msg = elevenMessage
        if (msg.isNullOrBlank()) {
            c.message.visibility = View.GONE
        } else {
            c.message.visibility = View.VISIBLE
            c.message.text = msg
            c.message.setTextColor(if (Store.elevenKeyStatus == "invalid") RED else GREY)
        }

        val specs: List<Btn> = if (!hasKey) {
            listOf(
                Btn("Add API key", PRIMARY) { showElevenKeyDialog() },
                Btn("Get a key", GHOST) { openUrl(ELEVEN_KEY_URL) }
            )
        } else {
            listOf(
                Btn(if (testingEleven) "Testing…" else "Test key", PRIMARY, enabled = !testingEleven, dim = testingEleven) { testElevenKey() },
                Btn("Change key", GHOST) { showElevenKeyDialog() },
                Btn("Options", GHOST) { showElevenOptions() }
            )
        }
        setButtons(c.buttons, specs)
    }

    private fun showElevenKeyDialog() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = "ElevenLabs API key"
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
            .setTitle("ElevenLabs API key")
            .setMessage(
                "Key sirf is phone par encrypted save hogi, app ke saath kahin nahi jaati. " +
                    "Key ko text-to-speech ki permission honi chahiye. Save ke baad ek chhota test chalega (2 characters ka credit)."
            )
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
                    Store.elevenKey = key
                } catch (e: Exception) {
                    toast("Key save nahi ho payi: " + (e.message ?: "keystore error"))
                    return@setOnClickListener
                }
                Store.elevenKeyStatus = "saved"
                elevenMessage = null
                dialog.dismiss()
                renderEleven()
                testElevenKey()
            }
        }
        dialog.show()
    }

    private fun testElevenKey() {
        val key = Store.elevenKey
        if (key.isNullOrBlank()) {
            toast("Pehle API key daalo.")
            return
        }
        if (testingEleven) return
        val scope = uiScope ?: return
        testingEleven = true
        elevenMessage = "Key check ho rahi hai…"
        renderEleven()
        val voiceId = Store.elevenVoiceId
        val model = Store.elevenModel
        scope.launch {
            try {
                withContext(Dispatchers.IO) { ElevenLabsClient.probe(key, voiceId, model) }
                Store.elevenKeyStatus = "verified"
                if (Store.ttsEngine != "eleven") {
                    Store.ttsEngine = "eleven"
                    elevenMessage = "Key sahi hai. Ab replies ElevenLabs ki awaaz mein bolenge. Band karna ho toh Options."
                } else {
                    elevenMessage = "Key sahi hai."
                }
            } catch (e: ElevenLabsClient.ApiError) {
                Store.elevenKeyStatus = if (ElevenLabsClient.isAuthFailure(e)) "invalid" else "saved"
                elevenMessage = e.message
            } catch (e: UnknownHostException) {
                elevenMessage = "Internet nahi mil raha. Connection check karke dobara Test key dabao."
            } catch (e: Exception) {
                elevenMessage = "Test fail hua: " + (e.message ?: e.javaClass.simpleName)
            }
            testingEleven = false
            if (view != null) renderEleven()
        }
    }

    private fun showElevenOptions() {
        val engineOn = Store.ttsEngine == "eleven"
        val labels = arrayOf(
            "ElevenLabs awaaz · " + (if (engineOn) "ON" else "OFF"),
            "Voice · " + Store.elevenVoiceName,
            "Voice type · " + voiceTypeLabel(),
            "Remove key"
        )
        AlertDialog.Builder(requireContext())
            .setTitle("ElevenLabs options")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> {
                        Store.ttsEngine = if (engineOn) "android" else "eleven"
                        elevenMessage = null
                        renderEleven()
                    }
                    1 -> chooseElevenVoice()
                    2 -> {
                        val anchor = elevenCard?.root
                        if (anchor != null) {
                            popup(anchor, voiceTypeOptions.map { it.first }, voiceTypeLabel()) { picked ->
                                setVoiceType(voiceTypeOptions.first { it.first == picked }.second)
                            }
                        }
                    }
                    3 -> confirmRemoveElevenKey()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun confirmRemoveElevenKey() {
        AlertDialog.Builder(requireContext())
            .setTitle("ElevenLabs key hata dein?")
            .setMessage("Key is phone se delete ho jayegi aur replies phone ki awaaz mein bolenge.")
            .setPositiveButton("Remove") { _, _ ->
                Store.elevenKey = null
                Store.elevenKeyStatus = "none"
                Store.ttsEngine = "android"
                elevenMessage = null
                renderEleven()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Qwen options ----------

    private fun showLocalOptions() {
        val items = ArrayList<String>()
        items.add("Context size · " + Store.localContext)
        items.add("CPU threads · " + Store.localThreads)
        items.add("Temperature · " + fmtF(Store.localTemp))
        items.add("Reply length · " + Store.replyLength)
        items.add("System prompt · edit")
        items.add("Prompt template · " + templateLabel())
        val oldPhi = Modules.legacyPhiBytes()
        if (oldPhi > 0L) items.add("Purani Phi-4 file delete · " + Modules.fmt(oldPhi))
        AlertDialog.Builder(requireContext())
            .setTitle("Gemma 4 E2B options")
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> choose("Context size (tokens)", listOf("2048", "4096", "6144", "8192"), Store.localContext.toString()) {
                        Store.localContext = it.toInt()
                        needsReload()
                    }
                    1 -> choose("CPU threads", listOf("2", "3", "4", "5", "6", "8"), Store.localThreads.toString()) {
                        Store.localThreads = it.toInt()
                        needsReload()
                    }
                    2 -> choose("Temperature", listOf("0.2", "0.3", "0.5", "0.7", "1.0"), fmtF(Store.localTemp)) {
                        Store.localTemp = it.toFloat()
                        needsReload()
                    }
                    3 -> choose("Reply length", listOf("Short", "Balanced", "Long"), Store.replyLength) {
                        Store.replyLength = it
                        showLocalOptions()
                    }
                    4 -> editSystemPrompt { showLocalOptions() }
                    5 -> chooseTemplate()
                    6 -> confirmDeleteOldPhi()
                }
            }
            .setNegativeButton("Done", null)
            .show()
    }

    /** "auto" plus what the last test chose, for example "auto → chatml". */
    private fun templateLabel(): String {
        val t = Store.localTemplate
        if (t != "auto") return t
        val r = Store.localTemplateResult
        return if (r.isBlank()) "auto (test baaki)" else "auto → $r"
    }

    /**
     * Offline answers are asked in one of three prompt shapes (chatml / lib / plain). "auto" tests them once on
     * this phone with a tiny "Hello" before the next offline reply; the result is on the KPI screen.
     */
    private fun chooseTemplate() {
        choose("Prompt template", listOf("auto", "chatml", "lib", "plain"), Store.localTemplate) {
            Store.localTemplate = it
            // a fresh test on the next offline reply, also when "auto" is picked again
            Store.localTemplateSig = ""
            toast(
                if (it == "auto") "Agli offline reply se pehle ek chhota test chalega (lagbhag 1 minute, ek baar)."
                else "Template badal gaya: $it"
            )
            showLocalOptions()
        }
    }

    private fun confirmDeleteOldPhi() {
        val bytes = Modules.legacyPhiBytes()
        AlertDialog.Builder(requireContext())
            .setTitle("Purani Phi-4 file delete karein?")
            .setMessage(
                "Phi-4 mini ab app mein nahi hai. Is file se " + Modules.fmt(bytes) +
                    " jagah khaali hogi. Wapas chahiye toh dobara download karni padegi."
            )
            .setPositiveButton("Delete") { _, _ ->
                if (!Modules.deleteLegacyPhi()) toast("File delete nahi ho payi.")
                renderLocal()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Context size, threads and temperature are baked in when the model is loaded. */
    private fun needsReload() {
        if (LocalLlm.loaded) {
            toast("Ye agle Load par apply hoga. Pehle Unload, phir Load karo.")
        }
        renderLocal()
        showLocalOptions()
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

    private val followUpOptions = listOf("Off" to "off", "Normal (8 s)" to "normal", "Long (20 s)" to "long")
    private val bargeOptions = listOf("Off" to "off", "Normal" to "normal", "Strict" to "strict")

    private fun followUpLabel() = followUpOptions.firstOrNull { it.second == Store.followUp }?.first ?: followUpOptions[1].first

    private fun bargeLabel() = bargeOptions.firstOrNull { it.second == Store.bargeIn }?.first ?: bargeOptions[0].first

    private fun micModeLabel() = if (Store.micMode == "continuous") "Continuous" else "Tap to talk"

    private fun speakLabel() = when (Store.speakReplies) {
        "off" -> "Off"
        "always" -> "Always"
        else -> "After voice messages"
    }

    private fun sttLabel() = sttOptions.firstOrNull { it.second == Store.sttLang }?.first ?: sttOptions[0].first

    private fun speedLabel(): String =
        speedOptions.minByOrNull { kotlin.math.abs(it.second - Store.ttsSpeed) }?.first ?: speedOptions[1].first

    private fun voiceLabel(): String = when {
        Store.elevenActive() -> "ElevenLabs · " + Store.elevenVoiceName.take(18)
        Store.ttsVoice.isBlank() -> "Auto"
        else -> Store.ttsVoice.take(22)
    }

    private val voiceTypeOptions = listOf("Any" to "any", "Female" to "female", "Male" to "male")

    private fun voiceTypeLabel(): String =
        voiceTypeOptions.firstOrNull { it.second == Store.elevenGender }?.first ?: "Any"

    private fun refreshVoiceRows() {
        voiceValue?.text = voiceLabel()
        voiceTypeValue?.text = voiceTypeLabel()
        renderEleven()
    }

    // ---------- ElevenLabs voices: male / female + picker (part 2) ----------

    /**
     * Gives the voice list through [done] on the main thread. Uses the saved list unless [force]; asks ElevenLabs
     * otherwise. If the list cannot be loaded (key without voices permission, no internet) the two basic voices are
     * returned together with a short reason so the screen still works.
     */
    private fun loadElevenVoices(force: Boolean, done: (List<ElevenLabsClient.Voice>, String?) -> Unit) {
        val saved = Store.elevenVoices
        if (!force && saved.isNotEmpty()) {
            done(saved, null)
            return
        }
        val key = Store.elevenKey
        val scope = uiScope
        if (key.isNullOrBlank() || scope == null) {
            done(ElevenLabsClient.FALLBACK_VOICES, "Pehle ElevenLabs key daalo.")
            return
        }
        scope.launch {
            var reason: String? = null
            val list: List<ElevenLabsClient.Voice> = try {
                val loaded = withContext(Dispatchers.IO) { ElevenLabsClient.listVoices(key) }
                if (loaded.isEmpty()) {
                    reason = "Account mein koi voice nahi mili."
                    ElevenLabsClient.FALLBACK_VOICES
                } else {
                    Store.elevenVoices = loaded
                    loaded
                }
            } catch (e: ElevenLabsClient.ApiError) {
                reason = if (e.http == 401 || e.http == 403) "Is key se voice list nahi khul rahi (voices permission chahiye)." else e.message
                ElevenLabsClient.FALLBACK_VOICES
            } catch (e: UnknownHostException) {
                reason = "Internet nahi mil raha."
                ElevenLabsClient.FALLBACK_VOICES
            } catch (e: Exception) {
                reason = "Voice list nahi aayi: " + (e.message ?: e.javaClass.simpleName)
                ElevenLabsClient.FALLBACK_VOICES
            }
            if (view != null) done(list, reason)
        }
    }

    private fun chooseElevenVoice() {
        if (Store.elevenKey.isNullOrBlank()) {
            toast("Pehle ElevenLabs key daalo.")
            return
        }
        toast("Voices la raha hoon…")
        loadElevenVoices(false) { list, reason ->
            if (reason != null) toast(reason + " Basic voices dikha raha hoon.")
            val g = Store.elevenGender
            val shown = list.filter { g == "any" || it.gender == g }
            if (shown.isEmpty()) {
                toast("Is type ki koi voice nahi mili. Voice type ko Any karke dekho.")
                return@loadElevenVoices
            }
            val labels = shown.map { it.label() }.toTypedArray()
            val current = shown.indexOfFirst { it.id == Store.elevenVoiceId }.coerceAtLeast(0)
            AlertDialog.Builder(requireContext())
                .setTitle("Voice · " + voiceTypeLabel())
                .setSingleChoiceItems(labels, current) { dialog, which ->
                    dialog.dismiss()
                    selectElevenVoice(shown[which])
                }
                .setNeutralButton("Refresh list") { _, _ ->
                    loadElevenVoices(true) { _, r ->
                        toast(r ?: "Voice list naye sire se aa gayi.")
                        chooseElevenVoice()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /** Saves the voice, makes sure ElevenLabs is the active engine, and plays a short sample in it. */
    private fun selectElevenVoice(v: ElevenLabsClient.Voice) {
        Store.elevenVoiceId = v.id
        Store.elevenVoiceName = v.name
        if (Store.ttsEngine != "eleven" && Store.elevenKeyStatus != "invalid" && !Store.elevenKey.isNullOrBlank()) {
            Store.ttsEngine = "eleven"
        }
        refreshVoiceRows()
        testVoice()
    }

    /** Male / Female / Any. A gendered choice also switches the current voice if it is the other kind. */
    private fun setVoiceType(type: String) {
        Store.elevenGender = type
        refreshVoiceRows()
        if (type == "any") return
        if (Store.elevenKey.isNullOrBlank()) {
            toast("Voice type save ho gaya. Key daalte hi ye voice type lagega.")
            return
        }
        loadElevenVoices(false) { list, reason ->
            if (reason != null) toast(reason + " Basic voices se chun raha hoon.")
            val current = list.firstOrNull { it.id == Store.elevenVoiceId }
            if (current != null && current.gender == type) {
                if (Store.ttsEngine != "eleven" && Store.elevenKeyStatus != "invalid") Store.ttsEngine = "eleven"
                refreshVoiceRows()
                testVoice()
                return@loadElevenVoices
            }
            val pick = list.firstOrNull { it.gender == type }
            if (pick == null) {
                toast("Is type ki koi voice nahi mili.")
                return@loadElevenVoices
            }
            selectElevenVoice(pick)
        }
    }

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

    // ---------- hands-free (wake word) ----------

    private val wakeLevelOptions = listOf("Strict" to "strict", "Normal" to "normal", "Loose" to "loose")

    private fun wakeLevelLabel() = wakeLevelOptions.firstOrNull { it.second == Store.wakeSensitivity }?.first ?: "Normal"

    private fun wakeStatusText(): String = when {
        !Store.wakeWord -> "Off"
        AssistantService.running -> WakeCoordinator.status
        else -> "Band (app kholne par dobara chalu)"
    }

    private fun wakeSwitchRow() {
        val row = inflateRow()
        row.findViewById<ImageView>(R.id.rowIcon).setImageResource(R.drawable.ic_mic)
        row.findViewById<TextView>(R.id.rowTitle).text = "Wake word (hands-free)"
        row.findViewById<TextView>(R.id.rowValue).visibility = View.GONE
        row.findViewById<ImageView>(R.id.rowChevron).visibility = View.GONE
        val sw = row.findViewById<SwitchCompat>(R.id.rowSwitch)
        sw.visibility = View.VISIBLE
        sw.isChecked = Store.wakeWord
        sw.setOnCheckedChangeListener { _, on ->
            if (!settingWakeSwitch) {
                if (on) requestEnableWake() else disableWake()
            }
        }
        row.setOnClickListener { sw.toggle() }
        wakeSwitch = sw
        addRow(row)
    }

    private fun setWakeSwitch(on: Boolean) {
        val sw = wakeSwitch ?: return
        settingWakeSwitch = true
        sw.isChecked = on
        settingWakeSwitch = false
    }

    private fun requestEnableWake() {
        val ctx = requireContext()
        val check = WakeSupport.check(ctx)
        if (!check.ok) {
            AlertDialog.Builder(ctx)
                .setTitle("Wake word yahan nahi chalega")
                .setMessage(check.reason)
                .setPositiveButton("Theek hai", null)
                .show()
            setWakeSwitch(false)
            return
        }
        if (Store.wakeConsent >= WAKE_CONSENT_VERSION) {
            askWakePermissions()
            return
        }
        AlertDialog.Builder(ctx)
            .setTitle("Hands-free listening")
            .setMessage(
                "Wake word on karne par:\n\n" +
                    "• Phone ka mic chalu rehta hai (notification mein Stop button ke saath) jab tak aap band na karo.\n" +
                    "• Awaaz sirf phone ke andar on-device recognizer se check hoti hai. Record, save ya internet par nahi bheji jaati.\n" +
                    "• Aapke phrase ke bina kuch nahi hota. Phrase sunne par app khulti hai ya ek \"Haan? Maine suna\" notification aati hai.\n" +
                    "• Battery thodi zyada kharch hogi. Kitni, Settings mein Debug: conversation KPIs mein dikhta hai.\n" +
                    "• Kuch phones ka battery saver is service ko band kar deta hai.\n\n" +
                    "Kabhi bhi is switch se ya notification ke Stop se band kar sakte ho."
            )
            .setPositiveButton("Samajh gaya, on karo") { _, _ ->
                Store.wakeConsent = WAKE_CONSENT_VERSION
                askWakePermissions()
            }
            .setNegativeButton("Cancel") { _, _ -> setWakeSwitch(false) }
            .setOnCancelListener { setWakeSwitch(false) }
            .show()
    }

    private fun askWakePermissions() {
        val ctx = requireContext()
        val need = ArrayList<String>()
        if (!HandsFree.hasMic(ctx)) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isEmpty()) finishEnableWake() else wakePermissions.launch(need.toTypedArray())
    }

    private fun finishEnableWake() {
        Store.wakeWord = true
        if (HandsFree.start(requireContext())) {
            toast("Hands-free on")
        } else {
            Store.wakeWord = false
            setWakeSwitch(false)
            toast("Android ne hands-free service start nahi hone di. App khuli rakhkar dobara try karo.")
        }
    }

    private fun disableWake() {
        Store.wakeWord = false
        HandsFree.stop(requireContext())
        toast("Hands-free band")
    }

    private fun editWakePhrase(valueView: TextView) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            setText(Store.wakePhrase)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        AlertDialog.Builder(ctx)
            .setTitle("Wake phrase")
            .setMessage("3-4 syllable ka alag sa phrase rakho. Aam naam (Rani, Sara) ya \"ok google\" jaise phrase mat rakho.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val phrase = input.text.toString().trim()
                val warning = WakeMatcher.phraseWarning(phrase)
                if (phrase.isEmpty()) {
                    toast("Phrase khali nahi ho sakta.")
                } else if (warning == null) {
                    saveWakePhrase(phrase, valueView)
                } else {
                    AlertDialog.Builder(ctx)
                        .setTitle("Is phrase se dikkat ho sakti hai")
                        .setMessage(warning)
                        .setPositiveButton("Phir bhi rakho") { _, _ -> saveWakePhrase(phrase, valueView) }
                        .setNegativeButton("Badlo", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveWakePhrase(phrase: String, valueView: TextView) {
        Store.wakePhrase = phrase
        valueView.text = Store.wakePhrase
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

    private fun valueRow(icon: Int, title: String, value: String, onClick: (View, TextView) -> Unit): TextView {
        val row = inflateRow()
        row.findViewById<ImageView>(R.id.rowIcon).setImageResource(icon)
        row.findViewById<TextView>(R.id.rowTitle).text = title
        val tv = row.findViewById<TextView>(R.id.rowValue)
        tv.text = value
        row.setOnClickListener { onClick(it, tv) }
        addRow(row)
        return tv
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
