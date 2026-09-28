@echo off
rem -----------------------------------------------------------------------
rem Starts 1 download manager without the packaged launcher.
rem
rem Use this when 1DownloadManager.exe says "Failed to launch JVM".
rem
rem The launcher stub loads jvm.dll directly and, when that fails for any
rem reason, its only possible message is that one line. Running the JVM
rem ourselves produces the actual error instead - a missing DLL, a denied
rem path, a blocked file - which is the only way to actually diagnose it.
rem
rem This also sidesteps the stub entirely, so on many machines it is simply
rem the one that starts.
rem
rem Everything it needs is already in this folder. Nothing is installed.
rem -----------------------------------------------------------------------
setlocal
set "HERE=%~dp0"
set "JAVA=%HERE%runtime\bin\java.exe"
set "APPDIR=%HERE%app"

if not exist "%JAVA%" (
    echo Could not find the bundled Java runtime:
    echo   "%JAVA%"
    echo.
    echo The folder is incomplete. Extract the whole zip again - do not copy
    echo just the .exe out of it.
    pause
    exit /b 1
)
if not exist "%APPDIR%\1DownloadManager.cfg" (
    echo Could not find "%APPDIR%\1DownloadManager.cfg".
    echo.
    echo The folder is incomplete. Extract the whole zip again.
    pause
    exit /b 1
)

rem If the app is already running, do not start a second copy. Two copies
rem fight over the same queue file and the same loopback port.
"%JAVA%" -cp "%APPDIR%\*" ^
    -Dcompose.application.resources.dir="%APPDIR%\resources" ^
    -Dcompose.application.configure.swing.globals=true ^
    -Dskiko.library.path="%APPDIR%" ^
    com.downloadhub.desktop.MainKt

rem Only reached if the app exited. Anything printed above is the real
rem reason it could not start, which is the point of this script.
echo.
echo The app exited. If it printed an error above, that is the cause.
pause
