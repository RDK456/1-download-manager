@echo off
rem Double-click this if 1DownloadManager.exe says "Failed to launch JVM".
rem It only reads files; it installs nothing and changes nothing.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Troubleshoot.ps1"
