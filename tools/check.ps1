<#
.SYNOPSIS
  Il comando unico di verifica di MyCloud Gallery: se finisce tutto OK, il repo e' in ordine.

.DESCRIPTION
  Esegue, in ordine, e alla fine stampa un riepilogo OK/FAIL (codice di uscita 0 solo se e' tutto OK):

    1. engine-doctor            l'engine e' quello che engine.properties dichiara (versione, tag, moduli)
    2. engine pulito            nessuna modifica locale dentro engine/ (sarebbe una variante silenziosa)
    3. repo senza segreti       nessun .jks/.keystore/local.properties tracciato o da tracciare
    4. verifica checksum        gradle/verification-metadata.xml c'e' ed e' attiva; il wrapper e' fissato
    5. gradle                   assembleDebug assembleRelease galleryTest galleryLint
                                (galleryTest/galleryLint = testDebugUnitTest/lintDebug dei soli moduli della
                                galleria, engine escluso; in piu' assembleRelease esegue verifyReleaseManifest)
    6. test eseguiti            ogni modulo ha eseguito almeno un test e nessuno e' fallito (un modulo con
                                ZERO test eseguiti risulterebbe "passato" per Gradle: qui no). `:benchmark` e'
                                escluso: e' un modulo `com.android.test` (Macrobenchmark) senza unit test, che
                                gira solo da un dispositivo e non entra mai nell'APK (Fase 3, D21)
    7. APK di release           ispezione dell'APK vero con aapt2: non debuggable, backup escluso, nessun
                                componente esportato oltre quelli ammessi da gradle\verify-release-manifest.gradle
                                (launcher e JobService di WorkManager, quest'ultimo solo con BIND_JOB_SERVICE),
                                nessun codice solo-debug dentro, e (Fase 3) nessun `ProfileInstallReceiver` (il
                                receiver del Macrobenchmark torna solo nella variante benchmarkRelease)
    7b. nessun resto di debug  nel dex dell'APK di release non compaiono la Libreria sintetica, il marcatore della famiglia
                                "pronta", `DebugSyntheticSize`, il sink di logcat e il catalogo dei componenti (I4 della
                                revisione di sicurezza): lettura diretta del dex, FALLISCE se non lo legge
    7c. niente servizi         l'APK di release non ha nessun `META-INF/services/dev.pampa.*` (ne' un file di servizio che
                                nomini una classe `dev.pampa.*`): le varianti di misura legano le loro classi con Hilt, mai
                                con `ServiceLoader` (R6)
    8. niente fake in release   nessuna classe di un package `testing` nel dex dell'APK di release (D6 di
                                docs/fase2-progetto.md: i fake stanno in src/main per riusarli a valle, ma
                                non devono arrivare all'APK). Lettura diretta dei classes*.dex: non dipende
                                da apkanalyzer e, se non riesce a leggere il dex, FALLISCE (non salta)
    +  permessi documentati     ogni permesso ammesso da gradle\verify-release-manifest.gradle (statico) e ogni
                                permesso dell'APK di release deve comparire in docs\sicurezza.md 4-bis
    +  profilo nell'APK         (Fase 3, P9) quando il Baseline Profile esiste nei sorgenti
                                (app\src\main\generated\baselineProfiles), l'APK di release contiene
                                assets/dexopt/baseline.prof e AGP ha fuso le regole del profilo
    +  benchmark compilano      (Fase 3, P9) gli scenari di :benchmark e il dataset di app/src/benchmarkRelease
                                compilano: senza dispositivo e' tutto cio' che si puo' verificare
    +  -Bench                   (Fase 3, P9) le misure su dispositivo, SKIP senza dispositivo (vedi -Bench)

.PARAMETER SkipGradle
  Salta i passi 5-7 (e' il controllo statico, veloce: engine, repo, checksum, permessi ammessi vs docs).

.PARAMETER NoDaemon
  Esegue Gradle con --no-daemon (nessun processo che resta residente a fine verifica).

.PARAMETER Bench
  Fase 3, P9 (docs/fase3-progetto.md par. 2.6, D21; docs/bench/README.md): esegue le misure di prestazioni (Macrobenchmark:
  scorrimento, pinch, avvio a freddo, apertura del viewer, zoom a 200 MP) sul dispositivo collegato, sulla variante
  benchmarkRelease, e le confronta con benchmark\soglie.json e con il riferimento docs\bench\riferimento-<dispositivo>.json
  (tools\bench\compare.ps1). SENZA dispositivo il passo e' SKIP. Annota la frequenza dello schermo letta in sola lettura
  (getprop, dumpsys display, settings get): a 120 Hz serve "Fluidita' movimento: Adattiva". Su un emulatore e' uno smoke:
  gli scenari girano e basta, nessuna soglia si applica. Un dispositivo per volta.

.PARAMETER BenchSerial
  Il numero di serie del dispositivo (`adb devices`) quando ce n'e' piu' di uno.

.PARAMETER BenchStrict
  Con -Bench, una prova attesa senza risultato (saltata, non girata) o un tetto da calibrare e' un errore: e' la modalita'
  della chiusura della Fase 3. Senza, restano un avviso ("OK con riserve").

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\check.ps1
#>
[CmdletBinding()]
param(
  [switch]$SkipGradle,
  [switch]$NoDaemon,
  [switch]$Bench,
  [string]$BenchSerial,
  [switch]$BenchStrict
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $root

$script:results = New-Object System.Collections.Generic.List[object]
$script:failed = $false

# --- Utilita' ---------------------------------------------------------------------------------------------

function Read-TextFile($path) {
  return [System.IO.File]::ReadAllText($path, (New-Object System.Text.UTF8Encoding($false)))
}

# Esegue un comando nativo, mostra l'output in rientro (o solo la coda se e' lunghissimo) e restituisce
# codice e righe. 2>&1 e' sicuro qui: non si guarda $? ma $LASTEXITCODE, e ogni ErrorRecord diventa testo.
function Invoke-Native {
  param([string]$File, [string[]]$Arguments, [string]$LogPath)
  $previous = $ErrorActionPreference
  $ErrorActionPreference = "Continue"
  try {
    $lines = @(& $File @Arguments 2>&1 | ForEach-Object { $_.ToString() })
    $code = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $previous
  }
  if ($LogPath) {
    $dir = Split-Path -Parent $LogPath
    if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
    [System.IO.File]::WriteAllLines($LogPath, $lines)
  }
  return [pscustomobject]@{ Code = $code; Lines = $lines }
}

function Show-Lines($lines, [int]$tail = 0) {
  $shown = $lines
  if ($tail -gt 0 -and $lines.Count -gt $tail) {
    Write-Host ("        ... ({0} righe omesse)" -f ($lines.Count - $tail)) -ForegroundColor DarkGray
    $shown = $lines | Select-Object -Last $tail
  }
  foreach ($line in $shown) { Write-Host ("        " + $line) -ForegroundColor DarkGray }
}

# Un passo ritorna $null (tutto bene) oppure una stringa che spiega cosa non va. Un'eccezione e' un FAIL.
function Step {
  param([string]$Name, [scriptblock]$Body, [switch]$Skip, [string]$SkipReason)
  if ($Skip) {
    Write-Host ("[ -- ] {0}  ({1})" -f $Name, $SkipReason) -ForegroundColor DarkGray
    $script:results.Add([pscustomobject]@{ Name = $Name; Status = "SKIP"; Detail = $SkipReason; Seconds = 0 })
    return
  }
  Write-Host ("[ .. ] {0}" -f $Name) -ForegroundColor Cyan
  $watch = [System.Diagnostics.Stopwatch]::StartNew()
  $problem = $null
  try {
    $problem = & $Body
  } catch {
    $problem = "eccezione: " + $_.Exception.Message
  }
  $watch.Stop()
  $seconds = [math]::Round($watch.Elapsed.TotalSeconds, 1)
  if ($problem) {
    Write-Host ("[FAIL] {0}  -> {1}  ({2}s)" -f $Name, $problem, $seconds) -ForegroundColor Red
    $script:failed = $true
    $script:results.Add([pscustomobject]@{ Name = $Name; Status = "FAIL"; Detail = [string]$problem; Seconds = $seconds })
  } else {
    Write-Host ("[ OK ] {0}  ({1}s)" -f $Name, $seconds) -ForegroundColor Green
    $script:results.Add([pscustomobject]@{ Name = $Name; Status = "OK"; Detail = ""; Seconds = $seconds })
  }
}

# Gradle ha bisogno di un JDK 17: se JAVA_HOME non c'e' e java non e' nel PATH, prova dove Alessio lo tiene.
function Ensure-Java {
  if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin\java.exe"))) { return }
  if (Get-Command java -ErrorAction SilentlyContinue) { return }
  $candidate = Get-ChildItem "C:\Program Files\Microsoft" -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1
  if ($candidate) { $env:JAVA_HOME = $candidate.FullName }
}

function Get-Aapt2 {
  $sdk = $null
  $localProperties = Join-Path $root "local.properties"
  if (Test-Path -LiteralPath $localProperties) {
    $match = Select-String -LiteralPath $localProperties -Pattern '^\s*sdk\.dir\s*=\s*(.+)$' | Select-Object -First 1
    if ($match) {
      # Formato .properties: "C\:\\Users\\..." -> "C:\Users\..."
      $sdk = $match.Matches[0].Groups[1].Value.Trim().Replace("\:", ":").Replace("\\", "\")
    }
  }
  if (-not $sdk -and $env:ANDROID_HOME) { $sdk = $env:ANDROID_HOME }
  if (-not $sdk -and $env:ANDROID_SDK_ROOT) { $sdk = $env:ANDROID_SDK_ROOT }
  if (-not $sdk) { return $null }
  $tools = Get-ChildItem (Join-Path $sdk "build-tools") -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "aapt2.exe") } |
    Sort-Object { try { [version]($_.Name -replace '[^0-9.].*$', '') } catch { [version]"0.0" } } -Descending |
    Select-Object -First 1
  if (-not $tools) { return $null }
  return (Join-Path $tools.FullName "aapt2.exe")
}

# Legge il manifest DENTRO l'apk (non quello dei sorgenti, non quello fuso: quello che si installa).
function Get-ApkManifestFacts([string]$aapt2, [string]$apk) {
  $dump = Invoke-Native -File $aapt2 -Arguments @("dump", "xmltree", "--file", "AndroidManifest.xml", $apk)
  if ($dump.Code -ne 0) { throw "aapt2 dump xmltree fallito: " + ($dump.Lines -join " | ") }
  $facts = [ordered]@{ Debuggable = $false; AllowBackup = "(assente)"; Components = @() }
  $element = ""
  $current = $null
  foreach ($line in $dump.Lines) {
    if ($line -match '^\s*E: ([\w\-]+) ') {
      $element = $Matches[1]
      if (@("activity", "activity-alias", "service", "receiver", "provider") -contains $element) {
        $current = @{ Kind = $element; Name = ""; Exported = $false; Permission = "" }
        $facts.Components += , $current
      } else {
        $current = $null
      }
      continue
    }
    if ($line -match '^\s*A: .*android:([A-Za-z]+)\(0x[0-9a-fA-F]+\)=(.*)$') {
      $attr = $Matches[1]
      $value = $Matches[2].Trim()
      $isTrue = ($value -match '^true' -or $value -match '0xffffffff')
      if ($element -eq "application") {
        if ($attr -eq "debuggable") { $facts.Debuggable = $isTrue }
        if ($attr -eq "allowBackup") { $facts.AllowBackup = if ($isTrue) { "true" } else { "false" } }
      }
      if ($current) {
        if ($attr -eq "name" -and $value -match '^"([^"]*)"') { $current.Name = $Matches[1] }
        if ($attr -eq "exported") { $current.Exported = $isTrue }
        if ($attr -eq "permission" -and $value -match '^"([^"]*)"') { $current.Permission = $Matches[1] }
      }
    }
  }
  return $facts
}

# I componenti esportati ammessi da gradle\verify-release-manifest.gradle (stessa fonte di verifyReleaseManifest):
# nome -> permesso che il componente deve dichiarare ("" = nessun obbligo).
function Get-AllowedExported {
  $gradle = Read-TextFile (Join-Path $root "gradle\verify-release-manifest.gradle")
  $list = [regex]::Match($gradle, '(?s)allowedExported\s*:\s*\[(.*?)\n\s*\],')
  if (-not $list.Success) { throw "non trovo allowedExported in gradle\verify-release-manifest.gradle" }
  $allowed = @{}
  foreach ($line in ($list.Groups[1].Value -split '\r?\n')) {
    $code = ($line -replace '//.*$', '').Trim()
    foreach ($m in [regex]::Matches($code, "'([^']+)'")) { $allowed[$m.Groups[1].Value] = "" }
  }
  $map = [regex]::Match($gradle, '(?s)exportedRequiresPermission\s*:\s*\[(.*?)\n\s*\],')
  if ($map.Success) {
    foreach ($line in ($map.Groups[1].Value -split '\r?\n')) {
      $code = ($line -replace '//.*$', '').Trim()
      $pair = [regex]::Match($code, "'([^']+)'\s*:\s*'([^']+)'")
      if ($pair.Success) { $allowed[$pair.Groups[1].Value] = $pair.Groups[2].Value }
    }
  }
  if ($allowed.Count -eq 0) { throw "allowedExported e' vuoto in gradle\verify-release-manifest.gradle" }
  return $allowed
}

# Le classi definite nei classes*.dex di un APK che corrispondono a [pattern] (un'espressione regolare sui
# descrittori, es. Ldev/pampa/mycloudgallery/core/nas/testing/FakeNasSessionManager;). Legge i byte del dex: i
# descrittori delle classi sono stringhe ASCII nella tabella delle stringhe, quindi una lettura Latin-1 li conserva.
# Controllo positivo: deve trovare la MainActivity, altrimenti la lettura non e' affidabile e si lancia.
function Get-DexClassesMatching([string]$apk, [string]$pattern) {
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zip = [System.IO.Compression.ZipFile]::OpenRead($apk)
  try {
    $dexes = @($zip.Entries | Where-Object { $_.FullName -match '^classes\d*\.dex$' })
    if ($dexes.Count -eq 0) { throw "nessun classes*.dex nell'APK" }
    $latin1 = [System.Text.Encoding]::GetEncoding(28591)
    $found = @{}
    $sawMain = $false
    foreach ($entry in $dexes) {
      $stream = $entry.Open()
      try {
        $buffer = New-Object System.IO.MemoryStream
        $stream.CopyTo($buffer)
      } finally {
        $stream.Dispose()
      }
      $text = $latin1.GetString($buffer.ToArray())
      if ($text.Contains("Ldev/pampa/mycloudgallery/MainActivity;")) { $sawMain = $true }
      foreach ($m in [regex]::Matches($text, $pattern)) { $found[$m.Value] = $true }
    }
    if (-not $sawMain) { throw "lettura del dex non affidabile: la MainActivity non compare in nessun classes*.dex" }
    return @($found.Keys | Sort-Object)
  } finally {
    $zip.Dispose()
  }
}

# Le classi di un package `testing` (i fake dei contratti, D6 di Fase 2 e D23 di Fase 3).
function Get-TestingClassesInApk([string]$apk) {
  return Get-DexClassesMatching $apk 'L(?:[A-Za-z0-9_$]+/)+testing/[A-Za-z0-9_$/]*;'
}

# La misura (Fase 3, D21): classi `Bench*` (l'inizializzatore del dataset sintetico della variante benchmarkRelease),
# un package `benchmark`, androidx.benchmark e UiAutomator. Nessuna deve stare nella release.
function Get-BenchmarkClassesInApk([string]$apk) {
  return Get-DexClassesMatching $apk 'L(?:[A-Za-z0-9_$]+/)*(?:Bench[A-Za-z0-9_$]*;|benchmark/[A-Za-z0-9_$/]*;)|Landroidx/test/uiautomator/[A-Za-z0-9_$/]*;'
}

# I permessi documentati in docs/sicurezza.md 4-bis: i nomi in maiuscolo tra apici inversi (es. `VIBRATE`),
# esclusa la riga "Non richiesti" (i permessi che l'app promette di NON avere non contano come documentati).
function Get-DocumentedPermissions {
  $text = Read-TextFile (Join-Path $root "docs\sicurezza.md")
  $start = $text.IndexOf("### 4-bis")
  if ($start -lt 0) { throw "docs\sicurezza.md non ha la sezione '### 4-bis'" }
  $rest = $text.Substring($start + 9)
  $end = [regex]::Match($rest, '(?m)^(---|## )')
  $section = if ($end.Success) { $rest.Substring(0, $end.Index) } else { $rest }
  $names = @{}
  foreach ($line in ($section -split '\r?\n')) {
    if ($line -match 'Non richiesti') { continue }
    foreach ($m in [regex]::Matches($line, '`([A-Z][A-Z0-9_]+)`')) { $names[$m.Groups[1].Value] = $true }
  }
  if ($names.Count -eq 0) { throw "la sezione 4-bis di docs\sicurezza.md non elenca nessun permesso" }
  return $names
}

# Un permesso (nome completo) e' documentato se il suo nome corto (dopo l'ultimo punto) e' nella sezione 4-bis.
# L'unico permesso ammesso senza riga e' quello `signature` che androidx.core aggiunge ai receiver dinamici.
function Get-UndocumentedPermissions($permissions, $documented) {
  $missing = @()
  foreach ($permission in $permissions) {
    if ($permission -match '\.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION$') { continue }
    $short = $permission.Substring($permission.LastIndexOf('.') + 1)
    if (-not $documented.ContainsKey($short)) { $missing += $permission }
  }
  return $missing
}

Write-Host ""
Write-Host "MyCloud Gallery - verifica completa" -ForegroundColor Cyan
Write-Host "  cartella: $root"
Write-Host ""

# --- 1. engine-doctor -------------------------------------------------------------------------------------
Step "engine-doctor (engine agganciato alla versione dichiarata)" {
  $doctor = Join-Path $root "engine\tools\engine-doctor.ps1"
  if (-not (Test-Path -LiteralPath $doctor)) { return "manca engine\tools\engine-doctor.ps1: submodule non inizializzato? (git submodule update --init)" }
  $run = Invoke-Native -File "powershell" -Arguments @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $doctor, "-AppRoot", ".")
  Show-Lines $run.Lines
  if ($run.Code -ne 0) { return "engine-doctor ha segnalato problemi (codice $($run.Code))" }
}

# --- 2. engine pulito -------------------------------------------------------------------------------------
Step "engine senza modifiche locali" {
  $status = Invoke-Native -File "git" -Arguments @("-C", "engine", "status", "--porcelain")
  if ($status.Code -ne 0) { return "git non risponde dentro engine/" }
  $dirty = @($status.Lines | Where-Object { $_.Trim().Length -gt 0 })
  if ($dirty.Count -gt 0) {
    Show-Lines $dirty 10
    return "engine/ ha $($dirty.Count) modifiche non committate: o vanno nel repo dell'engine o si perdono"
  }
}

# --- 3. repo senza segreti ---------------------------------------------------------------------------------
Step "repo senza segreti (chiavi di firma, local.properties)" {
  $files = Invoke-Native -File "git" -Arguments @("ls-files", "--cached", "--others", "--exclude-standard")
  if ($files.Code -ne 0) { return "git ls-files non risponde" }
  $bad = @($files.Lines | Where-Object { $_ -match '(\.jks|\.keystore|\.p12|\.pfx|\.pem)$' -or $_ -match '(^|/)local\.properties$' -or $_ -match '(^|/)keystore\.properties$' })
  if ($bad.Count -gt 0) {
    Show-Lines $bad 10
    return "file sensibili tracciati o non ignorati: " + ($bad -join ", ")
  }
}

# --- 4. verifica dei checksum -----------------------------------------------------------------------------
Step "verifica checksum delle dipendenze attiva" {
  $metadata = Join-Path $root "gradle\verification-metadata.xml"
  if (-not (Test-Path -LiteralPath $metadata)) { return "manca gradle\verification-metadata.xml (vedi README: come rigenerarlo)" }
  $xml = Read-TextFile $metadata
  if ($xml -notmatch '<verify-metadata>\s*true\s*</verify-metadata>') { return "verification-metadata.xml non ha <verify-metadata>true</verify-metadata>" }
  $components = ([regex]::Matches($xml, '<component ')).Count
  if ($components -lt 50) { return "verification-metadata.xml ha solo $components componenti: e' vuoto o troncato" }
  $properties = Read-TextFile (Join-Path $root "gradle.properties")
  if ($properties -match '(?im)^\s*org\.gradle\.dependency\.verification\s*=\s*(off|lenient)') { return "gradle.properties allenta la verifica dei checksum" }
  $wrapper = Read-TextFile (Join-Path $root "gradle\wrapper\gradle-wrapper.properties")
  if ($wrapper -notmatch '(?m)^distributionSha256Sum=[0-9a-f]{64}') { return "il wrapper non fissa distributionSha256Sum" }
}

# --- permessi: allowlist del manifest vs docs/sicurezza.md 4-bis (statico) ----------------------------------
Step "permessi ammessi dal controllo del manifest elencati in docs/sicurezza.md 4-bis" {
  $documented = Get-DocumentedPermissions
  $gradle = Read-TextFile (Join-Path $root "gradle\verify-release-manifest.gradle")
  $block = [regex]::Match($gradle, '(?s)allowedPermissions\s*:\s*\[(.*?)\n\s*\],')
  if (-not $block.Success) { return "non trovo allowedPermissions in gradle\verify-release-manifest.gradle" }
  $entries = @()
  foreach ($line in ($block.Groups[1].Value -split '\r?\n')) {
    $code = ($line -replace '//.*$', '').Trim()
    foreach ($m in [regex]::Matches($code, "'([^']+)'")) { $entries += $m.Groups[1].Value }
  }
  $missing = Get-UndocumentedPermissions $entries $documented
  if ($missing.Count -gt 0) {
    return "permessi ammessi in verify-release-manifest.gradle ma senza riga in docs\sicurezza.md 4-bis: " + ($missing -join ", ")
  }
}

# --- 5-7. gradle e APK ------------------------------------------------------------------------------------
$gradleOk = $false
Step "gradle: assembleDebug assembleRelease galleryTest galleryLint" -Skip:$SkipGradle -SkipReason "-SkipGradle" {
  Ensure-Java
  $arguments = @("--console=plain", "-q", "assembleDebug", "assembleRelease", "galleryTest", "galleryLint")
  if ($NoDaemon) { $arguments = @("--no-daemon") + $arguments }
  Write-Host "        (puo' volerci qualche minuto; il log completo e' in build\check-gradle.log)" -ForegroundColor DarkGray
  $run = Invoke-Native -File (Join-Path $root "gradlew.bat") -Arguments $arguments -LogPath (Join-Path $root "build\check-gradle.log")
  if ($run.Code -ne 0) {
    Show-Lines $run.Lines 60
    return "gradle e' fallito (codice $($run.Code))"
  }
  $script:gradleOk = $true
}

# I moduli senza unit test per costruzione: `:benchmark` e' un `com.android.test` (Macrobenchmark, D21) che non ha un
# task testDebugUnitTest e gira solo da un dispositivo. Tutti gli altri devono avere eseguito almeno un test.
$modulesWithoutUnitTests = @("benchmark")
$modules = @()
foreach ($line in (Get-Content -LiteralPath (Join-Path $root "settings.gradle"))) {
  if ($line -match "^\s*include\s+':([A-Za-z0-9\-]+)'" -and $modulesWithoutUnitTests -notcontains $Matches[1]) { $modules += $Matches[1] }
}

Step "test eseguiti in ogni modulo (nessuno a zero, nessuno fallito)" -Skip:(-not $gradleOk) -SkipReason "gradle non completato" {
  $problems = @()
  $summary = @()
  foreach ($module in $modules) {
    $dir = Join-Path $root "$module\build\test-results\testDebugUnitTest"
    $tests = 0; $bad = 0
    if (Test-Path -LiteralPath $dir) {
      foreach ($file in Get-ChildItem $dir -Filter "*.xml") {
        $head = (Get-Content -LiteralPath $file.FullName -TotalCount 3) -join " "
        if ($head -match 'tests="(\d+)"') { $tests += [int]$Matches[1] }
        if ($head -match 'failures="(\d+)"') { $bad += [int]$Matches[1] }
        if ($head -match 'errors="(\d+)"') { $bad += [int]$Matches[1] }
      }
    }
    $summary += ("{0}={1}" -f $module, $tests)
    if ($tests -eq 0) { $problems += "${module}: ZERO test eseguiti" }
    if ($bad -gt 0) { $problems += "${module}: ${bad} falliti" }
  }
  Write-Host ("        test per modulo: " + ($summary -join "  ")) -ForegroundColor DarkGray
  if ($problems.Count -gt 0) { return ($problems -join "; ") }
}

Step "APK di release: non debuggable, backup escluso, nessun componente esposto" -Skip:(-not $gradleOk) -SkipReason "gradle non completato" {
  $aapt2 = Get-Aapt2
  if (-not $aapt2) { return "aapt2 non trovato: controlla sdk.dir in local.properties e le build-tools dell'SDK" }
  $apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk\release") -Filter "*.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $apk) { return "nessun APK di release in app\build\outputs\apk\release" }
  $signed = ($apk.Name -notmatch "unsigned")
  Write-Host ("        apk: {0} ({1})" -f $apk.Name, $(if ($signed) { "firmato" } else { "non firmato: mancano le chiavi pampa.* in local.properties, e' previsto" })) -ForegroundColor DarkGray

  $problems = @()
  $badging = Invoke-Native -File $aapt2 -Arguments @("dump", "badging", $apk.FullName)
  if ($badging.Code -ne 0) { return "aapt2 dump badging fallito" }
  if ($badging.Lines -match '^application-debuggable') { $problems += "l'APK di release e' DEBUGGABLE (application-debuggable)" }

  $facts = Get-ApkManifestFacts $aapt2 $apk.FullName
  if ($facts.Debuggable) { $problems += "android:debuggable=true nel manifest dell'APK" }
  if ($facts.AllowBackup -ne "false") { $problems += "android:allowBackup nell'APK e' '$($facts.AllowBackup)', deve essere false" }
  $exported = @($facts.Components | Where-Object { $_.Exported })
  $allowedExported = Get-AllowedExported
  foreach ($component in $exported) {
    if (-not $allowedExported.ContainsKey($component.Name)) {
      $problems += ("componente esportato non ammesso: <{0}> {1}" -f $component.Kind, $component.Name)
    } elseif ($allowedExported[$component.Name] -and $component.Permission -ne $allowedExported[$component.Name]) {
      $problems += ("<{0}> {1} e' esportato senza il permesso {2}" -f $component.Kind, $component.Name, $allowedExported[$component.Name])
    }
  }
  # Fase 3: il ProfileInstallReceiver (Macrobenchmark) non deve esserci neanche non esportato.
  foreach ($component in @($facts.Components | Where-Object { $_.Name -eq "androidx.profileinstaller.ProfileInstallReceiver" })) {
    $problems += "il ProfileInstallReceiver e' nella release (<{0}>): torna solo nella variante benchmarkRelease" -f $component.Kind
  }
  if (-not ($exported | Where-Object { $_.Name -eq "dev.pampa.mycloudgallery.MainActivity" })) {
    $problems += "la launcher activity non risulta esportata: il lettore del manifest non funziona o l'app non si avvia"
  }
  Write-Host ("        componenti: {0}, esportati: {1}" -f @($facts.Components).Count, (($exported | ForEach-Object { $_.Name }) -join ", ")) -ForegroundColor DarkGray

  if ($problems.Count -gt 0) { return ($problems -join "; ") }
}

Step "nessuna classe di un package testing nel dex di release (fake solo per i test)" -Skip:(-not $gradleOk) -SkipReason "gradle non completato" {
  $apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk\release") -Filter "*.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $apk) { return "nessun APK di release in app\build\outputs\apk\release" }
  $testing = @(Get-TestingClassesInApk $apk.FullName)
  if ($testing.Count -gt 0) {
    Show-Lines $testing 20
    return "$($testing.Count) classi di un package testing nel dex di release: i fake (D6) non devono arrivare all'APK"
  }
}

# I resti degli strumenti di debug (I4): nomi di classi e stringhe che nella release non devono esserci. Il passo legge il dex
# direttamente (come i passi dei fake e della misura): se non lo legge FALLISCE, non salta (prima usava apkanalyzer e, senza,
# saltava in silenzio).
$debugRemnants = @(
  # Il sink di logcat e il catalogo dei componenti (Fase 1/2).
  "LogcatSink", "ComponentCatalogPlaceholder", "core/designsystem/catalog", "DeveloperEntryPoint", "CatalogRoute",
  # La famiglia "pronta" per lo smoke sull'emulatore: il marcatore scritto da adb e la famiglia finta (DeveloperTools di debug).
  "debug-family-ready", "DeveloperTools`$ReadyFamily",
  # La Libreria sintetica (M1): il kit, i nomi del suo database e della sua istantanea, la manopola che sceglie la dimensione.
  "SyntheticCatalogKit", "gallery-synthetic", "replica-synthetic", "libreria-sintetica", "m1-synthetic-count", "DebugSyntheticSize",
  "LibraryDebugHooks"
)
Step "nessun resto degli strumenti di debug nel dex di release (Libreria sintetica, famiglia pronta, sink di logcat)" -Skip:(-not $gradleOk) -SkipReason "gradle non completato" {
  $apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk\release") -Filter "*.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $apk) { return "nessun APK di release in app\build\outputs\apk\release" }
  $pattern = ($debugRemnants | ForEach-Object { [regex]::Escape($_) }) -join "|"
  $found = @(Get-DexClassesMatching $apk.FullName $pattern)
  if ($found.Count -gt 0) {
    Show-Lines $found 20
    return "$($found.Count) resti di strumenti di debug nel dex di release: " + ($found -join ", ")
  }
  Write-Host ("        cercati {0} nomi, nessuno nel dex" -f $debugRemnants.Count) -ForegroundColor DarkGray
}

# R6: nessun file di servizio nostro nell'APK di release. `ServiceLoader` e' un canale dichiarativo e invisibile: un file in
# META-INF/services basta a sostituire una classe (la famiglia "pronta", la sincronizzazione ferma) senza toccare il codice.
# Le varianti di misura (benchmarkRelease, nonMinifiedRelease) legano le loro classi con un modulo Hilt, quindi la release
# non deve averne nemmeno uno.
Step "APK di release: nessun file di servizio dev.pampa.* in META-INF/services" -Skip:(-not $gradleOk) -SkipReason "gradle non completato" {
  $apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk\release") -Filter "*.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $apk) { return "nessun APK di release in app\build\outputs\apk\release" }
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zip = [System.IO.Compression.ZipFile]::OpenRead($apk.FullName)
  try {
    $names = @($zip.Entries | ForEach-Object { $_.FullName })
    # Controllo positivo: se l'elenco non e' quello di un APK la lettura non e' affidabile.
    if (-not ($names -contains "AndroidManifest.xml") -or -not ($names | Where-Object { $_ -match '^classes\d*\.dex$' })) {
      return "lettura dell'APK non affidabile: mancano AndroidManifest.xml o classes.dex"
    }
    $services = @($zip.Entries | Where-Object { $_.FullName -like "META-INF/services/*" -and -not $_.FullName.EndsWith("/") })
    $problems = @()
    foreach ($entry in $services) {
      if ($entry.Name -match '^dev\.pampa\.') { $problems += "file di servizio nostro: " + $entry.FullName; continue }
      # Anche un servizio con il nome di un'interfaccia di Java o di una libreria non deve registrare una classe nostra.
      $reader = New-Object System.IO.StreamReader($entry.Open(), [System.Text.Encoding]::UTF8)
      try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
      if ($text -match 'dev\.pampa\.') { $problems += ($entry.FullName + " registra una classe dev.pampa.*") }
    }
    Write-Host ("        servizi nell'APK: {0}" -f $(if ($services.Count -gt 0) { ($services | ForEach-Object { $_.Name }) -join ", " } else { "nessuno" })) -ForegroundColor DarkGray
    if ($problems.Count -gt 0) { return ($problems -join "; ") }
  } finally {
    $zip.Dispose()
  }
}

Step "nessuna classe di misura (Bench*, benchmark, UiAutomator) nel dex di release" -Skip:(-not $gradleOk) -SkipReason "gradle non completato" {
  $apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk\release") -Filter "*.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $apk) { return "nessun APK di release in app\build\outputs\apk\release" }
  $benchClasses = @(Get-BenchmarkClassesInApk $apk.FullName)
  if ($benchClasses.Count -gt 0) {
    Show-Lines $benchClasses 20
    return "$($benchClasses.Count) classi di misura nel dex di release: la variante benchmarkRelease non deve mai arrivare all'APK (D21)"
  }
}

Step "permessi dell'APK di release tutti elencati in docs/sicurezza.md 4-bis" -Skip:(-not $gradleOk) -SkipReason "gradle non completato" {
  $aapt2 = Get-Aapt2
  if (-not $aapt2) { return "aapt2 non trovato: controlla sdk.dir in local.properties e le build-tools dell'SDK" }
  $apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk\release") -Filter "*.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $apk) { return "nessun APK di release in app\build\outputs\apk\release" }
  $dump = Invoke-Native -File $aapt2 -Arguments @("dump", "permissions", $apk.FullName)
  if ($dump.Code -ne 0) { return "aapt2 dump permissions fallito" }
  $permissions = @()
  foreach ($line in $dump.Lines) {
    if ($line -match "^uses-permission[\w\-]*: name='([^']+)'") { $permissions += $Matches[1] }
  }
  Write-Host ("        permessi nell'APK: " + (($permissions | Sort-Object) -join ", ")) -ForegroundColor DarkGray
  $missing = Get-UndocumentedPermissions $permissions (Get-DocumentedPermissions)
  if ($missing.Count -gt 0) {
    return "permessi nell'APK senza riga in docs\sicurezza.md 4-bis: " + ($missing -join ", ")
  }
}

# --- Fase 3, P9: Baseline Profile e prestazioni misurate (docs/fase3-progetto.md par. 2.6, D21) --------------------------

# Il Baseline Profile nei sorgenti, se esiste (lo scrive :app:generateBaselineProfile o tools\bench\generate-profile.ps1).
function Get-BaselineProfileSource {
  foreach ($relative in @("app\src\main\generated\baselineProfiles\baseline-prof.txt", "app\src\release\generated\baselineProfiles\baseline-prof.txt", "app\src\main\baseline-prof.txt")) {
    $path = Join-Path $root $relative
    if (Test-Path -LiteralPath $path) { return $path }
  }
  return $null
}

# Le regole di un profilo testuale: tutte le righe che non sono vuote ne' commenti.
function Get-ProfileRules([string]$path) {
  return @(Get-Content -LiteralPath $path | Where-Object { $_.Trim().Length -gt 0 -and -not $_.TrimStart().StartsWith("#") })
}

function Get-SdkDir {
  $sdk = $null
  $localProperties = Join-Path $root "local.properties"
  if (Test-Path -LiteralPath $localProperties) {
    $match = Select-String -LiteralPath $localProperties -Pattern '^\s*sdk\.dir\s*=\s*(.+)$' | Select-Object -First 1
    if ($match) { $sdk = $match.Matches[0].Groups[1].Value.Trim().Replace("\:", ":").Replace("\\", "\") }
  }
  if (-not $sdk -and $env:ANDROID_HOME) { $sdk = $env:ANDROID_HOME }
  if (-not $sdk -and $env:ANDROID_SDK_ROOT) { $sdk = $env:ANDROID_SDK_ROOT }
  return $sdk
}

function Get-Adb {
  $sdk = Get-SdkDir
  if ($sdk) {
    $path = Join-Path $sdk "platform-tools\adb.exe"
    if (Test-Path -LiteralPath $path) { return $path }
  }
  $command = Get-Command adb -ErrorAction SilentlyContinue
  if ($command) { return $command.Source }
  return $null
}

# I numeri di serie dei dispositivi collegati e pronti (`adb devices`), nell'ordine di adb.
function Get-AdbSerials([string]$adb) {
  $run = Invoke-Native -File $adb -Arguments @("devices")
  $serials = @()
  foreach ($line in $run.Lines) {
    if ($line -match '^(\S+)\s+device$') { $serials += $Matches[1] }
  }
  return $serials
}

# I fatti del dispositivo, letti in SOLA LETTURA (getprop, dumpsys, settings get): niente si cambia mai sul telefono.
# La frequenza dello schermo conta perche' a 120 Hz serve "Fluidita' movimento: Adattiva" (par. 2.6): si leggono i modi
# del display (il massimo disponibile e l'attivo) e le tre impostazioni che la decidono; il giudizio e' di chi legge.
function Get-DeviceFacts([string]$adb, [string]$serial) {
  $read = {
    param([string]$command)
    $run = Invoke-Native -File $adb -Arguments @("-s", $serial, "shell", $command)
    return (($run.Lines -join "`n").Trim())
  }
  $facts = [ordered]@{ Serial = $serial; Model = ""; Release = ""; Sdk = ""; Emulator = $false; ActiveHz = $null; MaxHz = $null; Settings = "" }
  $facts.Model = & $read "getprop ro.product.model"
  $facts.Release = & $read "getprop ro.build.version.release"
  $facts.Sdk = & $read "getprop ro.build.version.sdk"
  $facts.Emulator = ($serial -like "emulator-*") -or ((& $read "getprop ro.kernel.qemu") -eq "1") -or ((& $read "getprop ro.boot.qemu") -eq "1")
  $display = & $read "dumpsys display"
  $modes = @{}
  # I modi del display: "{id=1, [parentModeId=-1, sfModeId=0, flags=,] width=1080, height=2400, fps=60.0, ...}" (i campi
  # in mezzo cambiano da una versione di Android all'altra).
  foreach ($m in [regex]::Matches($display, '\bid=(\d+),[^{}]*?\bwidth=\d+,\s*height=\d+,\s*fps=([0-9.]+)')) {
    $modes[$m.Groups[1].Value] = [double]::Parse($m.Groups[2].Value, [System.Globalization.CultureInfo]::InvariantCulture)
  }
  if ($modes.Count -gt 0) { $facts.MaxHz = ($modes.Values | Measure-Object -Maximum).Maximum }
  $render = [regex]::Match($display, 'mActiveRenderFrameRate=([0-9.]+)')
  $active = [regex]::Match($display, 'mActiveModeId=(\d+)')
  if ($render.Success) {
    $facts.ActiveHz = [double]::Parse($render.Groups[1].Value, [System.Globalization.CultureInfo]::InvariantCulture)
  } elseif ($active.Success -and $modes.ContainsKey($active.Groups[1].Value)) {
    $facts.ActiveHz = $modes[$active.Groups[1].Value]
  }
  $peak = & $read "settings get system peak_refresh_rate"
  $min = & $read "settings get system min_refresh_rate"
  $mode = & $read "settings get secure refresh_rate_mode"
  $facts.Settings = "peak_refresh_rate=$peak min_refresh_rate=$min refresh_rate_mode=$mode"
  return $facts
}

Step "Baseline Profile: se esiste nei sorgenti, l'APK di release lo contiene e AGP l'ha unito" -Skip:(-not $gradleOk) -SkipReason "gradle non completato" {
  $source = Get-BaselineProfileSource
  if (-not $source) {
    Write-Host "        nessun profilo nei sorgenti (app\src\main\generated\baselineProfiles): da generare con :app:generateBaselineProfile o tools\bench\generate-profile.ps1" -ForegroundColor Yellow
    return
  }
  $rules = @(Get-ProfileRules $source)
  if ($rules.Count -lt 50) { return "il profilo nei sorgenti ha solo $($rules.Count) regole: vuoto o troncato ($source)" }
  $apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk\release") -Filter "*.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $apk) { return "nessun APK di release in app\build\outputs\apk\release" }
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zip = [System.IO.Compression.ZipFile]::OpenRead($apk.FullName)
  try {
    $prof = $zip.Entries | Where-Object { $_.FullName -eq "assets/dexopt/baseline.prof" } | Select-Object -First 1
    $profm = $zip.Entries | Where-Object { $_.FullName -eq "assets/dexopt/baseline.profm" } | Select-Object -First 1
    $profSize = if ($prof) { $prof.Length } else { 0 }
    $profmSize = if ($profm) { $profm.Length } else { 0 }
  } finally {
    $zip.Dispose()
  }
  if (-not $prof) { return "l'APK di release non contiene assets/dexopt/baseline.prof pur esistendo il profilo nei sorgenti" }
  # Le librerie (Compose, Room...) portano profili propri e producono un baseline.prof anche senza il nostro: l'unione
  # si verifica sul testo che AGP ha fuso, che deve contenere (quasi) tutte le nostre regole.
  $merged = Join-Path $root "app\build\intermediates\merged_art_profile\release\mergeReleaseArtProfile\baseline-prof.txt"
  $note = ""
  if (Test-Path -LiteralPath $merged) {
    $set = New-Object System.Collections.Generic.HashSet[string]
    foreach ($line in (Get-Content -LiteralPath $merged)) { [void]$set.Add($line.Trim()) }
    $absent = @($rules | Where-Object { -not $set.Contains($_.Trim()) })
    if ($absent.Count -gt [math]::Ceiling($rules.Count * 0.05)) {
      return "AGP non ha unito il profilo: $($absent.Count) di $($rules.Count) regole non compaiono nel profilo fuso della release"
    }
    $note = ", unione verificata"
  } else {
    $note = ", file fuso non trovato (unione non verificata)"
  }
  Write-Host ("        profilo: {0} regole; APK: baseline.prof {1} byte, baseline.profm {2} byte{3}" -f $rules.Count, $profSize, $profmSize, $note) -ForegroundColor DarkGray
}

Step "benchmark: gli scenari (:benchmark) e il dataset (app/src/benchmarkRelease) compilano" -Skip:$SkipGradle -SkipReason "-SkipGradle" {
  Ensure-Java
  $arguments = @("--console=plain", "-q", ":app:compileBenchmarkReleaseKotlin", ":benchmark:compileBenchmarkReleaseKotlin")
  if ($NoDaemon) { $arguments = @("--no-daemon") + $arguments }
  $run = Invoke-Native -File (Join-Path $root "gradlew.bat") -Arguments $arguments -LogPath (Join-Path $root "build\check-bench-compile.log")
  if ($run.Code -ne 0) {
    Show-Lines $run.Lines 40
    return "la variante benchmarkRelease non compila (codice $($run.Code)); log in build\check-bench-compile.log"
  }
}

# -Bench: le misure del par. 2.6 su un dispositivo collegato. Senza -Bench o senza dispositivo il passo e' SKIP (non fallisce).
$benchAdb = $null
$benchTarget = $null
$benchSkip = -not $Bench
$benchReason = "senza -Bench"
if ($Bench) {
  $benchAdb = Get-Adb
  if (-not $benchAdb) {
    $benchSkip = $true
    $benchReason = "adb non trovato (sdk.dir in local.properties)"
  } else {
    $serials = @(Get-AdbSerials $benchAdb)
    if ($BenchSerial) { $serials = @($serials | Where-Object { $_ -eq $BenchSerial }) }
    if ($serials.Count -eq 0) {
      $benchSkip = $true
      $benchReason = "nessun dispositivo collegato"
    } elseif ($serials.Count -eq 1) {
      $benchTarget = $serials[0]
    } else {
      $benchTarget = ""   # piu' dispositivi: il passo fallisce chiedendo -BenchSerial (un dispositivo per volta)
    }
  }
}
Step "benchmark su dispositivo (-Bench): scenari del par. 2.6 contro benchmark\soglie.json e riferimento" -Skip:$benchSkip -SkipReason $benchReason {
  if ($benchTarget -eq "") { return "piu' dispositivi collegati: indica quale con -BenchSerial <numero di serie> (si misura un dispositivo per volta)" }
  $facts = Get-DeviceFacts $benchAdb $benchTarget
  $emulatorNote = if ($facts.Emulator) { " - EMULATORE: smoke, i numeri non valgono" } else { "" }
  Write-Host ("        dispositivo: {0} ({1}), Android {2} (SDK {3}){4}" -f $facts.Model, $benchTarget, $facts.Release, $facts.Sdk, $emulatorNote) -ForegroundColor DarkGray
  $hz = if ($null -ne $facts.MaxHz) { $facts.MaxHz } else { 0 }
  $activeText = if ($null -ne $facts.ActiveHz) { $facts.ActiveHz } else { "?" }
  $maxText = if ($null -ne $facts.MaxHz) { $facts.MaxHz } else { "?" }
  Write-Host ("        schermo (sola lettura): attivo {0} Hz, massimo {1} Hz; {2}" -f $activeText, $maxText, $facts.Settings) -ForegroundColor DarkGray
  if (-not $facts.Emulator) {
    Write-Host "        a 120 Hz serve 'Fluidita' movimento: Adattiva' (Impostazioni > Display), batteria carica, schermo acceso e sbloccato" -ForegroundColor DarkGray
  }
  Ensure-Java
  $gradleArguments = @(
    "--console=plain", ":benchmark:connectedBenchmarkReleaseAndroidTest",
    "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=macrobenchmark"
  )
  if ($facts.Emulator) {
    $gradleArguments += "-Pandroid.testInstrumentationRunnerArguments.benchSmoke=true"
    $gradleArguments += "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR,LOW-BATTERY,NOT-PROFILEABLE"
  }
  if ($NoDaemon) { $gradleArguments = @("--no-daemon") + $gradleArguments }
  Write-Host "        (puo' volerci diversi minuti; il log completo e' in build\check-bench.log)" -ForegroundColor DarkGray
  # Un dispositivo per volta: Gradle gira su tutti quelli collegati, a meno di ANDROID_SERIAL.
  $previousSerial = $env:ANDROID_SERIAL
  $env:ANDROID_SERIAL = $benchTarget
  try {
    $run = Invoke-Native -File (Join-Path $root "gradlew.bat") -Arguments $gradleArguments -LogPath (Join-Path $root "build\check-bench.log")
  } finally {
    $env:ANDROID_SERIAL = $previousSerial
  }
  if ($run.Code -ne 0) {
    Show-Lines $run.Lines 60
    $hint = "vedi build\check-bench.log"
    if (@($run.Lines | Where-Object { $_ -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' }).Count -gt 0) {
      $hint = "la release firmata e' installata con un'altra firma: disinstallarla prima (docs/bench/README.md)"
    }
    return "il benchmark e' fallito (codice $($run.Code)): $hint"
  }
  $compare = Join-Path $root "tools\bench\compare.ps1"
  $compareArguments = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $compare, "-RefreshHz", ([string]$hz).Replace(",", "."))
  if ($facts.Emulator) { $compareArguments += "-Smoke" }
  if ($BenchStrict) { $compareArguments += "-RequireAll" }
  $result = Invoke-Native -File "powershell" -Arguments $compareArguments -LogPath (Join-Path $root "build\check-bench-compare.log")
  Show-Lines $result.Lines
  if ($result.Code -ne 0) { return "i risultati non rispettano le soglie (codice $($result.Code)); dettaglio sopra e in build\check-bench-compare.log" }
}

# --- Riepilogo ---------------------------------------------------------------------------------------------
Write-Host ""
Write-Host "Riepilogo" -ForegroundColor Cyan
foreach ($result in $script:results) {
  $color = switch ($result.Status) { "OK" { "Green" } "FAIL" { "Red" } default { "DarkGray" } }
  Write-Host ("  {0,-4}  {1}" -f $result.Status, $result.Name) -ForegroundColor $color
  if ($result.Status -eq "FAIL") { Write-Host ("        {0}" -f $result.Detail) -ForegroundColor Red }
}
Write-Host ""
if ($script:failed) {
  Write-Host "FAIL: c'e' almeno un controllo che non passa." -ForegroundColor Red
  exit 1
}
Write-Host "OK: tutto a posto." -ForegroundColor Green
exit 0
