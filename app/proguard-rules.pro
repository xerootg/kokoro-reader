# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep our native bridge
-keep class com.brahmadeo.supertonic.tts.SupertonicTTS { *; }

# Keep AIDL interfaces and their stubs
-keep interface com.brahmadeo.supertonic.tts.service.IPlaybackService { *; }
-keep interface com.brahmadeo.supertonic.tts.service.IPlaybackListener { *; }
-keep class com.brahmadeo.supertonic.tts.service.IPlaybackService$Stub { *; }
-keep class com.brahmadeo.supertonic.tts.service.IPlaybackListener$Stub { *; }

# Keep models/data classes that might be used for serialization/reflection
# If you have any data classes used with Gson/JSON, add them here.
-keep class com.brahmadeo.supertonic.tts.utils.LexiconManager$** { *; }

# Fix: Missing classes detected while running R8 (Missing JP2Decoder from Readium/PDFium)
-dontwarn com.gemalto.jp2.JP2Decoder

# MediaPipe's task options are protobuf-lite messages whose fields are read by reflection
# ("Field useAdvancedGpuApi_ for as1 not found" in the first shrunk release build, 2026-11-14).
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-dontwarn com.google.mediapipe.**
# The direct LiteRT-LM binding: JNI looks the class and its methods up by name.
-keep class com.brahmadeo.supertonic.tts.KokoroNative { *; }
