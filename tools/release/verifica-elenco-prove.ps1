<#
.SYNOPSIS
  Prove di tools\release\verifica-elenco.ps1 (pacchetto U, giro 1 delle critiche, US-1): ogni controllo ha un caso che lo fa scattare, su file finti
  sotto build\verifica-elenco\prove, senza rete e senza strumenti dell'SDK (lo scaricamento e i fatti dell'APK sono finti). Codice di uscita 0 solo
  se tutte le prove passano.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$script:Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
. (Join-Path $PSScriptRoot "release-lib-core.ps1")
. (Join-Path $PSScriptRoot "verifica-elenco-lib.ps1")

$work = Join-Path $script:Root "build\verifica-elenco\prove"
if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
New-Item -ItemType Directory -Path $work -Force | Out-Null
$utf8 = New-Object Text.UTF8Encoding($false)
$failures = New-Object System.Collections.Generic.List[string]
$count = 0

function Assert-That([string]$name, [bool]$condition, [string]$detail = "") {
  $script:count++
  if (-not $condition) { $script:failures.Add($name + $(if ($detail) { ": " + $detail } else { "" })); Write-Host ("  [KO] " + $name + $(if ($detail) { " -- " + $detail } else { "" })) -ForegroundColor Red }
  else { Write-Host ("  [ok] " + $name) -ForegroundColor DarkGray }
}

$owner = "Casual76"
$repo = "MyCloud-Gallery"
$fingerprint = "ab" * 32
$version = "1.1.0-beta.01"
$tag = "beta-mycloud-gallery-v$version"
$assetName = "mycloud-gallery-$version.apk"
$bytes = New-Object byte[] 5000
(New-Object Random 11).NextBytes($bytes)
$apk = Join-Path $work $assetName
[IO.File]::WriteAllBytes($apk, $bytes)
$apkSha = Get-FileSha256 $apk

# L'elenco come lo scrive publisher.py (canale beta), con o senza sha256; [Extra] cambia o aggiunge campi della voce.
function New-Elenco([string]$Name, [hashtable]$Extra = @{}, [switch]$WithSha, [string]$Channel = "beta", [string]$RepoName = $repo, [switch]$Twin) {
  $fields = [ordered]@{
    version = $version; releaseDate = "2026-11-20"; changelog = "### Novita'`n- Note con {graffe}, [quadre] e `"virgolette`"."
    releaseTag = $tag; apkAsset = $assetName; sizeBytes = 5000
  }
  if ($WithSha) { $fields.sha256 = $apkSha }
  foreach ($key in $Extra.Keys) { $fields[$key] = $Extra[$key] }
  $entry = ($fields.GetEnumerator() | ForEach-Object { '      "' + $_.Key + '": ' + $(if ($_.Value -is [string]) { '"' + ($_.Value -replace '\\', '\\' -replace '"', '\"' -replace "`n", '\n') + '"' } else { [string]$_.Value }) }) -join (",`r`n")
  $channels = '    "' + $Channel + '": {' + "`r`n" + $entry + "`r`n    }"
  if ($Twin) { $channels += ",`r`n" + '    "beta": {' + "`r`n" + $entry + "`r`n    }" }
  $text = "{`r`n  `"app`": {`r`n    `"id`": `"mycloud-gallery`",`r`n    `"repository`": { `"repoOwner`": `"$owner`", `"repoName`": `"$RepoName`", `"manifestPath`": `"manifest.json`", `"ref`": `"main`" },`r`n" + $channels + "`r`n  }`r`n}`r`n"
  $path = Join-Path $work ($Name + ".json")
  [IO.File]::WriteAllText($path, $text, $utf8)
  return $path
}

$script:urls = New-Object System.Collections.Generic.List[string]
$published = Join-Path $work "release"
New-Item -ItemType Directory -Path $published -Force | Out-Null
Copy-Item -LiteralPath $apk -Destination (Join-Path $published $assetName)
$fakeDownload = { param($url, $target) $script:urls.Add($url); Copy-Item -LiteralPath (Join-Path $published ($url -split '/')[-1]) -Destination $target }

function Get-Facts([hashtable]$Change = @{}) {
  $facts = [ordered]@{ Verified = $true; Signers = 1; CertSha256 = $fingerprint; Package = "dev.pampa.mycloudgallery"; VersionCode = 2; VersionName = $version }
  foreach ($key in $Change.Keys) { $facts[$key] = $Change[$key] }
  return [pscustomobject]$facts
}

function Invoke-Case([string]$ElencoPath, [hashtable]$Facts = @{}, [scriptblock]$Download = $fakeDownload, [string]$Version = $version, [string]$Channel = "beta", [string]$Apk = $apk) {
  $script:urls.Clear()
  $f = Get-Facts $Facts
  return Invoke-ElencoCheck -Channel $Channel -Version $Version -Apk $Apk -ElencoPath $ElencoPath -Owner $owner -Repo $repo -ExpectedFingerprint $fingerprint `
    -ExpectedCode 2 -WorkDir (Join-Path $work "scaricati") -Download $Download -ReadFacts ({ param($a) $f }.GetNewClosure())
}

function Has([object]$Outcome, [string]$Like) { return [bool](@($Outcome.Problems | Where-Object { $_ -like $Like }).Count) }

Write-Host "`nL'elenco giusto" -ForegroundColor Cyan
$path = New-Elenco "giusto" -WithSha
$hash = Get-FileSha256 $path
$o = Invoke-Case $path
Assert-That "con sha256 e peso giusti: nessun problema, niente scritto" ($o.Problems.Count -eq 0 -and $o.Wrote.Count -eq 0) ($o.Problems -join "; ")
Assert-That "il file dell'elenco non cambia" ((Get-FileSha256 $path) -eq $hash)
Assert-That "si scarica esattamente l'asset della voce" ($script:urls.Count -eq 1 -and $script:urls[0] -ceq "https://github.com/$owner/$repo/releases/download/$tag/$assetName") ($script:urls -join ", ")

Write-Host "`nUn elenco scritto prima della correzione del publisher (senza sha256)" -ForegroundColor Cyan
$path = New-Elenco "senza-sha"
$o = Invoke-Case $path
$after = (Read-Utf8 $path) | ConvertFrom-Json
Assert-That "scrive lo sha256 dell'APK nella voce" ($o.Problems.Count -eq 0 -and [string]$after.app.beta.sha256 -ceq $apkSha) ($o.Problems -join "; ")
Assert-That "il resto della voce resta com'era (note con graffe e virgolette comprese)" ($after.app.beta.changelog -ceq "### Novita'`n- Note con {graffe}, [quadre] e `"virgolette`"." -and $after.app.beta.sizeBytes -eq 5000 -and $after.app.beta.releaseTag -ceq $tag)
Assert-That "dice che cosa ha scritto" (@($o.Wrote) -contains "beta: sha256") ($o.Wrote -join ", ")
Assert-That "il testo resta con i suoi a capo (CRLF)" ((Read-Utf8 $path).Contains("`r`n      `"sha256`""))
$o2 = Invoke-Case $path
Assert-That "rilanciato, non scrive di nuovo" ($o2.Problems.Count -eq 0 -and $o2.Wrote.Count -eq 0) ($o2.Problems -join "; ")
$path = New-Elenco "stabile-gemella" -Channel "stable" -Twin -Extra @{ version = "1.1.0"; releaseTag = "stable-mycloud-gallery-v1.1.0"; apkAsset = "mycloud-gallery-1.1.0.apk" }
Copy-Item -LiteralPath $apk -Destination (Join-Path $published "mycloud-gallery-1.1.0.apk")
$o = Invoke-Case $path -Version "1.1.0" -Channel "stable" -Facts @{ VersionName = "1.1.0" }
$after = (Read-Utf8 $path) | ConvertFrom-Json
Assert-That "la stabile scritta anche in beta (lo stesso file): sha256 in tutte e due" ($o.Problems.Count -eq 0 -and [string]$after.app.stable.sha256 -ceq $apkSha -and [string]$after.app.beta.sha256 -ceq $apkSha) ($o.Problems -join "; ")
$path = New-Elenco "senza-peso" -Extra @{ sizeBytes = $null }
$text = (Read-Utf8 $path) -replace ',\r\n      "sizeBytes": ', ''
[IO.File]::WriteAllText($path, $text, $utf8)
$o = Invoke-Case $path
$after = (Read-Utf8 $path) | ConvertFrom-Json
Assert-That "senza peso: scrive anche sizeBytes" ($o.Problems.Count -eq 0 -and $after.app.beta.sizeBytes -eq 5000 -and [string]$after.app.beta.sha256 -ceq $apkSha) ($o.Problems -join "; ")

Write-Host "`nCio' che non deve passare (e allora non scrive niente)" -ForegroundColor Cyan
function Assert-Refused([string]$name, [string]$path, $outcome, [string]$like) {
  $before = $script:hashes[$path]
  Assert-That $name ((Has $outcome $like) -and $outcome.Wrote.Count -eq 0 -and (Get-FileSha256 $path) -eq $before) (($outcome.Problems -join "; ") + " | scritto: " + ($outcome.Wrote -join ", "))
}
$script:hashes = @{}
function New-Refused([string]$Name, [hashtable]$Extra = @{}, [switch]$WithSha, [string]$RepoName = $repo) {
  $p = New-Elenco $Name -Extra $Extra -WithSha:$WithSha -RepoName $RepoName
  $script:hashes[$p] = Get-FileSha256 $p
  return $p
}
$p = New-Refused "sha-diverso" -Extra @{ sha256 = "c" * 64 }
Assert-Refused "sha256 nell'elenco diverso dall'APK" $p (Invoke-Case $p) "*dice sha256*"
$p = New-Refused "peso-diverso" -Extra @{ sizeBytes = 4999 }
Assert-Refused "peso nell'elenco diverso dall'APK" $p (Invoke-Case $p) "*dice sizeBytes*"
$p = New-Refused "asset-diverso"
$other = Join-Path $work "altro.apk"
[IO.File]::WriteAllBytes($other, (New-Object byte[] 5000))
Assert-Refused "l'asset pubblicato non e' l'APK locale" $p (Invoke-Case $p -Download { param($url, $target) Copy-Item -LiteralPath $other -Destination $target }) "*non e' l'APK locale*"
$p = New-Refused "firma"
Assert-Refused "APK firmato con un'altra chiave" $p (Invoke-Case $p -Facts @{ CertSha256 = "cd" * 32 }) "*non e' firmato con pampa.jks*"
$p = New-Refused "firma-non-verificata"
Assert-Refused "firma che non si verifica" $p (Invoke-Case $p -Facts @{ Verified = $false }) "*non si verifica*"
$p = New-Refused "due-firmatari"
Assert-Refused "due firmatari" $p (Invoke-Case $p -Facts @{ Signers = 2 }) "*firmatari*"
$p = New-Refused "pacchetto"
Assert-Refused "un altro pacchetto" $p (Invoke-Case $p -Facts @{ Package = "com.altra.app" }) "*del pacchetto*"
$p = New-Refused "versionname"
Assert-Refused "versionName diverso" $p (Invoke-Case $p -Facts @{ VersionName = "1.1.0-beta.02" }) "*versionName*"
$p = New-Refused "versioncode"
Assert-Refused "versionCode diverso da release/versions.txt" $p (Invoke-Case $p -Facts @{ VersionCode = 3 }) "*versionCode*"
$p = New-Refused "versione-voce" -Extra @{ version = "1.1.0-beta.02" }
Assert-Refused "la voce dice un'altra versione" $p (Invoke-Case $p) "*dice la versione*"
$p = New-Refused "repository" -RepoName "altro"
Assert-Refused "l'elenco indica un altro repository" $p (Invoke-Case $p) "*indica il repository*"
$p = New-Refused "tag" -Extra @{ releaseTag = "../altro" }
$o = Invoke-Case $p
Assert-Refused "un tag con un percorso" $p $o "*tag*non ammesso*"
Assert-That "...e senza scaricare niente" ($script:urls.Count -eq 0)
$p = New-Refused "forma"
Assert-Refused "una versione fuori forma (beta con una cifra)" $p (Invoke-Case $p -Version "1.1.0-beta.1" -Facts @{ VersionName = "1.1.0-beta.1" }) "*fuori forma*"
$p = New-Refused "cambiato"
Assert-Refused "l'elenco cambiato durante la verifica" $p (Invoke-Case $p -Download {
    param($url, $target)
    Copy-Item -LiteralPath (Join-Path $published ($url -split '/')[-1]) -Destination $target
    [IO.File]::AppendAllText($p, " ", $utf8)
    $script:hashes[$p] = Get-FileSha256 $p
  }) "*cambiato*"
$p = New-Refused "scaricamento-fallito"
Assert-Refused "lo scaricamento non riesce" $p (Invoke-Case $p -Download { param($url, $target) throw "404" }) "*non riesco a scaricare*"

Write-Host "`nGiro 2: l'elenco gia' completo cambiato nel frattempo (US2-2)" -ForegroundColor Cyan
# Il caso ordinario dopo la correzione del publisher: sha256 e peso ci sono gia', non c'e' niente da scrivere. Mentre si scarica, un altro processo
# rimette nel file una voce valida che indica un'altra versione: il verde direbbe che la voce e' l'APK finale, e non lo e' piu'.
$p = New-Refused "completo-cambiato" -WithSha
$otherVersion = (Read-Utf8 (New-Elenco "altra-versione" -WithSha -Extra @{ version = "1.1.0-beta.02"; releaseTag = "beta-mycloud-gallery-v1.1.0-beta.02"; apkAsset = "mycloud-gallery-1.1.0-beta.02.apk" }))
$o = Invoke-Case $p -Download {
    param($url, $target)
    Copy-Item -LiteralPath (Join-Path $published ($url -split '/')[-1]) -Destination $target
    [IO.File]::WriteAllText($p, $otherVersion, $utf8)
    $script:hashes[$p] = Get-FileSha256 $p
  }
Assert-Refused "gia' completo e sostituito durante lo scaricamento: non e' verde" $p $o "*cambiato*"

Write-Host "`nGiro 2: app.repository obbligatorio (US2-3)" -ForegroundColor Cyan
# Senza repository (o con owner o nome vuoti) l'engine non costruisce l'offerta: nessun telefono riceverebbe l'aggiornamento.
function New-RepoCase([string]$Name, [string]$Repository) {
  $p = New-Elenco $Name -WithSha
  $text = (Read-Utf8 $p) -replace '    "repository": \{[^}]*\},\r\n', $Repository
  [IO.File]::WriteAllText($p, $text, $utf8)
  $script:hashes[$p] = Get-FileSha256 $p
  return $p
}
foreach ($case in @(
    @("repository-assente", "", "assente"),
    @("repository-null", "    `"repository`": null,`r`n", "null"),
    @("repository-vuoto", "    `"repository`": {},`r`n", "oggetto vuoto"),
    @("repository-nomi-vuoti", "    `"repository`": { `"repoOwner`": `"`", `"repoName`": `"`" },`r`n", "owner e nome vuoti"),
    @("repository-testo", "    `"repository`": `"$owner/$repo`",`r`n", "una stringa al posto dell'oggetto"))) {
  $p = New-RepoCase $case[0] $case[1]
  Assert-Refused ("app.repository " + $case[2] + ": non e' verde") $p (Invoke-Case $p) "*non ha app.repository*"
}
$p = New-RepoCase "repository-chiavi-corte" "    `"repository`": { `"owner`": `"$owner`", `"name`": `"$repo`" },`r`n"
$o = Invoke-Case $p
Assert-That "app.repository con owner e name (le chiavi che l'engine accetta anche): passa" ($o.Problems.Count -eq 0) ($o.Problems -join "; ")
$p = New-RepoCase "repository-altro-owner" "    `"repository`": { `"repoOwner`": `"Altro`", `"repoName`": `"$repo`" },`r`n"
Assert-Refused "app.repository di un altro owner" $p (Invoke-Case $p) "*indica il repository*"

Write-Host "`nIl lettore del JSON" -ForegroundColor Cyan
$sample = '{ "a": { "x": "}{\"", "app": [1, {"b": 2}] }, "app": { "beta": { "k": "v\\" } } }'
$span = Find-JsonObjectSpan $sample @("app", "beta")
Assert-That "trova l'oggetto giusto anche con graffe, virgolette e barre nelle stringhe" ($span -and $sample.Substring($span.Start, $span.End - $span.Start + 1) -ceq '{ "k": "v\\" }') $(if ($span) { $sample.Substring($span.Start, $span.End - $span.Start + 1) } else { "nessuno" })
$added = Add-JsonFields $sample $span @('"sha256": "x"')
Assert-That "aggiunge in fondo all'oggetto e il JSON resta valido" ((($added | ConvertFrom-Json).app.beta.sha256 -ceq "x") -and (($added | ConvertFrom-Json).app.beta.k -ceq "v\"))

Write-Host "`nLo strumento con -Prova" -ForegroundColor Cyan
$run = Invoke-Native -File "powershell" -Arguments @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", (Join-Path $PSScriptRoot "verifica-elenco.ps1"), "-Prova")
Assert-That "verifica-elenco.ps1 -Prova finisce con 0 e scrive lo sha256 mancante" ($run.Code -eq 0 -and (($run.Lines -join "`n") -like "*scritto: beta: sha256*")) ($run.Lines -join " | ")

if ($failures.Count -gt 0) {
  Write-Host ("verifica-elenco-prove: {0} prove, {1} fallite" -f $count, $failures.Count) -ForegroundColor Red
  exit 1
}
Write-Host ("verifica-elenco-prove: {0} prove, tutte passate (file finti sotto build\verifica-elenco\prove, nessuna rete)" -f $count) -ForegroundColor Green
exit 0
