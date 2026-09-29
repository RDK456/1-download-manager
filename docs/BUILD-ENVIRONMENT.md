# Build environment

Recorded because both of these were in `%TEMP%` and were lost with it.

- JDK: `C:\dlm-tools\jdk-17.0.20.1+1` (Temurin 17, from Adoptium)
- Android SDK: `%LOCALAPPDATA%\Android\Sdk`
- Working copy: `C:\dlm-work\repo`

## Why the working copy moved out of `%TEMP%`

`%TEMP%` on this machine does not reliably keep its contents. It has silently truncated
source files mid-edit, and then removed an entire working copy *including its `.git`*,
taking two rounds of uncommitted work with it. Nothing under `%TEMP%` is relied on any
more.

The bundled jpackage runtime cannot stand in for a JDK: jlink strips
`java.management`, and Gradle fails to start without it.

The app's own scratch directories are under `%APPDATA%\1DownloadManager`, and every
released build is on GitHub, so nothing user-facing depends on `%TEMP%`.

## Building

    set JAVA_HOME=C:\dlm-tools\jdk-17.0.20.1+1
    set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
    gradlew :core:test :desktop:test
## The native torrent library, and `%TEMP%`

`libtorrent4j` unpacks its 13 MB native library to `%TEMP%\libtorrent4j.dll` the first
time a test touches it. That file is left behind, and a run that finds it already there
sometimes fails to load it:

    NoClassDefFoundError: Could not initialize class org.libtorrent4j.swig.libtorrent_jni
    libtorrent4j JNI call failed; jni.path=C:\Users\RDK\AppData\Local\Temp\libtorrent4j.dll

One failure poisons every test after it, because the class is already half-initialised,
so the message points at whichever test happened to run second rather than at the real
cause. Delete the file and run again:

    del "%TEMP%\libtorrent4j.dll"

This is the same `%TEMP%` unreliability as above, showing up in a place that looks like a
code failure. Before believing a libtorrent test failure, check whether the DLL is there.
