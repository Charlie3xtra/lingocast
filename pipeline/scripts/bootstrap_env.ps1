[CmdletBinding()]
param(
    [string]$RootDir = 'D:\ccproject\lingocast'
)

$ErrorActionPreference = 'Stop'

$Toolchain = Join-Path $RootDir '.toolchain'
$AndroidSdk = Join-Path $Toolchain 'android-sdk'
$GradleHome = Join-Path $Toolchain 'gradle'
$VenvDir = Join-Path $Toolchain 'venv'
$HfCache = Join-Path $Toolchain 'hf-cache'
$UvCache = Join-Path $Toolchain 'uv-cache'
$TmpDir = Join-Path $Toolchain 'tmp'
$DictDir = Join-Path $RootDir 'data\dict'

foreach ($dir in @(
    $Toolchain,
    (Join-Path $AndroidSdk 'platforms'),
    (Join-Path $AndroidSdk 'build-tools'),
    (Join-Path $GradleHome 'dist'),
    $HfCache,
    $UvCache,
    $TmpDir,
    $DictDir
)) {
    [IO.Directory]::CreateDirectory($dir) | Out-Null
}

# Isolate ALL caches and temp directories onto D:
$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_SDK_ROOT = $AndroidSdk
$env:GRADLE_USER_HOME = $GradleHome
$env:HF_HOME = $HfCache
$env:UV_CACHE_DIR = $UvCache
$env:TEMP = $TmpDir
$env:TMP = $TmpDir

function Download-File {
    param([string]$Url, [string]$Dest)
    if ((Test-Path $Dest) -and ((Get-Item $Dest).Length -gt 0)) {
        Write-Host "[skip] Already downloaded: $Dest"
        return
    }
    $part = "$Dest.part"
    if (Test-Path $part) { Remove-Item $part -Force }
    Write-Host "[download] $Url -> $Dest"
    $curl = Get-Command 'curl.exe' -ErrorAction SilentlyContinue
    if ($curl) {
        & $curl.Source -fL --retry 3 --retry-delay 2 -C - -o $part $Url
        if ($LASTEXITCODE -ne 0) {
            throw "curl failed ($LASTEXITCODE) downloading $Url"
        }
    } else {
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest -Uri $Url -OutFile $part -UseBasicParsing
    }
    Move-Item -Path $part -Destination $Dest -Force
}

function Expand-ToNormalizedDir {
    param(
        [string]$ZipPath,
        [string]$TargetParent,
        [string]$NormalizedName,
        [string]$RequiredRelativeFile
    )
    $finalDir = Join-Path $TargetParent $NormalizedName
    $checkFile = Join-Path $finalDir $RequiredRelativeFile
    if (Test-Path $checkFile) {
        Write-Host "[skip] Already extracted: $finalDir"
        return
    }
    $staging = Join-Path $TmpDir ("extract_" + [Guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($staging) | Out-Null
    Write-Host "[extract] $ZipPath -> $finalDir"
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        [System.IO.Compression.ZipFile]::ExtractToDirectory($ZipPath, $staging)
        $children = @(Get-ChildItem -LiteralPath $staging)
        if ($children.Count -eq 1 -and $children[0].PSIsContainer) {
            $src = $children[0].FullName
        } else {
            $src = $staging
        }
        if (Test-Path $finalDir) { Remove-Item $finalDir -Recurse -Force }
        Move-Item -Path $src -Destination $finalDir -Force
        if (-not (Test-Path $checkFile)) {
            throw "Expected file not found after extraction: $checkFile"
        }
    } finally {
        if (Test-Path $staging) { Remove-Item $staging -Recurse -Force -ErrorAction SilentlyContinue }
    }
}

# 1. Android SDK Platform 36 & Build-Tools 36.0.0
$platformZip = Join-Path $TmpDir 'platform-36_r02.zip'
Download-File 'https://dl.google.com/android/repository/platform-36_r02.zip' $platformZip
Expand-ToNormalizedDir -ZipPath $platformZip -TargetParent (Join-Path $AndroidSdk 'platforms') -NormalizedName 'android-36' -RequiredRelativeFile 'android.jar'

$buildToolsZip = Join-Path $TmpDir 'build-tools_r36_windows.zip'
Download-File 'https://dl.google.com/android/repository/build-tools_r36_windows.zip' $buildToolsZip
Expand-ToNormalizedDir -ZipPath $buildToolsZip -TargetParent (Join-Path $AndroidSdk 'build-tools') -NormalizedName '36.0.0' -RequiredRelativeFile 'aapt2.exe'

# Create platform-tools/adb junction or copy if needed? No, AGP does not require platform-tools unless running adb from gradle,
# and existing adb is at F:\Programfiles\ADBgjx\AndroidWatch_ADB_ToolBox\adb\adb.exe. Ensure licenses exist for Gradle AGP.
$licensesDir = Join-Path $AndroidSdk 'licenses'
[IO.Directory]::CreateDirectory($licensesDir) | Out-Null
Set-Content -Path (Join-Path $licensesDir 'android-sdk-license') -Value "`r`n24333f8a63b6825ea9c5514f83c2829b004d1fee" -Encoding ASCII
Set-Content -Path (Join-Path $licensesDir 'android-sdk-preview-license') -Value "`r`n84831b9409646a918e30573bab4c9c91346d8abd" -Encoding ASCII

# 2. Gradle 8.13
$gradleZip = Join-Path $GradleHome 'dist\gradle-8.13-bin.zip'
Download-File 'https://services.gradle.org/distributions/gradle-8.13-bin.zip' $gradleZip
Expand-ToNormalizedDir -ZipPath $gradleZip -TargetParent $GradleHome -NormalizedName 'gradle-8.13' -RequiredRelativeFile 'bin\gradle.bat'

# 3. Python 3.12 venv + GPU Whisper & NLP dependencies via uv
$uvExe = Join-Path $env:USERPROFILE '.local\bin\uv.exe'
$py312 = Join-Path $env:USERPROFILE '.local\bin\python3.12.exe'
if (-not (Test-Path $uvExe)) { throw "uv.exe not found at $uvExe" }
if (-not (Test-Path $py312)) { throw "python3.12.exe not found at $py312" }

$venvPython = Join-Path $VenvDir 'Scripts\python.exe'
if (-not (Test-Path $venvPython)) {
    Write-Host "[venv] Creating Python 3.12 venv at $VenvDir"
    & $uvExe venv --python $py312 $VenvDir
    if ($LASTEXITCODE -ne 0) { throw "uv venv failed ($LASTEXITCODE)" }
}

$packages = @(
    'faster-whisper',
    'nvidia-cublas-cu12',
    'nvidia-cudnn-cu12',
    'rapidocr-onnxruntime',
    'simplemma',
    'pillow',
    'pydantic',
    'jsonschema',
    'httpx',
    'typer',
    'rich',
    'imageio-ffmpeg',
    'yt-dlp',
    'pytest'
)
Write-Host "[uv pip] Installing Python pipeline packages into $VenvDir"
& $uvExe pip install --python $venvPython @packages
if ($LASTEXITCODE -ne 0) { throw "uv pip install failed ($LASTEXITCODE)" }

# 4. Offline ECDICT CSV
$ecdictCsv = Join-Path $DictDir 'ecdict.csv'
Download-File 'https://raw.githubusercontent.com/skywind3000/ECDICT/master/ecdict.csv' $ecdictCsv

# Cleanup downloaded large zips in tmp after successful extraction
foreach ($z in @($platformZip, $buildToolsZip)) {
    if (Test-Path $z) { Remove-Item $z -Force -ErrorAction SilentlyContinue }
}

Write-Host '=== Bootstrap complete ==='
Write-Host "ANDROID_HOME = $AndroidSdk"
Write-Host "GRADLE_HOME  = $GradleHome\gradle-8.13"
Write-Host "VENV_PYTHON  = $venvPython"
Write-Host "ECDICT_CSV   = $ecdictCsv"
