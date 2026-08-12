# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the SDK tools proguard-defaults.txt.

# Kotlin
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }

# Keep the application class
-keep class com.todo.app.** { *; }
