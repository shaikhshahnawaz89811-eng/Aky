package com.codeassist.ai.settings

import android.content.Context
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import com.codeassist.ai.data.Store
import com.codeassist.ai.service.HandsFree
import com.codeassist.ai.voice.WakeSupport
import com.codeassist.ai.voice.WakeTest

/**
 * Settings > Hands-free > Wake test: say the phrase 10 times, see how many the phone caught
 * (audit PDF Phase 2 exit: wake-word hit rate measured). Results are added to the KPI report.
 */
object WakeTestDialog {
    private const val TRIALS = 10

    fun show(ctx: Context) {
        val check = WakeSupport.check(ctx)
        if (!check.ok) {
            AlertDialog.Builder(ctx).setTitle("Wake test yahan nahi chalega").setMessage(check.reason)
                .setPositiveButton("Theek hai", null).show()
            return
        }
        if (!HandsFree.hasMic(ctx)) {
            AlertDialog.Builder(ctx).setTitle("Mic permission chahiye")
                .setMessage("Pehle Hands-free switch on karke mic permission do, phir test chalao.")
                .setPositiveButton("Theek hai", null).show()
            return
        }
        val log = StringBuilder()
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Wake test")
            .setMessage(
                "$TRIALS baar \"${Store.wakePhrase}\" bolna hai, har baar ek alag try. Shant jagah mein, aise bolo jaise " +
                    "roz bolte ho. Sensitivity abhi: ${Store.wakeSensitivity}.\n\nShuru ho raha hai..."
            )
            .setNegativeButton("Rok do", null)
            .create()
        val ui = object : WakeTest.Ui {
            override fun onTrial(index: Int, total: Int, phrase: String) {
                dialog.setMessage("Try $index / $total\n\nAb bolo: \"$phrase\"\n\n$log")
            }

            override fun onResult(index: Int, hit: Boolean, heard: String) {
                log.append(index).append(if (hit) ": suna\n" else ": nahi suna\n")
                dialog.setMessage("Try $index / $TRIALS: " + (if (hit) "suna" else "nahi suna") + "\n\n$log")
            }

            override fun onDone(hits: Int, total: Int) {
                val missPct = (total - hits) * 100 / total
                dialog.setMessage(
                    "Test poora: $hits / $total pakda (miss $missPct%).\n\n" +
                        "Bahut miss ho toh Sensitivity Loose karke dobara test karo. Roz ke use mein bina bole " +
                        "trigger zyada lage toh Strict karo. Ye sirf miss (false reject) hai; false accept ghanton ke " +
                        "asli use se KPI screen par aata hai."
                )
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.text = "Band karo"
            }

            override fun onError(reason: String) {
                dialog.setMessage("Test ruk gaya: $reason")
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.text = "Band karo"
            }
        }
        val test = WakeTest(ctx, TRIALS, ui)
        dialog.setOnDismissListener { test.cancel() }
        dialog.show()
        val neg: Button? = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        neg?.setOnClickListener {
            test.cancel()
            dialog.dismiss()
        }
        test.start()
    }
}
