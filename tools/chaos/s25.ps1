<#
.SYNOPSIS
  Interruzioni casuali sul telefono durante una mossa di massa sul NAS: la prova delle 500 interruzioni lato telefono
  (docs/fase4-progetto.md par. 5.5, P15; come si usa: docs/prova-500.md).

.DESCRIPTION
  L'utente avvia nell'app una mossa di massa (di norma tra due share di prova, sotto mcg-it/<giro>/), poi lancia questo script.
  A intervalli casuali lo script fa UNA di due cose:
    - uccide il processo dell'app (la morte del processo: l'app deve ripartire dal suo diario locale);
    - spegne il Wi-Fi per qualche secondo e lo riaccende (la rete che cade a meta' di un passo).
  Dopo ogni morte rilancia l'app (se non si chiede altrimenti), cosi' la ripresa parte come all'avvio vero. A fine prova aspetta che la
  ripresa finisca; con -Verify lancia il verificatore degli invarianti sull'area di prova (tools\chaos\area.ps1 -Mode verify).

  Il calendario delle interruzioni (quando e che cosa) dipende solo dal seme: lo stesso -Seed rifa' la stessa prova. Con -DryRun lo script
  stampa il calendario e i comandi adb che userebbe e NON ESEGUE NIENTE (nemmeno adb): e' il modo di controllarlo senza il telefono.

  Sicurezza: tocca un solo dispositivo (-Serial, o ANDROID_SERIAL), mai "tutti"; rifiuta di partire senza un numero di serie vero
  (ANDROID_SERIAL=none vale "nessun dispositivo": solo -DryRun); il Wi-Fi viene SEMPRE riacceso, anche se lo script e' interrotto.
  Funziona sulla build debug (run-as) con gli account di prova (par. 5.7); non scrive niente sul NAS e non legge dati dell'utente.

.PARAMETER Serial              Numero di serie del telefono (`adb devices`). Default: ANDROID_SERIAL.
.PARAMETER Package             Il pacchetto dell'app (default: la build debug, dev.pampa.mycloudgallery.debug).
.PARAMETER Interruptions       Quante interruzioni (default 100, come chiede il par. 5.5 per il telefono).
.PARAMETER Seed                Il seme del calendario (default: casuale; lo script lo stampa, cosi' la prova si rifa').
.PARAMETER MinGapSeconds       Attesa minima fra due interruzioni (default 6).
.PARAMETER MaxGapSeconds       Attesa massima fra due interruzioni (default 40).
.PARAMETER WifiPercent         Su cento interruzioni, quante sono col Wi-Fi (le altre uccidono il processo; default 40).
.PARAMETER WifiOffMinSeconds   Il Wi-Fi resta spento almeno questo (default 3).
.PARAMETER WifiOffMaxSeconds   ...e al massimo questo (default 20).
.PARAMETER KillMode            Come si uccide il processo: run-as (kill -9 dalla build debug; poi `am crash` se non riesce), crash
                               (am crash), am-kill (am kill: uccide solo un processo non in primo piano), force-stop (am force-stop:
                               toglie anche i lavori in coda, quindi la ripresa parte solo dal rilancio).
.PARAMETER RelaunchDelaySeconds  Dopo una morte, quanto aspettare prima di rilanciare l'app (default 4).
.PARAMETER NoRelaunch          Non rilancia l'app dopo una morte (la ripresa e' lasciata al sistema).
.PARAMETER SettleSeconds       A fine prova: quanto aspettare che la ripresa finisca (default 180).
.PARAMETER Manifest            Il manifesto dell'area di prova (scritto da `area.ps1 -Mode seed`): serve a -Verify.
.PARAMETER Verify              A fine prova lancia il verificatore sull'area (serve -Manifest).
.PARAMETER Log                 Dove scrivere il racconto (default: build\chaos\s25-<data>.log).
.PARAMETER DryRun              Stampa il calendario e i comandi, non esegue niente.

.EXAMPLE
  # Controllo a secco, senza telefono:
  powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -DryRun -Interruptions 10 -Seed 42

.EXAMPLE
  # La prova vera sul telefono (dopo aver avviato la mossa di massa nell'app):
  powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Serial R5CX1234567 -Interruptions 100 -Manifest build\chaos\manifest-20261016-101500.txt -Verify
#>
[CmdletBinding()]
param(
  [string]$Serial = $env:ANDROID_SERIAL,
  [string]$Package = "dev.pampa.mycloudgallery.debug",
  [int]$Interruptions = 100,
  [int]$Seed = 0,
  [int]$MinGapSeconds = 6,
  [int]$MaxGapSeconds = 40,
  [int]$WifiPercent = 40,
  [int]$WifiOffMinSeconds = 3,
  [int]$WifiOffMaxSeconds = 20,
  [ValidateSet("run-as", "crash", "am-kill", "force-stop")][string]$KillMode = "run-as",
  [int]$RelaunchDelaySeconds = 4,
  [switch]$NoRelaunch,
  [int]$SettleSeconds = 180,
  [string]$Manifest,
  [switch]$Verify,
  [string]$Log,
  [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path

function Fail([string]$message, [int]$code = 1) {
  Write-Host $message -ForegroundColor Red
  exit $code
}

# --- I parametri -----------------------------------------------------------------------------------------------------------
if ($Interruptions -lt 1 -or $Interruptions -gt 1000) { Fail "-Interruptions deve stare fra 1 e 1000." }
if ($MinGapSeconds -lt 1 -or $MaxGapSeconds -lt $MinGapSeconds -or $MaxGapSeconds -gt 600) { Fail "Le attese vanno da 1 a 600 secondi, il massimo non sotto il minimo." }
if ($WifiPercent -lt 0 -or $WifiPercent -gt 100) { Fail "-WifiPercent deve stare fra 0 e 100." }
if ($WifiOffMinSeconds -lt 1 -or $WifiOffMaxSeconds -lt $WifiOffMinSeconds -or $WifiOffMaxSeconds -gt 120) { Fail "Il Wi-Fi spento dura da 1 a 120 secondi, il massimo non sotto il minimo." }
if ($Package -notmatch '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$') { Fail "Nome del pacchetto non valido." }
if ($Verify -and -not $Manifest) { Fail "-Verify vuole -Manifest (il file scritto da area.ps1 -Mode seed)." }
if ($Seed -eq 0) { $Seed = Get-Random -Minimum 1 -Maximum 2147483647 }

# --- Il calendario: dipende solo dal seme ------------------------------------------------------------------------------------
$random = New-Object System.Random($Seed)
$plan = @()
for ($i = 1; $i -le $Interruptions; $i++) {
  $gap = $random.Next($MinGapSeconds, $MaxGapSeconds + 1)
  if ($random.Next(100) -lt $WifiPercent) {
    $plan += [pscustomobject]@{ N = $i; Gap = $gap; Action = "wifi"; Duration = $random.Next($WifiOffMinSeconds, $WifiOffMaxSeconds + 1) }
  } else {
    $plan += [pscustomobject]@{ N = $i; Gap = $gap; Action = "kill"; Duration = 0 }
  }
}
$wifiCount = @($plan | Where-Object { $_.Action -eq "wifi" }).Count
$killCount = $plan.Count - $wifiCount
$totalSeconds = ($plan | Measure-Object -Property Gap -Sum).Sum + ($plan | Measure-Object -Property Duration -Sum).Sum +
  $killCount * $(if ($NoRelaunch) { 0 } else { $RelaunchDelaySeconds }) + $SettleSeconds

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
if (-not $Log) { $Log = Join-Path $root "build\chaos\s25-$stamp.log" }
$logDir = Split-Path -Parent $Log
$logLines = New-Object System.Collections.Generic.List[string]
function Write-Log([string]$text) {
  $line = "{0}  {1}" -f (Get-Date -Format "HH:mm:ss"), $text
  Write-Host $line
  $logLines.Add($line)
}

# --- I comandi che si userebbero (stampati a secco, eseguiti davvero altrimenti) --------------------------------------------------
$serialText = if ($Serial) { $Serial } else { "<numero di serie>" }
$commandPlan = [ordered]@{
  "stato del telefono"         = "adb -s $serialText get-state"
  "app installata"             = "adb -s $serialText shell pm path $Package"
  "processo dell'app"          = "adb -s $serialText shell pidof $Package"
  "uccidere (run-as)"          = "adb -s $serialText shell run-as $Package kill -9 <pid>      (se non riesce: am crash)"
  "uccidere (crash)"           = "adb -s $serialText shell am crash $Package"
  "uccidere (am-kill)"         = "adb -s $serialText shell am kill $Package"
  "uccidere (force-stop)"      = "adb -s $serialText shell am force-stop $Package"
  "Wi-Fi spento"               = "adb -s $serialText shell svc wifi disable"
  "Wi-Fi acceso"               = "adb -s $serialText shell svc wifi enable"
  "rilancio dell'app"          = "adb -s $serialText shell monkey -p $Package -c android.intent.category.LAUNCHER 1"
}

if ($DryRun) {
  Write-Host "=== PROVA A SECCO: nessun comando adb viene eseguito ===" -ForegroundColor Yellow
  Write-Host ("Telefono: {0}   Pacchetto: {1}   Seme: {2}   Uccisione: {3}{4}" -f $serialText, $Package, $Seed, $KillMode, $(if ($NoRelaunch) { " (senza rilancio)" } else { "" }))
  Write-Host ("{0} interruzioni: {1} morti del processo, {2} cadute del Wi-Fi; durata prevista circa {3} minuti" -f $plan.Count, $killCount, $wifiCount, [math]::Ceiling($totalSeconds / 60.0))
  Write-Host ""
  Write-Host ("{0,4}  {1,8}  {2}" -f "n.", "attesa", "azione")
  foreach ($event in $plan) {
    $what = if ($event.Action -eq "wifi") { "Wi-Fi spento per $($event.Duration) s, poi acceso" } else { "morte del processo ($KillMode)" + $(if ($NoRelaunch) { "" } else { ", rilancio dopo $RelaunchDelaySeconds s" }) }
    Write-Host ("{0,4}  {1,6} s  {2}" -f $event.N, $event.Gap, $what)
  }
  Write-Host ""
  Write-Host "Comandi adb che userebbe:"
  foreach ($name in $commandPlan.Keys) { Write-Host ("  {0,-24} {1}" -f $name, $commandPlan[$name]) }
  if ($Verify) { Write-Host ("A fine prova: powershell -File tools\chaos\area.ps1 -Mode verify -Manifest {0}" -f $Manifest) }
  Write-Host ""
  Write-Host "Il calendario e' ripetibile: stesso -Seed, stessa prova." -ForegroundColor Green
  exit 0
}

# --- La prova vera ---------------------------------------------------------------------------------------------------------------
if (-not $Serial -or $Serial -eq "none") {
  Fail "Serve il numero di serie di un telefono vero (-Serial o ANDROID_SERIAL): 'none' vuol dire nessun dispositivo, e questo script non sceglie da solo." 2
}
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
$adb = $null
if ($sdk -and (Test-Path -LiteralPath (Join-Path $sdk "platform-tools\adb.exe"))) { $adb = Join-Path $sdk "platform-tools\adb.exe" }
if (-not $adb) { $command = Get-Command adb -ErrorAction SilentlyContinue; if ($command) { $adb = $command.Source } }
if (-not $adb) { Fail "adb non trovato (sdk.dir in local.properties, o adb nel PATH)." }

# Uno solo dei comandi adb: sempre con -s, mai su "tutti i dispositivi".
function Invoke-Adb([string[]]$arguments) {
  $previous = $ErrorActionPreference
  $ErrorActionPreference = "Continue"
  try {
    $lines = @(& $adb -s $Serial @arguments 2>&1 | ForEach-Object { $_.ToString() })
    $code = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $previous
  }
  return [pscustomobject]@{ Code = $code; Text = (($lines -join "`n").Trim()) }
}

$state = Invoke-Adb @("get-state")
if ($state.Text -ne "device") { Fail "Il telefono $Serial non e' pronto (adb dice: '$($state.Text)')." }
$installed = Invoke-Adb @("shell", "pm", "path", $Package)
if ($installed.Text -notmatch "package:") { Fail "L'app $Package non e' installata sul telefono." }

function Start-App {
  [void](Invoke-Adb @("shell", "monkey", "-p", $Package, "-c", "android.intent.category.LAUNCHER", "1"))
}

function Stop-AppProcess {
  $pidText = (Invoke-Adb @("shell", "pidof", $Package)).Text
  if (-not $pidText) { return "il processo non era in esecuzione" }
  $pidNumber = ($pidText -split "\s+")[0]
  if ($pidNumber -notmatch '^\d+$') { return "pid non valido" }
  switch ($KillMode) {
    "run-as" {
      $run = Invoke-Adb @("shell", "run-as", $Package, "kill", "-9", $pidNumber)
      if ($run.Code -eq 0 -and $run.Text -notmatch "(?i)not debuggable|unknown package|Operation not permitted") { return "kill -9 del pid $pidNumber" }
      [void](Invoke-Adb @("shell", "am", "crash", $Package))
      return "run-as non riuscito, am crash"
    }
    "crash" { [void](Invoke-Adb @("shell", "am", "crash", $Package)); return "am crash" }
    "am-kill" { [void](Invoke-Adb @("shell", "am", "kill", $Package)); return "am kill" }
    "force-stop" { [void](Invoke-Adb @("shell", "am", "force-stop", $Package)); return "am force-stop" }
  }
}

Write-Log ("INIZIO prova: telefono {0}, app {1}, seme {2}, {3} interruzioni ({4} morti, {5} Wi-Fi), durata prevista circa {6} minuti" -f $Serial, $Package, $Seed, $plan.Count, $killCount, $wifiCount, [math]::Ceiling($totalSeconds / 60.0))
$exitCode = 0
try {
  foreach ($event in $plan) {
    Start-Sleep -Seconds $event.Gap
    if ($event.Action -eq "kill") {
      $how = Stop-AppProcess
      Write-Log ("#{0}/{1}  morte del processo: {2}" -f $event.N, $plan.Count, $how)
      if (-not $NoRelaunch) {
        Start-Sleep -Seconds $RelaunchDelaySeconds
        Start-App
        Write-Log ("#{0}/{1}  app rilanciata" -f $event.N, $plan.Count)
      }
    } else {
      [void](Invoke-Adb @("shell", "svc", "wifi", "disable"))
      Write-Log ("#{0}/{1}  Wi-Fi spento per {2} s" -f $event.N, $plan.Count, $event.Duration)
      Start-Sleep -Seconds $event.Duration
      [void](Invoke-Adb @("shell", "svc", "wifi", "enable"))
      Write-Log ("#{0}/{1}  Wi-Fi acceso" -f $event.N, $plan.Count)
    }
  }
  Write-Log "Fine delle interruzioni: l'app riparte e si aspetta che la ripresa finisca ($SettleSeconds s)."
  Start-App
  Start-Sleep -Seconds $SettleSeconds
} catch {
  Write-Log ("INTERROTTA: " + $_.Exception.Message)
  $exitCode = 1
} finally {
  # Qualunque cosa sia successa, il telefono non resta senza Wi-Fi.
  [void](Invoke-Adb @("shell", "svc", "wifi", "enable"))
  Write-Log "Wi-Fi riacceso (garanzia di uscita)."
  if (-not (Test-Path -LiteralPath $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }
  [System.IO.File]::WriteAllLines($Log, $logLines)
  Write-Host "Racconto: $Log"
}

if ($exitCode -eq 0 -and $Verify) {
  $verify = Join-Path $PSScriptRoot "area.ps1"
  Write-Log "Verifica degli invarianti sull'area di prova..."
  & $verify -Mode verify -Manifest $Manifest
  $exitCode = $LASTEXITCODE
}
exit $exitCode
