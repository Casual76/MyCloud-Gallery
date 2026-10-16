# Un adb FINTO: imita il telefono in una cartella (FAKE_PHONE), per provare tools\chaos\s25.ps1 senza telefono. Niente rete, niente adb vero.
$root = $env:FAKE_PHONE
if (-not $root) { Write-Output "FAKE_PHONE non impostata"; exit 9 }
$log = Join-Path $root "adb-calls.log"
$all = @($args)
Add-Content -LiteralPath $log -Value ($all -join " | ")

function Map-Path([string]$p) {
  # /sdcard/... -> <FAKE_PHONE>\sdcard\...
  return Join-Path $root ($p.TrimStart('/').Replace('/', '\'))
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
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Copy-Item -LiteralPath $local -Destination $dest -Recurse -Force
    Write-Output "1 file pushed"
    exit 0
  }
  "shell" {
    $line = ($rest -join " ")
    if ($line -match '^pm path (.+)$') { Write-Output "package:/data/app/fake/base.apk"; exit 0 }
    if ($line -match '^pidof ') { exit 0 }
    if ($line -match '^svc wifi') { exit 0 }
    if ($line -match '^mkdir -p (.+)$') { foreach ($d in ($Matches[1] -split '\s+')) { New-Item -ItemType Directory -Force -Path (Map-Path $d) | Out-Null }; exit 0 }
    if ($line -match '^cp (\S+) (\S+)$') { Copy-Item -LiteralPath (Map-Path $Matches[1]) -Destination (Map-Path $Matches[2]) -Force; exit 0 }
    if ($line -match "^sh -c '?dd if=/dev/urandom bs=1048576 count=(\d+) >> (\S+?)'?$") {
      # 16 byte per MB: il telefono finto non tiene i GB
      $bytes = New-Object byte[] (16 * [int]$Matches[1])
      (New-Object System.Random(5)).NextBytes($bytes)
      $f = Map-Path $Matches[2]
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
