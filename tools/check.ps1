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
                                ZERO test eseguiti risulterebbe "passato" per Gradle: qui no)
    7. APK di release           ispezione dell'APK vero con aapt2: non debuggable, backup escluso, nessun
                                componente esportato oltre la launcher, nessun codice solo-debug dentro
    +  permessi documentati     ogni permesso ammesso da gradle\verify-release-manifest.gradle (statico) e ogni
                                permesso dell'APK di release deve comparire in docs\sicurezza.md 4-bis

.PARAMETER SkipGradle
  Salta i passi 5-7 (e' il controllo statico, veloce: engine, repo, checksum, permessi ammessi vs docs).

.PARAMETER NoDaemon
  Esegue Gradle con --no-daemon (nessun processo che resta residente a fine verifica).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\check.ps1
#>
[CmdletBinding()]
param(
  [switch]$SkipGradle,
  [switch]$NoDaemon
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
        $current = @{ Kind = $element; Name = ""; Exported = $false }
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
      }
    }
  }
  return $facts
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

$modules = @()
foreach ($line in (Get-Content -LiteralPath (Join-Path $root "settings.gradle"))) {
  if ($line -match "^\s*include\s+':([A-Za-z0-9\-]+)'") { $modules += $Matches[1] }
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
  foreach ($component in $exported) {
    if ($component.Name -ne "dev.pampa.mycloudgallery.MainActivity") {
      $problems += ("componente esportato non ammesso: <{0}> {1}" -f $component.Kind, $component.Name)
    }
  }
  if (-not ($exported | Where-Object { $_.Name -eq "dev.pampa.mycloudgallery.MainActivity" })) {
    $problems += "la launcher activity non risulta esportata: il lettore del manifest non funziona o l'app non si avvia"
  }
  Write-Host ("        componenti: {0}, esportati: {1}" -f @($facts.Components).Count, (($exported | ForEach-Object { $_.Name }) -join ", ")) -ForegroundColor DarkGray

  # Codice solo-debug dentro la release: il sink di logcat e il catalogo dei componenti non devono esserci.
  $analyzer = $null
  $sdkTools = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $aapt2))
  foreach ($candidate in @("cmdline-tools\latest\bin\apkanalyzer.bat", "cmdline-tools\latest-2\bin\apkanalyzer.bat")) {
    $path = Join-Path $sdkTools $candidate
    if (Test-Path -LiteralPath $path) { $analyzer = $path; break }
  }
  if ($analyzer) {
    Ensure-Java
    $classes = Invoke-Native -File $analyzer -Arguments @("dex", "packages", "--defined-only", $apk.FullName)
    if ($classes.Code -eq 0) {
      $text = $classes.Lines -join "`n"
      foreach ($forbidden in @("LogcatSink", "ComponentCatalogPlaceholder", "core.designsystem.catalog")) {
        if ($text -match [regex]::Escape($forbidden)) { $problems += "codice solo-debug dentro la release: $forbidden" }
      }
    } else {
      Write-Host "        (apkanalyzer non e' riuscito a leggere il dex: salto il controllo del codice solo-debug)" -ForegroundColor Yellow
    }
  } else {
    Write-Host "        (apkanalyzer non trovato: salto il controllo del codice solo-debug)" -ForegroundColor Yellow
  }
  if ($problems.Count -gt 0) { return ($problems -join "; ") }
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
