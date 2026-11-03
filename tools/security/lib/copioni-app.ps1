# I copioni brevi delle voci (Fase 10 P11, seconda parte): l'esportazione della Diagnostica letta dall'anteprima (senza salvarla: niente
# selettore di sistema), Cerca, l'approvazione e la revoca di un dispositivo di prova (Impostazioni, Dispositivi), lo scongelamento del NAS
# ("Ho controllato, continua" con il numero). Leggono lo schermo con UI Automator e restituiscono numeri; il testo letto resta in memoria.

# Dalla schermata in cui si e' (Libreria o una pagina delle Impostazioni) alla riga [Row] delle Impostazioni.
function Open-SecSettingsRow([string]$Serial, [string]$Row) {
  for ($i = 0; $i -lt 6; $i++) {
    $ui = Get-SecUi $Serial
    if ((Test-SecUiHas $ui "Impostazioni") -and (Test-SecUiHas $ui $Row)) { if (Invoke-SecTapLabel $Serial $Row) { return $true } }
    $settings = Find-SecNode $ui "Impostazioni"
    # Nella Libreria il pulsante Impostazioni non c'e': sta nell'intestazione di Raccolte (corretto sul campo il 03/11).
    $tab = if (-not $settings -and (Test-SecUiHas $ui "Libreria") -and (Test-SecUiHas $ui "Raccolte")) { Find-SecNode $ui "Raccolte" } else { $null }
    if ($settings) { Invoke-SecTap $Serial $settings } elseif ($tab) { Invoke-SecTap $Serial $tab } else { Invoke-SecKey $Serial "KEYCODE_BACK" }
    Start-Sleep -Seconds 2
  }
  return $false
}

function ConvertFrom-SecDiagLine([string]$Line) {
  $code = [regex]::Match($Line, '"code"\s*:\s*"([A-Z0-9_]+)"')
  if (-not $code.Success) { return $null }
  $values = [ordered]@{}
  foreach ($m in [regex]::Matches($Line, '"([A-Za-z0-9_.]+)"\s*:\s*(-?[0-9]+)\b')) { $values[$m.Groups[1].Value] = [long]$m.Groups[2].Value }
  return [pscustomobject]@{ Code = $code.Groups[1].Value; Values = $values }
}

# L'esportazione della Diagnostica dall'anteprima ("Esporta la diagnostica"), riga per riga scorrendo; poi Indietro (non si salva niente).
function Read-SecDiagnostics([string]$Serial, [string]$LogDir) {
  if (-not (Open-SecSettingsRow $Serial "Diagnostica")) { throw "La Diagnostica non si apre." }
  Start-Sleep -Seconds 2
  if (-not (Invoke-SecTapLabel $Serial "Esporta la diagnostica" -Scroll 8)) { throw "Manca 'Esporta la diagnostica'." }
  Start-Sleep -Seconds 2
  $seen = New-Object System.Collections.Generic.List[string]
  $quiet = 0
  for ($i = 0; $i -lt 60 -and $quiet -lt 3; $i++) {
    $ui = Get-SecUi $Serial
    $added = 0
    foreach ($n in $ui) { foreach ($t in @($n.Text, $n.Desc)) { if ($t -and $t.Contains('"') -and -not $seen.Contains($t)) { $seen.Add($t); $added++ } } }
    if ($added -eq 0) { $quiet++ } else { $quiet = 0 }
    [void](Invoke-SecShell $Serial @("input", "swipe", "500", "1700", "500", "600", "250"))
  }
  Invoke-SecKey $Serial "KEYCODE_BACK"
  $items = @()
  foreach ($t in $seen) { foreach ($line in ($t -split "`n")) { $item = ConvertFrom-SecDiagLine $line; if ($item) { $items += $item } } }
  return [pscustomobject]@{ Items = $items; Text = ($seen -join "`n"); Lines = $seen.Count }
}

# La somma dei valori numerici delle voci [Codes] (per esempio i rifiuti per motivo), facoltativamente solo le chiavi che iniziano per [Prefix].
function Get-SecDiagSum($Diag, [string[]]$Codes, [string]$Prefix = "") {
  $sum = 0
  foreach ($item in $Diag.Items) {
    if ($item.Code -notin $Codes) { continue }
    foreach ($k in $item.Values.Keys) { if (-not $Prefix -or $k.StartsWith($Prefix)) { $sum += $item.Values[$k] } }
  }
  return $sum
}

function Test-SecDiagHas($Diag, [string]$Code) { return (@($Diag.Items | Where-Object { $_.Code -eq $Code }).Count -gt 0) }

# Cerca: la scheda "Cerca", il campo, la parola, Invio; poi si contano le celle dei risultati (descrizioni "Foto, ..." e "Video, ...", D22).
function Invoke-SecSearch([string]$Serial, [string]$Query) {
  $tab = $null
  for ($i = 0; $i -lt 5 -and -not $tab; $i++) {
    $tab = Find-SecNode (Get-SecUi $Serial) "Cerca"
    if (-not $tab) { Invoke-SecKey $Serial "KEYCODE_BACK"; Start-Sleep -Seconds 1 }
  }
  if (-not $tab) { throw "Manca la scheda Cerca." }
  Invoke-SecTap $Serial $tab
  Start-Sleep -Seconds 2
  $ui = Get-SecUi $Serial
  $field = @($ui | Where-Object { $_.Id -match 'search|query|cerca' -or $_.Desc -match '^Cerca' -or $_.Text -match '^Cerca ' }) | Select-Object -First 1
  if ($field) { Invoke-SecTap $Serial $field }
  Invoke-SecType $Serial $Query
  Invoke-SecKey $Serial "KEYCODE_ENTER"
  Start-Sleep -Seconds 4
  $ui = Get-SecUi $Serial
  $cells = @($ui | Where-Object { $_.Desc -match '^(Foto|Video),' }).Count
  $mentions = @($ui | Where-Object { ($_.Text + " " + $_.Desc).IndexOf($Query, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and $_.Id -notmatch 'search|query' }).Count
  Invoke-SecKey $Serial "KEYCODE_BACK"
  return [pscustomobject]@{ Cells = $cells; Mentions = [math]::Max(0, $mentions - 1) }
}

# Approva la richiesta del dispositivo di prova [Name] con il codice [Code] (Impostazioni, Dispositivi); il foglio dello sblocco e' dell'utente.
function Get-SecCopioneApprova([string]$Name, [string]$Code) {
  $script:SecApprove = @{ Name = $Name; Code = $Code; Typed = $false; Confirmed = $false; Mode = $false; Tapped = $false; Attempts = 0; Retap = 0 }
  $steps = @()
  $steps += New-SecStep "sblocco" { param($ui) Test-SecUnlockSheet $ui } { param($ui) if ($script:SecCtx.Emulator) { Invoke-SecType $script:SecCtx.Serial $SecEmulatorTestPin; Invoke-SecKey $script:SecCtx.Serial "KEYCODE_ENTER"; "avanti" } else { "attendi-utente" } } "sull'S25 conferma con il dito (o il tuo PIN) l'approvazione del dispositivo di prova."
  $steps += New-SecStep "fatto" { param($ui) (Test-SecUiHas $ui "Fatto") -and (Test-SecUiHas $ui $script:SecApprove.Name -Contains) } { param($ui) Invoke-SecKey $script:SecCtx.Serial "KEYCODE_BACK"; "fine" }
  # La pagina "Nuovo dispositivo" (corretta sul campo il 03/11): il pulsante sta sotto la piega, la spunta dell'account e' sul contenitore, la
  # tastiera copre il pulsante, `input text` lungo perde caratteri. Un'azione per giro: spunta, "Digita il codice", codice letto e verificato,
  # tastiera chiusa, "Approva questo dispositivo"; poi il foglio dello sblocco (passo "sblocco", dell'utente).
  $steps += New-SecStep "approva" { param($ui) (Test-SecUiHas $ui "Nuovo dispositivo") -and ((Test-SecUiHas $ui "Digita il codice") -or (Test-SecUiHas $ui "Approva questo dispositivo")) } {
    param($ui)
    $s = $script:SecCtx.Serial
    $a = $script:SecApprove
    # Una pagina di una richiesta scaduta (15 minuti da quando l'app l'ha vista) non serve: si torna all'elenco, dove c'e' quella nuova.
    if (Test-SecUiHas $ui "Richiesta scaduta") { Invoke-SecKey $s "KEYCODE_BACK"; Start-Sleep -Seconds 2; return "attendi" }
    if (@($ui | Where-Object { $_.Text -like "MCG prova *" }).Count -gt 0 -and -not (Test-SecUiHas $ui $a.Name)) { return "fermo:la richiesta aperta non e' '$($a.Name)'" }
    if ($a.Tapped) { $a.Tapped = $false; $a.Retap++ ; if ($a.Retap -gt 3) { return "fermo:il foglio dello sblocco non e' comparso" } }
    if (-not $a.Confirmed) {
      $confirm = Find-SecNode $ui (U '\u00e8 giusto') -Contains   # la spunta, non la frase "...quello giusto" sopra
      if (-not $confirm) { [void](Invoke-SecShell $s @("input", "swipe", "500", "1500", "500", "900", "300")); return "attendi" }
      if (-not $confirm.Checked) { Invoke-SecTap $s $confirm }
      $a.Confirmed = $true; Start-Sleep -Seconds 1; return "attendi"
    }
    if (-not $a.Mode) {
      $mode = Find-SecNode $ui "Digita il codice"
      if ($mode) { Invoke-SecTap $s $mode; $a.Mode = $true; Start-Sleep -Seconds 1 } else { [void](Invoke-SecShell $s @("input", "swipe", "500", "1500", "500", "900", "300")) }
      return "attendi"
    }
    $want = ($a.Code -replace '-', '')
    $shown = @($ui | Where-Object { $_.Text -match '^[A-Z0-9]{2,4}(-[A-Z0-9]{1,4}){0,3}$' -and $_.Text -ne "AAAA-BBBB-CCCC-DDDD" } | ForEach-Object { $_.Text -replace '-', '' })
    if (-not $a.Typed -or (-not $env:MCG_ADB -and ($shown -notcontains $want))) {   # sull'adb finto il campo non mostra il testo: niente verifica
      if ($a.Attempts -ge 4) { return "fermo:il codice non si scrive giusto" }
      $field = Find-SecNode $ui "AAAA-BBBB-CCCC-DDDD"
      if (-not $field) { $field = Find-SecNode $ui "Codice del dispositivo" }
      if (-not $field -and $shown.Count -gt 0) { $field = $ui | Where-Object { $_.Text -match '^[A-Z0-9]{2,4}(-[A-Z0-9]{1,4}){0,3}$' } | Select-Object -First 1 }
      if (-not $field) { [void](Invoke-SecShell $s @("input", "swipe", "500", "1500", "500", "900", "300")); return "attendi" }
      Invoke-SecTap $s $field
      Start-Sleep -Milliseconds 800
      if ($env:MCG_ADB) { Invoke-SecType $s $a.Code } else { Invoke-SecTypeKeys $s $a.Code }
      $a.Typed = $true; $a.Attempts++
      Start-Sleep -Seconds 1
      return "attendi"
    }
    if (Test-SecImeShown $s) { Invoke-SecKey $s "KEYCODE_BACK"; Start-Sleep -Seconds 1; return "attendi" }
    $button = Find-SecNode $ui "Approva questo dispositivo"
    if (-not $button) { [void](Invoke-SecShell $s @("input", "swipe", "500", "1500", "500", "900", "300")); return "attendi" }
    Invoke-SecTap $s $button
    $a.Tapped = $true
    Start-Sleep -Seconds 2
    return "avanti"
  }
  $steps += New-SecStep "elenco" { param($ui) (Test-SecUiHas $ui "Dispositivi") -and (Test-SecUiHas $ui $script:SecApprove.Name -Contains) } { param($ui) [void](Invoke-SecTapLabel $script:SecCtx.Serial $script:SecApprove.Name -Contains); "avanti" }
  $steps += New-SecStep "impostazioni" { param($ui) (Test-SecUiHas $ui "Impostazioni") -and (Test-SecUiHas $ui "Dispositivi") } { param($ui) [void](Invoke-SecTapLabel $script:SecCtx.Serial "Dispositivi"); "avanti" }
  $steps += New-SecStep "altrove" { param($ui) Test-SecUiHas $ui "Impostazioni" } { param($ui) Invoke-SecTap $script:SecCtx.Serial (Find-SecNode $ui "Impostazioni"); "avanti" }
  $steps += New-SecStep "libreria" { param($ui) (Test-SecUiHas $ui "Libreria") -and (Test-SecUiHas $ui "Raccolte") } { param($ui) Invoke-SecTap $script:SecCtx.Serial (Find-SecNode $ui "Raccolte"); "avanti" }
  return $steps
}

# Revoca il dispositivo di prova [Name] (Impostazioni, Dispositivi, "Revoca l'accesso", foglio "Revoca"); il foglio dello sblocco e' dell'utente.
function Get-SecCopioneRevoca([string]$Name) {
  $script:SecRevoke = @{ Name = $Name }
  $steps = @()
  $steps += New-SecStep "sblocco" { param($ui) Test-SecUnlockSheet $ui } { param($ui) if ($script:SecCtx.Emulator) { Invoke-SecType $script:SecCtx.Serial $SecEmulatorTestPin; Invoke-SecKey $script:SecCtx.Serial "KEYCODE_ENTER"; "avanti" } else { "attendi-utente" } } "sull'S25 conferma con il dito (o il tuo PIN) la revoca del dispositivo di prova."
  $steps += New-SecStep "revocato" { param($ui) (Test-SecUiHas $ui "Revocato") -and (Test-SecUiHas $ui $script:SecRevoke.Name -Contains) } { param($ui) Invoke-SecKey $script:SecCtx.Serial "KEYCODE_BACK"; "fine" }
  $steps += New-SecStep "foglio" { param($ui) (Test-SecUiHas $ui "Revoca") -and (Test-SecUiHas $ui "Revocare" -Contains) } { param($ui) Invoke-SecTap $script:SecCtx.Serial (Find-SecNode $ui "Revoca"); "avanti" }
  $steps += New-SecStep "pagina" { param($ui) Test-SecUiHas $ui (U 'Revoca l\u2019accesso') } { param($ui) [void](Invoke-SecTapLabel $script:SecCtx.Serial (U 'Revoca l\u2019accesso')); "avanti" }
  $steps += New-SecStep "elenco" { param($ui) (Test-SecUiHas $ui "Dispositivi") -and (Test-SecUiHas $ui $script:SecRevoke.Name -Contains) } { param($ui) [void](Invoke-SecTapLabel $script:SecCtx.Serial $script:SecRevoke.Name -Contains); "avanti" }
  $steps += New-SecStep "impostazioni" { param($ui) (Test-SecUiHas $ui "Impostazioni") -and (Test-SecUiHas $ui "Dispositivi") } { param($ui) [void](Invoke-SecTapLabel $script:SecCtx.Serial "Dispositivi"); "avanti" }
  $steps += New-SecStep "altrove" { param($ui) Test-SecUiHas $ui "Impostazioni" } { param($ui) Invoke-SecTap $script:SecCtx.Serial (Find-SecNode $ui "Impostazioni"); "avanti" }
  $steps += New-SecStep "libreria" { param($ui) (Test-SecUiHas $ui "Libreria") -and (Test-SecUiHas $ui "Raccolte") } { param($ui) Invoke-SecTap $script:SecCtx.Serial (Find-SecNode $ui "Raccolte"); "avanti" }
  return $steps
}

# S9: "Ho controllato, continua" nella Diagnostica, poi il dialogo con il numero dei file da riscrivere (doppia conferma, D10). Con
# -Sbagliato scrive prima un numero sbagliato: il dialogo deve restare (il congelamento non si toglie).
function Invoke-SecUnfreeze([string]$Serial, [switch]$Sbagliato) {
  if (-not (Open-SecSettingsRow $Serial "Diagnostica")) { throw "La Diagnostica non si apre." }
  Start-Sleep -Seconds 2
  if (-not (Invoke-SecTapLabel $Serial "Ho controllato, continua" -Scroll 8)) { return [pscustomobject]@{ Dialog = $false; Wrong = 0; Done = $false } }
  Start-Sleep -Seconds 2
  $ui = Get-SecUi $Serial
  if (-not (Test-SecUiHas $ui "Riprendere il lavoro sul NAS?")) { return [pscustomobject]@{ Dialog = $false; Wrong = 0; Done = $false } }
  $numbers = @($ui | ForEach-Object { [regex]::Matches(($_.Text + " "), '\b([0-9][0-9.]*)\b') | ForEach-Object { $_.Groups[1].Value -replace '\.', '' } } | Where-Object { [int64]$_ -gt 0 })
  $count = if ($numbers.Count -gt 0) { [string]($numbers | Sort-Object { [int64]$_ } -Descending | Select-Object -First 1) } else { $null }
  if (-not $count) { throw "Il numero dei file non si legge nel dialogo." }
  $field = Find-SecNode $ui "Numero dei file"
  $wrong = 0
  if ($Sbagliato) {
    if ($field) { Invoke-SecTap $Serial $field }
    Invoke-SecType $Serial ([string]([int64]$count + 1))
    [void](Invoke-SecTapLabel $Serial "Ho controllato, continua" -Scroll 0)
    Start-Sleep -Seconds 2
    if (Test-SecUiHas (Get-SecUi $Serial) "Riprendere il lavoro sul NAS?") { $wrong = 1 }
    Invoke-SecKey $Serial "KEYCODE_BACK"
    return [pscustomobject]@{ Dialog = $true; Wrong = $wrong; Done = $false }
  }
  if ($field) { Invoke-SecTap $Serial $field }
  Invoke-SecType $Serial $count
  [void](Invoke-SecTapLabel $Serial "Ho controllato, continua" -Scroll 0)
  Start-Sleep -Seconds 2
  $after = Get-SecUi $Serial
  return [pscustomobject]@{ Dialog = $true; Wrong = 0; Done = -not (Test-SecUiHas $after "Riprendere il lavoro sul NAS?") }
}
