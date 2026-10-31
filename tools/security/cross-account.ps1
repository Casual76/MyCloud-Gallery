<#
.SYNOPSIS
  S1 (b), "un account non legge dati privati altrui tramite l'indice" (Fase 10 P11, docs/fase10-progetto.md par. 2.11).
.DESCRIPTION
  Dal PC, sul NAS di prova: `test` scrive nella sua area privata file e indice con segni unici (MCG-PRIV-<casuale>), e un indice
  dell'area comune senza; con le credenziali di `test2` si legge tutto cio' che test2 puo' leggere sotto mcg-it/<giro>/ e si cercano
  i segni (atteso: zero; l'area privata di test negata). Serve user2/password2/share2 nel file delle credenziali del banco.
  Il livello jvm (S1 (a), 10.000 mondi) e' run-all.ps1 -Livello jvm -Voce S1; il livello emulatore (S1 (c)) e' della seconda parte.
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\security\cross-account.ps1
#>
param([switch]$DryRun)
& (Join-Path $PSScriptRoot "run-all.ps1") -Livello pc -Voce S1 -DryRun:$DryRun
exit $LASTEXITCODE
