<#
.SYNOPSIS
  Genera il SET OCR SINTETICO (Fase 6, P9; docs\fase6-progetto.md par. 5.3) e il resoconto dell'errore per carattere.

.DESCRIPTION
  Compila i sorgenti Java di tools\fixtures\src (solo JDK) ed esegue OcrSetGenerator. Il set ha scontrini di negozi inventati,
  chat e schermate finte, cartelli con accenti, apostrofi ed euro; testo da 10 a 40 px (altezza del carattere nell'immagine che il
  lettore vede), dritto, ruotato, sfocato, con rumore, con artefatti JPEG e in prospettiva, a 1024 e a 2048 px. Tutto il testo e'
  inventato: nessun dato personale.

  Scrive in <Out>\MCG-ocr\: immagini\*.jpg, manifest.tsv (il testo atteso) e LEGGIMI.txt.

  La MISURA VERA con ML Kit e' sul dispositivo: si copia la cartella con adb e la corsa di debug "Lettura di prova" (Diagnostica >
  Sviluppo > Valutazione della ricerca) scrive letto.tsv. Con -Resoconto il PC ne ricava l'errore per carattere e la ricerca per
  parola (resoconto-ocr.md e .tsv). Con -LettoreFinto il resoconto si prova su un lettore inventato: NON e' una misura.

  NON COMMITTARE l'uscita: di default e' tools\fixtures\out\ (la regola "out/" del .gitignore la copre).

.PARAMETER Out
  La cartella di uscita (default tools\fixtures\out).

.PARAMETER Rapido
  Un set ridotto (72 immagini invece di 216): per provare il generatore.

.PARAMETER Resoconto
  Il file letto.tsv (id<TAB>testo letto) di una corsa: non genera niente, scrive il resoconto.

.PARAMETER Etichetta
  Chi ha letto, per il titolo del resoconto (per esempio "ML Kit 16.0.1 su S25").

.PARAMETER LettoreFinto
  Prova del resoconto con un lettore finto (stampa FINTO).

.PARAMETER Verifica
  Le prove del generatore sul solo JDK (errore per carattere, formato, determinismo, degradazioni): non genera niente.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\fixtures\generate-ocr.ps1
  powershell -ExecutionPolicy Bypass -File tools\fixtures\generate-ocr.ps1 -Resoconto C:\temp\letto.tsv -Etichetta "ML Kit su S25"
#>
param(
  [string]$Out = "",
  [switch]$Rapido,
  [string]$Resoconto = "",
  [string]$Etichetta = "",
  [switch]$LettoreFinto,
  [switch]$Verifica
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

$classes = Join-Path ([System.IO.Path]::GetTempPath()) "mcg-fixtures-classes-ocr"
if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
New-Item -ItemType Directory -Force $classes | Out-Null
$sources = Get-ChildItem -Recurse -Filter *.java (Join-Path $PSScriptRoot "src\dev\pampa\mycloudgallery\fixtures\ocr") | ForEach-Object { $_.FullName }
& $javac -encoding UTF-8 -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw "Compilazione fallita." }

if ($Verifica) {
  & $java "-Dfile.encoding=UTF-8" "-Djava.awt.headless=true" -cp $classes dev.pampa.mycloudgallery.fixtures.ocr.OcrSelfCheck
  if ($LASTEXITCODE -ne 0) { throw "Verifica fallita." }
  return
}

$arguments = @($Out)
if ($Rapido) { $arguments += "--rapido" }
if ($LettoreFinto) { $arguments += "--lettore-finto" }
if ($Resoconto -ne "") {
  $arguments += @("--resoconto", $Resoconto)
  if ($Etichetta -ne "") { $arguments += @("--etichetta", $Etichetta) }
}
& $java -Xmx768m "-Dfile.encoding=UTF-8" "-Djava.awt.headless=true" -cp $classes dev.pampa.mycloudgallery.fixtures.ocr.OcrSetGenerator @arguments
if ($LASTEXITCODE -ne 0) { throw "Generazione fallita." }
