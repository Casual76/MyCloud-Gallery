# Il giro di una release (Fase 10, P12; docs/fase10-progetto.md par. 2.9): i passi 0-8 in ordine. Si carica con il punto da release.ps1, nella sua
# stessa portata: usa i suoi parametri e le funzioni delle librerie. Solo ASCII, PowerShell 5.1.

$script:results = New-Object System.Collections.Generic.List[object]
$script:failed = $false
$script:exitCode = 1
$script:passed = New-Object System.Collections.Generic.List[string]
$script:apk = $null
$script:facts = $null
$work = Join-Path $script:Root "build\release-work"

# Un passo ritorna l'elenco dei problemi (stringhe; vuoto = a posto). Un'eccezione e' un FAIL.
function Step {
  param([string]$Name, [scriptblock]$Body, [switch]$Skip, [string]$SkipReason)
  if ($Skip) {
    Write-Host ("[ -- ] {0}  ({1})" -f $Name, $SkipReason) -ForegroundColor DarkGray
    $script:results.Add([pscustomobject]@{ Name = $Name; Status = "SKIP"; Detail = $SkipReason })
    return
  }
  Write-Host ("[ .. ] {0}" -f $Name) -ForegroundColor Cyan
  $watch = [System.Diagnostics.Stopwatch]::StartNew()
  $problems = @()
  try {
    $problems = @(& $Body | Where-Object { $_ -is [string] -and $_.Length -gt 0 })
  } catch {
    $problems = @("eccezione: " + $_.Exception.Message)
  }
  $seconds = [math]::Round($watch.Elapsed.TotalSeconds, 1)
  if ($problems.Count -gt 0) {
    Write-Host ("[FAIL] {0}  ({1}s)" -f $Name, $seconds) -ForegroundColor Red
    foreach ($problem in $problems) { Write-Host ("        - " + $problem) -ForegroundColor Red }
    $script:failed = $true
    $script:results.Add([pscustomobject]@{ Name = $Name; Status = "FAIL"; Detail = ($problems -join "; ") })
  } else {
    Write-Host ("[ OK ] {0}  ({1}s)" -f $Name, $seconds) -ForegroundColor Green
    $script:passed.Add($Name)
    $script:results.Add([pscustomobject]@{ Name = $Name; Status = "OK"; Detail = "" })
  }
}

Write-Host ""
Write-Host ("MyCloud Gallery - release $Version ($Channel)") -ForegroundColor Cyan
Write-Host ("  cartella: " + $script:Root)
if ($isTest) {
  Write-Host "  PROVA con un keystore di prova (build\release-test): NON e' una release e non si pubblica." -ForegroundColor Yellow
  if ($script:Overlay) { Write-Host ("  file sostituiti da: " + $script:Overlay) -ForegroundColor Yellow }
}
Write-Host ""

# --- 0. Chiavi del manifest ---------------------------------------------------------------------------------------------------------
Step "0. chiavi del manifest: ReleaseKeys.PROVISIONAL e' false (L560)" { Test-ReleaseKeysNotProvisional }

# --- 1. Albero ---------------------------------------------------------------------------------------------------------------------
Step "1. albero: pulito, HEAD sul tag v$Version di main, engine pulito" -Skip:$SkipTree -SkipReason "prova (-SkipTree)" { Test-ReleaseTree $script:Root $Version }

# --- 2. Recinzioni -----------------------------------------------------------------------------------------------------------------
Step "2. recinzioni: tools\release\recinzioni.json" {
  $summary = ""
  $problems = Test-Recinzioni ([ref]$summary)
  if (@($problems).Count -eq 0) { Write-Host ("        campi dichiarati: " + $summary + " (il bytecode lo confronta check.ps1, passo 3)") -ForegroundColor DarkGray }
  $problems
}

# --- 3. Prove ----------------------------------------------------------------------------------------------------------------------
Step "3. prove: check.ps1 -Bench -BenchStrict verde, S25 collegato" -Skip:($SkipChecks -or $script:failed) -SkipReason $(if ($SkipChecks) { "prova (-SkipChecks)" } else { "un controllo prima e' fallito: non si spreca mezz'ora di prove" }) {
  Write-Host "        (puo' volerci piu' di mezz'ora; log in build\release-work\check.log)" -ForegroundColor DarkGray
  $arguments = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", (Join-Path $script:Root "tools\check.ps1"), "-Bench", "-BenchStrict")
  if ($NoDaemon) { $arguments += "-NoDaemon" }
  if ($BenchSerial) { $arguments += @("-BenchSerial", $BenchSerial) }
  $run = Invoke-Native -File "powershell" -Arguments $arguments -LogPath (Join-Path $work "check.log")
  Test-CheckSummary $run.Lines $run.Code
}
Step "3. prove: riferimento S25 di al piu' $BenchMaxAgeDays giorni" -Skip:$SkipChecks -SkipReason "prova (-SkipChecks)" { Test-BenchReference $BenchMaxAgeDays (Get-Date) }

# --- 4. Build ----------------------------------------------------------------------------------------------------------------------
$script:parsed = Read-VersionRows $versionsFile
Step "4. versioni: release/versions.txt (versionCode maggiore di ogni pubblicata, mai riusato)" { Test-VersionRows $script:parsed $Version $Channel }

# Il passaggio da beta a stable della stessa versione (stesso versionCode, D33): l'APK della stable e' QUELLO della beta, provato per due settimane. Se il
# resoconto della beta c'e' ancora e la sua impronta e' quella scritta in versions.txt, si riusa il file; altrimenti si ricostruisce, e Test-ApkAgainstVersion
# rifiuta un APK diverso con lo stesso numero.
$promotion = $null
$rowsNow = @($script:parsed.Rows)
if ($rowsNow.Count -gt 0 -and -not $ApkPath) {
  $lastRow = $rowsNow[$rowsNow.Count - 1]
  $earlier = @($rowsNow | Where-Object { $_.State -eq "pubblicata" -and $_.Name -eq $lastRow.Name -and $_.Code -eq $lastRow.Code -and $_.Channel -ne $lastRow.Channel })
  $stored = Join-Path $outDir "MyCloudGallery-$Version.apk"
  if ($lastRow.State -eq "prevista" -and $earlier.Count -gt 0 -and (Test-Path -LiteralPath $stored) -and ((Get-FileSha256 $stored) -eq $earlier[0].Sha)) { $promotion = $stored }
}
Step "4. promozione: l'APK della beta gia' provata (stessa impronta di versions.txt) passa a stable senza ricostruirlo" -Skip:(-not $promotion -or $script:failed) -SkipReason $(if ($script:failed) { "un controllo prima e' fallito" } else { "nessuna beta della stessa versione da promuovere: si costruisce" }) {
  $copyDir = Join-Path $work "promozione"
  New-Item -ItemType Directory -Path $copyDir -Force | Out-Null
  $copy = Join-Path $copyDir "MyCloudGallery-$Version.apk"
  Copy-Item -LiteralPath $promotion -Destination $copy -Force
  $script:apk = $copy
}

$building = (-not $script:failed) -and (-not $ApkPath) -and (-not $promotion)
Step "4. build: assembleRelease firmata con pampa.jks" -Skip:(-not $building) -SkipReason $(if ($ApkPath) { "prova: APK dato con -ApkPath" } elseif ($promotion) { "promozione: si riusa l'APK della beta" } else { "un controllo prima e' fallito: non si spreca la build" }) {
  Initialize-Java
  $apkDir = Join-Path $script:Root "app\build\outputs\apk\release"
  if (Test-Path -LiteralPath $apkDir) { Remove-Item -LiteralPath $apkDir -Recurse -Force }
  $arguments = @("--console=plain")
  if ($NoDaemon) { $arguments += "--no-daemon" }
  $arguments += $GradleArgs
  if ($isTest) {
    $arguments += @("-Ppampa.storeFile=" + $testKeystorePath.Replace("\", "/"), "-Ppampa.keyAlias=$TestKeyAlias", "-Ppampa.storePassword=$TestStorePassword")
  }
  $arguments += ":app:assembleRelease"
  Write-Host "        (puo' volerci diversi minuti; log in build\release-work\gradle.log)" -ForegroundColor DarkGray
  $run = Invoke-Native -File (Join-Path $script:Root "gradlew.bat") -Arguments $arguments -LogPath (Join-Path $work "gradle.log")
  if ($run.Code -ne 0) { return "gradle e' fallito (codice $($run.Code)): vedi build\release-work\gradle.log" }
  $built = @(Get-ChildItem -LiteralPath $apkDir -Filter "*.apk" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending)
  if ($built.Count -eq 0) { return "nessun APK in app\build\outputs\apk\release" }
  $signed = @($built | Where-Object { $_.Name -notmatch 'unsigned' })
  if ($signed.Count -eq 0) { return "l'APK di release non e' firmato: mancano le voci pampa.* in local.properties (pampa.storeFile, pampa.storePassword)" }
  $script:apk = $signed[0].FullName
}
if ($ApkPath) { $script:apk = (Resolve-Path -LiteralPath $ApkPath).Path }

# --- 5. APK ------------------------------------------------------------------------------------------------------------------------
Step "5. APK: firma v2 e v3, impronta di pampa.jks, non debuggable, manifest, resti di debug, 16 KB" -Skip:(-not $script:apk) -SkipReason "nessun APK da controllare" {
  $tools = Get-BuildTools
  if (-not $tools) { return "aapt2, apksigner e zipalign non trovati: controlla sdk.dir in local.properties e le build-tools dell'SDK" }
  $expected = Get-DocFingerprint (Resolve-RepoFile "docs\sicurezza.md")
  $result = Test-ReleaseApk $script:apk $expected $tools
  $script:facts = $result.Facts
  $problems = @($result.Problems)
  if (@($script:parsed.Rows).Count -gt 0) { $problems += @(Test-ApkAgainstVersion $script:parsed $Version $result.Facts $result.Facts.Sha256) }
  $f = $result.Facts
  Write-Host ("        {0} versionCode {1} ({2}), {3}: v1={4} v2={5} v3={6}, certificato {7}..., {8} byte, {9} librerie native" -f $f.Package, $f.VersionCode, $f.VersionName, $f.KeyAlgorithm, $f.V1, $f.V2, $f.V3, $f.CertSha256.Substring(0, [math]::Min(12, $f.CertSha256.Length)), $f.SizeBytes, $f.NativeLibs) -ForegroundColor DarkGray
  $problems
}

# --- 6. Changelog ------------------------------------------------------------------------------------------------------------------
$script:section = Get-ChangelogSection (Resolve-RepoFile "CHANGELOG.md") $Version
Step "6. changelog: la sezione $Version di CHANGELOG.md, con le quattro voci" { $script:section.Problems }
Step "6. changelog: la sezione e' dentro l'APK (pagina Novita' di questa versione)" -Skip:(-not $script:apk) -SkipReason "nessun APK da controllare" { Test-ChangelogInApk $script:apk $script:section.Text }

# --- 7. Resoconto ------------------------------------------------------------------------------------------------------------------
Step "7. resoconto: APK, SHA-256, impronta, dipendenze, mapping in $($outDir.Replace($script:Root + '\', ''))" -Skip:$script:failed -SkipReason "un controllo e' fallito: nessun resoconto" {
  $mapping = Join-Path $script:Root "app\build\outputs\mapping\release\mapping.txt"
  if (-not $isTest -and -not (Test-Path -LiteralPath $mapping)) { return "manca app\build\outputs\mapping\release\mapping.txt di R8 (il mapping resta sul PC)" }
  $commit = (Invoke-Native -File "git" -Arguments @("-C", $script:Root, "rev-parse", "--short=12", "HEAD")).Lines | Select-Object -First 1
  $output = New-ReleaseOutput -OutDir $outDir -Apk $script:apk -Facts $script:facts -Version $Version -Channel $Channel -SectionText $script:section.Text `
    -Commit $commit -Test $isTest -Passed @($script:passed) -MappingFile $mapping -MetadataPath (Join-Path $script:Root "gradle\verification-metadata.xml") -Today $today
  $script:output = $output
  Write-Host ("        {0}  SHA-256 {1}" -f $output.Apk, $output.Sha256) -ForegroundColor DarkGray
}

# --- Riepilogo, passo 8 e checklist ------------------------------------------------------------------------------------------------
Write-Host ""
Write-Host "Riepilogo" -ForegroundColor Cyan
foreach ($result in $script:results) {
  $color = switch ($result.Status) { "OK" { "Green" } "FAIL" { "Red" } default { "DarkGray" } }
  Write-Host ("  {0,-4}  {1}" -f $result.Status, $result.Name) -ForegroundColor $color
}
Write-Host ""
if ($script:failed) {
  Write-Host "FAIL: la release non e' pronta: c'e' almeno un controllo che non passa." -ForegroundColor Red
  $script:exitCode = 1
  return
}
if ($isTest) {
  Write-Host "OK (PROVA): tutti i controlli passano con il keystore di prova. Non e' una release." -ForegroundColor Yellow
  $script:exitCode = 0
  return
}
Write-Host "OK: i passi 0-7 passano. Mancano il passo 8 (prova a secco) e la checklist." -ForegroundColor Green
Write-Host ""
Write-Host "8. PROVA A SECCO del publisher del Pampa Store (non la fa questo script: la lancia l'orchestratore, con la skill pampa-store-publish-direct e --dry-run):" -ForegroundColor Cyan
Write-Host ("     dati:       " + (Join-Path $outDir "pampa-store.json"))
Write-Host ("     APK:        " + $script:output.Apk)
Write-Host ("     versione:   $Version (versionCode $($script:facts.VersionCode)), canale $Channel")
Write-Host ("     changelog:  " + (Join-Path $outDir "changelog-store.txt"))
Write-Host ("     da dire:    'prova a secco del publisher del Pampa Store con " + (Join-Path $outDir "pampa-store.json") + ", --dry-run, senza caricare niente'")
Write-Host "   Dopo la prova a secco e la checklist: la pubblicazione la avvia l'utente (passo 9), prima beta per almeno due settimane, poi stable per tutta la famiglia."
Write-Host "   Dopo la pubblicazione: release.ps1 -SegnaPubblicata -Version $Version -Channel $Channel, e il commit di versions.txt."
Write-Host ""
Write-Host "Checklist di rilascio (la spuntano l'orchestratore e l'utente; copia in $outDir\checklist.txt)" -ForegroundColor Cyan
foreach ($item in (Get-ReleaseChecklist)) { Write-Host "  [ ] $item" }
$script:exitCode = 0
