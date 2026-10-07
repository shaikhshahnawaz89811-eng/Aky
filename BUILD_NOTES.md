# Build notes (honest status)

This source was written and statically checked in a sandbox that has **no Android SDK and no network**,
so it has **never been compiled or run**. Checks that were done: all 123 XML files parse, every
`R.id/layout/drawable/color/string` reference resolves, every project import resolves, Kotlin brackets and
strings balance in all 30 files, every member used on the new objects exists. Run the CI / Android Studio
build first and send the compiler output if anything fails.

| Part | Status |
|---|---|
| Message model, Store settings, Keystore key storage | Written, not compiled |
| Phi-4 card: Download, Import, GGUF check, Load, Unload, Delete | Written, needs device test |
| Local generation (llama-android 0.1.1 Free API) | Written, needs device test. No token streaming in the Free API: the reply appears at once. Context is folded into one prompt (single-turn API). |
| Gemini key card, Test key, model list, streamed replies, image/text attachments | Written, never run against the live API |
| Mic in chat box, tap / hold / lock / continuous, waveform from real mic levels | Written, needs device test |
| TTS replies (Hindi voice for Devanagari, Indian English otherwise) | Written, needs device test |
| Tier-0 actions (time, date, battery, torch, timer, alarm, open app, dial) | Written, needs device test. Clock apps cannot set "tomorrow" alarms through intents, the app says so. |
| Voice and AI screen with option dialogs | Written, needs device test |

## Not built yet (from the audit PDF)

Voice barge-in, first-run consent screen, Memory Manager,
Activity log with Undo, confirm sheet for message/payment actions, mic level test / onboarding, offline-degrade
banner, debug overlay, proactive suggestions, persona settings, cost meter.

## Known limits

- Local replies take a while on CPU (about 2 to 10 tokens/s depending on the phone) and cannot be interrupted
  once started: Stop hides the reply, and the next local reply waits until the old one finishes.
- Phi-4 mini needs roughly 3.2 GB free RAM with the default 2048 context.
- Changing context size, threads or temperature applies at the next Load.


---

# Phase 2, part 1 (conversation core) — honest status

Same situation as Phase 1: written and statically checked in a sandbox with **no Android SDK, no Kotlin compiler
and no network**, so it has **never been compiled or run**. Static checks done: brackets / parentheses balance in
all 45 Kotlin files, every project import resolves (nested classes aside), every `R.*` reference resolves. The
numeric logic (pause rules, VAD, barge-in gate) was also re-implemented in Python and run on the test scenarios
below; the Kotlin unit tests in `app/src/test` mirror those scenarios (`./gradlew testDebugUnitTest`).

| Part | Status |
|---|---|
| `Endpointing`, `DialogActs`, `EnergyVad`, `BargeInGate`, `SpokenTracker` (pure Kotlin) | Written, JVM unit tests written, not run |
| `VoiceController` pause guard, follow-up window, dialog acts, resume / repeat, audio focus | Written, needs device test |
| `BargeInDetector` (AudioRecord + AEC) | Written, **needs a real-device test on 3 phones** (AEC is device-dependent) |
| `PhraseCache` fillers (second TTS engine -> WAV files -> MediaPlayer) | Written, needs device test |
| `ConvKpi` + KPI dialog | Written, needs device test |

## Deliberate gaps and limits (read before testing)

- **No volume duck before cancel.** The audit sequence is duck -> verify -> cancel. Platform TextToSpeech cannot
  change volume in the middle of an utterance, so the assistant keeps talking at full volume until the gate says
  "valid" (about 0.3-0.7 s of speech), then stops. Real ducking needs an own PCM player (ElevenLabs streaming, later).
- **First word(s) of an interruption are lost.** The platform SpeechRecognizer cannot take buffered audio on all
  Android versions, so it starts after the detector releases the mic. People usually keep talking; "ruko" alone is fine.
- **Verification is energy-based, not transcript-based.** The audit also checks the partial transcript for "is this
  an echo of the assistant". There is no transcript while the recognizer is off, so the gate uses sustained speech
  and level instead. Expect more false barge-ins on speaker without AEC: default is Off on phones that report no
  hardware AEC, and the KPI screen shows the real false-barge-in rate.
- **Premature cut-off KPI cannot be measured by the app.** It counts how often the guard fired and how often the
  user then really continued. The true rate (target <= 3 %) needs the labelled Hinglish test set.
- Platform SpeechRecognizer may play its start beep on some phones when the follow-up window re-opens the mic.
- Silence hints (`EXTRA_SPEECH_INPUT_*_SILENCE_*`) are ignored by some recognizers.
- Filler wording is gender-neutral on purpose; the persona setting (name, gender form) is Phase 4.
- Only the dialog acts that need no LLM are implemented (backchannel, stop, goodbye, resume, repeat, yes / no with
  a pending question). Correction, reference and side-talk classification need the Phase 3 planner.


---

# Phase 2, part 2A (hands-free service + wake word) — honest status

Same situation as before: written and statically checked in a sandbox with **no Android SDK, no Kotlin compiler and no
network**, so it has **never been compiled or run**. Static checks done: brackets balance in all 53 Kotlin files, every
project import resolves, every `R.*` reference resolves, every `Object.member` use on project objects resolves, all XML
parses. The matching logic of `WakeMatcher` was also re-implemented in Python and run on 20 scenarios; the Kotlin unit
tests in `WakeMatcherTest` mirror them (`./gradlew testDebugUnitTest`). Run the CI / Android Studio build first and send
the compiler output if anything fails.

| Part | Status |
|---|---|
| `WakeMatcher` (pure Kotlin) | Written, JVM unit tests written, not run |
| `WakeSupport`, `WakeCoordinator`, `HandsFree` | Written, needs device test |
| `VadGatedWakeWord` (AudioRecord + on-device SpeechRecognizer) | Written, **needs a real-device test; FAR / FRR unknown until measured** |
| `AssistantService` (foreground service, notifications) | Written, needs device test on Android 12 / 14 and on one aggressive-battery OEM phone |
| `VoiceController.startFromWake`, mic-sharing flag | Written, needs device test |
| Settings > Hands-free card (consent, permissions, phrase, sensitivity) | Written, needs device test |

## Deliberate gaps and limits (read before testing)

- **This is not a real keyword spotter.** The audit asks for an on-device keyword-spotting engine and a measured FAR / FRR.
  `VadGatedWakeWord` is a stand-in behind the `WakeWordEngine` interface so the service, screens and KPIs can be built and
  measured now. A real engine (for example openWakeWord or Porcupine; check licences) replaces one class.
- **The first word of the phrase is often lost.** The recognizer starts a moment after the voice detector fires. The matcher
  therefore never requires the greeting ("hey"), and Normal accepts the last key word alone ("assist"). Cost: more false
  accepts on the word "assist". If misses are the bigger problem, the next step is to pre-roll the buffered audio into the
  recognizer with `RecognizerIntent.EXTRA_AUDIO_SOURCE` (API 33); it was not built because it cannot be tested here.
- **Android 13+ only, on-device only.** On older phones, or without the offline speech pack, the switch refuses and says why:
  the online recognizer would send every noise burst in the room to a server. Tap-to-talk is unaffected.
- **Every speech burst costs one short recognizer run.** A storm guard allows 6 runs per minute, then rests 30 s
  (KPI `wake_backoff`). TV or a busy room can still drain battery: measure it (part 2B adds a drain meter).
- **The recognizer may play its start beep** on some phones. Muting system streams to hide it was rejected (a crash would
  leave the phone muted).
- **No "Haan?" spoken reply.** The platform TTS would be heard by the recognizer. A short buzz and the mic visual confirm instead.
- **App in the background = notification, not auto-open.** Android blocks activity launches from a service. The notification
  uses a full-screen intent, which Android 14 limits and **Google Play restricts to alarm / calling apps**: delete the
  `USE_FULL_SCREEN_INTENT` line from the manifest for a Play build (the notification then shows as a heads-up).
- **Wake events are not counted if the user ignores the notification**, so `wake_empty` (possible false accepts) is a lower bound.
- **Foreground-service start can be refused** (for example if the app was not visible). Counted in `wake_fgs_denied`.
- Wake phrase language: English letters use `en-IN`, Devanagari letters use `hi-IN` (needs that offline pack).


---

# Phase 2, part 2B (health check, watchdog, battery guide, drain meter, wake test) — honest status

Same situation as before: written and statically checked in a sandbox with **no Android SDK, no Kotlin compiler and no
network**, so it has **never been compiled or run**. Static checks done: brackets balance in every new or edited file (three
older files, `SpeechText`, `HomeFragment` and `VoiceAiFragment`, show the same small count difference in the crude checker as
in the 2A zip; the 2B edits added matching pairs only), every project import resolves, every `R.*` reference resolves, all
XML parses. The pure logic (`HealthVerdict`, `DrainMath`, `BatteryAdvice`) was re-implemented in Python and run on every
scenario of the new JVM tests (`HealthVerdictTest`, `DrainMathTest`, `BatteryAdviceTest`, `./gradlew testDebugUnitTest`).
Run the CI / Android Studio build first and send the compiler output if anything fails.

| Part | Status |
|---|---|
| `HealthVerdict`, `DrainMath`, `BatteryAdvice` (pure Kotlin) | Written, JVM unit tests written, not run |
| `HealthCheck`, `WatchdogReceiver`, `HandsFree` / `AssistantService` wiring | Written, needs device test (kill the app from Recents on 2 phones, one with an aggressive battery saver) |
| `DrainMeter` | Written, needs a night-long device test; some phones report no charge counter |
| `BatterySetup`, `BatteryDialogs` (maker screens) | Written, **maker screen names are best effort and may not exist on your phone / version**; every call falls back to the app info screen |
| `WakeTest`, `WakeTestDialog` | Written, needs device test on an Android 13+ phone with the offline English India pack |
| KPI report additions, Hands-free card rows | Written, needs device test |

## Deliberate gaps and limits (read before testing)

- **The watchdog cannot restart hands-free.** Android 12+ refuses to start a microphone foreground service from the
  background (audit Gap B2). It can only tell the user. Restart happens when the user opens the app or taps the notification.
- **The watchdog is not a guarantee.** It is an inexact alarm (about every 15 minutes, later in Doze). A force stop clears it,
  and some phone makers block alarms of apps they have killed. The in-app health check at app start always runs.
- **A kill is "flag set, service gone".** Force stop from Settings, "clear all" in Recents and a battery saver look the same
  and are all counted as kills. A reboot is excluded only when Android's boot counter is readable on the phone.
- **"Down time" is an estimate**: last heartbeat to the moment the app was opened.
- **The drain meter measures the whole phone.** Only clean stretches (screen off, unplugged, no screen-on or charger event in
  between) are counted, so the figure is an upper bound for the app's own share. Measure one night with wake word off for a
  baseline. Some phones update the charge counter in coarse steps or not at all; then the KPI screen says there is no clean
  sample.
- **No one-tap "ignore battery optimisation" request.** `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is restricted by Google Play, so
  the guide opens the system list and the user flips the switch.
- **Wake test measures false reject only.** False accepts need hours of real listening (see KPI report), not a test round.
  Test rounds never touch the real-listening counters. The wake test and the hands-free service never use the mic together:
  the service pauses while the test runs.
- **Phase 2 is not "done" until the KPI checklist says so**: wake false reject / accept, false barge-in, battery drain and the
  premature cut-off still need real device hours and the labelled Hinglish test set. The engine is still the stand-in
  (`VadGatedWakeWord`), not a real keyword spotter.


---

# Phase 2 — status check after part 2B

**Scope in the audit PDF (Sec 13, Phase 2):** foreground service, VAD + endpointing, barge-in + AEC, follow-up window,
backchannel logic, phrase cache, wake word (opt-in). All seven exist in code and are wired into the app (checked: every
class is used from `MainActivity`, `HomeFragment`, `VoiceController`, `AssistantService` or the settings screens).
**Code scope: complete.** Re-checked in this pass: 123 XML files parse, every `R.*` and `@resource` reference resolves,
every project import resolves, every `Object.member` use on the 30 project objects resolves, and brackets balance in
all Kotlin files (checked with a lexer that understands string templates). Still **never compiled or run**.

**Exit criteria (not met yet, cannot be met in a sandbox):** wake-word FAR / FRR measured, false barge-in and premature
cut-off KPIs, battery budget. The app now measures them (Settings > Activity and debug > Debug: conversation KPIs, bottom
checklist) but they need real phone hours and the labelled Hinglish test set.

**Open items inside Phase 2 (deliberate, listed above):** wake engine is a stand-in, not a real keyword spotter; no volume
duck before barge-in cancel (needs own PCM player); first word of an interruption / wake phrase can be lost (pre-roll via
`EXTRA_AUDIO_SOURCE`, API 33, not built because it cannot be tested here).

**Bug fixed in this pass:** the pause guard (`Endpointing`) treated a trailing "on" as an unfinished sentence, so the
finished fast-path commands "torch on" / "flashlight on" waited an extra 1.3 s (the fast-path target is 0.7 s). "on" is
no longer a tail word; `EndpointingTest` covers it. All other unit tests were walked through by hand against the code
(wake matcher, dialog acts, barge-in gate, VAD, spoken tracker, health verdict, drain math, battery advice): no other
failure found.

**CI changes:**
- The Android SDK step uses `packages: platform-tools` (the default `tools platform-tools` made the SDK step fail before
  lint and build ran).
- `testDebugUnitTest` now runs after the APKs are built and uploaded, and its report is uploaded as `unit-test-report`.
  The JVM tests have still never been executed: this is their first run.
- `app/build.gradle` has `lint { checkReleaseBuilds = false; abortOnError = false }` so lint findings cannot fail the
  unsigned release build (lint still runs in its own CI step).

**Compiler runs on GitHub Actions (two so far):**
1. `VoiceController.kt:743 Type checking has run into a recursive problem`: `fillerRunnable` re-posted itself inside its
   own `Runnable { }` lambda. Adding an explicit type was not enough:
2. `VoiceController.kt:744 Variable 'fillerRunnable' must be initialized`: a lambda cannot name the val it is being
   assigned to. Final fix: `fillerRunnable` is now an `object : Runnable` that re-posts itself with `this` (same pattern
   as `AssistantService.heartbeat` and `ComposerController.timerTick`, which were already written that way).
A scan of all Kotlin files found no other lambda that names its own val, and no smart-cast on a mutable property.
Each run only shows the errors the compiler reaches before it stops, so more may appear; unit tests have not compiled yet.


---

# ElevenLabs voice, part 1 of 2 (engine + API key + fallback) — honest status

**Where it is in the audit PDF:** Phase 1 (Sec 13) lists "streaming ElevenLabs"; Sec 15.5 week-1 task 5 is "TtsEngine
(ElevenLabs streaming, backend token) + AudioTrack playback"; Gap G4 (BLOCKER) says the key must never be in the APK; Sec 9.7
has the ElevenLabs table (Flash v2.5, HTTP streaming, `pcm_24000` into AudioTrack, previous_text / next_text, phone-TTS fallback).
**Status before this part:** not present in the zip (no ElevenLabs code at all; speech out was platform TextToSpeech only).

Same situation as before: written and statically checked (brackets balance in every new / edited file, imports and `R.*`
references checked by hand) in a sandbox with **no Android SDK, no Kotlin compiler and no network**, so it has **never been
compiled or run**. Send the CI compiler output if anything fails.

| Part | Status |
|---|---|
| `ai/ElevenLabsClient` (HTTPS POST `/v1/text-to-speech/{voice}/stream?output_format=pcm_24000`, `xi-api-key` header, friendly errors, key probe) | Written, never run against the live API |
| `voice/ElevenPlayer` (fetch thread -> bounded queue -> AudioTrack stream thread, next chunk prefetched, stop = pause + flush, duck / unduck) | Written, needs device test |
| `Store`: `elevenKey` (Keystore-encrypted), `elevenKeyStatus`, `ttsEngine`, `elevenVoiceId`, `elevenModel` | Written |
| `VoiceController`: ElevenLabs path + fallback to phone voice, real volume duck on barge-in candidate | Written, needs device test |
| Settings > Voice and AI > "ElevenLabs voice" card: Add / Change key, Test key, engine ON / OFF, Remove key | Written, needs device test |

## How the key is handled (differs from the PDF's "backend proxy", on purpose)

The PDF wants a backend proxy or short-lived token so no key can be extracted from the APK. This app has no backend, so the
key is **typed by the user and stored encrypted in the Android Keystore** (same as the Gemini key). Nothing is in the build.
That is fine for your own phone / a sideloaded app. For a public release, add a small backend that hands out short-lived
tokens and remove the key card.

## Behaviour

- After a key passes **Test key** (one real 2-character synthesis), replies switch to ElevenLabs once; Options turns it off.
- Any failure (no internet, quota, server error) speaks the **rest of the reply with the phone voice** and shows one line with
  the reason. A network / quota failure pauses ElevenLabs for 60 s; a rejected key marks it **Invalid** and stops using it.
- Chunks are about 220 characters (sentence-sized) so the first audio starts early. `Speaking speed` maps to the API
  `speed` setting (clamped 0.7 to 1.2).
- Volume duck: when the barge-in detector sees a candidate the ElevenLabs voice drops to 30 %; if the gate rejects it the
  volume comes back. (Platform TTS still cannot duck.)

## Known limits of part 1 (part 2 handles the first three)

- **Voice is fixed** to a default premade voice id (`ElevenLabsClient.DEFAULT_VOICE`). No male / female choice, no voice
  list, no preview yet.
- The existing "Voice" row (phone voices) is not engine-aware yet.
- **Filler phrases ("ek second") still use the phone voice** (PhraseCache runs on platform TTS), so they will not match.
- `spoken_up_to` is chunk-level (the chunk that was playing), not word-level: the character timestamps endpoint is not used yet.
- The test key probe costs a couple of credits; a restricted key without text-to-speech permission shows as Invalid / 403.
- The "Get a key" button opens `elevenlabs.io/app/settings/api-keys`; that page address was not verified here.


---

# ElevenLabs voice, part 2 of 2 (male / female, voice picker, matching fillers) — honest status

Same situation as before: written and statically checked (brackets balance in every new / edited file, signatures and imports
checked by hand, JVM tests hand-walked) in a sandbox with **no Android SDK, no Kotlin compiler and no network**, so it has
**never been compiled or run**. Send the CI compiler output if anything fails.

| Part | Status |
|---|---|
| Settings > Voice: **Voice** row is engine-aware (ElevenLabs voice picker when ElevenLabs is on, phone voices otherwise) | Written, needs device test |
| Settings > Voice: new **Voice type (ElevenLabs)** row: Any / Female / Male | Written, needs device test |
| Voice picker: shows only voices of the chosen type, one tap saves + plays a spoken sample, "Refresh list" button | Written, needs device test |
| Choosing Female / Male switches the current voice to the first voice of that kind if it is the other kind, then plays a sample | Written, needs device test |
| `ElevenLabsClient.listVoices` (GET `/v2/voices`, up to 3 pages of 100) + `parseVoices` / `encodeVoices` / `decodeVoices` | Written, never run against the live API |
| `PhraseCache.prepareEleven` + `WavHeader`: "ek second" fillers are now synthesized in the chosen ElevenLabs voice | Written, needs device test |
| ElevenLabs card: shows chosen voice; Options has Voice and Voice type | Written, needs device test |
| `WavHeaderTest`, `ElevenVoicesTest` (JVM) | Written, not run |

## How male / female works

ElevenLabs tags each voice with a `gender` label. The picker filters on it. A voice with no label (or a non-binary label) shows
as "Other" and is only listed under Any. If the account's list cannot be loaded (a restricted key without the voices permission,
or no internet) the screen falls back to two basic voices (Rachel female, George male) and says why.
Library voices that are not in the account may not be usable on a free plan; only voices the list returns are offered.

## Known limits of part 2

- **Phone voices have no male / female choice.** Android does not report a reliable gender for its TTS voices, so Voice type only
  applies to ElevenLabs. The row title says so.
- **Hindi quality depends on the voice.** Flash v2.5 speaks Hindi, but some voices have a strong accent on Roman Hindi. Try a few.
- **Fillers cost a few hundred characters of credit** once per voice / speed / model (they are cached as files). If ElevenLabs is
  unreachable the filler cache simply stays empty and no filler is played.
- The Gemini persona is **not** told the voice gender, so Hindi verb forms in replies (karta / karti) stay as the model chooses.
  Making them follow the voice type is the persona setting from the audit (Phase 4).
- `/v2/voices` and its `has_more` / `next_page_token` fields were taken from the ElevenLabs docs; not tested against a real account.


---

# Qwen2.5 1.5B replaces Phi-4 mini, part A of 2 (engine swap) — honest status

**What was asked:** remove the Phi-4 module, put Qwen2.5-1.5B Q4_K_M (~986 MB) in its place, and make it do what the Gemini
module can do. Audit PDF reference: Sec 9.3 lists the on-device LLM as Tier 2 ("offline / degraded mode: simple chat +
tool calls, only capable devices, tell the user the limits"), and Sec 9.9 (L1 / L2) is where it is used when the network
is slow or gone. Part A is the engine and the chat parity. Part B (below) is tool calls and the automatic fallback.

Same situation as before: written and statically checked in a sandbox with **no Android SDK, no Kotlin compiler and no
network**, so it has **never been compiled or run**. Send the CI compiler output if anything fails.

| Part | Status |
|---|---|
| `ai/LocalLlm` (was `LocalPhi`): same Free-API calls, Qwen KV-cache memory check (28,672 bytes / token + 400 MB) | Written, needs device test |
| `ai/ChatMl` (pure Kotlin): hand-built ChatML prompt, history budget, special-token stripping, reply cleanup | Written, JVM unit tests written (`ChatMlTest`), not run |
| `ai/Modules`: Qwen file name / URL / size, 450 MB sanity floor, GGUF check accepts architecture `qwen2` only | Written, needs device test |
| `Gguf` reader | Unchanged logic; `GgufTest` (JVM) builds synthetic headers, not run |
| `Store`: `localContext` (default 4096), `localThreads`, `localTemp`, `localDownloadId`; provider values `local` / `gemini` | Written. Old `phi4` value reads as `local`; old Phi context / threads / temp keys are not carried over (new defaults) |
| Settings > Voice and AI: Qwen card, "On-device · Qwen" switch, Options (context 2048 / 4096 / 6144 / 8192) | Written, needs device test |
| Old Phi-4 leftovers: unfinished Phi download is cancelled at start; finished 2.49 GB file is kept until the user deletes it (Options) | Written, needs device test |

## What Qwen can do like Gemini now, and what it cannot

| Gemini in this app | Qwen2.5 1.5B on the phone |
|---|---|
| Chat with history, system prompt, reply length, voice-mode short answers | Yes (same `Prompts.system`, history folded into the prompt, 4096-token window by default) |
| Text / source-file attachments | Yes, as many characters as the window allows (half of it) |
| Tier-0 phone actions (time, torch, alarm ...) | Yes, they run before either brain |
| Regenerate, Stop, spoken replies, KPI trace | Yes (Stop hides the reply; a running local reply still finishes in the background) |
| Reads images | **No.** Text-only model. The app says so under the reply. |
| Streams words as they are produced | **No.** The Free llama-android API returns the whole reply at once (streaming is Pro-only) |
| Good Hinglish, long coding answers | **Weaker.** A 1.5B model; expect more mistakes than Gemini, especially in Roman Hindi |
| Tool calls / plans | Part B |

## Deliberate gaps and limits (read before testing)

- **Chat template is hand-built** (ChatML) and `complete()` gets an empty system prompt, exactly as the Phi build did after it
  produced raw continuations. The library wiki lists an "auto chat template" for the Free tier; if replies start with
  a repeated "system" / "user" header, the library is adding a second template: tell me and the call changes to
  `systemPrompt = system` with a plain prompt.
- **Qwen answers are shorter on a 4096 window than on Gemini's.** A big attachment plus history is cut to fit; the app does not
  tell you which part was cut (same as before).
- Context 6144 / 8192 uses more RAM (about 0.2 GB more than 4096) and makes the first words slower: the whole prompt is read
  before the first word, on the CPU.
- Qwen2.5 sampling extras (top-p 0.8, top-k 20, repetition penalty 1.05, as the model card suggests) are not set: the Free API
  config used here only takes context, threads and temperature.
- The 986 MB size is the Hugging Face listing for `bartowski/Qwen2.5-1.5B-Instruct-GGUF` at the time of writing. The
  Qwen2.5-Coder-1.5B-Instruct Q4_K_M file is the same size and also has architecture `qwen2`: it can be loaded through Import file.
- Download link and file name were read from the Hugging Face page, not downloaded in the sandbox.

## Part B is now split in two

| | Scope (items of the old Part B list) | Status |
|---|---|---|
| **B1** (this zip) | 1. one plan format for both brains, JSON check, repair retry, question instead of a guess · 2. tools behind the risk-tier policy gate with ActivityLog / Undo, T2 tap-to-confirm | Written, never compiled |
| **B2** (next) | 3. automatic fallback levels L0 / L1 / L2 (Gemini timeout or no internet -> Qwen with one honest line, and back) · 4. optional OCR so Qwen can read screenshots of code (needs a new dependency) · Settings switch + KPI lines for both | Not started |

B2 needs B1 because the fallback has to hand the SAME plan format to whichever brain answers.

---

# Qwen2.5 tools, part B1 of 2 (plan format + policy gate) — honest status

Same situation as before: written and statically checked in a sandbox with **no Android SDK, no Kotlin compiler and no
network**, so it has **never been compiled or run**, and the new JVM tests have **not been run** (CI only runs
`assembleDebug` / `assembleRelease`; run `./gradlew testDebugUnitTest` yourself). Send the CI compiler output if anything fails.

## What it does

You say "kal subah 8 baje ka alarm laga do aur battery batao" (words that are NOT an exact Tier-0 phrase). The brain proposes tool
calls, the app checks and runs them, and you get ONE merged reply.

| Piece | Where | Status |
|---|---|---|
| Tool table: 8 tools (`time_now`, `date_today`, `battery_level`, `torch_set`, `timer_set`, `alarm_set`, `app_open`, `call_dial`), argument rules, tiers | `ai/ToolSpecs`, `ai/PlanTypes` | Written, JVM tests written |
| Qwen: tools go into the ChatML system text in Qwen's own `<tools>` / `<tool_call>` format; the app parses the reply | `ai/ChatMl` (`withTools`, `appendTurn`), `ai/PlanParser` | Written, JVM tests written |
| Qwen: broken tool call -> ONE repair retry (the error is sent back) -> still broken -> short question, never a guess | `ai/ChatRunner.runLocal` | Written, needs device test |
| Gemini: tools sent as function declarations (mode AUTO), `functionCall` parts read from the stream | `ai/GeminiClient.streamWithTools` | Written, **not tested against the real API** |
| Policy gate: T0 run, T1 run + Undo, T2 readback + tap, anything else refused. Tier comes from the table, never from the model | `ai/PolicyGate` | Written, JVM tests written |
| T2 (`call_dial`): reply says "Dialer mein NUMBER khol dun?" and a **Haan / Nahi** button row appears under it; Haan opens the dialer (you still press Call). A pending confirm older than 10 minutes is refused | `ai/PlanExecutor.confirm`, `chat/MessagesAdapter`, `item_msg_ai.xml`, `HomeFragment` | Written, needs device test |
| Tools reuse the Tier-0 code (`Tier0.runAlarm`, `runTorch`, ...): same Clock-app intents, same Undo tokens | `ai/Tier0` (refactor), `ai/ToolRunner` | Written, needs device test |
| One merged reply: read-only answers first, then actions, then errors, then a pending question | `ai/ReplyComposer` | Written, JVM tests written |
| Activity log + Undo: one reply can hold several undo ids; the single Undo chip undoes all of them | `ai/ActivityLog` (comma list), `Message.undoId` | Written, needs device test |

## Safety rules that are in the code

- **Attachments = no tools.** Text from an attached file is untrusted (audit Sec 11.3, prompt injection), so a message with an
  attachment never gets tools. Tier-0 already worked this way.
- **Tool arguments are checked in code** (ranges, number format, at most 4 calls per turn) before anything runs.
- **A T2 action never runs without the tap.** Voice "haan" is not accepted (audit: voice alone is not a valid confirmation without
  speaker verification). The spoken reply therefore ends with "...neeche Haan dabao.", not with a question mark.
- Failed actions (no flash, app not found, Clock app missing) are shown but are **not** written to the activity log and get no Undo.

## Deliberate gaps and limits (read before testing)

- **Qwen only gets tools when the message looks like a phone action** (`ActionHint`: short, no code, an action word like alarm / timer /
  torch / battery / time / call / open / kholo). Reason: the tool block is ~2.2k characters (~700 tokens of the 4096 window) and a
  1.5B model calls tools by mistake. Gemini always gets the tools. A phone request without those words goes to Qwen as plain chat.
- **With tools on, use the 4096 context** (Options). At 2048 almost nothing is left for history.
- **A 1.5B model will often get tool calls wrong** (wrong hour, 12-hour value in `hour`, invented number). The code catches
  malformed calls, not wrong-but-valid ones. T1 actions say what they did ("Alarm 08:00 AM (kal) ke liye set kar diya") and have Undo.
- **No confidence axis yet.** The audit matrix (risk x confidence) is not implemented: the gate only looks at the tier, so it
  asks more, never less. STT word confidences are not available from Android SpeechRecognizer in this app.
- **No repair retry for Gemini.** Its calls are schema-shaped; a refused call (for example hour 25) becomes the short question.
- **The model's own words around tool calls are dropped** ("Theek hai, abhi karta hoon"): the reply is built from the real results.
- **`ambiguities[]` from the audit's plan format is not a field.** AM / PM is handled by the model giving a 24-hour `hour` and by the
  readback in the result line; the system prompt tells the model to ask ONE question when something is missing.
- **Tasks run one after another on the main thread** (like Tier-0), not in parallel; there is no DAG, priority class or pause / resume
  yet (audit Phase 3).
- **Only one T2 action per turn**; a second one gets "Ek baar mein ek hi cheez confirm kar sakta hoon".
- Gemini function declarations use the proto type names (`OBJECT`, `STRING`, `INTEGER`, `BOOLEAN`); written from the API docs, not run.
- The `Message` class got two new nullable fields (`actions`, `pending`); chats saved by older builds load fine (they are `null`).

## Part B2 (next)

3. Degrade levels (audit Sec 9.9): Gemini timeout / no internet switches that turn to Qwen with one honest line, and back; the level
   is shown and counted in the KPI screen.
4. Optional: OCR so Qwen can use screenshots of code (needs a new dependency).
