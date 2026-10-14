# Sketch Studio (upgrade of the T4 sketch feature) - audit, stages 1 and 2, what is still pending

Written 14 Oct 2026 (stage 1 and stage 2). **Nothing in these changes has been compiled or run on a device**: the sandbox it was written in has no
Kotlin compiler, no Gradle, no Android SDK and no network. What was actually checked is listed under "Checks done".
Run the GitHub Actions build (it now shows every failing step in one run) and send the output if anything fails.

## 1. Audit of the original sketch feature (before this change)

| Part | What it did | Verdict |
|---|---|---|
| `ai/Sketch.kt` | JSON format with 3 kinds: `flowchart` (labels only, app lays out), `wireframe` (parts list, app stacks them), `shapes` (rect / tri / circle / line / text on a 100 x 100 canvas, max 40). Validation with reasons, one retry with the reason, `toJson` / `fromJson`, system prompt, intent words (`SketchIntent`). Pure Kotlin. | Works as designed; 175 lines of JVM tests (`SketchTest`) cover it. Never compiled in CI. |
| `ui/SketchView.kt` | Square Canvas view, no touch handling, theme colours only. | Fine; could only draw the 5 primitives, one colour scheme. |
| `ai/ChatRunner.runSketch` | Offline Gemma call -> JSON -> `Message.sketch`. Phone brain only (not Gemini, not voice). | Works as designed. No way to change a drawing: every request started from scratch. |
| `chat/MessagesAdapter`, `item_msg_ai.xml` | Shows the drawing under the reply text. | Fine. |
| Persistence | The JSON lives in `Message.sketch`, saved with the chat. | Survives restarts; no separate "designs" list. |
| Export | None. | Missing. |
| Not present at all | HTML/CSS/JS designs, Three.js 3D, layers, transforms, gradients, undo/redo, zoom/pan, separate studio screen, PNG / file export. | See section 4. |

The original files were extended, not replaced; no second sketch engine was created.

## 2. Stage 1 - what changed (all backward compatible)

| File | Change |
|---|---|
| `ai/SvgPath.kt` (new) | Reader for SVG path data: M L H V C S Q T Z (absolute + relative, implicit repeats), normalised to absolute M/L/C/Q/Z. Arcs (A) are refused with a hint. Limits: 800 characters, 80 segments, coordinates -5..105 (clamped to 0..100). Pure Kotlin. |
| `ai/Sketch.kt` | New shapes `path`, `poly` (polygon / open polyline, 2-60 points), `ellipse`. Optional style per shape (`fill`, `stroke`, `sw`, `op`; colours `#RGB`, `#RRGGBB`, 13 names, `none`) and `bg` for the whole drawing. `"v":2` raises the shape limit 40 -> 120 (documents without `v` keep 40). Total limit 800 path points, 30,000 characters of JSON. `toJson` now writes `"v":2`. `toSvg()` exports a standalone SVG. `reviseSystemPrompt` / `revisePrompt` for edits. `SketchIntent.isRevision`. |
| `ui/SketchView.kt` | Draws the new shapes and styles; background colour. Still no touch handling. |
| `ai/ChatRunner.kt` | A short change request right after a drawing ("background dark kar do") edits THAT drawing: the model gets the current JSON + the request and must return the full updated JSON. A drawing over 3,500 characters is refused for editing with a clear message (the phone model cannot take it back as input); the old drawing stays. |
| `home/HomeFragment.kt` | Message menu on a drawing: **Copy SVG** and **Share SVG** (SVG source as text; there is no file provider yet, so it is text, not a `.svg` file). |
| `ai/ConvKpi.kt` | KPI line `sk_edit` (edits asked). |
| `test/.../SvgPathTest.kt`, `SketchStudioTest.kt` (new) | 34 new tests. `SketchTest` is untouched. |

### Format summary (what the model may write)
`{"kind":"shapes","v":2,"title":"..","bg":"#102030","shapes":[ ... ]}` with shapes
`rect/tri {x,y,w,h}`, `circle {x,y,r}`, `ellipse {x,y,rx,ry}`, `line {x,y,x2,y2,arrow}`, `text {x,y,l}`,
`path {d}`, `poly {pts:[[x,y],..], closed}`; every shape may add `fill`, `stroke`, `sw` (0.1-6 canvas units), `op` (0-1).

## 2b. Stage 2 - studio screen and real file export (new, 14 Oct 2026)

Scope chosen from the pending table of stage 1: the first two rows that need no new library and no WebView - the **studio screen**
and **real .svg / .png files**. HTML / JS designs, Three.js, layers / transforms / gradients and variations stay pending (section 4).

| File | Change |
|---|---|
| `ai/SketchDesigns.kt` (new) | Pure Kotlin. `Design` + list operations (add, update, rename, duplicate, delete, sorted; 60 designs max, titles 40 characters), `SketchHistory` (undo / redo, 30 states), `ViewXform` (zoom 1x-8x around the fingers, pan clamped so the picture always covers the view), `fileName` (safe .svg / .png names). |
| `ai/SketchGen.kt` (new) | One Gemma call for the studio: new drawing or edit of the current one. Same prompts, same checks, one retry, same 3,500-character edit limit as `ChatRunner.runSketch`. `ChatRunner` itself is NOT changed. |
| `sketch/SketchStudioActivity.kt` (new) | The screen, built in code (no layout files). LIST: preview, title, shape count, time, menu per design, "+ Naya design". EDITOR: interactive canvas (pinch-zoom, one-finger pan, double-tap reset), Undo / Redo, prompt field with **Naya** (new drawing from the text) and **Badlo** (change the current drawing), Stop, menu: Naam badlo / Duplicate / Share SVG file / Share PNG / Copy SVG text / Delete. Every accepted drawing, undo and redo is saved at once. An untitled design takes the drawing's title. |
| `ui/SketchView.kt` | `interactive` mode (touch), `setDrawing` (also an empty canvas), `renderBitmap(px)` for PNG. In the chat the view is still not interactive. |
| `ui/SketchFiles.kt` (new), `res/xml/file_paths.xml` (new), `AndroidManifest.xml` | A real `.svg` / `.png` file in `cache/sketch_export` shared through a `FileProvider` (authority = package name + `.files`). PNG is 1080 x 1080. Files older than a day are removed. |
| `data/Store.kt` | `sketchDesigns()` / `saveSketchDesigns()` (same list helpers and recovery copy as chats). |
| `MainActivity.kt`, `res/layout/activity_main.xml` | Drawer row "Sketch Studio". |
| `home/HomeFragment.kt` | Message menu on a drawing: **Open in Studio** (the same drawing is not saved twice), **Share SVG file**, **Share PNG**, Copy SVG (text, as before). The old text-only "Share SVG" is gone. |
| `ai/ConvKpi.kt` | KPI line `sk_studio` (runs started from the studio screen). |
| `test/.../SketchDesignsTest.kt` (new) | 26 JVM tests for the pure logic. |

## 3. Checks done (and not done)

- Done: a Python port of the `SvgPath` logic was run against every expectation in `SvgPathTest` (all hold). The tests for
  `Sketch` / SVG export were checked by hand against the code; the SVG test parses the export with the JDK XML parser, so
  well-formedness is tested once the tests run.
- Done: every `when` over `Sketch.Kind` was checked for exhaustiveness (Kotlin 2.4 refuses non-exhaustive ones).
- **Not done: compiling, running any unit test, lint, running the app.** No claim is made that they pass.
- Stage 2, done: a Python port of `SketchDesigns` / `SketchHistory` / `ViewXform` was run against every expectation of
  `SketchDesignsTest` (all hold); the three XML files (manifest, `activity_main.xml`, `file_paths.xml`) parse; braces and
  parentheses balance in every Kotlin file that was touched; every drawable / colour the studio screen uses exists in `res/`.
- Stage 2, **not done**: compiling, running the JVM tests, lint, touch handling (pinch / pan), PNG rendering, the FileProvider
  share, the studio screen on a phone, a Gemma call from the studio screen. All of these need the CI run or a device.
- **Existing CI failures: not investigated.** The ZIP contains the workflow but no CI log or `ci-reports` artifact, and the
  sandbox cannot build. Send the log (or the `ci-reports` artifact) and the failures can be looked at on their own, without
  assuming the sketch feature caused them.

## 4. Pending (not built, nothing pretends otherwise)

| Wanted | Status | Why / what it needs |
|---|---|---|
| Dedicated studio screen (prompt, preview, regenerate, zoom / pan, undo / redo, save / rename / duplicate) | **Stage 2: written, not compiled** | `SketchStudioActivity` + designs store. Undo / redo lasts while a design is open (see section 5). |
| HTML / CSS / JS designs (glassmorphism, animations) | Pending | Needs a restricted WebView: JS only in a sandbox page, no network, no bridge objects, size / time limits. Not built, so the model is not offered it. |
| Real 3D (Three.js / WebGL) | Pending | Needs `three.min.js` bundled in `assets/` (could not be downloaded here), WebView WebGL check on the target phone, RAM / APK-size measurement (three.js is roughly 0.6-0.7 MB minified). Perspective / isometric-looking pictures drawn with paths are still 2D and are not labelled 3D. |
| PNG and real `.svg` / project-file export | **Stage 2: .svg and .png written, not compiled** | FileProvider + share sheet. A project file (a design with its history) is still not exported. |
| Layers / groups, transforms, gradients, arcs in paths | Pending | Format extension + renderer + export; not offered in the prompt. |
| Design variations (3 layouts from one request) | Pending | Needs the studio screen and a multi-call plan for the phone model. |
| Light theme | Not applicable yet | The app has one (dark) colour set. |
| Fabric.js | Not added | Nothing in stage 1 needs a canvas object library. |

## 5. Known limits

- Preservation of unrelated shapes in an edit depends on the model following the instruction; the app does not diff them.
- A short edit message like "torch on karo" right after a drawing is handled by the phone-action path first (it runs before
  the sketch check); other short messages containing an edit word ("kar", "karo", "make", ...) right after a drawing are read as edits.
- Edits and the detailed format are offline-Gemma only (same as T4). A small model may need the retry or fail; the reason is shown.
- The prompt grew by about 700 characters (detail help). Context use on the phone model has not been measured.

### Known limits of stage 2

- Undo / redo lives only while a design is open: the saved design keeps its last state, the history is not stored. Opening it again starts a new history.
- The studio always uses the phone model (offline Gemma), also when the Gemini brain is chosen, like the chat sketch path. If the model is not imported the screen says so. While a chat reply is being written the studio call waits for it (`LocalLlm` runs one call at a time).
- "Badlo" on a drawing over 3,500 characters is refused (the phone model cannot take it back as input); "Naya" always works.
- Designs are kept in one SharedPreferences string (like chats), 60 designs at most. A drawing can be up to 30,000 characters, so the worst case is large; normal drawings are a few KB. If it turns out slow, moving designs to files is the next step.
- Zoom and pan are for looking only: they are not saved and do not change the exported file. The PNG is always the whole picture, 1080 x 1080, with transparent rounded corners.
- Files are shared through the Android share sheet ("Save to Files / Drive" there). There is no direct "save to Downloads" button.
- The PNG is encoded on the main thread (a short job at 1080 px). Not measured on a phone.
- `SketchGen` repeats the call logic of `ChatRunner.runSketch` on purpose, so a mistake in the studio cannot break the chat. If the logic changes, change both.
