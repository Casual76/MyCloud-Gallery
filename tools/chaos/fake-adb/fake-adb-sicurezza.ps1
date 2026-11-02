# (Il codice d'uscita di un comando gestito va in $script:FakeSecCode; null = non e' un comando di queste aggiunte.)
# Aggiunte all'adb FINTO per le prove di sicurezza della Fase 10 P11, seconda parte (tools\security\sicurezza-finto.ps1). Si carica da
# fake-adb.ps1 PRIMA dei comandi di sempre e risponde solo ai comandi nuovi (gli altri passano oltre, invariati):
#   - lo schermo finto per UI Automator: <FAKE_PHONE>\ui\stato.txt (la schermata corrente) e ui\schermate\<nome>.xml; un nodo con
#     fake-tap="<schermata>" porta li' quando lo si tocca; la radice puo' avere fake-testo (dopo `input text`), fake-indietro (Indietro),
#     fake-invio (Invio), fake-auto e fake-dopo (dopo N letture passa da sola: l'utente che scrive la password o appoggia il dito);
#   - l'emulatore acceso o spento (FAKE_EMULATOR_SPENTO=1: spento finche' non c'e' <FAKE_PHONE>\emulatore-acceso), `emu kill`;
#   - logcat (<FAKE_PHONE>\logcat.txt), dumpsys (<FAKE_PHONE>\dumpsys\*.txt), bmgr, settings, locksettings, getprop, ip, pm grant,
#     install e uninstall (<FAKE_PHONE>\pacchetti\<pacchetto>, se FAKE_PACCHETTI=1), am start, il broadcast PERIODIC_NOW;
#   - il servizio di accessibilita' di prova: acceso con settings, scrive in logcat le righe di FieldProbeA11y (FAKE_A11Y_LEGGE=1: legge
#     anche la nostra app, il caso rosso).

function Get-FakeUiDir { $d = Join-Path $root "ui"; New-Item -ItemType Directory -Force -Path (Join-Path $d "schermate") | Out-Null; return $d }

function Get-FakeScreen {
  $ui = Get-FakeUiDir
  $state = Join-Path $ui "stato.txt"
  $name = if (Test-Path -LiteralPath $state) { (Get-Content -LiteralPath $state -Raw).Trim() } else { "vuota" }
  $file = Join-Path $ui "schermate\$name.xml"
  if (-not (Test-Path -LiteralPath $file)) { return $null }
  return [pscustomobject]@{ Name = $name; File = $file; Xml = [xml](Get-Content -LiteralPath $file -Raw -Encoding UTF8) }
}

function Set-FakeScreen([string]$name) {
  $ui = Get-FakeUiDir
  Set-Content -LiteralPath (Join-Path $ui "stato.txt") -Value $name
  Remove-Item -LiteralPath (Join-Path $ui "letture.txt") -ErrorAction SilentlyContinue
  Add-Content -LiteralPath $log -Value "schermata: $name"
}

function Get-FakeRootAttr($screen, [string]$attr) {
  $node = $screen.Xml.hierarchy
  if ($node -and $node.HasAttribute($attr)) { return $node.GetAttribute($attr) }
  return
}

function Write-FakeLogcat([string[]]$lines) { Add-Content -LiteralPath (Join-Path $root "logcat.txt") -Value $lines }

function Invoke-FakeSecurityShell([string]$line) {
  if ($line -match '^uiautomator dump (\S+)$') {
    $screen = Get-FakeScreen
    $target = Map-Path $Matches[1]
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
    if (-not $screen) { Set-Content -LiteralPath $target -Value '<?xml version="1.0" encoding="UTF-8"?><hierarchy rotation="0"></hierarchy>' -Encoding UTF8; $script:FakeSecCode = 0; return }
    Copy-Item -LiteralPath $screen.File -Destination $target -Force
    # Dopo N letture la schermata passa da sola (l'utente che fa la sua parte).
    $auto = Get-FakeRootAttr $screen "fake-auto"
    if ($auto) {
      $counter = Join-Path (Get-FakeUiDir) "letture.txt"
      $n = if (Test-Path -LiteralPath $counter) { [int](Get-Content -LiteralPath $counter -Raw) + 1 } else { 1 }
      Set-Content -LiteralPath $counter -Value $n
      $after = Get-FakeRootAttr $screen "fake-dopo"
      if ($n -ge [int]$(if ($after) { $after } else { 2 })) { Set-FakeScreen $auto }
    }
    Write-Output "UI hierchary dumped to: $($Matches[1])"
    $script:FakeSecCode = 0; return
  }
  if ($line -match '^cat (/sdcard/\S+)$') {
    $file = Map-Path $Matches[1]
    if (-not (Test-Path -LiteralPath $file)) { Write-Output "cat: $($Matches[1]): No such file or directory"; $script:FakeSecCode = 1; return }
    Get-Content -LiteralPath $file -Encoding UTF8 | ForEach-Object { Write-Output $_ }
    $script:FakeSecCode = 0; return
  }
  if ($line -match '^input tap (\d+) (\d+)$') {
    $x = [int]$Matches[1]; $y = [int]$Matches[2]
    Add-Content -LiteralPath $log -Value "tocco: $x,$y"
    $screen = Get-FakeScreen
    if (-not $screen) { $script:FakeSecCode = 0; return }
    $hit = $null
    foreach ($n in $screen.Xml.SelectNodes("//node[@fake-tap]")) {
      $b = [regex]::Match($n.GetAttribute("bounds"), '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
      if ($b.Success -and $x -ge [int]$b.Groups[1].Value -and $x -le [int]$b.Groups[3].Value -and $y -ge [int]$b.Groups[2].Value -and $y -le [int]$b.Groups[4].Value) { $hit = $n }
    }
    if ($hit) {
      $target = $hit.GetAttribute("fake-tap")
      if ($target -eq "@verifica") {
        $typed = Join-Path (Get-FakeUiDir) "testo.log"
        $last = if (Test-Path -LiteralPath $typed) { (Get-Content -LiteralPath $typed | Select-Object -Last 1) } else { "" }
        if ($last -eq (Get-FakeRootAttr $screen "fake-numero")) { Set-FakeScreen (Get-FakeRootAttr $screen "fake-giusto") }
      } else {
        Set-FakeScreen $target
      }
    }
    $script:FakeSecCode = 0; return
  }
  if ($line -match '^input text (\S+)$') {
    $typed = $Matches[1]
    Add-Content -LiteralPath (Join-Path (Get-FakeUiDir) "testo.log") -Value $typed
    # FAKE_LOGCAT_ECO=1: un'app che scrive nel registro quello che si cerca (il caso rosso di S6).
    if ($env:FAKE_LOGCAT_ECO -eq "1") { Write-FakeLogcat "11-02 10:00:02.000  4200  4200 D MyCloud/Search: query=$typed" }
    $screen = Get-FakeScreen
    if ($screen) { $next = Get-FakeRootAttr $screen "fake-testo"; if ($next) { Set-FakeScreen $next } }
    $script:FakeSecCode = 0; return
  }
  if ($line -match '^input keyevent (KEYCODE_[A-Z_0-9]+)$') {
    Add-Content -LiteralPath $log -Value "tasto: $($Matches[1])"
    $screen = Get-FakeScreen
    if ($screen) {
      $next = if ($Matches[1] -eq "KEYCODE_BACK") { Get-FakeRootAttr $screen "fake-indietro" } elseif ($Matches[1] -eq "KEYCODE_ENTER") { Get-FakeRootAttr $screen "fake-invio" } else { $null }
      if ($next) { Set-FakeScreen $next }
    }
    $script:FakeSecCode = 0; return
  }
  if ($line -match '^input swipe ') { $script:FakeSecCode = 0; return }
  if ($line -match '^getprop sys\.boot_completed$') {
    if ($env:FAKE_EMULATOR_SPENTO -eq "1" -and -not (Test-Path -LiteralPath (Join-Path $root "emulatore-acceso"))) { $script:FakeSecCode = 1; return }
    Write-Output "1"; $script:FakeSecCode = 0; return
  }
  if ($line -match '^getprop ro\.build\.version\.sdk$') { Write-Output $(if ($env:FAKE_SDK) { $env:FAKE_SDK } else { "35" }); $script:FakeSecCode = 0; return }
  if ($line -match '^ip -6 addr show wlan0$') {
    $f = Join-Path $root "ip6.txt"
    if (Test-Path -LiteralPath $f) { Get-Content -LiteralPath $f | ForEach-Object { Write-Output $_ } } else { Write-Output "    inet6 fec0::5054:ff:fe12:3456/64 scope site dynamic" }
    $script:FakeSecCode = 0; return
  }
  if ($line -match '^pm grant (\S+) (\S+)$') { Add-Content -LiteralPath $log -Value "permesso: $($Matches[2])"; $script:FakeSecCode = 0; return }
  if ($env:FAKE_PACCHETTI -eq "1" -and $line -match '^pm path (\S+)$') {
    if (Test-Path -LiteralPath (Join-Path $root "pacchetti\$($Matches[1])")) { Write-Output "package:/data/app/fake/base.apk"; $script:FakeSecCode = 0; return }
    $script:FakeSecCode = 1; return
  }
  if ($line -match '^am broadcast -a dev\.pampa\.mycloudgallery\.debug\.PERIODIC_NOW -p (\S+) --es jobs (\S+)$') {
    Add-Content -LiteralPath $log -Value "lavori: $($Matches[2])"
    Write-Output "Broadcast completed: result=0"; $script:FakeSecCode = 0; return
  }
  if ($line -match '^am start ') {
    Add-Content -LiteralPath $log -Value "avvio: $line"
    if (Test-Path -LiteralPath (Join-Path (Get-FakeUiDir) "schermate\libreria.xml")) { Set-FakeScreen "libreria" }
    $script:FakeSecCode = 0; return
  }
  if ($line -match '^logcat -c$') { Set-Content -LiteralPath (Join-Path $root "logcat.txt") -Value @(); $script:FakeSecCode = 0; return }
  if ($line -match '^logcat -d -b all') {
    $f = Join-Path $root "logcat.txt"
    if (Test-Path -LiteralPath $f) { Get-Content -LiteralPath $f | ForEach-Object { Write-Output $_ } }
    $script:FakeSecCode = 0; return
  }
  if ($line -match '^dumpsys (package|meminfo|netstats|notification)\b\s*(\S*)') {
    $what = $Matches[1]
    $name = if ($what -eq "package" -or $what -eq "meminfo") { "$what-$($Matches[2])" } else { $what }
    $f = Join-Path $root "dumpsys\$name.txt"
    if (Test-Path -LiteralPath $f) { Get-Content -LiteralPath $f | ForEach-Object { Write-Output $_ } }
    $script:FakeSecCode = 0; return
  }
  if ($line -match '^bmgr (.+)$') {
    $sub = $Matches[1]
    Add-Content -LiteralPath $log -Value "bmgr: $sub"
    if ($sub -eq "list transports") { Write-Output "  * com.google.android.gms/.backup.BackupTransportService"; Write-Output "    com.android.localtransport/.LocalTransport"; $script:FakeSecCode = 0; return }
    if ($sub -match '^backupnow (\S+)$') {
      $result = if ($env:FAKE_BMGR_OK -eq "1") { "Success" } else { "Backup is not allowed" }
      Write-Output "Running incremental backup for 1 requested packages."; Write-Output "Package $($Matches[1]) with result: $result"; Write-Output "Backup finished with result: Success"
      $script:FakeSecCode = 0; return
    }
    if ($sub -match '^transport (\S+)$') { Write-Output "Selected transport $($Matches[1])"; $script:FakeSecCode = 0; return }
    $script:FakeSecCode = 0; return
  }
  if ($line -match '^settings (get|put|delete) secure (\S+)\s*(.*)$') {
    $dir = Join-Path $root "settings"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $verb = $Matches[1]; $key = $Matches[2]; $value = $Matches[3]
    $f = Join-Path $dir "$key.txt"
    switch ($verb) {
      "get" { if (Test-Path -LiteralPath $f) { Write-Output (Get-Content -LiteralPath $f -Raw).Trim() } else { Write-Output "null" } }
      "put" {
        Set-Content -LiteralPath $f -Value $value
        if ($key -eq "enabled_accessibility_services" -and $value -match 'FieldProbeA11yService') {
          # Il servizio non "strumento": la finestra della nostra app non si legge (accessibilityDataSensitive), le Impostazioni si'.
          $app = if ($env:FAKE_A11Y_LEGGE -eq "1") { "finestra dev.pampa.mycloudgallery.debug: nodi=30 conTesto=9 toccabili=6" } else { "finestra dev.pampa.mycloudgallery.debug: nessuna radice leggibile" }
          Write-FakeLogcat @("11-02 10:00:00.000  4321  4321 I MyCloud/FieldProbeA11y: collegato: strumento di accessibilita'=false", "11-02 10:00:01.000  4321  4321 I MyCloud/FieldProbeA11y: $app", "11-02 10:00:05.000  4321  4321 I MyCloud/FieldProbeA11y: finestra com.android.settings: nodi=40 conTesto=12 toccabili=10")
          # Come sull'S25 (prove-campo-esiti, F182): accendere un servizio svuota le tre chiavi delle scorciatoie.
          foreach ($k in @("accessibility_button_targets", "accessibility_direct_access_target_service", "accessibility_gesture_targets")) {
            Set-Content -LiteralPath (Join-Path $dir "$k.txt") -Value ""
          }
        }
      }
      "delete" { Remove-Item -LiteralPath $f -ErrorAction SilentlyContinue }
    }
    $script:FakeSecCode = 0; return
  }
  if ($line -match '^locksettings (set-pin|clear)\b') { Add-Content -LiteralPath $log -Value "pin: $($Matches[1])"; $script:FakeSecCode = 0; return }
  return
}

function Invoke-FakeSecurityCommand([string]$cmd, [string[]]$rest) {
  if ($cmd -eq "get-state" -and $env:FAKE_EMULATOR_SPENTO -eq "1" -and -not (Test-Path -LiteralPath (Join-Path $root "emulatore-acceso"))) {
    Write-Output "error: device not found"; $script:FakeSecCode = 1; return
  }
  if ($cmd -eq "emu" -and $rest[0] -eq "kill") {
    Remove-Item -LiteralPath (Join-Path $root "emulatore-acceso") -ErrorAction SilentlyContinue
    Add-Content -LiteralPath $log -Value "emulatore spento"
    Write-Output "OK: killing emulator, bye bye"; $script:FakeSecCode = 0; return
  }
  if ($cmd -eq "install") {
    $apk = $rest[-1]
    $pkg = if ((Split-Path -Leaf $apk) -match 'release|rc') { "dev.pampa.mycloudgallery" } else { "dev.pampa.mycloudgallery.debug" }
    New-Item -ItemType Directory -Force -Path (Join-Path $root "pacchetti") | Out-Null
    Set-Content -LiteralPath (Join-Path $root "pacchetti\$pkg") -Value $apk
    Add-Content -LiteralPath $log -Value "installato: $pkg"
    Write-Output "Success"; $script:FakeSecCode = 0; return
  }
  if ($cmd -eq "uninstall") {
    Remove-Item -LiteralPath (Join-Path $root "pacchetti\$($rest[0])") -ErrorAction SilentlyContinue
    Add-Content -LiteralPath $log -Value "disinstallato: $($rest[0])"
    Write-Output "Success"; $script:FakeSecCode = 0; return
  }
  if ($cmd -eq "shell") { Invoke-FakeSecurityShell ($rest -join " "); return }
  return
}
