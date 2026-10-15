<#
.SYNOPSIS
  Lancia la prova delle interruzioni dal PC: il banco JVM, il runner contro l'area di prova del NAS, la semina dell'area e la verifica
  degli invarianti (docs/fase4-progetto.md par. 5.4 e 5.5, P15; come si legge il risultato: docs/prova-500.md).

.DESCRIPTION
  Modi (-Mode):
    jvm     il banco sul NAS finto in memoria (2.000 giri di norma; nessun NAS, nessuna rete). E' quello che gira sempre in check.ps1.
    run     il runner contro l'AREA DI PROVA del NAS vero, dietro la guardia: -Interruptions interruzioni (400 di norma; una prova breve
            ne usa al massimo 20). Serve il file %USERPROFILE%\.mycloudgallery\nas-it.properties e l'aggancio al NAS vero di P12: senza,
            il test risulta SALTATO (con il motivo) prima di aprire qualunque connessione.
    seed    riempie l'area con gli originali e scrive il manifesto (per la prova sul telefono, tools\chaos\s25.ps1).
    verify  verifica gli invarianti dell'area dal manifesto, anche da solo (-Manifest); -NotSettled se la prova non e' ancora ferma.

  Con -DryRun stampa cosa farebbe (variabili e comando) e non lancia niente.

  Tutto cio' che tocca il NAS vero resta sotto mcg-it/<giro>/ delle share di prova (test, test-comune, test2). Mai alessio, pampa, NAS, Public.
  La pulizia dell'area la fa NasIntegrationSuiteTest di core-nas al giro dopo (gradlew :core-nas:testDebugUnitTest -PnasIntegration).

.PARAMETER Mode           jvm | run | seed | verify
.PARAMETER Interruptions  Per run: le interruzioni da far cadere (chaos.rounds del file ha la precedenza). Default 400.
.PARAMETER Rounds         Per jvm: i giri (default 2000).
.PARAMETER FirstRound     Per jvm: il primo giro (default 0): con -Rounds 1 e lo stesso -Seed rifa' esattamente un giro che e' fallito.
.PARAMETER Seed           Il seme (default: quello del banco; per run/seed, casuale).
.PARAMETER Files          Per seed: quanti originali (default 300).
.PARAMETER Manifest       Per seed: dove scrivere il manifesto; per verify: quale leggere.
.PARAMETER Run            Per seed: il nome del giro (default: nuovo).
.PARAMETER NotSettled     Per verify: la prova e' ancora in corso, si controllano solo gli invarianti che valgono sempre.
.PARAMETER DryRun         Stampa e basta.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode jvm
  powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode run -Interruptions 20
  powershell -ExecutionPolicy Bypass -File tools\chaos\area.ps1 -Mode verify -Manifest build\chaos\manifest-20261016-101500.txt
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][ValidateSet("jvm", "run", "seed", "verify")][string]$Mode,
  [int]$Interruptions = 400,
  [int]$Rounds = 2000,
  [int]$FirstRound = 0,
  [long]$Seed = 0,
  [int]$Files = 300,
  [string]$Manifest,
  [string]$Run,
  [switch]$NotSettled,
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
if ($Run -and $Run -notmatch '^[0-9]{8}-[0-9]{6}-[0-9a-f]{8}$') { Fail "-Run deve avere la forma aaaammgg-hhmmss-<8 esadecimali>." }
if ($Mode -eq "verify" -and -not $Manifest) { Fail "verify vuole -Manifest." }
if ($Manifest -and $Mode -eq "verify" -and -not $DryRun -and -not (Test-Path -LiteralPath $Manifest)) { Fail "Manifesto non trovato: $Manifest" }

# Le variabili che il test legge (tutte facoltative tranne il modo).
$environment = [ordered]@{}
if ($Mode -ne "jvm") {
  $environment["MCG_NAS_INTEGRATION"] = "true"
  $environment["MCG_CHAOS_MODE"] = $Mode
}
if ($Mode -eq "jvm") {
  $environment["MCG_CHAOS_JVM_ROUNDS"] = "$Rounds"
  if ($Seed -ne 0) { $environment["MCG_CHAOS_SEED"] = "$Seed" }
  if ($FirstRound -ne 0) { $environment["MCG_CHAOS_FIRST_ROUND"] = "$FirstRound" }
}
if ($Mode -eq "run") { $environment["MCG_CHAOS_ROUNDS"] = "$Interruptions" }
if ($Mode -eq "seed") { $environment["MCG_CHAOS_FILES"] = "$Files" }
if (($Mode -eq "run" -or $Mode -eq "seed") -and $Seed -ne 0) { $environment["MCG_CHAOS_SEED"] = "$Seed" }
if ($Manifest) { $environment["MCG_CHAOS_MANIFEST"] = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Manifest) }
if ($Run) { $environment["MCG_CHAOS_RUN"] = $Run }
if ($Mode -eq "verify" -and $NotSettled) { $environment["MCG_CHAOS_SETTLED"] = "false" }
# Nessun dispositivo: questa prova non usa adb.
$environment["ANDROID_SERIAL"] = "none"

$test = if ($Mode -eq "jvm") { "*ChaosBenchTest*" } else { "*ChaosNasIntegrationTest*" }
$arguments = @(":core-nasops:testDebugUnitTest", "--tests", $test, "--console=plain", "--rerun", "-PmcgTestTimeoutMinutes=180")

if ($DryRun) {
  Write-Host "=== A SECCO: non lancio niente ===" -ForegroundColor Yellow
  Write-Host "Modo: $Mode"
  foreach ($name in $environment.Keys) { Write-Host ("  {0}={1}" -f $name, $environment[$name]) }
  Write-Host ("  gradlew.bat {0}" -f ($arguments -join " "))
  if ($Mode -ne "jvm") {
    Write-Host "Il test si salta, con il motivo, se manca il file delle credenziali o l'aggancio al NAS vero: nessuna connessione prima della guardia." -ForegroundColor DarkGray
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
$reportDir = Join-Path $root "core-nasops\build\chaos"
if (Test-Path -LiteralPath $reportDir) { Write-Host "Rapporti: $reportDir" }
exit $code
