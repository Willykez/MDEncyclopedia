# ══════════════════════════════════════════════════════════
#  MD Encyclopedia — ProGuard / R8 rules
# ══════════════════════════════════════════════════════════

# Keep line numbers for readable crash stacks, hide the source file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The app is 100% programmatic UI (no XML layouts / findViewById by id
# resources), so we do not need to keep View constructors for inflation.
# Still keep anything Parcelable/Serializable-related that Android needs
# to instantiate reflectively.
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}
-keep class * extends android.app.Activity

# Kotlin metadata / coroutines
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# AndroidX DocumentFile / storage access framework
-keep class androidx.documentfile.provider.** { *; }

# Our own data model classes (kept whole in case reflection/serialization
# is added later, e.g. persisting recent-files list as JSON)
-keep class com.willykez.md.model.** { *; }

# Remove logging in release builds
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
