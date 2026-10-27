<#
.SYNOPSIS
  Crea l'ambiente Python isolato per convertire i modelli dei volti (Fase 8, P1; docs/fase8-progetto.md §2.2), FUORI dal repository.

.DESCRIPTION
  Serve Python 3.11 (onnx2tf 1.29.x e TensorFlow 2.19 non hanno altro per Windows qui). L'ambiente nasce in <Root>\venv\<Nome> e riceve
  SOLO i pacchetti di requirements.txt, ciascuno controllato con le impronte SHA-256 (`--require-hashes`, `--no-deps`: il grafo e' scritto
  per intero). La cache di pip, i file temporanei e quelli di Keras restano sotto <Root> (cache\, tmp\): niente fuori da quella cartella,
  salvo il Python che si usa per creare l'ambiente. Si scarica solo da pypi.org e files.pythonhosted.org.

.EXAMPLE
  powershell -NoProfile -File tools\models\faces\setup-env.ps1 -Root "C:\VibeCoded Projects\mcg-models\faces"
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$Root,
  [string]$Nome = "conv"
)

$ErrorActionPreference = "Stop"
$qui = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = (Resolve-Path (Join-Path $qui "..\..\..")).Path
$Root = [System.IO.Path]::GetFullPath($Root)
if ($Root -eq $repo -or $Root.StartsWith($repo + "\")) { throw "La cartella dei modelli non puo' stare dentro il repository (mai ambienti Python nel repository)." }

foreach ($sotto in @("venv", "cache", "tmp")) { New-Item -ItemType Directory -Force -Path (Join-Path $Root $sotto) | Out-Null }
$env:PIP_CACHE_DIR = Join-Path $Root "cache\pip"
$env:TMP = Join-Path $Root "tmp"
$env:TEMP = $env:TMP
$env:XDG_CACHE_HOME = Join-Path $Root "cache"
$env:KERAS_HOME = Join-Path $Root "cache\keras"
$env:PIP_DISABLE_PIP_VERSION_CHECK = "1"

$venv = Join-Path $Root "venv\$Nome"
if (Test-Path -LiteralPath $venv) { throw "$venv esiste gia': cancellarlo a mano se si vuole rifarlo." }
& py -3.11 -I -m venv $venv
if ($LASTEXITCODE -ne 0) { throw "Python 3.11 non trovato (py -3.11)." }
$python = Join-Path $venv "Scripts\python.exe"
& $python -I -m pip install --require-hashes --no-deps -r (Join-Path $qui "requirements.txt")
if ($LASTEXITCODE -ne 0) { throw "pip non ha installato i pacchetti (una impronta non torna o un pacchetto manca)." }
& $python -I -c "import tensorflow, onnx, onnxruntime, onnx2tf, cv2, numpy; print('ok: tensorflow', tensorflow.__version__, '| onnx', onnx.__version__, '| onnxruntime', onnxruntime.__version__, '| numpy', numpy.__version__, '| opencv', cv2.__version__)"
Write-Host "Ambiente pronto: $python"
