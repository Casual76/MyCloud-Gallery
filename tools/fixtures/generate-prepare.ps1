<#
.SYNOPSIS
  Genera i campioni della preparazione (Fase 4, P6): video con l'indice in coda e in testa, video verticale, APV finto, DNG
  piccolo e, se c'e' ffmpeg, la versione decodificabile (H.264, tre HEIC veri, APV vero), piu' il JPEG da 200 MP.

.DESCRIPTION
  Compila i sorgenti Java di tools\fixtures\src (solo JDK) ed esegue PrepareFixtureGenerator. Scrive in <Out>\MCG-prepara\
  i file e MANIFEST.tsv con cio' che ci si aspetta da ciascuno. Gli stessi sorgenti (PrepareFixtures) sono usati dai test di
  core-prepare, quindi i valori attesi sono quelli che i test verificano su JVM.

  NON COMMITTARE l'uscita: di default e' tools\fixtures\out\ (la regola "out/" del .gitignore la copre; nessuna aggiunta
  serve). I video finti stanno sui 20 MB, i veri sui 10-20 MB, il JPEG da 200 MP sui 70 MB. Il generatore non tocca il
  telefono: per la prova F6 di docs\fase4-progetto.md si copiano sull'area di prova del NAS con il consenso dell'utente, o sul
  telefono con `adb push <Out>\MCG-prepara /sdcard/Download/MCG-prepara`, e a fine prove si cancella la cartella.

.PARAMETER Out
  La cartella di uscita (default tools\fixtures\out).

.PARAMETER SkipBig
  Salta il JPEG da 200 MP (circa 70 MB, un quarto di minuto).

.PARAMETER NoFfmpeg
  Non cerca ffmpeg: genera solo i campioni "finti" (strutture vere, fotogrammi finti).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\fixtures\generate-prepare.ps1 -SkipBig
#>
param(
  [string]$Out = "",
  [switch]$SkipBig,
  [switch]$NoFfmpeg
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

$classes = Join-Path ([System.IO.Path]::GetTempPath()) "mcg-fixtures-classes-prepare"
if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
New-Item -ItemType Directory -Force $classes | Out-Null
$sources = Get-ChildItem -Recurse -Filter *.java (Join-Path $PSScriptRoot "src") | ForEach-Object { $_.FullName }
& $javac -encoding UTF-8 -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw "Compilazione fallita." }

$arguments = @($Out)
if ($SkipBig) { $arguments += "--skip-big" }
if ($NoFfmpeg) { $arguments += "--no-ffmpeg" }
& $java -Xmx768m "-Dfile.encoding=UTF-8" "-Djava.awt.headless=true" -cp $classes dev.pampa.mycloudgallery.fixtures.PrepareFixtureGenerator @arguments
if ($LASTEXITCODE -ne 0) { throw "Generazione fallita." }
