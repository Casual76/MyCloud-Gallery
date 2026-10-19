<#
.SYNOPSIS
  Lancia la prova delle interruzioni dal PC: il banco JVM, il runner contro l'area di prova del NAS, la semina dell'area e la verifica
  degli invarianti (docs/fase4-progetto.md par. 5.4 e 5.5, P15 per le operazioni sui file; docs/fase5-progetto.md par. 5.4, P6 per il backup;
  come si legge il risultato: docs/prova-500.md).

.DESCRIPTION
  Modi (-Mode):
    jvm     il banco sul NAS finto in memoria (2.000 giri di norma; nessun NAS, nessuna rete). E' quello che gira sempre in check.ps1.
    run     il runner contro l'AREA DI PROVA del NAS vero, dietro la guardia: -Interruptions interruzioni (400 di norma; una prova breve
            ne usa al massimo 20). Serve il file %USERPROFILE%\.mycloudgallery\nas-it.properties e l'aggancio al NAS vero di P12: senza,
            il test risulta SALTATO (con il motivo) prima di aprire qualunque connessione.
    seed    riempie l'area con gli originali e scrive il manifesto (per la prova sul telefono, tools\chaos\s25.ps1).
    verify  verifica gli invarianti dell'area dal manifesto, anche da solo (-Manifest); -NotSettled se la prova non e' ancora ferma.
    backup  la prova delle interruzioni del BACKUP (Fase 5A, P6) e delle VERSIONI (Fase 5B, P13), a livelli (-Stage):
              jvm     il banco sul NAS finto, due volte: sull'esecutore di riferimento e sul motore vero (2.000 giri di norma, nessuna rete);
                      dalla 5B ogni giro ha anche la fase delle versioni, degli scambi ("Ripristina originale"), del guardiano e di
                      "Libera spazio" con la verifica sul momento (invarianti 12-14 del par. 5.4);
              smb     il banco sul simulatore SMB con le primitive vere del backup e delle versioni (600 giri di norma, nessuna rete);
              run     il runner contro l'AREA DI PROVA del NAS vero (>= 400 interruzioni di norma), dietro la guardia, con il motore vero;
                      un errore di autenticazione lo ferma subito, senza nuovi tentativi. Solo mcg-it/<giro>/ delle share di prova;
              run5b   come run, ma SOLO la fase 5B (versioni, scambi, guardiano, "Libera spazio"): i media li semina il banco sotto
                      mcg-it/<giro>/ (il caricamento della 5A non gira). Con -WithUpload il giro fa prima anche il caricamento;
              verify  verifica l'area di prova dal manifesto del telefono (tools\chaos\s25.ps1 -Mode backup), in sola lettura;
              state   verifica DA SOLO uno stato salvato da un giro rosso (core-backup\build\chaos\backup-state-<giro>.txt) con -Manifest <file>.

  Con -DryRun stampa cosa farebbe (variabili e comando) e non lancia niente.

  Tutto cio' che tocca il NAS vero resta sotto mcg-it/<giro>/ delle share di prova (test, test-comune, test2). Mai alessio, pampa, NAS, Public.
  La pulizia dell'area la fa NasIntegrationSuiteTest di core-nas al giro dopo (gradlew :core-nas:testDebugUnitTest -PnasIntegration).

.PARAMETER Mode           jvm | run | seed | verify | backup
.PARAMETER Stage          Per backup: jvm | smb | run | run5b | verify | state (default jvm).
.PARAMETER Interruptions  Per run (e backup/run, backup/run5b): le interruzioni da far cadere. Default 400. Per il backup e' anche il TETTO: il valore di
                          backup.rounds del file delle credenziali non lo supera mai (una prova da 20 non diventa per sbaglio quella da 400).
.PARAMETER Rounds         Per jvm (e backup/jvm, backup/smb): i giri (default 2000; 600 per backup/smb).
.PARAMETER FirstRound     Per jvm (e backup/jvm, backup/smb): il primo giro (default 0): con -Rounds 1 e lo stesso -Seed rifa' esattamente un giro che e' fallito.
                          Per run (e backup/run): con lo stesso -Seed (seme di base del rapporto) e -Interruptions pari a quelle del giro, rifa' quel
                          giro sul NAS vero.
.PARAMETER Seed           Il seme (default: quello del banco; per run/seed, casuale).
.PARAMETER Files          Per seed: quanti originali (default 300).
.PARAMETER Manifest       Per seed: dove scrivere il manifesto; per verify (e backup/verify): quale leggere; per backup/state: lo stato salvato.
.PARAMETER Run            Per seed (e backup/run): il nome del giro (default: nuovo).
.PARAMETER NotSettled     Per verify (e backup/verify): la prova non e' ancora ferma, si controllano solo gli invarianti che valgono sempre.
.PARAMETER Stack          Per backup/run: reference per provare il NAS vero con l'esecutore di riferimento invece del motore vero.
.PARAMETER WithUpload     Per backup/run5b: il giro fa prima il caricamento della 5A e poi la fase 5B (altrimenti solo la 5B).
.PARAMETER TimeoutMinutes Il tetto del task dei test (default 180, da 20 a 600): il giro da 400 sul NAS vero dura ore con i file grandi, di
                          piu' con il PC carico (ogni morte del processo riapre la connessione, come sul telefono).
.PARAMETER DryRun         Stampa e basta.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode jvm
  powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode run -Interruptions 20
  powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode verify -Manifest build\chaos\manifest-20261016-101500.txt
  powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode backup -Stage jvm
  powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode backup -Stage run -Interruptions 20 -DryRun
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][ValidateSet("jvm", "run", "seed", "verify", "backup")][string]$Mode,
  [ValidateSet("jvm", "smb", "run", "run5b", "verify", "state")][string]$Stage = "jvm",
  [int]$Interruptions = 400,
  [int]$Rounds = 2000,
  [int]$FirstRound = 0,
  [long]$Seed = 0,
  [int]$Files = 300,
  [string]$Manifest,
  [string]$Run,
  [switch]$NotSettled,
  [ValidateSet("production", "reference")][string]$Stack = "production",
  [switch]$WithUpload,
  [int]$TimeoutMinutes = 180,
  [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path

function Fail([string]$message, [int]$code = 1) {
  Write-Host $message -ForegroundColor Red
  exit $code
}

if ($Interruptions -lt 0 -or $Interruptions -gt 100000) { Fail "-Interruptions fuori misura." }
if ($Rounds -lt 1 -or $Rounds -gt 1000000) { Fail "-Rounds fuori misura." }
if ($Files -lt 1 -or $Files -gt 100000) { Fail "-Files fuori misura." }
if ($TimeoutMinutes -lt 20 -or $TimeoutMinutes -gt 600) { Fail "-TimeoutMinutes fuori misura (20-600)." }
if ($Run -and $Run -notmatch '^[0-9]{8}-[0-9]{6}-[0-9a-f]{8}$') { Fail "-Run deve avere la forma aaaammgg-hhmmss-<8 esadecimali>." }
$needsManifest = ($Mode -eq "verify") -or ($Mode -eq "backup" -and ($Stage -eq "verify" -or $Stage -eq "state"))
if ($needsManifest -and -not $Manifest) { Fail "Questo modo vuole -Manifest." }
if ($Manifest -and $needsManifest -and -not $DryRun -and -not (Test-Path -LiteralPath $Manifest)) { Fail "File non trovato: $Manifest" }

# Il livello del backup: quale test, quale modulo, quali variabili.
$isBackup = ($Mode -eq "backup")
$module = if ($isBackup) { ":core-backup" } else { ":core-nasops" }
$reportModule = if ($isBackup) { "core-backup" } else { "core-nasops" }
$backupRounds = $Rounds
if ($isBackup -and $Stage -eq "smb" -and -not $PSBoundParameters.ContainsKey("Rounds")) { $backupRounds = 600 }
$touchesNas = ($Mode -eq "run") -or ($Mode -eq "seed") -or ($Mode -eq "verify") -or ($isBackup -and ($Stage -eq "run" -or $Stage -eq "run5b" -or $Stage -eq "verify"))

# Le variabili che il test legge (tutte facoltative tranne il modo).
$environment = [ordered]@{}
if ($isBackup) {
  switch ($Stage) {
    "jvm" {
      $environment["MCG_BACKUP_CHAOS_ROUNDS"] = "$backupRounds"
      if ($Seed -ne 0) { $environment["MCG_BACKUP_CHAOS_SEED"] = "$Seed" }
      if ($FirstRound -ne 0) { $environment["MCG_BACKUP_CHAOS_FIRST_ROUND"] = "$FirstRound" }
    }
    "smb" {
      $environment["MCG_BACKUP_CHAOS_SMB_ROUNDS"] = "$backupRounds"
      if ($Seed -ne 0) { $environment["MCG_BACKUP_CHAOS_SEED"] = "$Seed" }
      if ($FirstRound -ne 0) { $environment["MCG_BACKUP_CHAOS_FIRST_ROUND"] = "$FirstRound" }
    }
    { $_ -eq "run" -or $_ -eq "run5b" } {
      $environment["MCG_NAS_INTEGRATION"] = "true"
      $environment["MCG_BACKUP_MODE"] = "run"
      $environment["MCG_BACKUP_ROUNDS"] = "$Interruptions"
      # Il tetto: il file delle credenziali puo' avere backup.rounds=400, ma una prova da -Interruptions 20 non supera mai le 20.
      $environment["MCG_BACKUP_CAP"] = "$Interruptions"
      if ($Stage -eq "run5b") { $environment["MCG_BACKUP_FIVEB"] = $(if ($WithUpload) { "with" } else { "only" }) }
      if ($Seed -ne 0) { $environment["MCG_BACKUP_SEED"] = "$Seed" }
      if ($FirstRound -ne 0) { $environment["MCG_BACKUP_FIRST_ROUND"] = "$FirstRound" }
      if ($Run) { $environment["MCG_BACKUP_RUN"] = $Run }
      if ($Stack -eq "reference") { $environment["MCG_BACKUP_STACK"] = "reference" }
    }
    "verify" {
      $environment["MCG_NAS_INTEGRATION"] = "true"
      $environment["MCG_BACKUP_MODE"] = "verify"
      $environment["MCG_BACKUP_MANIFEST"] = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Manifest)
      if ($NotSettled) { $environment["MCG_BACKUP_SETTLED"] = "false" }
    }
    "state" {
      $environment["MCG_BACKUP_STATE"] = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Manifest)
      if ($NotSettled) { $environment["MCG_BACKUP_SETTLED"] = "false" }
    }
  }
} else {
  if ($Mode -ne "jvm") {
    $environment["MCG_NAS_INTEGRATION"] = "true"
    $environment["MCG_CHAOS_MODE"] = $Mode
  }
  if ($Mode -eq "jvm") {
    $environment["MCG_CHAOS_JVM_ROUNDS"] = "$Rounds"
    if ($Seed -ne 0) { $environment["MCG_CHAOS_SEED"] = "$Seed" }
    if ($FirstRound -ne 0) { $environment["MCG_CHAOS_FIRST_ROUND"] = "$FirstRound" }
  }
  if ($Mode -eq "run") {
    $environment["MCG_CHAOS_ROUNDS"] = "$Interruptions"
    # Rifare un giro rosso sul NAS vero: stesso -Seed (il seme di base del rapporto), -FirstRound N e tante -Interruptions quante ne aveva.
    if ($FirstRound -ne 0) { $environment["MCG_CHAOS_FIRST_ROUND"] = "$FirstRound" }
  }
  if ($Mode -eq "seed") { $environment["MCG_CHAOS_FILES"] = "$Files" }
  if (($Mode -eq "run" -or $Mode -eq "seed") -and $Seed -ne 0) { $environment["MCG_CHAOS_SEED"] = "$Seed" }
  if ($Manifest) { $environment["MCG_CHAOS_MANIFEST"] = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Manifest) }
  if ($Run) { $environment["MCG_CHAOS_RUN"] = $Run }
  if ($Mode -eq "verify" -and $NotSettled) { $environment["MCG_CHAOS_SETTLED"] = "false" }
}
# Nessun dispositivo: questa prova non usa adb.
$environment["ANDROID_SERIAL"] = "none"

if ($isBackup) {
  $test = switch ($Stage) {
    "jvm" { "*BackupChaosBenchTest*" }
    "smb" { "*BackupChaosSmbBenchTest*" }
    "state" { "*BackupStateVerifyTest*" }
    default { "*BackupNasIntegrationTest*" }
  }
} else {
  $test = if ($Mode -eq "jvm") { "*ChaosBenchTest*" } else { "*ChaosNasIntegrationTest*" }
}
$arguments = @("${module}:testDebugUnitTest", "--tests", $test, "--max-workers=4", "--console=plain", "--rerun", "-PmcgTestTimeoutMinutes=$TimeoutMinutes")

if ($DryRun) {
  Write-Host "=== A SECCO: non lancio niente ===" -ForegroundColor Yellow
  Write-Host ("Modo: {0}{1}" -f $Mode, $(if ($isBackup) { " / livello $Stage" } else { "" }))
  foreach ($name in $environment.Keys) { Write-Host ("  {0}={1}" -f $name, $environment[$name]) }
  Write-Host ("  gradlew.bat {0}" -f ($arguments -join " "))
  if ($touchesNas) {
    Write-Host "Il test si salta, con il motivo, se manca il file delle credenziali: nessuna connessione prima della guardia." -ForegroundColor DarkGray
  }
  exit 0
}

foreach ($name in $environment.Keys) { Set-Item -Path "Env:$name" -Value $environment[$name] }
Push-Location $root
try {
  & (Join-Path $root "gradlew.bat") @arguments
  $code = $LASTEXITCODE
} finally {
  Pop-Location
}
$reportDir = Join-Path $root "$reportModule\build\chaos"
if (Test-Path -LiteralPath $reportDir) { Write-Host "Rapporti: $reportDir" }
exit $code
