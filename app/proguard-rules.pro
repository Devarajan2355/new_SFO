# ---------------------------------------------------------------------------
# Sanya release build (R8). New file: app/proguard-rules.pro
# ---------------------------------------------------------------------------

# JNA and Vosk load the native speech library through reflection. Never touch them.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-keep class org.vosk.** { *; }

# JNA mentions desktop-only classes that do not exist on Android.
-dontwarn java.awt.**
-dontwarn javax.swing.**

# Keep line numbers and Sanya's own class and method names, so the Health check's
# "Last errors" list stays readable. Unused code is still removed.
-keepattributes SourceFile,LineNumberTable
-keepnames class com.sanya.assistant.** { *; }
