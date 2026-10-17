# Deploys TV Head content to the tablet.
#
# Copies every supported media file from the repo (or a given folder) to the tablet's
# media folder, and pushes the media.json mapping to the folder the app can read it from.
#
# Usage:
#   .\tools\push-media.ps1                                  # repo media\ -> default device
#   .\tools\push-media.ps1 -Serial 192.168.1.157:35089
#   .\tools\push-media.ps1 -Source D:\other\media -Config D:\other\media.json
#   .\tools\push-media.ps1 -ConfigOnly
#
# Why two destinations: under Android's scoped storage the app can read *media* files by
# path from /sdcard/Movies/tv-head, but a non-media file such as media.json is refused
# with EACCES no matter which permissions are granted. The app-specific folder needs no
# permission at all, so the config goes there. Both are reachable with adb.

[CmdletBinding()]
param(
    [string]$Source = "",
    [string]$Config = "",
    [string]$Serial = "",
    [switch]$ConfigOnly
)

$ErrorActionPreference = 'Stop'

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
if (-not $Source) { $Source = Join-Path $repoRoot "media" }
if (-not $Config) {
    $candidate = Join-Path $Source "media.json"
    if (Test-Path $candidate) { $Config = $candidate }
}

$mediaDir = "/sdcard/Movies/tv-head"
$configDir = "/sdcard/Android/data/com.ortus.tvhead/files"

$extensions = @('.mp4', '.m4v', '.mkv', '.webm', '.avi', '.mov', '.3gp', '.ts',
                '.jpg', '.jpeg', '.png', '.webp', '.bmp')

$script:adbArgs = @()
if ($Serial) { $script:adbArgs = @('-s', $Serial) }

function Invoke-Adb {
    param([string[]]$Arguments)
    & adb @($script:adbArgs + $Arguments)
    if ($LASTEXITCODE -ne 0) {
        throw "adb $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
}

if (-not $ConfigOnly) {
    if (-not (Test-Path $Source)) { throw "Source not found: $Source" }

    Write-Host "== Creating $mediaDir on the device =="
    Invoke-Adb @('shell', "mkdir -p $mediaDir")

    $files = Get-ChildItem -Path $Source -File -Recurse |
        Where-Object { $extensions -contains $_.Extension.ToLower() }

    if (-not $files) {
        Write-Warning "No videos or images found under $Source"
    } else {
        foreach ($file in $files) {
            Invoke-Adb @('push', $file.FullName, "$mediaDir/") | Out-Null
            Write-Host ("  " + $file.Name)
        }
        Write-Host "Pushed $($files.Count) media file(s)."
    }
}

if ($Config) {
    if (-not (Test-Path $Config)) { throw "Config not found: $Config" }
    Write-Host "== Pushing config =="
    Invoke-Adb @('shell', "mkdir -p $configDir")
    Invoke-Adb @('push', (Resolve-Path $Config).Path, "$configDir/media.json") | Out-Null
    Write-Host ("  " + (Resolve-Path $Config).Path)
}

Write-Host "== media on device =="
Invoke-Adb @('shell', "ls -l $mediaDir")
Write-Host "== config on device =="
Invoke-Adb @('shell', "ls -l $configDir/videos.json")
