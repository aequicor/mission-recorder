# JavaCPP/JNA discover native bindings and structure fields reflectively. Keeping these
# adapters is required even when ProGuard cannot see a direct JVM call to every member.
-keep class org.bytedeco.** { *; }
-keep class com.sun.jna.** { *; }

# JNA maps application-defined interface method names and Structure field names directly
# to native ABIs. Obfuscating or optimizing their signatures breaks packaged release builds.
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keep class * extends com.sun.jna.Structure { *; }

# The JavaCV jar exposes optional integrations that Mission Recorder does not ship or use.
# Their absent Maven, JavaFX, OpenCL, Android, and Python APIs are valid for this desktop app.
-dontwarn org.bytedeco.javacpp.**
-dontwarn org.bytedeco.javacv.**
-dontwarn org.bytedeco.opencv.opencv_python3
-dontwarn org.opencv.android.**
