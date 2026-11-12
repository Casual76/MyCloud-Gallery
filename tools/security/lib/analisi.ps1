# Le letture dei controlli esterni all'app (Fase 10 P11, seconda parte): logcat (semi e stack trace), cattura dell'emulatore (pcap: SMB e
# destinazioni), dumpsys package (componenti esportati e permessi), netstats per UID, bmgr, il servizio di accessibilita' di prova. Ognuna
# restituisce SOLO NUMERI: il testo letto non esce dalla funzione (un seme trovato si conta, non si stampa).

# Quante volte compare ogni seme (anche in minuscolo) nelle righe; mai le righe stesse.
function Measure-SecSeeds([string[]]$Lines, [string[]]$Seeds) {
  $total = 0
  $text = ($Lines -join "`n")
  foreach ($seed in $Seeds) {
    if (-not $seed) { continue }
    $total += ([regex]::Matches($text, [regex]::Escape($seed), [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)).Count
  }
  return $total
}

# Le stack trace dell'app in logcat (formato threadtime o brief): blocchi "at dev.pampa.mycloudgallery..." o "W System.err" del suo pid, e
# le eccezioni per nome (ReadLimitExceededException e la classe che R8 le da'). FATAL e ANR a parte.
function Measure-SecStackTraces([string[]]$Lines, [string]$Package, [string[]]$Pids) {
  $traces = 0; $frames = 0; $fatal = 0; $anr = 0; $readLimit = 0
  $inTrace = $false
  foreach ($line in $Lines) {
    $ours = $false
    foreach ($p in $Pids) { if ($p -and $line -match "^\S+\s+\S+\s+$p\s") { $ours = $true } }
    if ($line -match 'FATAL EXCEPTION' -and ($ours -or $line -match [regex]::Escape($Package))) { $fatal++ }
    if ($line -match 'ANR in ' -and $line -match [regex]::Escape($Package)) { $anr++ }
    if ($line -match 'ReadLimitExceeded|UnreadableRecords') { $readLimit++ }
    $isFrame = ($line -match '\s+at\s+[A-Za-z_$][\w$.]*\(' -and ($ours -or $line -match 'System\.err'))
    if ($isFrame) {
      $frames++
      if (-not $inTrace) { $traces++; $inTrace = $true }
    } else { $inTrace = $false }
  }
  return [ordered]@{ stacktrace = $traces; righe_di_stack = $frames; fatal = $fatal; anr = $anr; read_limit = $readLimit }
}

# --- pcap (la cattura -tcpdump dell'emulatore: Ethernet, classico libpcap) ------------------------------------------------------------
# Conta: SYN verso la porta 445, pacchetti SMB2 SESSION_SETUP (comando 0x0001), il segno NTLMSSP, le destinazioni IPv4 distinte fuori
# dalla rete dell'emulatore (10.0.2.0/24) e i nomi chiesti al DNS, con quanti sono fuori dagli host ammessi (S13 e par. 6.4 del progetto:
# tiles.openfreemap.org, l'host dei modelli, Google e i domini di sistema di Android). La cattura e' di tutto l'emulatore, non della sola app:
# i nomi fuori elenco si contano, non bocciano la voce.
$SecAllowedHostSuffixes = @("openfreemap.org", "huggingface.co", "hf.co", "google.com", "googleapis.com", "gstatic.com", "android.com", "googleusercontent.com", "gvt1.com", "gvt2.com", "1e100.net")

function Get-SecDnsName([byte[]]$Bytes, [int]$Start, [int]$End) {
  $labels = @()
  $at = $Start
  while ($at -lt $End) {
    $len = [int]$Bytes[$at]
    if ($len -eq 0 -or $len -gt 63 -or $at + 1 + $len -gt $End) { break }
    $labels += [System.Text.Encoding]::ASCII.GetString($Bytes, $at + 1, $len)
    $at += 1 + $len
  }
  return ($labels -join ".").ToLowerInvariant()
}

function Read-SecPcap([string]$Path) {
  $bytes = [System.IO.File]::ReadAllBytes($Path)
  $result = [ordered]@{ pacchetti = 0; syn_445 = 0; smb2 = 0; session_setup = 0; ntlmssp = 0; destinazioni = 0; nomi_dns = 0; nomi_fuori_elenco = 0; formato_valido = 0 }
  $names = @{}
  if ($bytes.Length -lt 24) { return $result }
  $magic = [BitConverter]::ToUInt32($bytes, 0)
  $little = $true
  if ($magic -eq 0xa1b2c3d4L -or $magic -eq 0xa1b23c4dL) { $little = $true } elseif ($magic -eq 0xd4c3b2a1L -or $magic -eq 0x4d3cb2a1) { $little = $false } else { return $result }
  $result.formato_valido = 1
  function U32([byte[]]$b, [int]$o, [bool]$le) { if ($le) { return [BitConverter]::ToUInt32($b, $o) } else { return [uint32](([int]$b[$o] -shl 24) -bor ([int]$b[$o + 1] -shl 16) -bor ([int]$b[$o + 2] -shl 8) -bor [int]$b[$o + 3]) } }
  $linkType = U32 $bytes 20 $little
  $dests = @{}
  $ntlm = [System.Text.Encoding]::ASCII.GetBytes("NTLMSSP")
  $at = 24
  while ($at + 16 -le $bytes.Length) {
    $incl = [int](U32 $bytes ($at + 8) $little)
    $start = $at + 16
    if ($incl -lt 0 -or $start + $incl -gt $bytes.Length) { break }
    $result.pacchetti++
    $ip = if ($linkType -eq 1) { $start + 14 } elseif ($linkType -eq 101) { $start } else { -1 }
    if ($ip -ge 0 -and $incl -ge 34 -and ($bytes[$ip] -shr 4) -eq 4) {
      $ihl = ($bytes[$ip] -band 0x0f) * 4
      $proto = $bytes[$ip + 9]
      $dst = "{0}.{1}.{2}.{3}" -f $bytes[$ip + 16], $bytes[$ip + 17], $bytes[$ip + 18], $bytes[$ip + 19]
      if ($dst -notmatch '^10\.0\.2\.' -and $dst -notmatch '^(255\.|0\.|224\.)') { $dests[$dst] = $true }
      if ($proto -eq 17 -and $incl -ge ($ip - $start) + $ihl + 8 + 12) {
        $udp = $ip + $ihl
        $udpDport = ([int]$bytes[$udp + 2] -shl 8) -bor [int]$bytes[$udp + 3]
        if ($udpDport -eq 53) {
          $name = Get-SecDnsName $bytes ($udp + 8 + 12) ($start + $incl)
          if ($name) { $names[$name] = $true }
        }
      }
      if ($proto -eq 6) {
        $tcp = $ip + $ihl
        $dport = ([int]$bytes[$tcp + 2] -shl 8) -bor [int]$bytes[$tcp + 3]
        $sport = ([int]$bytes[$tcp + 0] -shl 8) -bor [int]$bytes[$tcp + 1]
        $flags = [int]$bytes[$tcp + 13]
        if ($dport -eq 445 -and ($flags -band 0x02) -and -not ($flags -band 0x10)) { $result.syn_445++ }
        $payload = $tcp + (($bytes[$tcp + 12] -shr 4) * 4)
        $end = $start + $incl
        if (($dport -eq 445 -or $sport -eq 445) -and $payload + 4 + 16 -le $end) {
          # NetBIOS (4 byte) poi l'intestazione SMB2: FE 'S' 'M' 'B', il comando a +12 (2 byte, little endian).
          if ($bytes[$payload + 4] -eq 0xFE -and $bytes[$payload + 5] -eq 0x53 -and $bytes[$payload + 6] -eq 0x4D -and $bytes[$payload + 7] -eq 0x42) {
            $result.smb2++
            $command = [int]$bytes[$payload + 16] -bor ([int]$bytes[$payload + 17] -shl 8)
            if ($command -eq 1) { $result.session_setup++ }
          }
          for ($k = $payload; $k -le $end - $ntlm.Length; $k++) {
            $hit = $true
            for ($j = 0; $j -lt $ntlm.Length; $j++) { if ($bytes[$k + $j] -ne $ntlm[$j]) { $hit = $false; break } }
            if ($hit) { $result.ntlmssp++; break }
          }
        }
      }
    }
    $at = $start + $incl
  }
  $result.destinazioni = $dests.Count
  $result.nomi_dns = $names.Count
  foreach ($n in $names.Keys) {
    $ok = $false
    foreach ($suffix in $SecAllowedHostSuffixes) { if ($n -eq $suffix -or $n.EndsWith("." + $suffix)) { $ok = $true } }
    if (-not $ok) { $result.nomi_fuori_elenco++ }
  }
  return $result
}

# --- dumpsys package: componenti con filtri (quindi raggiungibili da fuori), provider, permessi chiesti e concessi -------------------
function Read-SecPackageDump([string[]]$Lines, [string]$Package) {
  $components = @{}
  $requested = @{}
  $granted = @{}
  $section = ""
  foreach ($line in $Lines) {
    if ($line -match '^\s*(requested permissions|install permissions|runtime permissions):') { $section = $Matches[1]; continue }
    if ($line -match '^\S' -or $line -match '^\s{0,2}[A-Z][A-Za-z ]+:$') { if ($line -notmatch 'permissions:') { $section = "" } }
    $m = [regex]::Match($line, [regex]::Escape($Package) + '/(\.?[A-Za-z0-9_.$]+)')
    if ($m.Success) {
      $name = $m.Groups[1].Value
      if ($name.StartsWith(".")) { $name = $Package.Replace(".debug", "") + $name }
      $components[$name] = $true
    }
    if ($section -eq "requested permissions" -and $line -match '^\s+([a-zA-Z0-9_.]+)\s*$') { $requested[$Matches[1]] = $true }
    if ($section -match 'install|runtime' -and $line -match '^\s+([a-zA-Z0-9_.]+): granted=true') { $granted[$Matches[1]] = $true }
  }
  $uid = ($Lines | Where-Object { $_ -match '^\s*(userId|appId)=(\d+)' } | Select-Object -First 1)
  $uidValue = if ($uid -and $uid -match '=(\d+)') { [int]$Matches[1] } else { -1 }
  $flags = ($Lines | Where-Object { $_ -match '^\s*(pkgFlags|flags)=\[' }) -join " "
  return [pscustomobject]@{
    Components = @($components.Keys | Sort-Object); Requested = @($requested.Keys | Sort-Object); Granted = @($granted.Keys | Sort-Object)
    Uid = $uidValue; Debuggable = ($flags -match '\bDEBUGGABLE\b'); AllowBackup = ($flags -match '\bALLOW_BACKUP\b')
  }
}

# L'elenco chiuso dei componenti esportati della release (gradle\verify-release-manifest.gradle, lo stesso di check.ps1).
function Get-SecAllowedExported([string]$Root) {
  $gradle = Get-Content -LiteralPath (Join-Path $Root "gradle\verify-release-manifest.gradle") -Raw
  $allowed = @{}
  foreach ($key in @("allowedExported", "exportedRequiresPermission")) {
    $block = [regex]::Match($gradle, "(?s)$key\s*:\s*\[(.*?)\n\s*\],")
    if (-not $block.Success) { continue }
    foreach ($line in ($block.Groups[1].Value -split '\r?\n')) {
      $code = ($line -replace '//.*$', '')
      $m = [regex]::Match($code, "'([^']+)'")
      if ($m.Success) { $allowed[$m.Groups[1].Value] = $true }
    }
  }
  # I5 (12/11/2026): i tre ingressi del launcher (alias della MainActivity, uno per icona) di launcherAliasPolicy: esportati e voluti.
  $aliases = [regex]::Match($gradle, "(?s)launcherAliasPolicy\s*:\s*\[.*?'aliases'\s*:\s*'([^']+)'")
  if ($aliases.Success) { foreach ($name in ($aliases.Groups[1].Value -split '\|')) { $allowed[$name] = $true } }
  return $allowed
}

# netstats per UID: byte ricevuti e mandati dall'UID dell'app (tutte le interfacce), dalle righe "uid=<n> ... rb=<n> ... tb=<n>".
function Read-SecNetstats([string[]]$Lines, [int]$Uid) {
  $rx = 0L; $tx = 0L; $inUid = $false
  foreach ($line in $Lines) {
    if ($line -match '\buid=(-?\d+)') { $inUid = ([int]$Matches[1] -eq $Uid) }
    if ($inUid -and $line -match '\brb=(\d+).*\btb=(\d+)') { $rx += [long]$Matches[1]; $tx += [long]$Matches[2] }
  }
  return [ordered]@{ byte_ricevuti = $rx; byte_mandati = $tx }
}

# Il servizio di accessibilita' di prova (FieldProbeA11y): per pacchetto, quante finestre lette e quanti nodi con testo.
function Read-SecA11yProbe([string[]]$Lines, [string]$Package) {
  $windows = 0; $withText = 0; $unreadable = 0; $otherWithText = 0
  foreach ($line in $Lines) {
    if ($line -notmatch 'FieldProbeA11y') { continue }
    if ($line -match 'finestra (\S+): nodi=(\d+) conTesto=(\d+)') {
      if ($Matches[1] -eq $Package) { $windows++; $withText += [int]$Matches[3] } else { $otherWithText += [int]$Matches[3] }
    } elseif ($line -match 'finestra (\S+): nessuna radice leggibile' -and $Matches[1] -eq $Package) { $unreadable++ }
  }
  return [ordered]@{ finestre_app = $windows; testi_letti_app = $withText; finestre_app_illeggibili = $unreadable; testi_letti_altre_app = $otherWithText }
}

# bmgr backupnow: l'app esclusa dal backup (allowBackup=false) non deve dare "Success".
function Read-SecBmgr([string[]]$Lines, [string]$Package) {
  $text = $Lines -join "`n"
  $success = ([regex]::Matches($text, "Package $([regex]::Escape($Package)) with result: Success")).Count
  $notAllowed = ([regex]::Matches($text, "Package $([regex]::Escape($Package)) with result: (Backup is not allowed|Package not eligible|not eligible)")).Count
  return [ordered]@{ backup_riuscito = $success; backup_non_ammesso = $notAllowed }
}
