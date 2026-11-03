<#
.SYNOPSIS
  Il RESET DELLA FAMIGLIA DI PROVA (`test`, aree `test` e `test-comune`), decisione 1 dell'utente del 02/11 (Fase 10 P11, seconda parte).
  Pronto, provato contro l'adb finto e il NAS finto (tools\security\sicurezza-finto.ps1, FamilyAreaFakeNasTest); da eseguire alla prossima
  finestra con l'S25 (tools\security\PROSSIMA-FINESTRA.md).

.DESCRIPTION
  L'utente non ha piu' la chiave ne' il kit della famiglia di prova: la si rifa' da capo con la procedura "Ricominciare da zero" dell'app
  (docs/famiglia-dispositivi.md par. 5), solo sulle share di prova. Passi (-Passo):
    piano        stampa i passi e i tempi, non tocca niente;
    tutto        (di norma) fotografia "prima" -> archivio di `test-comune/.mycloudgallery/family` in `family-vecchia-<giro>` -> il copione
                 sull'app (Impostazioni, Famiglia, "Rientra con un QR nuovo", "Crea la famiglia" con `test`): si FERMA alla password e aspetta
                 l'UTENTE (la scrive lui, una volta) e il suo dito sul foglio della chiave -> codice del kit nuovo nel file del kit (fuori da
                 git) e riscritto nell'app -> fotografia "dopo" e confronto (dentro .mycloudgallery solo la famiglia nuova; fuori niente) ->
                 la famiglia nuova si legge dal registro firmato. In mezzo, SUBITO dopo l'onboarding e nello stesso momento dell'utente, i
                 dispositivi di prova del PC ("approvato" e "revocato") chiedono di entrare, lo script li approva (codice digitato) e revoca
                 "revocato": ogni gesto vuole il dito dell'utente sull'S25 (M3);
    dispositivi  solo i dispositivi di prova (se "tutto" si e' fermato dopo la famiglia nuova);
    verifica     solo la fotografia "dopo", il confronto e la lettura della famiglia (sola lettura);
    pulizia      toglie dall'area della famiglia di prova solo cio' che il registro della guardia dice creato dal giro (C470).
  Il PC scrive sul NAS solo con la guardia dell'area della famiglia di prova (FamilyAreaGuard: test e test-comune, mai NAS, Public, alessio,
  test2) e legge le credenziali dal banco (NasIntegration: il file non si apre, non si stampa, non si copia). Al primo AUTH_FAILED si ferma.
  Il resoconto (soli numeri) va in build\security\<data>\famiglia.txt.

.PARAMETER Passo     piano | tutto | dispositivi | verifica | pulizia (default piano).
.PARAMETER Serial    Il dispositivo (di norma l'S25 RFCY61FZDHW; con l'adb finto, MCG_ADB, un altro). Mai emulator-5554.
.PARAMETER Emulatore Il dispositivo e' l'emulatore Pampa_Tablet (il foglio della chiave lo fa lo script con un PIN di prova suo).
.PARAMETER NasIp     L'indirizzo del NAS per l'onboarding (default 192.168.178.58).
.PARAMETER KitFile   Il file del codice del kit nuovo (default C:\VibeCoded Projects\MyCloud-Gallery-2-note-agenti\famiglia-di-prova\kit.txt).
.PARAMETER StatoDir  Lo stato del giro sul PC (default build\security\famiglia-di-prova).
.PARAMETER Attesa    Minuti di attesa dell'utente alla password (default 30).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\security\famiglia-di-prova.ps1 -Passo piano
  powershell -ExecutionPolicy Bypass -File tools\security\famiglia-di-prova.ps1 -Passo tutto -Serial RFCY61FZDHW
  powershell -ExecutionPolicy Bypass -File tools\security\famiglia-di-prova.ps1 -Passo dispositivi -Serial RFCY61FZDHW
#>
[CmdletBinding()]
param(
  [ValidateSet("piano", "tutto", "dispositivi", "verifica", "pulizia")][string]$Passo = "piano",
  [string]$Serial,
  [switch]$Emulatore,
  [string]$NasIp = "192.168.178.58",
  [string]$KitFile = "C:\VibeCoded Projects\MyCloud-Gallery-2-note-agenti\famiglia-di-prova\kit.txt",
  [string]$StatoDir,
  [int]$Attesa = 30,
  [int]$PollSeconds = 3
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
foreach ($lib in @("security-common", "dispositivo", "copioni", "copione-famiglia", "copioni-app", "analisi", "voci", "voci-sistema", "famiglia")) {
  . (Join-Path $PSScriptRoot "lib\$lib.ps1")
}

if ($Passo -eq "piano") {
  Write-Host "Reset della famiglia di prova (account test, aree test e test-comune), circa 20 minuti:"
  Write-Host "  +0  min  PC: fotografia 'prima' di test e test-comune (sola lettura; mcg-it saltata; mai NAS, Public, alessio)"
  Write-Host "  +2  min  PC: test-comune/.mycloudgallery/family -> family-vecchia-<giro> (niente si cancella)"
  Write-Host "  +3  min  app: Impostazioni, Famiglia, Rientra con un QR nuovo, Esci e rientra, Crea la famiglia, casa, NAS $NasIp, nome utente test"
  Write-Host "  +6  min  [UTENTE] la password di test, una volta; poi il dito sul foglio della chiave di famiglia"
  Write-Host "  +8  min  app: aree test e test-comune, niente backup automatico, codice del kit nuovo in $KitFile e riscritto, riepilogo"
  Write-Host " +11  min  PC: richieste dei dispositivi di prova; app: approva 'approvato' e 'revocato', revoca 'revocato' ([UTENTE] il dito ai tre fogli)"
  Write-Host " +20  min  PC: fotografia 'dopo', confronto, la famiglia nuova si legge con i due dispositivi di prova (approvato, revocato)"
  exit 0
}

$isEmulator = [bool]$Emulatore
if (-not $Serial) { $Serial = if ($isEmulator) { $SecEmulatorSerial } else { $SecPhoneSerial } }
$errors = @(Get-SecDeviceErrors $(if ($isEmulator) { "emulatore" } else { "s25" }) $Serial $SecDebugPackage)
if ($KitFile -like "$root*") { $errors += "Il file del kit va fuori dal repository (-KitFile)." }
if ($NasIp -notmatch '^(\d{1,3}\.){3}\d{1,3}$') { $errors += "-NasIp deve essere un indirizzo IPv4 letterale." }
if ($errors.Count -gt 0) { $errors | ForEach-Object { Write-Host $_ -ForegroundColor Red }; exit 2 }

if (-not $StatoDir) { $StatoDir = Join-Path $root "build\security\famiglia-di-prova" }
$outDir = Join-Path $root ("build\security\" + (Get-Date -Format "yyyyMMdd-HHmmss"))
New-Item -ItemType Directory -Force -Path $outDir, $StatoDir | Out-Null
$script:SecCtx = [pscustomobject]@{
  Root = $root; Livello = "s25"; Serial = $Serial; Package = $SecDebugPackage; Emulator = $isEmulator; StateDir = $StatoDir; OutDir = $outDir
  LogDir = (Join-Path $outDir "schermate"); NasIp = $NasIp; KitFile = $KitFile; SettleSeconds = 60; PollSeconds = $PollSeconds; UserWaitMinutes = $Attesa
}
$script:SecAuthStopped = $false
Initialize-SecAdb $root $Serial
$report = Join-Path $outDir "famiglia.txt"

$pinSet = $false
try {
  if ($isEmulator -and $Passo -in @("tutto", "dispositivi")) { Set-SecTestPin $Serial; $pinSet = $true }
  $results = switch ($Passo) {
    "tutto" { Invoke-SecFamilyReset -WithDevices }
    "dispositivi" { Invoke-SecTestDevices }
    "verifica" { $null }
    "pulizia" { Invoke-SecFamilyCleanup }
  }
  if ($Passo -eq "verifica") {
    $after = Invoke-SecPcStep "fotografia" @{ MCG_FAMIGLIA_ETICHETTA = "dopo" }
    $compare = Invoke-SecPcStep "confronta"
    $read = Invoke-SecPcStep "leggi"
    $results = New-SecResult "verifica" $(if ((Get-SecPcValue $compare "pulito") -eq 1 -and (Get-SecPcValue $read "trovata") -eq 1) { "verde" } else { "rosso" }) ([ordered]@{ confronto_pulito = [int](Get-SecPcValue $compare "pulito"); famiglia_nuova = [int](Get-SecPcValue $read "trovata") })
  }
} finally {
  if ($pinSet) { Clear-SecTestPin $Serial }
}
$values = [ordered]@{ esito = $results.Esito }
foreach ($k in $results.Valori.Keys) { $values[$k] = $results.Valori[$k] }
Add-SecReport $report (New-SecLine $results.Voce $values)
if ($script:SecAuthStopped) { Add-SecReport $report (New-SecLine "fermata" ([ordered]@{ auth_failed = 1; nuovi_tentativi = 0 })) }
Get-Content -LiteralPath $report | Write-Host
Write-Host "Resoconto: $report"
exit $(if ($results.Esito -eq "verde") { 0 } else { 1 })
