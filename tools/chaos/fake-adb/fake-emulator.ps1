# Un emulatore FINTO (Fase 10 P11, seconda parte): accende "Pampa_Tablet" nella cartella del telefono finto (FAKE_PHONE\emulatore-acceso) e,
# con -tcpdump <file>, scrive la cattura preparata dalla prova (FAKE_PHONE\cattura.pcap). Niente emulatore vero, niente rete.
$root = $env:FAKE_PHONE
if (-not $root) { Write-Output "FAKE_PHONE non impostata"; exit 9 }
$all = @($args)
Add-Content -LiteralPath (Join-Path $root "adb-calls.log") -Value ("emulatore: " + ($all -join " | "))
$avd = $null
$tcpdump = $null
for ($i = 0; $i -lt $all.Count; $i++) {
  if ($all[$i] -eq "-avd") { $avd = $all[$i + 1] }
  if ($all[$i] -eq "-tcpdump") { $tcpdump = $all[$i + 1].Trim('"') }
}
if ($avd -ne "Pampa_Tablet") { Add-Content -LiteralPath (Join-Path $root "adb-calls.log") -Value "emulatore rifiutato: $avd"; exit 3 }
Set-Content -LiteralPath (Join-Path $root "emulatore-acceso") -Value $avd
if ($tcpdump -and (Test-Path -LiteralPath (Join-Path $root "cattura.pcap"))) { Copy-Item -LiteralPath (Join-Path $root "cattura.pcap") -Destination $tcpdump -Force }
exit 0
