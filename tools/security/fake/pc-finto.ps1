<#
.SYNOPSIS
  I passi del PC FINTI (Fase 10 P11, seconda parte) per provare gli script dei dispositivi senza NAS e senza Gradle: al posto di
  FamilyAreaNasIntegrationTest (che il NAS finto lo prova da solo, FamilyAreaFakeNasTest) scrivono le righe MCG-SEC attese, i file dello
  stato (codici, segno di S1) e cambiano la Diagnostica dell'app finta come la cambierebbe l'app vera (rifiuti, blocchi, congelamento).
  Lo usa tools\security\sicurezza-finto.ps1 con MCG_SECURITY_PC_FINTO. Con FAKE_PC_SPORCO=1 il confronto delle fotografie e' sporco;
  con FAKE_PC_AUTH=1 ogni passo risponde AUTH_FAILED.
#>
param([string]$Passo, [string]$Dir)
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "schermate.ps1")
$log = Join-Path $Dir "famiglia-nas.txt"
Add-Content -LiteralPath (Join-Path $Dir "passi-pc.log") -Value $Passo
$stateFile = Join-Path $Dir "fake-diag.txt"
$state = @{ rifiuti = 0; manifest = 0; blocco = ""; congelato = 0; approvati = 0 }
if (Test-Path -LiteralPath $stateFile) { foreach ($l in Get-Content -LiteralPath $stateFile) { $k, $v = $l -split '=', 2; $state[$k] = $v } }

function Save-State {
  $state.Keys | ForEach-Object { "$_=$($state[$_])" } | Set-Content -LiteralPath $stateFile
  # L'anteprima dell'esportazione dell'app finta, come la scriverebbe l'app vera dopo il passo.
  $lines = @("{""code"": ""INDEX_REJECTED"", ""export"": {""rejected.SIGNER_NOT_APPROVED"": $($state.rifiuti)}}")
  if ([int]$state.manifest -gt 0) { $lines += "{""code"": ""RELEASE_MANIFEST_REJECTED"", ""export"": {""rejected.ROLLBACK"": $($state.manifest)}}" }
  if ($state.blocco) { $lines += "{""code"": ""RELEASE_BLOCKED"", ""export"": {""state"": ""ReleaseLevel.$($state.blocco)""}}" }
  if ([int]$state.congelato -eq 1) { $lines += "{""code"": ""NAS_SCAN_FROZEN"", ""export"": {""changed"": 333, ""gone"": 666}}" }
  $ui = Join-Path $env:FAKE_PHONE "ui"
  $current = if (Test-Path (Join-Path $ui "stato.txt")) { (Get-Content (Join-Path $ui "stato.txt") -Raw).Trim() } else { "libreria" }
  New-FakeItemScreens $ui ($lines -join "`n")
  $d = Join-Path $ui "schermate"
  if ([int]$state.congelato -eq 1) {
    New-FakeScreen $d "diagnostica" @((N "Diagnostica"), (N "Esporta la diagnostica" "esporta"), (N "Ho controllato, continua" "scongela")) @{ "fake-indietro" = "impostazioni" }
    New-FakeScreen $d "scongela" @((N "Riprendere il lavoro sul NAS?"), (N "Sono cambiati o spariti 999 file."), (N "Numero dei file"), (N "Ho controllato, continua" "@verifica"), (N "Annulla" "diagnostica")) @{ "fake-indietro" = "diagnostica"; "fake-numero" = "999"; "fake-giusto" = "scongelato" }
    New-FakeScreen $d "scongelato" @((N "Diagnostica"), (N "Tutto a posto")) @{ "fake-indietro" = "impostazioni" }
  }
  Set-Content -LiteralPath (Join-Path $ui "stato.txt") -Value $current
}

function Line([string]$text) { Add-Content -LiteralPath $log -Value $text; Write-Host $text }

if ($env:FAKE_PC_AUTH -eq "1") { Line "MCG-SEC famiglia passo=$Passo fermata=AUTH_FAILED nuovi_tentativi=0"; exit 1 }
# I dispositivi di prova risultano approvato e revocato solo se il copione sull'app finta e' arrivato davvero alla pagina "Revocato".
$calls = Join-Path $env:FAKE_PHONE "adb-calls.log"
if ((Test-Path -LiteralPath $calls) -and (Select-String -LiteralPath $calls -Pattern "^schermata: revocato$" -Quiet)) { $state.approvati = 1 }
$status = if ([int]$state.approvati -eq 1) { "approvato_stato=approved revocato_stato=revoked canaglia_stato=assente" } else { "approvato_stato=assente revocato_stato=assente canaglia_stato=assente" }
switch ($Passo) {
  "fotografia" { Line "MCG-SEC famiglia fotografia=$env:MCG_FAMIGLIA_ETICHETTA voci=42 cartelle=9" }
  "archivia" { Line "MCG-SEC famiglia archiviata=1" }
  "confronta" { $clean = if ($env:FAKE_PC_SPORCO -eq "1") { 0 } else { 1 }; Line "MCG-SEC famiglia_confronto dentro_aggiunti=7 dentro_cambiati=1 dentro_tolti=0 fuori_aggiunti=$(1 - $clean) fuori_cambiati=0 fuori_tolti=0 family_archiviata=1 pulito=$clean"; if ($clean -eq 0) { exit 1 } }
  "leggi" { Line "MCG-SEC famiglia trovata=1 versione_registro=$(1 + 2 * [int]$state.approvati) dispositivi=$(1 + 2 * [int]$state.approvati) $status" }
  "richieste" {
    Set-Content -LiteralPath (Join-Path $Dir "codici.txt") -Value @("approvato=AAAA-BBBB-CCCC-DDDD", "revocato=EEEE-FFFF-GGGG-HHHH")
    Line "MCG-SEC famiglia richieste=2"
  }
  "semina-s1" { Set-Content -LiteralPath (Join-Path $Dir "segno-s1.txt") -Value "MCG-PRIV-0123456789ab"; Line "MCG-SEC S1capp file=2 teste=1 segmenti=1 byte=900" }
  "semina-s2" { $state.rifiuti = [int]$state.rifiuti + 7; Save-State; Line "MCG-SEC S2app file=12 teste=6 segmenti=6 byte=4000"; Line "MCG-SEC famiglia trovata=1 $status" }
  "semina-s3" { $state.rifiuti = [int]$state.rifiuti + 15; Save-State; Line "MCG-SEC S3bapp file=18 teste=1 segmenti=17 byte=2100000"; Line "MCG-SEC famiglia trovata=1 $status" }
  "semina-s7a" { $n = @($env:MCG_S7A_SHA -split ',' | Where-Object { $_ }).Count; Line "MCG-SEC S7aapp file=$n teste=0 segmenti=0 byte=$(4096 * $n)" }
  "immagini" { Line "MCG-SEC S3capp file=13 teste=0 segmenti=0 byte=151900" }
  "manifest" {
    $name = Split-Path -Leaf $env:MCG_FAMIGLIA_BUSTA
    if ($name -match '^01-') { $state.blocco = "WRITES" } elseif ($name -match '^02-') { $state.blocco = "ALL" } elseif ($name -match '^03-') { $state.blocco = "" } else { $state.manifest = [int]$state.manifest + 1 }
    Save-State
    Line "MCG-SEC S7app busta_messa=1 byte=600"
  }
  "massa-crea" { Line "MCG-SEC S9app_crea file=$env:MCG_SECURITY_S9_FILES" }
  "massa-cambia" { $state.congelato = 1; Save-State; Line "MCG-SEC S9app_cambia rinominati=333 cambiati=333 cifrati=333" }
  "massa-pulizia" { $state.congelato = 0; Save-State; Line "MCG-SEC S9app_pulizia residui=0 solo_il_giro_della_prova=1" }
  "pulizia" { Line "MCG-SEC famiglia pulizia residui=0 registro=0" }
  default { Line "MCG-SEC famiglia passo_sconosciuto=1"; exit 1 }
}
exit 0
