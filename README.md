# CodeAssistAI Android client

Native Android client built with Kotlin and XML views. Chats, projects and settings are stored
on the device. AI replies are real: pick the on-device Gemma 4 E2B model (Google LiteRT-LM) or a Gemini API key in
**Settings > Voice and AI**. If neither is set up, the app says so in the chat instead of faking a reply.

## Included

- Home chat and searchable chat history with saved drafts; rename, pin, move, export, delete.
- Edit, copy, share, select, delete, and **Regenerate** (last reply) message actions; image previews zoom.
- Local projects with instructions and workspace files; a project chat gets its first reply on open.
- Image, ZIP, and file attachments. Text/source files and pictures go to either brain (Gemma 4 E2B takes pictures directly).
- **Brain**: Gemma 4 E2B (~2.6 GB, Apache-2.0, `litert-community/gemma-4-E2B-it-litert-lm`, file `gemma-4-E2B-it.litertlm`)
  running offline on Google's LiteRT-LM runtime (GPU first, CPU if the GPU does not start), or Gemini over HTTPS
  (streamed replies, model list read from the API). Gemma replaced Qwen2.5 1.5B (llama.cpp), which had replaced Phi-4 mini.
- **Gemma module card**: Download (Android DownloadManager) or Import file (`.litertlm` file-name check),
  Load / Unload / Delete with the rules enforced in code (no Delete while loaded, no second Load). If the old
  Phi-4 file is still on the phone, Options offers to delete it (2.49 GB back).
- **Gemini key card**: key stored encrypted with an Android Keystore AES-GCM key, Test key, model picker.
- **Voice**: mic button inside the chat box, left of Send. Tap = tap-to-talk, hold = push-to-talk
  (slide left cancels, slide up locks to continuous), Continuous mode ("bas" ends it).
  Speech in/out uses Android SpeechRecognizer and TextToSpeech.
- **Tier-0 phone actions** (no LLM, exact short phrases only): time, date, battery, torch, timer, alarm,
  open app, dial a number.
- Settings for text size, glass cards, clearing chat history, opening the last project, Voice and AI.

## Phase 2, part 1: natural conversation (audit PDF Sec 13, Phase 2)

- **Pause guard** (`Endpointing`): a sentence that ends in a connector ("... ka", "... ki", "... aur") or in
  half a time ("alarm laga do kal subah 8") keeps the mic open ~1.3-1.8 s longer instead of being sent half-said.
- **Follow-up window**: after a spoken reply the mic re-opens by itself (Off / 8 s / 20 s). Silence ends it quietly.
- **Dialog acts** (`DialogActs`): a bare "hmm / haan / achha" inside a session is not sent as a message; "bas" /
  "bye" end the session; "aage batao" resumes an interrupted answer; "dobara bolo" repeats the last one.
- **Barge-in** (`BargeInDetector`, `EnergyVad`, `BargeInGate`): while the assistant speaks, its own AudioRecord
  (VOICE_COMMUNICATION source + AEC / noise suppressor when the phone has them) listens for a real interruption.
  Short quiet "hmm" bursts are rejected; sustained or clearly louder speech stops the voice and opens the mic.
- **Filler phrases** (`PhraseCache`): "Hmm, ek second" style acknowledgements are synthesized once with the chosen
  voice and played while a slow brain thinks. Never on the Tier-0 fast path; at most two per turn.
- **Audio focus**: a call or another app taking the audio stops speech; the rest can be resumed.
- **KPIs** (`ConvKpi`): Settings > Activity and debug > "Debug: conversation KPIs" shows first-audio latency
  p50 / p95, barge-in counts, false barge-in per 50 turns, pause-guard saves, follow-up usage.

## Phase 2, part 2A: hands-free (audit PDF Sec 13, Phase 2)

- **Hands-free service** (`AssistantService`): foreground service of type microphone with a notification that has a
  **Stop** button. Opt-in (Settings > Voice and AI > Hands-free), never started at boot, started only while the app is
  visible (Android 11+ rule), re-armed the next time the user opens the app if a battery saver killed it.
- **Wake word** (`VadGatedWakeWord`, `WakeMatcher`): a cheap energy detector keeps the mic; when someone speaks it hands
  a short burst to Android's **on-device** recognizer (Android 13+, offline English India pack) and looks for the phrase.
  Default phrase "hey jarvis" (the greeting is optional, so just "Jarvis" works), editable with a warning for common names.
  Sensitivity Strict / Normal / Loose. Words said right after the phrase ("hey jarvis time batao") are sent as the user's turn.
- **Where the conversation happens**: the home screen. App open: the mic opens at once (short buzz). App closed or in the
  background: Android does not allow a service to open an activity, so a "Haan? Maine suna" notification appears; tap it.
- **Mic sharing** (`WakeCoordinator`): the wake engine stops listening while a voice turn is open and comes back about
  0.8 s after it ends.
- **Wake KPIs** (Settings > Debug: conversation KPIs): detects, recognizer runs, quiet closes after a wake (possible false
  accepts), listening hours and false accepts per hour.

## Phase 2, part 2B: keep hands-free alive and measure it (audit PDF Sec 13, Phase 2)

- **Health check** (`HealthCheck`, `HealthVerdict`): every time the app opens it asks "was hands-free switched on and is the
  service gone without a clean stop?". If yes, Android (battery saver, task clear, force stop) ended it: counted as a kill and
  the service is started again. A phone reboot is told apart by Android's boot counter and is *not* counted as a kill.
- **Watchdog** (`WatchdogReceiver`): while hands-free is on, an inexact alarm wakes a small receiver about every 15 minutes.
  If the service is dead it posts one notification "Hands-free ruk gaya": tapping it opens the app, which restarts the service
  (Android does not allow a microphone service to start from the background, so it cannot restart itself). The alarm chain ends
  after that notice, when hands-free is switched off, and after a reboot.
- **Battery setup guide** (Settings > Voice and AI > Hands-free > Battery setup): shows whether battery optimisation is on for the
  app, opens the system battery list, and opens the phone maker's autostart screen where one is known (Xiaomi, Oppo / Realme,
  Vivo, Samsung, OnePlus, Huawei); steps are written per maker. The same guide pops up once (not more than every 3 days) after
  Android has stopped the service twice within a week.
- **Battery drain meter** (`DrainMeter`, `DrainMath`): while hands-free runs, the battery charge counter is sampled with the
  service heartbeat. Only clean stretches count (screen off, no charger, nothing plugged in or switched on in between). The
  KPI screen shows mAh per hour and a rough 8-hour figure.
- **Wake test** (Settings > Hands-free > Wake test): say the wake phrase 10 times, each try has 9 seconds; the dialog shows
  heard / not heard per try. Results are added to the KPI report, in total and per sensitivity level (Strict / Normal / Loose).
- **Health line** in the Hands-free card: "Android ne N baar band kiya · aakhri baar ...".
- **KPI report** (Settings > Activity and debug > Debug: conversation KPIs) now ends with a **Phase 2 exit checklist** that says
  which numbers are measured and what is still missing (for example "8 ghante sunna chahiye, abhi 1.5").

## Part B1: tool calls behind a policy gate (audit PDF Sec 9.3, 9.6, 11)

- **One plan format, two brains**: Gemini uses function calling, Gemma 4 writes `<tool_call>` text that the app parses (`PlanParser`).
  A broken call gets one repair retry, then a short question: never a guess.
- **8 tools** (time, date, battery, torch, timer, alarm, open app, dial) reuse the Tier-0 code, so they do exactly what the fast path does.
- **Policy gate** (`PolicyGate`): T0 runs, T1 runs with Undo, T2 (dial) waits for a **Haan** tap under the reply; nothing else runs.
- **One merged reply** per turn (`ReplyComposer`); one Undo chip undoes every action of that reply; every action is in the activity log.
- Attachments never get tools. Gemma gets tools only for short messages that mention a phone action (`ActionHint`); Gemini always.
- Automatic Gemini -> Gemma fallback is in **B2**. See BUILD_NOTES.md for the honest status.

## Part B2: graceful cloud fallback (the screenshot-OCR half is history)

- When Gemini times out, has no route / DNS, hits a rate limit, or returns a server error, the current turn
  can use an **already installed** Gemma model. Authentication, invalid-model, malformed-request, safety, and
  user-cancel errors do not silently switch brains.
- Fallback is enabled by default and can be turned off in **Settings > Voice and AI > Reliability and local reading**.
  The selected provider stays Gemini, so the next turn tries Gemini again. The reply says which level was used.
- Gemini uses bounded connect / read timeouts for this route; if it had already streamed partial text, that text is
  cleared before Gemma starts so two answers are not merged. B1's attachment tool block and T2 confirmation remain in force.
- The **attached-image OCR** switch of this part is left over from the Qwen days. Gemma 4 reads pictures directly and nothing
  in the code reads the switch any more, so it changes nothing (it is still shown in Settings; removing it is open).
  Attachments still cannot invoke phone-action tools.
- Settings > Activity and debug > **Debug: conversation KPIs** records successful Gemini L0 turns, L1 / L2 degradation
  and Gemma fallback outcomes. These are on-device counts, not message contents.

## Offline fix, part 1 (history: written for Qwen2.5 1.5B)

After the engine swap to Gemma 4 E2B the prompt-shape test, the ChatML template, the "Qwen options" screen and the token tricks below are
no longer used. `ReplyGuard` is used again, but only for the language check of phase T2 (see the last section). The text is kept as
the record of why those pieces exist.

Why: in a real chat log the offline Qwen answered "Hello" with a long rambling reply, wrote foreign-script junk, looped
("I. I. I.") and forgot the user's name, while Gemini was fine. This part fixes what can be fixed without replacing the
llama-android library (that is part 2).

- **Prompt-shape test** (`LocalTemplate`, `LocalCalibration`): the app does not know what the library does with the prompt
  text (adds its own template? reads `<|im_start|>` as real tokens? stops at `<|im_end|>`?). Before the first offline reply
  it tries three shapes with a tiny "Hello" (hand-built ChatML, library template, plain "User: / Assistant:") and keeps the
  first one that stops by itself and answers sensibly. Once per model file, about a minute. Settings > Voice and AI > Qwen
  options > **Prompt template** can force a shape or run the test again.
- **Lower temperature** (default 0.3, was 0.7): the free API has no top-k / top-p / repeat penalty, so randomness is what
  produced the junk.
- **Reply guard** (`ReplyGuard`): foreign-script junk, letter / phrase loops and low-variety text are detected on the finished
  reply. A clean beginning is kept ("Hello! How can I help you today?"), an unusable reply gets ONE retry with a minimal prompt,
  and if that fails too the app says so instead of showing junk. A reply that ran into the token limit is cut at the last
  full sentence (an open code block is closed).
- **Greetings stay short**: "hello", "kaise ho", "mera naam ... hai" get a small token budget and at most three sentences.
- **Clean history**: failed and broken replies are never put back into the next prompt, and rambling ones are trimmed. The
  user's own turns always stay, so "mera naam X hai" is still there for "mera naam kya hai?". The old double subtraction of the
  system prompt from the history budget is gone.
- **Short system prompt for the phone model** (plain English rules, a Hinglish rule, a "remember this chat" rule). A system
  prompt you edited yourself is still used.
- **Photo question without a readable photo**: a short question with an image Qwen cannot read ("isko jante ho?") now gets a
  straight answer and the two ways forward (OCR switch, Gemini) instead of an invented reply.
- Settings > Activity and debug > **Debug: conversation KPIs** has a new section: chosen template, the three test lines,
  how many replies were trimmed, retried or rejected.

## Network and privacy

- `INTERNET` is used for Gemini, the optional ElevenLabs voice, the weather tool (Open-Meteo) and for downloading models.
  Gemma 4 inference itself is offline. With the phone brain chosen and a Gemini key saved, a heavy plan question can still go to
  Gemini for that turn (T2); the switch is in Settings > Voice and AI and can be turned off.
- `RECORD_AUDIO` is requested the first time the mic is used. The app never records or stores audio;
  Android's speech service handles it (offline pack optional).
- Android app backup is disabled.

## Build and checks

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
Use a real 64-bit ARM phone for the on-device Gemma model; an emulator was never tested.

See `BUILD_NOTES.md` for what is verified and what still needs a device test.

## CI

The GitHub Actions workflow is in `.github/workflows/android-build.yml`. The Android SDK step installs only
`platform-tools` (`packages: platform-tools`): the action's default also installs the old `tools` package, which can
break the step before lint and build run. Licenses are still accepted, and Gradle downloads the platform and
build-tools it needs. A Node.js 20 deprecation warning in the log is harmless.
The workflow builds and uploads both APKs first, then runs `testDebugUnitTest` and uploads the test report
(artifact `unit-test-report`), so a failing test never costs you the APK. Lint findings do not fail the build.

## Phase 3, part 3A: task engine (audit PDF Sec 13, Phase 3)

Phase 3 of the audit roadmap is "Multi-intent DAG, durable queue, corrections + undo, priority classes, risk tiers +
confirmations, progress monitor, partial failure". Part 3A is the engine underneath; corrections and the progress monitor
are part 3B (see BUILD_NOTES.md for what is in which part).

- **Plan = DAG** (`TaskGraph`, `TaskEngine`): every tool now has an optional `after` argument (id of an earlier call, t1, t2 ...
  in the order the brain wrote them). "Pehle torch on karo, phir 5 minute ka timer" becomes torch -> timer. Loops, self links
  and unknown ids are refused like any broken call (Gemma gets its one repair retry, then a short question).
- **Order of work**: dependencies first, then priority class (NOW before SOON: answers and instant effects before alarms /
  timers), then the order the brain wrote them.
- **Partial failure**: when a step fails, only the steps that depend on it are skipped (with the reason); independent steps
  still run. The one merged reply ends with a line such as "1 kaam ho gaya, 1 nahi hua, 1 chhod diya".
- **Confirmations**: a T2 step (dial) waits for the Haan tap under the reply, one at a time; the next T2 step of the same plan
  appears under the same reply after the first answer. Right before any tool runs the engine asks `PolicyGate.mayExecute`
  again, and every attempt that gets past it without a tap would be counted (target 0).
- **Durable queue** (`TaskQueue`): every plan and the state of every step is written to `task_plans.json` before and after each
  tool runs. After a process kill, the next app start (`TaskEngine.recover`) re-runs steps that are safe to repeat (time, date,
  battery, torch, open app), never re-runs a timer or alarm that may already exist (it says "pata nahi hua ya nahi"), drops
  work older than 2 minutes, and puts the result into the chat.
- **Debug** (Settings > Activity and debug > **Debug: task engine**): every stored plan with the state of each step, Copy, and a
  **Kill-test** button that writes a half-done plan and closes the app so the recovery can be checked on the phone.
- **KPIs** (Debug: conversation KPIs): a "Task engine (Phase 3)" block and a "Phase 3 exit checklist".

## Phase 3, part 3B-1: corrections (audit PDF Sec 13, Phase 3)

Right after an alarm or timer, the user can fix it in a few words, without the brain and without a new alarm piling up:

- **Change**: "nahi, 9 baje shaam", "8 baje nahi, 9 baje kar do", "nahi kal subah 7 baje" (alarm); "nahi 10 minute", "galat, 2 ghante" (timer).
- **Cancel**: "rehne do", "wo alarm hata do", "cancel karo" (same as tapping Undo).
- Only a reply from the last 30 minutes that made exactly one alarm or one timer can be corrected. Anything else goes on as before.
- Alarm: the new one is set first, the old one is dismissed by its own label only if that worked (never both lost). Same time as before: nothing is done.
- The reply always says the Clock app should be checked once: some Clock apps ignore the dismiss request.
- Code: `Correction` (pure grammar, `CorrectionTest`), `CorrectionRunner` (phone side), hook in `ChatRunner.quickReply`, KPI block "Corrections (Phase 3B)".
- Roadmap for the rest: `PLAN.md`.

## Phase 3, part 3B-2: weather, progress, time limit, Stop (audit PDF Sec 9.6, 9.7)

- **`weather_now(city)`** (Open-Meteo, no API key, T0, read-only): "Mumbai ka mausam batao". It is the first SLOW tool: it runs on a
  worker thread under a hard **8 second** limit (`StepTimeout`), after every quick step that is ready (the timer is set at once, the
  weather is waited for). If it does not answer, only that step fails; the rest of the plan runs ("mausam batao aur timer lagao").
  The sentence is built from numbers and a cleaned place name only; nothing the server sends goes back to a model.
- **Progress line** (`PlanProgress`): while a slow step runs, the "thinking" note under the reply says "Kaam 2/3 chal raha hai: Mausam";
  after 4 s one more line says the answer is late (audit rules: at most 2 lines per step, 6 s apart; quick steps get no filler).
  On screen only, not spoken.
- **Stop** ends a running plan (`TaskGraph.stop`, the first use of the INTERRUPT class): every step that has not finished is cancelled,
  an answer that comes in later is dropped, finished steps stay (an alarm already set stays set, its Undo chip stays), and the reply says
  "Theek hai, ruk gaya." plus what happened.
- A slow step cannot wait for a step that needs the Haan tap (`ToolSpecs.check` refuses such a plan once, then Gemma gets its repair retry).
  After a crash a network step is never resumed: it is cancelled with "dobara bolo".

## Phase 3, part 3C: measure and edit (audit PDF Sec 12, Appendix A #2, #19, #20)

- **Eval** (Settings > Activity and debug > **Debug: eval (30 Hinglish cases)**): `assets/eval_multi.json` has 30 labelled cases (17
  independent multi-step, 9 "pehle ... phir" with `after`, 4 where the right answer is one question and no tool). The chosen brain
  (Gemini or Gemma) is asked each one with the same prompts as the chat; only the plan is judged (`EvalCases.judge`), **no tool runs**.
  The result is kept and shown in the KPI screen under "Phase 3 exit checklist" (target 85 %).
- **Plan edit** (`PlanEdit`, `PlanEditRunner`): right after a plan reply, "torch wala kaam hata do", "call wala kaam cancel karo",
  "doosra timer hata do". A step that has not run is cancelled and the steps that waited for it are skipped; a step that already ran
  is taken back through its Undo; a name that is not a step of the plan ("petrol wala kaam hata do") gets an honest answer listing what
  the plan has. Two steps of the same tool are never guessed: it asks for "pehla" or "doosra".
- **Plans clear** (same Settings group): removes every stored plan.
- Roadmap for what is left: `PLAN.md`.

## Phase 4, part 4A: Memory Manager (audit PDF Sec 9.5)

- **Settings > AI service > Memory (yaad)**: everything the assistant remembers, in two groups ("Hamesha ke liye", "Abhi ke liye, 24 ghante"),
  with search, edit, delete one, delete the last 5 / 30 / 60 minutes or 24 hours, delete all, text export and Private mode.
- Chat commands: "yaad rakho ki ...", "tum mere baare mein kya jaante ho", "bhool jao", "pichhle 10 minute bhool jao", "private mode on / off".
- A phone-like number is kept for 24 hours and read back; say "hamesha yaad rakho ki ..." to keep it. OTP, password, PIN, card, Aadhaar / PAN, bank
  account and API keys are never saved.
- Stored encrypted (Keystore), not in backups. Every delete is verified by reading the stored value back and rebuilding the prompt.
- Only the offline Gemma gets the (at most 6) relevant facts; "Gemini ko bhi yaad bhejo" is an opt-in switch, off by default.
- Details, limits and the phone check: `BUILD_NOTES.md` (end of file). What comes next: `PLAN.md`.

## Phase 4, part 4B: persona and suggestions (audit PDF Sec 9.7, 9.8, 9.10)

- **Settings > Assistant style**: Tone, Humor, Aap ya tum, Bolne ka lehja (Female = "karti hoon", Male = "karta hoon", Auto = as before), Apne aap suggestions.
  The defaults change nothing in any prompt. Serious messages (emergency, pain, accident) never get jokes.
- **Suggestion card on Home** (only while the app is open, works offline): battery 20 % or less and not charging, or a torch the assistant switched on
  more than 10 minutes ago and that is still on. At most 1 (Low) or 2 (Normal) a day, never more than 2, not between 22:00 and 08:00, 3 hours apart,
  "Ye mat bolo" mutes a kind (switch it on again in Settings).
- No notifications and no calendar / location triggers yet. Details, limits and the phone check: `BUILD_NOTES.md` (end of file). What comes next: `PLAN.md`.

## Test-chat fixes (13 Oct 2026): T1 and T2

Source: `CodeAssistAI_Test_Chat_Report.pdf`. Limits and the phone checks are at the end of `BUILD_NOTES.md`; the rest of the plan is `PLAN.md`.

- **T1**: "mera naam jante ho" no longer saves a name; "WhatsApp on karo" opens WhatsApp.
- **T2, language**: "Hinglish me batao" (also "English me batao", "reply in Hinglish") is kept as a standing choice (memory kind
  `reply_language`, visible and deletable in Settings > Memory) and goes into the prompt of both brains, also for messages written in English.
  If Gemma still answers in the wrong language, the app asks once more with the rule repeated (`ReplyGuard.languageMismatch`); a retry that is
  still wrong is dropped and the caption under the reply says so.
- **T2, stack**: the prompt of both brains states what the brain really is (Gemma 4 E2B on LiteRT-LM, offline; or the Gemini model id),
  so "tum kis model par chalte ho" gets the real answer.
- **T2, heavy plans**: with the phone brain chosen and a Gemini key saved, a request to write a plan / roadmap / spec / prompt goes to Gemini
  for that turn (switch in Settings > Voice and AI; the question then leaves the phone). If Gemini cannot be reached, Gemma answers.


## Test chat ke baad (T3, T4)

- **Plan builder (T3)**: phone brain par "ek prompt do, poora plan, dusra AI coding kar de" jaisi maang par app khud 5 phase wala plan banati hai: chat ke tumhare lines seedha plan me, har hissa alag Gemma call me, jaanch ke saath. Switch: Settings > Voice and AI. Detail BUILD_NOTES me "Phase T3".
- **Sketch (T4)**: "sketch banao: ghar", "login ka flowchart banao", "chat screen ka wireframe banao". Model JSON likhta hai, app chitra banati hai (training nahi). Detail BUILD_NOTES me "Phase T4".
  **Studio stage 1** (kabhi compile nahi hua): detail wale chitra (path / poly / ellipse, rang, background), pichhle chitra me badlav ("background dark kar do"), SVG copy / share. Poori list `SKETCH_STUDIO.md` me.
  **Studio stage 2** (kabhi compile nahi hua): drawer me "Sketch Studio" screen (designs ki list, zoom / pan, undo / redo, naya / badlo, rename / duplicate / delete), asli `.svg` aur `.png` file share, chat ke menu me "Open in Studio". HTML / JS, 3D, layers, variations: stage 3 me.
  **Studio stage 3** (kabhi compile nahi hua): SVG arcs, gradient, move / ghumao / scale, layers (Layers panel), "3 option" (3 alag chitra, ek chuno), web design (HTML / CSS / JS band WebView me: internet nahi, app se koi bridge nahi, atakne par 4 second me band), `.html` file share. **3D scene ka raasta bana hai par `three.min.js` file ZIP me nahi hai** (download nahi ho saki): `app/src/main/assets/sketch/` me rakho, `README.txt` wahin hai. Poori list, safety aur seemayein `SKETCH_STUDIO.md` section 2c me.
- Dono sirf phone brain par. Compile / test abhi baaki (PLAN.md, Phase 0).
