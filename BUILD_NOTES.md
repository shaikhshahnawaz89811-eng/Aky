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

Wake word and hands-free foreground service, voice barge-in, first-run consent screen, Memory Manager,
Activity log with Undo, confirm sheet for message/payment actions, mic level test / onboarding, offline-degrade
banner, debug overlay, proactive suggestions, persona settings, cost meter.

## Known limits

- Local replies take a while on CPU (about 2 to 10 tokens/s depending on the phone) and cannot be interrupted
  once started: Stop hides the reply, and the next local reply waits until the old one finishes.
- Phi-4 mini needs roughly 3.2 GB free RAM with the default 2048 context.
- Changing context size, threads or temperature applies at the next Load.
