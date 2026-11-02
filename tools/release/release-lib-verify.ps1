# Libreria di tools\release\release.ps1 (Fase 10, P12; docs/fase10-progetto.md par. 2.9 passo 5, D32, C443): il controllo dell'APK firmato.
# Si carica dopo release-lib-core.ps1 e release-lib-apk.ps1. Solo ASCII, PowerShell 5.1.

$script:AppId = "dev.pampa.mycloudgallery"

# Passo 5. Ritorna i problemi e i fatti letti (per il resoconto). Controlla, nell'ordine:
#  1. apksigner (--min-sdk-version 24: verifica v2 e v3 insieme; con il minSdk dell'app, 29, verificherebbe solo la v3): schemi v2 e v3 verificati,
#     un solo firmatario con un solo certificato, impronta uguale a quella scritta in docs/sicurezza.md; la v1 (META-INF/*.SF) si riporta e, se c'e', si verifica
#  2. aapt2 badging: l'id dell'app e' quello di release (mai .debug ne' .bench), non debuggable;
#  3. il manifest dentro l'APK: backup escluso, componenti esportati solo dell'elenco chiuso (con il permesso quando serve), nessun
#     sharedUserId, nessun permesso definito dall'app o da una libreria (tranne quello di androidx.core), permessi dell'elenco chiuso e documentati;
#  4. il dex: nessun resto di debug (l'elenco di check.ps1), nessuna classe di misura, nessun fake (package testing), nessuna chiave di prova KT;
#  5. le librerie native: almeno una, solo arm64-v8a, non compresse, allineate a 16 KB nel file e nei segmenti LOAD.
function Test-ReleaseApk([string]$Apk, [string]$ExpectedFingerprint, $Tools) {
  $problems = New-Object System.Collections.Generic.List[string]
  $facts = [ordered]@{
    Package = ""; VersionCode = 0; VersionName = ""; V1 = $false; V2 = $false; V3 = $false; Signers = 0; CertSha256 = ""; KeyAlgorithm = ""; KeyBits = ""
    MinSdk = ""; TargetSdk = ""; ComponentCount = 0; Exported = @(); Permissions = @(); NativeLibs = 0; SizeBytes = (Get-Item -LiteralPath $Apk).Length
    Sha256 = (Get-FileSha256 $Apk)
  }

  # 1. La firma.
  $verify = Invoke-Native -File $Tools.ApkSigner -Arguments @("verify", "--print-certs", "-v", "--min-sdk-version", "24", $Apk)
  $digests = @{}
  foreach ($line in $verify.Lines) {
    if ($line -match 'Verified using v2 scheme.*:\s*(true|false)') { $facts.V2 = ($Matches[1] -eq "true") }
    elseif ($line -match 'Verified using v3 scheme \(.*:\s*(true|false)') { $facts.V3 = ($Matches[1] -eq "true") }
    elseif ($line -match 'Number of signers:\s*(\d+)') { $facts.Signers = [int]$Matches[1] }
    elseif ($line -match 'certificate SHA-256 digest:\s*([0-9a-fA-F:]+)') { $digests[($Matches[1] -replace ':', '').ToLowerInvariant()] = $true }
    elseif ($line -match 'key algorithm:\s*(.+)$') { $facts.KeyAlgorithm = $Matches[1].Trim() }
    elseif ($line -match 'key size \(bits\):\s*(\d+)') { $facts.KeyBits = $Matches[1] }
  }
  if ($digests.Count -eq 1) { $facts.CertSha256 = @($digests.Keys)[0] }
  elseif ($digests.Count -gt 1) { $problems.Add("l'APK ha piu' certificati di firma diversi ($($digests.Count)): ne serve uno solo") }
  # La v1 (JAR): c'e' se nell'APK c'e' un file di firma META-INF/*.SF; se c'e', deve verificare (apksigner con un minSdk basso).
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zipForV1 = [System.IO.Compression.ZipFile]::OpenRead($Apk)
  try { $facts.V1 = (@($zipForV1.Entries | Where-Object { $_.FullName -match '^META-INF/[^/]+\.SF$' }).Count -gt 0) } finally { $zipForV1.Dispose() }
  if ($facts.V1) {
    $verifyV1 = Invoke-Native -File $Tools.ApkSigner -Arguments @("verify", "--min-sdk-version", "23", $Apk)
    if ($verifyV1.Code -ne 0) { $problems.Add("la firma v1 (JAR) dell'APK non si verifica (apksigner con minSdk 23, codice $($verifyV1.Code))") }
  }
  if ($verify.Code -ne 0) {
    $reason = @($verify.Lines | Where-Object { $_ -match 'DOES NOT VERIFY|ERROR' } | Select-Object -First 1)
    $problems.Add("apksigner verify e' fallito (codice $($verify.Code))" + $(if ($reason.Count -gt 0) { ": " + $reason[0] } else { "" }))
  } else {
    if (-not $facts.V2) { $problems.Add("la firma v2 dell'APK non e' verificata (D32: schemi v2 e v3)") }
    if (-not $facts.V3) { $problems.Add("la firma v3 dell'APK non e' verificata (D32: schemi v2 e v3, serve per la rotazione futura)") }
    if ($facts.Signers -ne 1) { $problems.Add("l'APK ha $($facts.Signers) firmatari, ne serve uno") }
    if (-not $ExpectedFingerprint) {
      $problems.Add("manca l'impronta del certificato di pampa.jks in docs/sicurezza.md par. 5.6: la scrive l'utente o release.ps1 -Impronta -Scrivi")
    } elseif ($facts.CertSha256 -ne $ExpectedFingerprint.ToLowerInvariant()) {
      $problems.Add("l'impronta del certificato dell'APK ($($facts.CertSha256.Substring(0, [math]::Min(16, $facts.CertSha256.Length)))...) non e' quella di pampa.jks scritta in docs/sicurezza.md par. 5.6: l'APK e' firmato con un'altra chiave")
    }
  }

  # 2. Id, versione, debuggable.
  $badging = Invoke-Native -File $Tools.Aapt2 -Arguments @("dump", "badging", $Apk)
  if ($badging.Code -ne 0) {
    $problems.Add("aapt2 dump badging fallito")
  } else {
    $package = @($badging.Lines | Where-Object { $_ -match "^package: name='([^']*)' versionCode='(\d+)' versionName='([^']*)'" } | Select-Object -First 1)
    if ($package.Count -eq 0) {
      $problems.Add("aapt2 badging non dice il pacchetto: lettura non affidabile")
    } else {
      [void]($package[0] -match "^package: name='([^']*)' versionCode='(\d+)' versionName='([^']*)'")
      $facts.Package = $Matches[1]; $facts.VersionCode = [int]$Matches[2]; $facts.VersionName = $Matches[3]
    }
    foreach ($line in $badging.Lines) {
      if ($line -match "^(?:minSdkVersion|sdkVersion):'(\d+)'") { $facts.MinSdk = $Matches[1] }
      if ($line -match "^targetSdkVersion:'(\d+)'") { $facts.TargetSdk = $Matches[1] }
    }
    if ($facts.Package -and $facts.Package -ne $script:AppId) { $problems.Add("l'id dell'APK e' '$($facts.Package)', non $($script:AppId): e' una build di debug o di misura, non la release") }
    if (@($badging.Lines | Where-Object { $_ -match '^application-debuggable' }).Count -gt 0) { $problems.Add("l'APK e' DEBUGGABLE (application-debuggable)") }
  }

  # 3. Il manifest dentro l'APK.
  $manifest = Get-ApkManifestFacts $Tools.Aapt2 $Apk
  if ($manifest.Debuggable) { $problems.Add("android:debuggable=true nel manifest dell'APK") }
  if ($manifest.AllowBackup -ne "false") { $problems.Add("android:allowBackup nell'APK e' '$($manifest.AllowBackup)', deve essere false") }
  if ($manifest.SharedUserId) { $problems.Add("l'APK dichiara sharedUserId ($($manifest.SharedUserId)): con la chiave condivisa (D32, L559) un'altra app Pampa potrebbe condividere i dati") }
  foreach ($defined in @($manifest.DefinedPermissions)) {
    if ($defined.Name -notmatch '\.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION$') { $problems.Add("l'APK definisce un permesso proprio ($($defined.Name)): nessun permesso definito dall'app o dalle librerie (D32, C443)") }
  }
  $allowed = Get-AllowedExported
  $exported = @($manifest.Components | Where-Object { $_.Exported })
  foreach ($component in $exported) {
    if (-not $allowed.ContainsKey($component.Name)) {
      $problems.Add(("componente esportato non ammesso: <{0}> {1}" -f $component.Kind, $component.Name))
    } elseif ($allowed[$component.Name] -and $component.Permission -ne $allowed[$component.Name]) {
      $problems.Add(("<{0}> {1} e' esportato senza il permesso {2}" -f $component.Kind, $component.Name, $allowed[$component.Name]))
    }
  }
  if (-not ($exported | Where-Object { $_.Name -eq "$($script:AppId).MainActivity" })) { $problems.Add("la launcher activity non risulta esportata: il lettore del manifest non funziona o l'app non si avvia") }
  foreach ($component in @($manifest.Components | Where-Object { $_.Name -match 'debugtools\.probes\.|FieldProbe|androidx\.profileinstaller\.ProfileInstallReceiver' })) {
    $problems.Add("un componente di debug o di misura e' nella release: <$($component.Kind)> $($component.Name)")
  }
  $facts.ComponentCount = @($manifest.Components).Count
  $facts.Exported = @($exported | ForEach-Object { $_.Name })
  $permissionDump = Invoke-Native -File $Tools.Aapt2 -Arguments @("dump", "permissions", $Apk)
  if ($permissionDump.Code -ne 0) {
    $problems.Add("aapt2 dump permissions fallito")
  } else {
    $permissions = @()
    foreach ($line in $permissionDump.Lines) { if ($line -match "^uses-permission[\w\-]*: name='([^']+)'") { $permissions += $Matches[1] } }
    $facts.Permissions = @($permissions | Sort-Object)
    $closed = @(Get-PolicyList "allowedPermissions" | ForEach-Object { $_.Replace("{applicationId}", $script:AppId) })
    $documented = Get-DocumentedPermissions
    foreach ($permission in $permissions) {
      if ($closed -notcontains $permission) { $problems.Add("permesso fuori dall'elenco chiuso di verify-release-manifest.gradle: $permission") }
      if ($permission -notmatch '\.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION$' -and -not $documented.ContainsKey($permission.Substring($permission.LastIndexOf('.') + 1))) {
        $problems.Add("permesso senza riga in docs/sicurezza.md 4-bis: $permission")
      }
    }
  }

  # 4. Il dex.
  $texts = Get-DexTexts $Apk
  $names = Get-DebugRemnants
  $leftovers = @(Find-InDex $texts (($names | ForEach-Object { [regex]::Escape($_) }) -join "|"))
  if ($leftovers.Count -gt 0) { $problems.Add("resti di strumenti di debug nel dex ($($leftovers.Count)): " + (($leftovers | Select-Object -First 8) -join ", ")) }
  $allowedBench = Get-AllowedBenchmarkTypes
  $bench = @(Find-InDex $texts 'L(?:[A-Za-z0-9_$]+/)*(?:Bench[A-Za-z0-9_$]*;|benchmark/[A-Za-z0-9_$/]*;)|Landroidx/test/uiautomator/[A-Za-z0-9_$/]*;' | Where-Object { $allowedBench -notcontains $_ })
  if ($bench.Count -gt 0) { $problems.Add("classi di misura nel dex ($($bench.Count)): " + (($bench | Select-Object -First 8) -join ", ")) }
  $fakes = @(Find-InDex $texts 'L(?:[A-Za-z0-9_$]+/)+testing/[A-Za-z0-9_$/]*;')
  if ($fakes.Count -gt 0) { $problems.Add("classi di un package testing (fake) nel dex ($($fakes.Count)): " + (($fakes | Select-Object -First 8) -join ", ")) }
  $ktFile = Resolve-RepoFile "core-release\src\main\kotlin\dev\pampa\mycloudgallery\core\release\testing\ReleaseTestKeys.kt"
  if (-not (Test-Path -LiteralPath $ktFile)) {
    $problems.Add("manca ReleaseTestKeys.kt: non posso cercare la chiave di prova KT nel dex")
  } else {
    $literals = @([regex]::Matches((Read-Utf8 $ktFile), '"([A-Za-z0-9+/=]{64,})"') | ForEach-Object { $_.Groups[1].Value })
    if ($literals.Count -eq 0) {
      $problems.Add("ReleaseTestKeys.kt senza la chiave pubblica di KT come stringa: non la posso cercare nel dex")
    } elseif (@(Find-InDex $texts (($literals | ForEach-Object { [regex]::Escape($_) }) -join "|")).Count -gt 0) {
      $problems.Add("la chiave di prova KT del manifest e' nel dex di release")
    }
  }

  # 5. Le librerie native.
  Initialize-ApkInspect
  $libs = @([ReleaseApkInspect]::NativeLibs($Apk))
  $facts.NativeLibs = $libs.Count
  if ($libs.Count -eq 0) { $problems.Add("nessuna libreria nativa nell'APK: la lettura non e' affidabile (LiteRT, ML Kit e MapLibre ne portano)") }
  foreach ($lib in $libs) {
    $abi = ($lib.Name -split '/')[1]
    if ($abi -ne "arm64-v8a") { $problems.Add("$($lib.Name): ABI non ammessa nella release (solo arm64-v8a)"); continue }
    if ($lib.Method -ne 0) { $problems.Add("$($lib.Name) e' compressa nell'APK (va mappata cosi' com'e')"); continue }
    if ($lib.DataOffset % 16384 -ne 0) { $problems.Add("$($lib.Name) non e' allineata a 16 KB nel file (scostamento $($lib.DataOffset))") }
    if ($lib.Problem) { $problems.Add("$($lib.Name): $($lib.Problem)"); continue }
    if (@($lib.LoadAligns).Count -eq 0) { $problems.Add("$($lib.Name): nessun segmento LOAD letto") }
    foreach ($align in $lib.LoadAligns) { if ($align -lt 16384) { $problems.Add("$($lib.Name): segmento LOAD allineato a $align byte (servono 16 KB)") } }
  }
  return [pscustomobject]@{ Problems = @($problems); Facts = [pscustomobject]$facts }
}
