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
| **B1** (base ZIP) | 1. one plan format for both brains, JSON check, repair retry, question instead of a guess · 2. tools behind the risk-tier policy gate with ActivityLog / Undo, T2 tap-to-confirm | Included; see B1 notes above |
| **B2** (this update) | 3. automatic fallback levels L0 / L1 / L2 (Gemini timeout or no internet -> Qwen with one honest line, and back) · 4. optional OCR so Qwen can read screenshots of code · Settings switch + KPI lines for both | Implemented below; still needs Android build / device verification |

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

## Part B2 scope (as listed in the Part A / B1 handoff)

3. Degrade levels (audit Sec 9.9): Gemini timeout / no internet switches that turn to Qwen with one honest line, and back; the level
    is shown and counted in the KPI screen.
4. Optional: OCR so Qwen can use screenshots of code (needs a new dependency).


---

# Qwen2.5 tools, part B2 of 2 (fallback + optional OCR) — implementation status

## Implemented in this source

| Piece | Behavior |
|---|---|
| L0 | Successful Gemini turns are counted as full-cloud turns. The chosen provider is not mutated. |
| L1 | Connect/read timeouts, transient I/O, HTTP 408 / 429 / 5xx may fall back to an installed Qwen model for this turn. |
| L2 | DNS / no-route failures may fall back to the installed Qwen model for this turn. |
| Return to Gemini | Automatic fallback is per-turn; the next user message starts with the selected Gemini provider again. |
| Honest status | The in-progress label and final reply identify degraded/offline fallback. Any partial Gemini stream is cleared before local generation. |
| Safe exclusions | Invalid credentials, HTTP 4xx other than 408 / 429, safety blocks, malformed requests, cancellation, and unrelated exceptions do not trigger fallback. |
| Missing local model | The app says fallback was unavailable and tells the user to install Qwen; it never downloads a 986 MB model silently. |
| Screenshot OCR | Optional, disabled by default, bundled ML Kit Latin recognition, on-device only, added only to the Qwen text prompt. |
| B1 safety | Attachments still do not receive phone-action tools; Gemini continues to receive images only on its existing cloud path. |
| Measurements | Local KPI counters cover L0, L1, L2, fallback success / unavailable / error, and OCR readable / failed images. No content is stored in the KPI counters. |

## Verification status

The source ZIP is based on the B1 tree and preserves its application ID and existing data model. The changes have not
been built as an Android app or run on a phone yet. In this handoff, Gradle configuration and the resolved runtime
dependency graph both succeeded; all 123 XML files and scanned resource references passed static checks. The new
`FallbackPolicy` and its four JVM tests compiled in an isolated Kotlin harness, and all four passed with JUnitCore.
The harness's Gradle test-worker fork did not connect, so this is not reported as a successful Android Gradle test task.
Full Android compile / lint / APK and device verification still require an Android SDK / accepted SDK license, then
`./gradlew assembleDebug testDebugUnitTest lintDebug`. OCR language coverage is Latin / English, not Devanagari.


---

# Offline fix, part 1 of 2 (Qwen answers) — honest status

**Why:** the Transfer Dock chat log showed the offline Qwen answering "Hello" with a long ramble that contains foreign-script
junk, "mera naam shahnawaz hai" with "Your name is S. N. H. H. H. ... I. I. I.", and a photo question with a loop, while
Gemini answered correctly and remembered the chat. The code review that followed (see the report) found: hand-built ChatML
that the library may or may not read as intended (no stop at `<|im_end|>`), temperature 0.7 with no top-k / top-p / repeat
penalty (free API), junk replies fed back into the next prompt, a long English system prompt for a 1.5B model, an image-only
question that reached the model as a bare sentence, and the system prompt counted twice against the history budget.

Same situation as before: written and statically checked in a sandbox with **no Android SDK, no Kotlin compiler and no
network**, so it has **never been compiled or run**. Static checks done: brackets / strings / templates balance in every new
or edited Kotlin file (lexer that understands string templates), every project member used on the new objects exists, the
new JVM tests were hand-walked. The detection logic (`ReplyGuard`, `ChatMl.clean` / `cutDialog`, small-talk rule) was
re-implemented in Python and run on the **real garbage replies from the chat log** plus normal answers, code, lists and
Hinglish text: junk and loops are caught, normal text is left untouched. Send the CI compiler output if anything fails.

| Part | Status |
|---|---|
| `ReplyGuard` (pure Kotlin): junk / loop / low-variety detection, trim to last sentence, small-talk limit, history cleaning, photo-question rule | Written, JVM tests written (`ReplyGuardTest`), not run |
| `ChatMl.clean` (leaked headers), `ChatMl.cutDialog`, `ChatMl.buildPlain`; history from `ReplyGuard.cleanHistory` | Written, JVM tests written (`ChatMlGuardTest`, old `ChatMlTest` unchanged), not run |
| `LocalTemplate` (pure): three prompt shapes + probe judge | Written, JVM tests written (`LocalTemplateTest`), not run |
| `LocalCalibration`: runs the probe on the phone once per model file | Written, **needs a device test** (this is the part that settles the real cause) |
| `ChatRunner.runLocal`: shape, small token budget for greetings, image notice, guard, one retry, error message | Written, needs device test |
| `Store`: `localTemp` default 0.3 (new key `local_temp_v2`), `localTemplate*`, `systemPromptIsCustom` | Written |
| Qwen options: Temperature list has 0.3, new **Prompt template** row | Written, needs device test |
| KPI screen: "Offline Qwen jawab" section | Written, needs device test |
| `versionCode 9`, `versionName 1.8` | Done |

## How to check it on the phone (about 10 minutes)

1. Install, open Settings > Voice and AI > Qwen options: Temperature shows 0.3 and the row **Prompt template · auto (test baaki)** exists.
2. Choose the on-device brain and send **Hello**. The first reply is slower (label "Model ki jaanch: chatml ..." for about a minute,
   once). Expected: a short greeting, not a ramble.
3. Settings > Activity and debug > Debug: conversation KPIs > section **Offline Qwen jawab**: it shows which shape was chosen and
   the three test lines (tokens, "ruka / nahi ruka", "saaf / gadbad"). **Send me that text.** If it says "KOI shape saaf nahi
   nikla", the library itself is the problem and part 2 (engine swap) is the real fix.
4. Send "mera naam shahnawaz hai", then "mera naam kya hai?". Expected: the name comes back.
5. Attach a photo and send "isko jante ho?". Expected: a straight "Qwen photo nahi dekh sakta" answer with the OCR / Gemini options.

## Deliberate gaps and limits (read before testing)

- **This is a safety net plus a calibration, not a new engine.** The Free llama-android API still cannot stream tokens, cannot be
  cancelled mid-reply, and has no top-k / top-p / repeat penalty. Part 2: an engine with those controls, token streaming, a
  working Stop, KV-cache reuse (second reply faster).
- **The test costs time once.** In the worst case three tiny runs (48 tokens each) before the first offline reply, about a
  minute on a slow phone. The result is remembered per model file; forcing a shape in Qwen options skips it.
- **The guard cannot make a bad answer good.** It removes junk and loops, keeps a clean beginning and retries once. A wrong but
  fluent answer from a 1.5B model passes. Hinglish quality needs the bigger model (part 2: model picker, Qwen2.5-3B).
- **Tools (alarm, torch ...) from the model only work in the ChatML shape.** If the test picks another shape, exact Tier-0 phrases
  still work; flexible phrasing falls back to plain chat.
- **Thresholds are first guesses** (run of 5 equal words, a phrase 5 times covering 30 % of the text, unique-word ratio 0.40,
  at least 25 characters worth keeping). They were tuned on the one chat log and a few synthetic loops, not on a test set.
  `local_guard_trim / retry / fail` on the KPI screen show how often they fire; a high `fail` count means look at the shape test first.
- **Foreign-script check** assumes the user writes Latin or Devanagari; if they ask for Chinese, Japanese or Korean in words
  ("japanese mein", "translate") the check is skipped for that message.
- **No cross-chat memory yet.** The name is remembered inside one chat because the user's turns stay in the prompt. A real
  memory (Memory Manager, "yaad rakhna", never-store list, audit Sec 9.5) is part 2.
- **Old saved temperature is ignored**: phones that had the previous setting (default 0.7) start at 0.3 after the update.


# Offline fix, part 2 (make Qwen work like Gemini, offline-style) — honest status

## What Gemini does that Qwen did not (read from the code and the chat log)

| Gemini path | Offline path before this part |
| --- | --- |
| Gets all 8 phone tools on every plain message and picks one from any phrasing | Tools only when `ActionHint` saw an action word, and "bhaje", "uthna", "yaad dilana" were not action words |
| Sees up to 24 turns / 24k characters of the chat | About 9k characters, and phone-action replies ("Torch off kar di.") were counted as conversation |
| Gets the photo itself (inline JPEG) | Photo refused unless an OCR setting was on; even then only text |
| Follows "answer in Roman Hinglish" from one line | A 1.5B model understands Hinglish but answers in English |
| Streams long answers without runaways | One digit repeated 80 times inside a code block was shown as is |

The chat log shows each of these: "Kal mujhe 8 bhaje uthna hai" -> "Torch off kar di." (no alarm word, and the prompt was full of
torch replies the model copied), "Off" -> English rambling (a bare "off" was not tied to the torch), "Torch o" -> torch off
(a guess), "Create python 2 line code" -> `Hello, World! 2000000...` (runaway not caught).

## What changed (files)

- `HinglishGuide.kt` (new): detects a Roman-Hinglish message; two example exchanges are put in front of the history for such
  a message; a finished English-only answer to a Hinglish message gets ONE retry with a blunt Hinglish system prompt and
  "Ji, " already written for the model to continue (ChatML and plain shapes; not the library shape). The first answer is kept if
  the retry is not clean and really Hinglish. KPI: `local_lang_retry`.
- `LocalMemory.kt` (new) + `Store.userFacts`: a list of at most 12 short facts, on the phone. Filled only from what the user
  states ("mera naam Rahul hai", "main Pune mein rehta hoon", "mujhe cricket pasand hai") or says to remember ("yaad rakho ki ...").
  "tum mere baare mein kya jaante ho" lists them, "bhool jao" clears them. These commands never reach the model. The facts are
  added to the offline system prompt only (Gemini is untouched).
- `ChatRunner.localHistory`: phone-action exchanges are removed from what the offline model sees.
- `Tier0`: "8 bhaje" is read as "8 baje"; "kal 8 baje uthna hai" / "utha dena" / "wake me" set an alarm without the word
  alarm (not when the sentence is a statement: "padta", "tha", "kab" ...); "10 minute baad yaad dilana" starts a timer; "torch" with no
  direction asks instead of guessing; a bare "off" / "band karo" / "on" right after a torch reply controls the torch.
- `ActionHint`: more wake / reminder words; `Prompts.LOCAL_TOOL_HINT`: three worked examples for the tool prompt.
- `AttachmentText.analyzeImage`: for the offline model a photo becomes plain text: ML Kit OCR text plus up to 6 object/scene
  labels (bundled ML Kit image labeling, new dependency `image-labeling:17.0.9`), with a closing note that the model cannot see the
  photo and must not guess who a person is. `Store.localScreenshotOcr` now defaults to ON.
- `ReplyGuard`: a character repeated 30+ times is cut; if that leaves an open code block the reply is rejected so the existing retry runs.
- `Prompts.LOCAL_SYSTEM`: explicit language rule with an example, "use earlier messages and known facts".
- Version 1.9 (code 10).

## Verification status — read this first

- **Not compiled and not run.** The machine that produced this zip has no Kotlin compiler and no Android SDK. The regular
  expressions (memory, Hinglish markers, wake phrase, runaway run) were run in plain Java (same regex engine) with the example
  sentences from the chat log and behaved as described. Everything else is unbuilt Kotlin: expect to fix a compile error or two.
- New unit tests (`LocalMemoryTest`, `HinglishGuideTest`, additions to `ActionHintTest` and `ReplyGuardTest`) are written but were
  not run. `Tier0` and `AttachmentText` use Android classes and have no JVM test.
- The ML Kit image-labeling artifact version was typed from memory; if Gradle cannot resolve `17.0.9`, use the newest 17.x.

## Deliberate gaps and limits

- **No real vision.** The free llama-android API has no image input. Labels are rough guesses ("Dog, Grass, Plant"), OCR reads
  Latin text only. A face is never identified. Real offline vision needs a vision GGUF (for example Qwen2-VL or SmolVLM with its
  projector file) and a library with vision support; that is a separate, larger step.
- **Hinglish quality is bounded by a 1.5B model.** The examples and the retry fix the language (English answer to a Hinglish
  question), not wrong facts or clumsy grammar. The planned Qwen2.5-3B picker is still the real quality step.
- **Memory is explicit only.** It does not summarise old chats and it does not learn from hints. Facts are plain text in app
  preferences; the memory commands also appear in the activity log as "Yaad".
- **Voice**: the TTS picks `en-IN` for Roman text and `hi-IN` for Devanagari; Roman Hinglish is therefore read by the English
  voice. That part was not changed.
- **A reply that needs a retry costs time**: a language retry is one more generation (about 10-40 s on a slow phone).
- "kal 8 baje" said before 8 am still gets the Clock-app rule from part B (the app cannot set a tomorrow-only alarm); unchanged.

## How to check it on the phone (about 10 minutes)

1. Chat: "kaise ho?" and "mujhe chai banana sikhao" -> answers in Roman Hinglish (caption may say "hinglish retry").
2. "mera naam Rahul hai", new chat, "tum mere baare mein kya jaante ho" -> lists the name. "bhool jao" -> cleared.
3. "torch on", then "off" -> torch turns off. "torch o" -> asks on or off.
4. "kal subah 6 baje utha dena" -> alarm path (not a torch reply). "10 minute baad yaad dilana" -> 10 minute timer.
5. Attach a photo of a printed page and ask "isme kya likha hai" -> text answer; a photo of a dog -> "kutta/dog" type answer, no names of people.
6. "create 2 line python code" -> a short code block, no runaway digits.

# Offline engine swap: Qwen2.5 1.5B (llama.cpp) -> Gemma 4 E2B (LiteRT-LM) — honest status

## Why
Qwen 1.5B was too small for good Hindi + English, re-read the whole prompt on the CPU every turn (slow), could not see
photos, and needed a lot of repair code. Gemma 4 E2B (2.58 GB, Apache-2.0) runs on Google's LiteRT-LM runtime with GPU/NPU
support, takes images directly and handles Hindi and English. Google's own table for a flagship phone: prefill 557 tok/s and
decode 47 tok/s on CPU, 3808 / 52 on GPU. A mid-range phone will be slower.

## What changed
- `LocalLlm.kt`: rewritten on `com.google.ai.edge.litertlm` (Engine / Conversation). GPU first, CPU if the GPU start fails
  (the result is remembered in `Store.localBackend`). Streaming replies, history as real user/model messages, images as
  `Content.ImageBytes`. The old API (`loaded`, `busy`, `unload`, `LoadFailure`, `generate`) is kept so Settings still compiles.
- `Modules.kt`: model card, URL (`litert-community/gemma-4-E2B-it-litert-lm`, file `gemma-4-E2B-it.litertlm`), size checks;
  the GGUF header check is replaced by a `.litertlm` file-name check on import.
- `ChatRunner.runLocal`: new, much shorter. Photos go to the model as pictures. Phone tools still use the text `<tool_call>`
  format with one repair retry (the model's native tool API was not used: its support for Gemma 4 is not documented).
  The memory facts (`LocalMemory`), the removal of phone-action turns from the history and Tier-0 stay.
- Not used any more (still in the source, still unit-tested): `ChatMl`, `LocalTemplate`, `LocalCalibration`, `HinglishGuide`
  (the Hinglish few-shot and language retry were Qwen workarounds), `Gguf`.
- All user-facing "Qwen" strings now say "Gemma". The "Qwen options" screen in Settings (prompt shape, context, threads,
  temperature) no longer does anything.
- Build tools: AGP 8.5.2 -> 8.10.1, Gradle 8.7 -> 8.11.1, Kotlin 1.9.24 -> 2.4.0 (the runtime pulls in kotlin-stdlib 2.4.0, which needs a 2.4 compiler), compileSdk 34 -> 36, coroutines 1.8.1 -> 1.11.0
  (a third-party note says the runtime crashes with NoSuchMethodError on older coroutines). The llama-android dependency is
  removed. `AndroidManifest.xml` asks for `libOpenCL.so` and `libvndksupport.so` (needed for the GPU backend).
  Version 2.0 (code 11).

## Verification status — read first
- **Nothing here was compiled or run.** The runtime API (names such as `Engine`, `EngineConfig(modelPath, backend,
  visionBackend, cacheDir)`, `Contents.of(...)`, `Content.ImageBytes`, `sendMessageAsync(...): Flow`) was copied from Google's
  Android guide (page dated 2026-09-04), but `Content.ImageBytes(ByteArray)` and the exact `Message.toString()` streaming
  behaviour are assumptions. Expect compile errors; send the log.
- The Kotlin / AGP / compileSdk jump is the most likely source of build errors in the old code (Kotlin 2 is stricter).
  If the log says the runtime's Kotlin metadata is newer than the compiler, raise Kotlin (for example 2.3.x).
- `minSdk` is still 26. If the manifest merger complains that the runtime needs a higher minSdk, raise it to what it says.
- `litertlm-android:latest.release` is a moving version on purpose (the exact number was not known); pin it once it builds.

## Phone check
1. Settings > Voice and AI: Download (Wi-Fi, 2.6 GB) or Import a `gemma-4-E2B-it.litertlm` you already have, then Load.
   If the download fails with an access error, download the file in a browser and use Import.
2. "kaise ho?" -> a Hinglish answer, streaming. "mera naam Rahul hai", new chat, "mera naam kya hai?".
3. Attach a photo, ask "isme kya hai?" -> the answer should describe the picture.
4. "torch on", "kal subah 6 baje utha dena" -> phone actions. Caption of each reply shows GPU or CPU.
5. If the first load is slow (up to ~10 s, longer the very first time), that is the runtime preparing its cache.
6. The old 986 MB Qwen file is still in the app's models folder; delete it by clearing the app's storage or from a file manager.

# Offline speech model (sherpa-onnx, IndicConformer-style) — honest status

## What it is, and why it is not Vosk
Vosk runs Kaldi models. The model asked for (an AI4Bharat IndicConformer export for sherpa-onnx) is a NeMo CTC ONNX file;
Vosk cannot load it, only sherpa-onnx can. So the engine slot is built on sherpa-onnx (`OfflineRecognizer`, NeMo CTC).
The slot is generic: any other NeMo-CTC model (`model.int8.onnx` + `tokens.txt`) works by changing the link.
A real Vosk engine could be added as a second `SttEngine` later; the interface is ready for it.

## What changed (files)
- New `voice/SttEngine.kt`: interface with the five calls `VoiceController` already made on `SpeechRecognizer`, plus
  `PlatformStt` (a pass-through to the Android recognizer = old behaviour, still the default).
- New `voice/OfflineStt.kt`: the offline engine. Own `AudioRecord` (16 kHz), `EnergyVad` for start / pause, then one model run
  per utterance; answers through the same `RecognitionListener` callbacks (results, no-match, speech-timeout, errors).
- New `voice/SherpaBridge.kt`: calls `com.k2fsa.sherpa.onnx.*` by reflection, so the project compiles and runs with NO AAR.
- New `voice/OfflineSttStore.kt`: model files (`filesDir/stt_offline/`), resumable download, background load, auto-unload after 90 s.
- New `voice/OfflineSttUrls.kt` (+ `OfflineSttUrlsTest`): link -> two file URLs (HF repo page, folder, or direct .onnx).
- `VoiceController.kt`: the recognizer field is an `SttEngine`; the engine is chosen in `startRecognizer()`; the three
  "is recognition available" checks also accept the offline engine. Nothing else in the voice loop was touched.
- `Store.kt`: `sttEngine` ("platform" default | "offline"), `offlineSttUrl` (empty by default).
- `VoiceAiFragment.kt`: new section "Offline speech model (sherpa-onnx)": engine picker, model link, download / cancel, delete.
- `app/build.gradle`: `implementation fileTree(dir: 'libs', include: ['*.aar'])` and a `pickFirsts` for `libc++_shared.so`.
  `.github/workflows/android-build.yml`: optional step that downloads the AAR (never fails the build). `app/libs/README.txt`.

## Verification status — read first
- **Nothing here was compiled or run.** There was no Gradle / Kotlin compiler (and no network) where this was written.
  The URL logic was checked with an equivalent Python port only. Expect to send a build log if something does not compile.
- **The sherpa-onnx AAR is unverified.** The CI step assumes `https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.39/sherpa-onnx-1.12.39.aar`.
  The file name `sherpa-onnx-1.12.39.aar` exists in the wild, but that exact release URL was not opened. If the step warns,
  download an AAR from the sherpa-onnx releases page yourself and put it in `app/libs/`.
- **Reflection names are from memory** of the sherpa-onnx Kotlin API (`OfflineModelConfig.nemo`, `OfflineNemoEncDecCtcModelConfig.model`,
  `OfflineRecognizer(assetManager, config)`, `createStream`, `acceptWaveform`, `decode`, `getResult(...).text`). A different
  AAR version may rename something; the app then shows "Ye sherpa-onnx version app ke code se match nahi karta (<name>)".
- **No model link is built in.** The line that was pasted ("IndicConformer Hinglish Swift - sherpa-onnx") carried only a title, not a URL.
  Paste the real link in Voice and AI > Offline speech model > Model link. A silent-failure trap to know about: NeMo exports
  need ONNX metadata (`normalize_type=per_feature`, `vocab_size`, `subsampling_factor`, `feature_dim=80`); without it a model can
  load, run and return EMPTY text. If the engine always says "no match", the model file is the first suspect.
- APK gets about 50-60 MB bigger once the AAR is present (native libraries for all phone architectures).

## Deliberate gaps and limits
- No live partial text: it is an offline model, the words appear after you stop talking.
- One utterance is cut at ~28 s (Conformer memory grows with the square of the length: roughly 300-600 MB while running).
- Endpointing is a simple energy VAD: in a loud room the pause may be detected late. Tapping the mic again sends at once.
- The wake word (`VadGatedWakeWord`) and barge-in still use the Android recognizer / their own mic code: unchanged.
- "Speech language" is ignored while the offline engine is on; the model decides the language and script.
- Hinglish: whether text comes out in Devanagari or Roman depends on the model, not on the app.

## Phone check
1. Settings > Voice and AI > Offline speech model: Model link -> paste the link -> Model (tap) -> Download (Wi-Fi, ~140-190 MB).
2. "Engine library" must say "sherpa-onnx · mila". If it says "Nahi mila (AAR)", the AAR is not in the APK.
3. Speech engine -> Offline model. Tap the mic in chat, say a sentence, stop: the text appears after a second or two.
4. Switch the engine back to Android (Google): voice must behave exactly as before.
5. Delete model: files go, the engine falls back to Android.


---

# Offline speech model: Import / Load / Unload (v4)

The offline speech model now has a card in Settings > Voice and AI, built like the Gemma card, instead of only a
"Model link" row that downloaded straight from a URL.

| Status badge | Buttons |
|---|---|
| Not imported | Import files, Download |
| Importing / Downloading | Cancel |
| Unloaded | Load, Import files, Delete |
| Loading | Loading... (disabled) |
| Loaded | Unload, Delete (asks to Unload first) |
| Error | message in red; Load (if files are there) or Import files / Download |

- **Import files**: pick the model (any `*.onnx`, copied as `model.int8.onnx`) and `tokens.txt` together in the file picker.
  Sizes and "is this really the file" (webpage / git-lfs pointer) are checked before anything old is replaced.
  A single file can be picked if the other one is already installed.
- **Download** is still there, using the "Download link (optional)" row, but it is no longer the main path.
- **Load** keeps the model in RAM until **Unload** (no 90 s timeout). Without Load the mic still loads it on first use
  and drops it after ~90 s idle, and the badge follows (Loaded -> Unloaded).
- Unload is refused while the mic is listening. Delete is refused while loaded or while a copy / download runs.
- New pure-Kotlin `OfflineSttImport` (which picked file is model / tokens) with `OfflineSttImportTest`.

Status: written and bracket-checked, **not compiled** (no Kotlin compiler in the sandbox). Send the CI log if anything fails.


---

# v5: offline speech model, AAR source fixed + Settings card spacing

**Why:** in v4 the Offline speech model card (Import files, Download, Load, Unload, Delete) was complete in code but
could not do anything on a phone built from CI: the CI step downloaded the sherpa-onnx AAR from a GitHub release URL
that was never opened, and a failed download is silent by design. No AAR in the APK means the card says "Nahi mila
(AAR)", Load refuses, and the engine cannot be switched to Offline model.

**Not changed:** the engine is still sherpa-onnx (NeMo CTC, IndicConformer-style), not Vosk. There is no Vosk / Kaldi engine
in this source. `SttEngine` is the slot where one could be added.

## What changed
- New `scripts/fetch_sherpa_aar.sh`: tries `huggingface.co/csukuangfj/sherpa-onnx-libs/.../android/aar/<ver>/sherpa-onnx-<ver>.aar`,
  the same folder without the version folder, the `sherpa-onnx-static-link-onnxruntime-<ver>.aar` file of that folder, and the old
  GitHub release URL. A download is kept only if it is a valid zip, over 5 MB, and its `classes.jar` contains
  `com/k2fsa/sherpa/onnx/OfflineRecognizer`. It never fails the build (exit 0, prints a warning).
- `.github/workflows/android-build.yml`: the fetch step calls that script; new step "Report sherpa-onnx in APK" prints a warning
  when `libsherpa-onnx-jni.so` is not inside the debug APK.
- `app/libs/README.txt`: says where the AAR really lives.
- `VoiceAiFragment`: `card(topMarginDp)`; the rows card under the Offline speech model card now has a 12 dp gap (they touched before).

## Verification status
- The script was tested against a local fake server: good file in the versioned folder, only the static-link name present, a webpage
  served as 200, nothing anywhere, and an AAR already in `app/libs`. All five behaved as intended.
- **Not tested against the real Hugging Face repo** (no network where this was written). The exact location / name for 1.12.39 is a
  guess among four candidates; the version folders and the static-link name were seen on the repo's pages for 1.12.21 and older. If the CI log
  shows the warning, download the AAR from that repo by hand into `app/libs/`, or set `SHERPA_VER` to a version that exists there.
- Kotlin was **not compiled** (no Kotlin compiler here). The only Kotlin edit is the `card(...)` helper and one call.
- The sherpa-onnx class / method names used by `SherpaBridge` (by reflection) were not checked against the AAR; a mismatch shows
  "Ye sherpa-onnx version app ke code se match nahi karta".


# v6: Hinglish Whisper model in the Offline speech model card + wake word "Jarvis"

**Why:** the link `huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/tree/main/hi-hinglish-swift` is a **Whisper-base**
export (`encoder.int8.onnx` + `decoder.int8.onnx` + `tokens.txt`), not a NeMo CTC model. v5 could only build a NeMo CTC recognizer
(one `model.int8.onnx`), so Download got an HTTP 404 and Import loaded the wrong file. The `*.weights` files in that folder are
leftovers of an earlier export and are not needed (the model card says so); do not import them.

## What changed
- `SherpaBridge`: new `createWhisper(encoder, decoder, tokens, threads, language = "hi")` (reflection, `OfflineWhisperModelConfig`
  + `OfflineModelConfig.whisper`); `create(...)` (NeMo CTC) is unchanged. Both go through one private `build(...)`.
- `OfflineSttStore`: two kinds on disk, `OfflineSttKind.CTC` (`model.int8.onnx`) and `WHISPER` (`encoder.int8.onnx` + `decoder.int8.onnx`),
  sharing `tokens.txt`. One kind at a time; installing one removes the other. `kind()` / `installed()` / `installedBytes()` know both.
  Download: a **folder link is tried as Whisper first** (a 404 on `encoder.int8.onnx` means "not a Whisper folder", nothing is lost),
  then as CTC. If the folder has no `tokens.txt`, the one a folder up is used (the IndicConformer repo shares one `tokens.txt`, which
  also fixes Download for `.../tree/main/hi`). Import: picking encoder + decoder + tokens works; switching kind needs all files at once.
- `OfflineSttUrls` / `OfflineSttImport`: plan and classify both layouts. A direct link to `encoder...onnx` / `decoder...onnx` is Whisper.
- `Store.offlineSttUrl`: empty / never set = `OfflineSttUrls.DEFAULT_URL` (the Hinglish folder above), so **Download works with no typing**.
  In the link dialog the old "Clear" button is now "Default".
- `VoiceAiFragment`: card texts mention Whisper / CTC, detail line shows which kind is installed, Download dialog says ~161 MB.
- **Wake word:** `Store.DEFAULT_WAKE_PHRASE = "hey jarvis"`. The old built-in default "hey code assist" is replaced once on the first read
  after the update (flag `wake_phrase_jarvis`); any phrase the person typed themselves is kept. `WakeMatcher` never requires the greeting,
  so "Jarvis" alone wakes it. Typing just "Jarvis" in the editor still shows the 2-syllable warning (Phir bhi rakho works).
- Tests: `OfflineSttUrlsTest`, `OfflineSttImportTest`, `WakeMatcherTest` extended (Whisper layout, shared tokens, Jarvis matching).

## Verification status — read first
- Kotlin was **not compiled** and the unit tests were **not run** here (no Kotlin compiler / network). CI runs `testDebugUnitTest` after the
  APKs are uploaded; if a test fails the APK is still available. The expected values in the new tests were worked out by hand.
- **Not tested on a phone, not tested against the real AAR.** The sherpa-onnx names used by reflection (`OfflineWhisperModelConfig`,
  setters `encoder` / `decoder` / `language` / `task`, `OfflineModelConfig.setWhisper`) follow the sherpa-onnx Kotlin API; a mismatch shows
  "Ye sherpa-onnx version app ke code se match nahi karta (...)". `language` and `task` are optional (a missing setter is skipped).
- The Hinglish files were exported with sherpa-onnx's "attention" Whisper exporter (extra cross-attention output for timestamps). Text
  recognition is expected to work on a normal AAR; if Load fails with a native error, try a newer `SHERPA_VER`.
- Whisper reads at most 30 s per run; `OfflineStt` already cuts an utterance at ~28 s.
- Accuracy (model card): Whisper-base Hinglish WER 38.7% Common Voice, 35.1% FLEURS, 65.2% Indic-Voices (casual speech). The bigger
  `hi-hinglish-apex` (~1 GB) is more accurate but heavy next to Gemma; it is the same file layout, so the link can be changed to it.

## Phone check
1. Settings > Voice and AI > Offline speech model > **Download** (Wi-Fi). Wait for 3 files (encoder 29 MB, decoder 131 MB, tokens).
2. Badge "Unloaded" and the line "Whisper (Hinglish) · encoder.int8.onnx + decoder.int8.onnx + tokens.txt". Tap **Load**: "Loaded".
3. Speech engine = Offline model. Say a Hinglish sentence: text appears after you stop talking.
4. Hands-free: say "Jarvis, time batao" (or "hey Jarvis ..."). Wake test in the same screen shows how many of 10 were caught.


# v7: wake word "offline speech pack (English India)" error fixed in code

**Problem (phone report):** Settings > Voice and AI > Wake test stopped with "Wake word ke liye offline speech pack (English India)
download karo" although every offline setting was already on. Cause: `WakeSupport` only asks "is there an on-device recognizer?"
(yes), then the recognizer itself answered error 12 / 13 (`LANGUAGE_NOT_SUPPORTED` / `LANGUAGE_UNAVAILABLE`) for `en-IN`. The
on-device recognizer keeps its own language packs, which are not always the offline pack shown in the Google app. The app never
asked for a download and never tried another English pack.

**Fix**
- `WakeLang` (pure Kotlin): candidate order `en-IN` > `en-US` > `en-GB` > any other installed English (Devanagari phrase: `hi-IN` only),
  and the pick functions for "installed", "already downloading", "which one to download".
- `WakePacks` (Android 13+): `SpeechRecognizer.checkRecognitionSupport` (installed / pending / supported packs) and `triggerModelDownload`.
- `VadGatedWakeWord`: at start it checks the packs and uses the first installed language. Nothing installed: it asks Android to download
  one (needs Wi-Fi) and stops with a message saying so (wake test dialog / service notification). If the recognizer still answers
  12 / 13 for a language, that language is dropped for the run and the next one is tried (no more dead end on the first error).
  If Android cannot answer the pack check (3 s timeout), it behaves like v6.
- Texts: `WakeSupport` and the Voice and AI note no longer say only "English India".
- Test: `WakeLangTest` (expected values worked out by hand).

## Verification status — read first
- Kotlin was **not compiled** and the unit tests were **not run** here (no Kotlin compiler / network). Braces were checked with a script
  only. CI runs `testDebugUnitTest`; a compile error would show in the CI log.
- **Not tested on a phone.** `checkRecognitionSupport` / `triggerModelDownload` depend on the phone's recognition service: some builds
  answer `onError` (then v6 behaviour) or do nothing on download. The download itself finishes after the dialog, so the first test
  after install may still stop with "download shuru kar diya hai"; wait a few minutes on Wi-Fi and run Wake test again.
- If it still stops with a "nahi mila" message, the message lists the languages the phone says it supports: send that line.

## Phone check
1. Settings > Voice and AI > Hands-free > Wake test. Either it starts at once (a pack was found), or it says a download was started.
2. After the download, run Wake test again and say "Jarvis" 10 times.


---

# Phase 3, part 3A (task engine: DAG, durable queue, partial failure, T2 gate, crash resume) — honest status

**Same situation as before: written and statically checked in a sandbox with no Android SDK and no Kotlin compiler, so it has
never been compiled or run, and the new JVM tests have not been run.** What was done instead: brackets / strings balance in every
new or edited Kotlin file (lexer that understands string templates), every member used on project objects exists, every import
resolves, and the pure logic (`TaskGraph` rules, `ToolSpecs.parseAfter`, `ReplyComposer.partialSummary`, the kill-test plan) was
re-implemented line by line in Python and run on every scenario the new JVM tests assert (all pass). Run
`./gradlew assembleDebug testDebugUnitTest` (CI does) and send the compiler output if anything fails.

## What the audit's Phase 3 asks for, and where it stands

| Audit item (Sec 13, Phase 3) | Part | Status |
|---|---|---|
| Multi-intent DAG | 3A | `after` on every tool -> `PlanTask.dependsOn` -> `TaskGraph` (validation, order). Steps run one after another, in dependency order |
| Durable queue | 3A | `TaskQueue`: JSON file, written before and after every tool. **Not Room**: the app has no Room / KSP dependency; the file holds at most 20 plans |
| Partial failure | 3A | Failed step -> only its dependents are SKIPPED; independent steps run; closing summary line |
| Risk tiers + confirmations | 3A | T2 step = WAITING_CONFIRM, one at a time, hard gate `PolicyGate.mayExecute` before every run, KPI `t2_blocked` must stay 0. T3 is never run by the app (unchanged) |
| Priority classes | 3A (partly) | NOW and SOON are used. INTERRUPT and LATER exist in the enum but nothing uses them yet: they belong to corrections (3B) and proactive work (Phase 4) |
| Corrections + undo | 3B | Undo already exists (one chip undoes every action of a reply, activity log). "Nahi, 9 baje" -> change the same alarm needs entity ids and an alarm store: not in 3A |
| Progress monitor | 3B | Not built. All eight tools return at once, so there is nothing slow to report yet; it needs a slow tool (web search) to be testable |

## Exit criteria of Phase 3

| Criterion | Status |
|---|---|
| 0 unconfirmed T2 / T3 | Enforced in code and unit-tested (`PolicyGatePhase3Test`); the KPI screen counts attempts. Needs 5 real dial confirmations to show "[naapa]" |
| Kill-process resume test passes | Debug screen has a one-button test (below). Passes only when you run it on a phone |
| Multi-intent >= 85% | **Cannot be measured by the app.** It needs the labelled Hinglish set (Appendix A cases 2, 19, 20). Cases 19 (petrol) and 20 (plan edit) need tools the app does not have |

## Files

New: `ai/TaskGraph.kt` (pure), `ai/TaskQueue.kt`, `ai/TaskEngine.kt`; tests `TaskGraphTest`, `ToolSpecsAfterTest`,
`PolicyGatePhase3Test`, `ReplyComposerPartialTest`, `PlanParserAfterTest`.
Changed: `PlanTypes` (`PlanTask.dependsOn`, `ToolSpec.idempotent`, `PendingAction.planId / nodeId`), `ToolSpecs` (`after`, `task()`,
`parseAfter()`, graph check inside `check()`), `PlanParser` and `ChatRunner` (build tasks with `ToolSpecs.task`, pass chat id and
message id), `PlanExecutor` (delegates; plan-aware confirm; the old single-action confirm stays for messages made by older builds),
`PolicyGate` (`mayExecute`), `ReplyComposer` (`partialSummary`), `Prompts` (two sentences about `after`), `ConvKpi`, `MainActivity`
(calls `TaskEngine.recover` once), `SettingsFragment` (debug screen), `app/build.gradle` (version 2.1, code 12).

## How a crash is handled

| State when the process died | After the restart |
|---|---|
| Step RUNNING, tool is idempotent (time, date, battery, torch, app open), plan younger than 2 min | Run again |
| Step RUNNING, tool is NOT idempotent (timer, alarm, dial) | FAILED + "pata nahi hua ya nahi, phone mein check karo". Never run twice |
| Step PENDING and ready, plan younger than 2 min | Run now |
| Anything unfinished in a plan older than 2 min | Dropped ("bahut der ho gayi"), not run late |
| Waiting for the user's tap | Left as it is; the Haan / Nahi row stays under the message |
| Finished plan whose reply never reached the chat (younger than 10 min) | The reply is written into the chat |

The reply starts with "Pichli baar app beech mein band ho gayi thi." and is saved in the plan's own message (updated in place, or
created if the process died before it was saved).

## Deliberate gaps and limits (read before testing)

- **Recovery runs when the app is opened, not in the background.** The hands-free service starting the process does not trigger it.
- **Steps run sequentially on the main thread**, as Tier-0 always did. The audit's "parallelism limit ~3" only pays off with slow
  tools; with eight instant tools it would add risk (Clock intents and the torch are not meant for worker threads) and no speed.
- **`after` means "run after that step finished OK", nothing more.** It cannot say "only if the battery is below 20 %": that needs
  the result of one step passed into the next, which no current tool uses.
- **Tool prompt grew.** Every tool now declares `after`: the Gemma / Qwen tools block went from about 2,200 to about 3,000 characters
  (roughly +250 tokens). `ToolSpecsTest.qwenBlockStaysSmall` still passes with room to spare (limit 3,400). A small model may
  misuse `after`; an unknown id or a loop is refused and goes through the one repair retry, then a short question.
- **A second T2 step waits for the first answer.** Both readbacks are never on screen at once, because a message has one Haan / Nahi row.
- **The 2-minute resume window and the 10-minute confirm window are first guesses.** Tune them from the KPI numbers.
- **Plans keep the tool arguments** (for a dial step: the number) in `task_plans.json` in app-private storage for up to 24 hours /
  20 plans. App backup is off. There is no "clear plans" button yet.
- **The audit says Room.** If you later add Room for the Memory Manager (Phase 4), `TaskQueue` is one class to move.
- INTERRUPT / LATER priority classes, entity ids, alarm update ("nahi, 9 baje"), plan edit ("petrol wala kaam hata do") and the progress
  monitor are part 3B.

## Phone check (about 10 minutes, Gemini or Gemma)

1. "torch on karo, battery batao aur time batao" -> one merged reply. Settings > Activity and debug > Debug: task engine shows the plan,
   three steps COMPLETED.
2. "pehle torch on karo, phir 5 minute ka timer" -> timer step shows "(after t1)" in the debug screen. KPI: "`after` wale" goes up by 1.
3. Partial failure: "pehle Zxqvk app kholo, phir torch on karo" -> the app is not found, the torch line says it was skipped because t1
   did not work, and the reply ends with "Koi kaam nahi hua, 1 chhod diya. ..." (the torch must NOT turn on).
4. T2: "9876543210 par call karo" -> Haan / Nahi row. Tap Haan: dialer opens. Two numbers in one message: the second readback appears
   after you answer the first.
5. Kill-test: Debug: task engine > **Kill-test** > Chalao. The app closes. Open it again within 2 minutes. A new chat "Task engine test"
   must show: time (already told), battery, date, a line that the timer may or may not have started, the torch skipped, and
   "3 kaam ho gaye, 1 nahi hua, 1 chhod diya". The KPI screen then shows "kill-process test: 1 pass".
   If the app was opened later than 2 minutes the test counts as "der se khola" and the rest of the plan is dropped; run it again.


---

# Phase 3, part 3B-1 (corrections: "nahi, 9 baje") — honest status

**Same situation as every part before: not compiled, not run on a phone.** No Kotlin compiler and no Android SDK in the sandbox.
What was done instead: brackets / strings balance in every edited Kotlin file (script), imports and member names checked by hand,
and the pure logic (text clean-up, clock and duration reading, `Correction.kindOf / parse / alarmTimeOfToken`) was re-implemented
line by line in Python and run on every scenario `CorrectionTest` asserts (all pass). Run `./gradlew assembleDebug testDebugUnitTest`
(CI does) and send the compiler output if anything fails.

## What changed
New: `ai/Correction.kt` (pure grammar), `ai/CorrectionRunner.kt` (phone side), `test/.../CorrectionTest.kt`, `PLAN.md`.
Changed: `Tier0` (clock and duration reading moved into public `clockTime`, `secondsFromText`, `clean`; `alarm()` and `timer()` call them,
behaviour unchanged), `ChatRunner.quickReply` (correction is tried first), `ConvKpi` (new block), `app/build.gradle` (2.2, code 13).

## Rules in the code
- The thing being corrected is the last AI reply itself (title, time, its single undo id). No second store.
- Window 30 minutes; a reply with several actions (undo id list) is never corrected; an already-undone reply is not corrected.
- Alarm reply is corrected only with a clock time, timer reply only with a length of time. A mismatch returns null (not guessed).
- Message must be 10 words or fewer and carry a cue ("nahi", "galat", "wait" ...) before the new value, or be a plain cancel phrase.

## Deliberate gaps and limits (read before testing)
- **Clock app dependency.** Dismiss is an Android intent (`ACTION_DISMISS_ALARM` by label, `ACTION_DISMISS_TIMER` for the running timer).
  Some phone makers' Clock apps ignore it; the reply then says to remove the old one by hand. The KPI line counts both cases.
- **Timer order is the reverse of alarm order**: Android has no "which timer" for dismiss, so the old timer is stopped first and the new one
  set right after. If the new one cannot be set the reply says the old one was stopped.
- **No plan edit yet** ("petrol wala kaam hata do", audit Appendix A #20), no change of a call number, no INTERRUPT / LATER priority
  use: those come with the progress monitor (3B-2) and the labelled test set (3C). See `PLAN.md`.
- Without a cue word ("9 baje subah" alone) nothing is treated as a correction: it stays a normal message.

## Phone check (about 5 minutes)
1. "alarm 8 baje subah laga do", then "nahi, 9 baje subah". Expect: new alarm confirmed, "Pehla alarm hatane ki request bhej di".
   Open the Clock app: only the new alarm should be there.
2. "10 minute ka timer", then "nahi 5 minute". Expect: one 5-minute timer running.
3. "alarm 7 baje", then "rehne do". Expect: the alarm is gone (Clock app check).
4. "alarm 7 baje", then "nahi 7 baje". Expect: "pehle se hi 07:00 ka hai, kuch nahi badla".
5. Settings > Activity and debug > Debug: conversation KPIs > "Corrections (Phase 3B)" counts go up.


---

# Phase 3, parts 3B-2 and 3C (weather + time limit + progress + Stop, then eval + plan edit + Plans clear) — honest status

**Same situation as every part before: not compiled, not run on a phone.** The sandbox has Java but no Kotlin compiler and no
Android SDK (and no network to fetch them). What was done instead: brackets / strings balance in all 136 Kotlin files (script, the
whole project, not only the new ones); imports, member names and signatures checked by hand against the code they call; the data
file `eval_multi.json` was checked by a script (30 cases, unique ids, every expected plan passes the same rules as `ToolSpecs.check`);
and the grammar of `PlanEdit.parse` plus the Qwen tool-block size were re-implemented in Python and run on every scenario the tests
assert (all pass). **The new JVM tests have not been run.** Run `./gradlew assembleDebug testDebugUnitTest` (CI does) and send
the compiler output if anything fails. Phase 0 of `PLAN.md` (the compile gate) is still the most important open item.

## What the audit's Phase 3 asks for, and where it stands now

| Audit item (Sec 13, Phase 3) | Part | Status |
|---|---|---|
| Multi-intent DAG, durable queue, partial failure, T2 confirm, crash resume | 3A | done (earlier zip) |
| Corrections + undo | 3B-1 | done (earlier zip) |
| Priority classes | 3A + 3B-2 | NOW / SOON used, **INTERRUPT used for Stop**. LATER is still unused (proactive work, Phase 4) |
| Progress monitor | 3B-2 | done for the screen: one monitor, driven by the real step state, 2 lines per step at most. **Not spoken** (see gaps) |
| Per-node timeout | 3B-2 | 8 s for the slow tool |
| Plan edit (Appendix A #20) | 3C | done for the steps the app has (not for "petrol": no such tool) |
| Multi-intent >= 85 % | 3C | the **measuring tool is done; the measurement is yours to run** (Debug: eval). I cannot run a model here, so there is no number yet |

## What changed (files)

New: `ai/StepTimeout.kt`, `ai/WeatherClient.kt`, `ai/PlanProgress.kt`, `ai/PlanEdit.kt`, `ai/PlanEditRunner.kt`, `ai/EvalCases.kt`,
`ai/EvalRunner.kt`, `assets/eval_multi.json`; tests `StepTimeoutTest`, `WeatherClientTest`, `PlanProgressTest`,
`TaskGraphPhase3B2Test`, `PlanEditTest`, `EvalCasesTest`, plus 1 case in `ActionHintTest`.
Changed: `PlanTypes` (`ToolSpec.slow`), `ToolSpecs` (`weather_now`, city rule, slow-cannot-wait-for-confirm rule), `ToolRunner`,
`TaskGraph` (quick-before-slow order, `stop`, `STEP_TIMEOUT_MS`, crash rule for network steps, `Plan.stopped`), `TaskEngine` (the run is
now a suspend function: slow steps on a worker thread, `stop`, `applyEdit`, `clearPlans`, `Monitor`), `PlanExecutor` (suspend `run`,
`stop`), `ChatRunner` (plan id on the run, Stop ends the plan, progress lines, plan edit in the quick-reply chain), `ActionHint` and
`Prompts` (weather words, one example), `ConvKpi` (two new blocks, eval result in the exit checklist), `SettingsFragment` (Eval, Plans
clear), `app/build.gradle` (2.3, code 14), `PLAN.md`, `README.md`.

## Rules in the code

- A slow tool is run only by `TaskEngine.executeAsync` (chat reply). Every other caller (the Haan tap, crash recovery, plan edit) uses the
  main-thread loop, which never runs a network call: a slow step that somehow shows up there is cancelled, not run.
- `ToolSpecs.check` refuses a plan in which a slow step waits (directly or through other steps) for a step that needs the tap. The brain
  gets its one repair retry, then the short question. The other direction (the call waits for the weather) is allowed.
- Stop: `TaskGraph.stop` cancels PENDING / RUNNING / WAITING_CONFIRM steps in one go and sets `Plan.stopped`. The worker thread sees the flag
  within 100 ms. Whatever the network call returns afterwards is thrown away (KPI "Stop ke baad aaya jawab pheka").
- Plan edit only looks at the message right after a plan reply (the plan whose reply is the last AI message, younger than 10 minutes). It
  runs after the correction check, so "wo alarm hata do" is still a correction.
- The qwen tool block grew from 2,994 to **3,319** characters with `weather_now` (limit in `ToolSpecsTest.qwenBlockStaysSmall` is 3,400).
  That is the last room: the next tool needs a decision about the small model's prompt, not a quiet increase of the limit.

## Deliberate gaps and limits (read before testing)

- **The eval number does not exist yet.** Run Debug: eval on the phone with Gemini and with Gemma and send me the "Galat cases" text.
  The 30 cases are my labels: if you disagree with one (for example "kal subah uthna hai": I label it as a question, because no time is
  given), tell me and I change the label, not the code.
- **Gemma eval takes several minutes** (30 turns of the small model). Gemini takes about a minute. Eval uses your saved Gemini model and
  temperature, so two runs can differ slightly.
- **Appendix A #19 ("petrol") and #20 ("petrol wala kaam hata do")** need tools the app does not have. #19 is in the set as "ask one
  question"; #20 is handled for the tools that exist. There is no petrol, location or navigation tool.
- **Progress is on screen only.** The audit also wants it spoken ("thoda time lag raha hai"); that belongs with the filler / phrase-cache
  work and needs the voice path to know about the plan. Not built.
- **One slow tool only, one step waited for at a time.** The audit's "parallelism limit ~3" is still not built. Two weather requests in
  one message run one after the other, so the second may start with little time left of its own 8 s (each has its own 8 s, so the reply
  can take up to 16 s).
- **No city given = a question.** There is no "home city" setting; the brain is told to ask one short question. The offline Gemma may call
  the tool with a wrong or invented city: the geocoder then says "naam ka shehar nahi mila" or finds some other place with that name.
- **Weather needs internet**: offline it says so ("Internet nahi mil raha") and the rest of the plan runs. No cache.
- **A restart drops a network step** (weather) instead of re-asking; the reply says "dobara bolo".
- **Plan edit of a finished step** uses the activity-log Undo: for alarms and timers that is the Clock-app dismiss request, which some
  Clock apps ignore (same limit as 3B-1). Torch is reliable. A finished call / app open cannot be taken back; it says so.
- **Plans clear** also removes a plan that is waiting for a Haan tap: the tap then says the plan was not found and runs nothing.
- **INTERRUPT is used by Stop only.** A spoken "ruko" while a plan runs is still handled by the voice layer (it stops speaking) and not
  by the plan; only the Stop button ends the plan.

## Phone check (about 10 minutes, Gemini first)

1. "Mumbai ka mausam batao" -> one sentence with temperature, sky, humidity, wind. Note under the reply while waiting: "Mausam chal raha hai".
2. "Pune ka mausam batao aur 5 minute ka timer lagao" -> the timer is set at once, the reply has both lines. Debug: task engine shows both steps COMPLETED.
3. Airplane mode, then "Delhi ka mausam batao aur torch on karo" -> the torch turns on, the reply says the weather needs internet, ends with "1 kaam ho gaya, 1 nahi hua".
4. Slow network (or a VPN that stalls): the same request -> after about 4 s the note says the answer is late; after 8 s the weather step fails, the rest is done. KPI: "8 s timeout" goes up.
5. Stop while the weather waits (use step 4): reply says "Theek hai, ruk gaya." and keeps what already ran. KPI: "Stop (INTERRUPT)" goes up.
6. "9876543210 par call karo aur torch on karo" -> torch on, Haan / Nahi row for the call. Then "call wala kaam hata do" -> the row disappears, new short reply "Call wala kaam hata diya". Then try "petrol wala kaam hata do" right after another plan -> honest "naam ka koi kaam nahi mila" with the list.
7. "torch on karo aur battery batao", then "torch wala kaam hata do" -> torch goes off ("wapas kar diya"). "battery wala kaam hata do" -> "sirf ek jawab tha".
8. Settings > Debug: eval (30 Hinglish cases) > Chalao. Send me the result text. Then KPI > "Phase 3 exit checklist".
9. Settings > Plans clear -> "N plans hata diye"; Debug: task engine shows "Abhi koi plan nahi".



---

# Phase 4A: Memory Manager (audit Sec 9.5) — honest status

**Same situation as every part before: not compiled, not run on a phone.** No Kotlin compiler, no Android SDK here. Done instead:
brackets / strings balance in all 144 Kotlin files; every new symbol checked to exist; the grammar and number rules (`MemoryGuard`,
`MemoryCommands`, `LocalMemory.parse`) re-implemented in Python and run on all 50 inputs the new tests use (all as asserted). **The
new JVM tests (`MemoryGuardTest`, `MemoryBookTest`, `MemoryCommandsTest`) have not been run.** The old `LocalMemoryTest` is untouched and
must still pass (`LocalMemory.command` behaves as before; it is now built on the new `LocalMemory.parse`). Run
`./gradlew assembleDebug testDebugUnitTest` and send the compiler output if anything fails. Phase 0 (compile gate) is still the most
important open item.

## What it does

- **Memory (yaad) screen**: Settings > AI service > Memory (yaad). List in two groups, "Hamesha ke liye" and "Abhi ke liye, 24 ghante";
  search box; tap an item for Badlo (edit), Hatao (delete), Hamesha rakho (for a 24 hour item); "Pichhle kuch minute bhool jao" (5 / 30 /
  60 minutes / 24 hours); "Export (text)" through the share sheet; "Sab bhool jao"; Private mode switch; "Gemini ko bhi yaad bhejo" switch.
- **Layers**: Hamesha = until deleted. Abhi ke liye = 24 hours. Audit "Number yaad rakhna": a number of 6 to 11 digits (or with +country code)
  is saved as Abhi ke liye and read back digit by digit; "hamesha yaad rakho ki ..." or "yaad rakho hamesha ke liye ki ..." makes it permanent.
  The audit's "Sirf abhi ke liye ya hamesha ke liye?" question is replaced by this default, because a question needs a pending-answer
  state in the chat that does not exist yet.
- **Never stored** (`MemoryGuard`, even when asked, also when editing): words OTP, password, PIN, CVV, Aadhaar, card number, account number,
  PAN card / number, API / private / secret key; a PAN-shaped code; numbers of 12 digits (except 91 + mobile) or 13+ digits. A postal "pin code"
  with 6 digits is allowed. Reply: "Ye main yaad nahi rakhunga ...".
- **Chat commands** (work offline, never reach a model): "yaad rakho ki ...", "tum mere baare mein kya jaante ho", "bhool jao",
  "pichhle 10 minute bhool jao" (also "pichhle 1 ghante ki yaad hata do", "forget last 30 minutes"; at most 24 hours), "private mode on / off".
- **Private mode**: nothing new is saved, neither by command nor by the automatic pick-up of "mera naam ... hai". What is already saved is still used,
  and deleting still works. (Audit: "Private mode: koi memory write nahi".)
- **What the model gets** (`MemoryBook.promptFacts`): at most 6 facts: name and city always, then the ones that share words with the message, then
  the newest. Before 4A it was every fact (up to 12). Expired 24 hour items and deleted items are never in it. **Only the offline Gemma gets them by
  default.** The Gemini switch is off; when on, Gemini gets the same few facts in its system prompt (they leave the phone then).
- **Storage**: one SharedPreferences value, JSON encrypted with the Keystore key of `SecureStore` (AES-256-GCM), not in backups (`allowBackup=false`).
  The old "kind|text" facts are moved over once, with unknown age, and the old value is emptied. Limit 40 items; when full the oldest automatic one goes first.
- **Delete is verified** (audit exit "memory delete verified"): after every delete the stored value is read back from disk (not the cache) and the
  prompt is built again with the deleted words as the question; neither may contain them. KPI > "Phase 4A checklist" shows "[naapa] Memory delete
  verified: N delete, 0 fail". "Sab bhool jao" checks that the stored list is empty.
- The eval (Phase 3C) now leaves memory out of its prompts, so your saved facts cannot change its score.

## Deliberate gaps and limits (read before testing)

- **Deleting a memory does not delete the chat.** The old message "mera naam Rahul hai" stays in that chat and the model (Gemma or Gemini) can still read it
  there. The screen says so. For a clean start: delete the chat or begin a new one.
- **Secrets written as words pass**: "mera pin char aath do ek" is not caught. The word list is a safety net, not a guarantee; that is why everything
  stored is visible and deletable on the screen. Words like "password manager" are refused too (false alarm, on purpose).
- **Automatic pick-up is the same three patterns as before** (name, city, "mujhe X pasand hai"). Nothing new is guessed.
- **Edit changes the words, not the kind**: an edited name is still the item that "mera naam ... hai" replaces later.
- **If the Keystore key is lost** (app data restored on another phone) the list reads as empty; nothing crashes. If the Keystore refuses to encrypt, the
  list is stored as plain JSON (value starts with "p:") rather than lost.
- **Not built from the audit's memory section**: `last_used_at` / provenance and age in the prompt, a "sensitivity" column, "consent_state", top-3 to 5
  (we use 6), embeddings, "sensitive items only when the user asks" (no such items exist, they are refused), the Activity log's 30 to 90 day retention
  (still the existing limit), a "did you mean the other Rahul" question. The audit's table is richer than this; this is the part that decides what is
  stored and lets the user see and remove it.
- **No spoken confirmation of "private mode"** beyond the reply text; no widget or tile for it.
- **A number of 6 to 11 digits is always "Abhi ke liye"**, also a bill number or a year-month code. Say "hamesha" to keep it.

## Phone check (about 8 minutes, Gemma, no internet needed)

1. Chat: "mera naam Rahul hai" -> then Settings > Memory (yaad): "The user's name is Rahul." under Hamesha, "tumhari baat se pakda".
2. Chat: "yaad rakho ki mera number 98765 43210 hai" -> reply says 24 ghante and reads the digits. The item is under "Abhi ke liye".
3. Chat: "hamesha yaad rakho ki mera number 98765 43210 hai" -> now under Hamesha.
4. Chat: "yaad rakho ki mera otp 482913 hai" -> refused. Same for "mera password hunter2".
5. Memory screen: tap the name > Badlo > "The user's name is Rahul Sharma." > Save. Then ask in a new chat "mera naam kya hai" -> "Rahul Sharma".
6. Tap the name > Hatao. Toast says deleted. New chat: "mera naam kya hai" -> the model must not know it (in the old chat it may, that is the chat history). KPI screen > Phase 4A checklist -> "[naapa]".
7. Chat: "pichhle 10 minute bhool jao" -> the items saved in the last 10 minutes go, the old ones stay.
8. Private mode: switch on (or chat "private mode on"), say "mera naam Amit hai" and "yaad rakho ki kal chhutti hai" -> nothing saved. Switch off.
9. Memory screen > Export -> share sheet with the list as text. Then Sab bhool jao -> screen is empty; KPI shows "delete jaanch pass".
10. Search box: type part of a saved word -> list narrows.


---

# Phase 4B: Persona + proactive suggestions (audit Sec 9.7, 9.8, 9.10) — honest status

**Same situation as every part before: not compiled, not run on a phone.** No Kotlin compiler, no Android SDK here. Done instead: brackets /
strings balance in all Kotlin files; every new symbol and drawable checked to exist; the verb-conversion rule (`Persona.fit`), the
serious-message words and the daily budget (14 days, every 5 minutes, random candidates: never more than 1 / 2 a day) were re-implemented in
Python and run on the inputs the new tests use (all as asserted). **The new JVM tests (`PersonaTest`, `ProactiveTest`) have not been run.**
Run `./gradlew assembleDebug testDebugUnitTest` and send the compiler output if anything fails. Phase 0 (compile gate) is still the most
important open item.

## What it does

- **Settings > Assistant style**: Tone (Friendly / Professional / Calm / Playful), Humor (Off / Light / Frequent), Aap ya tum, Bolne ka lehja
  (Auto / Female / Male), Apne aap suggestions (Off / Low / Normal), Band ki hui suggestions. "Reply length" stays where it was (Voice and AI).
- **Persona in the prompts** (`Persona.block`): the defaults (Friendly, Light, aap, Auto) add **nothing**, so existing users get exactly the old
  prompts and the small model's context is unchanged. A non-default choice adds one line "Style: ..." (about 100 to 250 characters).
- **Serious messages**: ambulance, emergency, chakkar, dard, khoon, accident, hospital, maut, suicide, police, aag, saans ... in the user's message
  add "This message is serious: no jokes, calm and caring tone." whatever the humor setting is, and also when the user has a system prompt of their own
  (audit: "serious / safety = auto 0").
- **Bolne ka lehja = Female** (`Persona.fit`): the app's own sentences ("Soch raha hoon", "Theek hai, abhi karta hoon", "Sun raha hoon", the plan and
  phone-action replies) get feminine first-person verbs (rahi / karti / sakti hoon, karungi). Only a closed list of verbs before "hoon" and words ending
  "-unga" change; names ("Main Asha hoon") and object-agreeing past ("kar diya") do not. The default prompt's example sentence is converted too.
  The model is told the form in the prompt; **what a model writes is not edited**, so a small model can still slip.
- **Suggestion card** (Home, above the chat): one suggestion at a time, from real data, offline:
  battery 20 % or less and not charging (action: battery saver settings); torch switched on by the assistant more than 10 minutes ago and
  physically still on (a `TorchCallback` reports the real state; action: the Undo of that log entry).
  Buttons: the action, "Baad mein", "Ye mat bolo" (that kind never comes back until switched on in Settings > Band ki hui suggestions).
- **Rules** (`Proactive.pick`, tested): Low = 1 a day, Normal = 2, hard maximum 2; none from 22:00 to 08:00 (phone's local time); at least 3 hours
  between two cards; the same kind at most every 6 hours; a card counts when it is shown, not when it is used. KPI shows the counts and why a card
  was held back; "Phase 4B checklist" turns red if a day ever shows more than 2.

## Deliberate gaps and limits (read before testing)

- **No notifications, no background work.** The audit says "notification-first"; this app has no WorkManager and no notification channel for it, and
  adding both is a separate piece of work. The card appears only while the app is open, a moment (0.7 s) after Home comes to the front.
- **Two triggers only.** The audit also lists calendar events, a reminder that is due, location and a notification digest. Calendar and location need
  permissions the app does not ask for; "reminder due" needs the app's own reminder store (alarms and timers go to the Clock app, which does not
  tell us when they ring). Not built.
- **"Not during a call / media / meeting"** is not checked: the card is only shown inside the app, which the user is looking at.
- **Quiet hours are fixed** (22:00 to 08:00), not a setting. The proactive default is Low (as the audit's settings table says), so after the update a
  user can see one card a day if the battery is low; they can switch it off or mute a kind.
- **The torch card can be missed**: the torch state is known from the moment the app registered the callback (first Home visit of the process). A torch that
  the user turned off by hand disappears from the card at the next Home visit; one that was never switched on through the assistant never appears.
- **The audit's weekly summary of muted categories** is replaced by the row "Band ki hui suggestions" in Settings.
- **Gender: Female / Male does not change the voice** (the TTS voice is chosen separately in Voice and AI); it changes the words. Hindi nouns /
  adjectives that agree with the speaker ("main thaka hua hoon") are not handled, only the verbs listed.
- **Humor Frequent / Playful on the 1.5B-class model** may do little: it is one instruction in a small prompt.
- **The eval (Phase 3C) ignores persona and memory** (`personal = false`), so settings cannot change its score.

## Phone check (about 8 minutes)

1. Settings > Assistant style > Bolne ka lehja > Female. Chat: "torch on karo" -> reply unchanged ("Torch on kar di."). Ask "tum kya kar sakte ho?" with Gemma: the
   answer should use "sakti hoon". While thinking, the note says "Soch rahi hoon".
2. Same screen: Tone > Professional, Humor > Off, Aap ya tum > tum. Ask a normal question: shorter, formal, "tum".
3. Humor > Frequent, then type "mujhe chakkar aa raha hai": no joke, calm answer.
4. Set the defaults again (Friendly, Light, aap, Auto): answers go back to normal.
5. Suggestions: leave on Low. Torch: chat "torch on karo", wait 10+ minutes, open Home (or leave and return): the card says "Torch 10 minute se on hai. Band kar dun?"; "Band karo" switches it off.
6. Battery: with the phone at 20 % or less and unplugged, Home shows the battery card; "Battery saver kholo" opens the settings. Plug the charger in and return: the card is gone.
7. "Ye mat bolo" on the torch card, repeat step 5: no card. Settings > Band ki hui suggestions shows "Torch on reh gayi"; untick it > Save.
8. Settings > Apne aap suggestions > Off: no card at all. KPI screen > "Suggestions (Phase 4B)" shows the counts and "[naapa] Din mein 2 se zyada suggestion kabhi nahi".


---

# Test-chat fixes, Phase T1 of 4 (13 Oct 2026): naam aur "app on karo"

Source: `CodeAssistAI_Test_Chat_Report.pdf` (12 aur 13 Oct ki test chat). Is phase me report ki galti 1 aur 2 (aur 2 ka suraksha-jaal) theek hui. Baaki teen phase `PLAN.md` ki "Test-chat phases" table me hain.

## Kya badla

| File | Badlav |
|---|---|
| `LocalMemory.kt` | `stopWords` me sawaal ke shabd (jante, jaante, janta, yaad, tumhe, ...): "mera naam jante hoo" ab naam nahi banata. "?" par khatam hone wala message koi fact nahi deta. Naya `Cmd.AskName`: "mera naam kya hai", "kya tum mera naam jante ho", "what is my name". `findName`, `nameReply`, `validName`, `isWrongName` helpers |
| `MemoryCommands.kt` | `AskName`: saved naam seedha batata hai, warna "Mujhe abhi aapka naam nahi pata. Bataiye, aapka naam kya hai?". Model ko nahi puchta, kuch save nahi karta. KPI: `mem_ask_name`, `mem_ask_name_unknown` |
| `MemoryBook.kt` | `promptFacts` purani galat save ("The user's name is Jante.") model ko nahi bhejta |
| `OpenPhrase.kt` (naya) | App kholne wale phrase ka parser (pure Kotlin). Purane phrase jaise the; naye: "on karo / on kar do / chalu kar do / start kar do / launch kar do / on / chalu" |
| `Tier0.kt` | `openApp` ab `OpenPhrase` use karta hai. Naye "on karo" phrase **strict** hain: sirf poora app naam ya 4+ akshar ka prefix; "contains" nahi. `wifi / bluetooth / data / hotspot` jaise settings app nahi maane jaate. Naya `installedAppNames(ctx)` |
| `ActionHint.kt` | "<app> on karo" tab action hai jab "on / chalu" ke pehle ke shabd kisi app ka naam hon (installed list ya aam naam: whatsapp, youtube, ...) aur uske baad sirf karo / kar do / please jaise shabd hon. App list tabhi mangti hai jab message is shape ka ho |
| `ToolRefusal.kt` (naya) + `ChatRunner.kt` | Tools band the aur model ne chhote order par "main ek AI hoon, phone par ... nahi kar sakta" kaha: ek baar tools ke saath dobara try (KPI `local_refusal_retry`). "kya tum ... khol sakte ho?", "kaise", code, wifi / bluetooth / data jaisi settings, lambe message par kabhi retry nahi |

Naye / badle tests: `LocalMemoryTest`, `MemoryCommandsTest`, `ActionHintTest`, `OpenPhraseTest` (naya), `ToolRefusalTest` (naya).

## Jaan-boojh kar nahi kiya

- `eval_multi.json` ko nahi chhua: `EvalCasesTest` use 30 cases (17 / 9 / 4) par tikaye hue hai aur Phase 3 ka 85 % exit rule bhi 30 par. "WhatsApp on karo" jaise cases tab jodna ho to alag file me (T2 me puchhunga), warna naya number purane se mil jayega.
- `runOpenApp` (model ka `app_open` tool call) pehle jaisa loose hai; sirf phrase wale raste strict hain.
- Gemini raste me kuch nahi badla: use tools hamesha milte hain.

## Seemayein (zaroor padho)

- **Compile nahi hua.** Is sandbox me Kotlin compiler nahi hai (Phase 0 ka gate ab bhi khula hai). Naya logic (regex, ActionHint, OpenPhrase, ToolRefusal) Python me dohra kar ke test ke har expected jawab se milaya gaya; Kotlin me nahi chala. Pehle CI chalao; error aaye to lal hissa bhejo.
- `Tier0.openByName` aur `ChatRunner` ka retry Android types par tikay hain: JVM test nahi, sirf phone check.
- Retry ek aur generation chalata hai (kuch second). Sirf galat inkaar par hota hai.

## Phone check (about 5 minute)

1. Chat: "mera naam jante ho". Jawab: "Mujhe abhi aapka naam nahi pata..." (agar naam saved nahi). Settings > Memory me "Jante" nahi banna chahiye.
2. "mera naam shahnawaz hai", phir "mera naam kya hai": "Aapka naam Shahnawaz hai." seedha, bina model ke (note me Gemma / tok/s nahi).
3. "WhatsApp on karo": WhatsApp khulta hai (T0 "Open app"). "youtube chalu kar do": YouTube khulta hai.
4. "wifi on karo": koi app nahi khulta, aur inkaar-retry bhi nahi chalta (settings ke liye inkaar sach hai); model ka jawab jaisa aaye.
5. "kya tum WhatsApp khol sakte ho?": sirf jawab, WhatsApp nahi khulta.
6. KPI screen me `mem_ask_name` badhna chahiye; `local_refusal_retry` sirf tab jab model ne galat inkaar kiya.

# Test-chat fixes, Phase T2 of 4 (13 Oct 2026): bhasha, asli stack, bhaari plan sawaal

Source: `CodeAssistAI_Test_Chat_Report.pdf` (galti 3 aur 5). Report is zip me nahi hai, isliye kaam `PLAN.md` ki T2 row ke hisaab se kiya gaya. T3 aur T4 abhi baaki hain.

## Kya badla

| File | Badlav |
|---|---|
| `LocalMemory.kt` | Naya `languageChoice`: "Hinglish me batao", "hinglish mein jawab do", "ab se hinglish me baat karo", "reply in Hinglish", "hinglish only" (English ke liye wahi shape). Sirf baat-cheet ke kriya (batao, bolo, jawab, baat, reply, answer, talk ...): "english me translate karo" aur "hinglish me likho" ek baar ka kaam hain, choice nahi. "?" par khatam hone wala message koi choice nahi deta. Ek message me do phrase ho to baad wala jeetta hai. `extract` ab ye choice `reply_language` fact ke roop me deta hai (same kind purana badal deta hai); `languageOfFact` Memory screen me edit kiye sentence ko bhi samajhta hai |
| `MemoryBook.kt`, `MemoryStore.kt` | `replyLanguage()`: saved choice padhta hai. `promptFacts` is item ko "fact" ki list me nahi dikhata (iski apni line hai) |
| `ReplyGuard.kt`, `HinglishGuide.kt` | `wantedLanguage` (saved choice pehle, phir message Hinglish ho to Hinglish, warna koi niyam nahi; message me english / angrezi / translate / anuvad ho to us turn ka koi niyam nahi: "isko English me translate karo" ka jawab English hi hona chahiye), `languageMismatch` (code, 8 se kam shabd, aur niyam-rahit message kabhi nahi jaanchte. Hinglish chaha aur jawab me Hindi shabd 1 per 30 se kam: galat. Saved Roman Hinglish par Devanagari jawab: galat script. English chaha aur jawab me Hindi shabd 15 % ya zyada: galat), `retryNote`. `HinglishGuide.hindiShare` naya. Qwen ke baad ye dono pehli baar phir kaam me aaye |
| `Prompts.kt` | `stackLine`: "Gemma 4 E2B, phone par offline, Google LiteRT-LM" ya "Google Gemini (model ...)", offline brain ka zikr, aur "koi aur model apna mat batao". `languageLine`: saved choice ki ek line, aur ek apwaad: agar message translation ya doosri bhasha maange to wahi maano (Hinglish ke udaharan me gender badalne wala kriya nahi, kyunki Female voice sirf base prompt badalti hai). Dono sirf asli chat me, eval me nahi (`personal = false`). `DEFAULT_SYSTEM` / `LOCAL_SYSTEM` ka text nahi badla |
| `ChatRunner.kt` | `runLocal`: Gemma ka jawab galat bhasha me ho to EK baar dobara (system me language line, user turn me `retryNote`); retry bhi galat ho to pehla jawab rahega; caption me likha aata hai. Tool turn par nahi. `start`: bhaari plan sawaal par `runHeavyPlan` (Gemini; koi bhi failure par Gemma, `automaticFallback` ka isse lena-dena nahi) |
| `FallbackPolicy.kt` | `isHeavyPlan` (plan / roadmap / spec / prompt jaisa shabd + "banao / likho / batao" jaisa shabd + kam se kam 6 shabd, aur ya 12+ shabd ya "poora / complete / detailed / phase" jaisa shabd), `escalateHeavyPlan` (phone brain + saved Gemini key + switch on) |
| `Store.kt`, `VoiceAiFragment.kt` | Naya switch "Bhaari plan sawaal Gemini ko" (default on), Settings > Voice and AI > Reliability and local reading |
| `ConvKpi.kt` | Naya block "Bhasha aur bhaari sawaal (T2)": retry / theek hua / phir bhi galat, bhaari sawaal Gemini ko / Gemini nahi mila |
| `README.md`, `PLAN.md` | README ka Qwen2.5 wala hissa Gemma 4 E2B / LiteRT-LM se badla (jo sirf itihaas hai uspar "history" likha), T1 / T2 ka naya section; PLAN me T2 "ho gaya" |

Naye tests (24): `ReplyGuardTest` (+8), `LocalMemoryTest` (+6), `MemoryBookTest` (+3), `FallbackPolicyTest` (+3), `PromptsTest` (naya, 4).

## Jaan-boojh kar nahi kiya

- "tum kis model par chalte ho" ka jawab app khud nahi deta; sirf prompt me asli stack hai. Chhota model phir bhi galat bol sakta hai: phone check 7 dekho. Galat nikle to ek chhota Tier-0 jawab (brain ka naam seedha batane wala) agle phase me jod sakte hain.
- Gemini raste me bhasha retry nahi (Gemini ek line se maan leta hai, aur har retry ek aur network call hota).
- `eval_multi.json` ko nahi chhua (30 case wala 85 % rule).
- Qwen ke bache hue switch (attached-image OCR, "Qwen options") ko nahi hataya: dono ab kuch nahi karte.
- Offline modules (Gemma card, offline speech model) aur Gemini client ka review nahi kiya (tumne kaha abhi zaroori nahi).
- `DEFAULT_SYSTEM` ka text nahi badla: agar kisi ne Settings me purana default text save kiya ho to wo "custom" ginta jata aur naya text use nahi hota.

## Seemayein (zaroor padho)

- **Compile nahi hua.** Sandbox me Kotlin compiler nahi hai (Phase 0 ka gate ab bhi khula hai). Naya logic (regex, shabd-suchi, bhasha ka niyam, bhaari plan ka niyam) Python me dohra kar ke 65 case par chalaya gaya; regex aur shabd-suchi seedha Kotlin files se padhi gayi; sab pass. 154 `.kt` files par ek mota bracket / string check bhi saaf. Kotlin me kuch nahi chala: pehle CI chalao, error aaye to lal hissa bhejo.
- `ChatRunner` ka naya hissa Android types par tika hai: JVM test nahi, sirf phone check.
- **Bhasha ka retry ek aur generation hai** (kuch second). Pehla English jawab streaming me dikh sakta hai aur phir badal jata hai. 8 shabd se chhota jawab ya code wala jawab jaancha nahi jata.
- Hinglish jaanchne ki suchi me sirf Hindi shabd hain: bahut kam Hindi wala (1 per 30 shabd se kam) Hinglish jawab "English" ginta jayega aur ek retry kharch karega.
- **Bhaari plan sawaal internet par Google ko jaata hai**, bhale phone brain chuna ho. Isliye switch hai (default on, aur sirf tab jab key saved ho). Suchi mota anuman hai: "Goa trip ka 3 din ka detailed plan banao" jaisa sawaal bhi "bhaari" ginta jata hai. T3 ka plan builder ise pakka karega.
- Bhasha ka choice Gemini ko bhi jaata hai ("Gemini ko bhi yaad bhejo" band ho tab bhi): wo style ki pasand hai, nijee fact nahi. Private mode me ye choice save nahi hoti (us message me Hinglish likha ho to jawab phir bhi Hinglish aata hai).
- "kya tum hinglish me baat kar sakte ho" (bina "?" ke likha) bhi choice maana jata hai. Memory screen se item hata do to wapas message ke hisaab se chalega.

## Phone check (about 9 minute, Gemma brain, Gemini key saved)

1. "what is a REST API?" -> English jawab (koi niyam nahi).
2. "Hinglish me batao" -> pichhla jawab Roman Hinglish me. Settings > Memory me "The user wants all replies in Roman Hinglish..." dikhna chahiye.
3. Teen sawaal English me: "what is recursion?", "explain binary search", "list vs set difference" -> teeno jawab Roman Hinglish. English aaye to caption me "bhasha retry +Ns" ya "bhasha retry ke baad bhi nahi badli" dikhega; KPI > "Bhasha aur bhaari sawaal (T2)" me ginti.
4. Hinglish choice rehte hue: "isko English me translate karo: main ghar ja raha hoon, kal meeting hai" -> English anuvad (Hinglish retry nahi chalna chahiye).
5. "English me batao" -> ab jawab English; Memory me ab bhi bhasha ka sirf ek item (purana badal gaya).
6. Memory se wo item hata do. "kaise ho bhai" -> Hinglish jawab, "how are you" -> English jawab (message ke hisaab se).
7. "tum kis model par chalte ho?" -> Gemma 4 E2B, phone par offline, LiteRT-LM. Brain ko Gemini karke wahi sawaal -> Google Gemini (model ka naam). Qwen / Phi / GPT nahi aana chahiye.
8. Gemma brain par: "mere app ka poora phase wise plan banao" -> caption me "Gemini · ... · bhaari plan sawaal"; KPI me "bhaari plan sawaal Gemini ko" +1. Airplane mode me wahi sawaal -> Gemma jawab deta hai, caption me "bhaari plan sawaal: Gemini nahi mila". Switch band karke wahi sawaal -> Gemma, Gemini nahi.
9. "kaise ho", "torch on karo", "kal subah 6 baje utha dena" par koi retry / Gemini nahi (KPI ginti na badhe).


# Test-chat fixes, Phase T3 of 4 (14 Oct 2026): plan / prompt builder

Source: `CodeAssistAI_Test_Chat_Report.pdf`, galti 4 ("Ek prompt do, poora plan, dusra AI coding kar de": Phase 4 aur 5 gayab, "UI as discussed earlier", aakhir me jhootha "covers everything"). **Sirf phone brain (Gemma) ke liye.** Gemini ka raasta nahi chhua aur uska test abhi hua nahi.

## Kya badla

| File | Badlav |
|---|---|
| `PlanBuilder.kt` (naya, pure Kotlin) | **Kaun maangta hai**: plan / prompt / blueprint jaisa shabd + "do / de / banao ..." + "poora / phase / dusra AI / coding / language" jaisa ishara + software ka sandarbh (message me ya pichhle 8 user message me kam se kam 2 software shabd). "Goa trip ka detailed plan banao", "kal ka plan batao", "plan banao" plan-request nahi. **Bhasha badal kar dobara**: pichhla jawab isi builder ka ho (caption "Plan builder ...") aur message chhota ho ("English latter rakho or Hindi bhasha me do ye") to wahi purani maang naye bhasha me dobara chalti hai. `language()`: "english latter + hindi bhasha" = Roman Hinglish. **Faisle**: chat me user ke apne lines (phone command, "ok", chhote sawaal, plan ki maang, "Hinglish me do" jaise message nikaal kar), bina badle plan me likhe jaate hain (zyada hon to pehle 3 + sabse naye, 14 line / 1800 akshar tak). **Dhaancha app ka**: Goal, Faisle, Stack, Screens aur UI, 5 phase (Setup / Model aur data / UI / Core logic aur optimization / Testing aur deployment), Kya na karna, Kaam kab poora. Har hissa alag Gemma call (8 call). **Jaanch**: khaali hissa, "as discussed / jaisa pehle bataya" jaise chat ke hawale, galat bhasha, phase me `PASS:` line na ho; ek baar dobara likhwana, phir bhi galat to plan ke aakhir me "Dhyan: ye plan adhura hai (...)". **Galat tech**: "GGUF ko TFLite me convert", "7B+ model ko small / efficient", "Code Typos" wali line plan se hata di jaati hai. **Aakhri line app likhti hai** ("Is plan me 5 phase hain"), model nahi: "covers everything" kabhi nahi aa sakta |
| `ChatRunner.kt` | `start`: phone brain + switch on + voice nahi + attachment nahi + `PlanBuilder.source(...)` mile to `runPlanBuild` (heavy-Gemini hand-over se **pehle**, kyunki Gemini ka raasta untested hai). `prepareLocal` (model load jaanch) `runLocal` se nikaal kar dono me. Plan hissa-dar screen par badhta dikhta hai ("Plan: hissa 3 / 8"); Stop dabane par jo ban chuka wahi bachta hai |
| `Store.kt`, `VoiceAiFragment.kt` | Naya switch "Plan builder (phone par, section-wise)", default on (Settings > Voice and AI > Reliability) |
| `ConvKpi.kt` | Naya block "Plan builder (T3)": plan bane / poore / adhure / retry / hataayi gayi galat tech lines |
| `FallbackPolicy.kt` | `buildWords` me "do, de, dena, dijiye" jode. T2 ka naap: report ka asli message "Ek prompt do, poora plan, dusra AI coding kar de" T2 ke niyam se "bhaari" ginta hi nahi tha (usme "do / de" "make it" shabd nahi the). Ab ginta hai (builder band ho tab Gemini ko jaata hai) |

Naye tests: `PlanBuilderTest` (23). `FallbackPolicyTest` ke purane cases ke natije nahi badle (Python me dohra kar ke dekha).

## Jaan-boojh kar nahi kiya

- Gemini me plan builder nahi. Gemini ka raasta (T2 ka heavy-plan hand-over samet) abhi phone par test nahi hua; builder use sirf tab chalata hai jab phone brain chuna ho. Builder switch band karo to purana raasta (Gemini ya Gemma ek hi baar me) wapas.
- Stack hissa ki bhasha jaanch nahi (usme zyada English naam hote hain).
- Library ke naam ki asli jaanch (internet par) nahi: sirf teen jaani-hui galtiyan pakadi jaati hain. Baaki naam ke liye prompt me "verify karo" likha hai.
- Voice se maangi plan par builder nahi (bolkar lamba plan sunna kaam ka nahi).
- `eval_multi.json` ko nahi chhua (30 case, 85 % rule).

## Seemayein (zaroor padho)

- **Compile nahi hua** (sandbox me Kotlin compiler nahi; Phase 0 ka gate ab bhi khula hai). `PlanBuilderTest` JVM test hai par **chala nahi**. Python me regex / trigger / tidy / tech-check ka mirror chalaya (report ke sab cases: asli maang haan, Goa trip nahi, redo haan, "Hinglish me batao" redo nahi); Kotlin me kuch nahi chala.
- **Samay**: 8 chhote Gemma call, har ek kuch second se zyada. Phone par poora plan kuch minute le sakta hai (retry hua to zyada). Screen par plan hissa-dar dikhta hai.
- `PlanBuilder.build` ke andar model ka jawab `LocalLlm.chat` se aata hai; ek call ke dauran Stop turant nahi rukta (pehle jaisa).
- Faisle "user ke apne shabd" hain: agar user ne chat me galat ya adhoori baat likhi, wo bhi waisi hi plan me jaayegi.
- Plan ki bhasha ka andaza message ke shabdon se hai. "Hindi" ya "Hinglish" likha ho to Roman Hinglish; sirf "English" to English; kuch nahi to saved choice, phir message ki bhasha.
- Gemma chhota hai: 5 phase ke bullet kabhi kamzor ya dohrate ho sakte hain. App sirf dhaancha, hawale, bhasha, `PASS:` aur teen galat tech baatein pakadti hai.

## Phone check (about 12 minute, Gemma brain, internet band bhi chalega)

1. Chat me pehle likho: "coding assistant app banana hai jisme AI code likhe", "light theme chahiye, bada input box aur neeche output area", "settings me model size aur temperature ho, history bhi".
2. "Ek prompt do, poora plan, dusra AI coding kar de". Plan hissa-dar banta dikhe ("Plan: hissa 4 / 8"). Caption: "Plan builder · Gemma 4 E2B · 5/5 phase · 8 call".
3. Plan me "## 2. Faisle" ke neeche tumhare teeno lines waise ke waise, **"as discussed" kahin nahi**, Phase 1 se 5 sab me "Pass test:", aakhir me "Is plan me 5 phase hain". "GGUF ko TFLite" jaisi line nahi.
4. "English latter rakho or Hindi bhasha me do ye": wahi plan Roman Hinglish me, phase ginti wahi 5. KPI > "Plan builder (T3)": "bhasha badal kar dobara: 1".
5. "Goa trip ka detailed plan banao": builder nahi chalna chahiye (KPI "plan bane" nahi badhna).
6. Settings me switch band karke wahi maang: purana raasta (builder nahi).
7. Plan banate waqt Stop dabao: jo ban chuka wo bachna chahiye ("Beech mein roka gaya").
8. Agar caption me "adhura" aaye: plan ke aakhir ka "Dhyan: ..." padh kar bhejo (kaun sa hissa, kya galat).


# Test-chat fixes, Phase T4 of 4 (14 Oct 2026): sketch (bina training)

Source: report galti 6 ("coding assistant me AI ko sketch banane ki skills deni hain" par 5 point ki training list). **Sirf phone brain (Gemma).** Gemini nahi chhua.

## Kya badla

| File | Badlav |
|---|---|
| `Sketch.kt` (naya, pure Kotlin) | Skill = tool + format. Model sirf ek chhota JSON likhta hai, app jaanchti aur banati hai. 3 kism: **flowchart** (model sirf 2 se 8 label deta hai; app box aur teer rakhti hai, 3 box ki row, doosri row ulti taraf taaki row badalne wala teer seedha neeche jaye), **wireframe** (app screen: `header:Chat`, `list`, `input:...`, `button:...`, `text`, `image`; phone frame me upar se neeche, zyada ho to chhota ho kar), **shapes** (rect / tri / circle / line / text, 100 x 100 canvas, zyada se zyada 40). Jaanch: JSON na ho, kism na mile, 2 se kam flowchart item, anjaan shape, coordinate -5 se 105 ke bahar, w / h / r galat: refuse + wajah. Thoda bahar (-5..105) ho to 0..100 me clamp. `toJson` / `fromJson`: message me wahi JSON rakhi jaati hai. `SketchIntent`: seedha order ("ek sketch banao: ghar", "login ka flowchart banao") = drawing; **feature ki baat** ("AI ko sketch banane ki skills deni hain") = ek fixed sawaal, model ko nahi bheja: "drawing, flowchart, wireframe ya code ka khaka?". Uske baad "Training nahin dena hai ..." = fixed jawab ki training nahi lagti |
| `SketchView.kt` (naya), `item_msg_ai.xml`, `MessagesAdapter.kt`, `Models.kt` | `Message.sketch` (JSON, purani chats me null). AI reply ke text ke neeche ek square view me drawing: app ke accent / card rang. Aur koi touch nahi |
| `ChatRunner.kt` | `runSketch`: ek Gemma call (system me 3 udaharan), galat jawab par ek retry reason ke saath, phir bhi galat to saaf line "Sketch nahi ban paya (wajah)". `quickReply` me `sketchClarify`. Caption "Sketch · Gemma 4 E2B · Ns" |
| `ConvKpi.kt` | Naya block "Sketch (T4)" |

Naye tests: `SketchTest` (16).

## Jaan-boojh kar nahi kiya (aur PLAN se farq)

- **`ToolSpecs` me `draw_sketch` tool nahi joda.** PLAN me yahi likha tha, par (1) phone model ka tool prompt bhara hua hai (3,319 / 3,400 akshar) aur naya tool use todta, (2) task engine ka natija sirf text hota hai, drawing ka JSON nahi le ja sakta. Isliye sketch ka apna raasta hai; tool prompt ya `ToolSpecsTest` me kuch nahi badla. Natija wahi: bina training ke Gemma se chitra.
- Free-hand SVG nahi (report ka "baad me"). Sirf teen fixed kism.
- Gemini brain par sketch maangne par pehle jaisa text jawab aata hai. Voice se bhi nahi.
- Chitra save / share / zoom nahi. Sirf chat me dikhta hai.

## Seemayein (zaroor padho)

- **Compile nahi hua, test chale nahi.** Template ke coordinate Python me dohra kar ke dekhe: 2 se 8 box aur 8 wireframe part sab 0..100 ke andar. `SketchView` ka drawing (rang, text ka size, teer ka sira) kabhi dekha nahi gaya: phone par pehli baar dikhega, tedha ho sakta hai.
- Gson ka `JsonParser.parseString` thoda dheela (lenient) parse karta hai: kuch toote JSON galti se chal sakte hain; tab bhi coordinate / kism ki jaanch lagti hai.
- Chhota model galat kism chun sakta hai (flowchart ki jagah shapes). App use refuse nahi karti jab tak JSON sahi ho.
- "Sketch sawaal" ka reply `ActivityLog` me ek entry banata hai (MemoryCommands ke "naam" jawab jaisa).
- Message ka text sirf "<title> (<kism>)" hai; chitra ke neeche kuch likha nahi aata.

## Phone check (about 8 minute, Gemma brain)

1. "ek sketch banao: ghar" -> ghar (deewar, chhat, dhoop) ya, kam se kam, ek saaf galti-line "Sketch nahi ban paya (...)".
2. "login ka flowchart banao" -> 3 se 5 box, teer sahi disha me.
3. "chat screen ka wireframe banao" -> phone frame me header, list, input, button.
4. "coding assistant me AI ko sketch banane ki skills deni hain" -> training list nahi, ek sawaal ("drawing, flowchart, wireframe ya code ka khaka?").
5. Uske turant baad "Training nahin dena hai, skills deni hain" -> "Theek hai, koi training nahi ...".
6. "sketch kya hota hai" -> normal jawab (drawing nahi).
7. App band karke kholo: chitra chat me wapas dikhna chahiye (Message.sketch save hota hai).
8. KPI > "Sketch (T4)": maange / bane / nahi bane / dobara likhwaya / "skills" par sawaal.
