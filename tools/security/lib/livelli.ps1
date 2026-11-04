# I LIVELLI emulatore e s25 di run-all.ps1 (Fase 10 P11, seconda parte): l'ordine delle voci, i tempi stimati e UN SOLO punto in cui si
# aspetta l'utente (livello s25: la password di `test`, poi l'impronta ai fogli della chiave di famiglia, tutto di seguito all'inizio).
# Ogni passo scrive una riga di soli numeri nel resoconto; un passo rosso non ferma i successivi, salvo il reset della famiglia (senza la
# famiglia nuova le voci sull'app non hanno senso) e un AUTH_FAILED (nessun nuovo tentativo).

function Get-SecPlan([string]$Livello, [switch]$SenzaReset) {
  if ($Livello -eq "s25-release") {
    return @(
      [pscustomobject]@{ Id = "preflight"; Minuti = 1; Utente = $false; Titolo = "S25 e release candidata installata (la configura l'utente, mai lo script)" }
      [pscustomobject]@{ Id = "S6"; Minuti = 10; Utente = $false; Titolo = "S6 sulla release candidata: semi MCG-PRIV, logcat e Diagnostica, stack trace" }
      [pscustomobject]@{ Id = "S7rel"; Minuti = 30; Utente = $false; Titolo = "S7 sulla release candidata: le buste firmate con KT ignorate" }
      [pscustomobject]@{ Id = "S13"; Minuti = 2; Utente = $false; Titolo = "S13: dumpsys package (componenti, permessi), netstats per UID" }
      [pscustomobject]@{ Id = "S11"; Minuti = 1; Utente = $false; Titolo = "S11: nessuna sonda di debug nella release" }
    )
  }
  if ($Livello -eq "s25") {
    return @(
      [pscustomobject]@{ Id = "preparazione"; Minuti = 3; Utente = $false; Titolo = "dispositivo, app debug, permessi di Android dati con pm grant" }
      $(if ($SenzaReset) {
        [pscustomobject]@{ Id = "famiglia"; Minuti = 12; Utente = $true; Titolo = "famiglia di prova tenuta com'e' (S25 admin): fotografia prima, lettura, dispositivi di prova approvato e revocato (il dito 3 volte)" }
      } else {
        [pscustomobject]@{ Id = "famiglia"; Minuti = 32; Utente = $true; Titolo = "reset della famiglia di prova (fotografia, archivio di family, Crea la famiglia, kit nuovo), dispositivi di prova approvato e revocato, confronto" }
      })
      [pscustomobject]@{ Id = "S1c"; Minuti = 8; Utente = $false; Titolo = "S1 (c): segni di test in Cerca (controllo con test; test2 se c'e' un dispositivo)" }
      [pscustomobject]@{ Id = "S2"; Minuti = 12; Utente = $false; Titolo = "S2: canaglia e revocato nell'area della famiglia" }
      [pscustomobject]@{ Id = "S3"; Minuti = 19; Utente = $false; Titolo = "S3 (b)/(c) nell'app: segmenti ostili, immagini in test-comune/mcg-ostile" }
      [pscustomobject]@{ Id = "S7"; Minuti = 45; Utente = $false; Titolo = "S7: le buste del manifest di prova (debug: KT valida)" }
      [pscustomobject]@{ Id = "S7a"; Minuti = 10; Utente = $false; Titolo = "S7a: copie manomesse dei modelli sullo scaffale della famiglia" }
      [pscustomobject]@{ Id = "S9"; Minuti = 36; Utente = $false; Titolo = "S9 nell'app: congelamento, avviso, Ho controllato" }
      [pscustomobject]@{ Id = "S11"; Minuti = 1; Utente = $false; Titolo = "S11: sonda del trascinamento (dichiarata, stesso UID)" }
      [pscustomobject]@{ Id = "S10"; Minuti = 3; Utente = $false; Titolo = "S10 (secondaria): intent OPEN_WIDGET inventati" }
      [pscustomobject]@{ Id = "pulizia"; Minuti = 9; Utente = $false; Titolo = "pulizia dell'area della famiglia di prova (solo il registro), fotografia finale" }
    )
  }
  return @(
    [pscustomobject]@{ Id = "accensione"; Minuti = 4; Utente = $false; Titolo = "emulatore Pampa_Tablet su emulator-5580 con -tcpdump (solo se spento)" }
    [pscustomobject]@{ Id = "preflight"; Minuti = 1; Utente = $false; Titolo = "app, SDK, ULA nel Wi-Fi (l'emulatore puo' essere casa?)" }
    [pscustomobject]@{ Id = "S8b"; Minuti = 6; Utente = $false; Titolo = "S8 (b): rete estranea, nessuna SESSION_SETUP nella cattura" }
    [pscustomobject]@{ Id = "S5"; Minuti = 2; Utente = $false; Titolo = "S5: bmgr con il trasporto locale (trasferimento: dichiarato)" }
    [pscustomobject]@{ Id = "S4"; Minuti = 5; Utente = $false; Titolo = "S4: dispositivo perso azzerato (solo se l'emulatore e' casa e nella famiglia)" }
    [pscustomobject]@{ Id = "S6"; Minuti = 10; Utente = $false; Titolo = "S6: release candidata, semi MCG-PRIV, logcat e Diagnostica, stack trace" }
    [pscustomobject]@{ Id = "S7rel"; Minuti = 10; Utente = $false; Titolo = "S7 sulla release: KT ignorata (solo se la release raggiunge il NAS)" }
    [pscustomobject]@{ Id = "S13"; Minuti = 2; Utente = $false; Titolo = "S13: dumpsys package (componenti, permessi), netstats per UID" }
    [pscustomobject]@{ Id = "S11"; Minuti = 1; Utente = $false; Titolo = "S11: sonda del trascinamento solo in debug (dichiarata)" }
    [pscustomobject]@{ Id = "S12"; Minuti = 4; Utente = $false; Titolo = "S12 (secondaria): servizio di accessibilita' non strumento" }
    [pscustomobject]@{ Id = "disinstalla"; Minuti = 1; Utente = $false; Titolo = "release candidata tolta dall'emulatore se l'ha installata lo script" }
    [pscustomobject]@{ Id = "spegnimento"; Minuti = 1; Utente = $false; Titolo = "emulatore spento se l'ha acceso lo script" }
  )
}

function Show-SecPlan([string]$Livello, [object[]]$Plan) {
  $total = ($Plan | Measure-Object -Property Minuti -Sum).Sum
  Write-Host ("Livello {0}: {1} passi, circa {2} minuti. Punto in cui si aspetta l'utente: {3}." -f $Livello, $Plan.Count, $total, $(if (@($Plan | Where-Object { $_.Utente }).Count -gt 0) { "uno, all'inizio (passo 'famiglia': la password di test, poi il dito ai quattro fogli della chiave)" } else { "nessuno" }))
  $at = 0
  foreach ($p in $Plan) {
    Write-Host ("  +{0,3} min  {1,-12} {2}{3}" -f $at, $p.Id, $p.Titolo, $(if ($p.Utente) { "   [UTENTE]" } else { "" }))
    $at += $p.Minuti
  }
}

function Invoke-SecStep([string]$Id) {
  $ctx = $script:SecCtx
  switch ($Id) {
    "preparazione" {
      foreach ($perm in @("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.ACCESS_MEDIA_LOCATION", "android.permission.POST_NOTIFICATIONS")) {
        [void](Invoke-SecShell $ctx.Serial @("pm", "grant", $ctx.Package, $perm))
      }
      return Invoke-SecPreflight
    }
    "preflight" { return Invoke-SecPreflight }
    "famiglia" { if ($ctx.SenzaReset) { return Invoke-SecFamilyKeep }; return Invoke-SecFamilyReset -WithDevices }
    "dispositivi" { return Invoke-SecTestDevices }
    "S1c" { return Invoke-SecS1c }
    "S2" { return Invoke-SecS2 }
    "S3" { return Invoke-SecS3 }
    "S4" { return Invoke-SecS4 }
    "S5" { return Invoke-SecS5 }
    "S6" { if (-not $ctx.ReleaseReady) { return New-SecResult "S6" "bloccata" ([ordered]@{ release_installata = 0 }) }; return Invoke-SecS6 }
    "S7" { return Invoke-SecS7 $ctx.Serial $ctx.Package "S7" }
    "S7a" { return Invoke-SecS7a }
    "S7rel" {
      if (-not $ctx.ReleaseReady -or -not $ctx.HomePossible) { return New-SecResult "S7rel" "bloccata" ([ordered]@{ release_installata = [int][bool]$ctx.ReleaseReady; casa_possibile = [int][bool]$ctx.HomePossible }) }
      return Invoke-SecS7 $ctx.ReleaseSerial $ctx.ReleasePackage "S7rel"
    }
    "S8b" { return Invoke-SecS8b }
    "S9" { return Invoke-SecS9 }
    "S10" { return Invoke-SecS10 }
    "S11" { return Invoke-SecS11 }
    "S12" { return Invoke-SecS12 }
    "S13" { if (-not $ctx.ReleaseReady) { return New-SecResult "S13" "bloccata" ([ordered]@{ release_installata = 0 }) }; return Invoke-SecS13 }
    "pulizia" { return Invoke-SecFamilyCleanup }
    "accensione" {
      Start-SecEmulator $ctx.Root $ctx.Pcap
      if ($script:SecInstallHook) { & $script:SecInstallHook }
      return New-SecResult "accensione" "verde" ([ordered]@{ accesa_dallo_script = [int]$script:SecStartedEmulator; release_pronta = [int][bool]$ctx.ReleaseReady })
    }
    "disinstalla" {
      $done = 0
      if ($ctx.ReleaseInstalled) { [void](Invoke-SecAdb $ctx.Serial @("uninstall", $SecReleasePackage)); $done = 1 }
      return New-SecResult "disinstalla" "verde" ([ordered]@{ release_tolta = $done })
    }
    "spegnimento" { $was = [int]$script:SecStartedEmulator; Stop-SecEmulator; return New-SecResult "spegnimento" "verde" ([ordered]@{ spenta_dallo_script = $was }) }
    default { throw "passo sconosciuto: $Id" }
  }
}

# Esegue il piano (o la sola voce [Only]) e scrive il resoconto. Restituisce 0 se nessun passo e' rosso.
function Invoke-SecLivello([string]$Livello, [string[]]$Only, [string]$Report) {
  $plan = @(Get-SecPlan $Livello -SenzaReset:([bool]$script:SecCtx.SenzaReset))
  if ($Only -and $Only.Count -gt 0) { $plan = @($plan | Where-Object { $_.Id -in $Only -or $_.Id -in @("accensione", "spegnimento", "disinstalla", "preflight", "preparazione") }) }
  Show-SecPlan $Livello $plan
  Add-SecReport $Report @("# Revisione finale di sicurezza (Fase 10 P11, seconda parte), livello $Livello, $(Get-Date -Format 'yyyy-MM-dd HH:mm')")
  $results = @()
  $stop = $false
  try {
    foreach ($p in $plan) {
      if ($stop -and $p.Id -notin @("spegnimento", "disinstalla", "pulizia")) {
        $results += New-SecResult $p.Id "saltata" ([ordered]@{ dopo_un_fermo = 1 })
        Add-SecReport $Report (New-SecLine $p.Id ([ordered]@{ esito = "saltata" }))
        continue
      }
      $started = Get-Date
      Write-Host ("[{0}] {1}: {2}" -f (Get-Date -Format "HH:mm:ss"), $p.Id, $p.Titolo) -ForegroundColor Cyan
      try { $r = Invoke-SecStep $p.Id } catch { Write-Host ("  errore: {0}" -f $_.Exception.Message) -ForegroundColor Red; $r = New-SecResult $p.Id "rosso" ([ordered]@{ errore = 1 }) }
      $values = [ordered]@{ esito = $r.Esito; secondi = [int]((Get-Date) - $started).TotalSeconds }
      foreach ($k in $r.Valori.Keys) { $values[$k] = $r.Valori[$k] }
      $line = New-SecLine $p.Id $values
      Add-SecReport $Report $line
      Write-Host "  $line"
      $results += $r
      if ($p.Id -eq "famiglia" -and $r.Esito -ne "verde") { $stop = $true }
      if ($script:SecAuthStopped) { $stop = $true; Add-SecReport $Report (New-SecLine "fermata" ([ordered]@{ auth_failed = 1; nuovi_tentativi = 0 })) }
    }
  } finally {
    if ($script:SecStartedEmulator) { Stop-SecEmulator }
  }
  $red = @($results | Where-Object { $_.Esito -eq "rosso" }).Count
  $summary = [ordered]@{ passi = $results.Count; verdi = @($results | Where-Object { $_.Esito -eq "verde" }).Count; rossi = $red; dichiarate = @($results | Where-Object { $_.Esito -eq "dichiarata" }).Count; bloccate = @($results | Where-Object { $_.Esito -eq "bloccata" }).Count; saltate = @($results | Where-Object { $_.Esito -eq "saltata" }).Count }
  Add-SecReport $Report (New-SecLine "totale" $summary)
  return $(if ($red -eq 0) { 0 } else { 1 })
}
