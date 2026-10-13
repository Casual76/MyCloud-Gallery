<#
.SYNOPSIS
  Mette dei media di prova su un EMULATORE per lo smoke del benchmark (Fase 3, P9). Mai su un telefono vero.

.DESCRIPTION
  Il dataset della variante benchmarkRelease fa puntare le 100 mila righe sintetiche ai media VERI del dispositivo. Un
  emulatore appena creato non ne ha: con questo script ne ha una sessantina, generati qui (gradienti con una scritta, nelle
  proporzioni 4:3, 3:4, 16:9, 9:16, 1:1 e 3:1), copiati in Pictures/MCG-bench/ con `adb push`. Nessun dato dell'utente.
  A fine prove: `adb shell rm -r /sdcard/Pictures/MCG-bench` (o -Remove).

  Rifiuta di girare su un dispositivo che non sia un emulatore: sul telefono e sul tablet i media sono quelli veri, e la
  foto da 200 MP per lo zoom viene da tools/fixtures (P3, par. 5.3 del progetto) con il consenso dell'utente.

.PARAMETER Serial   Numero di serie dell'emulatore (`adb devices`), se ce n'e' piu' di uno.
.PARAMETER Count    Quante immagini generare (default 60).
.PARAMETER Remove   Toglie la cartella di prova dall'emulatore e basta.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\bench\seed-media.ps1
#>
[CmdletBinding()]
param([string]$Serial, [int]$Count = 60, [switch]$Remove)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path

function Get-SdkDir {
  $localProperties = Join-Path $root "local.properties"
  if (Test-Path -LiteralPath $localProperties) {
    $match = Select-String -LiteralPath $localProperties -Pattern '^\s*sdk\.dir\s*=\s*(.+)$' | Select-Object -First 1
    if ($match) { return $match.Matches[0].Groups[1].Value.Trim().Replace("\:", ":").Replace("\\", "\") }
  }
  if ($env:ANDROID_HOME) { return $env:ANDROID_HOME }
  return $env:ANDROID_SDK_ROOT
}

$sdk = Get-SdkDir
$adb = if ($sdk) { Join-Path $sdk "platform-tools\adb.exe" } else { "adb" }
$devices = @(& $adb devices | ForEach-Object { if ($_ -match '^(\S+)\s+device$') { $Matches[1] } })
if ($Serial) { $devices = @($devices | Where-Object { $_ -eq $Serial }) }
if ($devices.Count -ne 1) { Write-Host "Serve un solo dispositivo collegato (o -Serial). Trovati: $($devices.Count)." -ForegroundColor Red; exit 1 }
$serial = $devices[0]
$qemu = (& $adb -s $serial shell getprop ro.kernel.qemu | Out-String).Trim()
if ($serial -notlike "emulator-*" -and $qemu -ne "1") {
  Write-Host "Questo non e' un emulatore ($serial): non metto niente su un dispositivo vero." -ForegroundColor Red
  exit 1
}

$remote = "/sdcard/Pictures/MCG-bench"
if ($Remove) {
  & $adb -s $serial shell rm -r $remote | Out-Null
  & $adb -s $serial shell content call --uri content://media/ --method scan_volume --arg external_primary 2>&1 | Out-Null
  Write-Host "Cartella di prova tolta." -ForegroundColor Green
  exit 0
}

Add-Type -AssemblyName System.Drawing
$temp = Join-Path ([System.IO.Path]::GetTempPath()) ("mcg-bench-media-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
New-Item -ItemType Directory -Path $temp | Out-Null
try {
  $shapes = @(@(1600, 1200), @(1200, 1600), @(1920, 1080), @(1080, 1920), @(1400, 1400), @(2400, 800))
  $random = New-Object System.Random 4242
  for ($i = 0; $i -lt $Count; $i++) {
    $shape = $shapes[$i % $shapes.Count]
    $w = $shape[0]; $h = $shape[1]
    $bitmap = New-Object System.Drawing.Bitmap $w, $h
    try {
      $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
      try {
        $a = [System.Drawing.Color]::FromArgb(255, $random.Next(30, 220), $random.Next(30, 220), $random.Next(30, 220))
        $b = [System.Drawing.Color]::FromArgb(255, $random.Next(30, 220), $random.Next(30, 220), $random.Next(30, 220))
        $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush ((New-Object System.Drawing.Rectangle 0, 0, $w, $h)), $a, $b, 45.0
        $graphics.FillRectangle($brush, 0, 0, $w, $h)
        $font = New-Object System.Drawing.Font "Arial", ([float]($h / 8))
        $graphics.DrawString(("MCG " + $i), $font, [System.Drawing.Brushes]::White, 20.0, 20.0)
      } finally { $graphics.Dispose() }
      $name = "MCG_bench_{0:D3}.jpg" -f $i
      $bitmap.Save((Join-Path $temp $name), [System.Drawing.Imaging.ImageFormat]::Jpeg)
    } finally { $bitmap.Dispose() }
  }
  & $adb -s $serial shell mkdir -p $remote | Out-Null
  & $adb -s $serial push "$temp\." $remote | Out-Null
  & $adb -s $serial shell content call --uri content://media/ --method scan_volume --arg external_primary 2>&1 | Out-Null
  Write-Host ("{0} immagini copiate in {1} su {2}. Tolte con: tools\bench\seed-media.ps1 -Remove" -f $Count, $remote, $serial) -ForegroundColor Green
} finally {
  Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
}
exit 0
