<#
.SYNOPSIS
  Fotografia delle share, per area, e confronto "prima e dopo" del passo unico (Fase 10 P10; docs/fase10-progetto.md par. 2.10, E-SV2).

.DESCRIPTION
  SOLA LETTURA. Con -Modo Foto elenca ogni file delle due radici (area privata e area comune) con dimensione e data e scrive la
  fotografia in -Uscita (un file di testo, MCGSNAP1: una riga di intestazione e una riga JSON per file). Con -Modo Confronta legge
  due fotografie e dice, PER AREA, quali file sono stati aggiunti, cambiati e tolti, e soprattutto quali FUORI dalle cartelle attese
  di ogni area (recinzioni.json, "aree"): e' E-SV2, il passo si ferma e si torna indietro.

  Le radici sono cartelle (di solito `\\NAS\alessio` e la share dell'area comune, con le credenziali di Windows dell'utente). Lo
  script non scrive, non sposta e non cancella niente nelle radici: apre solo gli elenchi. Lo si prova SOLO su cartelle finte sotto build\
  (tools\release\share-snapshot-prove.ps1).

  RISERVATEZZA (I6 della revisione di sicurezza della Fase 10). La fotografia elenca OGNI file dell'area privata con nome, dimensione e
  data: letta da chi non deve (scritta per sbaglio nell'area comune, per esempio), mostrerebbe a tutta la famiglia i nomi dei file di una
  persona. Per questo:
    - la -Uscita (e il -Report) si scrivono SOLO su un disco FISSO di questo PC: niente percorsi UNC (\\server\share, \\indirizzo\share),
      niente unita' di rete, niente chiavette; e si guarda il percorso VERO (collegamenti, giunzioni, unita' assegnate con subst), non il
      testo: un'uscita che finisce dentro una radice e' rifiutata anche se il suo testo non lo dice;
    - a fine lavoro lo script lo ricorda: tienila solo su questo PC e cancellala (anche dal cestino) quando il confronto e' stato letto e
      il passo e' chiuso, non oltre 7 giorni.
  Limite dichiarato: non si vede un'unita' di rete che sia la share di una cartella di questo stesso PC.

  Comuni a tutte le aree, non contano: Thumbs.db, desktop.ini, .DS_Store e le cartelle di servizio del NAS (@eaDir, .wdmc, .plex,
  .twonkymedia, @appstore, ...): sono ignorati e contati a parte.

  Regole del confronto, per area (recinzioni.json):
    - un file e' "dentro" se il suo percorso comincia con una delle cartelle attese di quell'area (la prima area che lo
      contiene lo prende); altrimenti e' FUORI da ogni area;
    - soloAggiunte (il backup): dentro le sue cartelle si puo' solo aggiungere; un file cambiato o tolto li' e' un'anomalia;
    - spostaSorgenti (gli album condivisi): i file tolti da FUORI, con la stessa dimensione di un file aggiunto dentro le cartelle
      dell'area, sono "spostati" (le sorgenti dell'album), non sono inattesi; il numero dei tolti deve essere uguale a quello degli
      aggiunti fuori da .mycloudgallery/ in quell'area.

  Codici di uscita: 0 tutto dentro le cartelle attese; 3 qualcosa fuori o anomalo (E-SV2); 1 errore (percorsi, fotografie).

.PARAMETER Modo
  Foto o Confronta.

.PARAMETER Privata
  La radice dell'area privata (Foto).

.PARAMETER Comune
  La radice dell'area comune (Foto).

.PARAMETER Uscita
  Dove scrivere la fotografia (Foto): un file nuovo, fuori dalle radici.

.PARAMETER Prima
  La fotografia di prima (Confronta).

.PARAMETER Dopo
  La fotografia di dopo (Confronta).

.PARAMETER Aree
  Il file delle aree (di serie tools\release\recinzioni.json, accanto a questo script).

.PARAMETER Report
  Facoltativo (Confronta): scrive qui il resoconto in JSON (numeri e percorsi, niente altro).

.PARAMETER Massimo
  Quanti percorsi elencare per categoria (di serie 40); il resto e' contato.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\release\share-snapshot.ps1 -Modo Foto -Privata \\NAS\alessio -Comune \\NAS\comune -Uscita C:\Temp\prima.snap
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][ValidateSet("Foto", "Confronta")][string]$Modo,
  [string]$Privata,
  [string]$Comune,
  [string]$Uscita,
  [string]$Prima,
  [string]$Dopo,
  [string]$Aree,
  [string]$Report,
  [int]$Massimo = 40
)

$ErrorActionPreference = "Stop"
if (-not $Aree) { $Aree = Join-Path $PSScriptRoot "recinzioni.json" }

# Lettura e scrittura delle fotografie in C# (centinaia di migliaia di file: PowerShell sarebbe troppo lento). Solo elenchi:
# DirectoryInfo.EnumerateFiles/EnumerateDirectories. Le sole scritture sono nel file di uscita, mai nelle radici.
Add-Type -Language CSharp -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32.SafeHandles;

// Il percorso VERO di una cartella (I6): segue collegamenti simbolici, giunzioni, unita' assegnate con subst e unita' di rete.
public static class RealPath {
  [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
  static extern SafeFileHandle CreateFileW(string name, uint access, uint share, IntPtr security, uint disposition, uint flags, IntPtr template);

  [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
  static extern uint GetFinalPathNameByHandleW(SafeFileHandle handle, StringBuilder buffer, uint size, uint flags);

  public static string OfDirectory(string dir) {
    // accesso 0 (nessuna lettura), condivisione totale, apri esistente, FILE_FLAG_BACKUP_SEMANTICS (serve per aprire una cartella)
    using (SafeFileHandle h = CreateFileW(dir, 0, 7, IntPtr.Zero, 3, 0x02000000, IntPtr.Zero)) {
      if (h.IsInvalid) throw new IOException("non riesco a risolvere la cartella " + dir + " (errore " + Marshal.GetLastWin32Error() + ")");
      var sb = new StringBuilder(1024);
      uint n = GetFinalPathNameByHandleW(h, sb, (uint)sb.Capacity, 0);
      if (n >= sb.Capacity) { sb = new StringBuilder((int)n + 1); n = GetFinalPathNameByHandleW(h, sb, (uint)sb.Capacity, 0); }
      if (n == 0) throw new IOException("non riesco a leggere il percorso vero di " + dir + " (errore " + Marshal.GetLastWin32Error() + ")");
      return sb.ToString();
    }
  }
}

public class SnapEntry {
  public string Role; public string Path; public long Size; public long Mtime; public string Area;
  public string Key { get { return Role + "|" + Path.ToLowerInvariant(); } }
}

public class WalkResult {
  public int Files; public long Bytes; public int Unreadable; public int Ignored; public int Reparse;
  public Dictionary<string, long[]> PerArea = new Dictionary<string, long[]>();
}

public class SnapDiff {
  public List<SnapEntry> Added = new List<SnapEntry>();
  public List<SnapEntry> Removed = new List<SnapEntry>();
  public List<SnapEntry> Changed = new List<SnapEntry>();
}

public static class SnapTool {
  static string Esc(string s) {
    var b = new StringBuilder(s.Length + 8);
    foreach (char c in s) {
      if (c == '"') b.Append("\\\"");
      else if (c == '\\') b.Append("\\\\");
      else if (c < 0x20 || c > 0x7e) b.Append("\\u").Append(((int)c).ToString("x4"));
      else b.Append(c);
    }
    return b.ToString();
  }

  static string Unesc(string s) {
    if (s.IndexOf('\\') < 0) return s;
    var b = new StringBuilder(s.Length);
    for (int i = 0; i < s.Length; i++) {
      char c = s[i];
      if (c != '\\') { b.Append(c); continue; }
      char n = s[++i];
      if (n == 'u') { b.Append((char)Convert.ToInt32(s.Substring(i + 1, 4), 16)); i += 4; }
      else b.Append(n);
    }
    return b.ToString();
  }

  static string AreaOf(string rel, string role, string[][] areas) {
    // areas: per ogni area { id, prefissi della radice privata separati da '|', prefissi della comune separati da '|' }
    string low = rel.ToLowerInvariant();
    foreach (var a in areas) {
      string list = role == "privata" ? a[1] : a[2];
      if (list.Length == 0) continue;
      foreach (var p in list.Split('|')) {
        if (p.Length == 0) continue;
        if (low == p || low.StartsWith(p + "/")) return a[0];
      }
    }
    return "-";
  }

  public static WalkResult Walk(string root, string role, TextWriter w, string[][] areas, string[] ignoreNames, string[] ignoreTop) {
    var res = new WalkResult();
    var stack = new Stack<string[]>();
    stack.Push(new string[] { root, "" });
    while (stack.Count > 0) {
      var cur = stack.Pop();
      DirectoryInfo di = new DirectoryInfo(cur[0]);
      string prefix = cur[1];
      try {
        foreach (FileInfo f in di.EnumerateFiles()) {
          if (Array.IndexOf(ignoreNames, f.Name.ToLowerInvariant()) >= 0) { res.Ignored++; continue; }
          string rel = prefix + f.Name;
          string area = AreaOf(rel, role, areas);
          long mt = (long)(f.LastWriteTimeUtc - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalSeconds;
          w.Write("{\"r\":\"" + role + "\",\"p\":\"" + Esc(rel) + "\",\"d\":" + f.Length + ",\"m\":" + mt + ",\"a\":\"" + area + "\"}\n");
          res.Files++; res.Bytes += f.Length;
          long[] pa;
          if (!res.PerArea.TryGetValue(area, out pa)) { pa = new long[2]; res.PerArea[area] = pa; }
          pa[0]++; pa[1] += f.Length;
        }
        foreach (DirectoryInfo d in di.EnumerateDirectories()) {
          if (prefix.Length == 0 && Array.IndexOf(ignoreTop, d.Name.ToLowerInvariant()) >= 0) { res.Ignored++; continue; }
          if ((d.Attributes & FileAttributes.ReparsePoint) != 0) { res.Reparse++; continue; }
          stack.Push(new string[] { d.FullName, prefix + d.Name + "/" });
        }
      } catch (Exception) { res.Unreadable++; }
    }
    return res;
  }

  static string Field(string line, string key, bool quoted) {
    string k = "\"" + key + "\":";
    int i = line.IndexOf(k, StringComparison.Ordinal);
    if (i < 0) throw new InvalidDataException("riga senza " + key);
    i += k.Length;
    if (quoted) {
      i++; var b = new StringBuilder();
      while (line[i] != '"') { if (line[i] == '\\') { b.Append(line[i]); i++; } b.Append(line[i]); i++; }
      return Unesc(b.ToString());
    }
    int j = i; while (j < line.Length && (char.IsDigit(line[j]) || line[j] == '-')) j++;
    return line.Substring(i, j - i);
  }

  public static Dictionary<string, SnapEntry> Load(string path, out string header) {
    var map = new Dictionary<string, SnapEntry>();
    header = null;
    foreach (string line in File.ReadLines(path, Encoding.ASCII)) {
      if (header == null) { header = line; continue; }
      if (line.Length == 0 || line.StartsWith("{\"riepilogo\"")) continue;
      var e = new SnapEntry();
      e.Role = Field(line, "r", true); e.Path = Field(line, "p", true);
      e.Size = long.Parse(Field(line, "d", false)); e.Mtime = long.Parse(Field(line, "m", false)); e.Area = Field(line, "a", true);
      map[e.Key] = e;
    }
    return map;
  }

  public static SnapDiff Diff(Dictionary<string, SnapEntry> before, Dictionary<string, SnapEntry> after, long toleranceSeconds) {
    var d = new SnapDiff();
    foreach (var kv in after) {
      SnapEntry old;
      if (!before.TryGetValue(kv.Key, out old)) d.Added.Add(kv.Value);
      else if (old.Size != kv.Value.Size || Math.Abs(old.Mtime - kv.Value.Mtime) > toleranceSeconds) d.Changed.Add(kv.Value);
    }
    foreach (var kv in before) if (!after.ContainsKey(kv.Key)) d.Removed.Add(kv.Value);
    return d;
  }
}
'@

function Stop-Con([string]$message, [int]$code = 1) {
  Write-Host ("ERRORE: " + $message) -ForegroundColor Red
  exit $code
}

function Normalize-Prefix([string]$p) { return ($p.Replace("\", "/").Trim("/")).ToLowerInvariant() }

# Le aree di recinzioni.json: id, nome, regole e cartelle attese (minuscole, con la barra) per radice.
function Read-Aree([string]$path) {
  if (-not (Test-Path -LiteralPath $path)) { Stop-Con "manca il file delle aree: $path" }
  $json = [System.IO.File]::ReadAllText($path, (New-Object System.Text.UTF8Encoding($false))) | ConvertFrom-Json
  $list = @()
  foreach ($a in @($json.aree)) {
    $priv = @($a.cartelleAttese.privata | ForEach-Object { Normalize-Prefix ([string]$_) } | Where-Object { $_ })
    $com = @($a.cartelleAttese.comune | ForEach-Object { Normalize-Prefix ([string]$_) } | Where-Object { $_ })
    foreach ($p in ($priv + $com)) {
      if ($p -match '(^|/)\.\.(/|$)|^[a-z]:|^//') { Stop-Con "cartella attesa non valida nell'area $($a.id): $p" }
    }
    $list += [pscustomobject]@{
      id = [string]$a.id; recinzione = [string]$a.recinzione; nome = [string]$a.nome
      soloAggiunte = [bool]$a.soloAggiunte; spostaSorgenti = [bool]$a.spostaSorgenti; privata = $priv; comune = $com
    }
  }
  if ($list.Count -eq 0) { Stop-Con "nessuna area in $path" }
  return $list
}

function Get-AreeHash($aree) {
  $text = ($aree | ForEach-Object { "{0}|{1}|{2}|{3}|{4}" -f $_.id, $_.soloAggiunte, $_.spostaSorgenti, ($_.privata -join ";"), ($_.comune -join ";") }) -join "`n"
  $sha = [System.Security.Cryptography.SHA256]::Create()
  return ([System.BitConverter]::ToString($sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($text))) -replace "-", "").ToLowerInvariant()
}

function Test-InsideRoot([string]$file, [string[]]$roots) {
  $full = [System.IO.Path]::GetFullPath($file).TrimEnd("\")
  foreach ($r in $roots) {
    if (-not $r) { continue }
    $root = [System.IO.Path]::GetFullPath($r).TrimEnd("\")
    if ($full.Equals($root, [System.StringComparison]::OrdinalIgnoreCase) -or $full.StartsWith($root + "\", [System.StringComparison]::OrdinalIgnoreCase)) { return $true }
  }
  return $false
}

# I6 (revisione di sicurezza della Fase 10). La fotografia e il resoconto si scrivono SOLO su un disco FISSO di questo PC, e si guarda il
# percorso VERO, non il testo: niente percorsi di rete (\\server\share, \\indirizzo\share), niente unita' di rete collegate, niente
# collegamenti o giunzioni o unita' assegnate con subst che portino dentro una radice. I controlli sul testo di un percorso di rete non
# toccano mai la rete. Non si vede (limite dichiarato): una unita' di rete che e' la share di una cartella di questo stesso PC.
function Get-FullPath([string]$path) { return $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($path) }

# "Unc" per \\... (di rete o di dispositivo), altrimenti il tipo dell'unita' (Fixed, Network, Removable, ...).
function Get-DriveKind([string]$full) {
  if ($full.StartsWith("\\") -or $full.StartsWith("//")) { return "Unc" }
  $driveRoot = [System.IO.Path]::GetPathRoot($full)
  if (-not $driveRoot) { return "Ignota" }
  try { return [string](New-Object System.IO.DriveInfo($driveRoot)).DriveType } catch { return "Ignota" }
}

function Convert-FinalPath([string]$final) {
  if ($final.StartsWith("\\?\UNC\")) { return "\\" + $final.Substring(8) }
  if ($final.StartsWith("\\?\")) { return $final.Substring(4) }
  return $final
}

function Get-RealDirectory([string]$dir) { return (Convert-FinalPath ([RealPath]::OfDirectory($dir))) }

function Assert-LocalOutput([string]$path, [string[]]$roots, [string]$what) {
  $full = Get-FullPath $path
  $kind = Get-DriveKind $full
  if ($kind -eq "Unc") { Stop-Con "$what e' un percorso UNC ($full): questo file non esce mai verso una share. Salvalo su un disco fisso di questo PC (per esempio C:\Temp)" }
  if ($kind -ne "Fixed") { Stop-Con "$what sta su un'unita' di tipo $kind ($full), non su un disco fisso di questo PC: questo file non esce mai verso una share o una chiavetta" }
  $dir = Split-Path -Parent $full
  if (-not (Test-Path -LiteralPath $dir -PathType Container)) { Stop-Con "la cartella di $what non esiste: $dir" }
  $real = Join-Path (Get-RealDirectory $dir) (Split-Path -Leaf $full)
  $realKind = Get-DriveKind $real
  if ($realKind -ne "Fixed") { Stop-Con "$what, risolto il percorso vero ($real), sta su un'unita' di tipo $realKind e non su un disco fisso di questo PC" }
  $forms = @($full, $real)
  $rootForms = @()
  foreach ($r in @($roots | Where-Object { $_ })) {
    $rootFull = Get-FullPath $r
    $rootForms += $rootFull
    # il percorso vero di una radice si cerca solo se e' su un disco fisso (le radici di rete non si toccano: l'uscita e' sicuramente altrove)
    if ((Get-DriveKind $rootFull) -eq "Fixed" -and (Test-Path -LiteralPath $rootFull -PathType Container)) { $rootForms += (Get-RealDirectory $rootFull) }
  }
  foreach ($form in $forms) {
    if (Test-InsideRoot $form $rootForms) { Stop-Con "$what sta dentro una radice (percorso vero: $real): lo script non scrive mai nelle share" }
  }
}

# L'avviso sulla riservatezza (I6): la fotografia elenca i NOMI di tutti i file dell'area privata.
function Show-PrivacyNotice([string]$what) {
  Write-Host ""
  Write-Host "AVVISO (riservato): $what contiene i NOMI, le dimensioni e le date di tutti i file dell'area privata di chi ha l'account" -ForegroundColor Yellow
  Write-Host "  - e' un dato personale: tienilo SOLO su questo PC (disco fisso), mai in una share, nel cloud, in un messaggio o su una chiavetta;" -ForegroundColor Yellow
  Write-Host "  - cancellalo, anche dal cestino, quando il confronto e' stato letto e il passo e' chiuso (non tenerlo oltre 7 giorni)." -ForegroundColor Yellow
}

function Format-Bytes([long]$b) {
  if ($b -ge 1GB) { return ("{0:N2} GB" -f ($b / 1GB)) }
  if ($b -ge 1MB) { return ("{0:N1} MB" -f ($b / 1MB)) }
  if ($b -ge 1KB) { return ("{0:N0} KB" -f ($b / 1KB)) }
  return ("{0} B" -f $b)
}

$ignoreNames = [string[]]@("thumbs.db", "desktop.ini", ".ds_store")
$ignoreTop = [string[]]@("@eadir", ".wdmc", ".plex", "plex media server", ".twonkymedia", "twonkydata", "twonky", "@appstore", '$recycle.bin', "system volume information", ".recycle")

function Invoke-Foto {
  if (-not $Privata -or -not $Comune -or -not $Uscita) { Stop-Con "Foto vuole -Privata, -Comune e -Uscita" }
  foreach ($r in @($Privata, $Comune)) {
    if (-not (Test-Path -LiteralPath $r -PathType Container)) { Stop-Con "la radice non esiste o non e' una cartella: $r" }
  }
  Assert-LocalOutput $Uscita @($Privata, $Comune) "l'uscita"
  if ((Test-Path -LiteralPath $Uscita) -or (Get-Item -LiteralPath $Uscita -Force -ErrorAction SilentlyContinue)) { Stop-Con "l'uscita esiste gia' (anche un collegamento): non si sovrascrive una fotografia ($Uscita)" }
  $aree = Read-Aree $Aree
  $areas = New-Object 'string[][]' $aree.Count
  for ($i = 0; $i -lt $aree.Count; $i++) { $areas[$i] = [string[]]@($aree[$i].id, ($aree[$i].privata -join "|"), ($aree[$i].comune -join "|")) }
  $header = [ordered]@{
    formato = "MCGSNAP1"; creata = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ"); areeHash = (Get-AreeHash $aree)
    radici = [ordered]@{ privata = $Privata; comune = $Comune }
    aree = @($aree | ForEach-Object { [ordered]@{ id = $_.id; nome = $_.nome; soloAggiunte = $_.soloAggiunte; spostaSorgenti = $_.spostaSorgenti } })
  }
  $writer = New-Object System.IO.StreamWriter($Uscita, $false, [System.Text.Encoding]::ASCII)
  try {
    $writer.Write(($header | ConvertTo-Json -Compress -Depth 4) + "`n")
    $summary = [ordered]@{}
    foreach ($role in @("privata", "comune")) {
      $root = if ($role -eq "privata") { $Privata } else { $Comune }
      $watch = [System.Diagnostics.Stopwatch]::StartNew()
      $res = [SnapTool]::Walk($root, $role, $writer, $areas, $ignoreNames, $ignoreTop)
      $perArea = [ordered]@{}
      foreach ($k in $res.PerArea.Keys) { $perArea[$k] = [ordered]@{ file = $res.PerArea[$k][0]; byte = $res.PerArea[$k][1] } }
      $summary[$role] = [ordered]@{ file = $res.Files; byte = $res.Bytes; nonLeggibili = $res.Unreadable; ignorati = $res.Ignored; collegamenti = $res.Reparse; perArea = $perArea }
      Write-Host ("Radice {0}: {1} file, {2}, {3} cartelle non leggibili, {4} ignorati ({5:N1} s)" -f $role, $res.Files, (Format-Bytes $res.Bytes), $res.Unreadable, $res.Ignored, $watch.Elapsed.TotalSeconds)
      foreach ($a in $aree) {
        $n = $res.PerArea[$a.id]
        if ($n) { Write-Host ("  {0,-8} {1,8} file  {2}" -f $a.id, $n[0], (Format-Bytes $n[1])) } else { Write-Host ("  {0,-8} {1,8} file" -f $a.id, 0) }
      }
      $out = $res.PerArea["-"]
      if ($out) { Write-Host ("  {0,-8} {1,8} file  {2}  (fuori dalle cartelle attese di ogni area)" -f "-", $out[0], (Format-Bytes $out[1])) }
    }
    $writer.Write(("{""riepilogo"":" + ($summary | ConvertTo-Json -Compress -Depth 6) + "}") + "`n")
  } finally {
    $writer.Dispose()
  }
  Write-Host ("Fotografia scritta in {0}" -f $Uscita) -ForegroundColor Green
  Show-PrivacyNotice "la fotografia"
  exit 0
}

function Read-Foto([string]$path) {
  if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { Stop-Con "manca la fotografia: $path" }
  [string]$headerLine = ""
  $map = [SnapTool]::Load($path, [ref]$headerLine)
  if (-not $headerLine -or $headerLine -notmatch '"formato":"MCGSNAP1"') { Stop-Con "non e' una fotografia MCGSNAP1: $path" }
  $summaryLine = Get-Content -LiteralPath $path -Tail 1 -Encoding ASCII
  $summary = $null
  if ($summaryLine -match '^\{"riepilogo"') { $summary = ($summaryLine | ConvertFrom-Json).riepilogo }
  return [pscustomobject]@{ Header = ($headerLine | ConvertFrom-Json); Map = $map; Summary = $summary }
}

function Add-Total($totals, [string]$area, [string]$kind, [long]$bytes) {
  if (-not $totals.ContainsKey($area)) { $totals[$area] = @{ aggiunti = @(0, 0L); cambiati = @(0, 0L); tolti = @(0, 0L) } }
  $totals[$area][$kind][0] += 1
  $totals[$area][$kind][1] += $bytes
}

function Show-List([string]$title, $items, [int]$max) {
  if ($items.Count -eq 0) { return }
  Write-Host ("  {0}: {1}" -f $title, $items.Count) -ForegroundColor Yellow
  foreach ($e in ($items | Select-Object -First $max)) { Write-Host ("    [{0}] {1}  ({2})" -f $e.Role, $e.Path, (Format-Bytes $e.Size)) }
  if ($items.Count -gt $max) { Write-Host ("    ... e altri {0}" -f ($items.Count - $max)) }
}

function Invoke-Confronta {
  if (-not $Prima -or -not $Dopo) { Stop-Con "Confronta vuole -Prima e -Dopo" }
  $before = Read-Foto $Prima
  $after = Read-Foto $Dopo
  $roots = @($after.Header.radici.privata, $after.Header.radici.comune, $before.Header.radici.privata, $before.Header.radici.comune)
  if ($before.Header.areeHash -ne $after.Header.areeHash) { Stop-Con "le due fotografie hanno aree diverse (areeHash): non si confrontano" }
  if ($Report -and ((Test-Path -LiteralPath $Report) -or (Get-Item -LiteralPath $Report -Force -ErrorAction SilentlyContinue))) { Stop-Con "il resoconto esiste gia': $Report" }
  if ($Report) { Assert-LocalOutput $Report $roots "il resoconto" }
  $aree = @($before.Header.aree)
  $diff = [SnapTool]::Diff($before.Map, $after.Map, 2)
  $totals = @{}
  foreach ($e in $diff.Added) { Add-Total $totals $e.Area "aggiunti" $e.Size }
  foreach ($e in $diff.Changed) { Add-Total $totals $e.Area "cambiati" $e.Size }
  foreach ($e in $diff.Removed) { Add-Total $totals $e.Area "tolti" $e.Size }

  $problems = New-Object System.Collections.Generic.List[string]
  $outside = @{ aggiunti = @(); cambiati = @(); tolti = @() }
  $nonAdditive = @()
  $moved = 0
  $removedOut = New-Object System.Collections.Generic.List[object]
  foreach ($e in $diff.Removed) { if ($e.Area -eq "-") { $removedOut.Add($e) } }
  $outside.aggiunti = @($diff.Added | Where-Object { $_.Area -eq "-" })
  $outside.cambiati = @($diff.Changed | Where-Object { $_.Area -eq "-" })
  $sourceNotes = @()
  foreach ($a in $aree) {
    if ($a.soloAggiunte) {
      $nonAdditive += @($diff.Changed | Where-Object { $_.Area -eq $a.id })
      $nonAdditive += @($diff.Removed | Where-Object { $_.Area -eq $a.id })
    }
    if ($a.spostaSorgenti) {
      $copies = @($diff.Added | Where-Object { $_.Area -eq $a.id -and -not $_.Path.ToLowerInvariant().StartsWith(".mycloudgallery/") })
      $pairedSources = 0
      foreach ($c in $copies) {
        $hit = $null
        foreach ($r in $removedOut) { if ($r.Size -eq $c.Size) { $hit = $r; break } }
        if ($hit) { [void]$removedOut.Remove($hit); $pairedSources++ }
      }
      $moved += $pairedSources
      if ($pairedSources -ne $copies.Count) { $sourceNotes += ("{0}: {1} file aggiunti fuori da .mycloudgallery/ ma solo {2} sorgenti tolte con la stessa dimensione (conteggi diversi)" -f $a.id, $copies.Count, $pairedSources) }
    }
  }
  $outside.tolti = $removedOut.ToArray()

  Write-Host "Confronto prima e dopo, per area" -ForegroundColor Cyan
  Write-Host ("  prima: {0} file   dopo: {1} file   spostati (sorgenti degli album): {2}" -f $before.Map.Count, $after.Map.Count, $moved)
  foreach ($a in $aree) {
    $t = $totals[$a.id]
    if ($t) {
      Write-Host ("  {0,-8} aggiunti {1} ({2})  cambiati {3}  tolti {4}" -f $a.id, $t.aggiunti[0], (Format-Bytes $t.aggiunti[1]), $t.cambiati[0], $t.tolti[0])
    } else {
      Write-Host ("  {0,-8} nessun cambiamento" -f $a.id)
    }
  }
  if ($outside.aggiunti.Count + $outside.cambiati.Count + $outside.tolti.Count -gt 0) {
    $problems.Add("file fuori dalle cartelle attese di ogni area: aggiunti $($outside.aggiunti.Count), cambiati $($outside.cambiati.Count), tolti $($outside.tolti.Count)")
  }
  if ($nonAdditive.Count -gt 0) { $problems.Add("file cambiati o tolti dentro un'area che aggiunge soltanto (il backup): $($nonAdditive.Count)") }
  foreach ($n in $sourceNotes) { $problems.Add($n) }
  foreach ($role in @("privata", "comune")) {
    $u1 = if ($before.Summary) { [int]$before.Summary.$role.nonLeggibili } else { 0 }
    $u2 = if ($after.Summary) { [int]$after.Summary.$role.nonLeggibili } else { 0 }
    if ($u1 -ne $u2) { $problems.Add("cartelle non leggibili cambiate nella radice $role ($u1 prima, $u2 dopo): il confronto non e' completo") }
    elseif ($u2 -gt 0) { Write-Host ("  AVVISO: {0} cartelle non leggibili nella radice {1} (uguali prima e dopo)" -f $u2, $role) -ForegroundColor Yellow }
  }
  Show-List "Fuori dalle cartelle attese, aggiunti" $outside.aggiunti $Massimo
  Show-List "Fuori dalle cartelle attese, cambiati" $outside.cambiati $Massimo
  Show-List "Fuori dalle cartelle attese, tolti" $outside.tolti $Massimo
  Show-List "Cambiati o tolti dove si puo' solo aggiungere" $nonAdditive $Massimo

  if ($Report) {
    $out = [ordered]@{
      prima = $before.Header.creata; dopo = $after.Header.creata; spostati = $moved; problemi = @($problems)
      perArea = @($aree | ForEach-Object { $t = $totals[$_.id]; [ordered]@{ id = $_.id; aggiunti = $(if ($t) { $t.aggiunti[0] } else { 0 }); cambiati = $(if ($t) { $t.cambiati[0] } else { 0 }); tolti = $(if ($t) { $t.tolti[0] } else { 0 }) } })
      fuori = [ordered]@{
        aggiunti = @($outside.aggiunti | ForEach-Object { $_.Role + ":" + $_.Path }); cambiati = @($outside.cambiati | ForEach-Object { $_.Role + ":" + $_.Path })
        tolti = @($outside.tolti | ForEach-Object { $_.Role + ":" + $_.Path })
      }
    }
    [System.IO.File]::WriteAllText($Report, ($out | ConvertTo-Json -Depth 5), (New-Object System.Text.UTF8Encoding($false)))
    Show-PrivacyNotice "il resoconto"
  }
  if ($problems.Count -gt 0) {
    Write-Host "DA FERMARE (E-SV2):" -ForegroundColor Red
    foreach ($p in $problems) { Write-Host ("  - " + $p) -ForegroundColor Red }
    exit 3
  }
  Write-Host "Tutto dentro le cartelle attese di ogni area." -ForegroundColor Green
  exit 0
}

try {
  if ($Modo -eq "Foto") { Invoke-Foto } else { Invoke-Confronta }
} catch {
  Stop-Con ($_.Exception.Message + " (riga " + $_.InvocationInfo.ScriptLineNumber + ")")
}
