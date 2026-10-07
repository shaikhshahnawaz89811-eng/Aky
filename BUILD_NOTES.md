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
