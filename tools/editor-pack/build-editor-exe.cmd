@echo off
rem Build the standalone editor server exe.
rem ASCII-only + CRLF: cmd.exe parses batch files with the OEM code page.
setlocal
set ROOT=%~dp0..\..
set BUILD=%ROOT%\.toolchain\editor-pack
set CSC=C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe

if not exist "%BUILD%" mkdir "%BUILD%"

powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\tools\editor-pack\pack-assets.ps1"
if errorlevel 1 goto fail

"%CSC%" /nologo /target:exe /platform:anycpu /optimize+ /out:"%BUILD%\LOOW-Editor-1.0.1.exe" /win32icon:"%ROOT%\tools\editor-pack\loow.ico" /resource:"%BUILD%\web-editor.zip",web-editor.zip /reference:System.dll /reference:System.IO.Compression.dll /reference:System.IO.Compression.FileSystem.dll "%ROOT%\tools\editor-pack\EditorServer.cs"
if errorlevel 1 goto fail

if not exist "%ROOT%\dist" mkdir "%ROOT%\dist"
copy /y "%BUILD%\LOOW-Editor-1.0.1.exe" "%ROOT%\dist\LOOW-Editor-1.0.1.exe" >nul
echo OK: dist\LOOW-Editor-1.0.1.exe
goto end

:fail
echo BUILD FAILED
exit /b 1

:end
endlocal
