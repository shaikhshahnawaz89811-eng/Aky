Optional: offline speech engine (sherpa-onnx)
=============================================

Put the sherpa-onnx Android AAR in this folder, for example:

    app/libs/sherpa-onnx-1.12.39.aar

Where to get it: the "Assets" list of a release on https://github.com/k2-fsa/sherpa-onnx/releases
(file name sherpa-onnx-<version>.aar). The GitHub Actions workflow tries to download one automatically
when this folder has no .aar.

With no AAR here nothing breaks: the project builds and runs exactly as before, and
Settings > Voice and AI > "Offline speech model" shows "Engine library: Nahi mila (AAR)".

The AAR adds native libraries for every phone architecture (APK gets about 50-60 MB bigger).
