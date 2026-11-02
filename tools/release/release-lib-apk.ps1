# Libreria di tools\release\release.ps1 (Fase 10, P12; docs/fase10-progetto.md par. 2.9 passo 5, D32, C443): letture dell'APK firmato.
# Si carica dopo release-lib-core.ps1. Solo ASCII, PowerShell 5.1. Ogni lettura che non riesce e' un problema, mai un "salta".

$script:apkInspectLoaded = $false
function Initialize-ApkInspect {
  if ($script:apkInspectLoaded) { return }
  Add-Type -Language CSharp -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

public class ReleaseNativeLib {
  public string Name;
  public int Method;
  public long DataOffset;
  public long Size;
  public List<long> LoadAligns = new List<long>();
  public string Problem;
}

public static class ReleaseApkInspect {
  // Le voci lib/<abi>/*.so dalla directory centrale dello zip: compressione, posizione dei dati e allineamento dei segmenti LOAD dell'ELF
  // (come tools/check.ps1, passo 7e: Android 15+ su pagine da 16 KB).
  public static List<ReleaseNativeLib> NativeLibs(string apk) {
    var result = new List<ReleaseNativeLib>();
    byte[] data = File.ReadAllBytes(apk);
    int eocd = -1;
    for (int i = data.Length - 22; i >= Math.Max(0, data.Length - 65557); i--) {
      if (BitConverter.ToUInt32(data, i) == 0x06054b50) { eocd = i; break; }
    }
    if (eocd < 0) throw new InvalidOperationException("fine della directory centrale non trovata");
    int count = BitConverter.ToUInt16(data, eocd + 10);
    long p = BitConverter.ToUInt32(data, eocd + 16);
    for (int e = 0; e < count; e++) {
      if (BitConverter.ToUInt32(data, (int)p) != 0x02014b50) throw new InvalidOperationException("directory centrale non valida");
      int method = BitConverter.ToUInt16(data, (int)p + 10);
      long usize = BitConverter.ToUInt32(data, (int)p + 24);
      int nameLen = BitConverter.ToUInt16(data, (int)p + 28);
      int extraLen = BitConverter.ToUInt16(data, (int)p + 30);
      int commentLen = BitConverter.ToUInt16(data, (int)p + 32);
      long local = BitConverter.ToUInt32(data, (int)p + 42);
      string name = Encoding.UTF8.GetString(data, (int)p + 46, nameLen);
      p += 46 + nameLen + extraLen + commentLen;
      if (!name.StartsWith("lib/") || !name.EndsWith(".so")) continue;
      var fact = new ReleaseNativeLib { Name = name, Method = method, Size = usize };
      int localName = BitConverter.ToUInt16(data, (int)local + 26);
      int localExtra = BitConverter.ToUInt16(data, (int)local + 28);
      fact.DataOffset = local + 30 + localName + localExtra;
      if (method == 0) {
        long b = fact.DataOffset;
        if (data[b] != 0x7f || data[b + 1] != (byte)'E' || data[b + 2] != (byte)'L' || data[b + 3] != (byte)'F' || data[b + 4] != 2) {
          fact.Problem = "non e' un ELF a 64 bit";
        } else {
          long phoff = BitConverter.ToInt64(data, (int)(b + 0x20));
          int phentsize = BitConverter.ToUInt16(data, (int)(b + 0x36));
          int phnum = BitConverter.ToUInt16(data, (int)(b + 0x38));
          for (int h = 0; h < phnum; h++) {
            long ph = b + phoff + (long)h * phentsize;
            if (BitConverter.ToUInt32(data, (int)ph) == 1) fact.LoadAligns.Add(BitConverter.ToInt64(data, (int)(ph + 0x30)));
          }
        }
      }
      result.Add(fact);
    }
    return result;
  }
}
'@
  $script:apkInspectLoaded = $true
}

# Un elenco di stringhe fra apici di `name : [ ... ]` in gradle\verify-release-manifest.gradle (la stessa fonte di verifyReleaseManifest).
function Get-PolicyList([string]$Name) {
  $gradle = Read-Utf8 (Resolve-RepoFile "gradle\verify-release-manifest.gradle")
  $list = [regex]::Match($gradle, "(?s)$Name\s*:\s*\[(.*?)\n\s*\],")
  if (-not $list.Success) { throw "non trovo $Name in gradle\verify-release-manifest.gradle" }
  $values = @()
  foreach ($line in ($list.Groups[1].Value -split '\r?\n')) {
    $code = ($line -replace '//.*$', '').Trim()
    foreach ($m in [regex]::Matches($code, "'([^']+)'")) { $values += $m.Groups[1].Value }
  }
  if ($values.Count -eq 0) { throw "$Name e' vuoto in gradle\verify-release-manifest.gradle" }
  return $values
}

# I componenti esportati ammessi: nome -> permesso che il componente deve dichiarare ("" = nessun obbligo).
function Get-AllowedExported {
  $allowed = @{}
  foreach ($name in (Get-PolicyList "allowedExported")) { $allowed[$name] = "" }
  $gradle = Read-Utf8 (Resolve-RepoFile "gradle\verify-release-manifest.gradle")
  $map = [regex]::Match($gradle, '(?s)exportedRequiresPermission\s*:\s*\[(.*?)\n\s*\],')
  if ($map.Success) {
    foreach ($line in ($map.Groups[1].Value -split '\r?\n')) {
      $code = ($line -replace '//.*$', '').Trim()
      $pair = [regex]::Match($code, "'([^']+)'\s*:\s*'([^']+)'")
      if ($pair.Success) { $allowed[$pair.Groups[1].Value] = $pair.Groups[2].Value }
    }
  }
  return $allowed
}

# I permessi documentati in docs/sicurezza.md 4-bis (i nomi corti in maiuscolo fra apici inversi, tranne la riga "Non richiesti").
function Get-DocumentedPermissions {
  $text = Read-Utf8 (Resolve-RepoFile "docs\sicurezza.md")
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

# Il manifest DENTRO l'APK (quello che si installa): debuggable, backup, componenti, permessi definiti, sharedUserId.
function Get-ApkManifestFacts([string]$Aapt2, [string]$Apk) {
  $dump = Invoke-Native -File $Aapt2 -Arguments @("dump", "xmltree", "--file", "AndroidManifest.xml", $Apk)
  if ($dump.Code -ne 0) { throw "aapt2 dump xmltree fallito: " + ($dump.Lines -join " | ") }
  $facts = [ordered]@{ Debuggable = $false; AllowBackup = "(assente)"; Components = @(); DefinedPermissions = @(); SharedUserId = $null }
  $element = ""
  $current = $null
  foreach ($line in $dump.Lines) {
    if ($line -match '^\s*E: ([\w\-]+) ') {
      $element = $Matches[1]
      $current = $null
      if (@("activity", "activity-alias", "service", "receiver", "provider") -contains $element) {
        $current = @{ Kind = $element; Name = ""; Exported = $false; Permission = "" }
        $facts.Components += , $current
      } elseif ($element -eq "permission") {
        $current = @{ Kind = $element; Name = "" }
        $facts.DefinedPermissions += , $current
      }
      continue
    }
    if ($line -match '^\s*A: .*android:([A-Za-z]+)\(0x[0-9a-fA-F]+\)=(.*)$') {
      $attr = $Matches[1]
      $value = $Matches[2].Trim()
      $isTrue = ($value -match '^true' -or $value -match '0xffffffff')
      if ($element -eq "manifest" -and $attr -eq "sharedUserId") { $facts.SharedUserId = $value }
      if ($element -eq "application") {
        if ($attr -eq "debuggable") { $facts.Debuggable = $isTrue }
        if ($attr -eq "allowBackup") { $facts.AllowBackup = if ($isTrue) { "true" } else { "false" } }
      }
      if ($current) {
        if ($attr -eq "name" -and $value -match '^"([^"]*)"') { $current.Name = $Matches[1] }
        if ($attr -eq "exported" -and $current.Kind -ne "permission") { $current.Exported = $isTrue }
        if ($attr -eq "permission" -and $current.Kind -ne "permission" -and $value -match '^"([^"]*)"') { $current.Permission = $Matches[1] }
      }
    }
  }
  return $facts
}

# I nomi che nella release non devono esserci: l'elenco `$debugRemnants` di tools\check.ps1 (passo 7b), letto dal file, cosi' i due controlli
# non si allontanano mai. Se non si legge l'elenco il passo FALLISCE (non si ricopia una lista a mano).
function Get-DebugRemnants {
  $text = Read-Utf8 (Resolve-RepoFile "tools\check.ps1")
  $start = $text.IndexOf('$debugRemnants = @(')
  if ($start -lt 0) { throw 'non trovo $debugRemnants in tools\check.ps1' }
  $block = $text.Substring($start)
  $end = [regex]::Match($block, '(?m)^\)\s*$')
  if (-not $end.Success) { throw 'non trovo la fine di $debugRemnants in tools\check.ps1' }
  $names = New-Object System.Collections.Generic.List[string]
  foreach ($line in ($block.Substring(0, $end.Index) -split '\r?\n')) {
    if ($line.Trim().StartsWith("#")) { continue }
    foreach ($m in [regex]::Matches($line, '"((?:[^"`]|`.)*)"')) { $names.Add(($m.Groups[1].Value -replace '`(.)', '$1')) }
  }
  if ($names.Count -lt 20) { throw "l'elenco dei resti di debug di check.ps1 ha solo $($names.Count) nomi: lettura non affidabile" }
  return @($names | Select-Object -Unique)
}

# Le due classi di una libreria che hanno "Benchmark" nel nome senza essere misura (stesso elenco di check.ps1).
function Get-AllowedBenchmarkTypes {
  $text = Read-Utf8 (Resolve-RepoFile "tools\check.ps1")
  $match = [regex]::Match($text, '(?s)allowedLibraryBenchmarkTypes\s*=\s*@\((.*?)\)')
  if (-not $match.Success) { throw 'non trovo $allowedLibraryBenchmarkTypes in tools\check.ps1' }
  return @([regex]::Matches($match.Groups[1].Value, '"([^"]+)"') | ForEach-Object { $_.Groups[1].Value })
}

# Il testo Latin-1 di ogni classes*.dex: i descrittori e le stringhe sono ASCII nella tabella delle stringhe. La MainActivity deve esserci,
# altrimenti la lettura non e' affidabile.
function Get-DexTexts([string]$Apk) {
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $latin1 = [System.Text.Encoding]::GetEncoding(28591)
  $zip = [System.IO.Compression.ZipFile]::OpenRead($Apk)
  try {
    $texts = New-Object System.Collections.Generic.List[string]
    foreach ($entry in @($zip.Entries | Where-Object { $_.FullName -match '^classes\d*\.dex$' })) {
      $stream = $entry.Open()
      try {
        $buffer = New-Object System.IO.MemoryStream
        $stream.CopyTo($buffer)
      } finally {
        $stream.Dispose()
      }
      $texts.Add($latin1.GetString($buffer.ToArray()))
    }
  } finally {
    $zip.Dispose()
  }
  if ($texts.Count -eq 0) { throw "nessun classes*.dex nell'APK" }
  $sawMain = $false
  foreach ($text in $texts) { if ($text.Contains("Ldev/pampa/mycloudgallery/MainActivity;")) { $sawMain = $true } }
  if (-not $sawMain) { throw "lettura del dex non affidabile: la MainActivity non compare in nessun classes*.dex" }
  return @($texts)
}

function Find-InDex($Texts, [string]$Pattern) {
  $found = @{}
  $regex = New-Object System.Text.RegularExpressions.Regex($Pattern)
  foreach ($text in $Texts) {
    foreach ($m in $regex.Matches($text)) { $found[$m.Value] = $true }
  }
  return @($found.Keys | Sort-Object)
}
