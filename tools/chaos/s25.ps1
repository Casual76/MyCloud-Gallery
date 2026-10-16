<#
.SYNOPSIS
  Interruzioni casuali sul telefono: la prova delle 500 interruzioni lato telefono. Due modi:
    move    (default) durante una mossa di massa sul NAS (Fase 4B, docs/fase4-progetto.md par. 5.5, P15);
    backup  durante il primo backup di `mcg-prova` (Fase 5A, docs/fase5-progetto.md par. 5.3 e 5.4, P6).
  Come si usa e come si legge il risultato: docs/prova-500.md.

.DESCRIPTION
  --- Modo move ---
  L'utente avvia nell'app una mossa di massa (di norma tra due share di prova, sotto mcg-it/<giro>/), poi lancia questo script.
  A intervalli casuali lo script fa UNA di due cose:
    - uccide il processo dell'app (la morte del processo: l'app deve ripartire dal suo diario locale);
    - spegne il Wi-Fi per qualche secondo e lo riaccende (la rete che cade a meta' di un passo).
  Dopo ogni morte rilancia l'app (se non si chiede altrimenti), cosi' la ripresa parte come all'avvio vero. A fine prova aspetta che la
  ripresa finisca; con -Verify lancia il verificatore degli invarianti sull'area di prova (tools\chaos\area.ps1 -Mode verify).

  --- Modo backup (-Mode backup, con -Step) ---
  Il telefono e' quello vero, con la build DEBUG e l'account di prova: il backup legge SOLO DCIM/mcg-prova/ e Pictures/mcg-prova/ (D13) e scrive in
  <DestShare>/<DestDir>/Backup/ (default test/mcg-campo/Backup/). Quattro passi, in quest'ordine:
    seed       semina `mcg-prova` sul telefono con adb: i file di tools\fixtures (formati misti: JPEG con GPS, HEIC, PNG, WebP, motion photo, screenshot,
               MP4), file jpeg unici fino a -Photos, e -BigVideos video da -BigVideoMb MB fatti SUL telefono (copia di un MP4 piu' byte casuali: niente
               da trasferire); chiede la scansione del MediaStore; scrive il MANIFESTO con `adb shell sha256sum` (impronta, byte, etichetta).
    interrupt  l'utente ha gia' avviato il primo backup nell'app ("Inizia ora"); lo script uccide il processo o spegne il Wi-Fi a tempi casuali (come
               nel modo move), rilancia l'app, aspetta che la ripresa finisca e, con -Verify, passa a `verify`.
    verify     controlla che il telefono non sia cambiato (le impronte tornano) e lancia il verificatore dal PC sull'area di prova, in sola lettura:
               tools\chaos\area.ps1 -Mode backup -Stage verify -Manifest <il manifesto>.
    clean      toglie `mcg-prova` dal telefono (solo quelle due cartelle, con -Yes).
  Lo script non scrive sul NAS e non legge altro dal telefono che `mcg-prova`. Il verificatore dal PC sta in core-backup (BackupAreaVerifier).

  Il calendario delle interruzioni (quando e che cosa) dipende solo dal seme: lo stesso -Seed rifa' la stessa prova. Con -DryRun lo script
  stampa il calendario (o il piano della semina) e i comandi adb che userebbe e NON ESEGUE NIENTE (nemmeno adb): e' il modo di controllarlo
  senza il telefono. Con -SelfTest prova solo la conversione dell'uscita di `sha256sum` nel manifesto, su un testo finto.
  Con la variabile MCG_ADB che punta a un adb FINTO (uno script che imita il telefono in una cartella) si prova l'intera semina e la
  verifica senza telefono: lo fa tools\chaos\s25-finto.ps1, che lancia tutti i passi contro tools\chaos\fake-adb\ e controlla gli esiti.

  Sicurezza: tocca un solo dispositivo (-Serial, o ANDROID_SERIAL), mai "tutti"; rifiuta di partire senza un numero di serie vero
  (ANDROID_SERIAL=none vale "nessun dispositivo": solo -DryRun e -SelfTest); il Wi-Fi viene SEMPRE riacceso, anche se lo script e' interrotto.
  Funziona sulla build debug (run-as) con gli account di prova (par. 5.7); il modo backup rifiuta un pacchetto che non sia di debug.

.PARAMETER Mode                move | backup (default move).
.PARAMETER Step                Per backup: seed | interrupt | verify | clean (default interrupt).
.PARAMETER Serial              Numero di serie del telefono (`adb devices`). Default: ANDROID_SERIAL.
.PARAMETER Package             Il pacchetto dell'app (default: la build debug, dev.pampa.mycloudgallery.debug).
.PARAMETER Interruptions       Quante interruzioni (default 100, come chiede il par. 5.5 / 5.4 per il telefono).
.PARAMETER Seed                Il seme del calendario (default: casuale; lo script lo stampa, cosi' la prova si rifa'). Per la semina: il seme dei file unici.
.PARAMETER MinGapSeconds       Attesa minima fra due interruzioni (default 6).
.PARAMETER MaxGapSeconds       Attesa massima fra due interruzioni (default 40).
.PARAMETER WifiPercent         Su cento interruzioni, quante sono col Wi-Fi (le altre uccidono il processo; default 40).
.PARAMETER WifiOffMinSeconds   Il Wi-Fi resta spento almeno questo (default 3).
.PARAMETER WifiOffMaxSeconds   ...e al massimo questo (default 20).
.PARAMETER KillMode            Come si uccide il processo: run-as (kill -9 dalla build debug; poi `am crash` se non riesce), crash
                               (am crash), am-kill (am kill: uccide solo un processo non in primo piano), force-stop (am force-stop:
                               toglie anche i lavori in coda, quindi la ripresa parte solo dal rilancio).
.PARAMETER RelaunchDelaySeconds  Dopo una morte, quanto aspettare prima di rilanciare l'app (default 4).
.PARAMETER NoRelaunch          Non rilancia l'app dopo una morte (la ripresa e' lasciata al sistema).
.PARAMETER SettleSeconds       A fine prova: quanto aspettare che la ripresa finisca (default 180; per il backup con i video grandi alzarlo).
.PARAMETER Manifest            Il manifesto: per move, quello scritto da `area.ps1 -Mode seed`; per backup, quello scritto da `-Step seed` (o da leggere).
.PARAMETER Verify              A fine prova lancia il verificatore sull'area (serve -Manifest in move; in backup il manifesto di `seed`).
.PARAMETER Log                 Dove scrivere il racconto (default: build\chaos\s25-<data>.log).
.PARAMETER Photos              Per backup/seed: quante foto in tutto (default 300).
.PARAMETER BigVideos           Per backup/seed: quanti video grandi (default 2).
.PARAMETER BigVideoMb          Per backup/seed: quanto e' grande ciascuno, in MB (default 2560).
.PARAMETER FixturesDir         Per backup/seed: la cartella dei file di tools\fixtures gia' generati (default: li genera con generate.ps1 -SkipBig).
.PARAMETER DestShare           Per backup: la share di destinazione (default test).
.PARAMETER DestDir             Per backup: la cartella sotto cui sta Backup/ (D13, default mcg-campo).
.PARAMETER Device              Per backup: l'id del dispositivo del backup (Diagnostica), per riconoscere i suoi temporanei. Senza, qualunque `-bk-`.
.PARAMETER Run                 Per backup: il nome del giro (default: nuovo, aaaammgg-hhmmss-<8 esadecimali>).
.PARAMETER SkipNas             Per backup/verify: controlla solo le impronte del telefono, senza lanciare il verificatore sul NAS.
.PARAMETER Yes                 Per backup/clean: serve per togliere `mcg-prova` dal telefono.
.PARAMETER DryRun              Stampa il calendario/il piano e i comandi, non esegue niente.
.PARAMETER SelfTest            Prova la conversione dell'uscita di sha256sum nel manifesto, su un testo finto (nessun adb).

.EXAMPLE
  # Controllo a secco, senza telefono:
  powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -DryRun -Interruptions 10 -Seed 42

.EXAMPLE
  # La prova vera sul telefono (dopo aver avviato la mossa di massa nell'app):
  powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Serial R5CX1234567 -Interruptions 100 -Manifest build\chaos\manifest-20261016-101500.txt -Verify

.EXAMPLE
  # Il backup: semina, (l'utente avvia il primo backup nell'app), interrompi e verifica.
  powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode backup -Step seed -DryRun
  powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode backup -Step seed -Serial R5CX1234567 -Run 20261016-101500-1a2b3c4d
  powershell -ExecutionPolicy Bypass -File tools\chaos\s25.ps1 -Mode backup -Step interrupt -Serial R5CX1234567 -Interruptions 100 -Manifest build\chaos\backup-manifest-20261016-101500-1a2b3c4d.txt -Verify
#>
[CmdletBinding()]
param(
  [ValidateSet("move", "backup")][string]$Mode = "move",
  [ValidateSet("seed", "interrupt", "verify", "clean")][string]$Step = "interrupt",
  [string]$Serial = $env:ANDROID_SERIAL,
  [string]$Package = "dev.pampa.mycloudgallery.debug",
  [int]$Interruptions = 100,
  [int]$Seed = 0,
  [int]$MinGapSeconds = 6,
  [int]$MaxGapSeconds = 40,
  [int]$WifiPercent = 40,
  [int]$WifiOffMinSeconds = 3,
  [int]$WifiOffMaxSeconds = 20,
  [ValidateSet("run-as", "crash", "am-kill", "force-stop")][string]$KillMode = "run-as",
  [int]$RelaunchDelaySeconds = 4,
  [switch]$NoRelaunch,
  [int]$SettleSeconds = 180,
  [string]$Manifest,
  [switch]$Verify,
  [string]$Log,
  [int]$Photos = 300,
  [int]$BigVideos = 2,
  [int]$BigVideoMb = 2560,
  [string]$FixturesDir,
  [string]$DestShare = "test",
  [string]$DestDir = "mcg-campo",
  [string]$Device,
  [string]$Run,
  [switch]$Yes,
  [switch]$SkipNas,
  [switch]$DryRun,
  [switch]$SelfTest
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$isBackup = ($Mode -eq "backup")

function Fail([string]$message, [int]$code = 1) {
  Write-Host $message -ForegroundColor Red
  exit $code
}

# --- Le cartelle del telefono che il backup puo' leggere (D13): scritte qui, mai da un parametro -------------------------------------
$PhoneFolders = @("/sdcard/DCIM/mcg-prova", "/sdcard/Pictures/mcg-prova")

# --- L'uscita di `sha256sum` e di `stat` sul telefono diventa il manifesto (par. 5.4: il manifesto da `adb shell sha256sum`) -------------
# hashLines: "<64 esadecimali>  <percorso>"; sizeLines: "<byte> <percorso>". L'etichetta e' solo il nome del file (la cartella e' `mcg-prova`).
function ConvertTo-PhoneFileList([string[]]$hashLines, [string[]]$sizeLines) {
  $sizes = @{}
  foreach ($line in $sizeLines) {
    if ($line -match '^\s*(\d+)\s+(.+?)\s*$') { $sizes[$Matches[2]] = [long]$Matches[1] }
  }
  $files = @()
  foreach ($line in $hashLines) {
    if ($line -match '^([0-9a-fA-F]{64})\s+\*?(.+?)\s*$') {
      $path = $Matches[2]
      $sha = $Matches[1].ToLowerInvariant()
      if (-not $sizes.ContainsKey($path)) { throw "Manca la dimensione di un file del telefono." }
      # Un file a zero byte non e' un media da salvare (E-M12): il backup non lo carica, quindi non entra nel manifesto.
      if ($sizes[$path] -le 0) { continue }
      $files += [pscustomobject]@{ Sha = $sha; Size = $sizes[$path]; Label = (($path -split '/')[-1]) }
    }
  }
  return $files
}

function New-BackupManifestText([string]$runId, [string]$deviceId, [string]$destShare, [string]$destDir, $files) {
  $lines = @("# mcg-backup-manifest 1", "run=$runId", "device=$deviceId", "dest=$destShare|$destDir", "app=$destShare|")
  foreach ($file in $files) { $lines += ("phone={0}|{1}|{2}" -f $file.Sha, $file.Size, $file.Label) }
  return ($lines -join "`n") + "`n"
}

if ($SelfTest) {
  $hash = @(
    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  /sdcard/DCIM/mcg-prova/vuoto.jpg",
    "2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae  /sdcard/DCIM/mcg-prova/IMG_0001.jpg",
    "fcde2b2edba56bf408601fb721fe9b5c338d10ee429ea04fae5511b68fbf8fb9 */sdcard/Pictures/mcg-prova/VID_big_1.mp4",
    "una riga che non e' un hash"
  )
  $size = @("0 /sdcard/DCIM/mcg-prova/vuoto.jpg", "1234 /sdcard/DCIM/mcg-prova/IMG_0001.jpg", "2684354560 /sdcard/Pictures/mcg-prova/VID_big_1.mp4")
  $files = @(ConvertTo-PhoneFileList $hash $size)
  $text = New-BackupManifestText "20261016-101500-1a2b3c4d" "" "test" "mcg-campo" $files
  $expected = "# mcg-backup-manifest 1`nrun=20261016-101500-1a2b3c4d`ndevice=`ndest=test|mcg-campo`napp=test|`n" +
    "phone=2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae|1234|IMG_0001.jpg`n" +
    "phone=fcde2b2edba56bf408601fb721fe9b5c338d10ee429ea04fae5511b68fbf8fb9|2684354560|VID_big_1.mp4`n"
  Write-Host $text
  if ($text -ne $expected) { Fail "SelfTest: il manifesto non e' quello atteso." 3 }
  Write-Host "SelfTest: la conversione dell'uscita di sha256sum nel manifesto e' giusta (un file a zero byte e una riga estranea scartati)." -ForegroundColor Green
  exit 0
}

# --- I parametri -----------------------------------------------------------------------------------------------------------
if ($Interruptions -lt 1 -or $Interruptions -gt 1000) { Fail "-Interruptions deve stare fra 1 e 1000." }
if ($MinGapSeconds -lt 1 -or $MaxGapSeconds -lt $MinGapSeconds -or $MaxGapSeconds -gt 600) { Fail "Le attese vanno da 1 a 600 secondi, il massimo non sotto il minimo." }
if ($WifiPercent -lt 0 -or $WifiPercent -gt 100) { Fail "-WifiPercent deve stare fra 0 e 100." }
if ($WifiOffMinSeconds -lt 1 -or $WifiOffMaxSeconds -lt $WifiOffMinSeconds -or $WifiOffMaxSeconds -gt 120) { Fail "Il Wi-Fi spento dura da 1 a 120 secondi, il massimo non sotto il minimo." }
if ($Package -notmatch '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$') { Fail "Nome del pacchetto non valido." }
if ($Verify -and -not $Manifest) { Fail "-Verify vuole -Manifest (il file scritto da area.ps1 -Mode seed, o da questo script con -Mode backup -Step seed)." }
if ($isBackup) {
  if ($Package -notmatch '\.debug$') { Fail "Il modo backup vale solo per la build debug con gli account di prova (D13): il pacchetto deve finire con .debug." }
  if ($Photos -lt 1 -or $Photos -gt 5000) { Fail "-Photos deve stare fra 1 e 5000." }
  if ($BigVideos -lt 0 -or $BigVideos -gt 4) { Fail "-BigVideos deve stare fra 0 e 4." }
  if ($BigVideoMb -lt 1 -or $BigVideoMb -gt 4096) { Fail "-BigVideoMb deve stare fra 1 e 4096." }
  if ($DestShare -notmatch '^(test|test2|test-comune)$') { Fail "-DestShare deve essere una share di prova (test, test2, test-comune)." }
  if ($DestDir -notmatch '^[A-Za-z0-9._-]+$' -or $DestDir -match '^\.+$') { Fail "-DestDir deve essere un solo nome di cartella semplice (default mcg-campo)." }
  if ($Run -and $Run -notmatch '^[0-9]{8}-[0-9]{6}-[0-9a-f]{8}$') { Fail "-Run deve avere la forma aaaammgg-hhmmss-<8 esadecimali>." }
  if ($Device -and $Device -notmatch '^[A-Za-z0-9]{26}$') { Fail "-Device e' l'id del dispositivo: 26 caratteri (Diagnostica)." }
}
if ($Seed -eq 0) { $Seed = Get-Random -Minimum 1 -Maximum 2147483647 }
$runId = if ($Run) { $Run } else { "{0}-{1:x8}" -f (Get-Date -Format "yyyyMMdd-HHmmss"), (Get-Random -Minimum 0 -Maximum ([int]::MaxValue)) }
if ($isBackup -and -not $Manifest) { $Manifest = Join-Path $root "build\chaos\backup-manifest-$runId.txt" }

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
if (-not $Log) { $Log = Join-Path $root "build\chaos\s25-$stamp.log" }
$logDir = Split-Path -Parent $Log
$logLines = New-Object System.Collections.Generic.List[string]
function Write-Log([string]$text) {
  $line = "{0}  {1}" -f (Get-Date -Format "HH:mm:ss"), $text
  Write-Host $line
  $logLines.Add($line)
}

# --- Il calendario: dipende solo dal seme ------------------------------------------------------------------------------------
$random = New-Object System.Random($Seed)
$plan = @()
for ($i = 1; $i -le $Interruptions; $i++) {
  $gap = $random.Next($MinGapSeconds, $MaxGapSeconds + 1)
  if ($random.Next(100) -lt $WifiPercent) {
    $plan += [pscustomobject]@{ N = $i; Gap = $gap; Action = "wifi"; Duration = $random.Next($WifiOffMinSeconds, $WifiOffMaxSeconds + 1) }
  } else {
    $plan += [pscustomobject]@{ N = $i; Gap = $gap; Action = "kill"; Duration = 0 }
  }
}
$wifiCount = @($plan | Where-Object { $_.Action -eq "wifi" }).Count
$killCount = $plan.Count - $wifiCount
$totalSeconds = ($plan | Measure-Object -Property Gap -Sum).Sum + ($plan | Measure-Object -Property Duration -Sum).Sum +
  $killCount * $(if ($NoRelaunch) { 0 } else { $RelaunchDelaySeconds }) + $SettleSeconds

# --- I comandi che si userebbero (stampati a secco, eseguiti davvero altrimenti) --------------------------------------------------
$serialText = if ($Serial) { $Serial } else { "<numero di serie>" }
$commandPlan = [ordered]@{
  "stato del telefono"         = "adb -s $serialText get-state"
  "app installata"             = "adb -s $serialText shell pm path $Package"
  "processo dell'app"          = "adb -s $serialText shell pidof $Package"
  "uccidere (run-as)"          = "adb -s $serialText shell run-as $Package kill -9 <pid>      (se non riesce: am crash)"
  "uccidere (crash)"           = "adb -s $serialText shell am crash $Package"
  "uccidere (am-kill)"         = "adb -s $serialText shell am kill $Package"
  "uccidere (force-stop)"      = "adb -s $serialText shell am force-stop $Package"
  "Wi-Fi spento"               = "adb -s $serialText shell svc wifi disable"
  "Wi-Fi acceso"               = "adb -s $serialText shell svc wifi enable"
  "rilancio dell'app"          = "adb -s $serialText shell monkey -p $Package -c android.intent.category.LAUNCHER 1"
}

function Get-SdkDir {
  $localProperties = Join-Path $root "local.properties"
  if (Test-Path -LiteralPath $localProperties) {
    $match = Select-String -LiteralPath $localProperties -Pattern '^\s*sdk\.dir\s*=\s*(.+)$' | Select-Object -First 1
    if ($match) { return $match.Matches[0].Groups[1].Value.Trim().Replace("\:", ":").Replace("\\", "\") }
  }
  if ($env:ANDROID_HOME) { return $env:ANDROID_HOME }
  return $env:ANDROID_SDK_ROOT
}

# Uno solo dei comandi adb: sempre con -s, mai su "tutti i dispositivi". Si trova e si usa solo quando serve davvero (mai a secco).
$script:adb = $null
function Initialize-Adb {
  if (-not $Serial -or $Serial -eq "none") {
    Fail "Serve il numero di serie di un telefono vero (-Serial o ANDROID_SERIAL): 'none' vuol dire nessun dispositivo, e questo script non sceglie da solo." 2
  }
  # MCG_ADB: un adb finto per provare lo script senza telefono (modalita' finta): se c'e', e' l'unico adb che si usa.
  if ($env:MCG_ADB) {
    if (-not (Test-Path -LiteralPath $env:MCG_ADB)) { Fail "MCG_ADB non punta a un file." }
    $script:adb = $env:MCG_ADB
  }
  $sdk = if ($script:adb) { $null } else { Get-SdkDir }
  if ($sdk -and (Test-Path -LiteralPath (Join-Path $sdk "platform-tools\adb.exe"))) { $script:adb = Join-Path $sdk "platform-tools\adb.exe" }
  if (-not $script:adb) { $command = Get-Command adb -ErrorAction SilentlyContinue; if ($command) { $script:adb = $command.Source } }
  if (-not $script:adb) { Fail "adb non trovato (sdk.dir in local.properties, o adb nel PATH)." }
  $state = Invoke-Adb @("get-state")
  if ($state.Text -ne "device") { Fail "Il telefono $Serial non e' pronto (adb dice: '$($state.Text)')." }
}

function Invoke-Adb([string[]]$arguments) {
  $previous = $ErrorActionPreference
  $ErrorActionPreference = "Continue"
  try {
    $lines = @(& $script:adb -s $Serial @arguments 2>&1 | ForEach-Object { $_.ToString() })
    $code = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $previous
  }
  return [pscustomobject]@{ Code = $code; Text = (($lines -join "`n").Trim()); Lines = $lines }
}

function Assert-Debug-App {
  $installed = Invoke-Adb @("shell", "pm", "path", $Package)
  if ($installed.Text -notmatch "package:") { Fail "L'app $Package non e' installata sul telefono." }
}

function Start-App {
  [void](Invoke-Adb @("shell", "monkey", "-p", $Package, "-c", "android.intent.category.LAUNCHER", "1"))
}

function Stop-AppProcess {
  $pidText = (Invoke-Adb @("shell", "pidof", $Package)).Text
  if (-not $pidText) { return "il processo non era in esecuzione" }
  $pidNumber = ($pidText -split "\s+")[0]
  if ($pidNumber -notmatch '^\d+$') { return "pid non valido" }
  switch ($KillMode) {
    "run-as" {
      $run = Invoke-Adb @("shell", "run-as", $Package, "kill", "-9", $pidNumber)
      if ($run.Code -eq 0 -and $run.Text -notmatch "(?i)not debuggable|unknown package|Operation not permitted") { return "kill -9 del pid $pidNumber" }
      [void](Invoke-Adb @("shell", "am", "crash", $Package))
      return "run-as non riuscito, am crash"
    }
    "crash" { [void](Invoke-Adb @("shell", "am", "crash", $Package)); return "am crash" }
    "am-kill" { [void](Invoke-Adb @("shell", "am", "kill", $Package)); return "am kill" }
    "force-stop" { [void](Invoke-Adb @("shell", "am", "force-stop", $Package)); return "am force-stop" }
  }
}

function Save-Log {
  if (-not (Test-Path -LiteralPath $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }
  [System.IO.File]::WriteAllLines($Log, $logLines)
  Write-Host "Racconto: $Log"
}

# =================================================================================================================================
# Modo backup: seed, verify, clean (interrupt usa il calendario e il ciclo qui sotto, uguali al modo move)
# =================================================================================================================================

# I comandi del telefono per `mcg-prova`: scritti qui, con le due cartelle fisse.
$listCommand = "find " + ($PhoneFolders -join " ") + " -type f"
$hashCommand = "$listCommand -exec sha256sum {} +"
$sizeCommand = "$listCommand -exec stat -c '%s %n' {} +"
$scanCommand = "content call --uri content://media/ --method scan_volume --arg external_primary"

function Get-PhoneFiles {
  $hash = Invoke-Adb @("shell", $hashCommand)
  if ($hash.Code -ne 0) { Fail "sha256sum sul telefono non e' riuscito (adb dice: '$($hash.Text)')." }
  $size = Invoke-Adb @("shell", $sizeCommand)
  if ($size.Code -ne 0) { Fail "stat sul telefono non e' riuscito (adb dice: '$($size.Text)')." }
  return @(ConvertTo-PhoneFileList $hash.Lines $size.Lines)
}

function Write-SeedPlan {
  Write-Host "=== PIANO DELLA SEMINA (a secco): nessun comando adb viene eseguito ===" -ForegroundColor Yellow
  Write-Host ("Telefono: {0}   Giro: {1}   Seme: {2}   Destinazione: {3}/{4}/Backup/" -f $serialText, $runId, $Seed, $DestShare, $DestDir)
  Write-Host ("File: i fixture di tools\fixtures ({0}), fino a {1} foto in tutto (jpeg unici dal seme), {2} video da {3} MB fatti sul telefono" -f $(if ($FixturesDir) { $FixturesDir } else { "generati con generate.ps1 -SkipBig" }), $Photos, $BigVideos, $BigVideoMb)
  Write-Host "Cartelle del telefono (le uniche toccate): $($PhoneFolders -join ', ')"
  Write-Host ""
  Write-Host "Comandi adb che userebbe:"
  Write-Host ("  {0,-26} adb -s {1} get-state" -f "stato del telefono", $serialText)
  Write-Host ("  {0,-26} adb -s {1} shell mkdir -p {2}" -f "cartelle", $serialText, ($PhoneFolders -join " "))
  Write-Host ("  {0,-26} adb -s {1} push <staging>\DCIM\mcg-prova /sdcard/DCIM/   e   push <staging>\Pictures\mcg-prova /sdcard/Pictures/" -f "file piccoli", $serialText)
  for ($i = 1; $i -le $BigVideos; $i++) {
    Write-Host ("  {0,-26} adb -s {1} shell cp <un mp4 di mcg-prova> {2}/VID_mcg_grande_{3}.mp4" -f "video grande $i (copia)", $serialText, $PhoneFolders[0], $i)
    Write-Host ("  {0,-26} adb -s {1} shell sh -c 'dd if=/dev/urandom bs=1048576 count={2} >> {3}/VID_mcg_grande_{4}.mp4'" -f "video grande $i (byte)", $serialText, $BigVideoMb, $PhoneFolders[0], $i)
  }
  Write-Host ("  {0,-26} adb -s {1} shell {2}" -f "scansione del MediaStore", $serialText, $scanCommand)
  Write-Host ("  {0,-26} adb -s {1} shell {2}" -f "impronte (sha256sum)", $serialText, $hashCommand)
  Write-Host ("  {0,-26} adb -s {1} shell {2}" -f "dimensioni (stat)", $serialText, $sizeCommand)
  Write-Host ""
  Write-Host ("Il manifesto va in {0}: poi l'utente avvia il primo backup nell'app e si lancia -Step interrupt." -f $Manifest) -ForegroundColor Green
}

# Aspetta che il MediaStore abbia indicizzato i file di mcg-prova (se no l'app non li vede e il verificatore direbbe "persi"): conta le righe
# con `mcg-prova` nel percorso. Un esito diverso non ferma la semina, la avvisa.
function Wait-MediaStore([int]$expected) {
  $deadline = (Get-Date).AddSeconds(180)
  $seen = 0
  while ((Get-Date) -lt $deadline) {
    $rows = Invoke-Adb @("shell", "content query --uri content://media/external/file --projection _data")
    $seen = @($rows.Lines | Where-Object { $_ -match 'mcg-prova' }).Count
    if ($seen -ge $expected) { return $seen }
    Start-Sleep -Seconds 5
  }
  Write-Log "ATTENZIONE: il MediaStore ha indicizzato ${seen} file su ${expected}; l'app potrebbe non vederli tutti (ripetere la scansione)."
  return $seen
}

function Invoke-Seed {
  Initialize-Adb
  Assert-Debug-App
  $work = Join-Path $root "build\chaos\seed-$runId"
  New-Item -ItemType Directory -Force -Path $work | Out-Null
  # 1. I fixture: formati misti.
  if ($FixturesDir) {
    $fixtureRoot = (Resolve-Path -LiteralPath $FixturesDir).Path
  } else {
    $fixtureRoot = Join-Path $work "fixtures"
    Write-Log "Genero i fixture di prova (generate.ps1 -SkipBig)..."
    & (Join-Path $root "tools\fixtures\generate.ps1") -SkipBig -Out $fixtureRoot
    if ($LASTEXITCODE -ne 0) { Fail "generate.ps1 non e' riuscito." }
  }
  $allowed = @(".jpg", ".jpeg", ".png", ".webp", ".gif", ".heic", ".heif", ".dng", ".mp4", ".mov")
  $fixtures = @(Get-ChildItem -LiteralPath $fixtureRoot -Recurse -File | Where-Object { $allowed -contains $_.Extension.ToLowerInvariant() -and $_.Length -gt 0 })
  if ($fixtures.Count -eq 0) { Fail "Nessun fixture in $fixtureRoot." }
  # I due contenitori locali hanno il nome della cartella del telefono: `adb push <cartella> /sdcard/DCIM/` crea /sdcard/DCIM/mcg-prova.
  $dcim = Join-Path $work "staging\DCIM\mcg-prova"
  $pictures = Join-Path $work "staging\Pictures\mcg-prova"
  New-Item -ItemType Directory -Force -Path $dcim, $pictures | Out-Null
  $used = @{}
  $stagedMp4 = $null
  foreach ($file in $fixtures) {
    $name = $file.Name
    if ($used.ContainsKey($name.ToLowerInvariant())) { $name = "{0}_{1}{2}" -f $file.BaseName, $used.Count, $file.Extension }
    $used[$name.ToLowerInvariant()] = $true
    Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $dcim $name)
    if (-not $stagedMp4 -and $file.Extension -ieq ".mp4") { $stagedMp4 = $name }
  }
  # 2. Foto uniche fino a -Photos: un jpeg dei fixture con in coda byte casuali dal seme (un jpeg ignora cio' che segue la fine dell'immagine);
  #    meta' in DCIM e meta' in Pictures (le due sorgenti di D13).
  $base = $fixtures | Where-Object { $_.Extension -match '^\.jpe?g$' } | Sort-Object Length | Select-Object -First 1
  $random = New-Object System.Random($Seed)
  if ($base) {
    $baseBytes = [System.IO.File]::ReadAllBytes($base.FullName)
    $missing = $Photos - @(Get-ChildItem -LiteralPath $dcim -File | Where-Object { $_.Extension -match '^\.(jpe?g|png|webp|heic|heif|gif|dng)$' }).Count
    for ($i = 1; $i -le $missing; $i++) {
      $tail = New-Object byte[] 24
      $random.NextBytes($tail)
      $target = if ($i % 2 -eq 0) { $pictures } else { $dcim }
      [System.IO.File]::WriteAllBytes((Join-Path $target ("IMG_mcg_{0:D4}.jpg" -f $i)), [byte[]]($baseBytes + $tail))
    }
  }
  # 3. Sul telefono: le due cartelle, i file piccoli, i video grandi, la scansione.
  Write-Log "Preparo le cartelle del telefono: $($PhoneFolders -join ', ')"
  [void](Invoke-Adb (@("shell", "mkdir", "-p") + $PhoneFolders))
  foreach ($pair in @(@($dcim, "/sdcard/DCIM/"), @($pictures, "/sdcard/Pictures/"))) {
    if (@(Get-ChildItem -LiteralPath $pair[0] -File).Count -gt 0) {
      Write-Log "Spingo $($pair[0]) su $($pair[1])..."
      $result = Invoke-Adb @("push", $pair[0], $pair[1])
      if ($result.Code -ne 0) { Fail "adb push non e' riuscito (adb dice: '$($result.Text)')." }
    }
  }
  for ($i = 1; $i -le $BigVideos; $i++) {
    if (-not $stagedMp4) { Fail "Serve un mp4 fra i fixture per fare i video grandi." }
    $target = "$($PhoneFolders[0])/VID_mcg_grande_$i.mp4"
    Write-Log "Faccio il video grande $i sul telefono ($BigVideoMb MB)..."
    [void](Invoke-Adb @("shell", "cp", "$($PhoneFolders[0])/$stagedMp4", $target))
    $dd = Invoke-Adb @("shell", "sh", "-c", "'dd if=/dev/urandom bs=1048576 count=$BigVideoMb >> $target'")
    if ($dd.Code -ne 0) { Fail "dd sul telefono non e' riuscito (spazio?)." }
  }
  Write-Log "Chiedo la scansione del MediaStore..."
  [void](Invoke-Adb @("shell", $scanCommand))
  # 4. Il manifesto: le impronte vere, dal telefono.
  $files = Get-PhoneFiles
  if ($files.Count -eq 0) { Fail "Nessun file di mcg-prova sul telefono dopo la semina." }
  [void](Wait-MediaStore $files.Count)
  $text = New-BackupManifestText $runId $Device $DestShare $DestDir $files
  $manifestDir = Split-Path -Parent $Manifest
  if ($manifestDir -and -not (Test-Path -LiteralPath $manifestDir)) { New-Item -ItemType Directory -Path $manifestDir | Out-Null }
  [System.IO.File]::WriteAllText($Manifest, $text)
  Write-Log ("Semina finita: {0} file in mcg-prova (somma {1:N0} byte). Manifesto: {2}" -f $files.Count, (($files | Measure-Object -Property Size -Sum).Sum), $Manifest)
  Write-Host "Adesso, nell'app (build debug, account di prova), avvia il primo backup (Inizia ora) e lancia: s25.ps1 -Mode backup -Step interrupt -Manifest `"$Manifest`" -Verify" -ForegroundColor Green
}

function Invoke-VerifyBackup {
  if (-not (Test-Path -LiteralPath $Manifest)) { Fail "Manifesto non trovato: $Manifest" }
  # Il telefono non deve essere cambiato: nella 5A il backup non scrive niente sul telefono.
  Initialize-Adb
  $expected = @{}
  foreach ($line in (Get-Content -LiteralPath $Manifest)) {
    if ($line -match '^phone=([0-9a-f]{64})\|(\d+)\|(.+)$') { $expected["$($Matches[1])|$($Matches[2])|$($Matches[3])"] = $true }
  }
  $now = @{}
  foreach ($file in (Get-PhoneFiles)) { $now["$($file.Sha)|$($file.Size)|$($file.Label)"] = $true }
  $changed = @($expected.Keys | Where-Object { -not $now.ContainsKey($_) }).Count + @($now.Keys | Where-Object { -not $expected.ContainsKey($_) }).Count
  if ($changed -gt 0) { Fail "Il telefono e' cambiato durante la prova ($changed file diversi dal manifesto): nella 5A il backup non tocca il telefono." 4 }
  Write-Log "Il telefono non e' cambiato: le impronte di mcg-prova tornano con il manifesto."
  if ($SkipNas) {
    Write-Log "Verifica sul NAS saltata (-SkipNas): solo le impronte del telefono."
    return 0
  }
  $verify = Join-Path $PSScriptRoot "area.ps1"
  Write-Log "Verifica degli invarianti sull'area di prova (in sola lettura)..."
  & $verify -Mode backup -Stage verify -Manifest $Manifest
  return $LASTEXITCODE
}

function Invoke-Clean {
  if (-not $Yes) { Fail "Togliere mcg-prova dal telefono vuole -Yes." }
  Initialize-Adb
  Assert-Debug-App
  foreach ($folder in $PhoneFolders) {
    # Solo le due cartelle di prova, per nome intero: nessun parametro entra nel comando.
    [void](Invoke-Adb @("shell", "rm", "-rf", $folder))
    Write-Log "Tolta la cartella $folder dal telefono."
  }
  [void](Invoke-Adb @("shell", $scanCommand))
}

if ($isBackup -and $Step -eq "seed") {
  if ($DryRun) { Write-SeedPlan; exit 0 }
  try { Invoke-Seed } finally { Save-Log }
  exit 0
}
if ($isBackup -and $Step -eq "verify") {
  if ($DryRun) {
    Write-Host "=== A SECCO: non lancio niente ===" -ForegroundColor Yellow
    Write-Host ("adb -s {0} shell {1}     (le impronte di mcg-prova contro il manifesto)" -f $serialText, $hashCommand)
    Write-Host ("tools\chaos\area.ps1 -Mode backup -Stage verify -Manifest {0}     (sola lettura dell'area di prova)" -f $Manifest)
    exit 0
  }
  try { $code = Invoke-VerifyBackup } finally { Save-Log }
  exit $code
}
if ($isBackup -and $Step -eq "clean") {
  if ($DryRun) {
    Write-Host "=== A SECCO: non lancio niente ===" -ForegroundColor Yellow
    foreach ($folder in $PhoneFolders) { Write-Host ("adb -s {0} shell rm -rf {1}     (serve -Yes)" -f $serialText, $folder) }
    Write-Host ("adb -s {0} shell {1}" -f $serialText, $scanCommand)
    exit 0
  }
  try { Invoke-Clean } finally { Save-Log }
  exit 0
}

# =================================================================================================================================
# Le interruzioni: uguali nei due modi (move, e backup con -Step interrupt)
# =================================================================================================================================

if ($DryRun) {
  Write-Host "=== PROVA A SECCO: nessun comando adb viene eseguito ===" -ForegroundColor Yellow
  Write-Host ("Modo: {0}{1}   Telefono: {2}   Pacchetto: {3}   Seme: {4}   Uccisione: {5}{6}" -f $Mode, $(if ($isBackup) { " (interrupt)" } else { "" }), $serialText, $Package, $Seed, $KillMode, $(if ($NoRelaunch) { " (senza rilancio)" } else { "" }))
  Write-Host ("{0} interruzioni: {1} morti del processo, {2} cadute del Wi-Fi; durata prevista circa {3} minuti" -f $plan.Count, $killCount, $wifiCount, [math]::Ceiling($totalSeconds / 60.0))
  Write-Host ""
  Write-Host ("{0,4}  {1,8}  {2}" -f "n.", "attesa", "azione")
  foreach ($event in $plan) {
    $what = if ($event.Action -eq "wifi") { "Wi-Fi spento per $($event.Duration) s, poi acceso" } else { "morte del processo ($KillMode)" + $(if ($NoRelaunch) { "" } else { ", rilancio dopo $RelaunchDelaySeconds s" }) }
    Write-Host ("{0,4}  {1,6} s  {2}" -f $event.N, $event.Gap, $what)
  }
  Write-Host ""
  Write-Host "Comandi adb che userebbe:"
  foreach ($name in $commandPlan.Keys) { Write-Host ("  {0,-24} {1}" -f $name, $commandPlan[$name]) }
  if ($Verify) {
    if ($isBackup) {
      Write-Host ("A fine prova: s25.ps1 -Mode backup -Step verify -Manifest {0}   (impronte del telefono, poi area.ps1 -Mode backup -Stage verify)" -f $Manifest)
    } else {
      Write-Host ("A fine prova: powershell -File tools\chaos\area.ps1 -Mode verify -Manifest {0}" -f $Manifest)
    }
  }
  Write-Host ""
  Write-Host "Il calendario e' ripetibile: stesso -Seed, stessa prova." -ForegroundColor Green
  exit 0
}

# --- La prova vera ---------------------------------------------------------------------------------------------------------------
Initialize-Adb
Assert-Debug-App
if ($isBackup -and (Test-Path -LiteralPath $Manifest)) {
  $count = @(Get-Content -LiteralPath $Manifest | Where-Object { $_ -like "phone=*" }).Count
  Write-Log "Manifesto: $count file del telefono. Il primo backup deve essere gia' partito nell'app."
}

Write-Log ("INIZIO prova: telefono {0}, app {1}, modo {2}, seme {3}, {4} interruzioni ({5} morti, {6} Wi-Fi), durata prevista circa {7} minuti" -f $Serial, $Package, $Mode, $Seed, $plan.Count, $killCount, $wifiCount, [math]::Ceiling($totalSeconds / 60.0))
$exitCode = 0
try {
  foreach ($event in $plan) {
    Start-Sleep -Seconds $event.Gap
    if ($event.Action -eq "kill") {
      $how = Stop-AppProcess
      Write-Log ("#{0}/{1}  morte del processo: {2}" -f $event.N, $plan.Count, $how)
      if (-not $NoRelaunch) {
        Start-Sleep -Seconds $RelaunchDelaySeconds
        Start-App
        Write-Log ("#{0}/{1}  app rilanciata" -f $event.N, $plan.Count)
      }
    } else {
      [void](Invoke-Adb @("shell", "svc", "wifi", "disable"))
      Write-Log ("#{0}/{1}  Wi-Fi spento per {2} s" -f $event.N, $plan.Count, $event.Duration)
      Start-Sleep -Seconds $event.Duration
      [void](Invoke-Adb @("shell", "svc", "wifi", "enable"))
      Write-Log ("#{0}/{1}  Wi-Fi acceso" -f $event.N, $plan.Count)
    }
  }
  Write-Log "Fine delle interruzioni: l'app riparte e si aspetta che la ripresa finisca ($SettleSeconds s)."
  Start-App
  Start-Sleep -Seconds $SettleSeconds
} catch {
  Write-Log ("INTERROTTA: " + $_.Exception.Message)
  $exitCode = 1
} finally {
  # Qualunque cosa sia successa, il telefono non resta senza Wi-Fi.
  [void](Invoke-Adb @("shell", "svc", "wifi", "enable"))
  Write-Log "Wi-Fi riacceso (garanzia di uscita)."
  Save-Log
}

if ($exitCode -eq 0 -and $Verify) {
  if ($isBackup) {
    Write-Log "Verifica del backup: impronte del telefono e invarianti sull'area di prova..."
    $exitCode = Invoke-VerifyBackup
  } else {
    $verify = Join-Path $PSScriptRoot "area.ps1"
    Write-Log "Verifica degli invarianti sull'area di prova..."
    & $verify -Mode verify -Manifest $Manifest
    $exitCode = $LASTEXITCODE
  }
}
exit $exitCode
