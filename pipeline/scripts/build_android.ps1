[CmdletBinding()]
param(
    [string]$RootDir = 'D:\ccproject\lingocast',
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'

$Toolchain = Join-Path $RootDir '.toolchain'
$AndroidDir = Join-Path $RootDir 'android'
$GradleBin = Join-Path $Toolchain 'gradle\gradle-8.13\bin\gradle.bat'
$Jdk17 = 'C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'

$env:JAVA_HOME = $Jdk17
$env:ANDROID_HOME = Join-Path $Toolchain 'android-sdk'
$env:ANDROID_SDK_ROOT = Join-Path $Toolchain 'android-sdk'
$env:GRADLE_USER_HOME = Join-Path $Toolchain 'gradle'
$env:TEMP = Join-Path $Toolchain 'tmp'
$env:TMP = Join-Path $Toolchain 'tmp'
$env:PATH = "$Jdk17\bin;$env:PATH"

$localProps = Join-Path $AndroidDir 'local.properties'
$sdkEscaped = ($env:ANDROID_HOME -replace '\\', '\\')
Set-Content -Path $localProps -Value "sdk.dir=$sdkEscaped" -Encoding ASCII

$tasks = if ($SkipTests) { @('assembleDebug') } else { @('testDebugUnitTest', 'assembleDebug') }

Push-Location $AndroidDir
try {
    Write-Host "[gradle] Running: $($tasks -join ' ') with JAVA_HOME=$env:JAVA_HOME"
    & $GradleBin --no-daemon @tasks
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

$apkSrc = Join-Path $AndroidDir 'app\build\outputs\apk\debug\app-debug.apk'
$apkDst = Join-Path $RootDir 'dist\LingoCast-debug.apk'
if (Test-Path $apkSrc) {
    Copy-Item -Path $apkSrc -Destination $apkDst -Force
    $sizeMb = [math]::Round((Get-Item $apkDst).Length / 1MB, 2)
    Write-Host "[apk] Built $apkDst ($sizeMb MB)"
}
