# Libreria di tools\release\release.ps1 (Fase 10, P12; docs/fase10-progetto.md par. 2.9): i controlli dei passi 0-6.
# Si carica dopo release-lib-core.ps1 e release-lib-apk.ps1. Solo ASCII, PowerShell 5.1.
# Ogni funzione ritorna l'elenco dei problemi (vuoto = a posto); nessuna lancia per un controllo che non passa.

# Passo 0: ReleaseKeys.PROVISIONAL deve essere false (L560: K1 e K2 vere, altrimenti nessun kill switch).
function Test-ReleaseKeysNotProvisional {
  $file = Resolve-RepoFile "core-release\src\main\kotlin\dev\pampa\mycloudgallery\core\release\keys\ReleaseKeys.kt"
  if (-not (Test-Path -LiteralPath $file)) { return @("manca ReleaseKeys.kt: non posso sapere se K1 e K2 sono quelle vere") }
  $code = Read-Utf8 $file
  $code = [regex]::Replace($code, '(?s)/\*.*?\*/', '')
  $code = [regex]::Replace($code, '(?m)//.*$', '')
  $match = [regex]::Match($code, 'const\s+val\s+PROVISIONAL\b[^=]*=\s*(true|false)')
  if (-not $match.Success) { return @("non trovo ReleaseKeys.PROVISIONAL in ReleaseKeys.kt") }
  if ($match.Groups[1].Value -eq "true") {
    return @("ReleaseKeys.PROVISIONAL e' true: K1 e K2 sono ancora i segnaposto (L560), senza chiave privata non c'e' il blocco a distanza. Si genera con manifest-tool.ps1 keys, si fissano in ReleaseKeys.kt e si scrivono le impronte in docs/sicurezza.md par. 5.6")
  }
  return @()
}

# Passo 2: tools\release\recinzioni.json esiste ed e' un JSON non vuoto (lo scrive P10, par. 2.10). Il confronto con il bytecode della release lo fa
# tools\check.ps1 (passo 3 di questo script): qui si controlla che il file ci sia, si legge e si fa vedere lo stato dichiarato.
function Test-Recinzioni([ref]$Summary) {
  $file = Resolve-RepoFile "tools\release\recinzioni.json"
  if (-not (Test-Path -LiteralPath $file)) { return @("manca recinzioni.json (tools\release\recinzioni.json, lo crea P10): senza non si sa lo stato atteso delle quattro recinzioni") }
  try {
    $json = (Read-Utf8 $file) | ConvertFrom-Json
  } catch {
    return @("recinzioni.json non e' un JSON valido: " + $_.Exception.Message)
  }
  if ($null -eq $json) { return @("recinzioni.json e' vuoto") }
  $names = @($json.PSObject.Properties | ForEach-Object { $_.Name })
  if ($names.Count -eq 0) { return @("recinzioni.json non ha nessun campo") }
  $Summary.Value = ($names -join ", ")
  return @()
}

# Passo 1: albero pulito, sul tag v<versione> di main, engine pulito. $Root e' la cartella del repository; $EngineDoctor: eseguire engine-doctor.
function Test-ReleaseTree([string]$Root, [string]$Version, [string]$MainBranch = "main", [bool]$EngineDoctor = $true) {
  $problems = New-Object System.Collections.Generic.List[string]
  $status = Invoke-Native -File "git" -Arguments @("-C", $Root, "status", "--porcelain")
  if ($status.Code -ne 0) { return @("git non risponde in $Root") }
  $dirty = @($status.Lines | Where-Object { $_.Trim().Length -gt 0 })
  if ($dirty.Count -gt 0) { $problems.Add("l'albero ha $($dirty.Count) modifiche non committate (git status non e' pulito): si rilascia solo da un commit con tag") }
  $tag = "v$Version"
  $tagCommit = Invoke-Native -File "git" -Arguments @("-C", $Root, "rev-parse", "-q", "--verify", "refs/tags/$tag^{commit}")
  if ($tagCommit.Code -ne 0) {
    $problems.Add("manca il tag $tag (git tag $tag sul commit da rilasciare, su $MainBranch)")
  } else {
    $head = Invoke-Native -File "git" -Arguments @("-C", $Root, "rev-parse", "HEAD")
    if (($head.Lines | Select-Object -First 1) -ne ($tagCommit.Lines | Select-Object -First 1)) {
      # Un solo scarto: dopo la pubblicazione si segna `pubblicata` in release/versions.txt (un commit) e per il passaggio da beta a stable
      # della stessa versione serve ancora il tag; quel commit e' ammesso se cambia SOLO release/versions.txt (D33).
      $follows = Invoke-Native -File "git" -Arguments @("-C", $Root, "merge-base", "--is-ancestor", "refs/tags/$tag^{commit}", "HEAD")
      if ($follows.Code -ne 0) {
        $problems.Add("HEAD non e' sul tag $tag e non lo segue: si rilascia il commit del tag")
      } else {
        $changed = Invoke-Native -File "git" -Arguments @("-C", $Root, "diff", "--name-only", "refs/tags/$tag^{commit}", "HEAD")
        $others = @($changed.Lines | Where-Object { $_.Trim().Length -gt 0 -and $_.Trim() -ne "release/versions.txt" })
        if ($others.Count -gt 0) { $problems.Add("HEAD e' avanti rispetto al tag $tag con modifiche oltre a release/versions.txt ($($others.Count) file, per esempio $($others[0])): si rilascia il commit del tag") }
      }
    }
    $onMain = Invoke-Native -File "git" -Arguments @("-C", $Root, "merge-base", "--is-ancestor", "refs/tags/$tag^{commit}", "refs/heads/$MainBranch")
    if ($onMain.Code -ne 0) { $problems.Add("il tag $tag non e' sul ramo ${MainBranch}: si rilascia solo da $MainBranch") }
  }
  if ($EngineDoctor) {
    $engineStatus = Invoke-Native -File "git" -Arguments @("-C", (Join-Path $Root "engine"), "status", "--porcelain")
    if ($engineStatus.Code -ne 0) {
      $problems.Add("git non risponde dentro engine/")
    } elseif (@($engineStatus.Lines | Where-Object { $_.Trim().Length -gt 0 }).Count -gt 0) {
      $problems.Add("engine/ ha modifiche non committate (sarebbe una variante silenziosa dell'engine)")
    }
    $doctor = Join-Path $Root "engine\tools\engine-doctor.ps1"
    if (-not (Test-Path -LiteralPath $doctor)) {
      $problems.Add("manca engine\tools\engine-doctor.ps1: submodule non inizializzato? (git submodule update --init)")
    } else {
      $run = Invoke-Native -File "powershell" -Arguments @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $doctor, "-AppRoot", $Root)
      if ($run.Code -ne 0) { $problems.Add("engine-doctor ha segnalato problemi (codice $($run.Code))") }
    }
  }
  return @($problems)
}

# Passo 3, parte B: il riferimento S25 piu' recente di docs\bench non ha piu' di $MaxDays giorni (e non e' nel futuro).
function Test-BenchReference([int]$MaxDays, [datetime]$Today) {
  $dir = Join-Path $script:Root "docs\bench"
  $files = @(Get-ChildItem -LiteralPath $dir -Filter "riferimento-s25*.json" -ErrorAction SilentlyContinue)
  if ($files.Count -eq 0) { return @("nessun riferimento S25 in docs\bench (riferimento-s25-*.json): si misura con check.ps1 -Bench sull'S25") }
  $newest = $null
  foreach ($file in $files) {
    try {
      $date = [datetime]::ParseExact(([string]((Read-Utf8 $file.FullName) | ConvertFrom-Json).data), "yyyy-MM-dd", [System.Globalization.CultureInfo]::InvariantCulture)
    } catch {
      continue
    }
    if ($null -eq $newest -or $date -gt $newest.Date) { $newest = [pscustomobject]@{ Date = $date; Name = $file.Name } }
  }
  if ($null -eq $newest) { return @("i riferimenti S25 di docs\bench non hanno una data leggibile (campo data, AAAA-MM-GG)") }
  $age = ($Today.Date - $newest.Date).Days
  if ($age -lt 0) { return @("il riferimento S25 $($newest.Name) e' datato nel futuro ($($newest.Date.ToString('yyyy-MM-dd')))") }
  if ($age -gt $MaxDays) { return @("il riferimento S25 piu' recente ($($newest.Name), $($newest.Date.ToString('yyyy-MM-dd'))) ha $age giorni: al piu' $MaxDays. Si rifanno le misure sull'S25 (check.ps1 -Bench) e si registra il riferimento") }
  return @()
}

# Passo 3, parte A: legge il riepilogo di check.ps1 (le righe "  OK|FAIL|SKIP  nome") dal suo log. Il benchmark su dispositivo deve essere OK,
# non SKIP: senza l'S25 collegato check.ps1 lo salta e una release non puo' partire.
function Test-CheckSummary([string[]]$Lines, [int]$ExitCode) {
  $problems = New-Object System.Collections.Generic.List[string]
  if ($ExitCode -ne 0) { $problems.Add("check.ps1 e' fallito (codice $ExitCode)") }
  $inSummary = $false
  $benchSeen = $false
  foreach ($line in $Lines) {
    if ($line -match '^Riepilogo\s*$') { $inSummary = $true; continue }
    if (-not $inSummary) { continue }
    if ($line -match '^\s+(OK|FAIL|SKIP)\s+(.*)$') {
      $state = $Matches[1]
      $name = $Matches[2]
      if ($name -match 'benchmark su dispositivo') {
        $benchSeen = $true
        if ($state -ne "OK") { $problems.Add("il benchmark su dispositivo e' ${state}: serve l'S25 collegato e le misure dentro le guardie (check.ps1 -Bench -BenchStrict)") }
      } elseif ($state -eq "FAIL") {
        $problems.Add("check.ps1: $name")
      }
    }
  }
  if (-not $inSummary) { $problems.Add("nel log di check.ps1 non c'e' il riepilogo: l'esecuzione non e' finita") }
  elseif (-not $benchSeen) { $problems.Add("nel riepilogo di check.ps1 non c'e' il benchmark su dispositivo (e' stato lanciato con -Bench?)") }
  return @($problems)
}

# Passo 6: la sezione della versione in CHANGELOG.md. Ritorna problemi e, per riferimento, il testo della sezione.
function Get-ChangelogSection([string]$Path, [string]$Version) {
  if (-not (Test-Path -LiteralPath $Path)) { return [pscustomobject]@{ Problems = @("manca CHANGELOG.md"); Text = $null } }
  $text = (Read-Utf8 $Path) -replace "`r`n", "`n"
  $lines = $text -split "`n"
  $start = -1
  for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match '^##\s+(\S+)') {
      if ($Matches[1] -eq $Version) { $start = $i; break }
    }
  }
  if ($start -lt 0) { return [pscustomobject]@{ Problems = @("CHANGELOG.md non ha la sezione '## $Version'"); Text = $null } }
  $end = $lines.Count
  for ($i = $start + 1; $i -lt $lines.Count; $i++) { if ($lines[$i] -match '^##\s+\S') { $end = $i; break } }
  $section = @($lines[$start..($end - 1)])
  $problems = New-Object System.Collections.Generic.List[string]
  $titles = @()
  $items = @{}
  $current = $null
  foreach ($line in $section) {
    if ($line -match '^###\s+(.+?)\s*$') { $current = $Matches[1]; $titles += $current; $items[$current] = 0; continue }
    if ($current -and $line -match '^-\s+\S') { $items[$current]++ }
  }
  $expected = @(("Novit" + [char]0x00E0), "Correzioni", "Sicurezza", "Da sapere")
  if (($titles -join "|") -ne ($expected -join "|")) {
    $problems.Add("la sezione $Version di CHANGELOG.md deve avere, in quest'ordine, i titoli ### Novita' (con la a accentata), ### Correzioni, ### Sicurezza, ### Da sapere (ha: " + ($titles -join ", ") + ")")
  }
  foreach ($title in $titles) { if ($items[$title] -lt 1) { $problems.Add("CHANGELOG.md $Version, '$title': nessuna frase (- ...)") } }
  $body = (($section | Select-Object -Skip 1) -join "`n").Trim()
  if ($body.Length -lt 40) { $problems.Add("la sezione $Version di CHANGELOG.md e' vuota") }
  return [pscustomobject]@{ Problems = @($problems); Text = ($section -join "`n").TrimEnd() }
}

# Passo 6, parte B: dentro l'APK c'e' una risorsa con la sezione intera della versione (il percorso puo' cambiare per R8: si cerca il testo).
function Test-ChangelogInApk([string]$Apk, [string]$SectionText) {
  if (-not $SectionText) { return @("senza la sezione di CHANGELOG.md non si puo' controllare la risorsa dell'APK") }
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zip = [System.IO.Compression.ZipFile]::OpenRead($Apk)
  try {
    $needle = ($SectionText -replace "`r`n", "`n").Trim()
    foreach ($entry in @($zip.Entries | Where-Object { $_.FullName -like "res/*" -and $_.Length -gt 0 -and $_.Length -lt 262144 })) {
      $reader = New-Object System.IO.StreamReader($entry.Open(), (New-Object System.Text.UTF8Encoding($false)))
      try { $content = ($reader.ReadToEnd() -replace "`r`n", "`n") } finally { $reader.Dispose() }
      if ($content.Contains($needle)) { return @() }
    }
  } finally {
    $zip.Dispose()
  }
  return @("l'APK non contiene il changelog di questa versione fra le risorse (res/raw/changelog.md, la copia di CHANGELOG.md): la pagina 'Novita' di questa versione' sarebbe vuota")
}
