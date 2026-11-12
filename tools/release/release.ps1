<#
.SYNOPSIS
  La catena di rilascio di MyCloud Gallery (Fase 10, P12; docs/fase10-progetto.md par. 2.9, D32-D34): i passi 1-8 con tutti i controlli.

.DESCRIPTION
  Una release (tutto dal PC, niente rete: la pubblicazione non e' di questo script):

    0. chiavi del manifest   ReleaseKeys.PROVISIONAL deve essere false (L560): K1 e K2 vere, altrimenti nessun blocco a distanza
    1. albero                git status pulito, HEAD sul tag v<versione> di main, engine pulito (engine-doctor)
    2. recinzioni            tools\release\recinzioni.json c'e' ed e' leggibile (lo scrive P10; il confronto col bytecode e' di check.ps1)
    3. prove                 tools\check.ps1 -Bench -BenchStrict verde (con l'S25 collegato) e riferimento S25 di al piu' 7 giorni
    4. build                 release/versions.txt (versionCode maggiore di ogni versione pubblicata, mai riusato) e assembleRelease firmata
                             con pampa.jks (le voci pampa.* di local.properties, lette da Gradle: questo script non le legge mai)
    5. APK                   apksigner (v2 e v3, impronta del certificato = quella di docs/sicurezza.md par. 5.6), non debuggable, manifest e
                             permessi dell'elenco chiuso, nessun resto di debug, di misura, di KT, librerie native a 16 KB
    6. changelog             la sezione della versione in CHANGELOG.md e dentro l'APK (risorsa della pagina "Novita' di questa versione")
    7. resoconto             build\release\<versione>\: APK, SHA-256, impronta, dimensione, dipendenze con le impronte, mapping di R8 (resta sul PC)
    8. prova a secco         NON la fa questo script: stampa cosa dare all'orchestratore (skill del Pampa Store, --dry-run)

  La pubblicazione (passo 9) e il dopo (passo 10) li avvia l'utente alla fine di tutte le fasi, con la skill del Pampa Store.

  Altri modi:
    -Prepara            aggiunge a release/versions.txt la riga `prevista` di -Version e -Channel (versionCode = massimo + 1; stesso numero per il
                        passaggio da beta a stable della stessa versione)
    -SegnaPubblicata    dopo la pubblicazione: la riga diventa `pubblicata` con la data e l'impronta dell'APK del resoconto
    -Impronta [-Scrivi] calcola l'impronta SHA-256 del CERTIFICATO di pampa.jks (dato pubblico; la password passa da una variabile d'ambiente,
                        mai stampata) e, con -Scrivi, la mette nella riga di pampa.jks di docs/sicurezza.md par. 5.6
    -Checklist          stampa la checklist di rilascio

  PROVE (-TestKeystore): firmano e controllano con un keystore di PROVA generato sotto build\ (mai pampa.jks, mai K1 o K2). Solo cosi' valgono
  -TestOverlay (file del repository sostituiti: sicurezza.md, recinzioni.json, ReleaseKeys.kt, versions.txt, CHANGELOG.md...), -ApkPath (un APK gia'
  firmato invece della build), -SkipTree e -SkipChecks. Il resoconto va in build\release-test\ e dice "PROVA": non si pubblica.

.PARAMETER NoDaemon
  Gradle (e check.ps1) con --no-daemon: nessun processo che resta residente a fine release.

.PARAMETER BenchSerial
  Il numero di serie dell'S25 (`adb devices`) quando i dispositivi collegati sono piu' d'uno (passo 3).

.PARAMETER BenchMaxAgeDays
  Quanto puo' essere vecchio il riferimento S25 di docs\bench (passo 3). Di base 7 giorni.

.PARAMETER GradleArgs
  Argomenti in piu' per Gradle nel passo 4 (per esempio --max-workers=2).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\release\release.ps1 -Impronta -Scrivi
  powershell -ExecutionPolicy Bypass -File tools\release\release.ps1 -Prepara -Version 1.0.0 -Channel beta
  powershell -ExecutionPolicy Bypass -File tools\release\release.ps1 -Version 1.0.0 -Channel beta -NoDaemon
#>
[CmdletBinding()]
param(
  [string]$Version,
  [ValidateSet("beta", "stable")][string]$Channel,
  [switch]$Prepara,
  [switch]$SegnaPubblicata,
  [switch]$Impronta,
  [switch]$Scrivi,
  [switch]$Checklist,
  [switch]$NoDaemon,
  [string]$BenchSerial,
  [int]$BenchMaxAgeDays = 7,
  [string[]]$GradleArgs = @(),
  [string]$TestKeystore,
  [string]$TestStorePassword,
  [string]$TestKeyAlias = "pampa",
  [string]$TestOverlay,
  [string]$ApkPath,
  [switch]$SkipTree,
  [switch]$SkipChecks
)

$ErrorActionPreference = "Stop"
$script:Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$script:Overlay = $null
foreach ($lib in @("release-lib-core.ps1", "release-lib-apk.ps1", "release-lib-launcher.ps1", "release-lib-check.ps1", "release-lib-verify.ps1", "release-lib-report.ps1")) {
  . (Join-Path $PSScriptRoot $lib)
}

# --- Prove: il keystore di prova e le opzioni che valgono solo con lui -----------------------------------------------------------
$isTest = [bool]$TestKeystore
if (-not $isTest -and ($TestOverlay -or $ApkPath -or $SkipTree -or $SkipChecks -or $TestStorePassword)) {
  throw "-TestOverlay, -ApkPath, -SkipTree, -SkipChecks e -TestStorePassword valgono solo con -TestKeystore (prove con un keystore di prova sotto build\): una release vera non salta nessun controllo"
}
$testKeystorePath = $null
if ($isTest) {
  if (-not (Test-Path -LiteralPath $TestKeystore)) { throw "il keystore di prova non esiste: $TestKeystore" }
  $testKeystorePath = (Resolve-Path -LiteralPath $TestKeystore).Path
  $buildDir = (Join-Path $script:Root "build") + "\"
  if (-not $testKeystorePath.StartsWith($buildDir, [System.StringComparison]::OrdinalIgnoreCase)) { throw "il keystore di prova deve stare sotto $buildDir (generato dalla prova con keytool): mai un keystore vero" }
  if ([System.IO.Path]::GetFileName($testKeystorePath) -match 'pampa') { throw "un keystore di prova non si chiama pampa*: questo script non apre mai pampa.jks con -TestKeystore" }
  if ($TestOverlay) { $script:Overlay = (Resolve-Path -LiteralPath $TestOverlay).Path }
  if (-not $TestStorePassword) { $TestStorePassword = $env:MCG_TEST_STOREPASS }
  if (-not $TestStorePassword) { throw "serve la password del keystore di prova: -TestStorePassword o la variabile MCG_TEST_STOREPASS" }
  if ($ApkPath -and -not (Test-Path -LiteralPath $ApkPath)) { throw "l'APK di prova non esiste: $ApkPath" }
}
$today = (Get-Date).ToString("yyyy-MM-dd")
$versionsFile = Resolve-RepoFile "release\versions.txt"

# --- Modi che non sono una release ------------------------------------------------------------------------------------------------
if ($Checklist) {
  Write-Host "Checklist di rilascio (par. 2.9)" -ForegroundColor Cyan
  foreach ($item in (Get-ReleaseChecklist)) { Write-Host "  [ ] $item" }
  exit 0
}

if ($Impronta) {
  $keystore = $null; $alias = "pampa"; $envName = "MCG_RELEASE_STOREPASS"
  if ($isTest) {
    $keystore = $testKeystorePath; $alias = $TestKeyAlias
    $env:MCG_RELEASE_STOREPASS = $TestStorePassword
  } else {
    # La release vera: le stesse voci di Gradle, lette qui solo per aprire il certificato (dato pubblico). La password non si stampa mai.
    $properties = @{}
    $localProperties = Join-Path $script:Root "local.properties"
    if (Test-Path -LiteralPath $localProperties) {
      foreach ($line in (Get-Content -LiteralPath $localProperties)) {
        if ($line -match '^\s*(pampa\.[A-Za-z]+)\s*=\s*(.*)$') { $properties[$Matches[1]] = $Matches[2].Trim().Replace("\:", ":").Replace("\\", "\") }
      }
    }
    if (-not $properties["pampa.storeFile"] -or -not $properties["pampa.storePassword"]) { throw "local.properties non ha pampa.storeFile e pampa.storePassword (vedi app/build.gradle)" }
    $keystore = $properties["pampa.storeFile"]
    if ($properties["pampa.keyAlias"]) { $alias = $properties["pampa.keyAlias"] }
    $env:MCG_RELEASE_STOREPASS = $properties["pampa.storePassword"]
  }
  try {
    $hex = Get-KeystoreCertSha256 $keystore $alias $envName (Join-Path $script:Root "build\release-work")
  } finally {
    Remove-Item Env:\MCG_RELEASE_STOREPASS -ErrorAction SilentlyContinue
  }
  if (-not $hex) { throw "non riesco a leggere il certificato del keystore (keytool, alias '$alias'): controlla percorso, alias e password" }
  Write-Host "Impronta SHA-256 del certificato di firma: $hex"
  Write-Host "Confrontala con la copia offline di pampa.jks (ottieni la stessa con: keytool -list -v -keystore <file> -alias $alias)." -ForegroundColor DarkGray
  if ($Scrivi) {
    Set-DocFingerprint (Resolve-RepoFile "docs\sicurezza.md") $hex $today
    Write-Host "Scritta in docs/sicurezza.md par. 5.6 (riga di pampa.jks): ora commit." -ForegroundColor Green
  } else {
    Write-Host "Per scriverla nel documento: release.ps1 -Impronta -Scrivi" -ForegroundColor DarkGray
  }
  exit 0
}

if (-not $Version -or $Version -notmatch '^\d+\.\d+\.\d+(-beta\.\d+)?$') { throw "serve -Version (1.0.0, oppure 1.1.0-beta.2)" }
if (-not $Channel) { throw "serve -Channel beta oppure stable" }
$outRoot = Join-Path $script:Root ($(if ($isTest) { "build\release-test" } else { "build\release" }))
$outDir = Join-Path $outRoot $Version

if ($Prepara) {
  $code = Add-PreparedVersion $versionsFile $Version $Channel
  Write-Host "Aggiunta a release/versions.txt: $Version, versionCode $code, canale $Channel (prevista)." -ForegroundColor Green
  Write-Host "Ora: scrivi la sezione '## $Version' in CHANGELOG.md (e la copia in feature-settings/src/main/res/raw/changelog.md), commit, tag v$Version su main, poi release.ps1." -ForegroundColor DarkGray
  exit 0
}

if ($SegnaPubblicata) {
  $shaFile = Join-Path $outDir "MyCloudGallery-$Version.apk.sha256"
  if (-not (Test-Path -LiteralPath $shaFile)) { throw "manca il resoconto ${shaFile}: si segna pubblicata una versione di cui questo script ha fatto il resoconto" }
  $sha = ((Read-Utf8 $shaFile).Trim() -split '\s+')[0]
  Set-VersionPublished $versionsFile $Version $Channel $today $sha
  Write-Host "versions.txt: $Version ($Channel) ora e' 'pubblicata' il $today, impronta $($sha.Substring(0, 12))...: commit." -ForegroundColor Green
  exit 0
}

# Il giro vero e proprio (passi 0-8), nella stessa portata di questo file.
. (Join-Path $PSScriptRoot "release-run.ps1")
# Un `exit` dentro un file caricato con il punto non porta il codice di uscita a chi ha lanciato lo script: lo si da' qui.
exit $script:exitCode
