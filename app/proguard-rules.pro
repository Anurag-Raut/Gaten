# Gaten release shrinking rules (wired up in app/build.gradle.kts).

# Tesseract4Android's JNI layer resolves these classes and methods from native code by name, so R8
# must neither rename nor strip them.
-keep class com.googlecode.tesseract.android.** { *; }

# ML Kit builds its recognizer through a component registrar that R8 cannot follow statically; keep
# the SDK intact rather than risk stripping a reflectively loaded class.
-keep class com.google.mlkit.** { *; }

# ML Kit reads annotations and signatures reflectively; SourceFile/LineNumberTable keep release
# stack traces attributable.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Drop debug/verbose/info logs from release builds.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
