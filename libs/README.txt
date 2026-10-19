Optional: offline speech engine (sherpa-onnx)
=============================================

Put the sherpa-onnx Android AAR in this folder, for example:

    app/libs/sherpa-onnx-1.12.39.aar

Where to get it: the sherpa-onnx author publishes the AARs in the Hugging Face repo
    https://huggingface.co/csukuangfj/sherpa-onnx-libs/tree/main/android/aar
(newer versions are inside a folder named after the version, for example .../aar/1.12.39/; if the plain
"sherpa-onnx-<version>.aar" is not there, the "sherpa-onnx-static-link-onnxruntime-<version>.aar" in the same
folder has the same Kotlin API and works too).

The GitHub Actions workflow runs scripts/fetch_sherpa_aar.sh when this folder has no .aar. It tries those places,
checks that the file is a real AAR with the sherpa-onnx classes inside, and keeps the build going if nothing works.
After the debug build a step reports whether libsherpa-onnx-jni.so is inside the APK. Run the script by hand
(bash scripts/fetch_sherpa_aar.sh) or download the file yourself if the CI log shows the warning.

With no AAR here nothing breaks: the project builds and runs exactly as before, and
Settings > Voice and AI > "Offline speech model" shows "Engine library: Nahi mila (AAR)". Import and Delete still
work then, but Load and the Offline model engine need the AAR.

The AAR adds native libraries for every phone architecture (APK gets about 50-60 MB bigger).
