# Le VOCI della revisione finale sull'emulatore e sull'S25 (Fase 10 P11, seconda parte; docs/fase10-progetto.md par. 2.11). Ogni funzione
# fa la sua prova e restituisce { Voce; Esito (verde | rosso | dichiarata | bloccata | saltata); Valori (solo numeri) }. Il contesto e'
# $script:SecCtx (Root, Livello, Serial, Package, ReleasePackage, Emulator, StateDir, OutDir, LogDir, Test2Serial, SettleSeconds, ...).
# I passi sul NAS li fa il PC (Invoke-SecPcStep: FamilyAreaNasIntegrationTest, guardia C470); l'app si guida con i copioni.

$SecPeriodicAction = "dev.pampa.mycloudgallery.debug.PERIODIC_NOW"
$SecWidgetAction = "dev.pampa.mycloudgallery.action.OPEN_WIDGET"

function New-SecResult([string]$Voce, [string]$Esito, [System.Collections.IDictionary]$Valori) {
  return [pscustomobject]@{ Voce = $Voce; Esito = $Esito; Valori = $Valori }
}

# Un passo dal PC sull'area della famiglia di prova (o sul giro di mcg-it per S9): Gradle con -PnasIntegration dietro il semaforo, oppure
# lo script finto di MCG_SECURITY_PC_FINTO. Restituisce le righe MCG-SEC nuove del passo e il codice d'uscita.
function Invoke-SecPcStep([string]$Step, [hashtable]$Extra = @{}) {
  $ctx = $script:SecCtx
  $log = Join-Path $ctx.StateDir "famiglia-nas.txt"
  $before = if (Test-Path -LiteralPath $log) { @(Get-Content -LiteralPath $log).Count } else { 0 }
  $env:MCG_FAMIGLIA_PASSO = $Step
  $env:MCG_FAMIGLIA_DIR = $ctx.StateDir
  foreach ($k in $Extra.Keys) { Set-Item -Path "Env:$k" -Value $Extra[$k] }
  try {
    if ($env:MCG_SECURITY_PC_FINTO) {
      & powershell -NoProfile -ExecutionPolicy Bypass -File $env:MCG_SECURITY_PC_FINTO -Passo $Step -Dir $ctx.StateDir | Out-Host
      $code = $LASTEXITCODE
    } else {
      $code = Invoke-SecurityGradle -Root $ctx.Root -Slot "C:\VibeCoded Projects\gradle-slot.ps1" -Pairs @(":core-nas|*FamilyAreaNasIntegrationTest") -Extra @("-PnasIntegration")
    }
  } finally {
    Remove-Item Env:MCG_FAMIGLIA_PASSO -ErrorAction SilentlyContinue
    foreach ($k in $Extra.Keys) { Remove-Item "Env:$k" -ErrorAction SilentlyContinue }
  }
  $lines = if (Test-Path -LiteralPath $log) { @(Get-Content -LiteralPath $log | Select-Object -Skip $before) } else { @() }
  $stopped = (($lines -join " ") -match 'fermata=AUTH_FAILED')
  # Al primo AUTH_FAILED ci si ferma: nessun passo dopo tocca il NAS (nessun nuovo tentativo).
  if ($stopped) { $script:SecAuthStopped = $true }
  return [pscustomobject]@{ Code = $code; Lines = $lines; Stopped = $stopped }
}

function Get-SecPcValue($Step, [string]$Key) {
  foreach ($l in $Step.Lines) { if ($l -match "\b$([regex]::Escape($Key))=(-?[0-9]+)\b") { return [long]$Matches[1] } }
  return $null
}

# I lavori dell'app subito (solo debug: PERIODIC_NOW, protetto da DUMP); per la release si riavvia l'app (il manifest si legge all'avvio).
function Invoke-SecAppWork([string]$Serial, [string]$Package, [string]$Jobs = "all") {
  if ($Package -eq $SecDebugPackage) {
    [void](Invoke-SecShell $Serial @("am", "broadcast", "-a", $SecPeriodicAction, "-p", $Package, "--es", "jobs", $Jobs))
  } else {
    [void](Invoke-SecShell $Serial @("am", "force-stop", $Package))
    [void](Invoke-SecShell $Serial @("monkey", "-p", $Package, "-c", "android.intent.category.LAUNCHER", "1"))
  }
}

function Start-SecApp([string]$Serial, [string]$Package) { [void](Invoke-SecShell $Serial @("monkey", "-p", $Package, "-c", "android.intent.category.LAUNCHER", "1")) }

function Wait-SecSettle([int]$Seconds) { if ($env:MCG_ADB) { Start-Sleep -Milliseconds 200 } else { Start-Sleep -Seconds $Seconds } }

function Get-SecPids([string]$Serial, [string]$Package) {
  $p = Invoke-SecShell $Serial @("pidof", $Package)
  if ($p.Code -ne 0 -or -not $p.Text) { return @() }
  return @($p.Text -split '\s+' | Where-Object { $_ -match '^\d+$' })
}

function Get-SecLogcat([string]$Serial) { return (Invoke-SecShell $Serial @("logcat", "-d", "-b", "all", "-v", "threadtime")).Lines }

# I rifiuti contati in Diagnostica (S2, S3): indice, registro, album, AI, stati.
$SecRejectionCodes = @("INDEX_REJECTED", "TRUST_REJECTED_FILES", "TRUST_REJECTIONS", "TRUST_STATUS_REJECTED", "ALBUM_CONTRIB_REJECTED", "ALBUM_ENTRY_DENIED", "ALBUM_ITEM_UNATTESTED", "AI_SEGMENT_REJECTED")

# --- Preflight: il dispositivo, l'app, e se l'emulatore puo' essere "casa" (serve un ULA nel Wi-Fi: senza, l'onboarding si ferma a NO_ULA) ---
function Invoke-SecPreflight {
  $ctx = $script:SecCtx
  $installed = (Invoke-SecShell $ctx.Serial @("pm", "path", $ctx.Package)).Text -match "package:"
  $sdk = (Invoke-SecShell $ctx.Serial @("getprop", "ro.build.version.sdk")).Text
  $ula = @((Invoke-SecShell $ctx.Serial @("ip", "-6", "addr", "show", "wlan0")).Lines | Where-Object { $_ -match 'inet6\s+f[cd][0-9a-f]{2}:' }).Count
  $v = [ordered]@{ app_installata = [int]$installed; sdk = $(if ($sdk -match '^\d+$') { [int]$sdk } else { 0 }); ula_wifi = $ula; casa_possibile = [int]($ula -gt 0) }
  $ctx.HomePossible = ($ula -gt 0)
  return New-SecResult "preflight" $(if ($installed) { "verde" } else { "rosso" }) $v
}

# --- S1 (c): con test2, Cerca non trova i segni di test; con test (controllo) si' -----------------------------------------------------
function Invoke-SecS1c {
  $ctx = $script:SecCtx
  $seed = Invoke-SecPcStep "semina-s1"
  $markFile = Join-Path $ctx.StateDir "segno-s1.txt"
  if ($seed.Code -ne 0 -or -not (Test-Path -LiteralPath $markFile)) { return New-SecResult "S1c" "rosso" ([ordered]@{ semina = 0 }) }
  $mark = (Get-Content -LiteralPath $markFile -Raw).Trim()
  Invoke-SecAppWork $ctx.Serial $ctx.Package "sync"
  Wait-SecSettle $ctx.SettleSeconds
  $control = Invoke-SecSearch $ctx.Serial $mark
  $v = [ordered]@{ controllo_celle = $control.Cells; test2_eseguita = 0; test2_celle = -1; test2_menzioni = -1 }
  if ($ctx.Test2Serial) {
    Invoke-SecAppWork $ctx.Test2Serial $SecDebugPackage "sync"
    Wait-SecSettle $ctx.SettleSeconds
    $other = Invoke-SecSearch $ctx.Test2Serial $mark
    $v.test2_eseguita = 1; $v.test2_celle = $other.Cells; $v.test2_menzioni = $other.Mentions
  }
  $esito = if (-not $ctx.Test2Serial) { "dichiarata" } elseif ($v.test2_celle -eq 0 -and $v.test2_menzioni -eq 0 -and $control.Cells -gt 0) { "verde" } else { "rosso" }
  return New-SecResult "S1c" $esito $v
}

# --- S2: canaglia e revocato scrivono nell'area della famiglia; contatori di rifiuto in Diagnostica, niente in Libreria ---------------------
function Invoke-SecS2 {
  $ctx = $script:SecCtx
  $before = Read-SecDiagnostics $ctx.Serial $ctx.LogDir
  $seed = Invoke-SecPcStep "semina-s2"
  if ($seed.Code -ne 0) { return New-SecResult "S2" "rosso" ([ordered]@{ semina = 0 }) }
  Invoke-SecAppWork $ctx.Serial $ctx.Package "sync,scan"
  Wait-SecSettle $ctx.SettleSeconds
  $after = Read-SecDiagnostics $ctx.Serial $ctx.LogDir
  $found = Invoke-SecSearch $ctx.Serial "mcg-s2"
  $v = [ordered]@{
    teste_scritte = [long](Get-SecPcValue $seed "teste"); rifiuti_prima = (Get-SecDiagSum $before $SecRejectionCodes); rifiuti_dopo = (Get-SecDiagSum $after $SecRejectionCodes)
    voci_in_libreria = $found.Cells; revocato_nel_registro = [int](($seed.Lines -join " ") -match 'revocato_stato=revoked')
  }
  $v.rifiuti_nuovi = $v.rifiuti_dopo - $v.rifiuti_prima
  $esito = if ($v.rifiuti_nuovi -gt 0 -and $v.voci_in_libreria -eq 0) { "verde" } else { "rosso" }
  return New-SecResult "S2" $esito $v
}

# --- S3 (b)/(c) nell'app: segmenti ostili del dispositivo approvato e le 13 immagini in test-comune/mcg-ostile -------------------------------
function Invoke-SecS3 {
  $ctx = $script:SecCtx
  Start-SecApp $ctx.Serial $ctx.Package
  [void](Invoke-SecShell $ctx.Serial @("logcat", "-c"))
  $before = Read-SecDiagnostics $ctx.Serial $ctx.LogDir
  $segments = Invoke-SecPcStep "semina-s3"
  $images = Invoke-SecPcStep "immagini" @{ MCG_SECURITY_IMAGES = $ctx.ImagesDir }
  Invoke-SecAppWork $ctx.Serial $ctx.Package "sync,scan,prep"
  Wait-SecSettle ($ctx.SettleSeconds * 2)
  $pids = Get-SecPids $ctx.Serial $ctx.Package
  $log = Get-SecLogcat $ctx.Serial
  $traces = Measure-SecStackTraces $log $ctx.Package $pids
  $mem = (Invoke-SecShell $ctx.Serial @("dumpsys", "meminfo", $ctx.Package)).Lines | Where-Object { $_ -match 'TOTAL PSS:\s*(\d+)|^\s*TOTAL\s+(\d+)' } | Select-Object -First 1
  $pss = if ($mem -and $mem -match '(\d{3,})') { [math]::Round([long]$Matches[1] / 1024) } else { -1 }
  $after = Read-SecDiagnostics $ctx.Serial $ctx.LogDir
  $v = [ordered]@{
    segmenti_scritti = [long](Get-SecPcValue $segments "segmenti"); immagini_scritte = [long](Get-SecPcValue $images "file")
    approvato = [int](($segments.Lines -join " ") -match 'approvato_stato=approved'); processo_vivo = [int]($pids.Count -gt 0)
    crolli = $traces.fatal; anr = $traces.anr; pss_mb = $pss; tetto_mb = $ctx.MemoryCapMb
    rifiuti_nuovi = ((Get-SecDiagSum $after $SecRejectionCodes) - (Get-SecDiagSum $before $SecRejectionCodes))
  }
  $esito = if ($v.processo_vivo -eq 1 -and $v.crolli -eq 0 -and $v.anr -eq 0 -and ($pss -lt 0 -or $pss -le $ctx.MemoryCapMb) -and ($v.approvato -eq 0 -or $v.rifiuti_nuovi -gt 0)) { "verde" } else { "rosso" }
  return New-SecResult "S3app" $esito $v
}

# --- S4: dispositivo perso azzerato, SOLO sull'emulatore (mai la debug dell'S25) ---------------------------------------------------------
# L'emulatore deve far parte della famiglia di prova e raggiungere il NAS (casa): senza ULA nel suo Wi-Fi non entra (NO_ULA) e la voce e'
# bloccata. Con l'emulatore azzerato si controlla: niente in files/, no_backup/, databases/ (run-as), nessuna notifica, il widget vuoto.
function Invoke-SecS4 {
  $ctx = $script:SecCtx
  if (-not $ctx.Emulator) { return New-SecResult "S4" "saltata" ([ordered]@{ solo_emulatore = 1 }) }
  if (-not $ctx.HomePossible) { return New-SecResult "S4" "bloccata" ([ordered]@{ casa_possibile = 0 }) }
  $left = [ordered]@{}
  foreach ($dir in @("files", "no_backup", "databases")) {
    $r = Invoke-SecShell $ctx.Serial @("run-as", $ctx.Package, "find", $dir, "-type", "f", "-exec", "stat -c '%s %n'", "{}", "+")
    $left["${dir}_rimasti"] = @($r.Lines | Where-Object { $_ -match '^\d+ ' -and $_ -notmatch 'wipe|azzer' }).Count
  }
  $notes = @((Invoke-SecShell $ctx.Serial @("dumpsys", "notification", "--noredact")).Lines | Where-Object { $_ -match "pkg=$([regex]::Escape($ctx.Package))\b" }).Count
  $left.notifiche = $notes
  $esito = if (($left.Values | Measure-Object -Sum).Sum -eq 0) { "verde" } else { "rosso" }
  return New-SecResult "S4" $esito $left
}

# --- S5: backup di Android e trasferimento, SOLO sull'emulatore -----------------------------------------------------------------------------
function Invoke-SecS5 {
  $ctx = $script:SecCtx
  if (-not $ctx.Emulator) { return New-SecResult "S5" "saltata" ([ordered]@{ solo_emulatore = 1 }) }
  $dump = Read-SecPackageDump (Invoke-SecShell $ctx.Serial @("dumpsys", "package", $ctx.Package)).Lines $ctx.Package
  $previous = (Invoke-SecShell $ctx.Serial @("bmgr", "list", "transports")).Lines | Where-Object { $_ -match '^\s*\*\s*(\S+)' } | ForEach-Object { ($_ -replace '^\s*\*\s*', '').Trim() } | Select-Object -First 1
  [void](Invoke-SecShell $ctx.Serial @("bmgr", "enable", "true"))
  [void](Invoke-SecShell $ctx.Serial @("bmgr", "transport", "com.android.localtransport/.LocalTransport"))
  $run = Read-SecBmgr (Invoke-SecShell $ctx.Serial @("bmgr", "backupnow", $ctx.Package)).Lines $ctx.Package
  if ($previous) { [void](Invoke-SecShell $ctx.Serial @("bmgr", "transport", $previous)) }
  $v = [ordered]@{ allow_backup = [int]$dump.AllowBackup; backup_riuscito = $run.backup_riuscito; backup_non_ammesso = $run.backup_non_ammesso; trasporto_rimesso = [int][bool]$previous; trasferimento_simulato = 0 }
  $esito = if ($v.allow_backup -eq 0 -and $v.backup_riuscito -eq 0) { "verde" } else { "rosso" }
  return New-SecResult "S5" $esito $v
}
