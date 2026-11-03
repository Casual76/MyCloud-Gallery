# I COPIONI dell'interfaccia (Fase 10 P11, seconda parte): l'app guidata con UI Automator schermata per schermata. Un copione e' un elenco
# di passi { Nome; Se (riconosce la schermata); Fai (agisce e risponde "avanti", "attendi", "attendi-utente", "fine" o "fermo:<motivo>") }. Il motore
# legge lo schermo, sceglie il primo passo che riconosce la schermata e lo esegue; una schermata che nessun passo riconosce si salva sotto
# build\ (struttura, per correggere il copione) e, se resta, ferma la prova. Gli agenti confermano solo i dialoghi della nostra app: un
# dialogo di sistema (permessi) si chiude con Indietro; il foglio dello sblocco (chiave di famiglia, M3) sull'S25 lo fa l'utente, e lo
# script aspetta; sull'emulatore lo script scrive il PIN di prova che ha messo lui. I testi non ASCII si scrivono con \uXXXX (U).
# I passi leggono il contesto da $script:SecCtx (Serial, NasIp, KitFile, Emulator): i blocchi non catturano variabili.

$SecSystemPackages = @("com.android.systemui", "com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.samsung.android.biometrics.app.setting")

function U([string]$Text) { return [regex]::Unescape($Text) }

function New-SecStep([string]$Nome, [scriptblock]$Se, [scriptblock]$Fai, [string]$Messaggio = "") {
  return [pscustomobject]@{ Nome = $Nome; Se = $Se; Fai = $Fai; Messaggio = $Messaggio }
}

function Invoke-SecCopione {
  param(
    [string]$Serial,
    [object[]]$Copione,
    [string]$Nome,
    [string]$LogDir,
    [int]$MaxPolls = 2400,
    [int]$PollSeconds = 3,
    [int]$UserWaitMinutes = 30,
    [int]$UnknownLimit = 8
  )
  $unknown = 0
  $sameCount = 0
  $last = ""
  $waitingSince = $null
  $appSince = $null
  for ($i = 0; $i -lt $MaxPolls; $i++) {
    $ui = @(Get-SecUi $Serial)
    $step = $null
    foreach ($candidate in $Copione) { if (& $candidate.Se $ui) { $step = $candidate; break } }
    if (-not $step) {
      $unknown++
      if ($unknown -eq 1) { [void](Save-SecUi $ui $LogDir "$Nome-sconosciuta") }
      if ($unknown -ge $UnknownLimit) { return [pscustomobject]@{ Esito = "fermo"; Motivo = "schermata sconosciuta"; Passo = $last } }
      Start-Sleep -Seconds $PollSeconds
      continue
    }
    $unknown = 0
    if ($step.Nome -eq $last) { $sameCount++ } else { $sameCount = 0 }
    $result = [string](& $step.Fai $ui)
    if ($result -eq "fine") { return [pscustomobject]@{ Esito = "fine"; Motivo = ""; Passo = $step.Nome } }
    if ($result -like "fermo:*") {
      [void](Save-SecUi $ui $LogDir "$Nome-fermo")
      return [pscustomobject]@{ Esito = "fermo"; Motivo = $result.Substring(6); Passo = $step.Nome }
    }
    if ($result -eq "attendi-utente") {
      if (-not $waitingSince) {
        $waitingSince = Get-Date
        Write-Host ("[{0}] ADESSO TOCCA ALL'UTENTE: {1}" -f (Get-Date -Format "HH:mm:ss"), $step.Messaggio) -ForegroundColor Yellow
        try { [console]::Beep(880, 300) } catch { }
      }
      if (((Get-Date) - $waitingSince).TotalMinutes -gt $UserWaitMinutes) {
        return [pscustomobject]@{ Esito = "fermo"; Motivo = "l'utente non e' arrivato in $UserWaitMinutes minuti"; Passo = $step.Nome }
      }
      $sameCount = 0
    } elseif ($result -eq "attendi") {
      # L'app lavora (NAS, chiave): si aspetta senza contare la schermata come ferma, al piu' 10 minuti di fila.
      if (-not $appSince) { $appSince = Get-Date }
      if (((Get-Date) - $appSince).TotalMinutes -gt 10) { return [pscustomobject]@{ Esito = "fermo"; Motivo = "l'app lavora da piu' di 10 minuti"; Passo = $step.Nome } }
      $sameCount = 0
    } else {
      $appSince = $null
      if ($waitingSince) { Write-Host ("[{0}] Fatto, grazie: riprendo da solo." -f (Get-Date -Format "HH:mm:ss")) -ForegroundColor Green }
      $waitingSince = $null
      if ($sameCount -ge 6) {
        [void](Save-SecUi $ui $LogDir "$Nome-ferma")
        return [pscustomobject]@{ Esito = "fermo"; Motivo = "la schermata non cambia"; Passo = $step.Nome }
      }
    }
    $last = $step.Nome
    Start-Sleep -Seconds $PollSeconds
  }
  return [pscustomobject]@{ Esito = "fermo"; Motivo = "troppi giri"; Passo = $last }
}

# Un dialogo di sistema in primo piano (nessun nodo della nostra app).
function Test-SecSystemDialog($Ui) {
  $withPackage = @($Ui | Where-Object { $_.Package })
  if ($withPackage.Count -eq 0) { return $false }
  $system = @($withPackage | Where-Object { $_.Package -in $SecSystemPackages }).Count
  $ours = @($withPackage | Where-Object { $_.Package -like "dev.pampa.mycloudgallery*" }).Count
  return ($system -gt 0 -and $ours -eq 0)
}

# Il foglio dello sblocco per la chiave di famiglia (BiometricPrompt di sistema, o il suo sottotitolo).
function Test-SecUnlockSheet($Ui) {
  if (Test-SecUiHas $Ui "Conferma con la credenziale del telefono") { return $true }
  if (-not (Test-SecSystemDialog $Ui)) { return $false }
  return ((Test-SecUiHas $Ui "PIN" -Contains) -or (Test-SecUiHas $Ui "impronta" -Contains) -or (Test-SecUiHas $Ui "Sblocc" -Contains) -or (Test-SecUiHas $Ui "fingerprint" -Contains))
}

# Il codice di recupero sullo schermo: 7 gruppi di 4 (maiuscole e cifre) in ordine di lettura; l'impronta della famiglia: 26 caratteri a gruppi.
function Get-SecRecoveryCode($Ui) {
  $groups = @($Ui | Where-Object { $_.Text -cmatch '^[0-9A-Z]{4}$' } | Sort-Object Top, X | ForEach-Object { $_.Text })
  $fingerprint = $null
  foreach ($n in $Ui) {
    $plain = ($n.Text -replace '[\s-]', '')
    if ($plain -match '^[0-9A-Za-z]{26}$' -and $n.Text -match '[\s-]') { $fingerprint = $n.Text.Trim() }
  }
  if ($groups.Count -ne 7) { return $null }
  return [pscustomobject]@{ Groups = $groups; Code = ($groups -join "-"); Fingerprint = $fingerprint }
}

function Invoke-SecContinue([string]$Serial) {
  if (-not (Invoke-SecTapLabel $Serial "Continua" -Scroll 4)) { throw "Manca il tasto Continua." }
}

# Le aree: "test-comune" sotto "Area comune", "test" sotto "Area privata" (fra il titolo della sezione e quello della sezione dopo).
function Select-SecAreas([string]$Serial) {
  $ui = Get-SecUi $Serial
  $common = Find-SecNode $ui "Area comune"
  $private = Find-SecNode $ui "Area privata"
  if (-not $common -or -not $private) { throw "Le sezioni delle aree non si trovano." }
  $commonEnd = if ($private.Y -gt $common.Y) { $private.Y } else { 100000 }
  $privateEnd = if ($common.Y -gt $private.Y) { $common.Y } else { 100000 }
  $c = Find-SecNode $ui "test-comune" -Below $common.Y -Above $commonEnd
  $p = Find-SecNode $ui "test" -Below $private.Y -Above $privateEnd
  if (-not $c -or -not $p) { throw "Le share di prova non sono nelle aree." }
  Invoke-SecTap $Serial $c
  Invoke-SecTap $Serial $p
  Invoke-SecContinue $Serial
}
