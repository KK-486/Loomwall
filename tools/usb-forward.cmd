@echo off
setlocal
set ROOT=%~dp0..
set ADB=%ROOT%\.toolchain\android-sdk\platform-tools\adb.exe
if not exist "%ADB%" (
  echo [error] adb not found: %ADB%
  pause
  exit /b 1
)
echo [1/2] checking device...
"%ADB%" devices
echo [2/2] forwarding tcp:8765 -^> tcp:8765 ...
"%ADB%" forward tcp:8765 tcp:8765
if errorlevel 1 (
  echo [error] forward failed. Is the phone connected and authorized?
  pause
  exit /b 1
)
echo.
echo [ok] Now open the editor and click "send to phone".
echo      The page will auto-detect 127.0.0.1:8765 over USB.
echo.
pause