<#
.SYNOPSIS
  Il corpo dei livelli emulatore e s25 di tools\security\run-all.ps1 (Fase 10 P11, seconda parte). Si lancia da run-all.ps1 o da
  tools\chaos\s25.ps1 -Mode security; i parametri sono quelli di run-all.ps1 (vedi li').
#>
param(
  [string]$Root,
  [string]$OutDir,
  [ValidateSet("emulatore", "s25")][string]$Livello,
  [string]$Serial,
  [string[]]$Passo,
  [switch]$Piano,
  [string]$Preparati,
  [string]$ReleaseApk,
  [string]$DebugApk,
  [string]$Test2Serial,
  [string]$NasIp = "192.168.178.58",
  [string]$KitFile,
  [string]$StatoDir,
  [int]$SettleSeconds = 90,
  [int]$MemoryCapMb = 1536,
  [int]$MassFiles = 2000,
  [int]$PollSeconds = 3,
  [switch]$Release,
  [switch]$SenzaReset
)
$ErrorActionPreference = "Stop"
foreach ($lib in @("security-common", "dispositivo", "copioni", "copione-famiglia", "copioni-app", "analisi", "voci", "voci-sistema", "famiglia", "livelli")) {
  . (Join-Path $PSScriptRoot "$lib.ps1")
}

# -Passo S2,S3 arriva come una stringa sola da powershell -File: si divide qui.
$Passo = @($Passo | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$planLevel = if ($Release -and $Livello -eq "s25") { "s25-release" } else { $Livello }
$plan = Get-SecPlan $planLevel -SenzaReset:$SenzaReset
if ($Piano) { Show-SecPlan $planLevel $plan; exit 0 }

if (-not $Serial) { $Serial = if ($Livello -eq "emulatore") { $SecEmulatorSerial } else { $SecPhoneSerial } }
$errors = @(Get-SecDeviceErrors $Livello $Serial $SecDebugPackage)
if ($Test2Serial) { $errors += @(Get-SecDeviceErrors "s25" $Test2Serial $SecDebugPackage | Where-Object { $_ -notmatch "tocca solo" }) }
if ($NasIp -notmatch '^(\d{1,3}\.){3}\d{1,3}$') { $errors += "-NasIp deve essere un indirizzo IPv4 letterale." }
if ($KitFile -and ($KitFile -like "$Root*")) { $errors += "Il file del kit va fuori dal repository (-KitFile)." }
if ($errors.Count -gt 0) { $errors | ForEach-Object { Write-Host $_ -ForegroundColor Red }; exit 2 }

# Le buste del manifest e le immagini ostili: da un run-all -Livello jvm (la cartella piu' recente che le ha, o -Preparati).
if (-not $Preparati) {
  $Preparati = Get-ChildItem -LiteralPath (Join-Path $Root "build\security") -Directory -ErrorAction SilentlyContinue |
    Where-Object { (Test-Path (Join-Path $_.FullName "manifest")) -and (Test-Path (Join-Path $_.FullName "ostili")) } |
    Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $StatoDir) { $StatoDir = Join-Path $Root "build\security\famiglia-di-prova" }
New-Item -ItemType Directory -Force -Path $OutDir, $StatoDir | Out-Null

$script:SecCtx = [pscustomobject]@{
  Root = $Root; Livello = $Livello; Serial = $Serial; Package = $SecDebugPackage; Emulator = ($Livello -eq "emulatore")
  ReleaseSerial = $(if ($Livello -eq "emulatore" -or $Release) { $Serial } else { $null }); ReleasePackage = $SecReleasePackage; ReleaseReady = $false; ReleaseInstalled = $false
  StateDir = $StatoDir; OutDir = $OutDir; LogDir = (Join-Path $OutDir "schermate"); Test2Serial = $Test2Serial
  NasIp = $NasIp; KitFile = $KitFile; SettleSeconds = $SettleSeconds; MemoryCapMb = $MemoryCapMb; MassFiles = $MassFiles; PollSeconds = $PollSeconds
  UserWaitMinutes = 30; SenzaReset = [bool]$SenzaReset; HomePossible = ($Livello -eq "s25")
  ManifestDir = $(if ($Preparati) { Join-Path $Preparati "manifest" } else { $null }); ImagesDir = $(if ($Preparati) { Join-Path $Preparati "ostili" } else { $null })
  Pcap = $(if ($Livello -eq "emulatore") { Join-Path $OutDir "s8b-emulatore.pcap" } else { $null })
}
$script:SecAuthStopped = $false

if (-not $Preparati) { Write-Host "Mancano le buste del manifest e le immagini ostili: prima run-all.ps1 -Livello jvm -Voce S7 (con -VersionCode della build) e -Voce S3." -ForegroundColor Yellow }

Initialize-SecAdb $Root $Serial -NoState:($Livello -eq "emulatore")
$report = Join-Path $OutDir "$Livello.txt"
$code = 1
try {
  if ($Livello -eq "emulatore") {
    # L'emulatore si accende nel primo passo; le installazioni (solo sull'emulatore) dopo l'accensione, prima delle voci.
    $installHook = {
      if (-not $DebugApk) { $DebugApk = Join-Path $Root "app\build\outputs\apk\debug\app-debug.apk" }
      if (-not ((Invoke-SecShell $Serial @("pm", "path", $SecDebugPackage)).Text -match "package:") -and (Test-Path -LiteralPath $DebugApk)) {
        [void](Invoke-SecAdb $Serial @("install", "-r", $DebugApk))
      }
      if ($ReleaseApk -and (Test-Path -LiteralPath $ReleaseApk)) {
        $had = (Invoke-SecShell $Serial @("pm", "path", $SecReleasePackage)).Text -match "package:"
        [void](Invoke-SecAdb $Serial @("install", "-r", $ReleaseApk))
        $script:SecCtx.ReleaseInstalled = -not $had
      }
      $script:SecCtx.ReleaseReady = ((Invoke-SecShell $Serial @("pm", "path", $SecReleasePackage)).Text -match "package:")
    }
    $script:SecInstallHook = $installHook
  } elseif ($Release) {
    # La release candidata sull'S25: installata e configurata dall'utente (onboarding con la sua password), mai dallo script.
    $script:SecCtx.ReleaseReady = ((Invoke-SecShell $Serial @("pm", "path", $SecReleasePackage)).Text -match "package:")
  }
  $code = Invoke-SecLivello $planLevel $Passo $report
} finally {
  if ($script:SecStartedEmulator) { Stop-SecEmulator }
}
Write-Host ""
Get-Content -LiteralPath $report | Write-Host
Write-Host "Resoconto: $report"
exit $code
