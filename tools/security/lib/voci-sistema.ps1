# Le voci S6-S13 (Fase 10 P11, seconda parte): log della release, manifest, rete estranea, cambio di massa nell'app, widget, trascinamento,
# servizio non "strumento", componenti e traffico della release. Stesso contratto di voci.ps1: { Voce; Esito; Valori (solo numeri) }.

# --- S6: nessun dato personale (semi riconoscibili) nei log della release, nemmeno stack trace -------------------------------------------
# I semi: "MCG-PRIV-<casuale>" nei nomi di file di mcg-prova sul dispositivo (spinti dal PC), il segno di S1 sul NAS, una parola cercata in
# Cerca. Poi logcat -d -b all e l'esportazione della Diagnostica: zero occorrenze, zero stack trace dell'app.
function Invoke-SecS6 {
  $ctx = $script:SecCtx
  $serial = $ctx.ReleaseSerial
  $pkg = $ctx.ReleasePackage
  $seed = "MCG-PRIV-" + ([guid]::NewGuid().ToString("N").Substring(0, 12))
  $seeds = @($seed, "$seed-nome", "$seed-luogo")
  $markFile = Join-Path $ctx.StateDir "segno-s1.txt"
  if (Test-Path -LiteralPath $markFile) { $seeds += (Get-Content -LiteralPath $markFile -Raw).Trim() }
  # Un file di prova con il seme nel nome, in mcg-prova (la sola cartella che la build legge per il backup, D13).
  $tmp = Join-Path $ctx.LogDir "$seed-nome.jpg"
  New-Item -ItemType Directory -Force -Path $ctx.LogDir | Out-Null
  [System.IO.File]::WriteAllBytes($tmp, [byte[]](0xFF, 0xD8, 0xFF, 0xE0) + [System.Text.Encoding]::ASCII.GetBytes("$seed-luogo") + [byte[]](0xFF, 0xD9))
  [void](Invoke-SecShell $serial @("mkdir", "-p", "/sdcard/Pictures/mcg-prova"))
  [void](Invoke-SecAdb $serial @("push", $tmp, "/sdcard/Pictures/mcg-prova/"))
  Remove-Item -LiteralPath $tmp -Force
  [void](Invoke-SecShell $serial @("logcat", "-c"))
  Start-SecApp $serial $pkg
  Wait-SecSettle $ctx.SettleSeconds
  $search = Invoke-SecSearch $serial $seed
  $diag = Read-SecDiagnostics $serial $ctx.LogDir
  Wait-SecSettle 10
  $log = Get-SecLogcat $serial
  $traces = Measure-SecStackTraces $log $pkg (Get-SecPids $serial $pkg)
  [void](Invoke-SecShell $serial @("rm", "-rf", "/sdcard/Pictures/mcg-prova/$seed-nome.jpg"))
  $v = [ordered]@{
    semi = $seeds.Count; righe_logcat = $log.Count; occorrenze_logcat = (Measure-SecSeeds $log $seeds)
    righe_diagnostica = $diag.Lines; occorrenze_diagnostica = (Measure-SecSeeds @($diag.Text) $seeds)
    stacktrace_app = $traces.stacktrace; righe_di_stack = $traces.righe_di_stack; read_limit = $traces.read_limit; crolli = $traces.fatal; ricerca_eseguita = [int]($search -ne $null)
  }
  $esito = if ($v.occorrenze_logcat -eq 0 -and $v.occorrenze_diagnostica -eq 0 -and $v.stacktrace_app -eq 0 -and $v.crolli -eq 0) { "verde" } else { "rosso" }
  return New-SecResult "S6" $esito $v
}

# --- S7: le buste di build\security\<data>\manifest\ dove la famiglia di prova legge il manifest; KT ignorata in release ---------------------
# Per ogni busta: il PC la mette in test-comune/.mycloudgallery/release/manifest.mcgr, l'app la legge (giro o riavvio), la Diagnostica dice
# il blocco in vigore (RELEASE_BLOCKED) e i rifiuti (RELEASE_MANIFEST_REJECTED). Atteso in debug: 01 WRITES, 02 ALL, 03 nessun blocco,
# 04 (seq vecchio) rifiutata, le ostili rifiutate; in release (KT ignorata): tutte rifiutate, nessun blocco.
function Invoke-SecS7([string]$Serial, [string]$Package, [string]$Label) {
  $ctx = $script:SecCtx
  $files = @(Get-ChildItem -LiteralPath $ctx.ManifestDir -Filter "*.mcgr" -ErrorAction SilentlyContinue | Sort-Object Name)
  if ($files.Count -eq 0) { return New-SecResult $Label "rosso" ([ordered]@{ buste = 0 }) }
  $release = ($Package -eq $SecReleasePackage)
  $v = [ordered]@{ buste = $files.Count; blocchi_applicati = 0; rifiutate = 0; inattese = 0 }
  $previousRejected = 0
  foreach ($f in $files) {
    $placed = Invoke-SecPcStep "manifest" @{ MCG_FAMIGLIA_BUSTA = $f.FullName }
    if ($placed.Code -ne 0) { $v.inattese++; continue }
    Invoke-SecAppWork $Serial $Package "sync"
    Wait-SecSettle $ctx.SettleSeconds
    $diag = Read-SecDiagnostics $Serial $ctx.LogDir
    $blocked = @($diag.Items | Where-Object { $_.Code -eq "RELEASE_BLOCKED" }).Count -gt 0
    $rejected = Get-SecDiagSum $diag @("RELEASE_MANIFEST_REJECTED")
    $newReject = ($rejected -gt $previousRejected)
    $previousRejected = $rejected
    if ($blocked) { $v.blocchi_applicati++ }
    if ($newReject) { $v.rifiutate++ }
    $expectBlock = (-not $release) -and ($f.Name -match '^0[12]-')
    $expectReject = $release -or ($f.Name -match '^(04|1[0-9])-')
    # 04 arriva dopo 03 (nessun blocco): il blocco non deve tornare; le ostili non cambiano lo stato.
    if (($f.Name -match '^0[12]-' -and $blocked -ne $expectBlock) -or ($f.Name -match '^03-' -and $blocked) -or ($expectReject -and -not $newReject)) { $v.inattese++ }
  }
  $esito = if ($v.inattese -eq 0) { "verde" } else { "rosso" }
  return New-SecResult $Label $esito $v
}

# --- S8 (b): l'emulatore (rete non di casa) con la cattura -tcpdump: nessuna SESSION_SETUP, nessun NTLMSSP ----------------------------------
function Invoke-SecS8b {
  $ctx = $script:SecCtx
  if (-not $ctx.Emulator) { return New-SecResult "S8b" "saltata" ([ordered]@{ solo_emulatore = 1 }) }
  Start-SecApp $ctx.Serial $SecDebugPackage
  Invoke-SecAppWork $ctx.Serial $SecDebugPackage "all"
  Wait-SecSettle ($ctx.SettleSeconds * 2)
  $ui = Get-SecUi $ctx.Serial
  $foreign = [int]((Test-SecUiHas $ui "Rete non riconosciuta" -Contains) -or (Test-SecUiHas $ui "Benvenuto"))
  if (-not $ctx.Pcap -or -not (Test-Path -LiteralPath $ctx.Pcap)) { return New-SecResult "S8b" "rosso" ([ordered]@{ cattura = 0; rete_estranea_a_schermo = $foreign }) }
  $p = Read-SecPcap $ctx.Pcap
  $v = [ordered]@{ cattura = $p.formato_valido; pacchetti = $p.pacchetti; syn_445 = $p.syn_445; smb2 = $p.smb2; session_setup = $p.session_setup; ntlmssp = $p.ntlmssp; destinazioni = $p.destinazioni; nomi_dns = $p.nomi_dns; nomi_fuori_elenco = $p.nomi_fuori_elenco; rete_estranea_a_schermo = $foreign }
  $esito = if ($v.cattura -eq 1 -and $v.session_setup -eq 0 -and $v.ntlmssp -eq 0) { "verde" } else { "rosso" }
  return New-SecResult "S8b" $esito $v
}

# --- S9 nell'app: 2.000 file di prova in test-comune/mcg-it/<giro>/massa, un giro di scansione, il cambio di massa, il congelamento -----------
function Invoke-SecS9 {
  $ctx = $script:SecCtx
  $create = Invoke-SecPcStep "massa-crea" @{ MCG_SECURITY_S9_FILES = "$($ctx.MassFiles)" }
  if ($create.Code -ne 0) { return New-SecResult "S9app" "rosso" ([ordered]@{ semina = 0 }) }
  Invoke-SecAppWork $ctx.Serial $ctx.Package "scan"
  Wait-SecSettle ($ctx.SettleSeconds * 2)
  $change = Invoke-SecPcStep "massa-cambia"
  Invoke-SecAppWork $ctx.Serial $ctx.Package "scan"
  Wait-SecSettle ($ctx.SettleSeconds * 2)
  $diag = Read-SecDiagnostics $ctx.Serial $ctx.LogDir
  $frozen = [int](Test-SecDiagHas $diag "NAS_SCAN_FROZEN")
  $notes = @((Invoke-SecShell $ctx.Serial @("dumpsys", "notification", "--noredact")).Lines | Where-Object { $_ -match "pkg=$([regex]::Escape($ctx.Package))\b" }).Count
  $wrong = Invoke-SecUnfreeze $ctx.Serial -Sbagliato
  $right = Invoke-SecUnfreeze $ctx.Serial
  $clean = Invoke-SecPcStep "massa-pulizia"
  $v = [ordered]@{
    file = [long](Get-SecPcValue $create "file"); cambiati = [long](Get-SecPcValue $change "rinominati") + [long](Get-SecPcValue $change "cambiati") + [long](Get-SecPcValue $change "cifrati")
    congelato = $frozen; notifiche_app = $notes; numero_sbagliato_rifiutato = $wrong.Wrong; scongelato = [int]$right.Done; pulizia_residui = [long](Get-SecPcValue $clean "residui")
  }
  $esito = if ($v.congelato -eq 1 -and $v.numero_sbagliato_rifiutato -eq 1 -and $v.scongelato -eq 1 -and $v.pulizia_residui -eq 0) { "verde" } else { "rosso" }
  return New-SecResult "S9app" $esito $v
}

# --- S10 (widget, secondaria): intent OPEN_WIDGET inventati aprono la Libreria e niente altro, senza crolli -----------------------------
function Invoke-SecS10 {
  $ctx = $script:SecCtx
  [void](Invoke-SecShell $ctx.Serial @("logcat", "-c"))
  $cases = @(@("--ei", "slot", "999999"), @("--es", "media", "../../etc"), @("-d", "content://dev.pampa.mycloudgallery.debug.media/1"), @("--el", "widget", "-1", "--el", "token", "42"))
  $library = 0
  foreach ($extra in $cases) {
    [void](Invoke-SecShell $ctx.Serial (@("am", "start", "-a", $SecWidgetAction, "-n", "$($ctx.Package)/dev.pampa.mycloudgallery.MainActivity") + $extra))
    Wait-SecSettle 3
    if (Test-SecUiHas (Get-SecUi $ctx.Serial) "Libreria") { $library++ }
    Invoke-SecKey $ctx.Serial "KEYCODE_BACK"
  }
  $traces = Measure-SecStackTraces (Get-SecLogcat $ctx.Serial) $ctx.Package (Get-SecPids $ctx.Serial $ctx.Package)
  $v = [ordered]@{ intent_inventati = $cases.Count; aperta_la_libreria = $library; crolli = $traces.fatal; schermata_di_blocco = 0 }
  $esito = if ($library -eq $cases.Count -and $traces.fatal -eq 0) { "verde" } else { "rosso" }
  return New-SecResult "S10" $esito $v
}

# --- S11 (trascinamento): dichiarata "solo lettura per lo stesso UID" (I2): la sonda c'e' solo in debug, e ha l'UID dell'app ---------------
function Invoke-SecS11 {
  $ctx = $script:SecCtx
  $debug = Read-SecPackageDump (Invoke-SecShell $ctx.Serial @("dumpsys", "package", $SecDebugPackage)).Lines $SecDebugPackage
  $probe = [int](@($debug.Components | Where-Object { $_ -match 'FieldProbeDragReceiverActivity' }).Count -gt 0)
  $inRelease = 0
  if ($ctx.ReleaseSerial) {
    $rel = Read-SecPackageDump (Invoke-SecShell $ctx.ReleaseSerial @("dumpsys", "package", $SecReleasePackage)).Lines $SecReleasePackage
    $inRelease = [int](@($rel.Components | Where-Object { $_ -match 'FieldProbe' }).Count -gt 0)
  }
  return New-SecResult "S11" $(if ($inRelease -eq 0) { "dichiarata" } else { "rosso" }) ([ordered]@{ sonda_in_debug = $probe; sonda_in_release = $inRelease; stesso_uid = 1 })
}

# --- S12: un servizio di accessibilita' NON "strumento" (FieldProbeA11yService, solo debug) non legge la finestra dell'app -------------------
# Le impostazioni di accessibilita' si salvano prima e si rimettono dopo, CON le tre chiavi che l'accensione svuota (vedi prove-campo-esiti).
function Invoke-SecS12 {
  $ctx = $script:SecCtx
  $keys = @("enabled_accessibility_services", "accessibility_enabled", "accessibility_button_targets", "accessibility_direct_access_target_service", "accessibility_gesture_targets")
  $saved = [ordered]@{}
  foreach ($k in $keys) { $saved[$k] = (Invoke-SecShell $ctx.Serial @("settings", "get", "secure", $k)).Text }
  $probe = "$SecDebugPackage/dev.pampa.mycloudgallery.debugtools.probes.FieldProbeA11yService"
  $list = if ($saved.enabled_accessibility_services -and $saved.enabled_accessibility_services -ne "null") { $saved.enabled_accessibility_services + ":" + $probe } else { $probe }
  [void](Invoke-SecShell $ctx.Serial @("logcat", "-c"))
  try {
    [void](Invoke-SecShell $ctx.Serial @("settings", "put", "secure", "enabled_accessibility_services", $list))
    [void](Invoke-SecShell $ctx.Serial @("settings", "put", "secure", "accessibility_enabled", "1"))
    Start-SecApp $ctx.Serial $SecDebugPackage
    Wait-SecSettle 8
    [void](Invoke-SecShell $ctx.Serial @("am", "start", "-a", "android.settings.SETTINGS"))
    Wait-SecSettle 5
    $read = Read-SecA11yProbe (Get-SecLogcat $ctx.Serial) $SecDebugPackage
  } finally {
    foreach ($k in $keys) {
      $value = $saved[$k]
      if (-not $value -or $value -eq "null") { [void](Invoke-SecShell $ctx.Serial @("settings", "delete", "secure", $k)) }
      else { [void](Invoke-SecShell $ctx.Serial @("settings", "put", "secure", $k, $value)) }
    }
  }
  $back = 0
  foreach ($k in $keys) { if ((Invoke-SecShell $ctx.Serial @("settings", "get", "secure", $k)).Text -eq $saved[$k]) { $back++ } }
  $v = [ordered]@{ testi_letti_app = $read.testi_letti_app; finestre_app = $read.finestre_app; testi_letti_altre_app = $read.testi_letti_altre_app; impostazioni_rimesse = $back; impostazioni = $keys.Count }
  $esito = if ($v.testi_letti_app -eq 0 -and $v.testi_letti_altre_app -gt 0 -and $back -eq $keys.Count) { "verde" } else { "rosso" }
  return New-SecResult "S12" $esito $v
}

# --- S13: componenti esportati e permessi della release (dumpsys package), traffico per UID (netstats). PCAPdroid no (decisione 3). ------------
function Invoke-SecS13 {
  $ctx = $script:SecCtx
  $serial = $ctx.ReleaseSerial
  $dump = Read-SecPackageDump (Invoke-SecShell $serial @("dumpsys", "package", $SecReleasePackage)).Lines $SecReleasePackage
  $allowed = Get-SecAllowedExported $ctx.Root
  $outside = @($dump.Components | Where-Object { -not $allowed.ContainsKey($_) -and $_ -notmatch '^androidx\.|^com\.google\.' }).Count
  $declared = @([regex]::Matches((Get-Content -LiteralPath (Join-Path $ctx.Root "app\src\main\AndroidManifest.xml") -Raw), 'uses-permission\s+android:name="([^"]+)"') | ForEach-Object { $_.Groups[1].Value })
  $extraPerms = @($dump.Requested | Where-Object { $_ -notin $declared -and $_ -notmatch 'DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION$|^android\.permission\.(INTERNET|FOREGROUND_SERVICE|WAKE_LOCK|RECEIVE_BOOT_COMPLETED|FOREGROUND_SERVICE_DATA_SYNC)$' }).Count
  $net = Read-SecNetstats (Invoke-SecShell $serial @("dumpsys", "netstats", "detail")).Lines $dump.Uid
  $v = [ordered]@{ debuggable = [int]$dump.Debuggable; componenti_con_filtri = $dump.Components.Count; fuori_elenco = $outside; permessi_chiesti = $dump.Requested.Count; permessi_fuori_manifest = $extraPerms; byte_ricevuti = $net.byte_ricevuti; byte_mandati = $net.byte_mandati; host_per_nome = 0 }
  $esito = if ($v.debuggable -eq 0 -and $v.fuori_elenco -eq 0) { "verde" } else { "rosso" }
  return New-SecResult "S13" $esito $v
}

# --- S7a: modello manomesso sullo scaffale della famiglia di prova (devices/<approvato>/models/<sha256> con byte diversi) --------------------
# Le impronte vengono dal catalogo dei modelli (ModelCatalog.kt di core-models). L'app le guarda solo se deve scaricare un modello: se li ha
# gia' tutti, nessun rifiuto e' atteso e la voce resta dichiarata (il livello JVM, S7aTamperedModelTest, la prova sempre).
function Invoke-SecS7a {
  $ctx = $script:SecCtx
  $catalog = Get-ChildItem -LiteralPath (Join-Path $ctx.Root "core-models\src\main") -Recurse -Filter "ModelCatalog.kt" | Select-Object -First 1
  $shas = @([regex]::Matches((Get-Content -LiteralPath $catalog.FullName -Raw), '"([0-9a-f]{64})"') | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique)
  Start-SecApp $ctx.Serial $ctx.Package
  [void](Invoke-SecShell $ctx.Serial @("logcat", "-c"))
  $before = Read-SecDiagnostics $ctx.Serial $ctx.LogDir
  $seed = Invoke-SecPcStep "semina-s7a" @{ MCG_S7A_SHA = ($shas -join ",") }
  Invoke-SecAppWork $ctx.Serial $ctx.Package "all"
  Wait-SecSettle ($ctx.SettleSeconds * 2)
  $after = Read-SecDiagnostics $ctx.Serial $ctx.LogDir
  $traces = Measure-SecStackTraces (Get-SecLogcat $ctx.Serial) $ctx.Package (Get-SecPids $ctx.Serial $ctx.Package)
  $v = [ordered]@{ modelli_nel_catalogo = $shas.Count; copie_manomesse = [long](Get-SecPcValue $seed "file"); rifiuti_modello = ((Get-SecDiagSum $after @("AI_MODEL_MISMATCH")) - (Get-SecDiagSum $before @("AI_MODEL_MISMATCH"))); crolli = $traces.fatal }
  $esito = if ($v.crolli -gt 0) { "rosso" } elseif ($v.rifiuti_modello -gt 0) { "verde" } else { "dichiarata" }
  return New-SecResult "S7a" $esito $v
}
