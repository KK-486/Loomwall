# Pack tools/web-editor into a zip embedded into the editor exe.
# ASCII-only on purpose: Windows PowerShell reads .ps1 as ANSI without a BOM.
# index.html needs 4 scripts (catalog / studio / studio-compile / editor);
# missing any of them silently breaks a feature (studio = trigger workbench).
$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$build = Join-Path $root ".toolchain\editor-pack"
$stage = Join-Path $build "stage"

if (-not (Test-Path $build)) { New-Item -ItemType Directory -Path $build -Force | Out-Null }
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Path $stage -Force | Out-Null

$files = @("index.html", "editor.js", "catalog.js", "studio.js", "studio-compile.js")
foreach ($f in $files) {
    $src = Join-Path $root "tools\web-editor\$f"
    if (-not (Test-Path $src)) { throw ("missing editor file: " + $f) }
    Copy-Item $src $stage -Force
}

$icons = Join-Path $root "tools\web-editor\icons"
if (Test-Path $icons) { Copy-Item $icons (Join-Path $stage "icons") -Recurse -Force }

$zip = Join-Path $build "web-editor.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory($stage, $zip)

# Self-check: every required script must be inside the zip
$entries = [System.IO.Compression.ZipFile]::OpenRead($zip).Entries | ForEach-Object { $_.FullName }
foreach ($need in $files) {
    if ($entries -notcontains $need) { throw ("zip is missing " + $need) }
}
$kb = [math]::Round((Get-Item $zip).Length / 1KB, 1)
Write-Host ("packed " + $kb + " KB, " + $entries.Count + " entries")
