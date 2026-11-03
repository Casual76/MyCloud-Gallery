# Funzioni comuni dei livelli emulatore e s25 di tools\security (Fase 10 P11, seconda parte): guardie sui dispositivi, adb (vero o finto),
# emulatore acceso e spento solo se l'abbiamo acceso noi, UI Automator (lettura dello schermo, tocchi, testo), resoconto di soli numeri.
# Mai password: Invoke-SecType accetta solo testo semplice (nomi di share, codici di approvazione e di recupero, parole di prova).

# --- I dispositivi: scritti qui, mai da un parametro ---------------------------------------------------------------------------------
$SecEmulatorSerial = "emulator-5580"
$SecEmulatorPort = 5580
$SecEmulatorAvd = "Pampa_Tablet"
$SecPhoneSerial = "RFCY61FZDHW"
# Di un'altra sessione: mai, nemmeno con l'adb finto.
$SecForbiddenSerials = @("emulator-5554")
$SecForbiddenAvds = @("Medium_Phone")
$SecDebugPackage = "dev.pampa.mycloudgallery.debug"
$SecReleasePackage = "dev.pampa.mycloudgallery"
# Il PIN di prova dell'emulatore (mai sull'S25): lo mette e lo toglie lo script, serve al foglio della chiave di famiglia (M3).
$SecEmulatorTestPin = "4826"

$script:SecAdb = $null
$script:SecStartedEmulator = $false
$script:SecUiCounter = 0

function Get-SecSdkDir([string]$Root) {
  $props = Join-Path $Root "local.properties"
  if (Test-Path -LiteralPath $props) {
    foreach ($line in Get-Content -LiteralPath $props) {
      if ($line -match '^\s*sdk\.dir\s*=\s*(.+)$') { return ($Matches[1].Trim() -replace '\\\\', '\' -replace '\\:', ':') }
    }
  }
  if ($env:ANDROID_HOME) { return $env:ANDROID_HOME }
  return $null
}

# Gli errori delle guardie sul dispositivo per un livello: vuoto = si puo' partire.
function Get-SecDeviceErrors([string]$Livello, [string]$Serial, [string]$Package) {
  $errors = @()
  $fake = [bool]$env:MCG_ADB
  if (-not $Serial) { $errors += "Manca il numero di serie del dispositivo." }
  foreach ($bad in $SecForbiddenSerials) { if ($Serial -and $Serial.Trim().ToLowerInvariant() -eq $bad) { $errors += "$bad e' di un'altra sessione: mai." } }
  if ($Serial -and $Serial -notmatch '^[A-Za-z0-9-]{3,40}$') { $errors += "Numero di serie non valido." }
  if (-not $fake) {
    if ($Livello -eq "emulatore" -and $Serial -ne $SecEmulatorSerial) { $errors += "Il livello emulatore tocca solo $SecEmulatorSerial ($SecEmulatorAvd)." }
    if ($Livello -eq "s25" -and $Serial -ne $SecPhoneSerial) { $errors += "Il livello s25 tocca solo $SecPhoneSerial." }
  }
  if ($Package -notin @($SecDebugPackage, $SecReleasePackage)) { $errors += "Solo la nostra app: $SecDebugPackage o la release candidata $SecReleasePackage." }
  return $errors
}

function Initialize-SecAdb([string]$Root, [string]$Serial, [switch]$NoState) {
  if ($env:MCG_ADB) {
    if (-not (Test-Path -LiteralPath $env:MCG_ADB)) { throw "MCG_ADB non punta a un file." }
    $script:SecAdb = $env:MCG_ADB
  } else {
    $sdk = Get-SecSdkDir $Root
    if ($sdk -and (Test-Path -LiteralPath (Join-Path $sdk "platform-tools\adb.exe"))) { $script:SecAdb = Join-Path $sdk "platform-tools\adb.exe" }
    if (-not $script:SecAdb) { $c = Get-Command adb -ErrorAction SilentlyContinue; if ($c) { $script:SecAdb = $c.Source } }
    if (-not $script:SecAdb) { throw "adb non trovato (sdk.dir in local.properties, o adb nel PATH)." }
  }
  if (-not $NoState) {
    $state = Invoke-SecAdb $Serial @("get-state")
    if ($state.Text -ne "device") { throw "Il dispositivo $Serial non e' pronto (adb dice: '$($state.Text)')." }
  }
}

# Un comando adb su UN dispositivo (-s), mai su "tutti". L'uscita torna in Lines (il chiamante decide cosa conservare: mai nel resoconto).
function Invoke-SecAdb([string]$Serial, [string[]]$Arguments) {
  $previous = $ErrorActionPreference
  $ErrorActionPreference = "Continue"
  try {
    $lines = @(& $script:SecAdb -s $Serial @Arguments 2>&1 | ForEach-Object { $_.ToString() })
    $code = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $previous
  }
  return [pscustomobject]@{ Code = $code; Text = (($lines -join "`n").Trim()); Lines = $lines }
}

function Invoke-SecShell([string]$Serial, [string[]]$Command) { return Invoke-SecAdb $Serial (@("shell") + $Command) }

# --- L'emulatore: acceso solo se serve, spento solo se l'abbiamo acceso noi --------------------------------------------------------------
function Get-SecEmulatorExe([string]$Root) {
  if ($env:MCG_EMULATOR) { return $env:MCG_EMULATOR }
  $sdk = Get-SecSdkDir $Root
  if ($sdk) { $exe = Join-Path $sdk "emulator\emulator.exe"; if (Test-Path -LiteralPath $exe) { return $exe } }
  throw "emulator.exe non trovato (sdk.dir in local.properties)."
}

function Start-SecEmulator([string]$Root, [string]$Tcpdump, [int]$BootSeconds = 240) {
  $already = Invoke-SecAdb $SecEmulatorSerial @("get-state")
  if ($already.Text -eq "device") { Write-Host "Emulatore $SecEmulatorSerial gia' acceso: lo uso e non lo spengo."; return }
  $emuArgs = @("-avd", $SecEmulatorAvd, "-port", "$SecEmulatorPort", "-no-snapshot-save", "-no-audio", "-no-boot-anim")
  if ($Tcpdump) { $emuArgs += @("-tcpdump", "`"$Tcpdump`"") }
  $exe = Get-SecEmulatorExe $Root
  Start-Process -FilePath $exe -ArgumentList $emuArgs -WindowStyle Minimized | Out-Null
  $script:SecStartedEmulator = $true
  $deadline = (Get-Date).AddSeconds($BootSeconds)
  while ((Get-Date) -lt $deadline) {
    $boot = Invoke-SecShell $SecEmulatorSerial @("getprop", "sys.boot_completed")
    if ($boot.Text -eq "1") { Write-Host "Emulatore $SecEmulatorAvd acceso ($SecEmulatorSerial)."; return }
    Start-Sleep -Seconds $(if ($env:MCG_ADB) { 1 } else { 5 })
  }
  throw "L'emulatore non ha finito l'avvio in $BootSeconds s."
}

function Stop-SecEmulator {
  if (-not $script:SecStartedEmulator) { return }
  [void](Invoke-SecAdb $SecEmulatorSerial @("emu", "kill"))
  $script:SecStartedEmulator = $false
  Write-Host "Emulatore $SecEmulatorSerial spento (l'avevo acceso io)."
}

# Il PIN di prova sull'emulatore (mai sull'S25): lo mette e lo toglie lo script.
function Set-SecTestPin([string]$Serial) {
  if ($Serial -ne $SecEmulatorSerial -and -not $env:MCG_ADB) { throw "Il PIN di prova si mette solo sull'emulatore." }
  [void](Invoke-SecShell $Serial @("locksettings", "set-pin", $SecEmulatorTestPin))
}

function Clear-SecTestPin([string]$Serial) {
  if ($Serial -ne $SecEmulatorSerial -and -not $env:MCG_ADB) { return }
  [void](Invoke-SecShell $Serial @("locksettings", "clear", "--old", $SecEmulatorTestPin))
}

# --- UI Automator ----------------------------------------------------------------------------------------------------------------------
# Lo schermo come elenco di nodi: testo, descrizione, id, se e' un campo password, se e' cliccabile, centro e rettangolo.
function Get-SecUi([string]$Serial) {
  $remote = "/sdcard/mcg-sec-ui.xml"
  # Lo schermo dell'S25 si spegne in fretta (campo, 03/11): un tasto di risveglio prima di ogni lettura (niente impostazioni cambiate).
  if (-not $env:MCG_ADB) { [void](Invoke-SecShell $Serial @("input", "keyevent", "KEYCODE_WAKEUP")) }
  [void](Invoke-SecShell $Serial @("uiautomator", "dump", $remote))
  $cat = Invoke-SecShell $Serial @("cat", $remote)
  $xmlText = ($cat.Lines -join "`n")
  $start = $xmlText.IndexOf("<?xml")
  if ($start -lt 0) { $start = $xmlText.IndexOf("<hierarchy") }
  if ($start -lt 0) { return @() }
  [xml]$doc = $xmlText.Substring($start)
  $nodes = @()
  foreach ($n in $doc.SelectNodes("//node")) {
    $b = [regex]::Match([string]$n.bounds, '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')
    if (-not $b.Success) { continue }
    $x1 = [int]$b.Groups[1].Value; $y1 = [int]$b.Groups[2].Value; $x2 = [int]$b.Groups[3].Value; $y2 = [int]$b.Groups[4].Value
    # Una spunta di Compose e' sul contenitore (checkable), non sul testo: la si prende dall'antenato piu' vicino.
    $isChecked = $isChecked
    $anc = $n.ParentNode
    while ($anc -and $anc.Name -eq "node") { if ([string]$anc.checkable -eq "true") { $isChecked = ([string]$anc.checked -eq "true"); break }; $anc = $anc.ParentNode }
    $nodes += [pscustomobject]@{
      Text = [string]$n.text; Desc = [string]$n.'content-desc'; Id = [string]$n.'resource-id'; Package = [string]$n.package
      Password = ([string]$n.password -eq "true"); Clickable = ([string]$n.clickable -eq "true"); Checked = ([string]$n.checked -eq "true")
      X = [int](($x1 + $x2) / 2); Y = [int](($y1 + $y2) / 2); Top = $y1; Bottom = $y2
    }
  }
  return $nodes
}

function Get-SecUiLabels($Ui) { return @($Ui | ForEach-Object { $_.Text; $_.Desc } | Where-Object { $_ }) }

# Vero se lo schermo ha un nodo con quel testo (o descrizione), uguale; con -Contains basta che lo contenga.
function Test-SecUiHas($Ui, [string]$Label, [switch]$Contains) {
  foreach ($n in $Ui) {
    foreach ($t in @($n.Text, $n.Desc)) {
      if (-not $t) { continue }
      if ($Contains) { if ($t.IndexOf($Label, [StringComparison]::OrdinalIgnoreCase) -ge 0) { return $true } }
      elseif ($t.Trim() -eq $Label) { return $true }
    }
  }
  return $false
}

function Find-SecNode($Ui, [string]$Label, [switch]$Contains, [int]$Below = -1, [int]$Above = 100000) {
  foreach ($n in $Ui) {
    if ($n.Y -le $Below -or $n.Y -ge $Above) { continue }
    foreach ($t in @($n.Text, $n.Desc)) {
      if (-not $t) { continue }
      $hit = if ($Contains) { $t.IndexOf($Label, [StringComparison]::OrdinalIgnoreCase) -ge 0 } else { $t.Trim() -eq $Label }
      if ($hit) { return $n }
    }
  }
  return $null
}

function Invoke-SecTap([string]$Serial, $Node) {
  if (-not $Node) { throw "Niente da toccare." }
  [void](Invoke-SecShell $Serial @("input", "tap", "$($Node.X)", "$($Node.Y)"))
}

# Tocca il nodo con quel testo; se non c'e', scorre in giu' fino a [Scroll] volte. Vero se l'ha toccato.
function Invoke-SecTapLabel([string]$Serial, [string]$Label, [switch]$Contains, [int]$Scroll = 4) {
  for ($i = 0; $i -le $Scroll; $i++) {
    $ui = Get-SecUi $Serial
    $node = Find-SecNode $ui $Label -Contains:$Contains
    if ($node) { Invoke-SecTap $Serial $node; return $true }
    [void](Invoke-SecShell $Serial @("input", "swipe", "500", "1600", "500", "700", "300"))
  }
  return $false
}

# Solo testo semplice: lettere, cifre, punto, trattino, sottolineatura (nomi di share, codici). Mai una password: non c'e' un modo di passarla.
function Invoke-SecType([string]$Serial, [string]$Text) {
  if ($Text -notmatch '^[A-Za-z0-9._-]{1,64}$') { throw "Testo non ammesso per input text (solo lettere, cifre, . _ -)." }
  [void](Invoke-SecShell $Serial @("input", "text", $Text))
}

# La tastiera sullo schermo e' su? (sull'S25 copre il fondo della pagina: il tasto dietro non si tocca)
function Test-SecImeShown([string]$Serial) { return ((Invoke-SecShell $Serial @("dumpsys", "input_method")).Text -match 'mInputShown=true') }

# Come Invoke-SecType, ma a pezzi di 4 con una pausa: sull'S25 `input text` lungo perde qualche carattere (visto sul campo il 03/11).
function Invoke-SecTypeChunks([string]$Serial, [string]$Text, [int]$Size = 4, [int]$PauseMs = 600) {
  for ($i = 0; $i -lt $Text.Length; $i += $Size) {
    Invoke-SecType $Serial $Text.Substring($i, [math]::Min($Size, $Text.Length - $i))
    Start-Sleep -Milliseconds $PauseMs
  }
}

# Svuota il campo e scrive lettere e cifre come tasti, tutti in UN comando adb (visto sul campo il 03/11: sull'S25 `input text` perde e aggiunge
# caratteri, per via del servizio di accessibilita' che detta testo nei campi; un solo comando lascia loro il tempo minimo). Solo A-Z, 0-9, trattino.
function Invoke-SecTypeKeys([string]$Serial, [string]$Text) {
  if ($Text -notmatch '^[A-Za-z0-9-]{1,64}$') { throw "Testo non ammesso (solo lettere, cifre, trattino)." }
  [void](Invoke-SecShell $Serial (@("input", "keyevent", "KEYCODE_MOVE_END") + @(1..30 | ForEach-Object { "KEYCODE_DEL" })))
  Start-Sleep -Milliseconds 400
  # Un tasto per comando adb: il campo del codice (Compose, aggiunge i trattini a ogni carattere) perde i tasti in raffica, anche non doppi
  # (visto sul campo il 03/11: "0B" arrivato "0", "JJ" arrivato "J"). Il chiamante rilegge il campo e riprova.
  foreach ($c in $Text.ToUpperInvariant().ToCharArray()) {
    if ($c -eq '-') { continue }
    [void](Invoke-SecShell $Serial @("input", "keyevent", "KEYCODE_$c"))
    Start-Sleep -Milliseconds 80
  }
}

function Invoke-SecKey([string]$Serial, [string]$Key) {
  if ($Key -notmatch '^KEYCODE_[A-Z_0-9]+$') { throw "Tasto non valido." }
  [void](Invoke-SecShell $Serial @("input", "keyevent", $Key))
}

# Una schermata salvata per chi corregge il copione (sotto build\, mai nel repository): solo la struttura, senza i testi lunghi.
function Save-SecUi($Ui, [string]$Dir, [string]$Name) {
  New-Item -ItemType Directory -Force -Path $Dir | Out-Null
  $script:SecUiCounter++
  $file = Join-Path $Dir ("{0:D3}-{1}.txt" -f $script:SecUiCounter, $Name)
  $Ui | ForEach-Object { "{0}`t{1}`t{2}`t{3},{4}" -f $_.Text, $_.Desc, $_.Id, $_.X, $_.Y } | Set-Content -LiteralPath $file -Encoding UTF8
  return $file
}

# --- Resoconto: solo righe "MCG-SEC <voce> chiave=numero" -----------------------------------------------------------------------------
function New-SecLine([string]$Voce, [System.Collections.IDictionary]$Values) {
  $parts = @("MCG-SEC", $Voce)
  foreach ($k in $Values.Keys) {
    $v = $Values[$k]
    if ($v -is [bool]) { $v = [int]$v }
    if ("$v" -notmatch '^-?[0-9]+(\.[0-9]+)?$' -and "$v" -notmatch '^[a-z_]+$') { throw "Il resoconto ha solo numeri ed etichette fisse ($k)." }
    $parts += "$k=$v"
  }
  return ($parts -join " ")
}

function Add-SecReport([string]$Path, [string[]]$Lines) {
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
  Add-Content -LiteralPath $Path -Value $Lines -Encoding UTF8
}
