@echo off
REM ---------------------------------------------------------------------------
REM  Build APK with the portable toolchain bundled in .toolchain
REM  (no system-wide JDK / Android SDK / Gradle required).
REM
REM  Usage:
REM    build.cmd                       build debug APK
REM    build.cmd assembleRelease       build release APK
REM    build.cmd clean assembleDebug   other Gradle tasks are passed through
REM
REM  Output: app\build\outputs\apk\debug\app-debug.apk
REM
REM  NOTE: this file is intentionally ASCII-only. cmd.exe parses batch files
REM  with the active OEM code page, so UTF-8 Chinese comments break parsing.
REM ---------------------------------------------------------------------------
setlocal
set "T=%~dp0.toolchain"

if not exist "%T%\jdk\jdk-17.0.13+11\bin\java.exe" (
    echo [build] portable toolchain not found: %T%
    echo [build] run tools\bootstrap.cmd first.
    exit /b 1
)

set "JAVA_HOME=%T%\jdk\jdk-17.0.13+11"
set "ANDROID_SDK_ROOT=%T%\android-sdk"
set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
set "ANDROID_USER_HOME=%T%\android-home"
set "GRADLE_USER_HOME=%T%\gradle-home"
set "PATH=%JAVA_HOME%\bin;%T%\gradle-8.7\bin;%ANDROID_SDK_ROOT%\platform-tools;%ANDROID_SDK_ROOT%\cmdline-tools\latest\bin;%PATH%"

REM Do not write local.properties: AGP falls back to ANDROID_HOME / ANDROID_SDK_ROOT,
REM which also avoids Properties-file encoding problems with non-ASCII paths.

if "%~1"=="" (
    call gradle.bat assembleDebug --no-daemon --console=plain
) else (
    call gradle.bat %* --no-daemon --console=plain
)
set "RC=%ERRORLEVEL%"
endlocal & exit /b %RC%
