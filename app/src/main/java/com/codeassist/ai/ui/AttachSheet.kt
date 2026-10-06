package com.codeassist.ai.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Dialog
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.fragment.app.DialogFragment
import com.codeassist.ai.R
import com.codeassist.ai.data.Store

class AttachSheet : DialogFragment() {
    private val glowAnimations = mutableListOf<AnimatorSet>()

    companion object {
        const val RESULT_KEY = "attachment_picker_result"
        const val RESULT_ACTION = "action"
        const val ACTION_IMAGE = "image"
        const val ACTION_ZIP = "zip"
        const val ACTION_FILE = "file"
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val d = Dialog(requireContext(), R.style.SheetDialog)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return d
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.dialog_attach, container, false)
        v.setBackgroundResource(
            if (Store.glassEffect) R.drawable.sheet_bg else R.drawable.sheet_bg_flat
        )
        val cardBackground = if (Store.glassEffect) {
            R.drawable.attachment_option_card
        } else {
            R.drawable.attachment_option_card_flat
        }
        listOf(R.id.optImage, R.id.optZip, R.id.optFile).forEach { id ->
            v.findViewById<View>(id).setBackgroundResource(cardBackground)
        }
        v.findViewById<View>(R.id.btnCloseSheet).setOnClickListener { dismiss() }
        v.findViewById<View>(R.id.optImage).setOnClickListener { returnAction(ACTION_IMAGE) }
        v.findViewById<View>(R.id.optZip).setOnClickListener { returnAction(ACTION_ZIP) }
        v.findViewById<View>(R.id.optFile).setOnClickListener { returnAction(ACTION_FILE) }
        val iconColors = listOf(
            Color.rgb(91, 225, 177),
            Color.rgb(255, 177, 119),
            Color.rgb(173, 139, 255)
        )
        listOf(R.id.optionIconPhoto, R.id.optionIconZip, R.id.optionIconFile)
            .forEachIndexed { index, id ->
                val icon = v.findViewById<View>(id)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val color = iconColors[index]
                    icon.outlineAmbientShadowColor = Color.argb(48, Color.red(color), Color.green(color), Color.blue(color))
                    icon.outlineSpotShadowColor = Color.argb(150, Color.red(color), Color.green(color), Color.blue(color))
                }
                val pulse = AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(icon, View.ALPHA, 0.82f, 1f).apply {
                            duration = 1700L
                            repeatCount = ValueAnimator.INFINITE
                            repeatMode = ValueAnimator.REVERSE
                        },
                        ObjectAnimator.ofFloat(icon, View.SCALE_X, 0.97f, 1.04f).apply {
                            duration = 1700L
                            repeatCount = ValueAnimator.INFINITE
                            repeatMode = ValueAnimator.REVERSE
                        },
                        ObjectAnimator.ofFloat(icon, View.SCALE_Y, 0.97f, 1.04f).apply {
                            duration = 1700L
                            repeatCount = ValueAnimator.INFINITE
                            repeatMode = ValueAnimator.REVERSE
                        }
                    )
                    startDelay = index * 130L
                }
                glowAnimations += pulse
                pulse.start()
            }
        return v
    }

    private fun returnAction(action: String) {
        parentFragmentManager.setFragmentResult(
            RESULT_KEY,
            Bundle().apply { putString(RESULT_ACTION, action) }
        )
        dismiss()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
    }

    override fun onDestroyView() {
        glowAnimations.forEach(AnimatorSet::cancel)
        glowAnimations.clear()
        super.onDestroyView()
    }
}
