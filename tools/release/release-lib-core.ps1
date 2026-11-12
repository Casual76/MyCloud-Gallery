# Libreria di tools\release\release.ps1 (Fase 10, P12; docs/fase10-progetto.md par. 2.9, D32-D34): le funzioni di base.
# Si carica con il punto (". release-lib-core.ps1") da release.ps1 e da test-release.ps1. Solo ASCII, PowerShell 5.1.
# Nessuna funzione qui legge mai pampa.jks, K1 o K2 e nessuna stampa mai una password o una chiave privata.

# Il file di un ingresso del repository: se c'e' una cartella di sostituzione ($script:Overlay, solo nelle prove con -TestKeystore) e li'
# c'e' lo stesso percorso relativo, vale quello; altrimenti il file vero del repository. Per provare un file che MANCA quando nel
# repository c'e', la prova mette accanto un segnaposto "<percorso>.assente": allora vale il percorso della sostituzione, che non esiste.
function Resolve-RepoFile([string]$Relative) {
  if ($script:Overlay) {
    $candidate = Join-Path $script:Overlay $Relative
    if (Test-Path -LiteralPath $candidate) { return $candidate }
    if (Test-Path -LiteralPath ($candidate + ".assente")) { return $candidate }
  }
  return (Join-Path $script:Root $Relative)
}

function Read-Utf8([string]$Path) {
  return [System.IO.File]::ReadAllText($Path, (New-Object System.Text.UTF8Encoding($false)))
}

# Esegue un comando nativo: codice e righe di uscita (stdout e stderr insieme), senza lanciare mai per un'uscita diversa da zero.
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
    if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    [System.IO.File]::WriteAllLines($LogPath, $lines)
  }
  return [pscustomobject]@{ Code = $code; Lines = $lines }
}

# Gradle e apksigner vogliono un JDK 17: se JAVA_HOME non c'e' e java non e' nel PATH, prova dove Alessio lo tiene.
function Initialize-Java {
  if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin\java.exe"))) { return }
  if (Get-Command java -ErrorAction SilentlyContinue) { return }
  $candidate = Get-ChildItem "C:\Program Files\Microsoft" -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1
  if ($candidate) { $env:JAVA_HOME = $candidate.FullName }
}

function Get-Keytool {
  Initialize-Java
  if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME "bin\keytool.exe"
    if (Test-Path -LiteralPath $candidate) { return $candidate }
  }
  $found = Get-Command keytool -ErrorAction SilentlyContinue
  if ($found) { return $found.Source }
  return $null
}

# La cartella dell'SDK di Android: sdk.dir di local.properties del repository (l'unica voce che si legge da li' qui) oppure l'ambiente.
function Get-SdkDir {
  $localProperties = Join-Path $script:Root "local.properties"
  if (Test-Path -LiteralPath $localProperties) {
    $match = Select-String -LiteralPath $localProperties -Pattern '^\s*sdk\.dir\s*=\s*(.+)$' | Select-Object -First 1
    if ($match) {
      # Formato .properties: "C\:\\Users\\..." -> "C:\Users\..."
      return $match.Matches[0].Groups[1].Value.Trim().Replace("\:", ":").Replace("\\", "\")
    }
  }
  if ($env:ANDROID_HOME) { return $env:ANDROID_HOME }
  if ($env:ANDROID_SDK_ROOT) { return $env:ANDROID_SDK_ROOT }
  return $null
}

# Gli strumenti delle build-tools piu' recenti che hanno aapt2: aapt2, apksigner, zipalign.
function Get-BuildTools {
  $sdk = Get-SdkDir
  if (-not $sdk) { return $null }
  $tools = Get-ChildItem (Join-Path $sdk "build-tools") -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "aapt2.exe") } |
    Sort-Object { try { [version]($_.Name -replace '[^0-9.].*$', '') } catch { [version]"0.0" } } -Descending |
    Select-Object -First 1
  if (-not $tools) { return $null }
  Initialize-Java
  return [pscustomobject]@{
    Dir = $tools.FullName
    Sdk = $sdk
    Aapt2 = (Join-Path $tools.FullName "aapt2.exe")
    ApkSigner = (Join-Path $tools.FullName "apksigner.bat")
    ZipAlign = (Join-Path $tools.FullName "zipalign.exe")
  }
}

function Get-FileSha256([string]$Path) {
  $stream = [System.IO.File]::OpenRead($Path)
  try {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    return ([System.BitConverter]::ToString($sha.ComputeHash($stream)) -replace '-', '').ToLowerInvariant()
  } finally {
    $stream.Dispose()
  }
}

# --- I nomi delle versioni (pacchetto U, giro 1: docs/aggiornamenti-in-app.md, "Le versioni") -------------------------------------------------
# La forma ammessa e' quella che l'aggiornamento dentro l'app accetta (UpdateRules.VERSION in feature-update): tre numeri senza zeri davanti, al piu'
# quattro cifre l'uno, e per una beta `-beta.` con DUE cifre. Niente `v`, niente `+`, niente quarto numero.
$script:VersionNamePattern = '^(0|[1-9]\d{0,3})\.(0|[1-9]\d{0,3})\.(0|[1-9]\d{0,3})(-beta\.\d{2})?$'

function Test-VersionName([string]$Name) {
  return ($Name -cmatch $script:VersionNamePattern)
}

# Il confronto delle versioni dell'app (engine-foundation, EngineVersion.kt `compareVersions`, engine 3.0.4): i numeri prima del primo trattino uno
# per uno (un pezzo che non e' un numero vale 0, uno che manca vale 0), poi il suffisso: senza suffisso viene DOPO con suffisso, due suffissi si
# confrontano come testo (per questo beta.10 verrebbe prima di beta.9, e la forma vuole due cifre). Ritorna -1, 0 o 1.
function Compare-EngineVersion([string]$Left, [string]$Right) {
  $leftParts = @((($Left -split '-', 2)[0]) -split '\.' | ForEach-Object { $n = 0; if ([int]::TryParse($_.Trim(), [ref]$n)) { $n } else { 0 } })
  $rightParts = @((($Right -split '-', 2)[0]) -split '\.' | ForEach-Object { $n = 0; if ([int]::TryParse($_.Trim(), [ref]$n)) { $n } else { 0 } })
  $count = [Math]::Max($leftParts.Count, $rightParts.Count)
  for ($i = 0; $i -lt $count; $i++) {
    $l = if ($i -lt $leftParts.Count) { [int]$leftParts[$i] } else { 0 }
    $r = if ($i -lt $rightParts.Count) { [int]$rightParts[$i] } else { 0 }
    if ($l -ne $r) { return [Math]::Sign($l - $r) }
  }
  $leftSuffix = if ($Left.Contains('-')) { $Left.Substring($Left.IndexOf('-') + 1) } else { "" }
  $rightSuffix = if ($Right.Contains('-')) { $Right.Substring($Right.IndexOf('-') + 1) } else { "" }
  if ([string]::IsNullOrWhiteSpace($leftSuffix) -and -not [string]::IsNullOrWhiteSpace($rightSuffix)) { return 1 }
  if (-not [string]::IsNullOrWhiteSpace($leftSuffix) -and [string]::IsNullOrWhiteSpace($rightSuffix)) { return -1 }
  return [Math]::Sign([string]::CompareOrdinal($leftSuffix, $rightSuffix))
}

# --- release/versions.txt (D33) ----------------------------------------------------------------------------------------
# Una riga: <versione> <codice> <stato> <canale> <data> <sha256>. Ritorna le righe lette e i problemi di forma; le regole sulle
# versioni (Test-VersionRows) vengono dopo.
function Read-VersionRows([string]$Path) {
  $rows = New-Object System.Collections.Generic.List[object]
  $problems = New-Object System.Collections.Generic.List[string]
  if (-not (Test-Path -LiteralPath $Path)) {
    $problems.Add("manca release/versions.txt")
    return [pscustomobject]@{ Rows = @(); Problems = @($problems) }
  }
  $number = 0
  foreach ($raw in ((Read-Utf8 $Path) -split '\r?\n')) {
    $number++
    $line = $raw.Trim()
    if ($line.Length -eq 0 -or $line.StartsWith("#")) { continue }
    $columns = @($line -split '\s+')
    if ($columns.Count -ne 6) { $problems.Add("versions.txt riga ${number}: servono sei colonne (versione codice stato canale data sha256)"); continue }
    $row = [pscustomobject]@{ Name = $columns[0]; Code = 0; State = $columns[2]; Channel = $columns[3]; Date = $columns[4]; Sha = $columns[5].ToLowerInvariant(); Line = $number }
    if (-not (Test-VersionName $columns[0])) { $problems.Add("versions.txt riga ${number}: versione '$($columns[0])' non valida (1.0.0 oppure 1.1.0-beta.02: tre numeri, la beta con due cifre, niente v ne' +)"); continue }
    if ($columns[1] -notmatch '^[1-9]\d{0,8}$') { $problems.Add("versions.txt riga ${number}: versionCode '$($columns[1])' non valido"); continue }
    $row.Code = [int]$columns[1]
    if (@("pubblicata", "prevista") -notcontains $row.State) { $problems.Add("versions.txt riga ${number}: stato '$($row.State)' non valido (pubblicata o prevista)"); continue }
    if (@("beta", "stable") -notcontains $row.Channel) { $problems.Add("versions.txt riga ${number}: canale '$($row.Channel)' non valido (beta o stable)"); continue }
    if ($row.State -eq "pubblicata") {
      if ($row.Date -notmatch '^\d{4}-\d{2}-\d{2}$') { $problems.Add("versions.txt riga ${number}: una versione pubblicata ha la data AAAA-MM-GG"); continue }
      if ($row.Sha -notmatch '^[0-9a-f]{64}$') { $problems.Add("versions.txt riga ${number}: una versione pubblicata ha l'impronta SHA-256 dell'APK"); continue }
    }
    $rows.Add($row)
  }
  return [pscustomobject]@{ Rows = $rows.ToArray(); Problems = $problems.ToArray() }
}

# Le regole del passo 4 sulle versioni (D33): il versionCode cresce e non si riusa. Ritorna l'elenco dei problemi (vuoto = a posto).
# Lo stesso numero puo' stare su due righe solo per lo STESSO nome e solo se e' lo stesso identico APK (promozione da beta a stable):
# l'impronta si confronta dopo la build (Test-ApkAgainstVersion).
function Test-VersionRows($Parsed, [string]$Version, [string]$Channel) {
  $problems = New-Object System.Collections.Generic.List[string]
  foreach ($problem in $Parsed.Problems) { $problems.Add($problem) }
  $rows = @($Parsed.Rows)
  if ($rows.Count -eq 0) {
    if ($problems.Count -eq 0) { $problems.Add("release/versions.txt non ha nessuna riga di versione") }
    return @($problems)
  }
  $prepared = @($rows | Where-Object { $_.State -eq "prevista" })
  if ($prepared.Count -gt 1) { $problems.Add("versions.txt: piu' di una riga 'prevista' (ne basta una, l'ultima)") }
  $last = $rows[$rows.Count - 1]
  if ($last.State -ne "prevista") {
    $problems.Add("versions.txt: l'ultima riga e' '$($last.Name)' gia' pubblicata: per preparare la versione $Version si aggiunge la riga prevista (release.ps1 -Prepara -Version $Version -Channel $Channel)")
    return @($problems)
  }
  if ($last.Name -ne $Version) { $problems.Add("versions.txt: l'ultima riga e' la versione $($last.Name), non $Version") }
  if ($last.Channel -ne $Channel) { $problems.Add("versions.txt: l'ultima riga e' per il canale $($last.Channel), non $Channel") }
  if ($Channel -eq "stable" -and $Version -match '-beta\.') { $problems.Add("una versione $Version e' una beta: il canale stable vuole una versione senza -beta.N") }
  $published = @($rows | Where-Object { $_.State -eq "pubblicata" })
  $max = 0
  foreach ($row in $published) { if ($row.Code -gt $max) { $max = $row.Code } }
  $same = @($published | Where-Object { $_.Code -eq $last.Code })
  if ($same.Count -gt 0) {
    foreach ($other in $same) {
      if ($other.Name -ne $last.Name) { $problems.Add("versionCode $($last.Code) riusato: e' gia' della versione $($other.Name) (riga $($other.Line)); il versionCode cresce a ogni APK diverso") }
    }
  } elseif ($last.Code -lt $max) {
    $problems.Add("versionCode $($last.Code) piu' basso di uno gia' pubblicato ($max): deve essere maggiore di ogni versione pubblicata")
  }
  # Pacchetto U, giro 1: anche il NOME cresce. L'aggiornamento dentro l'app propone una versione solo se il suo nome e' piu' nuovo per il confronto
  # dell'app (Compare-EngineVersion): un APK nuovo con un nome non piu' nuovo (una 1.0.0-beta.02 dopo la 1.0.0, una 1.0.0 rifatta) non arriverebbe
  # a nessuno. Il passaggio dello STESSO APK da beta a stable (stesso nome, stesso numero) e' l'unica eccezione.
  foreach ($other in $published) {
    if ($other.Code -eq $last.Code) { continue }
    if ((Compare-EngineVersion $last.Name $other.Name) -le 0) {
      $problems.Add("versions.txt: il nome $($last.Name) non e' piu' nuovo di $($other.Name), gia' pubblicata: l'aggiornamento dentro l'app non lo proporrebbe (docs/aggiornamenti-in-app.md, Le versioni)")
    }
  }
  $previous = $null
  foreach ($row in $rows) {
    if ($previous -and $row.Code -lt $previous.Code) { $problems.Add("versions.txt riga $($row.Line): il versionCode $($row.Code) scende (prima c'e' $($previous.Code))") }
    if ($previous -and $row.Code -eq $previous.Code -and ($row.Name -ne $previous.Name -or $row.Channel -eq $previous.Channel)) {
      $problems.Add("versions.txt riga $($row.Line): il versionCode $($row.Code) e' ripetuto senza essere un passaggio da beta a stable della stessa versione")
    }
    $previous = $row
  }
  return @($problems)
}

# Dopo la build: l'APK dice la stessa versione della riga, e se il numero e' lo stesso di una versione gia' pubblicata l'APK e' identico.
function Test-ApkAgainstVersion($Parsed, [string]$Version, $Facts, [string]$ApkSha256) {
  $problems = New-Object System.Collections.Generic.List[string]
  $last = @($Parsed.Rows)[@($Parsed.Rows).Count - 1]
  if ($Facts.VersionCode -ne $last.Code) { $problems.Add("l'APK ha versionCode $($Facts.VersionCode), release/versions.txt dice $($last.Code)") }
  if ($Facts.VersionName -ne $Version) { $problems.Add("l'APK ha versionName '$($Facts.VersionName)', si rilascia '$Version'") }
  foreach ($other in @($Parsed.Rows | Where-Object { $_.State -eq "pubblicata" -and $_.Code -eq $Facts.VersionCode })) {
    if ($other.Sha -ne $ApkSha256) {
      $problems.Add("versionCode $($Facts.VersionCode) riusato: e' gia' pubblicato con un altro APK (impronta $($other.Sha.Substring(0, 12))...); un APK diverso vuole un versionCode maggiore")
    }
  }
  return @($problems)
}

# --- Impronta del certificato di pampa.jks in docs/sicurezza.md par. 5.6 ----------------------------------------------------

function Get-DocFingerprint([string]$SicurezzaPath) {
  if (-not (Test-Path -LiteralPath $SicurezzaPath)) { return $null }
  foreach ($line in ((Read-Utf8 $SicurezzaPath) -split '\r?\n')) {
    if ($line.StartsWith('| `pampa.jks` |')) {
      $match = [regex]::Match($line, '`([0-9a-fA-F]{64})`')
      if ($match.Success) { return $match.Groups[1].Value.ToLowerInvariant() }
      return $null
    }
  }
  return $null
}

# Scrive l'impronta nella riga di pampa.jks (le ultime due celle: stato e impronta); il resto del documento non cambia.
function Set-DocFingerprint([string]$SicurezzaPath, [string]$Hex, [string]$Today) {
  $text = Read-Utf8 $SicurezzaPath
  $eol = if ($text.Contains("`r`n")) { "`r`n" } else { "`n" }
  $lines = $text -split '\r?\n'
  $done = $false
  for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i].StartsWith('| `pampa.jks` |')) {
      $cells = @($lines[$i].Trim().Trim('|') -split '\|')
      if ($cells.Count -lt 4) { throw "la riga di pampa.jks in sicurezza.md par. 5.6 non ha quattro celle" }
      $lines[$i] = '| `pampa.jks` |' + $cells[1] + '| scritta il ' + $Today + ' con `release.ps1 -Impronta -Scrivi`; `release.ps1` la confronta a ogni release | `' + $Hex.ToLowerInvariant() + '` |'
      $done = $true
      break
    }
  }
  if (-not $done) { throw "non trovo la riga di pampa.jks in docs/sicurezza.md par. 5.6" }
  [System.IO.File]::WriteAllText($SicurezzaPath, ($lines -join $eol), (New-Object System.Text.UTF8Encoding($false)))
}

# L'impronta SHA-256 del CERTIFICATO (dato pubblico) di un keystore. La password passa da una variabile d'ambiente (`-storepass:env`):
# mai sulla riga di comando, mai nell'uscita. Ritorna $null se keytool non riesce.
function Get-KeystoreCertSha256([string]$Keystore, [string]$Alias, [string]$PasswordEnvName, [string]$TempDir) {
  $keytool = Get-Keytool
  if (-not $keytool) { return $null }
  if (-not (Test-Path -LiteralPath $TempDir)) { New-Item -ItemType Directory -Path $TempDir -Force | Out-Null }
  $cer = Join-Path $TempDir ("cert-" + [guid]::NewGuid().ToString("N") + ".cer")
  try {
    $run = Invoke-Native -File $keytool -Arguments @("-exportcert", "-keystore", $Keystore, "-alias", $Alias, "-storepass:env", $PasswordEnvName, "-file", $cer)
    if ($run.Code -ne 0 -or -not (Test-Path -LiteralPath $cer)) { return $null }
    return (Get-FileSha256 $cer)
  } finally {
    Remove-Item -LiteralPath $cer -Force -ErrorAction SilentlyContinue
  }
}
