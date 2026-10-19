package com.codeassist.ai.ai

import android.content.Context
import android.content.SharedPreferences
import com.codeassist.ai.data.Store
import java.util.Locale

/**
 * Audit PDF Sec 12.1 / Phase 2 exit criteria: counters for the conversation KPIs, kept on the device.
 * Only counts and timings are stored, never text or audio. The report says plainly which numbers are
 * measured and which ones still need a labelled test set or a human to judge.
 */
object ConvKpi {
    private var prefs: SharedPreferences? = null

    private const val LAT_NORMAL = "lat_normal"
    private const val LAT_FAST = "lat_fast"
    private const val KEEP = 60

    @Synchronized
    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("codeassist_kpi", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun inc(key: String, by: Long = 1L) {
        val p = prefs ?: return
        p.edit().putLong(key, p.getLong(key, 0L) + by).apply()
    }

    @Synchronized
    fun get(key: String): Long = prefs?.getLong(key, 0L) ?: 0L

    /** Phase 3C: a value that is set, not added to (the last eval result). */
    @Synchronized
    fun put(key: String, value: Long) {
        prefs?.edit()?.putLong(key, value)?.apply()
    }

    @Synchronized
    fun putText(key: String, value: String) {
        prefs?.edit()?.putString(key, value)?.apply()
    }

    @Synchronized
    fun getText(key: String): String = prefs?.getString(key, "") ?: ""

    /** End of the user's turn to the first spoken sample. [fast] = answered by the Tier-0 fast path. */
    @Synchronized
    fun recordFirstAudio(ms: Long, fast: Boolean) {
        val p = prefs ?: return
        val key = if (fast) LAT_FAST else LAT_NORMAL
        val list = readList(p, key)
        list.add(ms)
        while (list.size > KEEP) list.removeAt(0)
        p.edit().putString(key, list.joinToString(",")).apply()
    }

    private fun readList(p: SharedPreferences, key: String): ArrayList<Long> {
        val out = ArrayList<Long>()
        (p.getString(key, "") ?: "").split(',').forEach { s -> s.toLongOrNull()?.let { out.add(it) } }
        return out
    }

    private fun pct(sorted: List<Long>, q: Double): Long {
        if (sorted.isEmpty()) return 0L
        val idx = ((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    private fun check(done: Boolean, what: String, missing: String): String =
        if (done) "  [naapa] $what" else "  [baaki] $what: $missing"

    private fun sec(ms: Long): String = String.format(Locale.US, "%.2fs", ms / 1000.0)

    @Synchronized
    fun reset() {
        prefs?.edit()?.clear()?.apply()
    }

    @Synchronized
    fun report(): String {
        val p = prefs ?: return "KPI abhi ready nahi."
        val sb = StringBuilder()
        val voiceTurns = get("voice_turns")
        sb.append("Voice turns: ").append(voiceTurns).append("\n\n")

        val normal = readList(p, LAT_NORMAL).sorted()
        val fast = readList(p, LAT_FAST).sorted()
        sb.append("First audio (end of turn -> first spoken sample)\n")
        if (normal.isEmpty() && fast.isEmpty()) sb.append("  abhi koi sample nahi\n")
        if (normal.isNotEmpty()) {
            sb.append("  normal: n=").append(normal.size).append(" · p50 ").append(sec(pct(normal, 0.5)))
                .append(" · p95 ").append(sec(pct(normal, 0.95))).append("  (target p50 1.3s, p95 2.5s)\n")
        }
        if (fast.isNotEmpty()) {
            sb.append("  fast path: n=").append(fast.size).append(" · p50 ").append(sec(pct(fast, 0.5)))
                .append(" · p95 ").append(sec(pct(fast, 0.95))).append("  (target p50 0.7s, p95 1.2s)\n")
        }

        val speaking = get("speaking_turns")
        val cand = get("barge_candidates")
        val valid = get("barge_valid")
        val rejected = get("barge_rejected")
        val falseB = get("barge_false")
        sb.append("\nBarge-in\n")
        sb.append("  bolte hue turns: ").append(speaking).append(" · candidates ").append(cand)
            .append(" · valid ").append(valid).append(" · rejected (backchannel) ").append(rejected).append("\n")
        sb.append("  valid par kuch sunai nahi diya (false barge-in): ").append(falseB)
        if (speaking > 0) {
            sb.append(String.format(Locale.US, " = %.1f per 50 turns  (target <= 1)", falseB * 50.0 / speaking))
        }
        sb.append("\n")
        sb.append("  false barge-in ke baad jawab aage bola gaya: ").append(get("barge_resumed")).append("\n")

        val guard = get("endpoint_guard")
        val saved = get("endpoint_saved")
        sb.append("\nPause detection (Hindi verb-final)\n")
        sb.append("  adhoora sentence pakda: ").append(guard).append(" · user ne aage bola: ").append(saved).append("\n")
        sb.append("  Asli premature cut-off % (target <= 3%) sirf labelled test set / human check se naapa ja sakta hai.\n")

        sb.append("\nFollow-up window\n")
        sb.append("  khuli: ").append(get("followup_open")).append(" · user bola: ").append(get("followup_used"))
            .append(" · khamosh band: ").append(get("followup_timeout")).append("\n")

        sb.append("\nBaaki\n")
        sb.append("  backchannel ignore: ").append(get("backchannel_ignored"))
            .append(" · resume: ").append(get("resume_used"))
            .append(" · repeat: ").append(get("repeat_used"))
            .append(" · filler: ").append(get("filler_played"))
            .append(" · focus loss: ").append(get("focus_loss")).append("\n")

        val wakeOn = get("wake_detect")
        if (wakeOn > 0 || get("wake_listen_ms") > 0) {
            sb.append("\nWake word\n")
            sb.append("  detect: ").append(wakeOn).append(" · bina bole band (shayad false accept): ")
                .append(get("wake_empty")).append("\n")
            sb.append("  recognizer runs: ").append(get("wake_attempts")).append(" · shor-rest (backoff): ")
                .append(get("wake_backoff")).append(" · service refuse: ").append(get("wake_fgs_denied"))
                .append(" · engine band: ").append(get("wake_unavailable")).append("\n")
            val hours = get("wake_listen_ms") / 3_600_000.0
            if (hours > 0.05) {
                sb.append(String.format(Locale.US, "  suna: %.1f ghanta · false accept / ghanta (anuman): %.2f  (target <= 0.1)\n",
                    hours, get("wake_empty") / hours))
            }
        }
        val trials = get("waketest_trials")
        if (trials > 0) {
            val hit = get("waketest_hits")
            sb.append("\nWake test (phrase 10 try wale rounds)\n")
            sb.append(String.format(Locale.US, "  kul: %d / %d pakda = false reject %.0f%%\n",
                hit, trials, (trials - hit) * 100.0 / trials))
            for (lvl in listOf("strict", "normal", "loose")) {
                val t = get("waketest_trials_$lvl")
                if (t > 0) {
                    val h = get("waketest_hits_$lvl")
                    sb.append(String.format(Locale.US, "  %s: %d / %d pakda (miss %.0f%%)\n", lvl, h, t, (t - h) * 100.0 / t))
                }
            }
        }
        val killed = get("wake_killed")
        if (killed > 0 || get("wake_reboot") > 0 || get("wake_watchdog_notice") > 0) {
            sb.append("\nHands-free health\n")
            sb.append("  Android ne band kiya: ").append(killed).append(" baar · reboot: ").append(get("wake_reboot"))
                .append(" · watchdog notice: ").append(get("wake_watchdog_notice")).append("\n")
            if (killed > 0) {
                sb.append(String.format(Locale.US,
                    "  band rehne ka anuman (aakhri heartbeat se app kholne tak): %.1f ghanta\n", get("wake_down_ms") / 3_600_000.0))
            }
        }

        val drainMs = get("drain_ms")
        val drainHours = drainMs / 3_600_000.0
        if (drainMs > 0) {
            val mah = get("drain_uah") / 1000.0
            val perHour = mah / drainHours
            sb.append("\nBattery (hands-free on, screen band, charger nahi)\n")
            sb.append(String.format(Locale.US, "  %.1f ghante mein %.0f mAh = %.1f mAh / ghanta (8 ghante ~ %.0f mAh)\n",
                drainHours, mah, perHour, perHour * 8))
            sb.append("  Ye poore phone ka drain hai, sirf is app ka nahi. Tulna ke liye ek raat wake word off karke bhi naapo.\n")
            if (drainHours < 1.0) sb.append("  Abhi 1 ghante se kam sample hai: bharosa mat karo.\n")
        } else if (get("wake_listen_ms") > 0) {
            sb.append("\nBattery: abhi koi saaf sample nahi. Screen band aur charger nikla hona chahiye; kuch phones battery ka charge counter hi nahi dete.\n")
        }

        sb.append("\nGemini degrade / Gemma fallback\n")
        sb.append("  L0 full Gemini: ").append(get("degrade_l0_full"))
            .append(" · L1 timeout / busy: ").append(get("degrade_l1"))
            .append(" · L2 no route / DNS: ").append(get("degrade_l2")).append("\n")
        sb.append("  Gemma fallback: ").append(get("fallback_success")).append(" successful")
            .append(" · ").append(get("fallback_unavailable")).append(" model unavailable")
            .append(" · ").append(get("fallback_failed")).append(" Gemma errors\n")
        sb.append("  Screenshot OCR (Gemma-only, opt-in): ").append(get("ocr_success"))
            .append(" read · ").append(get("ocr_failed")).append(" unreadable / failed\n")

        sb.append("\nOffline Gemma jawab (offline fix part 1)\n")
        val shape = try {
            val chosen = Store.localTemplate
            if (chosen == "auto") "auto → " + Store.localTemplateResult.ifBlank { "test baaki" } else chosen
        } catch (_: Exception) {
            "?"
        }
        sb.append("  prompt template: ").append(shape).append("\n")
        val probeLines = try {
            Store.localTemplateNote
        } catch (_: Exception) {
            ""
        }
        if (probeLines.isNotBlank()) {
            for (line in probeLines.split('\n')) sb.append("    ").append(line).append("\n")
        }
        sb.append("  test chale: ").append(get("template_probe"))
            .append(" · galat jawab trim kiye: ").append(get("local_guard_trim"))
            .append(" · dobara koshish: ").append(get("local_guard_retry"))
            .append(" · reject: ").append(get("local_guard_fail"))
            .append(" · photo par seedha jawab: ").append(get("local_image_block")).append("\n")

        sb.append("\nBhasha aur bhaari sawaal (T2)\n")
        sb.append("  Hinglish / English retry: ").append(get("local_lang_retry")).append(" · theek hua: ")
            .append(get("local_lang_retry_ok")).append(" · phir bhi galat bhasha: ").append(get("local_lang_retry_failed")).append("\n")
        sb.append("  bhaari plan sawaal Gemini ko: ").append(get("heavy_to_gemini"))
            .append(" · Gemini nahi mila (Gemma ne diya): ").append(get("heavy_gemini_failed")).append("\n")

        sb.append("\nPlan builder (T3)\n")
        sb.append("  plan bane: ").append(get("pb_runs")).append(" (bhasha badal kar dobara: ").append(get("pb_redo"))
            .append(") · poore: ").append(get("pb_ok")).append(" · adhure: ").append(get("pb_incomplete")).append("\n")
        sb.append("  hisse dobara likhwaye: ").append(get("pb_retries"))
            .append(" · galat tech wali lines hata di: ").append(get("pb_tech_dropped")).append("\n")

        sb.append("\nSketch (T4)\n")
        sb.append("  sketch maange: ").append(get("sk_runs")).append(" · bane: ").append(get("sk_ok"))
            .append(" · nahi bane: ").append(get("sk_failed")).append(" · dobara likhwaya: ").append(get("sk_retry"))
            .append(" · \"skills\" par sawaal: ").append(get("sk_clarify"))
            .append(" · badlav (edit) maange: ").append(get("sk_edit")).append("\n")
        sb.append("  Studio screen se (stage 2) maange: ").append(get("sk_studio")).append("\n")
        sb.append("  Studio stage 3: 3 option maange: ").append(get("sk_variants")).append(" \u00B7 web page / 3D maange: ").append(get("sk_web")).append("\n")

        sb.append("\nNet search (stage S2)\n")
        sb.append("  search maange: ").append(get("web_runs")).append(" · jawab bana: ").append(get("web_ok"))
            .append(" · bina flag ke saaf: ").append(get("web_clean")).append(" · source nahi mila: ").append(get("web_no_source"))
            .append(" · adhura (ek round fail): ").append(get("web_partial")).append(" · Stop: ").append(get("web_stopped")).append("\n")
        sb.append("  Tavily credit kul: ").append(get("web_credits")).append(" · rounds: ").append(get("web_rounds"))
            .append(" · cache se (0 credit): ").append(get("web_cache_hit")).append(" · doosra round (broad): ").append(get("web_broadened")).append("\n")
        sb.append("  roka / line boli: key nahi ").append(get("web_no_key")).append(" · key galat ").append(get("web_bad_key"))
            .append(" · offline ").append(get("web_offline")).append(" · plan khatam ").append(get("web_plan_exhausted"))
            .append(" · aaj ki limit ").append(get("web_cap_reached")).append(" · private query ").append(get("web_blocked_query"))
            .append(" · number hataya ").append(get("web_number_stripped")).append(" · is stage mein na banne wali kism ").append(get("web_skip_kind")).append("\n")
        sb.append("  provider dikkat: timeout ").append(get("web_timeouts")).append(" · rate limit ").append(get("web_rate_limited"))
            .append(" · baaki ").append(get("web_failed")).append("\n")
        sb.append("  validator: verify nahi hui lines ").append(get("web_unverified")).append(" · model ke link hataye ").append(get("web_links_removed"))
            .append(" · galat [n] ").append(get("web_invalid_marker")).append(" · jawab mein koi [n] nahi ").append(get("web_cited_none"))
            .append(" · instruction jaisi lines hatayi ").append(get("web_injected_dropped")).append("\n")
        sb.append("  context: kam sources aaye ").append(get("web_ctx_trim")).append(" · jawab nahi likha gaya (sirf sources) ").append(get("web_compose_failed"))
            .append(" · Gemini se Gemma ").append(get("web_compose_fallback")).append(" · bhasha retry ").append(get("web_lang_retry"))
            .append(" (theek ").append(get("web_lang_retry_ok")).append(" / phir bhi galat ").append(get("web_lang_retry_failed")).append(")\n")
        val webOk = get("web_ok")
        val webClean = get("web_clean")
        sb.append("  S2 checklist (10 sawaal, jawab bane >= 10, saaf >= 90%): ")
        if (webOk < 10) {
            sb.append("[baaki] abhi ").append(webOk).append(" jawab bane\n")
        } else {
            val pctClean = Math.round(webClean * 100.0 / webOk).toInt()
            sb.append(if (webClean * 100 >= 90 * webOk) "[naapa] " else "[baaki] ").append(webClean).append("/").append(webOk)
                .append(" = ").append(pctClean).append("% bina flag ke\n")
        }

        sb.append("\nNet search links and downloads (stage S3)\n")
        sb.append("  link maange: ").append(get("web_link_runs")).append(" · cards bane: ").append(get("web_link_ok"))
            .append(" · kuch nahi mila: ").append(get("web_link_none")).append(" · file cards: ").append(get("web_link_files")).append("\n")
        sb.append("  GitHub API calls: ").append(get("web_link_gh_calls")).append(" · repo nahi mila: ").append(get("web_link_gh_notfound"))
            .append(" · GitHub limit: ").append(get("web_link_gh_rate")).append("\n")
        sb.append("  page extract: ").append(get("web_extract_runs")).append(" (fail ").append(get("web_extract_failed")).append(")")
            .append(" · file jaanch: pass ").append(get("web_guard_pass")).append(" / reject ").append(get("web_guard_reject")).append("\n")
        sb.append("  Download button: tap ").append(get("web_download_tap")).append(" · shuru ").append(get("web_download_started"))
            .append(" · cancel ").append(get("web_download_cancel")).append(" · roka ").append(get("web_download_blocked"))
            .append(" · fail ").append(get("web_download_failed")).append("\n")

        sb.append("\nTask engine (Phase 3)\n")
        sb.append("  plans: ").append(get("plan_runs")).append(" · 2+ kaam wale: ").append(get("plan_multi"))
            .append(" · `after` (dependency) wale: ").append(get("plan_with_deps")).append("\n")
        sb.append("  kaam: ").append(get("plan_node_ok")).append(" ho gaye · ").append(get("plan_node_failed"))
            .append(" nahi hue · ").append(get("plan_node_skipped")).append(" chhode (pehla kaam fail)\n")
        sb.append("  T2 confirm: ").append(get("t2_waiting")).append(" ruke · ").append(get("t2_confirmed"))
            .append(" tap se chale · ").append(get("t2_cancelled")).append(" cancel · ").append(get("t2_expired"))
            .append(" purane · BINA confirm chalne ki koshish: ").append(get("t2_blocked")).append("  (target 0)\n")
        sb.append("  crash resume: ").append(get("plan_recovered")).append(" plan mile · ").append(get("plan_resumed_nodes"))
            .append(" kaam dobara chale · ").append(get("plan_recover_unsure")).append(" \"pata nahi hua ya nahi\"\n")
        sb.append("  kill-process test: ").append(get("resume_test_pass")).append(" pass · ").append(get("resume_test_fail"))
            .append(" fail · ").append(get("resume_test_late")).append(" der se khola (2 minute ke baad, ginti nahi)\n")

        sb.append("\nCorrections (Phase 3B)\n")
        sb.append("  \"nahi, 9 baje\" jaise sudhar: ").append(get("correction_runs")).append(" mile · ")
            .append(get("correction_ok")).append(" purana hata diya · ")
            .append(get("correction_old_stays")).append(" purana khud hatana pada · ")
            .append(get("correction_failed")).append(" naya set nahi hua\n")

        sb.append("\nProgress, Stop, mausam (Phase 3B-2)\n")
        sb.append("  plans jisme mausam tha: ").append(get("plan_with_slow")).append(" · 8 s timeout: ").append(get("step_timeouts"))
            .append(" · der ki line (progress): ").append(get("progress_nudges"))
            .append(" · Stop ke baad aaya jawab pheka: ").append(get("plan_late_dropped")).append("\n")
        sb.append("  Stop (INTERRUPT): ").append(get("interrupt_stop")).append(" baar · ").append(get("interrupt_nodes_cancelled"))
            .append(" kaam cancel hue · recovery mein network kaam chhode: ").append(get("plan_slow_dropped")).append("\n")

        sb.append("\nPlan edit aur eval (Phase 3C)\n")
        sb.append("  \"X wala kaam hata do\": ").append(get("plan_edit_runs")).append(" mile · ").append(get("plan_edit_removed"))
            .append(" cancel hue · ").append(get("plan_edit_undone")).append(" wapas kiye · ").append(get("plan_edit_notfound"))
            .append(" \"aisa kaam nahi\" · ").append(get("plan_edit_ambiguous")).append(" \"kaun sa?\" · plans clear kiye: ")
            .append(get("plans_cleared")).append("\n")
        val evalTotal = get("eval_total")
        if (evalTotal > 0) {
            val ok = get("eval_ok")
            sb.append("  eval: ").append(ok).append("/").append(evalTotal).append(" sahi (").append(getText("eval_engine")).append(")\n")
        } else {
            sb.append("  eval: abhi poora set nahi chala (Settings > Debug: task engine > Eval chalao)\n")
        }

        sb.append("\nMemory (Phase 4A)\n")
        sb.append("  yaad rakhi: ").append(get("mem_added_auto")).append(" apne aap pakdi · ").append(get("mem_said"))
            .append(" tumne kaha · ").append(get("mem_working")).append(" sirf 24 ghante ke liye\n")
        sb.append("  mana kiya (OTP / PIN / card ...): ").append(get("mem_blocked")).append(" · Private mode ne roka: ")
            .append(get("mem_private_blocked")).append(" · Private on / off: ").append(get("mem_private_on")).append(" / ")
            .append(get("mem_private_off")).append("\n")
        sb.append("  Memory screen: ").append(get("mem_deleted")).append(" ek-ek delete · ").append(get("mem_edited")).append(" badli · ")
            .append(get("mem_promoted")).append(" hamesha ki gayi · ").append(get("mem_export")).append(" export\n")
        sb.append("  sab bhoola: ").append(get("mem_forget_all")).append(" · pichhle minute bhoole: ").append(get("mem_forget_recent"))
            .append(" · delete jaanch pass / fail: ").append(get("mem_delete_ok")).append(" / ").append(get("mem_delete_fail")).append("\n")

        sb.append("\nSuggestions (Phase 4B)\n")
        sb.append("  dikhin: ").append(get("proactive_shown")).append(" (battery ").append(get("proactive_kind_battery"))
            .append(", torch ").append(get("proactive_kind_torch")).append(") · kaam kiya: ").append(get("proactive_acted"))
            .append(" · baad mein: ").append(get("proactive_dismissed")).append(" · ye mat bolo: ").append(get("proactive_muted")).append("\n")
        sb.append("  roki gayin: budget ").append(get("proactive_blocked_budget")).append(" · raat ").append(get("proactive_blocked_quiet"))
            .append(" · 3 ghante ka gap ").append(get("proactive_blocked_gap")).append(" · wahi kism 6 ghante ").append(get("proactive_blocked_cooldown"))
            .append(" · band kism ").append(get("proactive_blocked_muted")).append(" · Off ").append(get("proactive_blocked_off")).append("\n")

        sb.append("\nPhase 4A checklist\n")
        val delOk = get("mem_delete_ok")
        val delFail = get("mem_delete_fail")
        sb.append(
            if (delFail > 0) "  [FAIL] Memory delete verified: $delFail delete ke baad bhi baat disk ya prompt mein mili\n"
            else if (delOk > 0) "  [naapa] Memory delete verified: $delOk delete, 0 fail (disk se wapas padh kar aur prompt dobara bana kar jaancha)\n"
            else "  [baaki] Memory delete verified: Settings > Memory se ek yaad hatao\n"
        )

        sb.append("\nPhase 4B checklist\n")
        val over = get("proactive_over_budget")
        val shown = get("proactive_shown")
        sb.append(
            if (over > 0) "  [FAIL] Din mein 2 se zyada suggestion: $over baar\n"
            else if (shown > 0) "  [naapa] Din mein 2 se zyada suggestion kabhi nahi: $shown dikhin, 0 over budget\n"
            else "  [baaki] Suggestion abhi tak dikhi nahi (battery 20% se kam ho ya torch 10 minute on rahe, Home khula ho)\n"
        )

        sb.append("\nPhase 2 exit checklist\n")
        val listenHours = get("wake_listen_ms") / 3_600_000.0
        sb.append(check(trials >= 20, "Wake false reject naapa", "kam se kam 20 try chahiye (Wake test), abhi $trials")).append("\n")
        sb.append(check(listenHours >= 8.0, "Wake false accept / ghanta", String.format(Locale.US, "8 ghante sunna chahiye, abhi %.1f", listenHours))).append("\n")
        sb.append(check(speaking >= 50, "False barge-in per 50 turns", "50 bolte turns chahiye, abhi $speaking")).append("\n")
        sb.append(check(drainHours >= 4.0, "Battery drain", String.format(Locale.US, "4 ghante saaf sample chahiye, abhi %.1f", drainHours))).append("\n")
        sb.append("  [baaki] Premature cut-off %: labelled Hinglish test set se hi naapa jaata hai\n")

        sb.append("\nPhase 3 exit checklist\n")
        val t2Waits = get("t2_waiting")
        val t2Blocked = get("t2_blocked")
        sb.append(
            if (t2Blocked > 0) "  [FAIL] Bina confirm T2 chalne ki koshish $t2Blocked baar hui: code mein bug hai, mujhe bhejo\n"
            else check(t2Waits >= 5, "0 unconfirmed T2 / T3", "5 T2 confirm chahiye (dial), abhi $t2Waits") + "\n"
        )
        sb.append(
            if (get("resume_test_fail") > 0) "  [FAIL] Kill-process resume test fail hua: Debug: task engine ka plan text mujhe bhejo\n"
            else check(get("resume_test_pass") >= 1, "Kill-process resume test", "Settings > Debug: task engine > Test chalao") + "\n"
        )
        if (evalTotal >= EvalCases.EXIT_MIN_CASES) {
            val ok = get("eval_ok")
            val pct = Math.round(ok * 100.0 / evalTotal).toInt()
            sb.append(
                if (ok * 100 >= EvalCases.EXIT_PERCENT * evalTotal) "  [naapa] Multi-intent >= 85%: $ok/$evalTotal = $pct% (${getText("eval_engine")})\n"
                else "  [baaki] Multi-intent >= 85%: abhi $ok/$evalTotal = $pct%. Eval ka \"Galat cases\" text mujhe bhejo, wahi theek karunga\n"
            )
        } else {
            sb.append("  [baaki] Multi-intent >= 85%: Debug: task engine > Eval chalao (30 cases, sirf parser, koi tool nahi chalta)\n")
        }
        return sb.toString().trimEnd()
    }
}
