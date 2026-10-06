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

The GitHub Actions workflow is in `.github/workflows/android-build.yml`.
