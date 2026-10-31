<#
.SYNOPSIS
  S3, "un segmento ostile (.., record enormi, immagini malformate) non fa danni" (Fase 10 P11, docs/fase10-progetto.md par. 2.11).
.DESCRIPTION
  -Livello jvm: le mutazioni dei codec sul corpus di tools\security\corpus (100.000 per codec) e le immagini malformate sul lettore dei
  metadati (S3 (a), S3 (c) JVM). -Livello pc: segmenti ostili e immagini malformate scritti in mcg-it/<giro>/ delle share di prova,
  riletti dal NAS e passati ai codec veri (S3 (b), S3 (c)). La decodifica sull'emulatore e sull'S25 e' della seconda parte.
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\security\hostile.ps1 -Livello pc
#>
param([ValidateSet("jvm", "pc")][string]$Livello = "pc", [switch]$DryRun)
& (Join-Path $PSScriptRoot "run-all.ps1") -Livello $Livello -Voce S3 -DryRun:$DryRun
exit $LASTEXITCODE
