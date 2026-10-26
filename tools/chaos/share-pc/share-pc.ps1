<#
.SYNOPSIS
  Il banco delle 500 interruzioni della CONDIVISIONE (Fase 7, P5; docs/fase7-progetto.md par. 5.5 e 5.6 F80; come si legge: docs/prova-500.md Parte E):
  i banchi sulla JVM, le mutazioni, i difetti noti e la prova dal PC contro il NAS vero di prova (come F16-PC, tools\chaos\area.ps1 -Mode run).

.DESCRIPTION
  Modi (-Mode):
    jvm       il banco sul NAS finto in memoria (2.000 giri di norma; nessun NAS, nessuna rete). Gira anche in check.ps1.
    smb       il banco sul simulatore SMB con la sessione di produzione e le primitive vere (600 giri di norma; nessuna rete). Gira anche in check.ps1.
    mutants   i quattro errori introdotti apposta (sorgente nel cestino prima della verifica, voce pubblicata prima del completamento, completamento dopo il
              cestino, conferma riusata): ognuno deve far diventare rosso il banco con l'invariante giusto.
    known     le riproduzioni dei difetti noti del codice di produzione trovati dal banco (SD-1, SD-2; rosse quando si correggono) e il banco che li trova.
    area      lo stesso codice del NAS vero (guardia, trasporto recintato, oracolo, pulizia, autotest) sopra il simulatore SMB: nessuna rete.
    run       la prova contro il NAS VERO di prova, dal PC. Serve il file %USERPROFILE%\.mycloudgallery\nas-it.properties (lo legge solo il codice del
              banco, in memoria: questo script non lo apre ne' lo stampa) e -Interruptions interruzioni (500 di norma). Lavora SOLO sotto mcg-it/<giro>/
              delle share `test` e `test-comune`, con l'account `test`, e rifiuta qualunque altra share (anche `test2`). Un errore di autenticazione
              ferma tutto subito, senza nuovi tentativi (il NAS blocca l'account). A fine prova toglie la cartella del giro e dice cosa e' rimasto.
    clean     toglie la cartella di un giro rimasto dopo una prova interrotta (-Run <giro>): solo mcg-it/<giro>/ delle due share di prova.
    verify    verifica dal PC, in SOLA LETTURA, l'area di una condivisione fatta dal telefono (tools\chaos\s25.ps1 -Mode share, F80): legge solo le cartelle
              del manifesto (-Manifest) e .mycloudgallery/{tmp,trash} di test e test-comune, e dice se c'e' qualche file a meta', un doppione, una
              perdita o un temporaneo rimasto. -NotSettled se la prova non e' ancora ferma. Non scrive niente: il lettore non ha nessuna primitiva di scrittura.

  Con -DryRun stampa cosa farebbe (variabili e comando) e non lancia niente. Il PC e' condiviso con altre build: prima di ogni comando Gradle lo script aspetta
  2 GB di RAM libera; chi lavora con piu' agenti prende prima un posto con gradle-slot.ps1.

.PARAMETER Mode           jvm | smb | mutants | known | area | run | clean
.PARAMETER Interruptions  Per run: le interruzioni da far cadere (default 500; da 1 a 5.000).
.PARAMETER Rounds         Per jvm e smb: i giri (default 2000 per jvm, 600 per smb).
.PARAMETER FirstRound     Per jvm e smb: il primo giro; con -Rounds 1 e lo stesso -Seed rifa' esattamente un giro che e' fallito. Per run: il primo giro del banco
                          (con lo stesso -Seed di base e tante -Interruptions quante ne aveva il giro, lo rifa' sul NAS vero).
.PARAMETER Seed           Il seme di base (default: quello del banco; per run, casuale e stampato nel rapporto).
.PARAMETER Run            Per run: il nome del giro (default: nuovo). Per clean: il giro da pulire (obbligatorio).
.PARAMETER Manifest       Per verify: il manifesto della condivisione (scritto da s25.ps1 -Mode share -Step seed, piu' le righe freed= del passo verify).
.PARAMETER NotSettled     Per verify: la prova non e' ancora ferma (temporanei propri e due copie visibili ammessi).
.PARAMETER TimeoutMinutes Il tetto del task dei test (default 180, da 20 a 600).
.PARAMETER DryRun         Stampa e basta.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\chaos\share-pc\share-pc.ps1 -Mode jvm
  powershell -ExecutionPolicy Bypass -File tools\chaos\share-pc\share-pc.ps1 -Mode jvm -Rounds 1 -FirstRound 163 -Seed 20261026
  powershell -ExecutionPolicy Bypass -File tools\chaos\share-pc\share-pc.ps1 -Mode run -Interruptions 20 -DryRun
  powershell -ExecutionPolicy Bypass -File tools\chaos\share-pc\share-pc.ps1 -Mode run -Interruptions 500
  powershell -ExecutionPolicy Bypass -File tools\chaos\share-pc\share-pc.ps1 -Mode verify -Manifest build\chaos\share-verify-20261026-101500-1a2b3c4d.txt
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][ValidateSet("jvm", "smb", "mutants", "known", "area", "run", "clean", "verify")][string]$Mode,
  [int]$Interruptions = 500,
  [int]$Rounds = 0,
  [int]$FirstRound = 0,
  [long]$Seed = 0,
  [string]$Run,
  [string]$Manifest,
  [switch]$NotSettled,
  [int]$TimeoutMinutes = 180,
  [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path

function Fail([string]$message, [int]$code = 1) {
  Write-Host $message -ForegroundColor Red
  exit $code
}

if ($Interruptions -lt 1 -or $Interruptions -gt 5000) { Fail "-Interruptions fuori misura (1-5000)." }
if ($Rounds -lt 0 -or $Rounds -gt 1000000) { Fail "-Rounds fuori misura." }
if ($FirstRound -lt 0) { Fail "-FirstRound fuori misura." }
if ($TimeoutMinutes -lt 20 -or $TimeoutMinutes -gt 600) { Fail "-TimeoutMinutes fuori misura (20-600)." }
if ($Run -and $Run -notmatch '^[0-9]{8}-[0-9]{6}-[0-9a-f]{8}$') { Fail "-Run deve avere la forma aaaammgg-hhmmss-<8 esadecimali>." }
if ($Mode -eq "clean" -and -not $Run) { Fail "clean vuole -Run <giro> (il nome della cartella sotto mcg-it/)." }
if ($Mode -eq "verify" -and -not $Manifest) { Fail "verify vuole -Manifest <il manifesto della condivisione>." }
if ($Manifest -and $Mode -eq "verify" -and -not $DryRun -and -not (Test-Path -LiteralPath $Manifest)) { Fail "File non trovato: $Manifest" }

$touchesNas = ($Mode -eq "run") -or ($Mode -eq "clean") -or ($Mode -eq "verify")
$environment = [ordered]@{}
$test = $null
switch ($Mode) {
  "jvm" {
    $test = "*ShareChaosBenchTest*"
    $environment["MCG_SHARE_CHAOS_ROUNDS"] = $(if ($Rounds -gt 0) { "$Rounds" } else { "2000" })
    if ($Seed -ne 0) { $environment["MCG_SHARE_CHAOS_SEED"] = "$Seed" }
    if ($FirstRound -ne 0) { $environment["MCG_SHARE_CHAOS_FIRST_ROUND"] = "$FirstRound" }
  }
  "smb" {
    $test = "*ShareChaosSmbBenchTest*"
    $environment["MCG_SHARE_CHAOS_SMB_ROUNDS"] = $(if ($Rounds -gt 0) { "$Rounds" } else { "600" })
    if ($Seed -ne 0) { $environment["MCG_SHARE_CHAOS_SEED"] = "$Seed" }
    if ($FirstRound -ne 0) { $environment["MCG_SHARE_CHAOS_FIRST_ROUND"] = "$FirstRound" }
  }
  "mutants" { $test = "*ShareChaosMutationTest*" }
  "known" { $test = "*ShareChaosKnownDefectsTest*" }
  "area" { $test = "*ShareAreaRunnerTest*" }
  "run" {
    $test = "*ShareNasIntegrationTest*"
    $environment["MCG_NAS_INTEGRATION"] = "true"
    $environment["MCG_SHARE_MODE"] = "run"
    $environment["MCG_SHARE_INTERRUPTIONS"] = "$Interruptions"
    if ($Seed -ne 0) { $environment["MCG_SHARE_SEED"] = "$Seed" }
    if ($FirstRound -ne 0) { $environment["MCG_SHARE_FIRST_ROUND"] = "$FirstRound" }
    if ($Run) { $environment["MCG_SHARE_RUN"] = $Run }
  }
  "clean" {
    $test = "*ShareNasIntegrationTest*"
    $environment["MCG_NAS_INTEGRATION"] = "true"
    $environment["MCG_SHARE_MODE"] = "clean"
    $environment["MCG_SHARE_RUN"] = $Run
  }
  "verify" {
    $test = "*ShareNasIntegrationTest*"
    $environment["MCG_NAS_INTEGRATION"] = "true"
    $environment["MCG_SHARE_MODE"] = "verify"
    $environment["MCG_SHARE_MANIFEST"] = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Manifest)
    if ($NotSettled) { $environment["MCG_SHARE_SETTLED"] = "false" }
  }
}
# Nessun dispositivo: questa prova non usa adb.
$environment["ANDROID_SERIAL"] = "none"
$arguments = @(":core-albums:testDebugUnitTest", "--tests", $test, "--max-workers=2", "--console=plain", "--rerun", "-PmcgTestTimeoutMinutes=$TimeoutMinutes")

if ($DryRun) {
  Write-Host "=== A SECCO: non lancio niente ===" -ForegroundColor Yellow
  Write-Host ("Modo: {0}" -f $Mode)
  foreach ($name in $environment.Keys) { Write-Host ("  {0}={1}" -f $name, $environment[$name]) }
  Write-Host ("  (prima: aspetto almeno 2 GB di RAM libera) gradlew.bat {0}" -f ($arguments -join " "))
  if ($touchesNas) {
    # Solo l'esistenza del file, mai il contenuto.
    $credentials = Join-Path $env:USERPROFILE ".mycloudgallery\nas-it.properties"
    Write-Host ("Il file delle credenziali: {0}. Il test si salta, con il motivo, se manca l'interruttore o il file: nessuna connessione prima della guardia." -f $(if (Test-Path -LiteralPath $credentials) { "presente (non lo leggo)" } else { "assente" })) -ForegroundColor DarkGray
    if ($Mode -eq "verify") {
      Write-Host "Sola lettura: solo le cartelle del manifesto e .mycloudgallery/{tmp,trash} delle share di prova test e test-comune; nessuna scrittura per costruzione." -ForegroundColor DarkGray
    } else {
      Write-Host "Tocca solo mcg-it/<giro>/ delle share di prova test e test-comune; un login rifiutato ferma tutto senza nuovi tentativi." -ForegroundColor DarkGray
    }
  }
  exit 0
}

# Il PC e' condiviso con altre build: prima di ogni comando Gradle almeno 2 GB di RAM libera (FreePhysicalMemory e' in KB).
while ((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory -lt 2097152) {
  Write-Host "RAM libera sotto i 2 GB: aspetto 20 secondi." -ForegroundColor DarkGray
  Start-Sleep -Seconds 20
}
foreach ($name in $environment.Keys) { Set-Item -Path "Env:$name" -Value $environment[$name] }
Push-Location $root
try {
  & (Join-Path $root "gradlew.bat") @arguments
  $code = $LASTEXITCODE
} finally {
  Pop-Location
}
$reportDir = Join-Path $root "core-albums\build\chaos"
if (Test-Path -LiteralPath $reportDir) { Write-Host "Rapporti: $reportDir" }
exit $code
