<#
.SYNOPSIS
  Genera i VIAGGI SINTETICI (Fase 7, P12; docs\fase7-progetto.md par. 5.3 punto 1, F87): JPEG con EXIF e GPS veri, fusi orari, giorni senza GPS,
  screenshot, tablet e familiari, dalle 20 storie di P6. Nessun dato personale.

.DESCRIPTION
  Le storie stanno nel codice Kotlin di P6 (SyntheticStory); il test TripFixtureExportTest di core-albums le scrive come testo
  (core-albums\build\trips-fixtures\spec-media.tsv, spec-album.tsv) insieme ai numeri che la JVM misura su QUELLO STESSO testo (attesi.tsv). Questo
  script lancia il test (se serve) e poi generate_trips.py (solo Python, nessuna libreria), che scrive i JPEG. Cosi' quello che il generatore
  disegna e quello che la JVM misura sono la stessa cosa.

  Scrive in <Out>\MCG-viaggi\storia-NN\: telefono\DCIM\mcg-prova\, telefono\Pictures\mcg-prova\ (screenshot), nas-tablet\, nas-famiglia\,
  MANIFEST.tsv e viaggi-sintetici.tsv; e in <Out>\MCG-viaggi\LEGGIMI.txt i comandi `adb push` per il telefono di prova (una storia per volta).

  NON COMMITTARE l'uscita: di default e' tools\fixtures\out\ (la regola "out/" del .gitignore la copre). Lo script non tocca il telefono.

  Il test (Gradle) su un PC condiviso va lanciato con il posto del semaforo; con -Spec si salta e si usa una specifica gia' scritta.

.PARAMETER Out
  La cartella di uscita (default tools\fixtures\out).

.PARAMETER Storia
  Solo queste storie (0-19); di default tutte (35.502 file, circa 30 MB).

.PARAMETER Rigenera
  Rifa la specifica con Gradle anche se c'e' gia'.

.PARAMETER Spec
  Una specifica gia' scritta (spec-media.tsv): salta Gradle. spec-album.tsv e attesi.tsv si cercano nella stessa cartella.

.PARAMETER SoloManifesto
  Scrive i manifesti e i file per lo strumento, senza i JPEG.

.PARAMETER Verifica
  Le prove del generatore sul campione (campione\): EXIF riletto, uscita deterministica e uguale al campione. Non genera niente.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\fixtures\trips\generate-trips.ps1 -Storia 7
  powershell -ExecutionPolicy Bypass -File tools\fixtures\trips\generate-trips.ps1 -Verifica
#>
param(
  [string]$Out = "",
  [int[]]$Storia = @(),
  [switch]$Rigenera,
  [string]$Spec = "",
  [switch]$SoloManifesto,
  [switch]$Verifica
)

$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$repo = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $here))
if ($Out -eq "") { $Out = Join-Path $repo "tools\fixtures\out" }
$script = Join-Path $here "generate_trips.py"

# Python: `python`, `python3` o `py -3`, il primo che risponde.
$python = $null
foreach ($candidate in @(@("python"), @("python3"), @("py", "-3"))) {
  try {
    $probe = & $candidate[0] @($candidate | Select-Object -Skip 1) -c "import sys; print(sys.version_info[0])" 2>$null
    if ($LASTEXITCODE -eq 0 -and "$probe".Trim() -eq "3") { $python = $candidate; break }
  } catch { }
}
if (-not $python) { throw "Serve Python 3 (python, python3 o py -3): il generatore usa solo la libreria standard." }

function Invoke-Python([string[]]$arguments) {
  & $python[0] @($python | Select-Object -Skip 1) -I $script @arguments
  if ($LASTEXITCODE -ne 0) { throw "generate_trips.py ha fallito (codice $LASTEXITCODE)." }
}

if ($Verifica) {
  Invoke-Python @("--verifica")
  # Un decodificatore e un lettore EXIF veri e indipendenti (GDI+ di Windows): ogni file del campione si apre, ha le dimensioni attese
  # e la data e l'ora locali del suo manifesto.
  try { Add-Type -AssemblyName System.Drawing } catch { Write-Host "System.Drawing non c'e': controllo con GDI+ saltato."; return }
  $base = Join-Path $here "campione\storia-99"
  $lines = @(Get-Content -LiteralPath (Join-Path $base "MANIFEST.tsv") | Where-Object { $_ -and -not $_.StartsWith("#") })
  $bad = @()
  foreach ($line in $lines) {
    $c = $line -split "`t"
    $file = Join-Path $base ($c[0] -replace "/", "\")
    $image = [System.Drawing.Image]::FromFile($file)
    try {
      $screen = $c[10] -eq "1"
      if ($image.Width -ne $(if ($screen) { 56 } else { 96 }) -or $image.Height -ne $(if ($screen) { 120 } else { 72 })) { $bad += "dimensioni: $($c[0])" }
      $property = $image.PropertyItems | Where-Object { $_.Id -eq 0x9003 } | Select-Object -First 1
      $stamp = [System.Text.Encoding]::ASCII.GetString($property.Value).TrimEnd([char]0)
      $expected = ("{0} {1:00}:{2:00}:00" -f ($c[3] -replace "-", ":"), [math]::Floor([int]$c[4] / 60), ([int]$c[4] % 60))
      if ($stamp -ne $expected) { $bad += "data: $($c[0])" }
    } finally { $image.Dispose() }
  }
  if ($bad.Count -gt 0) { throw "GDI+ non e' d'accordo con il campione: " + ($bad -join "; ") }
  Write-Host ("GDI+ apre i {0} file del campione, con le dimensioni e la data dello scatto del manifesto." -f $lines.Count)
  return
}

if ($Spec -eq "") {
  $dir = Join-Path $repo "core-albums\build\trips-fixtures"
  $Spec = Join-Path $dir "spec-media.tsv"
  if ($Rigenera -or -not (Test-Path -LiteralPath $Spec)) {
    Write-Host "Scrivo la specifica delle 20 storie con Gradle (TripFixtureExportTest)..."
    Push-Location $repo
    try {
      & (Join-Path $repo "gradlew.bat") ":core-albums:testDebugUnitTest" "--tests" "*TripFixtureExportTest" "--max-workers=2"
      if ($LASTEXITCODE -ne 0) { throw "Gradle ha fallito (codice $LASTEXITCODE)." }
    } finally { Pop-Location }
  }
}
if (-not (Test-Path -LiteralPath $Spec)) { throw "Manca la specifica: $Spec" }
$specDir = Split-Path -Parent $Spec

$arguments = @("--spec", $Spec, "--out", $Out)
$albums = Join-Path $specDir "spec-album.tsv"
$attesi = Join-Path $specDir "attesi.tsv"
if (Test-Path -LiteralPath $albums) { $arguments += @("--albums", $albums) }
if (Test-Path -LiteralPath $attesi) { $arguments += @("--attesi", $attesi) }
if ($Storia.Count -gt 0) { $arguments += "--storia"; $arguments += ($Storia | ForEach-Object { "$_" }) }
if ($SoloManifesto) { $arguments += "--solo-manifesto" }
Invoke-Python $arguments
Write-Host "Fatto. I comandi per il telefono di prova sono in $Out\MCG-viaggi\LEGGIMI.txt"
