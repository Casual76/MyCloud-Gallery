<#
.SYNOPSIS
  Genera il set di file di prova per il telefono (Fase 3, P3): date assurde, senza EXIF, corrotti, zero byte, otto
  orientamenti, nomi tipo (WhatsApp, Screenshot, Pixel, Signal, Telegram), motion photo sintetiche, WebP animato,
  HEIC, DNG, raffiche e coppie RAW+JPEG, e un JPEG da 200 MP.

.DESCRIPTION
  Compila i sorgenti Java di tools\fixtures\src (solo JDK, nessuna libreria) e li esegue. Scrive in
  <Out>\MCG-prova\ i file e MANIFEST.tsv con i valori attesi. Gli stessi sorgenti sono usati dai test di core-metadata,
  quindi i valori attesi di docs\fase3-campo.md sono quelli che i test verificano.

  NON COMMITTARE l'uscita: di default e' tools\fixtures\out\ (la regola "out/" del .gitignore la copre) e il JPEG da
  200 MP pesa circa 70 MB. Il generatore non tocca il telefono: per provarli (F2 di docs\fase3-progetto.md) con il
  consenso dell'utente, `adb push <Out>\MCG-prova /sdcard/Pictures/MCG-prova` e poi `adb shell am broadcast` o una
  scansione dal telefono; a fine prove si cancella la cartella.

.PARAMETER Out
  La cartella di uscita (default tools\fixtures\out).

.PARAMETER SkipBig
  Salta il JPEG da 200 MP (circa 70 MB, un quarto di minuto).

.PARAMETER Doc
  Invece di scrivere i file, scrive nel file indicato le tabelle dei valori attesi (da incollare in docs\fase3-campo.md).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\fixtures\generate.ps1 -SkipBig
#>
param(
  [string]$Out = "",
  [switch]$SkipBig,
  [string]$Doc = ""
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if ($Out -eq "") { $Out = Join-Path $repo "tools\fixtures\out" }

$java = "java"
$javac = "javac"
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\javac.exe"))) {
  $java = Join-Path $env:JAVA_HOME "bin\java.exe"
  $javac = Join-Path $env:JAVA_HOME "bin\javac.exe"
}

$classes = Join-Path ([System.IO.Path]::GetTempPath()) "mcg-fixtures-classes"
if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
New-Item -ItemType Directory -Force $classes | Out-Null
$sources = Get-ChildItem -Recurse -Filter *.java (Join-Path $PSScriptRoot "src") | ForEach-Object { $_.FullName }
& $javac -encoding UTF-8 -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw "Compilazione fallita." }

$arguments = @()
if ($Doc -ne "") { $arguments += @("--doc", $Doc) } else { $arguments += $Out }
if ($SkipBig) { $arguments += "--skip-big" }
# Il JPEG da 200 MP si scrive a strisce: bastano pochi centinaia di MB di heap.
& $java -Xmx768m "-Dfile.encoding=UTF-8" "-Djava.awt.headless=true" -cp $classes dev.pampa.mycloudgallery.fixtures.FixtureGenerator @arguments
if ($LASTEXITCODE -ne 0) { throw "Generazione fallita." }
