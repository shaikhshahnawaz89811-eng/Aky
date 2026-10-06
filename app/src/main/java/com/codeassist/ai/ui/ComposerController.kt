package com.codeassist.ai.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.animation.ValueAnimator
import android.os.Build
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.codeassist.ai.R
import com.codeassist.ai.data.AttachKind
import com.codeassist.ai.data.Attachment
import com.codeassist.ai.data.Store
import com.codeassist.ai.voice.MicButtonView
import com.codeassist.ai.voice.WaveformView
import java.util.Locale

/**
 * Wires the shared composer (view_composer.xml):
 *  - grows up to 5 lines, then scrolls internally (XML config)
 *  - 4000-char soft limit: counter appears near the limit, turns red and
 *    disables Send past the limit (typing itself is never blocked)
 *  - attachment chips with remove buttons
 */
class ComposerController(
    root: View,
    private val helper: AttachmentHelper,
    private val onSend: (String, List<Attachment>) -> Unit
) {
    companion object {
        const val MAX_CHARS = 4000
        const val COUNTER_SHOW_AT = 3400
    }

    val edit: EditText = root.findViewById(R.id.editMessage)
    private val btnSend: ImageButton = root.findViewById(R.id.btnSend)
    private val btnPlus: ImageButton = root.findViewById(R.id.btnPlus)
    private val btnTune: ImageButton = root.findViewById(R.id.btnTune)
    private val counter: TextView = root.findViewById(R.id.textCounter)
    private val attachScroll: HorizontalScrollView = root.findViewById(R.id.attachmentScroll)
    private val attachContainer: LinearLayout = root.findViewById(R.id.attachmentContainer)
    private val composerCard: View = root.findViewById(R.id.composerCard)
    private var glowAnimator: ValueAnimator? = null
    val mic: MicButtonView = root.findViewById(R.id.btnMic)
    private val voicePanel: View = root.findViewById(R.id.voicePanel)
    private val voiceDot: View = root.findViewById(R.id.voiceDot)
    private val voiceStatus: TextView = root.findViewById(R.id.voiceStatus)
    private val voiceTimer: TextView = root.findViewById(R.id.voiceTimer)
    private val voiceWave: WaveformView = root.findViewById(R.id.voiceWave)
    private val voiceTranscript: TextView = root.findViewById(R.id.voiceTranscript)
    private val voiceHint: TextView = root.findViewById(R.id.voiceHint)
    private var generating = false
    private var voiceVisual: MicButtonView.Visual = MicButtonView.Visual.IDLE
    private var voiceStartedAt = 0L
    private val timerTick = object : Runnable {
        override fun run() {
            val secs = (SystemClock.elapsedRealtime() - voiceStartedAt) / 1000
            voiceTimer.text = String.format(Locale.US, "%d:%02d", secs / 60, secs % 60)
            voicePanel.postDelayed(this, 500L)
        }
    }
    val chipModel: TextView = root.findViewById(R.id.chipModel)
    val chipTools: TextView = root.findViewById(R.id.chipTools)

    var hint: String
        get() = edit.hint?.toString() ?: ""
        set(v) { edit.hint = v }

    var onPlusClick: (() -> Unit)? = null
    var onTuneClick: (() -> Unit)? = null
    var onTextChanged: ((String) -> Unit)? = null
    var onStop: (() -> Unit)? = null

    init {
        btnPlus.setOnClickListener { onPlusClick?.invoke() }
        btnTune.setOnClickListener { onTuneClick?.invoke() }
        btnSend.setOnClickListener { if (generating) onStop?.invoke() else send() }
        refreshSurface(edit.hasFocus())
        composerCard.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (edit.hasFocus()) refreshSurface(hasFocus = true)
            }

            override fun onViewDetachedFromWindow(v: View) {
                stopGlow()
            }
        })

        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refresh()
                onTextChanged?.invoke(s?.toString().orEmpty())
            }
        })
        edit.setOnFocusChangeListener { _, hasFocus ->
            refreshSurface(hasFocus)
        }
        helper.onChanged = { renderAttachments() }
        refresh()
    }

    private fun refresh() {
        val len = edit.text?.length ?: 0
        val over = len > MAX_CHARS
        if (len >= COUNTER_SHOW_AT) {
            counter.visibility = View.VISIBLE
            counter.text = "$len/$MAX_CHARS"
            counter.setTextColor(if (over) 0xFFFF7B72.toInt() else 0xFF5D6B7C.toInt())
        } else {
            counter.visibility = View.GONE
        }
        val hasContent = !edit.text.isNullOrBlank() || helper.current.isNotEmpty()
        val enabled = generating || (hasContent && !over)
        if (btnSend.isEnabled != enabled) {
            btnSend.isEnabled = enabled
            btnSend.animate().cancel()
            btnSend.animate().alpha(if (enabled) 1f else 0.5f).setDuration(140).start()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val glowEnabled = enabled && Store.glassEffect
            btnSend.elevation = if (glowEnabled) dp(6f) else 0f
            btnSend.outlineSpotShadowColor =
                if (glowEnabled) Color.argb(180, 83, 177, 255) else Color.TRANSPARENT
        }
    }

    private fun refreshSurface(hasFocus: Boolean) {
        val glass = Store.glassEffect
        val v = voiceVisual
        val voiceOn = v != MicButtonView.Visual.IDLE
        composerCard.setBackgroundResource(
            when {
                !glass -> R.drawable.composer_bg_flat
                v == MicButtonView.Visual.THINKING -> R.drawable.composer_bg_think
                v == MicButtonView.Visual.SPEAKING -> R.drawable.composer_bg_speak
                hasFocus || voiceOn -> R.drawable.composer_bg_focus
                else -> R.drawable.composer_bg
            }
        )
        stopGlow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            applyGlow(glass, hasFocus || voiceOn, v)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun applyGlow(glass: Boolean, lit: Boolean, v: MicButtonView.Visual) {
        // glow colour follows the voice state: blue = listening, purple = thinking, green = speaking
        val r: Int
        val g: Int
        val b: Int
        when (v) {
            MicButtonView.Visual.THINKING -> { r = 180; g = 156; b = 255 }
            MicButtonView.Visual.SPEAKING -> { r = 123; g = 227; b = 168 }
            else -> { r = 83; g = 166; b = 238 }
        }
        composerCard.elevation = if (glass) dp(8f) else 0f
        composerCard.outlineAmbientShadowColor =
            if (glass) Color.argb(42, r * 3 / 4, g * 3 / 4, b * 3 / 4) else Color.TRANSPARENT
        val lowGlow = Color.argb(if (lit) 76 else 52, r, g, b)
        composerCard.outlineSpotShadowColor = lowGlow
        if (glass && lit) {
            val high = Color.argb(142, minOf(255, r + 16), minOf(255, g + 24), minOf(255, b + 17))
            glowAnimator = ValueAnimator.ofArgb(lowGlow, high).apply {
                duration = 1700L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener {
                    composerCard.outlineSpotShadowColor = it.animatedValue as Int
                }
                start()
            }
        }
    }

    private fun stopGlow() {
        glowAnimator?.cancel()
        glowAnimator = null
    }

    private fun dp(value: Float): Float = value * composerCard.resources.displayMetrics.density

    private fun renderAttachments() {
        attachContainer.removeAllViews()
        attachScroll.visibility = if (helper.current.isEmpty()) View.GONE else View.VISIBLE
        val inflater = LayoutInflater.from(attachContainer.context)
        helper.current.forEachIndexed { index, attachment ->
            val card = inflater.inflate(R.layout.item_attachment_preview, attachContainer, false)
            val image = card.findViewById<android.widget.ImageView>(R.id.previewImage)
            val fileInfo = card.findViewById<View>(R.id.fileInfo)
            if (attachment.kind == AttachKind.IMAGE) {
                image.visibility = View.VISIBLE
                fileInfo.visibility = View.GONE
                ImageLoader.load(card.context, image, attachment.uri)
            } else {
                image.visibility = View.GONE
                fileInfo.visibility = View.VISIBLE
                val extension = attachment.name.substringAfterLast('.', "").uppercase()
                val type = when {
                    attachment.kind == AttachKind.ZIP -> "ZIP"
                    extension == "PDF" -> "PDF"
                    extension.isNotBlank() && extension.length <= 4 -> extension
                    else -> "FILE"
                }
                val typeColor = when (type) {
                    "ZIP" -> Color.rgb(255, 179, 123)
                    "PDF" -> Color.rgb(180, 156, 255)
                    else -> Color.rgb(110, 193, 255)
                }
                card.findViewById<TextView>(R.id.textAttachType).apply {
                    text = type
                    setTextColor(typeColor)
                    background = GradientDrawable().apply {
                        cornerRadius = 5f * resources.displayMetrics.density
                        setColor(Color.argb(38, Color.red(typeColor), Color.green(typeColor), Color.blue(typeColor)))
                        setStroke(
                            (1f * resources.displayMetrics.density).toInt().coerceAtLeast(1),
                            Color.argb(105, Color.red(typeColor), Color.green(typeColor), Color.blue(typeColor))
                        )
                    }
                }
            }
            card.findViewById<TextView>(R.id.textAttachName).text = attachment.name
            card.findViewById<TextView>(R.id.textAttachSize).text =
                AttachmentHelper.formatSize(attachment.size)
            card.findViewById<ImageButton>(R.id.btnRemoveAttach).setOnClickListener {
                helper.remove(attachment)
            }
            card.alpha = 0f
            card.translationY = 7f
            attachContainer.addView(card)
            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(index * 35L)
                .setDuration(190L)
                .start()
        }
        refresh()
    }

    private fun send() {
        val text = edit.text?.toString()?.trim() ?: ""
        if (text.isEmpty() && helper.current.isEmpty()) return
        if (text.length > MAX_CHARS) return
        val atts = helper.current.toList()
        onSend(text, atts)
        edit.setText("")
        helper.current.clear()
        renderAttachments()
        refresh()
    }

    fun setModelLabel(model: String) {
        chipModel.text = "$model  ▾"
    }

    fun refreshAppearance() {
        refreshSurface(edit.hasFocus())
        refresh()
    }

    // ---------- stop button (while a reply is being produced) ----------

    fun setGenerating(on: Boolean) {
        if (generating == on) return
        generating = on
        btnSend.setImageResource(if (on) R.drawable.ic_stop else R.drawable.ic_send)
        btnSend.contentDescription = if (on) "Stop" else "Send"
        refresh()
    }

    // ---------- voice ----------

    fun hideMic() {
        mic.visibility = View.GONE
    }

    fun setVoiceVisual(v: MicButtonView.Visual) {
        val was = voiceVisual
        voiceVisual = v
        mic.visual = v
        val active = v != MicButtonView.Visual.IDLE
        val listening = v == MicButtonView.Visual.TAP || v == MicButtonView.Visual.HOLD ||
            v == MicButtonView.Visual.CONTINUOUS
        voicePanel.visibility = if (active) View.VISIBLE else View.GONE
        edit.visibility = if (active) View.GONE else View.VISIBLE
        voiceWave.visibility = if (listening) View.VISIBLE else View.GONE
        if (!listening) voiceTranscript.visibility = View.GONE

        val color: Int
        val label: String
        val hint: String
        when (v) {
            MicButtonView.Visual.TAP -> {
                color = MicButtonView.BLUE; label = "Sun raha hoon…"
                hint = "Pause par apne aap ruk jayega · mic dabao rokne ke liye"
            }
            MicButtonView.Visual.HOLD -> {
                color = MicButtonView.BLUE; label = "Bolo, chhodte hi send hoga"
                hint = "← cancel ke liye slide · ↑ lock karne ke liye"
            }
            MicButtonView.Visual.CONTINUOUS -> {
                color = MicButtonView.BLUE; label = "Continuous · sun raha hoon"
                hint = "Bolte raho · \"bas\" bolo ya mic dabao band karne ke liye"
            }
            MicButtonView.Visual.THINKING -> {
                color = MicButtonView.PURPLE; label = "Jawab ban raha hai…"
                hint = "Mic dabao rokne ke liye"
            }
            MicButtonView.Visual.SPEAKING -> {
                color = MicButtonView.GREEN; label = "Bol raha hoon"
                hint = "Mic dabao bolna rokne ke liye"
            }
            else -> {
                color = MicButtonView.BLUE; label = ""; hint = ""
            }
        }
        voiceStatus.text = label
        voiceStatus.setTextColor(color)
        voiceHint.text = hint
        voiceWave.barColor = color
        voiceDot.background?.mutate()?.setTint(color)

        voicePanel.removeCallbacks(timerTick)
        if (listening) {
            if (was == MicButtonView.Visual.IDLE || was == MicButtonView.Visual.THINKING || was == MicButtonView.Visual.SPEAKING) {
                voiceWave.clear()
                voiceStartedAt = SystemClock.elapsedRealtime()
            }
            voiceTimer.visibility = View.VISIBLE
            voicePanel.post(timerTick)
        } else {
            voiceTimer.visibility = View.GONE
        }
        refreshSurface(edit.hasFocus())
    }

    fun setVoiceLevel(level: Float) {
        voiceWave.push(level)
        mic.setLevel(level)
    }

    fun setVoicePartial(text: String) {
        if (text.isBlank()) {
            voiceTranscript.visibility = View.GONE
        } else {
            voiceTranscript.visibility = View.VISIBLE
            voiceTranscript.text = text
        }
    }

    fun setVoiceHint(text: String) {
        voiceHint.text = text
    }

    /** Hide controls that imply a server-side capability when no backend exists. */
    fun hideBackendControls() {
        chipTools.visibility = View.GONE
        btnTune.visibility = View.GONE
    }
}
