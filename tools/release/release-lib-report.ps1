# Libreria di tools\release\release.ps1 (Fase 10, P12; docs/fase10-progetto.md par. 2.9 passi 7 e 8, checklist): resoconto e checklist.
# Si carica dopo release-lib-core.ps1. Solo ASCII, PowerShell 5.1. Niente di cio' che scrive contiene segreti: impronte e numeri pubblici.

# La checklist di rilascio del par. 2.9 (spuntata dall'orchestratore e dall'utente). Il testo e' quello del progetto, con l'ordine di li'.
function Get-ReleaseChecklist {
  return @(
    "Le Fasi 1-10 e la fase grafica chiuse con i loro 'Fatto quando'; voci delle revisioni chiuse o dichiarate (par. 2.11).",
    "Le prove sul campo in sospeso fatte o dichiarate 'non bloccanti' una per una dall'utente.",
    "Recinzioni nello stato che l'utente ha deciso (par. 2.10: il passo unico fatto, o rinviato) e recinzioni.json coerente.",
    "pampa.jks: copia offline verificata (si apre), impronta del certificato nel documento; il limite L559 letto dall'utente.",
    "Chiavi del manifest K1 e K2, foglio offline di K2, un manifest vuoto firmato e letto da un dispositivo di prova.",
    "GitHub con 2FA, token a grana fine valido, ramo protetto.",
    "docs/sicurezza.md rivisto per intero, par. 1 per la famiglia; condiviso con la famiglia (e il padre dell'utente).",
    "Account di prova test e test2 tolti dal NAS o con password lunghe; share di prova svuotate o tenute (decide l'utente).",
    "docs/sicurezza.md par. 9 'Da fare prima della Fase 1': stato di ogni voce scritto.",
    "Tabelle della promessa 15 trattate (faces_head_mark e faces_outbox: migrazione 2->3 dei volti); database della galleria senza migrazioni distruttive.",
    "Changelog in italiano semplice; versione e canale giusti.",
    "APK installato sull'S25 e sul tablet (profilo dell'admin) dal Pampa Store in beta; aggiornamento da una beta precedente provato (stessa firma).",
    "Due settimane di beta sui dispositivi dell'admin senza voci bloccanti aperte (congelamenti, errori di scrittura, crolli); poi stable per tutta la famiglia insieme.",
    "Il giorno della famiglia preparato: un QR per dispositivo dall'S25, l'admin approva ogni dispositivo nella stessa sessione, docs/sicurezza.md par. 1 gia' condiviso."
  )
}

# L'elenco delle dipendenze con le impronte, da gradle\verification-metadata.xml (le impronte che Gradle controlla a ogni build). E' tutto cio' che il
# progetto verifica, quindi un insieme che contiene quello dell'APK. Ritorna il numero di componenti scritti.
function Write-DependencyList([string]$MetadataPath, [string]$OutPath) {
  if (-not (Test-Path -LiteralPath $MetadataPath)) { throw "manca gradle\verification-metadata.xml" }
  [xml]$doc = Read-Utf8 $MetadataPath
  $lines = New-Object System.Collections.Generic.List[string]
  $lines.Add("# Dipendenze verificate dal progetto (gradle/verification-metadata.xml) con l'impronta SHA-256 di ogni file.")
  $lines.Add("# gruppo:nome:versione  file  sha256   (e' un insieme che contiene quello dell'APK: Gradle verifica anche cio' che non entra nella release)")
  $count = 0
  foreach ($component in @($doc.SelectNodes("//*[local-name()='component']"))) {
    $count++
    foreach ($artifact in @($component.SelectNodes("*[local-name()='artifact']"))) {
      $sha = $artifact.SelectSingleNode("*[local-name()='sha256']")
      if ($sha) {
        $lines.Add(("{0}:{1}:{2}  {3}  {4}" -f $component.GetAttribute("group"), $component.GetAttribute("name"), $component.GetAttribute("version"), $artifact.GetAttribute("name"), $sha.GetAttribute("value")))
      }
    }
  }
  [System.IO.File]::WriteAllLines($OutPath, $lines)
  return $count
}

# Il testo del Pampa Store dalla sezione di CHANGELOG.md: i titoli diventano "Titolo:" e le frasi restano elenchi.
function ConvertTo-StoreText([string]$SectionText) {
  $out = New-Object System.Collections.Generic.List[string]
  foreach ($line in ($SectionText -split "`n")) {
    if ($line -match '^##\s+\S') { continue }
    if ($line -match '^###\s+(.+?)\s*$') { if ($out.Count -gt 0) { $out.Add("") }; $out.Add($Matches[1] + ":"); continue }
    if ($line.Trim().Length -gt 0) { $out.Add($line.TrimEnd()) }
  }
  return ($out -join "`n")
}

# Il passo 7: scrive in $OutDir l'APK, l'impronta, il resoconto, le dipendenze, il testo del Pampa Store, il mapping di R8 (resta sul PC) e i dati
# per la prova a secco (passo 8). $Test = una prova con un keystore di prova: lo dice in testa a ogni file e nel JSON.
function New-ReleaseOutput {
  param([string]$OutDir, [string]$Apk, $Facts, [string]$Version, [string]$Channel, [string]$SectionText, [string]$Commit, [bool]$Test,
        [string[]]$Passed, [string]$MappingFile, [string]$MetadataPath, [string]$Today)
  if (Test-Path -LiteralPath $OutDir) { Remove-Item -LiteralPath $OutDir -Recurse -Force }
  New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
  $name = "MyCloudGallery-$Version.apk"
  $target = Join-Path $OutDir $name
  Copy-Item -LiteralPath $Apk -Destination $target
  $sha = Get-FileSha256 $target
  [System.IO.File]::WriteAllText((Join-Path $OutDir "$name.sha256"), "$sha  $name`n", (New-Object System.Text.UTF8Encoding($false)))
  $store = ConvertTo-StoreText $SectionText
  [System.IO.File]::WriteAllText((Join-Path $OutDir "changelog-store.txt"), $store + "`n", (New-Object System.Text.UTF8Encoding($false)))
  $dependencies = Write-DependencyList $MetadataPath (Join-Path $OutDir "dipendenze.txt")
  $mappingState = "non copiato (nessun mapping.txt: prova senza build)"
  if ($MappingFile -and (Test-Path -LiteralPath $MappingFile)) {
    New-Item -ItemType Directory -Path (Join-Path $OutDir "mapping") -Force | Out-Null
    Copy-Item -LiteralPath $MappingFile -Destination (Join-Path $OutDir "mapping\mapping.txt")
    $mappingState = "mapping\mapping.txt (resta sul PC: non si pubblica mai)"
  }
  $banner = if ($Test) { "PROVA CON UN KEYSTORE DI PROVA: NON E' UNA RELEASE, NON SI PUBBLICA." } else { "Release firmata con pampa.jks." }
  $report = New-Object System.Collections.Generic.List[string]
  $report.Add("MyCloud Gallery - resoconto della release $Version ($Channel), $Today")
  $report.Add($banner)
  $report.Add("")
  $report.Add("Commit:            $Commit")
  $report.Add("Versione:          $Version  (versionCode $($Facts.VersionCode))")
  $report.Add("Canale:            $Channel")
  $report.Add("Id:                $($Facts.Package)   (minSdk $($Facts.MinSdk), targetSdk $($Facts.TargetSdk))")
  $report.Add("APK:               $name")
  $report.Add("Dimensione:        $($Facts.SizeBytes) byte")
  $report.Add("SHA-256 dell'APK:  $sha")
  $report.Add("Certificato (SHA-256): $($Facts.CertSha256)")
  $report.Add("Chiave:            $($Facts.KeyAlgorithm) $($Facts.KeyBits) bit; schemi: v1=$($Facts.V1) v2=$($Facts.V2) v3=$($Facts.V3); firmatari: $($Facts.Signers)")
  $report.Add("Componenti:        $($Facts.ComponentCount); esportati: " + (($Facts.Exported | ForEach-Object { $_.Replace('dev.pampa.mycloudgallery.', '') }) -join ", "))
  $report.Add("Permessi:          " + (($Facts.Permissions | ForEach-Object { $_.Substring($_.LastIndexOf('.') + 1) }) -join ", "))
  $report.Add("Librerie native:   $($Facts.NativeLibs) (arm64-v8a, non compresse, allineate a 16 KB)")
  $report.Add("Dipendenze:        $dependencies componenti verificati (dipendenze.txt)")
  $report.Add("Mapping di R8:     $mappingState")
  $report.Add("")
  $report.Add("Controlli passati in questo giro:")
  foreach ($line in $Passed) { $report.Add("  - $line") }
  [System.IO.File]::WriteAllLines((Join-Path $OutDir "rapporto.txt"), $report)
  [System.IO.File]::WriteAllLines((Join-Path $OutDir "checklist.txt"), @(Get-ReleaseChecklist | ForEach-Object { "[ ] $_" }))
  $data = [ordered]@{
    prova = $Test; dryRun = $true; package = $Facts.Package; versionName = $Version; versionCode = $Facts.VersionCode; channel = $Channel
    apk = $target; sha256 = $sha; certSha256 = $Facts.CertSha256; changelogFile = (Join-Path $OutDir "changelog-store.txt")
  }
  [System.IO.File]::WriteAllText((Join-Path $OutDir "pampa-store.json"), (($data | ConvertTo-Json) + "`n"), (New-Object System.Text.UTF8Encoding($false)))
  return [pscustomobject]@{ Dir = $OutDir; Apk = $target; Sha256 = $sha; Dependencies = $dependencies }
}

# release.ps1 -SegnaPubblicata: la riga `prevista` diventa `pubblicata` con la data e l'impronta dell'APK del resoconto.
function Set-VersionPublished([string]$VersionsPath, [string]$Version, [string]$Channel, [string]$Today, [string]$Sha256) {
  $text = Read-Utf8 $VersionsPath
  $eol = if ($text.Contains("`r`n")) { "`r`n" } else { "`n" }
  $lines = $text -split '\r?\n'
  $done = $false
  for ($i = $lines.Count - 1; $i -ge 0; $i--) {
    $line = $lines[$i].Trim()
    if ($line.Length -eq 0 -or $line.StartsWith("#")) { continue }
    $c = @($line -split '\s+')
    if ($c.Count -eq 6 -and $c[0] -eq $Version -and $c[2] -eq "prevista" -and $c[3] -eq $Channel) {
      $lines[$i] = ("{0}  {1}  {2}  {3}  {4}  {5}" -f $c[0], $c[1], "pubblicata", $c[3], $Today, $Sha256)
      $done = $true
    }
    break
  }
  if (-not $done) { throw "l'ultima riga di versions.txt non e' la versione $Version prevista per il canale $Channel" }
  [System.IO.File]::WriteAllText($VersionsPath, ($lines -join $eol), (New-Object System.Text.UTF8Encoding($false)))
}

# release.ps1 -Prepara: aggiunge in fondo la riga `prevista`. Il versionCode e' il massimo + 1, tranne il passaggio da beta a stable della stessa
# versione (stesso numero: e' lo stesso APK, D33).
function Add-PreparedVersion([string]$VersionsPath, [string]$Version, [string]$Channel) {
  $parsed = Read-VersionRows $VersionsPath
  if ($parsed.Problems.Count -gt 0) { throw ("release/versions.txt non e' valido: " + ($parsed.Problems -join "; ")) }
  $rows = @($parsed.Rows)
  if ($rows.Count -gt 0 -and $rows[$rows.Count - 1].State -eq "prevista") { throw "l'ultima riga di versions.txt e' gia' una versione prevista ($($rows[$rows.Count - 1].Name)): si completa o si toglie a mano" }
  $max = 0
  foreach ($row in $rows) { if ($row.Code -gt $max) { $max = $row.Code } }
  $code = $max + 1
  if ($rows.Count -gt 0) {
    $last = $rows[$rows.Count - 1]
    if ($last.Name -eq $Version -and $last.Channel -eq "beta" -and $Channel -eq "stable") { $code = $last.Code }
  }
  $text = Read-Utf8 $VersionsPath
  $eol = if ($text.Contains("`r`n")) { "`r`n" } else { "`n" }
  if (-not $text.EndsWith("`n")) { $text += $eol }
  $text += ("{0}  {1}  {2}  {3}  {4}  {5}" -f $Version, $code, "prevista", $Channel, "-", "-") + $eol
  [System.IO.File]::WriteAllText($VersionsPath, $text, (New-Object System.Text.UTF8Encoding($false)))
  return $code
}
