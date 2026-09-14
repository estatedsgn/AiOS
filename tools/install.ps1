<#
.SYNOPSIS
    Installs AiOS onto an Android phone connected over USB and switches on the
    accessibility service the agent needs in order to act.

.DESCRIPTION
    Enabling that service normally means walking through Settings by hand. Over
    a cable it can be written directly to secure settings, which is what makes
    this a one-command install - and it is also why the script prints exactly
    what it granted: this is the permission that lets the agent touch anything
    on screen.

.EXAMPLE
    .\tools\install.ps1 -Download
    .\tools\install.ps1 -Apk C:\path\to\aios.apk
    .\tools\install.ps1 -Uninstall
#>
[CmdletBinding()]
param(
    [switch]$Download,
    [switch]$Build,
    [switch]$Uninstall,
    [string]$Apk,
    [string]$Serial
)

$ErrorActionPreference = 'Stop'

$Package  = 'ai.aios.app'
$Service  = "$Package/$Package.device.AiosAccessibilityService"
$Activity = "$Package/$Package.ui.MainActivity"
$Repo     = 'estatedsgn/AiOS'

$RepoRoot = Split-Path -Parent $PSScriptRoot
$LocalApk = Join-Path $RepoRoot 'app\build\outputs\apk\debug\app-debug.apk'

function Write-Head($t) { Write-Host $t -ForegroundColor White }
function Write-Info($t) { Write-Host "  $t" }
function Write-Warn($t) { Write-Host "  $t" -ForegroundColor Yellow }
function Stop-With($t)  { Write-Host "error: $t" -ForegroundColor Red; exit 1 }

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Stop-With @"
adb is not on PATH.
  Install Android SDK platform-tools and add it to PATH:
  https://developer.android.com/tools/releases/platform-tools
"@
}

adb start-server 2>$null | Out-Null

# --- pick a device ----------------------------------------------------------

$ready = @()
foreach ($line in (adb devices | Select-Object -Skip 1)) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $parts = $line -split '\s+'
    switch ($parts[1]) {
        'device'       { $ready += $parts[0] }
        'unauthorized' {
            Stop-With "Phone $($parts[0]) has not authorised this computer.`n  Unlock the screen and tap 'Allow USB debugging', then run this again."
        }
        'offline'      { Write-Warn "Phone $($parts[0]) is offline - replug the cable if it is not picked up." }
    }
}

if ($Serial) { $ready = @($Serial) }
elseif ($ready.Count -eq 0) {
    Stop-With "No phone is connected with USB debugging on.`n  On the phone: Settings > About phone > tap 'Build number' seven times,`n  then Settings > Developer options > USB debugging."
}
elseif ($ready.Count -gt 1) {
    Stop-With "More than one device is attached: $($ready -join ', ')`n  Pick one with -Serial <id>."
}

$device = $ready[0]
function Adb { adb -s $device @args }
# `settings get` comes back with a trailing carriage return on many devices.
function AdbSh { (adb -s $device shell @args) -replace "`r", '' }

$model = AdbSh getprop ro.product.model
$rel   = AdbSh getprop ro.build.version.release
$sdk   = [int](AdbSh getprop ro.build.version.sdk)

Write-Head 'AiOS installer'
Write-Info "Device:  $model ($device)"
Write-Info "Android: $rel (API $sdk)"

if ($sdk -lt 26) { Stop-With "AiOS needs Android 8.0 (API 26) or newer; this device reports API $sdk." }

# --- uninstall --------------------------------------------------------------

if ($Uninstall) {
    Write-Head 'Removing AiOS'
    $current = AdbSh settings get secure enabled_accessibility_services
    if ($current -and $current -ne 'null') {
        # Keep any other accessibility services the user relies on.
        $remaining = ($current -split ':' | Where-Object { $_ -notlike "$Package/*" }) -join ':'
        AdbSh settings put secure enabled_accessibility_services "$remaining" | Out-Null
        Write-Info 'Revoked the accessibility grant.'
    }
    Adb uninstall $Package | Out-Null
    Write-Info "Uninstalled $Package."
    exit 0
}

# --- find an APK ------------------------------------------------------------

function Get-LatestApk {
    Write-Info "Looking up the latest release of $Repo..."
    $release = Invoke-RestMethod "https://api.github.com/repos/$Repo/releases/latest"
    $asset = $release.assets | Where-Object { $_.name -like '*.apk' } | Select-Object -First 1
    if (-not $asset) {
        Stop-With "No published APK found.`n  Tag a commit (git tag v0.1.0; git push --tags) to have CI build and publish one,`n  or download the 'aios-apk' artifact from the Actions tab and pass it with -Apk."
    }
    $dest = Join-Path ([System.IO.Path]::GetTempPath()) $asset.name
    Write-Info "Downloading $($asset.name)"
    Invoke-WebRequest $asset.browser_download_url -OutFile $dest
    return $dest
}

function Build-Apk {
    if (-not ($env:ANDROID_HOME -or $env:ANDROID_SDK_ROOT -or (Test-Path (Join-Path $RepoRoot 'local.properties')))) {
        Stop-With "No Android SDK found, so the APK cannot be built here.`n  Either install the SDK, or use -Download to fetch a CI-built APK."
    }
    Write-Info 'Building the APK (this takes a few minutes the first time)...'
    Push-Location $RepoRoot
    try { & .\gradlew.bat :app:assembleDebug --no-daemon -q } finally { Pop-Location }
    return $LocalApk
}

if ($Download)      { $Apk = Get-LatestApk }
elseif ($Build)     { $Apk = Build-Apk }
elseif ($Apk)       { if (-not (Test-Path $Apk)) { Stop-With "no such file: $Apk" } }
elseif (Test-Path $LocalApk) {
    $Apk = $LocalApk
    Write-Info 'Using the APK already built at app\build\outputs\apk\debug\'
}
elseif ($env:ANDROID_HOME -or $env:ANDROID_SDK_ROOT) { $Apk = Build-Apk }
else { $Apk = Get-LatestApk }

# --- install ----------------------------------------------------------------

Write-Head 'Installing'
Write-Info "$(Split-Path -Leaf $Apk) ($([math]::Round((Get-Item $Apk).Length / 1MB, 1)) MB)"

# -r replaces an existing install, -g pre-grants runtime permissions so the run
# notification (and its Stop button) is never silent.
$result = Adb install -r -g $Apk 2>&1 | Out-String
if ($result -notmatch 'Success') {
    Write-Warn 'Install failed. If this says INSTALL_FAILED_UPDATE_INCOMPATIBLE, run:'
    Write-Warn '  .\tools\install.ps1 -Uninstall   then install again.'
    Stop-With 'adb install did not report success'
}
Write-Info "Installed $Package."

# --- grant the agent its hands ---------------------------------------------

Write-Head 'Enabling the accessibility service'
Write-Info 'This is what lets the agent read the screen and tap, swipe and type.'

$current = AdbSh settings get secure enabled_accessibility_services
if (-not $current -or $current -eq 'null') { $updated = $Service }
elseif ($current -like "*$Service*")        { $updated = $current }
else                                        { $updated = "$current`:$Service" }

AdbSh settings put secure enabled_accessibility_services "$updated" | Out-Null
AdbSh settings put secure accessibility_enabled 1 | Out-Null

$verify = AdbSh settings get secure enabled_accessibility_services
if ($verify -like "*$Service*") {
    Write-Info 'Granted. AiOS can now operate this phone.'
} else {
    Write-Warn 'Could not enable it over the cable - some vendor builds refuse this.'
    Write-Warn "Turn on 'AiOS agent control' by hand in Settings > Accessibility."
}

Adb shell am start -n $Activity 2>$null | Out-Null

Write-Head 'Done'
Write-Info 'Open AiOS on the phone, paste your Anthropic API key in Settings, and give it a goal.'
Write-Info 'Every run shows a notification with a Stop button.'
Write-Info 'To remove it and revoke the access: .\tools\install.ps1 -Uninstall'
