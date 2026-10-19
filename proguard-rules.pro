-keepattributes Signature
-keep class com.codeassist.ai.data.** { *; }

# llama.cpp JNI bridge (called from native code)
-keep class dev.ffmpegkit.llama.** { *; }
