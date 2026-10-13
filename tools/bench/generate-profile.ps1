<#
.SYNOPSIS
  Genera il Baseline Profile dell'app sul dispositivo collegato e lo scrive nei sorgenti (Fase 3, P9, D21).

.DESCRIPTION
  Il percorso UFFICIALE e' `gradlew :app:generateBaselineProfile` (plugin androidx.baselineprofile): gira sulla variante
  `nonMinifiedRelease`. Quella variante pero' NON ha l'inizializzatore del dataset (vive solo in
  app/src/benchmarkRelease/) ne' il ProfileInstallReceiver: senza una Libreria da percorrere il generatore si salta, per non
  committare un profilo fatto sull'onboarding. Questo script fa lo stesso lavoro sulla variante `benchmarkRelease` (la
  stessa delle misure: dataset da 100 mila righe, receiver, profileable) e copia il risultato dove il plugin lo mette:

    app\src\main\generated\baselineProfiles\baseline-prof.txt
    app\src\main\generated\baselineProfiles\startup-prof.txt

  Da committare. Il profilo di una variante con R8 (senza offuscamento) contiene i metodi dopo l'ottimizzazione: R8 scarta
  da solo le regole che non trovano piu' il metodo quando riesegue il build di release con il profilo come ingresso.

  Prima di generarlo servono: dispositivo collegato (S25, o l'emulatore Medium_Phone), app sbloccata, e la Libreria
  raggiungibile (finche' P11/M1 non la collegano, il generatore si salta e lo script lo dice).

.PARAMETER Serial   Numero di serie (`adb devices`) se ce n'e' piu' di uno.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\bench\generate-profile.ps1
#>
[CmdletBinding()]
param([string]$Serial)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $root

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
if ($devices.Count -eq 0) { Write-Host "Nessun dispositivo collegato (adb devices)." -ForegroundColor Red; exit 1 }
if ($devices.Count -gt 1) { Write-Host "Piu' dispositivi collegati: indica -Serial ($($devices -join ', '))." -ForegroundColor Red; exit 1 }
$env:ANDROID_SERIAL = $devices[0]

if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
  $candidate = Get-ChildItem "C:\Program Files\Microsoft" -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
  if ($candidate) { $env:JAVA_HOME = $candidate.FullName }
}

$outputRoot = Join-Path $root "benchmark\build\outputs\connected_android_test_additional_output"
if (Test-Path -LiteralPath $outputRoot) { Remove-Item -LiteralPath $outputRoot -Recurse -Force }

Write-Host ("Genero il Baseline Profile su " + $devices[0] + " (variante benchmarkRelease)...") -ForegroundColor Cyan
& (Join-Path $root "gradlew.bat") --console=plain ":benchmark:connectedBenchmarkReleaseAndroidTest" `
  "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=baselineprofile" `
  "-Pandroid.testInstrumentationRunnerArguments.class=dev.pampa.mycloudgallery.benchmark.BaselineProfileGenerator"
if ($LASTEXITCODE -ne 0) { Write-Host "Il generatore e' fallito (codice $LASTEXITCODE)." -ForegroundColor Red; exit 1 }

$profile = Get-ChildItem -LiteralPath $outputRoot -Recurse -Filter "*baseline-prof.txt" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $profile) {
  Write-Host "Nessun profilo prodotto: il generatore si e' saltato (Libreria non raggiungibile?). Vedi il rapporto dei test: benchmark\build\reports\androidTests\connected." -ForegroundColor Yellow
  exit 1
}
$rules = @(Get-Content -LiteralPath $profile.FullName | Where-Object { $_.Trim().Length -gt 0 -and -not $_.TrimStart().StartsWith("#") })
if ($rules.Count -lt 50) {
  Write-Host ("Il profilo ha solo " + $rules.Count + " regole: non lo copio.") -ForegroundColor Red
  exit 1
}
$target = Join-Path $root "app\src\main\generated\baselineProfiles"
New-Item -ItemType Directory -Path $target -Force | Out-Null
Copy-Item -LiteralPath $profile.FullName -Destination (Join-Path $target "baseline-prof.txt") -Force
$startup = Get-ChildItem -LiteralPath $outputRoot -Recurse -Filter "*startup-prof.txt" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($startup) { Copy-Item -LiteralPath $startup.FullName -Destination (Join-Path $target "startup-prof.txt") -Force }
Write-Host ("Profilo scritto in app\src\main\generated\baselineProfiles ({0} regole{1}). Da committare." -f $rules.Count, $(if ($startup) { ", con il profilo di avvio" } else { "" })) -ForegroundColor Green
exit 0
