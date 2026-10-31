<#
.SYNOPSIS
  S2, "dati firmati da dispositivi non approvati o revocati rifiutati" (Fase 10 P11, docs/fase10-progetto.md par. 2.11).
.DESCRIPTION
  Livello jvm (questo script): la matrice di S2RogueDeviceTest su core-trust, per ogni dato che un dispositivo firma (teste di indice,
  dati personali e AI, testa d'album, testa dei volti, stato, prenotazione): chiave mai vista, in attesa, revocata, persa, dismessa e
  una chiave che si spaccia per un dispositivo approvato.
  Il livello "famiglia di prova" ed emulatore (un dispositivo canaglia e uno revocato dall'admin che scrivono nelle share di prova, i
  contatori di rifiuto in Diagnostica) e' della seconda parte di P11.
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\security\rogue-device.ps1
#>
param([switch]$DryRun)
& (Join-Path $PSScriptRoot "run-all.ps1") -Livello jvm -Voce S2 -DryRun:$DryRun
exit $LASTEXITCODE
