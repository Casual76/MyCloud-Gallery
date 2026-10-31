<#
.SYNOPSIS
  S9, "una modifica di massa simulata fa scattare il congelamento" (Fase 10 P11, docs/fase10-progetto.md par. 2.11; C11).
.DESCRIPTION
  Dal PC, in test-comune/mcg-it/<giro>/massa/: -File file di prova (di norma 2.000), poi un sesto rinominato, un sesto cambiato sul
  posto e un sesto "cifrato" (.locked), oltre la soglia max(50, 5%). Gli elenchi prima e dopo (nomi, dimensioni, date, fileId del NAS)
  vanno in build\security\<data>\s9-elenco.tsv e lo scanner vero (core-nasmedia, S9MassChangeTest) li rigioca: deve congelare al primo
  giro. Alla fine il giro si toglie. Il livello emulatore (congelamento nell'app, avviso, "Ho controllato") e' della seconda parte.
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\security\mass-change.ps1 -File 2000
#>
param([int]$File = 2000, [switch]$DryRun)
& (Join-Path $PSScriptRoot "run-all.ps1") -Livello pc -Voce S9 -File $File -DryRun:$DryRun
exit $LASTEXITCODE
