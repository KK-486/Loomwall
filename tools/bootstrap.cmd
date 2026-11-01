@echo off
REM ---------------------------------------------------------------------------
REM  Build the portable toolchain in .toolchain (JDK 17, Android cmdline-tools,
REM  platform-35, build-tools 35.0.0, Gradle 8.7, platform-tools).
REM
REM  Requirements: Node.js on PATH (downloads use Node's TLS stack, which works
REM  where Windows schannel may not), and tar.exe (Windows 10 1803+).
REM
REM  This mirrors the steps used to create .toolchain:
REM  download -> extract -> accept licenses -> install SDK packages.
REM
REM  ASCII-only on purpose: cmd.exe parses batch files with the OEM code page.
REM ---------------------------------------------------------------------------
setlocal enabledelayedexpansion
set "T=%~dp0..\.toolchain"
set "DL=%T%\dl"

echo [bootstrap] toolchain root: %T%

where node >nul 2>nul
if errorlevel 1 (
    echo [bootstrap] Node.js not found on PATH. Install Node.js, or use Android Studio instead.
    exit /b 1
)

echo [bootstrap] step 1/4: download archives
node "%~dp0download.js"
if errorlevel 1 (
    echo [bootstrap] download failed.
    exit /b 1
)

echo [bootstrap] step 2/4: extract
if not exist "%T%\jdk" mkdir "%T%\jdk"
if not exist "%T%\android-sdk\cmdline-tools" mkdir "%T%\android-sdk\cmdline-tools"
if not exist "%T%\android-home" mkdir "%T%\android-home"

tar.exe -xf "%DL%\jdk17-win-x64.zip" -C "%T%\jdk" || exit /b 1
tar.exe -xf "%DL%\commandlinetools-win.zip" -C "%T%\android-sdk\cmdline-tools" || exit /b 1
tar.exe -xf "%DL%\gradle-8.7-bin.zip" -C "%T%" || exit /b 1
tar.exe -xf "%DL%\platform-tools-win.zip" -C "%T%\android-sdk" || exit /b 1

if exist "%T%\android-sdk\cmdline-tools\cmdline-tools" (
    move "%T%\android-sdk\cmdline-tools\cmdline-tools" "%T%\android-sdk\cmdline-tools\latest" >nul
)

echo [bootstrap] step 3/4: accept SDK licenses
set "JAVA_HOME=%T%\jdk\jdk-17.0.13+11"
set "ANDROID_SDK_ROOT=%T%\android-sdk"
set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
set "ANDROID_USER_HOME=%T%\android-home"
set "GRADLE_USER_HOME=%T%\gradle-home"
set "PATH=%JAVA_HOME%\bin;%ANDROID_SDK_ROOT%\cmdline-tools\latest\bin;%ANDROID_SDK_ROOT%\platform-tools;%PATH%"

(for /l %%i in (1,1,40) do @echo y) | call "%ANDROID_SDK_ROOT%\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root="%ANDROID_SDK_ROOT%" --licenses

echo [bootstrap] step 4/4: install platform + build-tools
call "%ANDROID_SDK_ROOT%\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root="%ANDROID_SDK_ROOT%" "platforms;android-35" "build-tools;35.0.0"
if errorlevel 1 (
    echo [bootstrap] SDK install failed.
    exit /b 1
)

echo [bootstrap] done. now run: build.cmd
endlocal
