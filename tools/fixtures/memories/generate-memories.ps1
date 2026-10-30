<#
.SYNOPSIS
  Genera la LIBRERIA DI PROVA DEI RICORDI (Fase 9, P11; docs\fase9-progetto.md par. 5.3): JPEG piccoli con l'EXIF, con le date RELATIVE al giorno della prova
  (anniversari, viaggi, una festa, un evento, giorni di soli screenshot, giorni tristi nei concetti dei vettori finti). Nessun dato personale, nessun GPS.

.DESCRIPTION
  La Libreria sta nel codice Kotlin (FamilyBuilder, core-memories\src\test\...\eval\); il test MemoriesFixtureExportTest la scrive come testo (spec-media.tsv, con giorni
  relativi: mai una data) in core-memories\build\memories-fixtures\. Questo script lancia il test (se serve) e poi generate_memories.py (solo Python, nessuna libreria),
  che scrive i JPEG per il giorno -Oggi (di default oggi). Cosi' quello che il generatore disegna e quello che la JVM misura sono la stessa cosa.

  Scrive in <Out>\MCG-ricordi\: telefono\DCIM\mcg-prova\, telefono\Pictures\mcg-prova\ (screenshot), MANIFEST.tsv, concetti.tsv (i concetti dei vettori finti),
  nascoste.txt (le foto da nascondere a mano per provare i Nascosti) e LEGGIMI.txt con i comandi `adb push` per il telefono di prova.

  NON COMMITTARE l'uscita: di default e' tools\fixtures\memories\out\ (il .gitignore la copre e NoPrivateMemoriesEvalInGitTest lo controlla). Lo script non tocca il
  telefono. Il test (Gradle) su un PC condiviso va lanciato con il posto del semaforo: con -Spec si salta e si usa una specifica gia' scritta.

.PARAMETER Out
  La cartella di uscita (default tools\fixtures\memories\out).

.PARAMETER Oggi
  Il giorno della prova, aaaa-mm-gg (default oggi): gli anniversari cadono relativi a lui.

.PARAMETER Rigenera
  Rifa la specifica con Gradle anche se c'e' gia'.

.PARAMETER Spec
  Una specifica gia' scritta (spec-media.tsv): salta Gradle.

.PARAMETER SoloManifesto
  Scrive i manifesti senza i JPEG.

.PARAMETER Verifica
  Le prove del generatore sul campione (campione\spec-campione.tsv): EXIF riletto, nessun GPS, uscita deterministica, date relative a due giorni di prova diversi.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\fixtures\memories\generate-memories.ps1 -Verifica
  powershell -ExecutionPolicy Bypass -File tools\fixtures\memories\generate-memories.ps1 -Oggi <aaaa-mm-gg>
#>
param(
  [string]$Out = "",
  [string]$Oggi = "",
  [switch]$Rigenera,
  [string]$Spec = "",
  [switch]$SoloManifesto,
  [switch]$Verifica
)

$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$repo = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $here))
if ($Out -eq "") { $Out = Join-Path $here "out" }
$script = Join-Path $here "generate_memories.py"

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
  if ($LASTEXITCODE -ne 0) { throw "generate_memories.py ha fallito (codice $LASTEXITCODE)." }
}

if ($Verifica) {
  Invoke-Python @("--verifica")
  return
}

if ($Spec -eq "") {
  $dir = Join-Path $repo "core-memories\build\memories-fixtures"
  $Spec = Join-Path $dir "spec-media.tsv"
  if ($Rigenera -or -not (Test-Path -LiteralPath $Spec)) {
    Write-Host "Scrivo la specifica della Libreria con Gradle (MemoriesFixtureExportTest)..."
    Push-Location $repo
    try {
      & (Join-Path $repo "gradlew.bat") ":core-memories:testDebugUnitTest" "--tests" "*MemoriesFixtureExportTest" "--max-workers=2"
      if ($LASTEXITCODE -ne 0) { throw "Gradle ha fallito (codice $LASTEXITCODE)." }
    } finally { Pop-Location }
  }
}
if (-not (Test-Path -LiteralPath $Spec)) { throw "Manca la specifica: $Spec" }

$arguments = @("--spec", $Spec, "--out", $Out)
if ($Oggi -ne "") { $arguments += @("--oggi", $Oggi) }
if ($SoloManifesto) { $arguments += "--solo-manifesto" }
Invoke-Python $arguments
Write-Host "Fatto. I comandi per il telefono di prova sono in $Out\MCG-ricordi\LEGGIMI.txt"
