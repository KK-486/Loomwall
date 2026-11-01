@echo off
rem Drive the editor with Chrome DevTools Protocol (screenshot / eval / click / drag).
rem Usage:
rem   cdp.cmd shot out.png
rem   cdp.cmd eval "document.title"
rem   cdp.cmd click 500 400
rem   cdp.cmd drag 500 400 700 400
rem Requires: node on PATH (or D:\node\node.exe) and Edge/Chrome installed.
setlocal
set NODE=D:\node\node.exe
if not exist "%NODE%" set NODE=node
"%NODE%" "%~dp0cdp.mjs" %*
endlocal
