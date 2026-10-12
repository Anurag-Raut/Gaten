# Gaten release shrinking rules (wired up in app/build.gradle.kts).

# ML Kit builds its recognizer through a component registrar that R8 cannot follow statically; keep
# the SDK intact rather than risk stripping a reflectively loaded class.
-keep class com.google.mlkit.** { *; }

# ONNX Runtime's Java layer is a JNI binding: the native side resolves classes and fields by name
# from code R8 cannot see, so the package is kept whole. (Its own AAR ships no consumer rules.)
-keep class ai.onnxruntime.** { *; }

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
