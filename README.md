# CodeAssistAI Android client

Native Android client built with Kotlin and XML views. Chats, projects and settings are stored
on the device. AI replies are real: pick the on-device Phi-4 mini model or a Gemini API key in
**Settings > Voice and AI**. If neither is set up, the app says so in the chat instead of faking a reply.

## Included

- Home chat and searchable chat history with saved drafts; rename, pin, move, export, delete.
- Edit, copy, share, select, delete, and **Regenerate** (last reply) message actions; image previews zoom.
- Local projects with instructions and workspace files; a project chat gets its first reply on open.
- Image, ZIP, and file attachments. Text/source files are sent to either brain, images to Gemini only.
- **Brain**: Phi-4-mini-instruct Q4_K_M (~2.49 GB, llama.cpp through `dev.ffmpegkit-maintained:llama-android`)
  or Gemini over HTTPS (streamed replies, model list read from the API).
- **Phi-4 module card**: Download (Android DownloadManager) or Import file, GGUF header check,
  Load / Unload / Delete with the rules enforced in code (no Delete while loaded, no second Load).
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
  Default phrase "hey code assist", editable with a warning for common names. Sensitivity Strict / Normal / Loose.
  Words said right after the phrase ("hey code assist time batao") are sent as the user's turn.
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

## Network and privacy

- `INTERNET` is used only for Gemini and for downloading Phi-4 mini. Phi-4 mini inference is offline.
- `RECORD_AUDIO` is requested the first time the mic is used. The app never records or stores audio;
  Android's speech service handles it (offline pack optional).
- Android app backup is disabled.

## Build and checks

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
The AAR for llama.cpp ships `arm64-v8a` only, so use a 64-bit ARM phone (an x86 emulator cannot load Phi-4 mini).

See `BUILD_NOTES.md` for what is verified and what still needs a device test.

## CI

The GitHub Actions workflow is in `.github/workflows/android-build.yml`. The Android SDK step installs only
`platform-tools` (`packages: platform-tools`): the action's default also installs the old `tools` package, which can
break the step before lint and build run. Licenses are still accepted, and Gradle downloads the platform and
build-tools it needs. A Node.js 20 deprecation warning in the log is harmless.
The workflow builds and uploads both APKs first, then runs `testDebugUnitTest` and uploads the test report
(artifact `unit-test-report`), so a failing test never costs you the APK. Lint findings do not fail the build.
