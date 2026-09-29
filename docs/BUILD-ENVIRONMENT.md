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