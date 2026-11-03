<#
.SYNOPSIS
  La prova a secco degli strumenti della seconda parte di P11 (Fase 10) contro l'adb FINTO, l'emulatore FINTO e i passi del PC FINTI:
  nessun telefono, nessun emulatore, nessun NAS, nessuna rete. Il NAS finto vero e proprio (guardia C470, semina, pulizia) lo prova
  FamilyAreaFakeNasTest in check.ps1.

.DESCRIPTION
  1. le letture (pcap, logcat, dumpsys, netstats, bmgr, servizio di accessibilita'), le guardie (emulator-5554 mai, solo testo semplice in
     input text, resoconto di soli numeri), il codice di recupero dallo schermo;
  2. il reset della famiglia di prova sull'S25 finto: il copione fino alla password, l'attesa dell'"utente", il dialogo di sistema chiuso con
     Indietro e mai confermato, il foglio della chiave, il kit nel suo file, la riscrittura; poi i dispositivi di prova (approva, revoca);
     i casi rossi: il dispositivo admin della famiglia di prima, il codice illeggibile, AUTH_FAILED (un solo passo del PC);
  3. run-all -Livello s25 -Piano e i passi S2, S3, S7, S9, S10, S11, pulizia sull'app finta (Diagnostica letta dall'anteprima);
  4. run-all -Livello emulatore: accensione e spegnimento dell'emulatore finto, installazione e disinstallazione della release, S8 (b) dalla
     cattura, S5, S6, S13, S11, S12, S4 e S7rel bloccate senza ULA; poi gli stessi passi con i casi rossi (seme in logcat, servizio che legge,
     backup riuscito, SESSION_SETUP nella cattura, componente fuori elenco): devono uscire rossi.
  Esce con 0 se tutto e' come previsto, 1 altrimenti. Lavora in una cartella temporanea (tolta alla fine) e in build\security\finto-*.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\security\sicurezza-finto.ps1
#>
[CmdletBinding()]
param([switch]$Keep)
$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$root = Split-Path -Parent (Split-Path -Parent $here)
$work = Join-Path ([System.IO.Path]::GetTempPath()) ("mcg-sicurezza-finto-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
$failures = New-Object System.Collections.Generic.List[string]
foreach ($lib in @("security-common", "dispositivo", "copioni", "copione-famiglia", "copioni-app", "analisi", "voci", "voci-sistema", "famiglia", "livelli")) { . (Join-Path $here "lib\$lib.ps1") }
. (Join-Path $here "fake\schermate.ps1")
. (Join-Path $here "fake\pcap-finti.ps1")

function Check([string]$what, [bool]$ok) {
  if ($ok) { Write-Host ("  OK    {0}" -f $what) -ForegroundColor Green } else { Write-Host ("  FAIL  {0}" -f $what) -ForegroundColor Red; $failures.Add($what) }
}

# Un telefono finto nuovo: la sua cartella, l'adb finto, l'emulatore finto e i passi del PC finti.
function New-Phone([string]$name) {
  $phone = Join-Path $work $name
  New-Item -ItemType Directory -Force -Path $phone, (Join-Path $phone "dumpsys") | Out-Null
  $env:FAKE_PHONE = $phone
  $env:MCG_ADB = Join-Path $root "tools\chaos\fake-adb\adb.cmd"
  $env:MCG_EMULATOR = Join-Path $root "tools\chaos\fake-adb\emulator.cmd"
  $env:MCG_SECURITY_PC_FINTO = Join-Path $here "fake\pc-finto.ps1"
  foreach ($v in @("FAKE_EMULATOR_SPENTO", "FAKE_PACCHETTI", "FAKE_A11Y_LEGGE", "FAKE_BMGR_OK", "FAKE_LOGCAT_ECO", "FAKE_PC_AUTH", "FAKE_PC_SPORCO")) { Remove-Item "Env:$v" -ErrorAction SilentlyContinue }
  Set-Content -LiteralPath (Join-Path $phone "dumpsys\meminfo-dev.pampa.mycloudgallery.debug.txt") -Value "  TOTAL PSS:   345678   TOTAL RSS:   400000"
  Set-Content -LiteralPath (Join-Path $phone "dumpsys\notification.txt") -Value "  NotificationRecord(0x1: pkg=dev.pampa.mycloudgallery.debug user=UserHandle{0} id=7)"
  Set-Content -LiteralPath (Join-Path $phone "dumpsys\package-dev.pampa.mycloudgallery.debug.txt") -Value @(
    "Activity Resolver Table:", "  Non-Data Actions:", "      android.intent.action.MAIN:", "        1a2b dev.pampa.mycloudgallery.debug/dev.pampa.mycloudgallery.MainActivity filter 3c4d",
    "        5e6f dev.pampa.mycloudgallery.debug/dev.pampa.mycloudgallery.debugtools.probes.FieldProbeDragReceiverActivity", "Packages:", "  Package [dev.pampa.mycloudgallery.debug] (abc):",
    "    userId=10234", "    pkgFlags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ]")
  return $phone
}

function Invoke-Script([string]$script, [string[]]$arguments) {
  $out = & powershell -NoProfile -ExecutionPolicy Bypass -File $script @arguments 2>&1 | ForEach-Object { $_.ToString() }
  return [pscustomobject]@{ Code = $LASTEXITCODE; Out = @($out) }
}

New-Item -ItemType Directory -Force -Path $work | Out-Null
try {
  Write-Host "1. Letture e guardie"
  $clean = Join-Path $work "pulita.pcap"; New-FakePcap $clean -Clean
  $dirty = Join-Path $work "sporca.pcap"; New-FakePcap $dirty
  $p1 = Read-SecPcap $clean; $p2 = Read-SecPcap $dirty
  Check "pcap pulita: nessuna SESSION_SETUP, nessun NTLMSSP, destinazioni contate" ($p1.formato_valido -eq 1 -and $p1.session_setup -eq 0 -and $p1.ntlmssp -eq 0 -and $p1.syn_445 -eq 0 -and $p1.destinazioni -eq 2 -and $p1.nomi_dns -eq 2 -and $p1.nomi_fuori_elenco -eq 1)
  Check "pcap sporca: un SYN verso 445, una SESSION_SETUP, un NTLMSSP" ($p2.syn_445 -eq 1 -and $p2.session_setup -eq 1 -and $p2.ntlmssp -eq 1)
  $log = @("11-02 10:00:00.000  4200  4210 W System.err: dev.pampa.mycloudgallery.albums.UnreadableRecords: Troppi byte", "11-02 10:00:00.001  4200  4210 W System.err: `tat dev.pampa.mycloudgallery.a.b(Unknown Source:4)", "11-02 10:00:00.002  4200  4210 W System.err: `tat dev.pampa.mycloudgallery.c.d(Unknown Source:9)", "11-02 10:00:01.000  999  999 I Altro: MCG-PRIV-abc nella riga")
  $t = Measure-SecStackTraces $log "dev.pampa.mycloudgallery" @("4200")
  Check "stack trace dell'app contate (1 blocco, 2 righe, ReadLimit riconosciuto)" ($t.stacktrace -eq 1 -and $t.righe_di_stack -eq 2 -and $t.read_limit -eq 1)
  Check "semi contati senza stamparli (anche in minuscolo)" ((Measure-SecSeeds $log @("mcg-priv-ABC")) -eq 1)
  $net = Read-SecNetstats @("ident=[...] uid=10234 set=DEFAULT tag=0x0", "  st=1 rb=1000 rp=3 tb=200 tp=2", "ident=[...] uid=10500 set=DEFAULT", "  st=1 rb=9 rp=1 tb=9 tp=1") 10234
  Check "netstats per UID" ($net.byte_ricevuti -eq 1000 -and $net.byte_mandati -eq 200)
  Check "bmgr: 'not allowed' non e' un backup riuscito" (((Read-SecBmgr @("Package dev.pampa.mycloudgallery.debug with result: Backup is not allowed") "dev.pampa.mycloudgallery.debug").backup_riuscito) -eq 0)
  $refused = 0
  foreach ($bad in @("Pa`$`$w0rd!", "due parole", "a;rm -rf /", "x'y")) { try { $script:SecAdb = "nessuno"; Invoke-SecType "X" $bad } catch { if ($_.Exception.Message -match "non ammesso") { $refused++ } } }
  Check "input text rifiuta tutto cio' che non e' testo semplice (mai una password)" ($refused -eq 4)
  $lineRefused = $false; try { [void](New-SecLine "S6" ([ordered]@{ nome = "Foto di Marta" })) } catch { $lineRefused = $true }
  Check "il resoconto rifiuta un testo libero" $lineRefused
  $env:MCG_ADB = "finto"
  Check "emulator-5554 mai, nemmeno con l'adb finto" ((Get-SecDeviceErrors "emulatore" "emulator-5554" $SecDebugPackage).Count -gt 0 -and (Get-SecDeviceErrors "s25" "EMULATOR-5554" $SecDebugPackage).Count -gt 0)
  Remove-Item Env:MCG_ADB
  Check "senza adb finto: emulatore solo emulator-5580, s25 solo RFCY61FZDHW" ((Get-SecDeviceErrors "emulatore" "emulator-5582" $SecDebugPackage).Count -gt 0 -and (Get-SecDeviceErrors "s25" "R5CX0000000" $SecDebugPackage).Count -gt 0 -and (Get-SecDeviceErrors "s25" "RFCY61FZDHW" $SecDebugPackage).Count -eq 0)
  Check "solo la nostra app" ((Get-SecDeviceErrors "s25" "RFCY61FZDHW" "com.samsung.android.gallery3d").Count -gt 0)
  $ui = @(@{ Text = "ABCD"; Top = 1; X = 1 }, @{ Text = "EFGH"; Top = 2; X = 1 }, @{ Text = "JKMN"; Top = 3; X = 1 }, @{ Text = "PQRS"; Top = 4; X = 1 }, @{ Text = "TVWX"; Top = 5; X = 1 }, @{ Text = "YZ23"; Top = 6; X = 1 }, @{ Text = "4567"; Top = 7; X = 1 }, @{ Text = "abcde fghij klmno pqrst uvwxy z"; Top = 8; X = 1 }) | ForEach-Object { [pscustomobject]$_ }
  $code = Get-SecRecoveryCode $ui
  Check "codice di recupero dallo schermo: 7 gruppi e l'impronta" ($code -and $code.Code -eq "ABCD-EFGH-JKMN-PQRS-TVWX-YZ23-4567" -and $code.Fingerprint)

  Write-Host "2. Reset della famiglia di prova sull'S25 finto"
  $famScript = Join-Path $here "famiglia-di-prova.ps1"
  $kitDir = Join-Path $work "note-agenti\famiglia-di-prova"
  $kit = Join-Path $kitDir "kit.txt"
  $phone = New-Phone "s25"
  New-FakeFamilyScreens (Join-Path $phone "ui")
  New-FakeDeviceScreens (Join-Path $phone "ui")
  $state = Join-Path $work "stato-1"
  $r = Invoke-Script $famScript @("-Passo", "tutto", "-Serial", "FAKES25", "-KitFile", $kit, "-StatoDir", $state, "-PollSeconds", "0")
  Check "reset: uscita 0" ($r.Code -eq 0)
  Check "reset: il kit nuovo nel suo file, fuori da git" ((Test-Path $kit) -and ((Get-Content $kit -Raw) -match 'ABCD-EFGH-JKMN-PQRS-TVWX-YZ23-4567') -and ((Get-Content $kit -Raw) -match 'abcde fghij'))
  $typed = @(Get-Content (Join-Path $phone "ui\testo.log"))
  Check "reset: lo script ha scritto solo nome utente, indirizzo, codice del kit e codici di approvazione (mai una password)" (($typed | Where-Object { $_ -notin @("test", "192.168.178.58", "ABCDEFGHJKMNPQRSTVWXYZ234567", "AAAA-BBBB-CCCC-DDDD", "EEEE-FFFF-GGGG-HHHH") }).Count -eq 0 -and ($typed -contains "test"))
  $calls = Get-Content (Join-Path $phone "adb-calls.log") -Raw
  Check "reset: aspettato l'utente alla password (schermata passata da sola)" ($calls -match "schermata: accedo" -and $r.Out -match "ADESSO TOCCA ALL'UTENTE")
  Check "reset: il dialogo di sistema chiuso con Indietro, mai confermato" ($calls -match "schermata: batteria" -and $calls -notmatch "schermata: nonqui")
  $screens = @(Get-Content (Join-Path $phone "adb-calls.log") | Where-Object { $_ -like "schermata: *" })
  $afterSummary = [array]::IndexOf($screens, "schermata: riepilogo")
  Check "reset: arrivato al riepilogo e poi alla Libreria" ($afterSummary -ge 0 -and $screens[$afterSummary + 1] -eq "schermata: libreria")
  Check "reset: i passi del PC in ordine (fotografia, archivio, richieste e lettura dei dispositivi, fotografia, confronto, lettura)" (((Get-Content (Join-Path $state "passi-pc.log")) -join ",") -eq "fotografia,archivia,richieste,leggi,fotografia,confronta,leggi")
  Check "reset: nessun comando verso emulator-5554" ($calls -notmatch "emulator-5554")
  $rep = Get-ChildItem (Join-Path $root "build\security") -Recurse -Filter "famiglia.txt" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
  Check "reset: resoconto di soli numeri, verde" ($rep -and ((Get-Content $rep.FullName -Raw) -match 'MCG-SEC famiglia esito=verde .*kit_scritto=1 .*confronto_pulito=1 famiglia_nuova=1 dispositivi_approvati=2 dispositivi_revocati=1 approvato_ok=1 revocato_ok=1'))
  Check "dispositivi: approvato e revocato nello stesso momento (codici digitati, fogli dell'utente)" ($calls -match "schermata: fatto-a" -and $calls -match "schermata: fatto-r" -and $calls -match "schermata: revocato")
  Check "dispositivi: digitati i due codici di approvazione" ($typed -contains "AAAA-BBBB-CCCC-DDDD" -and $typed -contains "EEEE-FFFF-GGGG-HHHH")

  $phone = New-Phone "s25-admin"
  New-FakeFamilyScreens (Join-Path $phone "ui") -Admin
  Remove-Item $kit -ErrorAction SilentlyContinue
  $r = Invoke-Script $famScript @("-Passo", "tutto", "-Serial", "FAKES25", "-KitFile", $kit, "-StatoDir", (Join-Path $work "stato-2"), "-PollSeconds", "0")
  Check "admin della famiglia di prima: il copione si ferma, nessun 'Esci e rientra', nessun kit" ($r.Code -ne 0 -and -not (Test-Path $kit) -and (Get-Content (Join-Path $phone "adb-calls.log") -Raw) -notmatch "schermata: foglio")

  $phone = New-Phone "s25-codice"
  New-FakeFamilyScreens (Join-Path $phone "ui") -CodiceIlleggibile
  $r = Invoke-Script $famScript @("-Passo", "tutto", "-Serial", "FAKES25", "-KitFile", $kit, "-StatoDir", (Join-Path $work "stato-3"), "-PollSeconds", "0")
  Check "codice illeggibile: fermo, nessun kit scritto" ($r.Code -ne 0 -and -not (Test-Path $kit))

  $phone = New-Phone "s25-auth"
  New-FakeFamilyScreens (Join-Path $phone "ui")
  $env:FAKE_PC_AUTH = "1"
  $r = Invoke-Script $famScript @("-Passo", "tutto", "-Serial", "FAKES25", "-KitFile", $kit, "-StatoDir", (Join-Path $work "stato-4"), "-PollSeconds", "0")
  Remove-Item Env:FAKE_PC_AUTH
  Check "AUTH_FAILED: un solo passo del PC, l'app non si tocca" ($r.Code -ne 0 -and @(Get-Content (Join-Path $work "stato-4\passi-pc.log")).Count -eq 1 -and -not (Test-Path (Join-Path $phone "ui\testo.log")))
  $r = Invoke-Script $famScript @("-Passo", "tutto", "-Serial", "emulator-5554", "-KitFile", $kit)
  Check "famiglia-di-prova rifiuta emulator-5554" ($r.Code -eq 2)
  $r = Invoke-Script $famScript @("-Passo", "tutto", "-Serial", "FAKES25", "-KitFile", (Join-Path $root "build\kit.txt"))
  Check "famiglia-di-prova rifiuta un file del kit dentro il repository" ($r.Code -eq 2)

  . (Join-Path $here "fake\scenari-livelli.ps1")
} finally {
  foreach ($v in @("FAKE_PHONE", "MCG_ADB", "MCG_EMULATOR", "MCG_SECURITY_PC_FINTO", "FAKE_EMULATOR_SPENTO", "FAKE_PACCHETTI", "FAKE_A11Y_LEGGE", "FAKE_BMGR_OK", "FAKE_LOGCAT_ECO", "FAKE_PC_AUTH")) { Remove-Item "Env:$v" -ErrorAction SilentlyContinue }
  if (-not $Keep) { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }
}

Write-Host ""
if ($failures.Count -eq 0) { Write-Host "Tutto come previsto." -ForegroundColor Green; exit 0 }
Write-Host ("{0} controlli falliti:" -f $failures.Count) -ForegroundColor Red
$failures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
exit 1
