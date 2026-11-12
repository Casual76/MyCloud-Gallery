<#
.SYNOPSIS
  Prove della regola dei nomi delle versioni (pacchetto U, giro 1 delle critiche; docs/aggiornamenti-in-app.md, "Le versioni"): la forma ammessa
  (Test-VersionName), il confronto delle versioni dell'app (Compare-EngineVersion, come compareVersions dell'engine) e la crescita del nome in
  release/versions.txt (Test-VersionRows). Solo funzioni di release-lib-core.ps1: niente SDK, niente rete. Codice di uscita 0 solo se passano tutte.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$script:Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
. (Join-Path $PSScriptRoot "release-lib-core.ps1")

$work = Join-Path $script:Root "build\versioni-prove"
if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
New-Item -ItemType Directory -Path $work -Force | Out-Null
$utf8 = New-Object Text.UTF8Encoding($false)
$failures = New-Object System.Collections.Generic.List[string]
$count = 0

function Assert-That([string]$name, [bool]$condition, [string]$detail = "") {
  $script:count++
  if (-not $condition) { $script:failures.Add($name + $(if ($detail) { ": " + $detail } else { "" })); Write-Host ("  [KO] " + $name + $(if ($detail) { " -- " + $detail } else { "" })) -ForegroundColor Red }
  else { Write-Host ("  [ok] " + $name) -ForegroundColor DarkGray }
}

Write-Host "`nLa forma ammessa" -ForegroundColor Cyan
foreach ($good in @("1.0.0", "0.9.12", "12.3.4", "1.1.0-beta.01", "1.1.0-beta.99", "9999.9999.9999")) {
  Assert-That "$good passa" (Test-VersionName $good)
}
foreach ($bad in @("v1.1.0", "1.1.0+build.5", "1.1.0.0", "1.1", "01.1.0", "1.1.0-beta.3", "1.1.0-beta.100", "1.1.0-rc.01", "1.1.0-beta", " 1.1.0", "10000.0.0", "1.1.0-BETA.01")) {
  Assert-That "'$bad' non passa" (-not (Test-VersionName $bad))
}

Write-Host "`nIl confronto dell'app (come compareVersions di engine-foundation)" -ForegroundColor Cyan
$table = @(
  @("1.1.0", "1.0.0", 1), @("1.0.0", "1.0.0", 0), @("1.2", "1.2.0", 0), @("1.1.0-beta.02", "1.1.0-beta.01", 1),
  @("1.1.0", "1.1.0-beta.02", 1), @("1.0.0-beta.02", "1.0.0", -1), @("1.1.0-beta.01", "1.0.0", 1),
  # La trappola che la forma a due cifre evita: come testo beta.10 viene prima di beta.9.
  @("1.1.0-beta.10", "1.1.0-beta.9", -1), @("1.1.0-beta.10", "1.1.0-beta.09", 1), @("10.0.0", "9.9.9", 1)
)
foreach ($row in $table) {
  $got = Compare-EngineVersion $row[0] $row[1]
  Assert-That ("{0} contro {1}: {2}" -f $row[0], $row[1], $row[2]) ($got -eq $row[2]) "ottenuto $got"
}

Write-Host "`nIl nome cresce in release/versions.txt" -ForegroundColor Cyan
$sha = "a" * 64
function Test-Rows([string]$Text, [string]$Version, [string]$Channel) {
  $path = Join-Path $work ("versions-" + [guid]::NewGuid().ToString("N") + ".txt")
  [IO.File]::WriteAllText($path, $Text, $utf8)
  return @(Test-VersionRows (Read-VersionRows $path) $Version $Channel)
}
$p = Test-Rows "1.0.0  1  pubblicata  beta  2026-11-20  $sha`n1.1.0-beta.01  2  prevista  beta  -  -`n" "1.1.0-beta.01" "beta"
Assert-That "dopo la prima beta 1.0.0 la prossima 1.1.0-beta.01 (codice 2) passa" ($p.Count -eq 0) ($p -join "; ")
$p = Test-Rows "1.0.0  1  pubblicata  beta  2026-11-20  $sha`n1.0.0-beta.02  2  prevista  beta  -  -`n" "1.0.0-beta.02" "beta"
Assert-That "una 1.0.0-beta.02 dopo la 1.0.0 non passa (sembrerebbe un passo indietro)" ((($p -join "; ")) -like "*non e' piu' nuovo di 1.0.0*") ($p -join "; ")
$p = Test-Rows "1.0.0  1  pubblicata  beta  2026-11-20  $sha`n1.0.0  2  prevista  beta  -  -`n" "1.0.0" "beta"
Assert-That "un APK nuovo con lo stesso nome (codice piu' alto) non passa" ((($p -join "; ")) -like "*non e' piu' nuovo*") ($p -join "; ")
$p = Test-Rows "1.0.0  1  pubblicata  beta  2026-11-20  $sha`n1.0.0  1  prevista  stable  -  -`n" "1.0.0" "stable"
Assert-That "la promozione dello stesso APK da beta a stable passa" ($p.Count -eq 0) ($p -join "; ")
$p = Test-Rows "1.1.0-beta.01  2  pubblicata  beta  2026-11-20  $sha`n1.1.0  3  prevista  stable  -  -`n" "1.1.0" "stable"
Assert-That "dalla beta alla stabile omonima con un APK nuovo passa" ($p.Count -eq 0) ($p -join "; ")
$p = Test-Rows "1.1.0-beta.1  2  prevista  beta  -  -`n" "1.1.0-beta.1" "beta"
Assert-That "una beta con una cifra non passa (forma)" ((($p -join "; ")) -like "*non valida*") ($p -join "; ")
$p = Test-Rows "v1.1.0  2  prevista  beta  -  -`n" "v1.1.0" "beta"
Assert-That "una v davanti non passa (forma)" ((($p -join "; ")) -like "*non valida*") ($p -join "; ")
$current = @((Read-VersionRows (Join-Path $script:Root "release\versions.txt")).Problems)
Assert-That "il release/versions.txt di oggi ha la forma ammessa" ($current.Count -eq 0) ($current -join "; ")

if ($failures.Count -gt 0) {
  Write-Host ("versioni-prove: {0} prove, {1} fallite" -f $count, $failures.Count) -ForegroundColor Red
  exit 1
}
Write-Host ("versioni-prove: {0} prove, tutte passate" -f $count) -ForegroundColor Green
exit 0
