# Context-limit fix (offline Gemma) - 17 Oct 2026

Problem (test chat, "Input token ids are too long ... 4123 >= 4096"): after ~11 turns every message failed, retries and
Unload/Load did not help, a bigger Context size did not help, only a new chat worked.

Changes (original ZIP untouched; this is a copy):
- ai/ContextBudget.kt (new, no Android code): token-aware prompt fitting (newest turns first, room kept for the reply),
  overflow-error parser, shrink steps after an overflow.
- ai/LocalLlm.kt: EngineConfig now gets maxNumTokens = Store.localContext (it was saved but never passed; falls back to 4096 if the
  file/backend refuses a bigger size); chat() fits the prompt before sending and, on the runtime's overflow error, shrinks by the
  measured amount and retries (3 steps, last one without history); Reply has droppedTurns; Reply.capped is now set when prompt+reply
  come within ~48 tokens of the window.
- ai/ChatRunner.kt: caption notes ("N purani baat ... chhodi", "lamba jawab ... adhoora ho sakta hai").
- test/.../ContextBudgetTest.kt (new): 12 JVM tests.

NOT BUILT / NOT RUN: no Kotlin compiler or Gradle in the sandbox. Run `./gradlew testDebugUnitTest` and a phone test:
long chat (>15 turns), then set Context size 6144 / 8192, Unload, Load, and check the caption + no overflow error.

Deliberately NOT changed: sampler (temperature 0.7 / top-k 40 / top-p 0.95). Google's Gemma 4 defaults are 1.0 / 64 / 0.95; the
unused Settings "Temperature" (default 0.3) comes from the old Qwen build and is not wired in.
