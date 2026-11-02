# Il copione del RESET DELLA FAMIGLIA DI PROVA (decisione 1 dell'utente del 02/11): dalla Libreria (o dal benvenuto) a "Crea la famiglia"
# con l'account `test`, aree `test` e `test-comune`. Percorso: Impostazioni, Famiglia, "Rientra con un QR nuovo", "Esci dalla famiglia e
# rientra", "Esci e rientra" (foglio della nostra app), poi l'onboarding dell'admin (docs/fase2-progetto.md par. 2.1). Alla password lo
# script scrive solo il nome utente e ASPETTA l'utente; al foglio dello sblocco della chiave (M3) aspetta l'utente sull'S25, scrive il PIN
# di prova sull'emulatore. Il codice del kit nuovo va in $script:SecCtx.KitFile (fuori da git) e si riscrive nell'app per verificarlo.
# Se il dispositivo e' admin della famiglia di prima lo script si FERMA: la scelta (uscire perdendo la chiave o "Reimposta la famiglia") e'
# dell'utente. Nessun dialogo di sistema si conferma: i permessi si danno prima con `pm grant`, un dialogo che compare si chiude con Indietro.

function Get-SecCopioneFamiglia {
  $steps = @()
  $steps += New-SecStep "sblocco-chiave" { param($ui) Test-SecUnlockSheet $ui } {
    param($ui)
    if ($script:SecCtx.Emulator) {
      Invoke-SecType $script:SecCtx.Serial $SecEmulatorTestPin
      Invoke-SecKey $script:SecCtx.Serial "KEYCODE_ENTER"
      "avanti"
    } else { "attendi-utente" }
  } "sull'S25 appoggia il dito (o scrivi il tuo PIN) nel foglio di sblocco della chiave di famiglia."
  $steps += New-SecStep "dialogo-di-sistema" { param($ui) Test-SecSystemDialog $ui } { param($ui) Invoke-SecKey $script:SecCtx.Serial "KEYCODE_BACK"; "avanti" }
  $steps += New-SecStep "admin-di-prima" { param($ui) Test-SecUiHas $ui (U 'Questo dispositivo \u00e8 un admin') } {
    param($ui) "fermo:il dispositivo e' admin della famiglia di prima: decide l'utente (Reimposta la famiglia, oppure uscire e perdere la chiave)"
  }
  $steps += New-SecStep "esci-foglio" { param($ui) Test-SecUiHas $ui "Esci dalla famiglia?" } {
    param($ui) [void](Invoke-SecTapLabel $script:SecCtx.Serial "Esci e rientra" -Scroll 0); "avanti"
  }
  $steps += New-SecStep "rientra-pagina" { param($ui) Test-SecUiHas $ui "Esci dalla famiglia e rientra" } {
    param($ui) [void](Invoke-SecTapLabel $script:SecCtx.Serial "Esci dalla famiglia e rientra"); "avanti"
  }
  $steps += New-SecStep "famiglia" { param($ui) (Test-SecUiHas $ui "Famiglia") -and ((Test-SecUiHas $ui "Zona delicata") -or (Test-SecUiHas $ui "Rientra con un QR nuovo") -or (Test-SecUiHas $ui "Nessuna famiglia")) } {
    param($ui) if (Invoke-SecTapLabel $script:SecCtx.Serial "Rientra con un QR nuovo") { "avanti" } else { "fermo:manca Rientra con un QR nuovo" }
  }
  $steps += New-SecStep "impostazioni" { param($ui) (Test-SecUiHas $ui "Impostazioni") -and (Test-SecUiHas $ui "Famiglia") -and (Test-SecUiHas $ui "Dispositivi") } {
    param($ui) [void](Invoke-SecTapLabel $script:SecCtx.Serial "Famiglia"); "avanti"
  }
  $steps += New-SecStep "libreria" { param($ui) (Test-SecUiHas $ui "Libreria") -and (Test-SecUiHas $ui "Impostazioni") } {
    param($ui) Invoke-SecTap $script:SecCtx.Serial (Find-SecNode $ui "Impostazioni"); "avanti"
  }
  $steps += New-SecStep "benvenuto" { param($ui) Test-SecUiHas $ui "Crea la famiglia" } { param($ui) Invoke-SecTap $script:SecCtx.Serial (Find-SecNode $ui "Crea la famiglia"); "avanti" }
  $steps += New-SecStep "casa" { param($ui) Test-SecUiHas $ui (U 'S\u00ec, \u00e8 casa') } { param($ui) Invoke-SecTap $script:SecCtx.Serial (Find-SecNode $ui (U 'S\u00ec, \u00e8 casa')); "avanti" }
  $steps += New-SecStep "indirizzo" { param($ui) Test-SecUiHas $ui "Controlla il NAS" } {
    param($ui)
    $field = Find-SecNode $ui "Indirizzo IP del NAS"
    if ($field -and -not (Test-SecUiHas $ui $script:SecCtx.NasIp)) { Invoke-SecTap $script:SecCtx.Serial $field; Invoke-SecType $script:SecCtx.Serial $script:SecCtx.NasIp }
    [void](Invoke-SecTapLabel $script:SecCtx.Serial "Controlla il NAS" -Scroll 2); "avanti"
  }
  $steps += New-SecStep "password" { param($ui) (Test-SecUiHas $ui "Accedi al NAS") -or (Test-SecUiHas $ui (U 'Account dell\u2019admin')) } {
    param($ui)
    $user = Find-SecNode $ui "Nome utente"
    if ($user -and -not $script:SecUserTyped -and -not (Test-SecUiHas $ui "test")) {
      Invoke-SecTap $script:SecCtx.Serial $user
      Invoke-SecType $script:SecCtx.Serial "test"
      $script:SecUserTyped = $true
    }
    "attendi-utente"
  } "scrivi la password di test nel campo Password e tocca 'Accedi al NAS'. Gli agenti non scrivono password."
  $steps += New-SecStep "aree" { param($ui) Test-SecUiHas $ui "Aree del NAS" } { param($ui) Select-SecAreas $script:SecCtx.Serial; "avanti" }
  $steps += New-SecStep "cartelle" { param($ui) Test-SecUiHas $ui "Cartelle da escludere" } { param($ui) Invoke-SecContinue $script:SecCtx.Serial; "avanti" }
  $steps += New-SecStep "sorgenti" { param($ui) Test-SecUiHas $ui "Da dove copiare" } {
    param($ui) [void](Invoke-SecTapLabel $script:SecCtx.Serial "Nessun backup automatico" -Scroll 3); Invoke-SecContinue $script:SecCtx.Serial; "avanti"
  }
  $steps += New-SecStep "ruoli" { param($ui) Test-SecUiHas $ui "Che lavoro fa questo dispositivo" } { param($ui) Invoke-SecContinue $script:SecCtx.Serial; "avanti" }
  $steps += New-SecStep "preset" { param($ui) Test-SecUiHas $ui (U 'Quando pu\u00f2 lavorare l\u2019app') } { param($ui) Invoke-SecContinue $script:SecCtx.Serial; "avanti" }
  $steps += New-SecStep "permessi" { param($ui) Test-SecUiHas $ui "Permessi di Android" } { param($ui) Invoke-SecContinue $script:SecCtx.Serial; "avanti" }
  $steps += New-SecStep "batteria" { param($ui) Test-SecUiHas $ui "Il backup di notte" } {
    param($ui) if (-not (Invoke-SecTapLabel $script:SecCtx.Serial "Salta questo passo" -Scroll 3)) { Invoke-SecContinue $script:SecCtx.Serial }; "avanti"
  }
  $steps += New-SecStep "vpn" { param($ui) Test-SecUiHas $ui "VPN di casa" } {
    param($ui) if (-not (Invoke-SecTapLabel $script:SecCtx.Serial "Salta questo passo" -Scroll 3)) { Invoke-SecContinue $script:SecCtx.Serial }; "avanti"
  }
  $steps += New-SecStep "chiave" { param($ui) Test-SecUiHas $ui "Genera chiave e codice" } { param($ui) Invoke-SecTap $script:SecCtx.Serial (Find-SecNode $ui "Genera chiave e codice"); "avanti" }
  $steps += New-SecStep "codice" { param($ui) Test-SecUiHas $ui "Ho scritto il codice su carta" } {
    param($ui)
    $code = Get-SecRecoveryCode $ui
    if (-not $code) { return "fermo:il codice di recupero non si legge sullo schermo (va copiato a mano nel file del kit)" }
    $kit = $script:SecCtx.KitFile
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $kit) | Out-Null
    Set-Content -LiteralPath $kit -Encoding UTF8 -Value @(
      "Famiglia di prova (account test, aree test e test-comune): kit nuovo del $(Get-Date -Format 'dd/MM/yyyy HH:mm').",
      "Codice di recupero: $($code.Code)",
      "Impronta della famiglia: $($code.Fingerprint)",
      "Solo per la famiglia di prova, fuori da git. Il kit cifrato e' nell'area privata test, .mycloudgallery/recovery/.")
    $script:SecKitCode = ($code.Groups -join "")
    $script:SecKitWritten = $true
    [void](Invoke-SecTapLabel $script:SecCtx.Serial "Ho scritto il codice su carta" -Scroll 2)
    [void](Invoke-SecTapLabel $script:SecCtx.Serial "Fatto, continua" -Scroll 2)
    "avanti"
  }
  $steps += New-SecStep "riscrivi" { param($ui) Test-SecUiHas $ui "Verifica e salva il kit" } {
    param($ui)
    if (-not $script:SecKitCode) { return "fermo:codice di recupero non letto" }
    $field = Find-SecNode $ui "Codice di recupero"
    if ($field) { Invoke-SecTap $script:SecCtx.Serial $field }
    Invoke-SecType $script:SecCtx.Serial $script:SecKitCode
    [void](Invoke-SecTapLabel $script:SecCtx.Serial "Verifica e salva il kit" -Scroll 2)
    "avanti"
  }
  $steps += New-SecStep "attesa" { param($ui) (Test-SecUiHas $ui "Metto tutto al sicuro") -or (Test-SecUiHas $ui "Controllo il NAS" -Contains) -or (Test-SecUiHas $ui "Accedo" -Contains) -or (Test-SecUiHas $ui "Genero" -Contains) } { param($ui) "attendi" }
  $steps += New-SecStep "avviso" { param($ui) Test-SecUiHas $ui "Backup esterno e FRITZ!Box" } { param($ui) Invoke-SecContinue $script:SecCtx.Serial; "avanti" }
  $steps += New-SecStep "riepilogo" { param($ui) Test-SecUiHas $ui "Tutto pronto" } {
    param($ui)
    $script:SecSummaryShares = [int](Test-SecUiHas $ui "test-comune" -Contains) + [int](Test-SecUiHas $ui "test" -Contains)
    [void](Invoke-SecTapLabel $script:SecCtx.Serial "Vai alla Libreria" -Scroll 3)
    "fine"
  }
  return $steps
}
