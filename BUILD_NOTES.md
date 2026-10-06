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
