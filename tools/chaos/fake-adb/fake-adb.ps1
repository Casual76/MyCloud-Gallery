# Un adb FINTO: imita il telefono in una cartella (FAKE_PHONE), per provare tools\chaos\s25.ps1 senza telefono. Niente rete, niente adb vero.
$root = $env:FAKE_PHONE
if (-not $root) { Write-Output "FAKE_PHONE non impostata"; exit 9 }
$log = Join-Path $root "adb-calls.log"
$all = @($args)
Add-Content -LiteralPath $log -Value ($all -join " | ")

# --- Il processo dell'app (modo ai, DB-2): la cartella `proc\<pacchetto>.pid` dice se e' vivo e con quale pid ---------------------------------
# `pidof` risponde con il pid (o esce 1 se il processo non c'e'), `monkey -p` lo fa nascere, `run-as <pacchetto> kill -9 <pid>` e `am crash|kill|force-stop` lo
# tolgono. Con FAKE_TEAR_ON_KILL=1 la morte lascia a meta' l'ultima scrittura del file dei vettori (mezzo record in coda), come una morte vera durante `append`.
function Get-ProcFile([string]$package) {
  $dir = Join-Path $root "proc"
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  return Join-Path $dir ($package + ".pid")
}

function Start-FakeProcess([string]$package) {
  $counter = Join-Path (Join-Path $root "proc") "next-pid"
  New-Item -ItemType Directory -Force -Path (Join-Path $root "proc") | Out-Null
  $next = if (Test-Path -LiteralPath $counter) { [int](Get-Content -LiteralPath $counter -Raw).Trim() } else { 4200 }
  Set-Content -LiteralPath (Get-ProcFile $package) -Value $next
  Set-Content -LiteralPath $counter -Value ($next + 1)
}

function Stop-FakeProcess([string]$package) {
  $file = Get-ProcFile $package
  $existed = Test-Path -LiteralPath $file
  Remove-Item -LiteralPath $file -ErrorAction SilentlyContinue
  if ($existed -and $env:FAKE_TEAR_ON_KILL -eq "1") { Tear-Vectors $package }
  return $existed
}

# Il processo muore durante una scrittura: l'ultimo `append` resta a meta'. Il processo nuovo riparte dal multiplo intero (VectorFile.append), quindi la coda
# vecchia, se c'era, sparisce: nel file c'e' al piu' una coda troncata.
function Tear-Vectors([string]$package) {
  $dir = Join-Path $root ("data\data\" + $package + "\no_backup\search\index")
  $file = Get-ChildItem -LiteralPath $dir -Filter "vectors-*.bin" -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not $file) { return }
  $size = if ($env:FAKE_RECORD_SIZE) { [int]$env:FAKE_RECORD_SIZE } else { 1584 }
  $bytes = [System.IO.File]::ReadAllBytes($file.FullName)
  if ($bytes.Length -lt $size) { return }
  $base = [int]([math]::Floor($bytes.Length / $size) * $size)
  $out = New-Object byte[] ($base + [int]($size / 2))
  [Array]::Copy($bytes, 0, $out, 0, $base)
  [Array]::Copy($bytes, 0, $out, $base, [int]($size / 2))
  [System.IO.File]::WriteAllBytes($file.FullName, $out)
}

function Map-Path([string]$p) {
  # /sdcard/... -> <FAKE_PHONE>\sdcard\...
  return Join-Path $root ($p.TrimStart('/').Replace('/', '\'))
}

# --- Modo faces (Fase 8, P12): l'app di debug finta, i suoi file dei volti e il suo file di stato -------------------------------------------------------
# La cartella dell'app finta e' <FAKE_PHONE>\data\data\<pacchetto>: no_backup\ ha i file dei volti, files\prove\f8-status.txt il file di stato che scrive lo scenario.
# `am broadcast -a ...FACES_SCENARIO -p <pacchetto> --es scenario <nome>` esegue lo scenario sul telefono finto: f103-compute crea faces-1.db (con il suo -wal) e scrive
# phase=computed; f103-erase toglie tutti i faces-*.db* e scrive phase=erased (local_done=1, keys=0, faces_db=0); con FAKE_ERASE_LEAVES=1 la cancellazione "dimentica"
# faces-1.db e la chiave (local_done=0, keys=1): il caso rosso. f103-status non fa niente.
function Get-FakeAppDir([string]$package) { return Join-Path $root ("data\data\" + $package) }

function Write-FakeFacesStatus([string]$package, [hashtable]$values) {
  $dir = Join-Path (Get-FakeAppDir $package) "files\prove"
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  $lines = @()
  foreach ($key in @("phase", "state", "generation_present", "local_done", "keys", "faces_db", "erase_done")) { if ($values.ContainsKey($key)) { $lines += ("{0}={1}" -f $key, $values[$key]) } }
  Set-Content -LiteralPath (Join-Path $dir "f8-status.txt") -Value $lines
}

function Invoke-FakeFacesScenario([string]$package, [string]$scenario) {
  $noBackup = Join-Path (Get-FakeAppDir $package) "no_backup"
  New-Item -ItemType Directory -Force -Path $noBackup | Out-Null
  switch ($scenario) {
    "f103-compute" {
      Set-Content -LiteralPath (Join-Path $noBackup "faces-1.db") -Value "db finto"
      Set-Content -LiteralPath (Join-Path $noBackup "faces-1.db-wal") -Value "wal finto"
      Set-Content -LiteralPath (Join-Path $noBackup "faces-local") -Value "stato locale finto"
      Write-FakeFacesStatus $package @{ phase = "computed"; state = "on"; generation_present = 1; local_done = 0; keys = 1; faces_db = 1; erase_done = 0 }
      return $true
    }
    "f103-erase" {
      Get-ChildItem -LiteralPath $noBackup -File -Filter "faces-*.db*" -ErrorAction SilentlyContinue | Remove-Item -Force
      Set-Content -LiteralPath (Join-Path $noBackup "faces-erase") -Value "diario finto"
      Set-Content -LiteralPath (Join-Path $noBackup "faces-local") -Value "stato locale finto"
      if ($env:FAKE_ERASE_LEAVES -eq "1") {
        Set-Content -LiteralPath (Join-Path $noBackup "faces-1.db") -Value "db rimasto"
        Write-FakeFacesStatus $package @{ phase = "erased"; state = "off"; generation_present = 0; local_done = 0; keys = 1; faces_db = 1; erase_done = 0 }
      } else {
        Write-FakeFacesStatus $package @{ phase = "erased"; state = "off"; generation_present = 0; local_done = 1; keys = 0; faces_db = 0; erase_done = 1 }
      }
      return $true
    }
    "f103-status" { return $true }
    default { return $false }
  }
}

# -s <serial>
$i = 0
if ($all.Count -ge 2 -and $all[0] -eq "-s") { $i = 2 }
$cmd = $all[$i]
$rest = @($all | Select-Object -Skip ($i + 1))

switch ($cmd) {
  "get-state" { Write-Output "device"; exit 0 }
  "push" {
    $local = $rest[0]
    $remote = $rest[1].TrimEnd('/')
    $dest = Map-Path $remote
    if ((Test-Path -LiteralPath $local -PathType Container) -and -not (Test-Path -LiteralPath $dest -PathType Container)) {
      # Come `adb push cartella destinazione-che-non-c'e'`: la destinazione E' la cartella, con il contenuto dentro (modo ai, eval-push).
      New-Item -ItemType Directory -Force -Path $dest | Out-Null
      Copy-Item -Path (Join-Path $local "*") -Destination $dest -Recurse -Force
    } else {
      # Come `adb push cartella destinazione-esistente` (la semina del modo backup) o un file in una cartella: va DENTRO la destinazione.
      New-Item -ItemType Directory -Force -Path $dest | Out-Null
      Copy-Item -LiteralPath $local -Destination $dest -Recurse -Force
    }
    Write-Output "1 file pushed"
    exit 0
  }
  "exec-out" {
    # Modo ai: `exec-out run-as <pacchetto> cat <percorso relativo alla cartella dell'app>`: i byte del file, senza conversioni.
    if (($rest -join " ") -match '^run-as (\S+) cat (\S+)$') {
      $file = Join-Path $root ("data\data\" + $Matches[1] + "\" + $Matches[2].Replace('/', '\'))
      if (-not (Test-Path -LiteralPath $file)) { Write-Output "cat: $($Matches[2]): No such file or directory"; exit 1 }
      $bytes = [System.IO.File]::ReadAllBytes($file)
      $out = [Console]::OpenStandardOutput()
      $out.Write($bytes, 0, $bytes.Length)
      $out.Flush()
      exit 0
    }
    Write-Output "fake adb: exec-out non previsto: $($rest -join ' ')"
    exit 7
  }
  "shell" {
    $line = ($rest -join " ")
    if ($line -match '^pm path (.+)$') { Write-Output "package:/data/app/fake/base.apk"; exit 0 }
    if ($line -match '^pidof (\S+)$') {
      $file = Get-ProcFile $Matches[1]
      if (-not (Test-Path -LiteralPath $file)) { exit 1 }
      Write-Output (Get-Content -LiteralPath $file -Raw).Trim()
      exit 0
    }
    if ($line -match '^monkey -p (\S+) -c android\.intent\.category\.LAUNCHER 1$') {
      Start-FakeProcess $Matches[1]
      Write-Output "Events injected: 1"
      exit 0
    }
    if ($line -match '^run-as (\S+) kill -9 (\d+)$') {
      $package = $Matches[1]
      $file = Get-ProcFile $package
      $alive = (Test-Path -LiteralPath $file) -and ((Get-Content -LiteralPath $file -Raw).Trim() -eq $Matches[2])
      if (-not $alive) { Write-Output "kill: ($($Matches[2])) - No such process"; exit 1 }
      [void](Stop-FakeProcess $package)
      Add-Content -LiteralPath $log -Value "morto: $package"
      exit 0
    }
    if ($line -match '^am (crash|kill|force-stop) (\S+)$') {
      if (Stop-FakeProcess $Matches[2]) { Add-Content -LiteralPath $log -Value "morto: $($Matches[2])" }
      exit 0
    }
    if ($line -match '^svc wifi (enable|disable)$') {
      Set-Content -LiteralPath (Join-Path $root "wifi") -Value $(if ($Matches[1] -eq "enable") { "on" } else { "off" })
      exit 0
    }
    if ($line -match '^mkdir -p (.+)$') { foreach ($d in ($Matches[1] -split '\s+')) { New-Item -ItemType Directory -Force -Path (Map-Path $d) | Out-Null }; exit 0 }
    if ($line -match "^run-as (\S+) find (\S+) -type f -exec (stat -c '%s %n'|sha256sum) \{\} \+$") {
      # Modo ai: la cartella dell'app finta e' <FAKE_PHONE>\data\data\<pacchetto>; i percorsi stampati sono relativi ad essa, come `run-as`.
      $app = Join-Path $root ("data\data\" + $Matches[1])
      $dir = $Matches[2]
      $wantsHash = ($Matches[3] -eq "sha256sum")
      $full = Join-Path $app ($dir.Replace('/', '\'))
      if (-not (Test-Path -LiteralPath $full)) { Write-Output "find: '$dir': No such file or directory"; exit 1 }
      foreach ($f in (Get-ChildItem -LiteralPath $full -Recurse -File)) {
        $rel = $f.FullName.Substring($app.Length).Replace('\', '/').TrimStart('/')
        if ($wantsHash) { Write-Output ("{0}  {1}" -f (Get-FileHash -LiteralPath $f.FullName -Algorithm SHA256).Hash.ToLowerInvariant(), $rel) }
        else { Write-Output ("{0} {1}" -f $f.Length, $rel) }
      }
      exit 0
    }
    if ($line -match '^am broadcast -a (\S+) -p (\S+) --es scenario (\S+)$') {
      # Modo faces: solo l'azione degli scenari della Fase 8, mirata a un pacchetto.
      if ($Matches[1] -ne 'dev.pampa.mycloudgallery.debug.action.FACES_SCENARIO' -or -not (Invoke-FakeFacesScenario $Matches[2] $Matches[3])) { Write-Output "fake adb: scenario non previsto: $line"; exit 7 }
      Write-Output "Broadcast completed: result=0"
      exit 0
    }
    if ($line -match '^run-as (\S+) ls -1 (\S+)$') {
      # Modo faces: i nomi dei file di una cartella dell'app finta, uno per riga (come `ls -1`).
      $full = Join-Path (Get-FakeAppDir $Matches[1]) ($Matches[2].Replace('/', '\'))
      if (-not (Test-Path -LiteralPath $full -PathType Container)) { Write-Output "ls: $($Matches[2]): No such file or directory"; exit 1 }
      foreach ($f in (Get-ChildItem -LiteralPath $full | Sort-Object Name)) { Write-Output $f.Name }
      exit 0
    }
    if ($line -match '^run-as (\S+) cat (\S+)$') {
      # Modo faces: il file di stato dell'app finta (shell, non exec-out).
      $file = Join-Path (Get-FakeAppDir $Matches[1]) ($Matches[2].Replace('/', '\'))
      if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { Write-Output "cat: $($Matches[2]): No such file or directory"; exit 1 }
      Get-Content -LiteralPath $file | ForEach-Object { Write-Output $_ }
      exit 0
    }
    if ($line -match '^run-as (\S+) rm -f (files/prove/f8-status\.txt)$') {
      # Modo faces: l'unico file che la pulizia toglie dalla cartella dell'app, per nome intero.
      Remove-Item -LiteralPath (Join-Path (Get-FakeAppDir $Matches[1]) ($Matches[2].Replace('/', '\'))) -Force -ErrorAction SilentlyContinue
      exit 0
    }
    if ($line -match '^chmod -R a\+rX (\S+)$') { exit 0 }
    if ($line -match '^run-as (\S+) mkdir -p (\S+)$') {
      New-Item -ItemType Directory -Force -Path (Join-Path (Join-Path $root ("data\data\" + $Matches[1])) $Matches[2].Replace('/', '\')) | Out-Null
      exit 0
    }
    if ($line -match '^run-as (\S+) cp -r (/data/local/tmp/\S+)/\. (\S+)$') {
      # La cartella dell'app si scrive solo con `run-as`; l'origine e' la copia temporanea scritta dalla shell.
      $source = Map-Path $Matches[2]
      $target = Join-Path (Join-Path $root ("data\data\" + $Matches[1])) $Matches[3].TrimEnd('/').Replace('/', '\')
      if (-not (Test-Path -LiteralPath $source)) { Write-Output "cp: $($Matches[2]): No such file or directory"; exit 1 }
      New-Item -ItemType Directory -Force -Path $target | Out-Null
      Copy-Item -Path (Join-Path $source "*") -Destination $target -Recurse -Force
      exit 0
    }
    if ($line -match '^cp (\S+) (\S+)$') { Copy-Item -LiteralPath (Map-Path $Matches[1]) -Destination (Map-Path $Matches[2]) -Force; exit 0 }
    if ($line -match "^sh -c '?dd if=/dev/urandom bs=(\d+) count=(\d+) >> (\S+?)'?$") {
      # Il megabyte vale 16 byte: il telefono finto non tiene i GB. Una modifica (24 byte) e' vera, con byte che cambiano a ogni chiamata.
      $bs = [int]$Matches[1]
      $count = [int]$Matches[2]
      $length = if ($bs -ge 1048576) { 16 * $count } else { $bs * $count }
      $bytes = New-Object byte[] $length
      (New-Object System.Random(([int]((Get-Date).Ticks % 1000000)) + 5)).NextBytes($bytes)
      $f = Map-Path $Matches[3]
      $stream = [System.IO.File]::Open($f, [System.IO.FileMode]::Append)
      $stream.Write($bytes, 0, $bytes.Length)
      $stream.Close()
      exit 0
    }
    if ($line -match '^content call') { Write-Output "Result: Bundle[{}]"; exit 0 }
    if ($line -match '^content query .*_data') {
      $n = 0
      foreach ($f in (Get-ChildItem -LiteralPath (Join-Path $root "sdcard") -Recurse -File -ErrorAction SilentlyContinue)) {
        $rel = $f.FullName.Substring($root.Length).Replace('\', '/')
        Write-Output ("Row: {0} _data=/storage/emulated/0{1}" -f $n++, $rel.Substring("/sdcard".Length))
      }
      exit 0
    }
    if ($line -match '^find (.+?) -type f -exec sha256sum \{\} \+$') {
      foreach ($d in ($Matches[1] -split '\s+')) {
        $full = Map-Path $d
        if (Test-Path -LiteralPath $full) {
          foreach ($f in (Get-ChildItem -LiteralPath $full -Recurse -File)) {
            $rel = $f.FullName.Substring($root.Length).Replace('\', '/')
            Write-Output ("{0}  {1}" -f (Get-FileHash -LiteralPath $f.FullName -Algorithm SHA256).Hash.ToLowerInvariant(), $rel)
          }
        }
      }
      exit 0
    }
    if ($line -match "^find (.+?) -type f -exec stat -c '%s %n' \{\} \+$") {
      foreach ($d in ($Matches[1] -split '\s+')) {
        $full = Map-Path $d
        if (Test-Path -LiteralPath $full) {
          foreach ($f in (Get-ChildItem -LiteralPath $full -Recurse -File)) {
            $rel = $f.FullName.Substring($root.Length).Replace('\', '/')
            Write-Output ("{0} {1}" -f $f.Length, $rel)
          }
        }
      }
      exit 0
    }
    if ($line -match '^rm -rf (\S+)$') { Remove-Item -LiteralPath (Map-Path $Matches[1]) -Recurse -Force -ErrorAction SilentlyContinue; exit 0 }
    Write-Output "fake adb: comando shell non previsto: $line"
    exit 7
  }
  default { Write-Output "fake adb: comando non previsto: $cmd"; exit 8 }
}
