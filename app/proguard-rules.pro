# ProcView release-specific R8 rules. Library consumer rules cover Room,
# kotlinx.serialization, Compose, and Shizuku. Keep AIDL binder names explicit.
-keep class io.github.PctAIGM.procview.shizuku.** { *; }
-keep class * extends android.os.Binder { *; }
