# DownloadHub keeps release shrinking conservative while the native download engines
# are initialized through reflection-friendly entry points.
-keep class org.libtorrent4j.** { *; }
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn org.libtorrent4j.**
