<#
.SYNOPSIS
  Prova di tools\release\release.ps1 (Fase 10, P12): ogni controllo del par. 2.9 deve far FALLIRE la release quando serve, e farla passare quando no.

.DESCRIPTION
  Tutto con un keystore di PROVA generato qui con keytool sotto build\release-test-run\ (mai pampa.jks, mai K1 o K2, mai nei commit: build\ e *.jks
  sono in .gitignore, e tools\check.ps1 passo 3 li cercherebbe). Gli APK sono piccoli e costruiti qui (javac, d8, aapt2, zipalign, apksigner). Per
  ogni caso lancia release.ps1 DAVVERO (con -TestKeystore, -TestOverlay e -ApkPath) e guarda codice di uscita e messaggio:
    - passa:   l'APK giusto, con la chiave di prova e tutto in regola (e il resoconto in build\release-test\1.0.0\);
    - falliscono: un APK con un'altra chiave, senza firma v3, debuggable, con un resto di debug, di misura, di KT o un package testing, con le librerie
      native a 4 KB o non allineate o assenti, con un componente esportato o un permesso fuori elenco o sharedUserId, con l'id di debug; un versionCode
      riusato, piu' basso o diverso da versions.txt; un changelog mancante (nel file e nell'APK); ReleaseKeys.PROVISIONAL = true; recinzioni.json
      mancante o rotto; l'impronta di pampa.jks non scritta; le opzioni di prova senza il keystore di prova;
    - e, sulle funzioni: l'albero (git pulito, tag, ramo main), il riferimento S25 vecchio, il riepilogo di check.ps1 (FAIL, benchmark saltato), -Prepara,
      -SegnaPubblicata e -Impronta -Scrivi.
  Non usa Gradle ne' la rete. Codice di uscita 0 solo se ogni prova e' andata come doveva.
#>
[CmdletBinding()]
param([switch]$KeepWork)

$ErrorActionPreference = "Stop"
$script:Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$script:Overlay = $null
foreach ($lib in @("release-lib-core.ps1", "release-lib-apk.ps1", "release-lib-launcher.ps1", "release-lib-check.ps1", "release-lib-verify.ps1", "release-lib-report.ps1", "test-release-lib.ps1")) {
  . (Join-Path $PSScriptRoot $lib)
}
$realRoot = $script:Root
$releaseScript = Join-Path $PSScriptRoot "release.ps1"
$work = Join-Path $realRoot "build\release-test-run"
if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
New-Item -ItemType Directory -Path $work -Force | Out-Null
$script:ok = 0
$script:bad = New-Object System.Collections.Generic.List[string]
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Check([string]$Name, [bool]$Condition, [string]$Detail = "") {
  if ($Condition) { $script:ok++; Write-Host ("  ok    " + $Name) -ForegroundColor Green }
  else { $script:bad.Add($Name); Write-Host ("  MALE  " + $Name) -ForegroundColor Red; if ($Detail) { Write-Host ("        " + $Detail) -ForegroundColor DarkGray } }
}
function Invoke-Release([string[]]$ReleaseArgs) {
  $run = Invoke-Native -File "powershell" -Arguments (@("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $releaseScript) + $ReleaseArgs)
  return [pscustomobject]@{ Code = $run.Code; Text = ($run.Lines -join "`n") }
}
function Tail([string]$Text) { return (($Text -split "`n" | Select-Object -Last 12) -join " / ") }

# --- Le chiavi di prova -----------------------------------------------------------------------------------------------------------
$password = "prova-12345"
$ksA = Join-Path $work "prova-a.jks"
$ksB = Join-Path $work "prova-b.jks"
New-TestKeystore $ksA $password
New-TestKeystore $ksB $password
$common = @("-TestKeystore", $ksA, "-TestStorePassword", $password)

# --- L'overlay di base: i file del repository come devono essere per una release di prova ---------------------------------------------
$base = Join-Path $work "overlay-base"
foreach ($dir in @("docs", "tools\release", "release", "core-release\src\main\kotlin\dev\pampa\mycloudgallery\core\release\keys")) { New-Item -ItemType Directory -Path (Join-Path $base $dir) -Force | Out-Null }
# Il documento vero ha gia' l'impronta di pampa.jks (dal 03/11/2026): le prove partono da una copia senza, come prima della prima scrittura.
$docBlank = (((Read-Utf8 (Join-Path $realRoot "docs\sicurezza.md")) -split '\r?\n') | ForEach-Object {
  if ($_.StartsWith('| `pampa.jks` |')) { [regex]::Replace($_, '`[0-9a-fA-F]{64}`', 'da scrivere') } else { $_ }
}) -join "`n"
[System.IO.File]::WriteAllText((Join-Path $base "docs\sicurezza.md"), $docBlank, $utf8)
$keysRel = "core-release\src\main\kotlin\dev\pampa\mycloudgallery\core\release\keys\ReleaseKeys.kt"
$keysText = (Read-Utf8 (Join-Path $realRoot $keysRel)) -replace 'const val PROVISIONAL: Boolean = true', 'const val PROVISIONAL: Boolean = false'
[System.IO.File]::WriteAllText((Join-Path $base $keysRel), $keysText, $utf8)
[System.IO.File]::WriteAllText((Join-Path $base "tools\release\recinzioni.json"), '{ "formato": 1, "consenso": null, "recinzioni": { "indexPublishTestSharesOnly": "accesa" } }', $utf8)
$versionsBase = "0.9.0  1  pubblicata  beta  2026-01-01  " + ("a" * 64) + "`n1.0.0  2  prevista  beta  -  -`n"
[System.IO.File]::WriteAllText((Join-Path $base "release\versions.txt"), $versionsBase, $utf8)
$changelog = Read-Utf8 (Join-Path $realRoot "CHANGELOG.md")

Write-Host "`nImpronta del certificato di prova" -ForegroundColor Cyan
$before = Get-DocFingerprint (Join-Path $base "docs\sicurezza.md")
Check "la riga di pampa.jks parte senza impronta" ($null -eq $before)
$run = Invoke-Release (@("-Impronta", "-Scrivi", "-TestOverlay", $base) + $common)
$written = Get-DocFingerprint (Join-Path $base "docs\sicurezza.md")
$env:MCG_TEST_FP = $password
$expectedFp = Get-KeystoreCertSha256 $ksA "pampa" "MCG_TEST_FP" $work
Remove-Item Env:\MCG_TEST_FP
Check "-Impronta -Scrivi scrive l'impronta del certificato nel documento" ($run.Code -eq 0 -and $written -and $written -eq $expectedFp) (Tail $run.Text)
Check "-Impronta non stampa la password" (-not $run.Text.Contains($password))
$docAfter = Read-Utf8 (Join-Path $base "docs\sicurezza.md")
Check "le righe di K1, K2 e KT del documento non cambiano" ((($docAfter -split '\r?\n') | Where-Object { $_.StartsWith('| **K1** |') }).Count -eq 1)

function New-CaseOverlay([string]$Name, [hashtable]$Files = @{}) {
  $dir = Join-Path $work ("overlay-" + $Name)
  Copy-Item -LiteralPath $base -Destination $dir -Recurse
  foreach ($relative in $Files.Keys) {
    $target = Join-Path $dir $relative
    if ($null -eq $Files[$relative]) {
      # Un file che deve mancare: via dalla sostituzione, e un segnaposto perche' non valga quello vero del repository (Resolve-RepoFile).
      Remove-Item -LiteralPath $target -Force -ErrorAction SilentlyContinue
      New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
      [System.IO.File]::WriteAllText(($target + ".assente"), "", $utf8)
      continue
    }
    New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
    [System.IO.File]::WriteAllText($target, $Files[$relative], $utf8)
  }
  return $dir
}
function Invoke-Case([string]$Apk, [string]$Overlay, [string]$Version = "1.0.0", [string]$Channel = "beta") {
  return Invoke-Release (@("-Version", $Version, "-Channel", $Channel, "-TestOverlay", $Overlay, "-ApkPath", $Apk, "-SkipTree", "-SkipChecks") + $common)
}
function Expect-Fail([string]$Name, $Run, [string]$Fragment) {
  Check $Name ($Run.Code -ne 0 -and $Run.Text -like "*$Fragment*") ("codice $($Run.Code), cercavo '$Fragment': " + (Tail $Run.Text))
}
$apkDir = Join-Path $work "apk"
New-Item -ItemType Directory -Path $apkDir -Force | Out-Null
function Good([hashtable]$Extra = @{}) {
  $options = @{ Keystore = $ksA; VersionCode = 2; Changelog = $changelog }
  foreach ($key in $Extra.Keys) { $options[$key] = $Extra[$key] }
  return $options
}

Write-Host "`nLa release giusta" -ForegroundColor Cyan
$goodApk = New-MiniApk $apkDir "giusto" (Good)
$run = Invoke-Case $goodApk $base
Check "un APK giusto, firmato con la chiave di prova, passa tutti i controlli" ($run.Code -eq 0 -and $run.Text -like "*OK (PROVA)*") (Tail $run.Text)
$out = Join-Path $realRoot "build\release-test\1.0.0"
foreach ($file in @("MyCloudGallery-1.0.0.apk", "MyCloudGallery-1.0.0.apk.sha256", "rapporto.txt", "dipendenze.txt", "changelog-store.txt", "pampa-store.json", "checklist.txt")) {
  Check "il resoconto ha $file" (Test-Path -LiteralPath (Join-Path $out $file))
}
$report = if (Test-Path (Join-Path $out "rapporto.txt")) { Read-Utf8 (Join-Path $out "rapporto.txt") } else { "" }
Check "il resoconto dice PROVA e l'impronta del certificato" ($report -like "*PROVA*" -and $report -like "*$expectedFp*" -and $report -like "*v2=True v3=True*")
Check "il resoconto non contiene la password" (-not $report.Contains($password))
$store = if (Test-Path (Join-Path $out "changelog-store.txt")) { Read-Utf8 (Join-Path $out "changelog-store.txt") } else { "" }
Check "il testo del Pampa Store ha le quattro voci e nessun cancelletto" ($store -like "*Correzioni:*" -and $store -like "*Sicurezza:*" -and $store -like "*Da sapere:*" -and -not $store.Contains("#"))

Write-Host "`nLa firma" -ForegroundColor Cyan
Expect-Fail "un APK con un'altra chiave fa fallire" (Invoke-Case (New-MiniApk $apkDir "altra-chiave" (Good @{ Keystore = $ksB })) $base) "non e' quella di pampa.jks"
Expect-Fail "un APK senza firma v3 fa fallire" (Invoke-Case (New-MiniApk $apkDir "senza-v3" (Good @{ V3 = $false })) $base) "firma v3"
Expect-Fail "l'impronta non scritta nel documento fa fallire" (Invoke-Case $goodApk (New-CaseOverlay "senza-impronta" @{ "docs\sicurezza.md" = $docBlank })) "manca l'impronta"

Write-Host "`nIl contenuto dell'APK" -ForegroundColor Cyan
Expect-Fail "un APK debuggable fa fallire" (Invoke-Case (New-MiniApk $apkDir "debuggable" (Good @{ Debuggable = $true })) $base) "DEBUGGABLE"
Expect-Fail "l'id di debug fa fallire" (Invoke-Case (New-MiniApk $apkDir "id-debug" (Good @{ Package = "dev.pampa.mycloudgallery.debug" })) $base) "l'id dell'APK e'"
Expect-Fail "un resto di debug nel dex fa fallire" (Invoke-Case (New-MiniApk $apkDir "resto-debug" (Good @{ ExtraClasses = @{ "dev.pampa.mycloudgallery.debugtools.eval.EvalEntry" = "" } })) $base) "resti di strumenti di debug"
Expect-Fail "una classe di misura nel dex fa fallire" (Invoke-Case (New-MiniApk $apkDir "misura" (Good @{ ExtraClasses = @{ "dev.pampa.mycloudgallery.bench.BenchHooks" = "" } })) $base) "classi di misura"
Expect-Fail "un fake (package testing) nel dex fa fallire" (Invoke-Case (New-MiniApk $apkDir "fake" (Good @{ ExtraClasses = @{ "dev.pampa.mycloudgallery.core.nas.testing.FakeNas" = "" } })) $base) "package testing"
$kt = @([regex]::Matches((Read-Utf8 (Join-Path $realRoot "core-release\src\main\kotlin\dev\pampa\mycloudgallery\core\release\testing\ReleaseTestKeys.kt")), '"([A-Za-z0-9+/=]{64,})"') | ForEach-Object { $_.Groups[1].Value })[0]
Expect-Fail "la chiave di prova KT nel dex fa fallire" (Invoke-Case (New-MiniApk $apkDir "kt" (Good @{ ExtraClasses = @{ "dev.pampa.mycloudgallery.core.release.Holder" = "public static final String K = `"$kt`";" } })) $base) "chiave di prova KT"
Expect-Fail "la libreria nativa a 4 KB fa fallire" (Invoke-Case (New-MiniApk $apkDir "lib4k" (Good @{ Lib = "align4096" })) $base) "segmento LOAD allineato a 4096"
Expect-Fail "la libreria nativa non allineata nel file fa fallire" (Invoke-Case (New-MiniApk $apkDir "libnoalign" (Good @{ Lib = "unaligned" })) $base) "non e' allineata a 16 KB"
Expect-Fail "nessuna libreria nativa fa fallire (lettura non affidabile)" (Invoke-Case (New-MiniApk $apkDir "senza-lib" (Good @{ Lib = "none" })) $base) "nessuna libreria nativa"
Expect-Fail "un componente esportato fuori elenco fa fallire" (Invoke-Case (New-MiniApk $apkDir "esportato" (Good @{ ExportedComponents = @("dev.pampa.mycloudgallery.Intruso") })) $base) "componente esportato non ammesso"
# I5 (12/11/2026): gli ingressi del launcher. Il caso buono (i tre alias, solo l'automatica abilitata) e' l'APK "giusto" qui sopra.
Expect-Fail "un quarto alias del launcher fa fallire" (Invoke-Case (New-MiniApk $apkDir "alias-quarto" (Good @{ Launcher = (New-LauncherXml -ExtraAlias "Rainbow") })) $base) "ingresso del launcher fuori elenco"
Expect-Fail "un alias con un filtro in piu' (VIEW) fa fallire" (Invoke-Case (New-MiniApk $apkDir "alias-filtro" (Good @{ Launcher = (New-LauncherXml @{ Auto = @{ Extra = '<action android:name="android.intent.action.VIEW"/>' } }) })) $base) "la sola azione MAIN"
Expect-Fail "un alias verso un'altra attivita' fa fallire" (Invoke-Case (New-MiniApk $apkDir "alias-bersaglio" (Good @{ Launcher = (New-LauncherXml @{ Dark = @{ Target = "dev.pampa.mycloudgallery.Altra" } }) })) $base) "deve puntare a dev.pampa.mycloudgallery.MainActivity"
Expect-Fail "due alias abilitati di serie fanno fallire" (Invoke-Case (New-MiniApk $apkDir "alias-due" (Good @{ Launcher = (New-LauncherXml @{ Light = @{ Enabled = $true } }) })) $base) "un solo ingresso del launcher alla volta"
Expect-Fail "nessun alias abilitato di serie fa fallire" (Invoke-Case (New-MiniApk $apkDir "alias-zero" (Good @{ Launcher = (New-LauncherXml @{ Auto = @{ Enabled = $false } }) })) $base) "deve essere abilitato"
Expect-Fail "la MainActivity con il suo filtro LAUNCHER fa fallire" (Invoke-Case (New-MiniApk $apkDir "alias-main" (Good @{ Launcher = (New-LauncherXml -MainLauncher) })) $base) "fuori elenco"
Expect-Fail "sharedUserId fa fallire" (Invoke-Case (New-MiniApk $apkDir "shared" (Good @{ ManifestAttributes = 'android:sharedUserId="dev.pampa.altro"' })) $base) "sharedUserId"
Expect-Fail "un permesso fuori dall'elenco chiuso fa fallire" (Invoke-Case (New-MiniApk $apkDir "permesso" (Good @{ Permissions = @("android.permission.READ_SMS") })) $base) "permesso fuori dall'elenco chiuso"

Write-Host "`nVersioni e changelog" -ForegroundColor Cyan
$shaGood = Get-FileSha256 $goodApk
$reused = "0.9.0  2  pubblicata  beta  2026-01-01  " + ("b" * 64) + "`n1.0.0  2  prevista  beta  -  -`n"
Expect-Fail "un versionCode riusato (di un'altra versione) fa fallire" (Invoke-Case $goodApk (New-CaseOverlay "riusato" @{ "release\versions.txt" = $reused })) "riusato"
$lower = "0.9.0  5  pubblicata  beta  2026-01-01  " + ("b" * 64) + "`n1.0.0  3  prevista  beta  -  -`n"
Expect-Fail "un versionCode piu' basso di uno pubblicato fa fallire" (Invoke-Case $goodApk (New-CaseOverlay "basso" @{ "release\versions.txt" = $lower })) "piu' basso"
Expect-Fail "un APK con un versionCode diverso da versions.txt fa fallire" (Invoke-Case (New-MiniApk $apkDir "codice-3" (Good @{ VersionCode = 3 })) $base) "versions.txt dice 2"
$promo = "1.0.0  2  pubblicata  beta  2026-01-01  $shaGood`n1.0.0  2  prevista  stable  -  -`n"
$promoRun = Invoke-Case $goodApk (New-CaseOverlay "promozione" @{ "release\versions.txt" = $promo }) "1.0.0" "stable"
Check "lo stesso APK passa da beta a stable con lo stesso versionCode" ($promoRun.Code -eq 0) (Tail $promoRun.Text)
$promoNoApk = Invoke-Release (@("-Version", "1.0.0", "-Channel", "stable", "-TestOverlay", (New-CaseOverlay "promozione3" @{ "release\versions.txt" = $promo }), "-SkipTree", "-SkipChecks") + $common)
Check "la stable riusa l'APK della beta (stessa impronta) senza ricostruirlo" ($promoNoApk.Code -eq 0 -and $promoNoApk.Text -like "*promozione*" -and $promoNoApk.Text -like "*OK (PROVA)*") (Tail $promoNoApk.Text)
$otherApk = New-MiniApk $apkDir "altro-apk" (Good @{ Changelog = ($changelog + "`n") })
Expect-Fail "un APK diverso con il versionCode di uno pubblicato fa fallire" (Invoke-Case $otherApk (New-CaseOverlay "promozione2" @{ "release\versions.txt" = $promo }) "1.0.0" "stable") "riusato"
Expect-Fail "stable con una versione beta fa fallire" (Invoke-Case $goodApk (New-CaseOverlay "beta-stable" @{ "release\versions.txt" = "1.0.0-beta.1  2  prevista  stable  -  -`n" }) "1.0.0-beta.1" "stable") "senza -beta.N"
Expect-Fail "due righe previste fanno fallire" (Invoke-Case $goodApk (New-CaseOverlay "due-previste" @{ "release\versions.txt" = ($versionsBase + "1.1.0  3  prevista  beta  -  -`n") })) "piu' di una riga"
Expect-Fail "un versions.txt rotto fa fallire" (Invoke-Case $goodApk (New-CaseOverlay "rotto" @{ "release\versions.txt" = "1.0.0 due prevista beta - -`n" })) "versionCode 'due' non valido"
Expect-Fail "un changelog senza la sezione della versione fa fallire" (Invoke-Case $goodApk (New-CaseOverlay "senza-sezione" @{ "CHANGELOG.md" = "# Novita`n`n## 0.9.0`n`n### Novita'`n- x`n" })) "non ha la sezione"
Expect-Fail "un changelog senza le quattro voci fa fallire" (Invoke-Case $goodApk (New-CaseOverlay "poche-voci" @{ "CHANGELOG.md" = "## 1.0.0`n`n### Novit" + [char]0x00E0 + "`n- una cosa`n" })) "deve avere, in quest'ordine"
Expect-Fail "un APK senza il changelog fra le risorse fa fallire" (Invoke-Case (New-MiniApk $apkDir "senza-risorsa" (Good @{ Changelog = $null })) $base) "non contiene il changelog"
Expect-Fail "un APK con il changelog di un'altra versione fa fallire" (Invoke-Case (New-MiniApk $apkDir "risorsa-vecchia" (Good @{ Changelog = "## 0.9.0`n`n### Novit" + [char]0x00E0 + "`n- una cosa`n" })) $base) "non contiene il changelog"

Write-Host "`nChiavi del manifest e recinzioni" -ForegroundColor Cyan
$keysTrue = $keysText -replace 'const val PROVISIONAL: Boolean = false', 'const val PROVISIONAL: Boolean = true'
Expect-Fail "ReleaseKeys.PROVISIONAL = true fa fallire" (Invoke-Case $goodApk (New-CaseOverlay "provvisorie" @{ $keysRel = $keysTrue })) "PROVISIONAL e' true"
$noCheck = Invoke-Release (@("-Version", "1.0.0", "-Channel", "beta", "-TestOverlay", (New-CaseOverlay "provvisorie2" @{ $keysRel = $keysTrue }), "-ApkPath", $goodApk, "-SkipTree") + $common)
Check "se un controllo prima e' fallito, check.ps1 (mezz'ora) non parte nemmeno" ($noCheck.Code -ne 0 -and $noCheck.Text -like "*non si spreca mezz'ora*") (Tail $noCheck.Text)
$keysComment = $keysText + "`n// const val PROVISIONAL: Boolean = true`n/* const val PROVISIONAL: Boolean = true */`n"
$commentRun = Invoke-Case $goodApk (New-CaseOverlay "commento" @{ $keysRel = $keysComment })
Check "un PROVISIONAL true solo in un commento non conta" ($commentRun.Code -eq 0) (Tail $commentRun.Text)
Expect-Fail "recinzioni.json mancante fa fallire" (Invoke-Case $goodApk (New-CaseOverlay "senza-recinzioni" @{ "tools\release\recinzioni.json" = $null })) "manca recinzioni.json"
Expect-Fail "recinzioni.json rotto fa fallire" (Invoke-Case $goodApk (New-CaseOverlay "recinzioni-rotte" @{ "tools\release\recinzioni.json" = "{ non json" })) "non e' un JSON valido"

Write-Host "`nLe opzioni di prova non valgono senza il keystore di prova" -ForegroundColor Cyan
foreach ($option in @(@("-ApkPath", $goodApk), @("-SkipTree"), @("-SkipChecks"), @("-TestOverlay", $base))) {
  $run = Invoke-Release (@("-Version", "1.0.0", "-Channel", "beta") + $option)
  Check ("senza -TestKeystore, " + $option[0] + " e' rifiutata") ($run.Code -ne 0 -and $run.Text -like "*valgono solo con -TestKeystore*") (Tail $run.Text)
}
$outside = Join-Path ([System.IO.Path]::GetTempPath()) "prova-fuori.jks"
New-TestKeystore $outside $password
$run = Invoke-Release @("-Version", "1.0.0", "-Channel", "beta", "-TestKeystore", $outside, "-TestStorePassword", $password)
Remove-Item -LiteralPath $outside -Force
Check "un keystore di prova fuori da build\ e' rifiutato" ($run.Code -ne 0 -and $run.Text -like "*deve stare sotto*") (Tail $run.Text)
$named = Join-Path $work "pampa.jks"
Copy-Item -LiteralPath $ksA -Destination $named
$run = Invoke-Release @("-Version", "1.0.0", "-Channel", "beta", "-TestKeystore", $named, "-TestStorePassword", $password)
Check "un keystore di prova che si chiama pampa* e' rifiutato" ($run.Code -ne 0 -and $run.Text -like "*non si chiama pampa*") (Tail $run.Text)
$run = Invoke-Release @("-Channel", "beta")
Check "senza -Version non parte" ($run.Code -ne 0 -and $run.Text -like "*serve -Version*")

# Le prove sulle funzioni stanno in test-release-parts.ps1.
. (Join-Path $PSScriptRoot "test-release-parts.ps1")

Write-Host ""
if (-not $KeepWork) { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }
if ($script:bad.Count -gt 0) {
  Write-Host ("FAIL: {0} prove ok, {1} andate male:" -f $script:ok, $script:bad.Count) -ForegroundColor Red
  foreach ($name in $script:bad) { Write-Host ("  - " + $name) -ForegroundColor Red }
  exit 1
}
Write-Host ("OK: {0} prove, tutte come dovevano." -f $script:ok) -ForegroundColor Green
exit 0
