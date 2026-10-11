# PLAN.md - baaki kaam, phase-wise (CodeAssistAI v2.3 ke baad)

## Rule: har phase itna chhota ki ek chat (Anthropic free limit) me poora ho
- Ek phase = ek chat. Zip bhejo + likho "Phase X karo". Mai 5 se 7 files badalta hoon, ek test file, README / BUILD_NOTES me ek section, zip wapas.
- Ek phase me ek hi bada vichar. Do bade kaam ek saath nahi.
- Pehle har baar: project padhna (README + BUILD_NOTES ka aakhri section), phir code, phir jaanch, phir zip.
- Agar chat beech me kat jaye: "Phase X ka bacha hua kaam karo" likho, zip me jo ban chuka wo rahega.

## Phase 0 - COMPILE GATE (sabse zaroori, har phase se pehle)
Is project ka code abhi tak **kabhi compile nahi hua** (sandbox me Kotlin compiler aur Android SDK nahi hai).
1. GitHub par push karo, Actions me `android-build` chalao (ya Android Studio me `./gradlew assembleDebug testDebugUnitTest`).
2. Error aaye to log ka lal hissa copy karke bhejo. Mai sirf wahi theek karta hoon (ek chhota phase).
3. Jab tak build hara nahi, aage ka phase mat lo: naye code par naye error jama honge.
4. `app/build.gradle` me `litertlm-android:latest.release` pinned version nahi hai. CI hara hone par jo version chala (build log me dikhta hai), wahi likh do, warna kal ko build tut sakta hai.

## Ho chuka
| Part | Kya |
|---|---|
| 3A | Task engine: DAG (`after`), durable queue, partial failure, T2 confirm, crash resume |
| 3B-1 | Corrections: "nahi, 9 baje", "rehne do" (alarm / timer), KPI block |
| 3B-2 | `weather_now` (Open-Meteo, T0, 8 s timeout), progress line "Kaam 2/3 chal raha hai", Stop ends a running plan (INTERRUPT), crash rule for network steps |
| 3C | 30-case Hinglish eval (`assets/eval_multi.json`) + Debug "Eval chalao" (parser only), plan edit ("torch wala kaam hata do"), "Plans clear". Eval ka number abhi phone par chalana baaki hai |
| 4A | Memory Manager (audit Sec 9.5): list, search, edit, ek-ek delete (disk se wapas padh kar jaanch), "sab bhool jao", "pichhle N minute bhool jao", export, Private mode; OTP / PIN / card / Aadhaar / PAN kabhi save nahi; number sirf 24 ghante ("hamesha" bolo to hamesha); Keystore se encrypted. **Is zip me** |
| 4B | Persona (Tone, Humor, aap / tum, verbs ka gender Female / Male / Auto; serious baat par mazaak hamesha band) + Home par suggestion card (battery <= 20%, assistant ki jalayi torch 10 minute se zyada on): Off / Low 1 / Normal 2 din mein, kabhi 2 se zyada nahi, raat 10 se subah 8 nahi, 3 ghante ka gap, "Ye mat bolo". **Is zip me** |

## Phase 3 ka aakhri kaam (tumhare haath mein)
1. CI hara karo (Phase 0) aur `testDebugUnitTest` ka natija bhejo.
2. Phone par Debug: eval chalao (Gemini aur Gemma dono) aur "Galat cases" bhejo: Multi-intent >= 85% tabhi "[naapa]" hoga.
3. Debug: task engine > Kill-test aur 5 asli call-confirm (T2) karo: KPI checklist me "[naapa]" aayega.
4. BUILD_NOTES ke "Phone check" ke 9 step (10 minute).

## Aage ke phase
Audit ke Phase 4 me ek cheez abhi kisi part me nahi hai: **references aur topic stack** ("usko call karo", "woh wala", "waise pehle wala..."). Neeche 4D me rakhi hai.

| Phase | Kaam | Mukhya files | Pass hone ka test |
|---|---|---|---|
| 4D | References + topic stack: "usko / woh wala" ko pichhli baat se jodna, ek hi sawaal me do options ("Rahul ya Rakesh?"), topic badalne par wapas aana | `Dialog` (naya, pure Kotlin), `ChatRunner` | "Rahul ko call karo ... usko message bhejo" me "usko" sahi insaan |
| 4C | First-run consent screen + mic level test + onboarding + offline banner ("internet nahi, local brain chal raha hai") | `OnboardingActivity`, `MicLevelTest`, `HomeFragment` | Naya install: consent ke bina mic nahi khulta |
| 5A | Message bhejna (SMS draft): readback + Haan tap (T2). Paisa / payment hamesha T3 = app kabhi nahi karegi | `ToolSpecs`, `PolicyGate`, `Tier0` | Bina tap ke SMS nahi jata (KPI `t2_blocked` = 0) |
| 5B | Cost meter (Gemini tokens, mahine ka andaza) + debug overlay | `GeminiClient`, `ConvKpi`, `DebugOverlay` | Meter asli token count se milta hai |
| 5C | Release safai: R8 / proguard, lint, permissions ki list, battery test, signed release ke steps | `proguard-rules.pro`, `app/build.gradle`, docs | Release APK khulta hai, sab features chalte hain |

## Dhyan rakhne ki baatein
- 8 GB RAM wale phone par local model dheela hai: har naya tool prompt me jodta hai. 3A me prompt +250 token gaya tha; 3B-2 me `weather_now` se block 2,994 se 3,319 characters hua (limit 3,400). Ab jagah khatam hai: agla tool lagane se pehle chhote model ka prompt dobara tay karna hoga.
- T3 (paisa, band na hone wala kaam) kabhi app se nahi. Ye rule kisi phase me dheela nahi hoga.
- Har phase ke baad ek chhoti phone-check list BUILD_NOTES me hogi (5 se 10 minute). Uska natija bhejoge to agla phase uske hisaab se badlega.
