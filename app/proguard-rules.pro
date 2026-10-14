# DownloadHub keeps release shrinking conservative while the native download engines
# are initialized through reflection-friendly entry points.
-keep class org.libtorrent4j.** { *; }
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn org.libtorrent4j.**

# Room, kotlinx.serialization and Compose-adjacent reflection kept explicit now that R8 runs.
-keep class com.downloadhub.app.data.** { *; }
-keep class com.downloadhub.core.** { *; }
-keepclassmembers class **$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** { *** Companion; *** INSTANCE; kotlinx.serialization.KSerializer serializer(...); }
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keep class com.downloadhub.app.download.** { *; }
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn java.beans.**
-dontwarn org.w3c.dom.bootstrap.**
