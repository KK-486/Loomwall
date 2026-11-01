# Run the JVM unit tests without Gradle's test worker.
#
# Why this exists: Gradle's test executor talks to the test JVM over pipes.
# In restricted environments (some sandboxes, hardened CI agents) that fails with
# "Could not write standard input to Gradle Test Executor" or
# "ClassNotFoundException: worker.org.gradle.process.internal.worker.GradleWorkerMain".
# Running JUnit directly in one JVM avoids the pipe entirely and gives the same result.
#
# Usage (from the repo root):
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\run-tests.ps1
#
# Standard environments can simply use:  build.cmd testDebugUnitTest
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$java = Join-Path $root '.toolchain\jdk\jdk-17.0.13+11\bin\java.exe'
$cache = Join-Path $root '.toolchain\gradle-home\caches\modules-2\files-2.1'

if (-not (Test-Path -LiteralPath $java)) {
    Write-Host "[test] portable JDK not found: $java"
    Write-Host "[test] run .toolchain\scripts\bootstrap.cmd first, or use Android Studio."
    exit 1
}

Write-Host "[test] step 1/2: compile test sources"
& (Join-Path $root 'build.cmd') compileDebugUnitTestKotlin | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "[test] compilation failed"
    exit $LASTEXITCODE
}

$testClasses = Join-Path $root 'app\build\tmp\kotlin-classes\debugUnitTest'
$mainClasses = Join-Path $root 'app\build\tmp\kotlin-classes\debug'
foreach ($dir in @($testClasses, $mainClasses)) {
    if (-not (Test-Path -LiteralPath $dir)) {
        Write-Host "[test] missing compiled classes: $dir"
        exit 1
    }
}

$jarPatterns = @(
    'junit-4.13.2.jar',
    'hamcrest-core-1.3.jar',
    'json-20240303.jar',
    'kotlin-stdlib-2.0.21.jar'
)
$jars = @()
foreach ($pattern in $jarPatterns) {
    $found = Get-ChildItem -Path $cache -Recurse -Filter $pattern -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $found) {
        Write-Host "[test] dependency jar not found in Gradle cache: $pattern"
        Write-Host "[test] run 'build.cmd assembleDebug' once to populate the cache."
        exit 1
    }
    $jars += $found.FullName
}

$classpath = (@($testClasses, $mainClasses) + $jars) -join ';'

Write-Host "[test] step 2/2: run JUnit"
Push-Location (Join-Path $root 'app')
try {
    & $java '-Dfile.encoding=UTF-8' -cp $classpath org.junit.runner.JUnitCore `
        com.loomwall.wallpaper.scene.SceneParserTest `
        com.loomwall.wallpaper.scene.SceneWriterTest `
        com.loomwall.wallpaper.scene.EasingTest `
        com.loomwall.wallpaper.scene.InteractionsParserTest `
        com.loomwall.wallpaper.engine.AnimationRuntimeTest com.loomwall.wallpaper.engine.BindingEvaluatorTest `
        com.loomwall.wallpaper.editor.EditorStateTest com.loomwall.wallpaper.scene.TextTemplateTest com.loomwall.wallpaper.scene.EventsTest com.loomwall.wallpaper.scene.ImageNodeTest com.loomwall.wallpaper.engine.Mat2DTest com.loomwall.wallpaper.scene.ShapesTest com.loomwall.wallpaper.scene.SvgPathDataTest com.loomwall.wallpaper.scene.GradientSpecTest com.loomwall.wallpaper.scene.FeatherBlurTest com.loomwall.wallpaper.scene.ParallaxTest com.loomwall.wallpaper.scene.ExprTest com.loomwall.wallpaper.scene.RuleModelTest com.loomwall.wallpaper.engine.RuleRuntimeTest com.loomwall.wallpaper.engine.LoopRuleTest com.loomwall.wallpaper.engine.HitThroughTest com.loomwall.wallpaper.engine.TweenTest com.loomwall.wallpaper.engine.ReverseAnimationTest com.loomwall.wallpaper.engine.EffectiveAlphaTest com.loomwall.wallpaper.scene.PresetLibraryExportTest
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $code
