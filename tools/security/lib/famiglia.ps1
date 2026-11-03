# La PROCEDURA DEL RESET DELLA FAMIGLIA DI PROVA (decisione 1 dell'utente del 02/11; docs/famiglia-dispositivi.md par. 5, "Ricominciare da
# zero"): senza admin, senza kit e senza codice la famiglia si rifa' dal PC e da un telefono. In ordine:
#   1. PC, sola lettura: fotografia "prima" di test e test-comune (nomi, dimensioni, date; mcg-it saltata);
#   2. PC: `test-comune/.mycloudgallery/family` diventa `family-vecchia-<giro>` (niente si cancella; la guardia C470 non permette altro);
#   3. dispositivo (S25 con la debug e `test`): il copione porta l'app a "Crea la famiglia" e si ferma alla password: L'UTENTE la scrive
#      (una volta) e appoggia il dito al foglio della chiave; il codice del kit nuovo va nel file del kit (fuori da git) e si riscrive;
#   4. PC, sola lettura: fotografia "dopo" e confronto: dentro .mycloudgallery solo la famiglia nuova, il kit nuovo e l'archivio; fuori
#      niente di cambiato; la famiglia nuova si legge (registro firmato dalla chiave del kit nuovo).
# Le share di famiglia (NAS, Public, alessio) non si leggono mai: che il reset non le tocchi lo dicono la recinzione D33 della build debug con
# un account di prova (ogni share fuori da TestShares rifiutata prima di SMB) e le aree mostrate nel riepilogo (test e test-comune).

function Invoke-SecFamilyReset([switch]$SkipArchive, [switch]$WithDevices) {
  $ctx = $script:SecCtx
  $values = [ordered]@{ fotografia_prima = 0; archiviata = 0; copione = 0; kit_scritto = 0; aree_di_prova_nel_riepilogo = 0; fotografia_dopo = 0; confronto_pulito = 0; famiglia_nuova = 0 }
  $before = Invoke-SecPcStep "fotografia" @{ MCG_FAMIGLIA_ETICHETTA = "prima" }
  if ($before.Stopped -or $before.Code -ne 0) { return New-SecResult "famiglia" "rosso" $values }
  $values.fotografia_prima = 1
  if (-not $SkipArchive) {
    $archive = Invoke-SecPcStep "archivia"
    if ($archive.Code -ne 0) { return New-SecResult "famiglia" "rosso" $values }
    $values.archiviata = [int](Get-SecPcValue $archive "archiviata")
  }
  $script:SecKitCode = $null
  $script:SecKitWritten = $false
  $script:SecUserTyped = $false
  $script:SecSummaryShares = 0
  Start-SecApp $ctx.Serial $ctx.Package
  Wait-SecSettle 5
  $run = Invoke-SecCopione -Serial $ctx.Serial -Copione (Get-SecCopioneFamiglia) -Nome "famiglia" -LogDir $ctx.LogDir -PollSeconds $ctx.PollSeconds -UserWaitMinutes $ctx.UserWaitMinutes
  $script:SecKitCode = $null
  $values.copione = [int]($run.Esito -eq "fine")
  $values.kit_scritto = [int]$script:SecKitWritten
  $values.aree_di_prova_nel_riepilogo = [int]$script:SecSummaryShares
  if ($run.Esito -ne "fine") {
    Write-Host ("Il copione del reset si e' fermato al passo '{0}': {1}" -f $run.Passo, $run.Motivo) -ForegroundColor Red
    return New-SecResult "famiglia" "rosso" $values
  }
  # Subito, nello stesso momento dell'utente: le richieste dei dispositivi di prova, le due approvazioni e la revoca (un foglio a gesto).
  if ($WithDevices) {
    $devices = Invoke-SecTestDevices
    $values.dispositivi_approvati = $devices.Valori.approvati
    $values.dispositivi_revocati = $devices.Valori.revocati
    $values.approvato_ok = $devices.Valori.approvato_ok
    $values.revocato_ok = $devices.Valori.revocato_ok
  }
  Wait-SecSettle 20
  $after = Invoke-SecPcStep "fotografia" @{ MCG_FAMIGLIA_ETICHETTA = "dopo" }
  $values.fotografia_dopo = [int]($after.Code -eq 0)
  $compare = Invoke-SecPcStep "confronta"
  $values.confronto_pulito = [int](Get-SecPcValue $compare "pulito")
  $read = Invoke-SecPcStep "leggi"
  $values.famiglia_nuova = [int](Get-SecPcValue $read "trovata")
  $ok = ($values.copione -eq 1 -and $values.kit_scritto -eq 1 -and $values.confronto_pulito -eq 1 -and $values.famiglia_nuova -eq 1 -and $values.aree_di_prova_nel_riepilogo -ge 2)
  if ($WithDevices) { $ok = $ok -and $values.approvato_ok -eq 1 -and $values.revocato_ok -eq 1 }
  return New-SecResult "famiglia" $(if ($ok) { "verde" } else { "rosso" }) $values
}

# La famiglia di prova TENUTA COM'E' (decisione dell'utente del 03/11: l'S25 e' admin con la sua chiave, niente reset, niente rinomina di `family`,
# niente "Esci e rientra", niente "Crea la famiglia"): PC, sola lettura, fotografia "prima" e lettura della famiglia di adesso (kit nell'area
# privata + registro firmato); poi i dispositivi di prova ("approvato" e "revocato" chiedono di entrare, l'S25 li approva col codice digitato e
# revoca "revocato": il dito dell'utente ai fogli) e la fotografia "dopo". Nessun confronto "pulito": con la famiglia che resta, il registro cambia.
function Invoke-SecFamilyKeep {
  $ctx = $script:SecCtx
  $values = [ordered]@{ fotografia_prima = 0; famiglia_trovata = 0; registro_versione = -1; dispositivi_nel_registro = -1; dispositivi_approvati = 0; dispositivi_revocati = 0; approvato_ok = 0; revocato_ok = 0; fotografia_dopo = 0 }
  $before = Invoke-SecPcStep "fotografia" @{ MCG_FAMIGLIA_ETICHETTA = "prima" }
  if ($before.Stopped -or $before.Code -ne 0) { return New-SecResult "famiglia" "rosso" $values }
  $values.fotografia_prima = 1
  $read = Invoke-SecPcStep "leggi"
  $values.famiglia_trovata = [int](Get-SecPcValue $read "trovata")
  $version = Get-SecPcValue $read "versione_registro"; if ($null -ne $version) { $values.registro_versione = $version }
  $count = Get-SecPcValue $read "dispositivi"; if ($null -ne $count) { $values.dispositivi_nel_registro = $count }
  if ($values.famiglia_trovata -ne 1) { return New-SecResult "famiglia" "rosso" $values }
  Start-SecApp $ctx.Serial $ctx.Package
  Wait-SecSettle 5
  $devices = Invoke-SecTestDevices
  $values.dispositivi_approvati = $devices.Valori.approvati
  $values.dispositivi_revocati = $devices.Valori.revocati
  $values.approvato_ok = $devices.Valori.approvato_ok
  $values.revocato_ok = $devices.Valori.revocato_ok
  $after = Invoke-SecPcStep "fotografia" @{ MCG_FAMIGLIA_ETICHETTA = "dopo" }
  $values.fotografia_dopo = [int]($after.Code -eq 0)
  $ok = ($values.approvato_ok -eq 1 -and $values.revocato_ok -eq 1 -and $values.fotografia_dopo -eq 1)
  return New-SecResult "famiglia" $(if ($ok) { "verde" } else { "rosso" }) $values
}

# I dispositivi di prova del PC entrano nella famiglia nuova: richieste di "approvato" e "revocato", l'admin (lo stesso dispositivo, subito
# dopo il reset: stesso momento dell'utente) approva entrambi con il codice digitato dallo script, poi revoca "revocato". Il foglio dello
# sblocco, uno per gesto (M3), e' dell'utente sull'S25 (sull'emulatore il PIN di prova).
function Invoke-SecTestDevices {
  $ctx = $script:SecCtx
  $values = [ordered]@{ richieste = 0; approvati = 0; revocati = 0; approvato_ok = 0; revocato_ok = 0 }
  $req = Invoke-SecPcStep "richieste"
  if ($req.Code -ne 0) { return New-SecResult "dispositivi" "rosso" $values }
  $values.richieste = [int](Get-SecPcValue $req "richieste")
  $codes = @{}
  foreach ($line in Get-Content -LiteralPath (Join-Path $ctx.StateDir "codici.txt")) { if ($line -match '^(approvato|revocato)=([A-Z0-9-]+)$') { $codes[$Matches[1]] = $Matches[2] } }
  Invoke-SecAppWork $ctx.Serial $ctx.Package "sync"
  Wait-SecSettle 30
  foreach ($role in @("approvato", "revocato")) {
    $r = Invoke-SecCopione -Serial $ctx.Serial -Copione (Get-SecCopioneApprova "MCG prova $role" $codes[$role]) -Nome "approva-$role" -LogDir $ctx.LogDir -PollSeconds $ctx.PollSeconds -UserWaitMinutes $ctx.UserWaitMinutes
    if ($r.Esito -eq "fine") { $values.approvati++ }
  }
  $rev = Invoke-SecCopione -Serial $ctx.Serial -Copione (Get-SecCopioneRevoca "MCG prova revocato") -Nome "revoca" -LogDir $ctx.LogDir -PollSeconds $ctx.PollSeconds -UserWaitMinutes $ctx.UserWaitMinutes
  if ($rev.Esito -eq "fine") { $values.revocati = 1 }
  Wait-SecSettle 20
  $read = Invoke-SecPcStep "leggi"
  $text = $read.Lines -join " "
  $values.approvato_ok = [int]($text -match 'approvato_stato=approved')
  $values.revocato_ok = [int]($text -match 'revocato_stato=revoked')
  $ok = ($values.approvato_ok -eq 1 -and $values.revocato_ok -eq 1)
  return New-SecResult "dispositivi" $(if ($ok) { "verde" } else { "rosso" }) $values
}

# La pulizia finale dell'area della famiglia di prova: solo il registro della guardia, poi la fotografia "fine". La famiglia vecchia
# (`family-vecchia-<giro>`) resta: la toglie l'utente, se vuole. Le chiavi dei dispositivi di prova si tolgono dallo stato (nessuno puo' piu'
# firmare come loro); "approvato" resta approvato nel registro di prova, senza chiave (si revoca da Impostazioni, se si vuole).
function Invoke-SecFamilyCleanup {
  $ctx = $script:SecCtx
  $clean = Invoke-SecPcStep "pulizia"
  $end = Invoke-SecPcStep "fotografia" @{ MCG_FAMIGLIA_ETICHETTA = "fine" }
  $residue = Get-SecPcValue $clean "residui"
  $values = [ordered]@{ residui = $(if ($null -eq $residue) { -1 } else { $residue }); fotografia_fine = [int]($end.Code -eq 0); chiavi_tolte = 0 }
  if ($ctx.SenzaReset -and $end.Code -eq 0) {
    # Famiglia tenuta com'e': la fotografia "fine" contro la "prima". Fuori da .mycloudgallery niente deve essere cambiato; dentro cambia solo cio'
    # che fa l'app admin (registro, indici) e la famiglia stessa: i numeri vanno nel resoconto, l'elenco nel file fotografia-confronto.txt.
    $diff = Compare-SecSnapshots (Join-Path $ctx.StateDir "fotografia-prima.tsv") (Join-Path $ctx.StateDir "fotografia-fine.tsv") (Join-Path $ctx.StateDir "fotografia-confronto.txt")
    if ($diff) { foreach ($k in $diff.Keys) { $values[$k] = $diff[$k] } }
  }
  $state = Join-Path $ctx.StateDir "stato.txt"
  if ($clean.Code -eq 0 -and $residue -eq 0 -and (Test-Path -LiteralPath $state)) {
    (Get-Content -LiteralPath $state | Where-Object { $_ -notmatch '^device=' }) | Set-Content -LiteralPath (Join-Path $ctx.StateDir "stato-senza-chiavi.txt")
    Remove-Item -LiteralPath $state -Force
    $values.chiavi_tolte = 1
  }
  return New-SecResult "pulizia" $(if ($values.residui -eq 0) { "verde" } else { "rosso" }) $values
}

# Il confronto di due fotografie (TSV: share, percorso, byte, data ms, cartella) fatto dal PC senza NAS: aggiunte, tolte e cambiate, dentro e
# fuori da .mycloudgallery (mcg-ostile e mcg-it ignorate). L'elenco dei percorsi va in [Out] (sul PC, sotto build\); i numeri tornano.
function Compare-SecSnapshots([string]$Before, [string]$After, [string]$Out) {
  if (-not (Test-Path -LiteralPath $Before) -or -not (Test-Path -LiteralPath $After)) { return $null }
  function Read-Snap([string]$Path) {
    $map = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
      if ($line.StartsWith("#") -or -not $line.Trim()) { continue }
      $f = $line -split "`t"
      if ($f.Count -lt 5) { continue }
      $map[($f[0] + "|" + $f[1].ToLowerInvariant())] = [pscustomobject]@{ Share = $f[0]; Path = $f[1]; Bytes = $f[2]; Dir = $f[4] }
    }
    return $map
  }
  $a = Read-Snap $Before; $b = Read-Snap $After
  $inside = { param($e) $p = $e.Path.ToLowerInvariant(); ($p -eq ".mycloudgallery") -or $p.StartsWith(".mycloudgallery/") }
  $ignored = { param($e) $p = $e.Path.ToLowerInvariant(); ($p -eq "mcg-ostile") -or $p.StartsWith("mcg-ostile/") -or ($p -eq "mcg-it") -or $p.StartsWith("mcg-it/") }
  $lines = @()
  $n = [ordered]@{ fuori_aggiunti = 0; fuori_tolti = 0; fuori_cambiati = 0; dentro_aggiunti = 0; dentro_tolti = 0; dentro_cambiati = 0 }
  foreach ($k in $b.Keys) {
    $e = $b[$k]; if (& $ignored $e) { continue }
    $where = if (& $inside $e) { "dentro" } else { "fuori" }
    if (-not $a.ContainsKey($k)) { $n["${where}_aggiunti"]++; $lines += "+ $where $($e.Share)/$($e.Path)" }
    elseif ($a[$k].Bytes -ne $e.Bytes -and $e.Dir -ne "1" -and $e.Dir -ne "true") { $n["${where}_cambiati"]++; $lines += "~ $where $($e.Share)/$($e.Path)" }
  }
  foreach ($k in $a.Keys) {
    $e = $a[$k]; if (& $ignored $e) { continue }
    if (-not $b.ContainsKey($k)) { $where = if (& $inside $e) { "dentro" } else { "fuori" }; $n["${where}_tolti"]++; $lines += "- $where $($e.Share)/$($e.Path)" }
  }
  Set-Content -LiteralPath $Out -Encoding UTF8 -Value $lines
  return $n
}
