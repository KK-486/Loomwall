' Start the dev static server without a visible window.
'
' Why a VBScript wrapper:
'   A process started with Start-Process still shares the parent console, so it dies
'   together with the launcher window. pythonw has no usable stderr, which makes
'   http.server throw on every request. wscript with window style 0 gives us a
'   fully detached, invisible process - the simplest reliable option here.
' ASCII only: wscript parses .vbs as ANSI, non-ASCII comments can break it.
Option Explicit

Dim fso, sh, dir, nodeExe, cmd
Set fso = CreateObject("Scripting.FileSystemObject")
Set sh = CreateObject("WScript.Shell")

dir = fso.GetParentFolderName(WScript.ScriptFullName)

' Prefer the portable Node shipped with the repo, fall back to PATH.
nodeExe = "D:\node\node.exe"
If Not fso.FileExists(nodeExe) Then nodeExe = "node"

cmd = """" & nodeExe & """ """ & dir & "\serve.js"" 3009"
sh.CurrentDirectory = dir
' 0 = hidden window, False = do not wait
sh.Run cmd, 0, False
