<#
.SYNOPSIS
  Le prove pratiche della revisione finale di sicurezza (Fase 10 P11, docs/fase10-progetto.md par. 2.11, D36): S1-S9 ai livelli
  jvm e pc, con un resoconto di soli numeri in build\security\<data>\.

.DESCRIPTION
  Livelli (-Livello):
    jvm        i test JVM delle voci (S1 (a) 10.000 mondi, S2, S3 (a) 100.000 mutazioni per codec, S3 (c) sul lettore dei metadati,
               S7a, S7b, S8 (a), S9 sul NAS finto) con le quantita' PIENE (MCG_SECURITY_FULL=1), piu' gli strumenti del livello pc
               provati contro il NAS finto (guardia, pulizia, prova fra account che non e' cieca, AUTH_FAILED). Nessun NAS, nessuna rete.
               Scrive anche le buste di prova del manifest firmate con KT (manifest\) e le immagini ostili (ostili\) per la seconda parte.
    pc         il NAS di prova dal PC, con il banco esistente (core-nas, -PnasIntegration, il file delle credenziali che il banco legge
               da solo): S3 (b) segmenti ostili, S3 (c) immagini ostili, S9 cambiamento di massa (2.000 file) poi rigiocato dallo
               scanner vero, S1 (b) fra account (serve user2). Solo mcg-it/<giro>/ delle share di prova; ogni prova toglie il suo giro.
               Al primo AUTH_FAILED si ferma (nessun nuovo tentativo). Senza credenziali o senza NAS: i test risultano saltati o rossi
               con il motivo nel resoconto.
    emulatore  da eseguire nella seconda parte di P11 (S1 (c), S2, S3, S4, S5, S7, S8 (b), S9 con la famiglia di prova).
    s25        da eseguire nella seconda parte di P11 (tools\chaos\s25.ps1 -Mode security, release candidata).

  Ogni comando Gradle passa dal semaforo condiviso (C:\VibeCoded Projects\gradle-slot.ps1, se c'e'), con --max-workers=2 e --no-daemon.

.PARAMETER Livello   jvm | pc | emulatore | s25
.PARAMETER Voce      Facoltativa: S1, S2, S3, S7, S8, S9 (solo quella voce). Senza: tutte.
.PARAMETER Breve     jvm: le quantita' di check.ps1 (1.000 mondi, 2.000 mutazioni) invece di quelle piene.
.PARAMETER File      pc: quanti file per S9 (da 300 a 2.000; di norma 2.000).
.PARAMETER VersionCode jvm: la versionCode bloccata dalle buste di prova del manifest (di norma 7).
.PARAMETER DryRun    Stampa i comandi e non lancia niente.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\security\run-all.ps1 -Livello jvm
  powershell -ExecutionPolicy Bypass -File tools\security\run-all.ps1 -Livello pc
  powershell -ExecutionPolicy Bypass -File tools\security\run-all.ps1 -Livello pc -Voce S9 -File 300
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][ValidateSet("jvm", "pc", "emulatore", "s25")][string]$Livello,
  [ValidateSet("", "S1", "S2", "S3", "S7", "S8", "S9")][string]$Voce = "",
  [switch]$Breve,
  [int]$File = 2000,
  [int]$VersionCode = 7,
  [switch]$DryRun
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outDir = Join-Path $root "build\security\$stamp"
$slot = "C:\VibeCoded Projects\gradle-slot.ps1"
. (Join-Path $PSScriptRoot "lib\security-common.ps1")

if ($Livello -eq "emulatore" -or $Livello -eq "s25") {
  Write-Host "Livello ${Livello}: da eseguire nella seconda parte di P11 (emulatore con la famiglia di prova, S25 con la release candidata)."
  Write-Host "Prepara pero' gia' i file: run-all.ps1 -Livello jvm scrive build\security\<data>\manifest\ e build\security\<data>\ostili\."
  exit 2
}

if (-not $DryRun) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }

# I test di ogni voce, per livello: modulo -> filtri --tests.
$jvm = [ordered]@{
  "S1" = @(":core-index|*.security.S1*")
  "S2" = @(":core-trust|*.security.S2*")
  "S3" = @(":core-trust|*.security.S3*", ":core-personal|*.security.S3*", ":core-metadata|*.security.S3*")
  "S7" = @(":core-models|*.security.S7a*", ":core-models|*ModelChaosMutationTest", ":core-release|*.security.S7b*", ":core-release|*EnvelopeMutationTest")
  "S8" = @(":core-nas|*.security.S8*")
  "S9" = @(":core-nasmedia|*.security.S9*", ":core-nas|*SecurityProbesFakeNasTest")
}
$pc = [ordered]@{
  "S1" = @(":core-nas|*SecurityNasIntegrationTest.s1b*")
  "S3" = @(":core-metadata|*S3HostileImagesTest", ":core-nas|*SecurityNasIntegrationTest.s3*")
  "S9" = @(":core-nas|*SecurityNasIntegrationTest.s9*")
}

$env:MCG_SECURITY_OUT = $outDir
$env:GRADLE_OPTS = "-Dorg.gradle.workers.max=2"
$results = @()

if ($Livello -eq "jvm") {
  if (-not $Breve) { $env:MCG_SECURITY_FULL = "1" } else { Remove-Item Env:MCG_SECURITY_FULL -ErrorAction SilentlyContinue }
  $env:MCG_SECURITY_VERSION_CODE = "$VersionCode"
  $pairs = @()
  foreach ($k in $jvm.Keys) { if (-not $Voce -or $Voce -eq $k) { $pairs += $jvm[$k] } }
  $since = Get-Date
  $code = Invoke-SecurityGradle -Root $root -Slot $slot -Pairs $pairs -DryRun:$DryRun
  $results += Get-SecurityResults -Root $root -Pairs $pairs -Since $since
  $level = "jvm"
} else {
  $env:MCG_SECURITY_S9_FILES = "$File"
  $env:MCG_SECURITY_IMAGES = (Join-Path $outDir "ostili")
  $pairs = @()
  foreach ($k in $pc.Keys) { if (-not $Voce -or $Voce -eq $k) { $pairs += $pc[$k] } }
  if ($pairs.Count -eq 0) { Write-Host "La voce $Voce non ha prove al livello pc."; exit 2 }
  $since = Get-Date
  $code = Invoke-SecurityGradle -Root $root -Slot $slot -Pairs $pairs -Extra @("-PnasIntegration") -DryRun:$DryRun
  $results += Get-SecurityResults -Root $root -Pairs $pairs -Since $since
  # S9: l'elenco del NAS di prova rigiocato dallo scanner vero (core-nasmedia).
  $listing = Join-Path $outDir "s9-elenco.tsv"
  if ((Test-Path $listing) -or $DryRun) {
    $env:MCG_S9_LISTING = $listing
    $replay = @(":core-nasmedia|*.security.S9MassChangeTest")
    $since = Get-Date
    $code2 = Invoke-SecurityGradle -Root $root -Slot $slot -Pairs $replay -DryRun:$DryRun
    if ($code -eq 0) { $code = $code2 }
    $results += Get-SecurityResults -Root $root -Pairs $replay -Since $since
  }
  $level = "pc"
}

if ($DryRun) { exit 0 }
$report = Join-Path $outDir "$level.txt"
Write-SecurityReport -Path $report -Level $level -Results $results -GradleExit $code
Get-Content $report
Write-Host ""
Write-Host "Resoconto: $report"
exit $code
