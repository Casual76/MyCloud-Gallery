<#
.SYNOPSIS
  Il banco delle interruzioni del CALCOLO DEI VOLTI e di "DISATTIVA E CANCELLA" (Fase 8, P12; docs/fase8-progetto.md par. 5.5 e 5.6 F103; come si legge:
  docs/prova-500.md Parte F): i banchi sulla JVM, le mutazioni e la prova dal PC contro il NAS vero di prova (come F16-PC e share-pc.ps1).

.DESCRIPTION
  Modi (-Mode):
    jvm       il banco sul NAS finto in memoria (2.000 giri di norma; nessun NAS, nessuna rete). Gira anche in check.ps1.
    smb       il banco sul simulatore SMB con la sessione di produzione, le primitive vere e il vero NasFacesEraser (600 giri di norma; nessuna rete).
              Gira anche in check.ps1.
    mutants   i quattro errori introdotti apposta (pubblicare senza lo stato fresco, dichiarare la cancellazione prima di farla, togliere i file prima della
              chiave, cancellare fuori da faces/): ognuno deve far diventare rosso il banco con l'invariante giusto.
    area      lo stesso codice del NAS vero (guardia, trasporto recintato, oracolo, pulizia, autotest, verificatore) sopra il simulatore SMB: nessuna rete.
    run       la prova contro il NAS VERO di prova, dal PC. Serve il file %USERPROFILE%\.mycloudgallery\nas-it.properties (lo legge solo il codice del
              banco, in memoria: questo script non lo apre ne' lo stampa) e -Interruptions interruzioni (400 di norma). Lavora SOLO sotto mcg-it/<giro>/
              della share `test`, con l'account `test`, e rifiuta qualunque altra share (anche `test2`, `NAS`, `Public`); `test-comune` si apre solo per
              provare che li' non si scrive niente. Un errore di autenticazione ferma tutto subito, senza nuovi tentativi (il NAS blocca l'account).
              A fine prova toglie la cartella del giro e dice cosa e' rimasto.
    clean     toglie la cartella di un giro rimasto dopo una prova interrotta (-Run <giro>): solo mcg-it/<giro>/ della share `test`.
    verify    verifica dal PC, in SOLA LETTURA, i dati dei volti scritti dal telefono (tools\chaos\s25.ps1 -Mode faces, F103): legge solo la cartella del
              backup del manifesto (-Manifest) e .mycloudgallery/{faces,tmp} di test e test-comune. -Phase computed|erased dice cosa ci si aspetta
              ("erased": nessun file dei volti in nessuna share). Esce con 4 se qualcosa non torna. Non scrive niente: il lettore non ha nessuna primitiva di scrittura.

  Con -DryRun stampa cosa farebbe (variabili e comando) e non lancia niente. Il PC e' condiviso con altre build: prima di ogni comando Gradle lo script aspetta
  2 GB di RAM libera; chi lavora con piu' agenti passa -Slot <nome> e lo script prende (e rilascia) un posto con ..\gradle-slot.ps1.

.PARAMETER Mode           jvm | smb | mutants | area | run | clean | verify
.PARAMETER Interruptions  Per run: le interruzioni da far cadere (default 400; da 1 a 5.000).
.PARAMETER Rounds         Per jvm e smb: i giri (default 2000 per jvm, 600 per smb).
.PARAMETER FirstRound     Per jvm e smb: il primo giro; con -Rounds 1 e lo stesso -Seed rifa' esattamente un giro che e' fallito. Per run: il primo giro del banco.
.PARAMETER Seed           Il seme di base (default: quello del banco; per run, casuale e stampato nel rapporto).
.PARAMETER Run            Per run: il nome del giro (default: nuovo). Per clean: il giro da pulire (obbligatorio).
.PARAMETER Manifest       Per verify: il manifesto dei volti (scritto da s25.ps1 -Mode faces -Step seed).
.PARAMETER Phase          Per verify: computed (default) | erased.
.PARAMETER NotSettled     Per verify: la prova non e' ancora ferma (accettato per simmetria con gli altri modi: i controlli del verificatore valgono comunque).
.PARAMETER TimeoutMinutes Il tetto del task dei test (default 180, da 20 a 600).
.PARAMETER Slot           Il nome con cui prendere un posto del semaforo condiviso delle build (..\gradle-slot.ps1) prima di Gradle e rilasciarlo dopo.
.PARAMETER DryRun         Stampa e basta.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\chaos\faces-pc\faces-pc.ps1 -Mode jvm
  powershell -ExecutionPolicy Bypass -File tools\chaos\faces-pc\faces-pc.ps1 -Mode jvm -Rounds 1 -FirstRound 163 -Seed 20261029
  powershell -ExecutionPolicy Bypass -File tools\chaos\faces-pc\faces-pc.ps1 -Mode run -Interruptions 20 -DryRun
  powershell -ExecutionPolicy Bypass -File tools\chaos\faces-pc\faces-pc.ps1 -Mode run -Interruptions 400
  powershell -ExecutionPolicy Bypass -File tools\chaos\faces-pc\faces-pc.ps1 -Mode verify -Manifest build\chaos\faces-manifest-20261030-101500-1a2b3c4d.txt -Phase erased
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][ValidateSet("jvm", "smb", "mutants", "area", "run", "clean", "verify")][string]$Mode,
  [int]$Interruptions = 400,
  [int]$Rounds = 0,
  [int]$FirstRound = 0,
  [long]$Seed = 0,
  [string]$Run,
  [string]$Manifest,
  [ValidateSet("computed", "erased")][string]$Phase = "computed",
  [switch]$NotSettled,
  [int]$TimeoutMinutes = 180,
  [string]$Slot,
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
if ($Slot -and $Slot -notmatch '^[A-Za-z0-9._-]{1,32}$') { Fail "-Slot: nome non valido." }
if ($Mode -eq "clean" -and -not $Run) { Fail "clean vuole -Run <giro> (il nome della cartella sotto mcg-it/)." }
if ($Mode -eq "verify" -and -not $Manifest) { Fail "verify vuole -Manifest <il manifesto dei volti>." }
if ($Manifest -and $Mode -eq "verify" -and -not $DryRun -and -not (Test-Path -LiteralPath $Manifest)) { Fail "File non trovato: $Manifest" }

$touchesNas = ($Mode -eq "run") -or ($Mode -eq "clean") -or ($Mode -eq "verify")
$environment = [ordered]@{}
$test = $null
switch ($Mode) {
  "jvm" {
    $test = "*FacesChaosBenchTest*"
    $environment["MCG_FACES_CHAOS_ROUNDS"] = $(if ($Rounds -gt 0) { "$Rounds" } else { "2000" })
    if ($Seed -ne 0) { $environment["MCG_FACES_CHAOS_SEED"] = "$Seed" }
    if ($FirstRound -ne 0) { $environment["MCG_FACES_CHAOS_FIRST_ROUND"] = "$FirstRound" }
  }
  "smb" {
    $test = "*FacesChaosSmbBenchTest*"
    $environment["MCG_FACES_CHAOS_SMB_ROUNDS"] = $(if ($Rounds -gt 0) { "$Rounds" } else { "600" })
    if ($Seed -ne 0) { $environment["MCG_FACES_CHAOS_SEED"] = "$Seed" }
    if ($FirstRound -ne 0) { $environment["MCG_FACES_CHAOS_FIRST_ROUND"] = "$FirstRound" }
  }
  "mutants" { $test = "*FacesChaosMutationTest*" }
  "area" { $test = "*FacesAreaRunnerTest*" }
  "run" {
    $test = "*FacesNasIntegrationTest*"
    $environment["MCG_NAS_INTEGRATION"] = "true"
    $environment["MCG_FACES_MODE"] = "run"
    $environment["MCG_FACES_INTERRUPTIONS"] = "$Interruptions"
    if ($Seed -ne 0) { $environment["MCG_FACES_SEED"] = "$Seed" }
    if ($FirstRound -ne 0) { $environment["MCG_FACES_FIRST_ROUND"] = "$FirstRound" }
    if ($Run) { $environment["MCG_FACES_RUN"] = $Run }
  }
  "clean" {
    $test = "*FacesNasIntegrationTest*"
    $environment["MCG_NAS_INTEGRATION"] = "true"
    $environment["MCG_FACES_MODE"] = "clean"
    $environment["MCG_FACES_RUN"] = $Run
  }
  "verify" {
    $test = "*FacesNasIntegrationTest*"
    $environment["MCG_NAS_INTEGRATION"] = "true"
    $environment["MCG_FACES_MODE"] = "verify"
    $environment["MCG_FACES_MANIFEST"] = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Manifest)
    $environment["MCG_FACES_PHASE"] = $Phase
    if ($NotSettled) { $environment["MCG_FACES_SETTLED"] = "false" }
  }
}
# Nessun dispositivo: questa prova non usa adb.
$environment["ANDROID_SERIAL"] = "none"
foreach ($extra in @("MCG_FACES_CHAOS_TRACE", "MCG_FACES_CHAOS_NO_AVOID", "MCG_FACES_CHAOS_DB_ERRORS")) { if (Get-Item -Path "Env:$extra" -ErrorAction SilentlyContinue) { $environment[$extra] = (Get-Item -Path "Env:$extra").Value } }
$arguments = @(":core-faces:testDebugUnitTest", "--tests", $test, "--max-workers=2", "--console=plain", "--rerun", "-PmcgTestTimeoutMinutes=$TimeoutMinutes")

if ($DryRun) {
  Write-Host "=== A SECCO: non lancio niente ===" -ForegroundColor Yellow
  Write-Host ("Modo: {0}" -f $Mode)
  foreach ($name in $environment.Keys) { Write-Host ("  {0}={1}" -f $name, $environment[$name]) }
  Write-Host ("  (prima: aspetto almeno 2 GB di RAM libera{0}) gradlew.bat {1}" -f $(if ($Slot) { " e un posto del semaforo ($Slot)" } else { "" }), ($arguments -join " "))
  if ($touchesNas) {
    # Solo l'esistenza del file, mai il contenuto.
    $credentials = Join-Path $env:USERPROFILE ".mycloudgallery\nas-it.properties"
    Write-Host ("Il file delle credenziali: {0}. Il test si salta, con il motivo, se manca l'interruttore o il file: nessuna connessione prima della guardia." -f $(if (Test-Path -LiteralPath $credentials) { "presente (non lo leggo)" } else { "assente" })) -ForegroundColor DarkGray
    if ($Mode -eq "verify") {
      Write-Host "Sola lettura: solo la cartella del backup del manifesto e .mycloudgallery/{faces,tmp} delle share di prova test e test-comune; nessuna scrittura per costruzione." -ForegroundColor DarkGray
    } else {
      Write-Host "Tocca solo mcg-it/<giro>/ della share di prova test (test-comune solo per provare che li' non si scrive); un login rifiutato ferma tutto senza nuovi tentativi." -ForegroundColor DarkGray
    }
  }
  exit 0
}

if ($Slot) {
  & powershell -NoProfile -File (Join-Path $root "..\gradle-slot.ps1") -Acquire -Name $Slot | Out-Host
}
$code = 1
try {
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
} finally {
  if ($Slot) {
    & powershell -NoProfile -File (Join-Path $root "..\gradle-slot.ps1") -Release -Name $Slot | Out-Host
  }
}
$reportDir = Join-Path $root "core-faces\build\chaos"
if (Test-Path -LiteralPath $reportDir) { Write-Host "Rapporti: $reportDir" }
if ($Mode -eq "verify" -and $code -ne 0) { exit 4 }
exit $code
