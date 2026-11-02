# Le parti 3 e 4 di tools\security\sicurezza-finto.ps1 (si carica da li', con le sue funzioni Check, New-Phone, Invoke-Script): i livelli
# s25 ed emulatore di run-all.ps1 contro l'adb finto, l'emulatore finto e i passi del PC finti, con i casi verdi e i casi rossi.

function Get-Report([object]$Run, [string]$Level) {
  $line = $Run.Out | Where-Object { $_ -match '^Resoconto: ' } | Select-Object -Last 1
  if (-not $line) { return "" }
  return (Get-Content -LiteralPath ($line -replace '^Resoconto: ', '') -Raw)
}

function Get-Esito([string]$Report, [string]$Id) {
  $m = [regex]::Match($Report, "(?m)^MCG-SEC $([regex]::Escape($Id)) esito=([a-z]+)")
  if ($m.Success) { return $m.Groups[1].Value }
  return "assente"
}

# Le buste e le immagini "preparate" (come le scrive run-all -Livello jvm): qui sono file finti, nomi veri.
$prep = Join-Path $work "preparati"
New-Item -ItemType Directory -Force -Path (Join-Path $prep "manifest"), (Join-Path $prep "ostili") | Out-Null
foreach ($n in @("01-valido-blocco-writes", "02-valido-blocco-all", "03-valido-senza-blocchi", "04-seq-vecchio", "10-firma-rotta")) { Set-Content -LiteralPath (Join-Path $prep "manifest\$n.mcgr") -Value "busta finta $n" }
Set-Content -LiteralPath (Join-Path $prep "ostili\png-50000x50000.png") -Value "immagine finta"
$runAll = Join-Path $here "run-all.ps1"

Write-Host "3. run-all -Livello s25 (piano e voci sull'app finta)"
$r = Invoke-Script $runAll @("-Livello", "s25", "-Piano")
$text = $r.Out -join "`n"
Check "s25 -Piano: uscita 0, un solo momento dell'utente (il passo famiglia, all'inizio)" ($r.Code -eq 0 -and ([regex]::Matches($text, '\[UTENTE\]')).Count -eq 1 -and $text -match 'Punto in cui si aspetta l''utente: uno')
Check "s25 -Piano: il reset della famiglia e' il secondo passo, la pulizia l'ultimo" ($text -match '(?s)preparazione.*famiglia.*S1c.*S2.*S3.*S7.*S7a.*S9.*S11.*S10.*pulizia')
$r = Invoke-Script $runAll @("-Livello", "emulatore", "-Piano")
Check "emulatore -Piano: nessuna attesa dell'utente" ($r.Code -eq 0 -and ($r.Out -join "`n") -match "Punto in cui si aspetta l'utente: nessuno")

$phone = New-Phone "s25-voci"
New-FakeItemScreens (Join-Path $phone "ui")
$r = Invoke-Script $runAll @("-Livello", "s25", "-Serial", "FAKES25", "-Passo", "S2,S3,S7,S7a,S9,S10,S11,pulizia", "-Preparati", $prep, "-StatoDir", (Join-Path $work "stato-voci"), "-SettleSeconds", "0", "-PollSeconds", "0", "-File", "300")
$rep = Get-Report $r "s25"
foreach ($pair in @(@("S2", "verde"), @("S3", "verde"), @("S7", "verde"), @("S7a", "dichiarata"), @("S9", "verde"), @("S10", "verde"), @("S11", "dichiarata"), @("pulizia", "verde"))) {
  Check ("s25 {0}: {1}" -f $pair[0], $pair[1]) ((Get-Esito $rep $pair[0]) -eq $pair[1])
}
Check "s25: S2 conta i rifiuti nuovi e nessuna voce in Libreria" ($rep -match 'MCG-SEC S2 esito=verde .*rifiuti_nuovi=7' -and $rep -match 'voci_in_libreria=0')
Check "s25: S7 cinque buste, due blocchi applicati, due rifiutate, nessuna inattesa" ($rep -match 'MCG-SEC S7 esito=verde .*buste=5 blocchi_applicati=2 rifiutate=2 inattese=0')
Check "s25: S9 congelato, numero sbagliato rifiutato, scongelato con quello giusto" ($rep -match 'congelato=1 notifiche_app=1 numero_sbagliato_rifiutato=1 scongelato=1')
Check "s25: uscita 0 e il resoconto ha il totale" ($r.Code -eq 0 -and $rep -match 'MCG-SEC totale passi=')
Check "s25: nessun comando all'emulatore dell'altra sessione" ((Get-Content (Join-Path $phone "adb-calls.log") -Raw) -notmatch "emulator-5554")

Write-Host "4. run-all -Livello emulatore (verde, poi i casi rossi)"
function Start-EmulatorPhone([string]$name, [switch]$Red) {
  $phone = New-Phone $name
  New-FakeItemScreens (Join-Path $phone "ui")
  New-FakeScreen (Join-Path $phone "ui\schermate") "benvenuto" @((N "Benvenuto"), (N "Crea la famiglia"))
  $env:FAKE_EMULATOR_SPENTO = "1"
  $env:FAKE_PACCHETTI = "1"
  New-FakePcap (Join-Path $phone "cattura.pcap") -Clean:(-not $Red)
  foreach ($k in @("accessibility_button_targets", "accessibility_direct_access_target_service", "accessibility_gesture_targets")) {
    New-Item -ItemType Directory -Force -Path (Join-Path $phone "settings") | Out-Null
    Set-Content -LiteralPath (Join-Path $phone "settings\$k.txt") -Value "com.keyvoice.app/.Servizio"
  }
  $extra = if ($Red) { "        9a8b dev.pampa.mycloudgallery/dev.pampa.mycloudgallery.debugtools.probes.FieldProbeShareActivity" } else { "" }
  Set-Content -LiteralPath (Join-Path $phone "dumpsys\package-dev.pampa.mycloudgallery.txt") -Value @(
    "Activity Resolver Table:", "        1a2b dev.pampa.mycloudgallery/.MainActivity filter 3c4d", "        7f80 dev.pampa.mycloudgallery/dev.pampa.mycloudgallery.feature.integrations.picker.PickerActivity", $extra,
    "Packages:", "  Package [dev.pampa.mycloudgallery] (def):", "    userId=10301", "    pkgFlags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]",
    "    requested permissions:", "      android.permission.ACCESS_NETWORK_STATE", "      android.permission.INTERNET", "    install permissions:", "      android.permission.INTERNET: granted=true")
  Set-Content -LiteralPath (Join-Path $phone "dumpsys\netstats.txt") -Value @("ident=[...] uid=10301 set=DEFAULT tag=0x0", "  st=1 rb=4096 rp=8 tb=512 tp=4")
  $apks = Join-Path $work "apk-$name"
  New-Item -ItemType Directory -Force -Path $apks | Out-Null
  Set-Content -LiteralPath (Join-Path $apks "app-debug.apk") -Value "apk finto"
  Set-Content -LiteralPath (Join-Path $apks "app-release.apk") -Value "apk finto"
  return @($phone, $apks)
}

$phone, $apks = Start-EmulatorPhone "emulatore"
$r = Invoke-Script $runAll @("-Livello", "emulatore", "-Serial", "emulator-5580", "-Preparati", $prep, "-ReleaseApk", (Join-Path $apks "app-release.apk"), "-DebugApk", (Join-Path $apks "app-debug.apk"), "-SettleSeconds", "0", "-PollSeconds", "0")
$rep = Get-Report $r "emulatore"
$calls = Get-Content (Join-Path $phone "adb-calls.log") -Raw
foreach ($pair in @(@("accensione", "verde"), @("preflight", "verde"), @("S8b", "verde"), @("S5", "verde"), @("S4", "bloccata"), @("S6", "verde"), @("S7rel", "bloccata"), @("S13", "verde"), @("S11", "dichiarata"), @("S12", "verde"), @("disinstalla", "verde"), @("spegnimento", "verde"))) {
  Check ("emulatore {0}: {1}" -f $pair[0], $pair[1]) ((Get-Esito $rep $pair[0]) -eq $pair[1])
}
Check "emulatore: acceso e spento dallo script, release installata e tolta" ($calls -match "emulatore: -avd \| Pampa_Tablet" -and $calls -match "emulatore spento" -and $calls -match "installato: dev.pampa.mycloudgallery\b" -and $calls -match "disinstallato: dev.pampa.mycloudgallery")
Check "emulatore: senza ULA nel Wi-Fi l'emulatore non puo' essere casa" ($rep -match 'MCG-SEC preflight esito=verde .*casa_possibile=0')
Check "emulatore: S12 rimette le tre chiavi svuotate dall'accensione del servizio" ($rep -match 'impostazioni_rimesse=5 impostazioni=5' -and (Get-Content (Join-Path $phone "settings\accessibility_button_targets.txt") -Raw).Trim() -eq "com.keyvoice.app/.Servizio")
Check "emulatore: il PIN di prova non si mette (niente famiglia su questo livello)" ($calls -notmatch "pin: set-pin")
Check "emulatore: uscita 0" ($r.Code -eq 0)

$phone, $apks = Start-EmulatorPhone "emulatore-rosso" -Red
$env:FAKE_A11Y_LEGGE = "1"; $env:FAKE_BMGR_OK = "1"; $env:FAKE_LOGCAT_ECO = "1"
$r = Invoke-Script $runAll @("-Livello", "emulatore", "-Serial", "emulator-5580", "-Preparati", $prep, "-ReleaseApk", (Join-Path $apks "app-release.apk"), "-DebugApk", (Join-Path $apks "app-debug.apk"), "-SettleSeconds", "0", "-PollSeconds", "0")
$rep = Get-Report $r "emulatore"
foreach ($id in @("S8b", "S5", "S6", "S13", "S12")) { Check ("emulatore, caso rosso: {0} esce rosso" -f $id) ((Get-Esito $rep $id) -eq "rosso") }
Check "emulatore, caso rosso: S6 conta il seme in logcat senza stamparlo" ($rep -match 'MCG-SEC S6 esito=rosso .*occorrenze_logcat=[1-9]' -and $rep -notmatch 'MCG-PRIV-')
Check "emulatore, caso rosso: uscita 1 e l'emulatore spento lo stesso" ($r.Code -eq 1 -and (Get-Content (Join-Path $phone "adb-calls.log") -Raw) -match "emulatore spento")
foreach ($v in @("FAKE_A11Y_LEGGE", "FAKE_BMGR_OK", "FAKE_LOGCAT_ECO")) { Remove-Item "Env:$v" -ErrorAction SilentlyContinue }

$r = Invoke-Script $runAll @("-Livello", "emulatore", "-Serial", "emulator-5554")
Check "run-all rifiuta emulator-5554" ($r.Code -eq 2)
$r = Invoke-Script (Join-Path $root "tools\chaos\s25.ps1") @("-Mode", "security", "-Serial", "emulator-5554")
Check "s25.ps1 -Mode security rifiuta emulator-5554" ($r.Code -eq 2)
$r = Invoke-Script (Join-Path $root "tools\chaos\s25.ps1") @("-Mode", "security", "-Step", "verify", "-DryRun")
Check "s25.ps1 -Mode security -DryRun stampa il piano dell'S25" ($r.Code -eq 0 -and ($r.Out -join "`n") -match 'Livello s25')
$r = Invoke-Script (Join-Path $root "tools\chaos\s25.ps1") @("-Mode", "security", "-Release", "-DryRun")
Check "s25.ps1 -Mode security -Release -DryRun stampa il piano della release" ($r.Code -eq 0 -and ($r.Out -join "`n") -match 'release candidata')
