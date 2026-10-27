<#
.SYNOPSIS
  Prova a secco COMPLETA dello script del telefono per i volti (tools\chaos\s25.ps1 -Mode faces) contro un adb FINTO: nessun telefono, nessun adb vero, nessun
  NAS (docs/prova-500.md, Parte F, par. F9). Nello stile di tools\chaos\s25-finto.ps1 e di tools\chaos\share-pc\s25-share-finto.ps1.

.DESCRIPTION
  Il telefono finto e' una cartella temporanea (tools\chaos\fake-adb\fake-adb.ps1), con un'app di debug finta che sa i tre scenari della Fase 8 (f103-compute,
  f103-erase, f103-status), i suoi file dei volti in no_backup\ e il suo file di stato files\prove\f8-status.txt. Lo script prova, in quest'ordine:
    0. l'autotest del modo (-SelfTest) e i piani a secco (nessun adb viene chiamato; l'interruzione a secco mostra le tre finestre);
    1. la semina (-Step seed): il manifesto dei volti (`# mcg-faces-manifest 1`, id del dispositivo, cartella del backup, ritratti con nomi neutri), i file solo nelle
       due cartelle di `mcg-prova`; con meno di 10 ritratti o senza la cartella la semina e' rifiutata prima di toccare il telefono;
    2. la verifica a telefono invariato (-Step verify -SkipNas): in fase computed passa, in fase erased no (manca il file di stato);
    3. le interruzioni nelle tre finestre (-Step interrupt -Window all, 6 interruzioni con attese di 1 s) e la verifica finale in fase erased: il Wi-Fi e' stato
       riacceso, gli scenari sono partiti in ordine (calcolo, poi cancellazione), l'app e' stata rilanciata, e l'adb finto non ha ricevuto altro che comandi previsti;
    4. la sola finestra del calcolo: passa in fase computed e fallisce in fase erased (il database c'e');
    5. la cancellazione che lascia un faces-1.db e una chiave (keys=1): la verifica fallisce con il codice 4; rifatta bene, passa;
    6. un -wal orfano senza il suo .db: la verifica fallisce (codice 4);
    7. un file tolto dal telefono (con e senza -AllowFreed) e un file del telefono che cambia: la verifica deve FALLIRE (codice 4);
    8. la pulizia: senza -Yes rifiuta, con -Yes toglie solo le due cartelle di `mcg-prova` e il file di stato (il resto della cartella dell'app resta);
    9. le guardie: un pacchetto che non e' di debug, un Wi-Fi spento oltre 120 s, 0 o 501 interruzioni, una fase sconosciuta, una share che non e' `test`, un id di
       dispositivo non valido e due passi che il modo non ha (edit, eval-push) sono rifiutati prima di toccare il telefono.
  Il verificatore dal PC (faces-pc.ps1 -Mode verify) NON viene lanciato (-SkipNas): questo script non tocca mai il NAS. Esce con 0 se tutto e' come previsto, con 1
  altrimenti. Non lascia niente fuori dalla cartella temporanea e da build\chaos\.

.PARAMETER Portraits   Quanti ritratti finti di pubblico dominio (file casuali, nessun volto vero) in -PortraitsDir (default 12; il modo vuole da 10 a 200).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\chaos\faces-pc\s25-faces-finto.ps1
#>
[CmdletBinding()]
param(
  [int]$Portraits = 12
)

$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$chaos = (Resolve-Path (Join-Path $here "..")).Path
$script = Join-Path $chaos "s25.ps1"
$work = Join-Path ([System.IO.Path]::GetTempPath()) ("mcg-s25-faces-finto-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
$phone = Join-Path $work "telefono"
$manifest = Join-Path $work "manifesto.txt"
$log = Join-Path $work "racconto.log"
$portraitsDir = Join-Path $work "ritratti"
$failures = New-Object System.Collections.Generic.List[string]
$package = "dev.pampa.mycloudgallery.debug"
$appDir = Join-Path $phone "data\data\$package"
$noBackup = Join-Path $appDir "no_backup"
$statusFile = Join-Path $appDir "files\prove\f8-status.txt"

function Check([string]$what, [bool]$ok) {
  if ($ok) { Write-Host ("  OK    {0}" -f $what) -ForegroundColor Green } else { Write-Host ("  FAIL  {0}" -f $what) -ForegroundColor Red; $failures.Add($what) }
}

# Esegue s25.ps1 in un processo a parte con l'adb finto e restituisce il codice di uscita.
function Invoke-S25([string[]]$arguments) {
  $env:MCG_ADB = Join-Path $chaos "fake-adb\adb.cmd"
  $env:FAKE_PHONE = $phone
  & powershell -NoProfile -ExecutionPolicy Bypass -File $script @arguments -Serial FAKE123 -Log $log | Out-Null
  return $LASTEXITCODE
}

# Come Invoke-S25, ma restituisce anche l'uscita (per leggere i piani a secco).
function Invoke-S25Text([string[]]$arguments) {
  $env:MCG_ADB = Join-Path $chaos "fake-adb\adb.cmd"
  $env:FAKE_PHONE = $phone
  $text = (& powershell -NoProfile -ExecutionPolicy Bypass -File $script @arguments -Serial FAKE123 -Log $log 2>&1 | Out-String)
  return [pscustomobject]@{ Code = $LASTEXITCODE; Text = $text }
}

function Get-CallCount { if (Test-Path -LiteralPath (Join-Path $phone "adb-calls.log")) { return @(Get-Content -LiteralPath (Join-Path $phone "adb-calls.log")).Count } else { return 0 } }

function Get-Calls { if (Test-Path -LiteralPath (Join-Path $phone "adb-calls.log")) { return @(Get-Content -LiteralPath (Join-Path $phone "adb-calls.log")) } else { return @() } }

# I comandi che il modo faces puo' mandare al telefono: ogni riga del registro dell'adb finto deve essere una di queste (nessun'altra app, nessun'altra cartella).
$allowedCalls = @(
  '^-s \| FAKE123 \| get-state$',
  '^-s \| FAKE123 \| shell \| pm \| path \| dev\.pampa\.mycloudgallery\.debug$',
  '^-s \| FAKE123 \| shell \| pidof \| dev\.pampa\.mycloudgallery\.debug$',
  '^-s \| FAKE123 \| shell \| run-as \| dev\.pampa\.mycloudgallery\.debug \| kill \| -9 \| \d+$',
  '^-s \| FAKE123 \| shell \| am \| (crash|kill|force-stop) \| dev\.pampa\.mycloudgallery\.debug$',
  '^-s \| FAKE123 \| shell \| svc \| wifi \| (enable|disable)$',
  '^-s \| FAKE123 \| shell \| monkey \| -p \| dev\.pampa\.mycloudgallery\.debug \| -c \| android\.intent\.category\.LAUNCHER \| 1$',
  '^-s \| FAKE123 \| shell \| mkdir \| -p \| /sdcard/DCIM/mcg-prova \| /sdcard/Pictures/mcg-prova$',
  '^-s \| FAKE123 \| push \| .+ \| /sdcard/(DCIM|Pictures)/$',
  '^-s \| FAKE123 \| shell \| content call --uri content://media/ --method scan_volume --arg external_primary$',
  '^-s \| FAKE123 \| shell \| content query --uri content://media/external/file --projection _data$',
  '^-s \| FAKE123 \| shell \| find /sdcard/DCIM/mcg-prova /sdcard/Pictures/mcg-prova -type f -exec (sha256sum \{\} \+|stat -c ''%s %n'' \{\} \+)$',
  '^-s \| FAKE123 \| shell \| am \| broadcast \| -a \| dev\.pampa\.mycloudgallery\.debug\.action\.FACES_SCENARIO \| -p \| dev\.pampa\.mycloudgallery\.debug \| --es \| scenario \| f103-(compute|erase|status)$',
  '^-s \| FAKE123 \| shell \| run-as dev\.pampa\.mycloudgallery\.debug (ls -1 (no_backup|databases)|cat files/prove/f8-status\.txt|rm -f files/prove/f8-status\.txt)$',
  '^-s \| FAKE123 \| shell \| rm \| -rf \| /sdcard/(DCIM|Pictures)/mcg-prova$',
  '^morto: dev\.pampa\.mycloudgallery\.debug$'
)

function Get-Unexpected {
  return @(Get-Calls | Where-Object { $line = $_; -not ($allowedCalls | Where-Object { $line -match $_ }) })
}

function New-Portraits([string]$dir, [int]$count) {
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  $random = New-Object System.Random(7)
  for ($i = 1; $i -le $count; $i++) {
    $bytes = New-Object byte[] (200 + $random.Next(300))
    $random.NextBytes($bytes)
    [System.IO.File]::WriteAllBytes((Join-Path $dir ("sorgente-{0:D2}.jpg" -f $i)), $bytes)
  }
}

New-Item -ItemType Directory -Force -Path $phone | Out-Null
try {
  $run = "20261030-101500-1a2b3c4d"
  $deviceId = "abcdefghjkmnpqrstvwxyz0124"
  $otherId = "0123456789abcdefghjkmnpqrw"
  $common = @("-Mode", "faces", "-Run", $run, "-Manifest", $manifest, "-Seed", "5")
  $quick = @("-MinGapSeconds", "1", "-MaxGapSeconds", "1", "-WifiOffMinSeconds", "1", "-WifiOffMaxSeconds", "1", "-RelaunchDelaySeconds", "1", "-SettleSeconds", "2", "-SkipNas")

  Write-Host "0. Autotest e piani a secco"
  & powershell -NoProfile -ExecutionPolicy Bypass -File $script -Mode faces -SelfTest | Out-Null
  Check "l'autotest del modo faces passa (nessun adb)" ($LASTEXITCODE -eq 0)
  foreach ($step in @("seed", "interrupt", "verify", "clean")) {
    $before = Get-CallCount
    Check "il piano a secco di '$step' finisce" ((Invoke-S25 ($common + @("-Step", $step, "-DryRun", "-Interruptions", "6"))) -eq 0)
    Check "il piano a secco di '$step' non chiama nemmeno adb" ((Get-CallCount) -eq $before)
  }
  $plan = Invoke-S25Text ($common + @("-Step", "interrupt", "-DryRun", "-Interruptions", "6"))
  Check "il piano a secco dell'interruzione mostra le tre finestre (calcolo, cancellazione, ripresa) e gli scenari" (($plan.Text -match '\[compute\]') -and ($plan.Text -match '\[erase\]') -and ($plan.Text -match '\[resume\]') -and ($plan.Text -match 'f103-compute') -and ($plan.Text -match 'f103-erase'))
  $planErase = Invoke-S25Text ($common + @("-Step", "interrupt", "-DryRun", "-Interruptions", "6", "-Window", "erase"))
  Check "con -Window erase non c'e' la finestra del calcolo" (($planErase.Text -notmatch '\[compute\]') -and ($planErase.Text -match '\[erase\]') -and ($planErase.Text -match '\[resume\]'))

  Write-Host "1. Semina"
  New-Portraits $portraitsDir $Portraits
  $seed = $common + @("-Step", "seed", "-PortraitsDir", $portraitsDir, "-Device", "s25", "-DeviceId", $deviceId, "-OtherDeviceId", $otherId)
  $few = Join-Path $work "pochi"
  New-Portraits $few 3
  $before = Get-CallCount
  Check "con meno di 10 ritratti la semina e' rifiutata" ((Invoke-S25 ($common + @("-Step", "seed", "-PortraitsDir", $few))) -ne 0)
  Check "senza la cartella dei ritratti la semina e' rifiutata" ((Invoke-S25 ($common + @("-Step", "seed", "-PortraitsDir", (Join-Path $work "non-c-e")))) -ne 0)
  Check "i rifiuti non hanno toccato il telefono" ((Get-CallCount) -eq $before)
  Check "la semina finisce" ((Invoke-S25 $seed) -eq 0)
  $lines = if (Test-Path -LiteralPath $manifest) { @(Get-Content -LiteralPath $manifest) } else { @() }
  $phoneLines = @($lines | Where-Object { $_ -like "phone=*" })
  Check "il manifesto e' quello dei volti, con il giro, il dispositivo e il suo id" (($lines[0] -eq "# mcg-faces-manifest 1") -and ($lines -contains "run=$run") -and ($lines -contains "device=s25") -and ($lines -contains "deviceid=$deviceId"))
  Check "il manifesto dice dove il backup ha messo i ritratti (share test) e l'altro dispositivo" (($lines -contains "private=test|mcg-campo/Backup") -and ($lines -contains "other=$otherId"))
  Check "il manifesto ha $Portraits ritratti, ognuno con impronta, byte ed etichetta ritratto-NN" (($phoneLines.Count -eq $Portraits) -and (@($phoneLines | Where-Object { $_ -notmatch '^phone=[0-9a-f]{64}\|\d+\|ritratto-\d\d\.jpg$' }).Count -eq 0))
  Check "nel manifesto non c'e' nient'altro (nessun notmedia, nessun freed, nessun nome)" (@($lines | Where-Object { $_ -match '^(notmedia|freed|edit|dest|app)=' -or $_ -match 'sorgente' }).Count -eq 0)
  $onPhone = @(Get-ChildItem -LiteralPath (Join-Path $phone "sdcard") -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $_.FullName -notlike "*adb-calls.log" })
  Check "i file sono solo in DCIM\mcg-prova e Pictures\mcg-prova" (($onPhone.Count -eq $Portraits) -and (@($onPhone | Where-Object { $_.FullName -notmatch '\\sdcard\\(DCIM|Pictures)\\mcg-prova\\' }).Count -eq 0))
  Check "sul telefono i nomi sono neutri (ritratto-NN, mai il nome del file d'origine)" (@($onPhone | Where-Object { $_.Name -notmatch '^ritratto-\d\d\.jpg$' }).Count -eq 0)

  Write-Host "2. Verifica a telefono invariato"
  Check "in fase computed le impronte tornano e la cartella dell'app non ha problemi" ((Invoke-S25 ($common + @("-Step", "verify", "-SkipNas", "-Phase", "computed"))) -eq 0)
  Check "in fase erased senza il file di stato la verifica fallisce (codice 4)" ((Invoke-S25 ($common + @("-Step", "verify", "-SkipNas", "-Phase", "erased"))) -eq 4)

  Write-Host "3. Interruzioni nelle tre finestre"
  $env:MCG_ADB = Join-Path $chaos "fake-adb\adb.cmd"
  $env:FAKE_PHONE = $phone
  & $env:MCG_ADB -s FAKE123 shell monkey -p $package -c android.intent.category.LAUNCHER 1 | Out-Null
  $mark = Get-CallCount
  $interrupt = $common + @("-Step", "interrupt", "-Interruptions", "6", "-Window", "all", "-Verify") + $quick
  Check "le interruzioni finiscono e la verifica (fase erased) passa" ((Invoke-S25 $interrupt) -eq 0)
  $calls = @(Get-Calls | Select-Object -Skip $mark)
  Check "il Wi-Fi e' stato riacceso alla fine" ((@($calls | Where-Object { $_ -match 'svc \| wifi' }) | Select-Object -Last 1) -match 'enable$')
  Check "l'adb finto non ha ricevuto altro che comandi previsti" ((Get-Unexpected).Count -eq 0)
  Check "l'app e' stata rilanciata dopo le morti, e almeno una e' morta" ((@($calls | Where-Object { $_ -match 'monkey' }).Count -ge 1) -and (@($calls | Where-Object { $_ -match '^morto:' }).Count -ge 1))
  $scenarios = @($calls | Where-Object { $_ -match 'scenario \| f103-' } | ForEach-Object { ($_ -split '\| ')[-1] })
  Check "gli scenari sono partiti in ordine: prima il calcolo, poi la cancellazione" (($scenarios.Count -eq 2) -and ($scenarios[0] -eq "f103-compute") -and ($scenarios[1] -eq "f103-erase"))
  Check "ogni broadcast e' mirato al solo pacchetto di debug" (@($calls | Where-Object { $_ -match 'broadcast' -and $_ -notmatch '-p \| dev\.pampa\.mycloudgallery\.debug \| --es' }).Count -eq 0)
  $story = if (Test-Path -LiteralPath $log) { Get-Content -LiteralPath $log -Raw } else { "" }
  Check "il racconto ha le tre finestre, in ordine" (($story -match 'Finestra: calcolo') -and ($story -match "Finestra: 'Disattiva e cancella'") -and ($story -match 'Finestra: la ripresa') -and ($story.IndexOf('Finestra: calcolo') -lt $story.IndexOf("Finestra: 'Disattiva e cancella'")) -and ($story.IndexOf("Finestra: 'Disattiva e cancella'") -lt $story.IndexOf('Finestra: la ripresa')))
  Check "il file di stato dell'app dice cancellazione finita" ((Test-Path -LiteralPath $statusFile) -and ((Get-Content -LiteralPath $statusFile -Raw) -match 'phase=erased') -and ((Get-Content -LiteralPath $statusFile -Raw) -match 'keys=0'))
  Check "a cancellazione finita restano solo il diario e lo stato locale" ((@(Get-ChildItem -LiteralPath $noBackup -File | Where-Object { $_.Name -like 'faces-*' } | ForEach-Object { $_.Name } | Sort-Object) -join ",") -eq "faces-erase,faces-local")

  Write-Host "4. La sola finestra del calcolo"
  Check "con -Window compute la verifica (fase computed) passa" ((Invoke-S25 ($common + @("-Step", "interrupt", "-Interruptions", "2", "-Window", "compute", "-Verify") + $quick)) -eq 0)
  Check "il database c'e' (faces-1.db) e il file di stato dice computed" ((Test-Path -LiteralPath (Join-Path $noBackup "faces-1.db")) -and ((Get-Content -LiteralPath $statusFile -Raw) -match 'phase=computed'))
  Check "in fase erased la stessa cartella dell'app fallisce (il database c'e' ancora): codice 4" ((Invoke-S25 ($common + @("-Step", "verify", "-SkipNas", "-Phase", "erased"))) -eq 4)
  Check "in fase computed passa" ((Invoke-S25 ($common + @("-Step", "verify", "-SkipNas", "-Phase", "computed"))) -eq 0)

  Write-Host "5. Una cancellazione che lascia un database e una chiave"
  $env:FAKE_ERASE_LEAVES = "1"
  Check "la verifica dopo la cancellazione fallisce (codice 4: faces-1.db rimasto, keys=1)" ((Invoke-S25 ($common + @("-Step", "interrupt", "-Interruptions", "3", "-Window", "erase", "-Verify") + $quick)) -eq 4)
  Remove-Item Env:FAKE_ERASE_LEAVES -ErrorAction SilentlyContinue
  Check "rifatta bene, la cancellazione passa" ((Invoke-S25 ($common + @("-Step", "interrupt", "-Interruptions", "3", "-Window", "erase", "-Verify") + $quick)) -eq 0)

  Write-Host "6. Un compagno orfano del database"
  Set-Content -LiteralPath (Join-Path $noBackup "faces-7.db-wal") -Value "orfano"
  Check "un -wal senza il suo .db: la verifica fallisce (codice 4)" ((Invoke-S25 ($common + @("-Step", "verify", "-SkipNas", "-Phase", "computed"))) -eq 4)
  Remove-Item -LiteralPath (Join-Path $noBackup "faces-7.db-wal") -Force
  Check "tolto, la verifica passa" ((Invoke-S25 ($common + @("-Step", "verify", "-SkipNas", "-Phase", "computed"))) -eq 0)

  Write-Host "7. Il telefono cambia"
  $files = @(Get-ChildItem -LiteralPath (Join-Path $phone "sdcard") -Recurse -File | Where-Object { $_.FullName -match 'mcg-prova' })
  Remove-Item -LiteralPath $files[0].FullName -Force
  Check "un ritratto tolto dal telefono: senza -AllowFreed la verifica fallisce (codice 4)" ((Invoke-S25 ($common + @("-Step", "verify", "-SkipNas", "-Phase", "computed"))) -eq 4)
  Check "con -AllowFreed passa" ((Invoke-S25 ($common + @("-Step", "verify", "-SkipNas", "-Phase", "computed", "-AllowFreed"))) -eq 0)
  Add-Content -LiteralPath $files[1].FullName -Value "x"
  Check "un ritratto che cambia: la verifica fallisce con il codice 4" ((Invoke-S25 ($common + @("-Step", "verify", "-SkipNas", "-Phase", "computed", "-AllowFreed"))) -eq 4)

  Write-Host "8. Pulizia"
  Check "senza -Yes rifiuta" ((Invoke-S25 ($common + @("-Step", "clean"))) -ne 0)
  Check "senza -Yes non toglie niente" ((@(Get-ChildItem -LiteralPath (Join-Path $phone "sdcard") -Recurse -File -ErrorAction SilentlyContinue).Count -gt 0) -and (Test-Path -LiteralPath $statusFile))
  Check "con -Yes toglie le due cartelle e il file di stato" ((Invoke-S25 ($common + @("-Step", "clean", "-Yes"))) -eq 0)
  Check "non resta nessun ritratto di mcg-prova ne' il file di stato" ((@(Get-ChildItem -LiteralPath (Join-Path $phone "sdcard") -Recurse -File -ErrorAction SilentlyContinue).Count -eq 0) -and -not (Test-Path -LiteralPath $statusFile))
  Check "il resto della cartella dell'app non si tocca" (Test-Path -LiteralPath (Join-Path $noBackup "faces-erase"))
  Check "l'adb finto non ha ricevuto altro che comandi previsti (fino a qui)" ((Get-Unexpected).Count -eq 0)

  Write-Host "9. Le guardie"
  $before = Get-CallCount
  Check "un pacchetto che non e' di debug e' rifiutato" ((Invoke-S25 ($common + @("-Step", "interrupt", "-Package", "dev.pampa.mycloudgallery"))) -ne 0)
  Check "un Wi-Fi spento oltre 120 s e' rifiutato" ((Invoke-S25 ($common + @("-Step", "interrupt", "-WifiOffMaxSeconds", "121"))) -ne 0)
  Check "0 interruzioni sono rifiutate" ((Invoke-S25 ($common + @("-Step", "interrupt", "-Interruptions", "0"))) -ne 0)
  Check "501 interruzioni sono rifiutate" ((Invoke-S25 ($common + @("-Step", "interrupt", "-Interruptions", "501"))) -ne 0)
  Check "una fase sconosciuta e' rifiutata" ((Invoke-S25 ($common + @("-Step", "verify", "-Phase", "boh"))) -ne 0)
  Check "una share che non e' test e' rifiutata" ((Invoke-S25 ($common + @("-Step", "seed", "-DestShare", "test2"))) -ne 0)
  Check "un id di dispositivo non valido e' rifiutato" ((Invoke-S25 ($common + @("-Step", "seed", "-DeviceId", "ABC"))) -ne 0)
  Check "un passo che il modo non ha (edit) e' rifiutato" ((Invoke-S25 ($common + @("-Step", "edit"))) -ne 0)
  Check "un passo che il modo non ha (eval-push) e' rifiutato" ((Invoke-S25 ($common + @("-Step", "eval-push"))) -ne 0)
  Check "i rifiuti non hanno toccato il telefono" ((Get-CallCount) -eq $before)
} finally {
  Remove-Item Env:MCG_ADB, Env:FAKE_PHONE, Env:FAKE_ERASE_LEAVES -ErrorAction SilentlyContinue
  if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }
}

if ($failures.Count -gt 0) {
  Write-Host ("`n{0} controlli non passano." -f $failures.Count) -ForegroundColor Red
  exit 1
}
Write-Host "`nTutto come previsto: il modo faces dello script del telefono funziona contro l'adb finto." -ForegroundColor Green
exit 0
