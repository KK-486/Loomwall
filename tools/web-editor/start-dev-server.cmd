@echo off
rem Start the dev server (no window shown).
rem ASCII-only: cmd.exe parses batch files with the OEM code page.
wscript.exe "%~dp0start-dev-server.vbs"
echo Dev server starting on http://127.0.0.1:3009/
