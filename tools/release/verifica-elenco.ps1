<#
.SYNOPSIS
  Dopo la pubblicazione di una versione: verifica la voce del suo canale nell'elenco dell'app (manifest.json) contro l'APK finale conservato in locale
  e, solo se mancano, scrive sha256 e sizeBytes nella voce (pacchetto U, giro 1 delle critiche, US-1; docs/aggiornamenti-in-app.md).

.DESCRIPTION
  Dal 12/11/2026 publisher.py scrive da solo sha256 e sizeBytes (correzione decisa dall'utente): questo strumento e' il controllo dopo, e la strada per
  un elenco scritto prima della correzione. In ordine:
    1. la versione ha la forma ammessa; l'elenco indica il repository dell'app (owner e repo letti da feature-update, UpdateSource.kt); la voce del
       canale dice quella versione, con un tag e un file dal nome sicuro;
    2. l'APK locale e' firmato con pampa.jks (l'impronta di docs/sicurezza.md par. 5.6, un solo firmatario, apksigner), e' dev.pampa.mycloudgallery,
       con versionName uguale alla versione e il versionCode della sua riga in release/versions.txt (aapt2);
    3. scarica ESATTAMENTE l'asset della voce (https://github.com/<owner>/<repo>/releases/download/<tag>/<file>, ogni rimando verso un host ammesso,
       come l'app) e lo confronta byte per byte con l'APK locale (peso e SHA-256);
    4. sha256 e sizeBytes della voce, se ci sono, devono essere quelli; se mancano li scrive (anche nella voce dell'altro canale, se indica lo stesso
       file), solo se l'elenco non e' cambiato nel frattempo, rileggendo il testo nuovo prima di sostituire il file.
  Non fa commit ne' push: l'elenco cambiato lo pubblica chi coordina. Codice di uscita 0 solo se tutto e' a posto.

.PARAMETER Canale
  stable o beta.

.PARAMETER Versione
  La versione pubblicata (1.0.0, 1.1.0-beta.01).

.PARAMETER Apk
  L'APK finale conservato, lo stesso caricato sulla release.

.PARAMETER Elenco
  Il manifest.json locale (di serie manifest.json alla radice del repository, dopo un pull).

.PARAMETER Prova
  Senza rete e senza strumenti dell'SDK: un elenco, un APK e una release finti sotto build\verifica-elenco\prova, lo stesso controllo. Le prove vere
  sono in verifica-elenco-prove.ps1.

.EXAMPLE
  .\tools\release\verifica-elenco.ps1 -Canale beta -Versione 1.1.0-beta.01 -Apk C:\rilasci\mycloud-gallery-1.1.0-beta.01.apk
#>
[CmdletBinding()]
param(
  [string]$Canale,
  [string]$Versione,
  [string]$Apk,
  [string]$Elenco,
  [switch]$Prova
)

$ErrorActionPreference = "Stop"
$script:Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
. (Join-Path $PSScriptRoot "release-lib-core.ps1")
. (Join-Path $PSScriptRoot "verifica-elenco-lib.ps1")

function Write-Outcome($Outcome) {
  foreach ($written in $Outcome.Wrote) { Write-Host "  scritto: $written" -ForegroundColor Yellow }
  if ($Outcome.Problems.Count -gt 0) {
    foreach ($problem in $Outcome.Problems) { Write-Host "  [KO] $problem" -ForegroundColor Red }
    return 1
  }
  Write-Host "  [ok] la voce dell'elenco e l'asset pubblicato sono l'APK finale" -ForegroundColor Green
  return 0
}

$source = Get-UpdateSourceRepo (Join-Path $script:Root "feature-update\src\main\kotlin\dev\pampa\mycloudgallery\feature\update\source\UpdateSource.kt")

if ($Prova) {
  # Tutto finto, sotto build\: un APK di byte casuali, la "release" come una cartella, i fatti dell'APK come li direbbero apksigner e aapt2.
  $work = Join-Path $script:Root "build\verifica-elenco\prova"
  if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
  New-Item -ItemType Directory -Path $work -Force | Out-Null
  $bytes = New-Object byte[] 4096
  (New-Object Random 7).NextBytes($bytes)
  $apkPath = Join-Path $work "mycloud-gallery-1.1.0-beta.01.apk"
  [IO.File]::WriteAllBytes($apkPath, $bytes)
  $published = Join-Path $work "release"
  New-Item -ItemType Directory -Path $published -Force | Out-Null
  Copy-Item -LiteralPath $apkPath -Destination (Join-Path $published "mycloud-gallery-1.1.0-beta.01.apk")
  $elencoPath = Join-Path $work "manifest.json"
  $manifest = @"
{
  "app": {
    "id": "mycloud-gallery",
    "packageName": "dev.pampa.mycloudgallery",
    "repository": { "repoOwner": "$($source.Owner)", "repoName": "$($source.Repo)", "manifestPath": "manifest.json", "ref": "main" },
    "beta": {
      "version": "1.1.0-beta.01",
      "releaseDate": "2026-11-20",
      "changelog": "### Novita'\n- Prova con {graffe} e \"virgolette\".",
      "releaseTag": "beta-mycloud-gallery-v1.1.0-beta.01",
      "apkAsset": "mycloud-gallery-1.1.0-beta.01.apk",
      "sizeBytes": 4096
    }
  }
}
"@
  [IO.File]::WriteAllText($elencoPath, $manifest, (New-Object Text.UTF8Encoding($false)))
  $fingerprint = "ab" * 32
  $outcome = Invoke-ElencoCheck -Channel "beta" -Version "1.1.0-beta.01" -Apk $apkPath -ElencoPath $elencoPath -Owner $source.Owner -Repo $source.Repo `
    -ExpectedFingerprint $fingerprint -ExpectedCode 2 -WorkDir (Join-Path $work "scaricati") `
    -Download { param($url, $target) Copy-Item -LiteralPath (Join-Path $published ($url -split '/')[-1]) -Destination $target } `
    -ReadFacts { param($apk) [pscustomobject]@{ Verified = $true; Signers = 1; CertSha256 = $fingerprint; Package = "dev.pampa.mycloudgallery"; VersionCode = 2; VersionName = "1.1.0-beta.01" } }
  Write-Host "verifica-elenco -Prova (elenco finto senza sha256, sotto build\verifica-elenco\prova):"
  exit (Write-Outcome $outcome)
}

if (@("stable", "beta") -notcontains $Canale) { throw "serve -Canale stable oppure beta" }
if (-not $Versione) { throw "serve -Versione (per esempio 1.1.0-beta.01)" }
if (-not $Apk -or -not (Test-Path -LiteralPath $Apk)) { throw "serve -Apk, l'APK finale conservato" }
if (-not $Elenco) { $Elenco = Join-Path $script:Root "manifest.json" }
$fingerprint = Get-DocFingerprint (Join-Path $script:Root "docs\sicurezza.md")
$rows = @((Read-VersionRows (Join-Path $script:Root "release\versions.txt")).Rows | Where-Object { $_.Name -ceq $Versione })
if ($rows.Count -eq 0) { throw "release/versions.txt non ha la versione $Versione" }
$tools = Get-BuildTools
if (-not $tools) { throw "non trovo le build-tools dell'SDK (aapt2, apksigner)" }
$outcome = Invoke-ElencoCheck -Channel $Canale -Version $Versione -Apk (Resolve-Path $Apk).Path -ElencoPath $Elenco -Owner $source.Owner -Repo $source.Repo `
  -ExpectedFingerprint $fingerprint -ExpectedCode $rows[0].Code -WorkDir (Join-Path $script:Root "build\verifica-elenco") `
  -Download { param($url, $target) Invoke-GuardedDownload $url $target $script:ElencoMaxApk } `
  -ReadFacts { param($apk) Get-ApkIdentity $tools $apk }
Write-Host "verifica-elenco: $Canale $Versione"
exit (Write-Outcome $outcome)
