# Le schermate FINTE dell'app per le prove dei copioni contro l'adb finto (Fase 10 P11, seconda parte; tools\security\sicurezza-finto.ps1).
# Ogni schermata e' un XML come quello di `uiautomator dump`, con gli attributi che l'adb finto capisce (fake-tap, fake-testo, fake-auto,
# fake-dopo, fake-indietro, fake-invio). I testi sono quelli delle risorse dell'app (i non ASCII scritti con \uXXXX).

function U([string]$Text) { return [regex]::Unescape($Text) }

# $Nodes: elenco di @{ T = testo; D = descrizione; Tap = schermata dopo il tocco; Pkg = pacchetto; Id = resource-id }.
function New-FakeScreen([string]$Dir, [string]$Name, [object[]]$Nodes, [hashtable]$Root = @{}) {
  $doc = New-Object System.Xml.XmlDocument
  [void]$doc.AppendChild($doc.CreateXmlDeclaration("1.0", "UTF-8", "yes"))
  $h = $doc.CreateElement("hierarchy")
  $h.SetAttribute("rotation", "0")
  foreach ($k in $Root.Keys) { $h.SetAttribute($k, $Root[$k]) }
  [void]$doc.AppendChild($h)
  $y = 80
  foreach ($n in $Nodes) {
    $e = $doc.CreateElement("node")
    $e.SetAttribute("index", "0")
    $e.SetAttribute("text", $(if ($n.T) { $n.T } else { "" }))
    $e.SetAttribute("resource-id", $(if ($n.Id) { $n.Id } else { "" }))
    $e.SetAttribute("class", "android.view.View")
    $e.SetAttribute("package", $(if ($n.Pkg) { $n.Pkg } else { "dev.pampa.mycloudgallery.debug" }))
    $e.SetAttribute("content-desc", $(if ($n.D) { $n.D } else { "" }))
    $e.SetAttribute("clickable", $(if ($n.Tap) { "true" } else { "false" }))
    $e.SetAttribute("checked", "false")
    $e.SetAttribute("password", $(if ($n.P) { "true" } else { "false" }))
    $e.SetAttribute("bounds", "[0,$y][1000,$($y + 100)]")
    if ($n.Tap) { $e.SetAttribute("fake-tap", $n.Tap) }
    [void]$h.AppendChild($e)
    $y += 120
  }
  $path = Join-Path $Dir "$Name.xml"
  $doc.Save($path)
}

function N([string]$T, [string]$Tap = $null, [string]$D = $null, [string]$Pkg = $null, [string]$Id = $null) { return @{ T = $T; Tap = $Tap; D = $D; Pkg = $Pkg; Id = $Id } }

# Il percorso del reset della famiglia sull'S25 finto: dalla Libreria al riepilogo. Con -Admin la pagina "Rientra" dice che il dispositivo e'
# admin (il copione si deve fermare); con -CodiceIlleggibile il codice non si legge (il copione si deve fermare senza scrivere il kit).
function New-FakeFamilyScreens([string]$UiDir, [switch]$Admin, [switch]$CodiceIlleggibile) {
  $d = Join-Path $UiDir "schermate"
  New-Item -ItemType Directory -Force -Path $d | Out-Null
  New-FakeScreen $d "libreria" @((N "Libreria"), (N $null "impostazioni" "Impostazioni"), (N "Cerca" "cerca"))
  New-FakeScreen $d "impostazioni" @((N "Impostazioni"), (N "Famiglia" "famiglia"), (N "Dispositivi" "dispositivi"), (N "Diagnostica" "diagnostica")) @{ "fake-indietro" = "libreria" }
  New-FakeScreen $d "famiglia" @((N "Famiglia"), (N "Zona delicata"), (N "Rientra con un QR nuovo" "rientra"))
  $rientra = @((N "Rientra con un QR nuovo"), (N "Esci dalla famiglia e rientra" "foglio"))
  if ($Admin) { $rientra += (N (U 'Questo dispositivo \u00e8 un admin')) }
  New-FakeScreen $d "rientra" $rientra
  New-FakeScreen $d "foglio" @((N "Esci dalla famiglia?"), (N "Esci e rientra" "benvenuto"), (N "Annulla" "rientra"))
  New-FakeScreen $d "benvenuto" @((N "Benvenuto"), (N "Crea la famiglia" "casa"), (N "Entra con il QR"))
  New-FakeScreen $d "casa" @((N (U '\u00c8 la rete di casa?')), (N (U 'S\u00ec, \u00e8 casa') "indirizzo"), (N "No"))
  New-FakeScreen $d "indirizzo" @((N "Il NAS di famiglia"), (N "Indirizzo IP del NAS"), (N "Controlla il NAS" "password"))
  # La password: lo script scrive solo "test" nel nome utente e aspetta; "l'utente" la scrive dopo 3 letture dello schermo.
  New-FakeScreen $d "password" @((N (U 'Account dell\u2019admin')), (N "Nome utente"), (N "Password" $null $null $null "pwd"), (N "Accedi al NAS")) @{ "fake-auto" = "accedo"; "fake-dopo" = "3" }
  New-FakeScreen $d "accedo" @((N (U 'Accedo\u2026'))) @{ "fake-auto" = "aree"; "fake-dopo" = "1" }
  New-FakeScreen $d "aree" @((N "Aree del NAS"), (N "Area comune"), (N "test"), (N "test-comune"), (N "Area privata"), (N "test"), (N "test-comune"), (N "Continua" "cartelle"))
  New-FakeScreen $d "cartelle" @((N "Cartelle da escludere"), (N "Continua" "sorgenti"))
  New-FakeScreen $d "sorgenti" @((N "Da dove copiare"), (N "Nessun backup automatico"), (N "Continua" "ruoli"))
  New-FakeScreen $d "ruoli" @((N "Che lavoro fa questo dispositivo"), (N "Continua" "preset"))
  New-FakeScreen $d "preset" @((N (U 'Quando pu\u00f2 lavorare l\u2019app')), (N "Continua" "permessi"))
  New-FakeScreen $d "permessi" @((N "Permessi di Android"), (N "Continua" "dialogo"))
  # Un dialogo di sistema che compare lo stesso: il copione lo chiude con Indietro, non lo conferma.
  New-FakeScreen $d "dialogo" @((N "Consentire a MyCloud Gallery di accedere a foto e video?" $null $null "com.google.android.permissioncontroller"), (N "Consenti" "nonqui" $null "com.google.android.permissioncontroller")) @{ "fake-indietro" = "batteria" }
  New-FakeScreen $d "nonqui" @((N "QUESTO DIALOGO DI SISTEMA NON SI CONFERMA"))
  New-FakeScreen $d "batteria" @((N "Il backup di notte"), (N "Salta questo passo" "vpn"))
  New-FakeScreen $d "vpn" @((N "VPN di casa"), (N "Salta questo passo" "chiave"))
  New-FakeScreen $d "chiave" @((N "Chiave di famiglia"), (N "Genera chiave e codice" "sblocco"))
  New-FakeScreen $d "sblocco" @((N "Conferma con la credenziale del telefono" $null $null "com.android.systemui"), (N "Usa il PIN" $null $null "com.android.systemui")) @{ "fake-auto" = "codice"; "fake-dopo" = "2" }
  $groups = if ($CodiceIlleggibile) { @() } else { @("ABCD", "EFGH", "JKMN", "PQRS", "TVWX", "YZ23", "4567") | ForEach-Object { N $_ } }
  New-FakeScreen $d "codice" (@((N "Il codice di recupero")) + $groups + @((N "Impronta della famiglia"), (N "abcde fghij klmno pqrst uvwxy z"), (N "Ho scritto il codice su carta"), (N "Fatto, continua" "riscrivi")))
  New-FakeScreen $d "riscrivi" @((N "Riscrivilo per intero"), (N "Codice di recupero"), (N "Verifica e salva il kit" "sicuro"))
  New-FakeScreen $d "sicuro" @((N "Metto tutto al sicuro")) @{ "fake-auto" = "avviso"; "fake-dopo" = "2" }
  New-FakeScreen $d "avviso" @((N "Backup esterno e FRITZ!Box"), (N "Continua" "riepilogo"))
  New-FakeScreen $d "riepilogo" @((N "Riepilogo"), (N "Tutto pronto"), (N "Area privata: test"), (N "Area comune: test-comune"), (N "Vai alla Libreria" "libreria"))
  Set-Content -LiteralPath (Join-Path $UiDir "stato.txt") -Value "libreria"
}

# Le pagine delle voci sull'app finta: Impostazioni, Dispositivi (approva e revoca), Diagnostica con l'anteprima dell'esportazione, Cerca.
function New-FakeItemScreens([string]$UiDir, [string]$DiagLines = '{"code": "INDEX_REJECTED", "export": {"rejected.SIGNER_NOT_APPROVED": 0}}') {
  $d = Join-Path $UiDir "schermate"
  New-Item -ItemType Directory -Force -Path $d | Out-Null
  New-FakeScreen $d "libreria" @((N "Libreria"), (N $null "impostazioni" "Impostazioni"), (N "Cerca" "cerca"))
  New-FakeScreen $d "impostazioni" @((N "Impostazioni"), (N "Famiglia"), (N "Dispositivi" "dispositivi"), (N "Diagnostica" "diagnostica")) @{ "fake-indietro" = "libreria" }
  New-FakeScreen $d "diagnostica" @((N "Diagnostica"), (N "Esporta la diagnostica" "esporta")) @{ "fake-indietro" = "impostazioni" }
  $lines = @((N "Esporta la diagnostica")) + @($DiagLines -split "`n" | ForEach-Object { N $_ })
  New-FakeScreen $d "esporta" $lines @{ "fake-indietro" = "diagnostica" }
  New-FakeScreen $d "cerca" @((N "Cerca"), (N $null $null "Cerca nella galleria" $null "search_field")) @{ "fake-invio" = "risultati"; "fake-indietro" = "libreria" }
  New-FakeScreen $d "risultati" @((N "Cerca"), (N "Nessun risultato")) @{ "fake-indietro" = "libreria" }
  Set-Content -LiteralPath (Join-Path $UiDir "stato.txt") -Value "libreria"
}

# I dispositivi di prova sull'app finta: Dispositivi, l'approvazione con il codice e il foglio di sblocco (che "l'utente" fa da solo dopo due
# letture), poi la revoca con il suo foglio. Le pagine dell'app restano raggiungibili da Impostazioni.
function New-FakeDeviceScreens([string]$UiDir) {
  $d = Join-Path $UiDir "schermate"
  New-FakeScreen $d "impostazioni" @((N "Impostazioni"), (N "Famiglia" "famiglia"), (N "Dispositivi" "dispositivi"), (N "Diagnostica" "diagnostica")) @{ "fake-indietro" = "libreria" }
  New-FakeScreen $d "dispositivi" @((N "Dispositivi"), (N "MCG prova approvato" "approva-a"), (N "Da approvare"), (N "MCG prova revocato" "approva-r")) @{ "fake-indietro" = "impostazioni" }
  foreach ($r in @("a", "r")) {
    $name = if ($r -eq "a") { "MCG prova approvato" } else { "MCG prova revocato" }
    New-FakeScreen $d "approva-$r" @((N "Nuovo dispositivo"), (N $name), (N (U 'L\u2019account \u00abtest\u00bb \u00e8 giusto')), (N "Digita il codice"), (N "Codice del dispositivo"), (N "Approva questo dispositivo" "sblocco-$r")) @{ "fake-indietro" = "dispositivi" }
    New-FakeScreen $d "sblocco-$r" @((N "Approvare il dispositivo?" $null $null "com.android.systemui"), (N "Conferma con la credenziale del telefono" $null $null "com.android.systemui")) @{ "fake-auto" = "fatto-$r"; "fake-dopo" = "2" }
    New-FakeScreen $d "fatto-$r" @((N "Fatto"), (N $name), (N "Approvato")) @{ "fake-indietro" = "dispositivi-$r" }
  }
  New-FakeScreen $d "dispositivi-a" @((N "Dispositivi"), (N "MCG prova approvato"), (N "Approvato"), (N "MCG prova revocato" "approva-r")) @{ "fake-indietro" = "impostazioni" }
  New-FakeScreen $d "dispositivi-r" @((N "Dispositivi"), (N "MCG prova approvato"), (N "MCG prova revocato" "pagina-rv")) @{ "fake-indietro" = "impostazioni" }
  New-FakeScreen $d "pagina-rv" @((N "MCG prova revocato"), (N (U 'Revoca l\u2019accesso') "foglio-rv")) @{ "fake-indietro" = "dispositivi-r" }
  New-FakeScreen $d "foglio-rv" @((N (U 'Revocare \u00abMCG prova revocato\u00bb?')), (N "Revoca" "sblocco-rv"), (N "Annulla" "pagina-rv"))
  New-FakeScreen $d "sblocco-rv" @((N "Conferma con la credenziale del telefono" $null $null "com.android.systemui")) @{ "fake-auto" = "revocato"; "fake-dopo" = "2" }
  New-FakeScreen $d "revocato" @((N "MCG prova revocato"), (N "Revocato")) @{ "fake-indietro" = "dispositivi-r" }
}
