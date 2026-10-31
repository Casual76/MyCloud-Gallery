<#
.SYNOPSIS
  ManifestTool: chiavi e firma del manifest di rilascio di MyCloud Gallery (Fase 10 P1; docs/fase10-progetto.md par. 2.8, D29-D31).

.DESCRIPTION
  Compila lo strumento con i test di core-release (stesso codec e stesso verificatore dell'app; nessuna dipendenza nuova, nessuno
  scaricamento) e lo lancia con java diretto, cosi' la frase segreta si legge dalla console senza mostrarla.

    keys     genera K1 e K2 (P-256) in %USERPROFILE%\.mycloudgallery\release\manifest-keys.p12 con frase segreta; stampa le righe per
             ReleaseKeys.kt e docs\sicurezza.md par. 5.6 e il foglio di K2 da tenere offline
    sign     firma release\manifest.json in release\manifest.mcgr (K1; --key k2 la riserva; --test-key la chiave di prova KT), con un seq
             che solo cresce, e riverifica la busta con il verificatore dell'app prima di scriverla
    verify   verifica una busta con le chiavi fissate nell'app (--test-key: anche KT) e la racconta (--version-code n: lo stato per n)
    seq      dice l'ultimo seq firmato e il prossimo
    restore  ricostruisce K2 dal foglio offline

  Gli agenti firmano SOLO con --test-key (KT) e prendono prima un posto Gradle (gradle-slot.ps1). Le chiavi vere non entrano mai nel
  repository (tools\check.ps1 passi 3 e 3b).

.PARAMETER SkipBuild
  Non ricompila: usa il classpath scritto dall'ultima compilazione (core-release\build\manifest-tool\classpath.txt).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\release\manifest-tool.ps1 sign --in release\manifest.json --out release\manifest.mcgr
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\release\manifest-tool.ps1 sign --test-key --in release\manifest.json --out build\manifest-tool\prova.mcgr
#>
# Niente blocco param(): uno script "avanzato" prenderebbe --in e --out per i suoi parametri comuni (-InformationAction, -OutVariable).
$SkipBuild = $false
$Rest = @()
foreach ($arg in $args) {
  if ([string]$arg -eq "-SkipBuild") { $SkipBuild = $true } else { $Rest += [string]$arg }
}

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$classpathFile = Join-Path $repo "core-release\build\manifest-tool\classpath.txt"

Push-Location $repo
try {
  if (-not $SkipBuild) {
    & (Join-Path $repo "gradlew.bat") ":core-release:manifestToolClasspath" "-q" "--no-daemon" "--max-workers=2"
    if ($LASTEXITCODE -ne 0) { throw "Compilazione di ManifestTool non riuscita." }
  }
  if (-not (Test-Path -LiteralPath $classpathFile)) { throw "Manca $classpathFile : lancia senza -SkipBuild." }
  $classpath = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
  $java = "java"
  if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) { $java = Join-Path $env:JAVA_HOME "bin\java.exe" }
  # Il classpath passa dalla variabile CLASSPATH: una riga di comando di Windows ha un tetto di lunghezza.
  $env:CLASSPATH = $classpath
  & $java "-Dfile.encoding=UTF-8" dev.pampa.mycloudgallery.tools.release.ManifestTool @Rest
  exit $LASTEXITCODE
} finally {
  Pop-Location
}
