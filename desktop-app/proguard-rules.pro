# JavaCPP/JNA discover native bindings and structure fields reflectively. Keeping these
# adapters is required even when ProGuard cannot see a direct JVM call to every member.
-keep class org.bytedeco.** { *; }
-keep class com.sun.jna.** { *; }

# The JavaCV jar exposes optional integrations that Mission Recorder does not ship or use.
# Their absent Maven, JavaFX, OpenCL, Android, and Python APIs are valid for this desktop app.
-dontwarn org.bytedeco.javacpp.**
-dontwarn org.bytedeco.javacv.**
-dontwarn org.bytedeco.opencv.opencv_python3
-dontwarn org.opencv.android.**
