# Le funzioni del controllo delle recinzioni (Fase 10 P10; docs/fase10-progetto.md par. 2.10, D35, Q6 (b), C446).
# Si caricano con `. tools\release\recinzioni.ps1` (tools\check.ps1 e tools\release\recinzioni-prove.ps1). NESSUNA scrittura:
# leggono recinzioni.json, FeatureFlags.kt, docs\decisioni.md e (se c'e') il dex della release. Solo ASCII.
#
# Le quattro recinzioni si spengono in UN passo solo. Il controllo ha due stati:
#   - tutte accese: recinzioni.json le dichiara accese, il consenso e' vuoto, il sorgente e il bytecode della release dicono "accesa";
#   - tutte spente: recinzioni.json le dichiara spente con UN consenso (data e un marcatore che sta in UNA riga di docs\decisioni.md,
#     quella riga porta la stessa data e i quattro id); il sorgente e il bytecode dicono "spenta" per ognuna;
#   - misto: solo se recinzioni.json dichiara un ritorno indietro parziale (data, marcatore in una riga di decisioni.md, motivo)
#     e il consenso del passo resta valido.
# Ogni stato osservato (sorgente, bytecode) deve coincidere con quello dichiarato: una recinzione spenta di nascosto fa fallire il controllo.

$script:FenceIds = @("F4-D31", "F5-D12", "F7-D30", "F8-D34")

# Per ogni recinzione: il campo di FeatureFlags, la decisione e il getter che il bytecode della release tiene (consumer-rules.pro di
# core-common). F4-D31 non ha ne' decisione ne' getter tenuto: lo stato si legge nel toString() che R8 ripiega in una costante.
$script:FenceTable = [ordered]@{
  "F4-D31" = @{ campo = "indexPublishTestSharesOnly"; area = "indice"; getter = $null; decision = $null }
  "F5-D12" = @{ campo = "backupTestSharesOnly"; area = "backup"; getter = "getBackupTestSharesOnly"; decision = "backupMayWriteTo" }
  "F7-D30" = @{ campo = "sharedAlbumsTestSharesOnly"; area = "album"; getter = "getSharedAlbumsTestSharesOnly"; decision = "sharedAlbumsMayUse" }
  "F8-D34" = @{ campo = "facesSyncTestSharesOnly"; area = "volti"; getter = "getFacesSyncTestSharesOnly"; decision = "facesSyncMayUse" }
}

$script:FlagsClass = "Ldev/pampa/mycloudgallery/core/common/featureflags/FeatureFlags;"

function Read-RecinzioniJson([string]$path) {
  if (-not (Test-Path -LiteralPath $path)) { throw "manca $path" }
  return [System.IO.File]::ReadAllText($path, (New-Object System.Text.UTF8Encoding($false))) | ConvertFrom-Json
}

# Lo stato di ogni recinzione nel RELEASE di FeatureFlags.kt: "accesa" (true), "spenta" (false) o "ignota" (una forma che non si legge:
# in quel caso il controllo fallisce, non indovina). Segue le costanti `private const val NOME: Boolean = true|false`.
function Get-FlagsSourceStates([string]$text) {
  $states = @{}
  $consts = @{}
  foreach ($m in [regex]::Matches($text, 'private\s+const\s+val\s+([A-Z0-9_]+)\s*:\s*Boolean\s*=\s*(true|false)\b')) { $consts[$m.Groups[1].Value] = $m.Groups[2].Value }
  $block = [regex]::Match($text, 'val\s+RELEASE\s*:\s*FeatureFlags\s*=\s*FeatureFlags\((?<body>[^)]*)\)')
  foreach ($id in $script:FenceIds) { $states[$id] = "ignota" }
  if (-not $block.Success) { return $states }
  $values = @{}
  foreach ($m in [regex]::Matches($block.Groups["body"].Value, '(?m)^\s*([A-Za-z0-9_]+)\s*=\s*([A-Za-z0-9_]+)\s*,?\s*$')) { $values[$m.Groups[1].Value] = $m.Groups[2].Value }
  foreach ($id in $script:FenceIds) {
    $field = $script:FenceTable[$id].campo
    if (-not $values.ContainsKey($field)) { continue }
    $v = $values[$field]
    if ($consts.ContainsKey($v)) { $v = $consts[$v] }
    if ($v -eq "true") { $states[$id] = "accesa" } elseif ($v -eq "false") { $states[$id] = "spenta" }
  }
  return $states
}

# Legge dal dump di dexdump (-d) la classe FeatureFlags: lo streaming si ferma alla fine della classe (il dump intero e' di centinaia di MB).
function Initialize-FenceDexReader {
  if (([System.Management.Automation.PSTypeName]'FenceDexReader').Type) { return }
  Add-Type -Language CSharp -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Text;
using System.Text.RegularExpressions;

public static class FenceDexReader {
  static readonly Regex ClassLine = new Regex(@"^\s*Class descriptor\s*:\s*'([^']+)'", RegexOptions.Compiled);

  // Le righe della classe [descriptor] nel dex [dexPath] (null se non c'e'): da "Class descriptor" fino alla classe seguente.
  public static string[] ReadClass(string dexdump, string dexPath, string descriptor) {
    var info = new ProcessStartInfo(dexdump, "-d \"" + dexPath + "\"") {
      UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true,
      StandardOutputEncoding = Encoding.GetEncoding(28591),
    };
    using (var process = Process.Start(info)) {
      var errors = process.StandardError.ReadToEndAsync();
      var lines = new List<string>();
      bool inside = false;
      string line;
      while ((line = process.StandardOutput.ReadLine()) != null) {
        var m = ClassLine.Match(line);
        if (m.Success) {
          if (inside) break;
          if (m.Groups[1].Value == descriptor) inside = true;
        }
        if (inside) lines.Add(line);
      }
      if (!process.HasExited) { try { process.Kill(); } catch (Exception) { } }
      process.WaitForExit();
      return inside ? lines.ToArray() : null;
    }
  }
}
'@
}

# Le righe di FeatureFlags nei classes*.dex di un APK (estrae i dex in una cartella temporanea e li legge in ordine). $null se non c'e'.
function Get-FlagsClassLines([string]$dexdump, [string]$apk) {
  Initialize-FenceDexReader
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $temp = Join-Path ([System.IO.Path]::GetTempPath()) ("mcg-fence-" + [guid]::NewGuid().ToString("N"))
  New-Item -ItemType Directory -Path $temp | Out-Null
  $zip = [System.IO.Compression.ZipFile]::OpenRead($apk)
  try {
    $dexes = @($zip.Entries | Where-Object { $_.FullName -match '^classes\d*\.dex$' } | Sort-Object FullName)
    if ($dexes.Count -eq 0) { throw "nessun classes*.dex nell'APK" }
    foreach ($entry in $dexes) {
      $target = Join-Path $temp $entry.FullName
      [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
      $lines = [FenceDexReader]::ReadClass($dexdump, $target, $script:FlagsClass)
      if ($lines) { return $lines }
    }
    return $null
  } finally {
    $zip.Dispose()
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
  }
}

# I metodi di FeatureFlags dal dump: nome -> elenco di (op, resto) delle istruzioni, e le costanti di stringa del toString.
function Get-FlagsDexFacts([string[]]$classLines) {
  $methods = @{}
  $current = $null
  foreach ($line in $classLines) {
    if ($line -match "^\s+name\s+:\s+'([^']+)'\s*$") { $current = $Matches[1]; if (-not $methods.ContainsKey($current)) { $methods[$current] = New-Object System.Collections.Generic.List[object] }; continue }
    if ($current -and $line -match '\|[0-9a-fA-F]{4}:\s+(\S+)\s*(.*)$') { $methods[$current].Add([pscustomobject]@{ Op = $Matches[1]; Rest = $Matches[2] }) }
  }
  # Array veri (in PowerShell 5.1 @() su una List[object] puo' dare "Tipi di argomento non corrispondenti").
  $arrays = @{}
  foreach ($k in @($methods.Keys)) { $arrays[$k] = $methods[$k].ToArray() }
  return $arrays
}

# Quello che dice il bytecode, per ogni recinzione: l'elenco delle prove ("accesa"/"spenta") e i problemi (prove che non si leggono o
# che si contraddicono). Tre prove, tutte lette dal dex della release:
#   - getter tenuto da R8 (`getBackupTestSharesOnly`...): `const/4 vN, #int 1|0` e `return vN`;
#   - decisione per share (`backupMayWriteTo`...): chiama TestShares.isTestShare (accesa) oppure risponde sempre `const 1` (spenta);
#   - toString(): R8 ripiega i valori in una stringa costante `... campo=true|false ...` (la sola prova per F4-D31).
function Get-FenceDexStates($methods) {
  $result = @{}
  $names = @{}
  if ($methods.ContainsKey("toString")) {
    foreach ($insn in @($methods["toString"])) {
      if ($insn.Op -eq "const-string") {
        foreach ($m in [regex]::Matches($insn.Rest, '([A-Za-z0-9_]+)=(true|false)')) { $names[$m.Groups[1].Value] = $m.Groups[2].Value }
      }
    }
  }
  foreach ($id in $script:FenceIds) {
    $t = $script:FenceTable[$id]
    $proofs = @()
    $problems = @()
    if ($names.ContainsKey($t.campo)) { $proofs += $(if ($names[$t.campo] -eq "true") { "accesa" } else { "spenta" }) }
    if ($t.getter) {
      if (-not $methods.ContainsKey($t.getter)) { $problems += "il getter $($t.getter) non e' nel dex (consumer-rules.pro di core-common)" }
      else {
        $body = @($methods[$t.getter])
        if ($body.Count -ge 2 -and $body[0].Op -like "const*" -and $body[0].Rest -match '^v\d+, #int (\d+)' -and $body[1].Op -like "return*") {
          $proofs += $(if ([int]$Matches[1] -ne 0) { "accesa" } else { "spenta" })
        } else { $problems += "il getter $($t.getter) non e' una costante nel dex: non si legge lo stato" }
      }
    }
    if ($t.decision) {
      if (-not $methods.ContainsKey($t.decision)) { $problems += "la decisione $($t.decision) non e' nel dex" }
      else {
        $body = @($methods[$t.decision])
        $calls = @($body | Where-Object { $_.Op -like "invoke-static*" -and $_.Rest -like "*TestShares;.isTestShare:*" }).Count -gt 0
        $constOne = @($body | Where-Object { $_.Op -like "const*" -and $_.Rest -match '^v\d+, #int 1\b' }).Count -gt 0
        $readsField = @($body | Where-Object { $_.Op -like "iget-boolean*" }).Count -gt 0
        if ($readsField) { $problems += "la decisione $($t.decision) legge un campo: lo stato non e' una costante nel dex" }
        elseif ($calls) { $proofs += "accesa" }
        elseif ($constOne) { $proofs += "spenta" }
        else { $problems += "la decisione $($t.decision) non si legge ne' come accesa ne' come spenta" }
      }
    }
    if ($proofs.Count -eq 0 -and $problems.Count -eq 0) { $problems += "nessuna prova nel dex" }
    $distinct = @($proofs | Select-Object -Unique)
    if ($distinct.Count -gt 1) { $problems += "prove discordanti nel dex: " + ($proofs -join ", ") }
    $state = if ($distinct.Count -eq 1 -and $problems.Count -eq 0) { $distinct[0] } else { "incerta" }
    $result[$id] = [pscustomobject]@{ Stato = $state; Prove = $proofs.Count; Problemi = $problems }
  }
  return $result
}

function Test-IsoDate([string]$text, [datetime]$today) {
  $d = [datetime]::MinValue
  if ($text -notmatch '^\d{4}-\d{2}-\d{2}$') { return "la data '$text' non e' nel formato AAAA-MM-GG" }
  if (-not [datetime]::TryParseExact($text, "yyyy-MM-dd", [System.Globalization.CultureInfo]::InvariantCulture, [System.Globalization.DateTimeStyles]::None, [ref]$d)) { return "la data '$text' non esiste" }
  if ($d.Date -gt $today.Date) { return "la data $text e' nel futuro" }
  return $null
}

# Il rimando alla riga di docs\decisioni.md: il marcatore compare in UNA sola riga, che porta la data e gli id richiesti.
function Test-DecisionLine([string[]]$lines, [string]$marker, [string]$date, [string[]]$mustMention, [string]$what) {
  if (-not $marker) { return "$what : manca il marcatore della riga di docs/decisioni.md" }
  $hits = @($lines | Where-Object { $_.Contains($marker) })
  if ($hits.Count -eq 0) { return "$what : il marcatore '$marker' non compare in docs/decisioni.md" }
  if ($hits.Count -gt 1) { return "$what : il marcatore '$marker' compare in $($hits.Count) righe di docs/decisioni.md (deve essere una sola)" }
  $line = $hits[0]
  if (-not $line.Contains($date)) { return "$what : la riga di docs/decisioni.md non porta la data $date" }
  foreach ($id in $mustMention) { if (-not $line.Contains($id)) { return "$what : la riga di docs/decisioni.md non nomina $id" } }
  return $null
}

# Il controllo vero. Config = recinzioni.json letto; Source = stati letti da FeatureFlags.kt (Get-FlagsSourceStates); Dex = stati letti dal
# bytecode (Get-FenceDexStates) o $null se non si legge la release; DecisionLines = le righe di docs\decisioni.md. Ritorna i problemi
# (elenco vuoto = tutto in regola) e non scrive niente.
function Test-RecinzioniStato($Config, [hashtable]$Source, $Dex, [string[]]$DecisionLines, [datetime]$Today) {
  $problems = New-Object System.Collections.Generic.List[string]
  if ($Config.versione -ne 1) { $problems.Add("recinzioni.json: versione sconosciuta ($($Config.versione))"); return @($problems) }
  $declared = @{}
  $items = @($Config.recinzioni)
  if ($items.Count -ne 4) { $problems.Add("recinzioni.json: servono esattamente quattro recinzioni, ce ne sono $($items.Count)") }
  foreach ($item in $items) {
    $id = [string]$item.id
    if (-not $script:FenceTable.Contains($id)) { $problems.Add("recinzioni.json: recinzione sconosciuta '$id'"); continue }
    if ($declared.ContainsKey($id)) { $problems.Add("recinzioni.json: $id compare due volte"); continue }
    $t = $script:FenceTable[$id]
    if ([string]$item.campo -ne $t.campo) { $problems.Add("recinzioni.json: $id ha il campo '$($item.campo)', deve essere '$($t.campo)'") }
    if ([string]$item.area -ne $t.area) { $problems.Add("recinzioni.json: $id ha l'area '$($item.area)', deve essere '$($t.area)'") }
    if ([string]$item.stato -notin @("accesa", "spenta")) { $problems.Add("recinzioni.json: $id ha lo stato '$($item.stato)' (solo accesa o spenta)"); continue }
    $declared[$id] = [string]$item.stato
  }
  foreach ($id in $script:FenceIds) { if (-not $declared.ContainsKey($id)) { $problems.Add("recinzioni.json: manca $id") } }
  # Le aree della fotografia: una per recinzione, con le cartelle attese per radice.
  $areas = @($Config.aree)
  if ($areas.Count -ne 4) { $problems.Add("recinzioni.json: servono quattro aree, ce ne sono $($areas.Count)") }
  $seen = @{}
  foreach ($a in $areas) {
    $fence = [string]$a.recinzione
    if (-not $script:FenceTable.Contains($fence)) { $problems.Add("recinzioni.json: l'area '$($a.id)' nomina la recinzione sconosciuta '$fence'"); continue }
    if ($seen.ContainsKey($fence)) { $problems.Add("recinzioni.json: due aree per $fence") }
    $seen[$fence] = $true
    if ([string]$a.id -ne $script:FenceTable[$fence].area) { $problems.Add("recinzioni.json: l'area di $fence si chiama '$($a.id)', deve essere '$($script:FenceTable[$fence].area)'") }
    foreach ($role in @("privata", "comune")) {
      if ($null -eq $a.cartelleAttese -or $null -eq $a.cartelleAttese.$role) { $problems.Add("recinzioni.json: l'area $($a.id) non ha cartelleAttese.$role"); continue }
      foreach ($p in @($a.cartelleAttese.$role)) {
        $s = [string]$p
        if (-not $s -or $s -match '(^|[\\/])\.\.([\\/]|$)|^[a-zA-Z]:|^[\\/]') { $problems.Add("recinzioni.json: cartella attesa non valida in $($a.id): '$s'") }
      }
    }
  }
  if ($problems.Count -gt 0) { return @($problems) }

  # Lo stato osservato deve coincidere con quello dichiarato (sorgente e, se c'e', bytecode della release).
  foreach ($id in $script:FenceIds) {
    $src = $Source[$id]
    if ($src -eq "ignota") { $problems.Add("$id : FeatureFlags.kt non si legge (RELEASE senza '$($script:FenceTable[$id].campo)' come costante true/false)") }
    elseif ($src -ne $declared[$id]) { $problems.Add("$id : recinzioni.json la dichiara $($declared[$id]) ma FeatureFlags.kt la ha $src") }
    if ($Dex) {
      $d = $Dex[$id]
      if ($d.Stato -eq "incerta") { $problems.Add("$id : il bytecode della release non dice lo stato (" + ($d.Problemi -join "; ") + ")") }
      elseif ($d.Stato -ne $declared[$id]) { $problems.Add("$id : recinzioni.json la dichiara $($declared[$id]) ma il bytecode della release la ha $($d.Stato)") }
    }
  }
  # Il consenso unico e il ritorno indietro parziale.
  $off = @($script:FenceIds | Where-Object { $declared[$_] -eq "spenta" })
  $on = @($script:FenceIds | Where-Object { $declared[$_] -eq "accesa" })
  $consent = $Config.consenso
  $partial = $Config.ritornoParziale
  $partialOn = ($null -ne $partial) -and ([bool]$partial.dichiarato)
  $consentEmpty = ($null -eq $consent) -or ((-not [string]$consent.data) -and (-not [string]$consent.marcatore))
  if ($off.Count -eq 0) {
    if (-not $consentEmpty) { $problems.Add("consenso: le quattro recinzioni sono accese ma il consenso non e' vuoto (un ritorno indietro completo lo svuota; la storia resta in docs/decisioni.md)") }
    if ($partialOn) { $problems.Add("ritornoParziale: dichiarato, ma tutte le recinzioni sono accese (non e' un ritorno parziale)") }
    return @($problems)
  }
  # Almeno una spenta: serve il consenso del passo unico (data, marcatore, riga di decisioni.md con i quattro id).
  if ($consentEmpty) { $problems.Add("consenso: " + ($off -join ", ") + " spenta senza il consenso unico (data e marcatore in recinzioni.json)") }
  else {
    $dateProblem = Test-IsoDate ([string]$consent.data) $Today
    if ($dateProblem) { $problems.Add("consenso: $dateProblem") }
    else {
      $lineProblem = Test-DecisionLine $DecisionLines ([string]$consent.marcatore) ([string]$consent.data) $script:FenceIds "consenso"
      if ($lineProblem) { $problems.Add($lineProblem) }
    }
  }
  if ($on.Count -eq 0) {
    if ($partialOn) { $problems.Add("ritornoParziale: dichiarato, ma tutte le recinzioni sono spente (non e' un ritorno parziale)") }
    return @($problems)
  }
  # Stato misto: ammesso solo con un ritorno indietro parziale dichiarato.
  if (-not $partialOn) { $problems.Add("stato misto (spente: " + ($off -join ", ") + "; accese: " + ($on -join ", ") + ") senza un ritorno indietro parziale dichiarato in recinzioni.json") ; return @($problems) }
  if (-not [string]$partial.motivo) { $problems.Add("ritornoParziale: manca il motivo") }
  $partialDate = Test-IsoDate ([string]$partial.data) $Today
  if ($partialDate) { $problems.Add("ritornoParziale: $partialDate") }
  else {
    if (-not $consentEmpty -and -not (Test-IsoDate ([string]$consent.data) $Today) -and ([datetime]::ParseExact([string]$partial.data, "yyyy-MM-dd", [System.Globalization.CultureInfo]::InvariantCulture) -lt [datetime]::ParseExact([string]$consent.data, "yyyy-MM-dd", [System.Globalization.CultureInfo]::InvariantCulture))) {
      $problems.Add("ritornoParziale: la data e' anteriore al consenso")
    }
    $lineProblem = Test-DecisionLine $DecisionLines ([string]$partial.marcatore) ([string]$partial.data) $on "ritornoParziale"
    if ($lineProblem) { $problems.Add($lineProblem) }
  }
  return @($problems)
}

# Il giro completo sui file: legge recinzioni.json, FeatureFlags.kt e docs\decisioni.md dai percorsi dati (check.ps1 passa quelli veri,
# le prove le copie temporanee sotto build\) e ritorna i problemi. $DexStates = $null per il solo controllo statico.
function Invoke-RecinzioniCheck([string]$JsonPath, [string]$FlagsPath, [string]$DecisioniPath, $DexStates, [datetime]$Today) {
  $config = Read-RecinzioniJson $JsonPath
  $source = Get-FlagsSourceStates ([System.IO.File]::ReadAllText($FlagsPath, (New-Object System.Text.UTF8Encoding($false))))
  $lines = [System.IO.File]::ReadAllLines($DecisioniPath, (New-Object System.Text.UTF8Encoding($false)))
  return @(Test-RecinzioniStato $config $source $DexStates $lines $Today)
}
