# Aiuti di tools\release\test-release.ps1 (Fase 10, P12): costruiscono, sotto build\, un keystore di prova e piccoli APK firmati che servono a
# provare i controlli di release.ps1. Nessun APK e nessuna chiave sono quelli veri: tutto nasce qui, con keytool, javac, d8, aapt2 e apksigner.
# Solo ASCII, PowerShell 5.1. Si carica dopo le librerie di release.ps1 (usa Invoke-Native, Get-BuildTools, Initialize-Java).

function New-TestKeystore([string]$Path, [string]$Password) {
  $keytool = Get-Keytool
  if (-not $keytool) { throw "keytool non trovato" }
  if (Test-Path -LiteralPath $Path) { Remove-Item -LiteralPath $Path -Force }
  $run = Invoke-Native -File $keytool -Arguments @("-genkeypair", "-alias", "pampa", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
    "-keystore", $Path, "-storepass", $Password, "-keypass", $Password, "-dname", "CN=MCG Prova, OU=Prova, O=Prova, C=IT")
  if ($run.Code -ne 0) { throw ("keytool -genkeypair fallito: " + ($run.Lines -join " | ")) }
}

# Un ELF a 64 bit minimo, con un solo segmento LOAD allineato a $Align: abbastanza per il lettore dei 16 KB di release.ps1.
function New-FakeElf([long]$Align) {
  $bytes = New-Object byte[] 128
  $bytes[0] = 0x7f; $bytes[1] = [byte][char]'E'; $bytes[2] = [byte][char]'L'; $bytes[3] = [byte][char]'F'; $bytes[4] = 2; $bytes[5] = 1; $bytes[6] = 1
  [System.BitConverter]::GetBytes([uint16]3).CopyTo($bytes, 0x10)
  [System.BitConverter]::GetBytes([uint16]183).CopyTo($bytes, 0x12)
  [System.BitConverter]::GetBytes([uint32]1).CopyTo($bytes, 0x14)
  [System.BitConverter]::GetBytes([long]64).CopyTo($bytes, 0x20)
  [System.BitConverter]::GetBytes([uint16]64).CopyTo($bytes, 0x34)
  [System.BitConverter]::GetBytes([uint16]56).CopyTo($bytes, 0x36)
  [System.BitConverter]::GetBytes([uint16]1).CopyTo($bytes, 0x38)
  [System.BitConverter]::GetBytes([uint32]1).CopyTo($bytes, 64)
  [System.BitConverter]::GetBytes([uint32]5).CopyTo($bytes, 68)
  [System.BitConverter]::GetBytes([long]128).CopyTo($bytes, 96)
  [System.BitConverter]::GetBytes([long]128).CopyTo($bytes, 104)
  [System.BitConverter]::GetBytes($Align).CopyTo($bytes, 112)
  return $bytes
}

function Get-AndroidJar([string]$SdkDir) {
  $jar = Get-ChildItem (Join-Path $SdkDir "platforms") -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending |
    ForEach-Object { Join-Path $_.FullName "android.jar" } | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
  if (-not $jar) { throw "android.jar non trovato in $SdkDir\platforms" }
  return $jar
}

# I5 (12/11/2026): gli ingressi del launcher come nell'app vera: la MainActivity senza filtro e tre activity-alias, uno per icona, solo il primo
# abilitato. [Changes] cambia un alias (nome corto Auto, Light, Dark -> hashtable con Enabled, Target, Extra dentro il filtro), [MainLauncher] rimette
# il filtro LAUNCHER alla MainActivity, [ExtraAlias] aggiunge un quarto alias esportato con il suo filtro MAIN/LAUNCHER.
function New-LauncherXml([hashtable]$Changes = @{}, [switch]$MainLauncher, [string]$ExtraAlias = "") {
  $filter = '<intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/>{0}</intent-filter>'
  $main = if ($MainLauncher) { '    <activity android:name="dev.pampa.mycloudgallery.MainActivity" android:exported="true">' + ($filter -f "") + '</activity>' }
          else { '    <activity android:name="dev.pampa.mycloudgallery.MainActivity" android:exported="true"/>' }
  $lines = @($main)
  $aliases = [ordered]@{ Auto = $true; Light = $false; Dark = $false }
  if ($ExtraAlias) { $aliases[$ExtraAlias] = $false }
  foreach ($short in $aliases.Keys) {
    $c = if ($Changes.ContainsKey($short)) { $Changes[$short] } else { @{} }
    $enabled = if ($c.ContainsKey("Enabled")) { $c.Enabled } else { $aliases[$short] }
    $target = if ($c.ContainsKey("Target")) { $c.Target } else { "dev.pampa.mycloudgallery.MainActivity" }
    $extra = if ($c.ContainsKey("Extra")) { $c.Extra } else { "" }
    $lines += ('    <activity-alias android:name="dev.pampa.mycloudgallery.Launcher{0}" android:enabled="{1}" android:exported="true" android:targetActivity="{2}">' -f $short, ([string]$enabled).ToLowerInvariant(), $target) +
      ($filter -f $extra) + '</activity-alias>'
  }
  return ($lines -join "`n")
}

# Un APK piccolo e FIRMATO. Opzioni (tutte facoltative, i valori di base fanno passare release.ps1):
#   Keystore, Password, Package, VersionCode, VersionName, Debuggable, Lib ("aligned" | "align4096" | "unaligned" | "none"), Changelog (testo o $null),
#   V3 ($false = firma senza v3), ExtraClasses (nome completo -> corpo della classe), Permissions, ExportedComponents, ManifestAttributes,
#   Launcher (gli ingressi del launcher: di base quelli di New-LauncherXml, come nell'app vera).
function New-MiniApk([string]$Dir, [string]$Name, [hashtable]$Options) {
  $tools = Get-BuildTools
  $o = @{ Package = "dev.pampa.mycloudgallery"; VersionCode = 1; VersionName = "1.0.0"; Debuggable = $false; Lib = "aligned"; Changelog = $null; V3 = $true
          ExtraClasses = @{}; Permissions = @(); ExportedComponents = @(); ManifestAttributes = ""; Password = "prova-12345"; Launcher = (New-LauncherXml) }
  foreach ($key in $Options.Keys) { $o[$key] = $Options[$key] }
  if (-not $o.Keystore) { throw "New-MiniApk vuole un keystore di prova" }
  $work = Join-Path $Dir $Name
  if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
  New-Item -ItemType Directory -Path (Join-Path $work "src"), (Join-Path $work "classes"), (Join-Path $work "dex") -Force | Out-Null
  Initialize-Java
  $javac = Join-Path $env:JAVA_HOME "bin\javac.exe"

  # Il dex: la MainActivity (la lettura del dex la pretende) piu' le classi che il caso vuole.
  $classes = @{ "dev.pampa.mycloudgallery.MainActivity" = "" }
  foreach ($key in $o.ExtraClasses.Keys) { $classes[$key] = $o.ExtraClasses[$key] }
  $sources = @()
  foreach ($full in $classes.Keys) {
    $package = $full.Substring(0, $full.LastIndexOf('.'))
    $simple = $full.Substring($full.LastIndexOf('.') + 1)
    $file = Join-Path $work ("src\" + $simple + ".java")
    [System.IO.File]::WriteAllText($file, "package $package;`npublic class $simple { " + $classes[$full] + " }`n", (New-Object System.Text.UTF8Encoding($false)))
    $sources += $file
  }
  $run = Invoke-Native -File $javac -Arguments (@("--release", "8", "-nowarn", "-d", (Join-Path $work "classes")) + $sources)
  if ($run.Code -ne 0) { throw ("javac fallito: " + ($run.Lines -join " | ")) }
  $classFiles = @(Get-ChildItem (Join-Path $work "classes") -Recurse -Filter "*.class" | ForEach-Object { $_.FullName })
  $d8 = Join-Path $tools.Dir "d8.bat"
  $run = Invoke-Native -File $d8 -Arguments (@("--release", "--min-api", "29", "--output", (Join-Path $work "dex")) + $classFiles)
  if ($run.Code -ne 0) { throw ("d8 fallito: " + ($run.Lines -join " | ")) }

  # Il manifest e la base dell'APK (aapt2 link).
  $permissions = ($o.Permissions | ForEach-Object { "  <uses-permission android:name=`"$_`"/>" }) -join "`n"
  $extra = ($o.ExportedComponents | ForEach-Object { "    <receiver android:name=`"$_`" android:exported=`"true`"/>" }) -join "`n"
  $manifest = @"
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$($o.Package)" $($o.ManifestAttributes)>
$permissions
  <application android:allowBackup="false" android:debuggable="$(([string]$o.Debuggable).ToLowerInvariant())">
$($o.Launcher)
$extra
  </application>
</manifest>
"@
  $manifestFile = Join-Path $work "AndroidManifest.xml"
  [System.IO.File]::WriteAllText($manifestFile, $manifest, (New-Object System.Text.UTF8Encoding($false)))
  $base = Join-Path $work "base.apk"
  $run = Invoke-Native -File $tools.Aapt2 -Arguments @("link", "-o", $base, "--manifest", $manifestFile, "-I", (Get-AndroidJar $tools.Sdk), "--min-sdk-version", "29",
    "--target-sdk-version", "36", "--version-code", [string]$o.VersionCode, "--version-name", $o.VersionName)
  if ($run.Code -ne 0) { throw ("aapt2 link fallito: " + ($run.Lines -join " | ")) }

  # Dentro l'APK: il dex, la libreria nativa e il changelog come risorsa, tutti NON compressi (`jar --no-compress`: la libreria nativa deve
  # restare "stored", e il resto non importa). Il controllo dei 16 KB legge proprio il metodo di compressione.
  $stage = Join-Path $work "stage"
  New-Item -ItemType Directory -Path (Join-Path $stage "lib/arm64-v8a"), (Join-Path $stage "res/raw") -Force | Out-Null
  Copy-Item -LiteralPath (Join-Path $work "dex\classes.dex") -Destination (Join-Path $stage "classes.dex")
  if ($o.Lib -ne "none") {
    $align = if ($o.Lib -eq "align4096") { 4096 } else { 16384 }
    [System.IO.File]::WriteAllBytes((Join-Path $stage "lib/arm64-v8a/libfake.so"), (New-FakeElf $align))
  }
  if ($null -ne $o.Changelog) { [System.IO.File]::WriteAllText((Join-Path $stage "res/raw\changelog.md"), $o.Changelog, (New-Object System.Text.UTF8Encoding($false))) }
  $jar = Join-Path $env:JAVA_HOME "bin\jar.exe"
  $run = Invoke-Native -File $jar -Arguments @("--update", "--file", $base, "--no-compress", "--no-manifest", "-C", $stage, ".")
  if ($run.Code -ne 0) { throw ("jar fallito: " + ($run.Lines -join " | ")) }
  $aligned = Join-Path $work "aligned.apk"
  if ($o.Lib -eq "unaligned") {
    # Senza zipalign: la libreria resta dove l'ha messa jar, a un posto qualunque del file (zipalign 37 allinea le .so a 16 KB da solo).
    Copy-Item -LiteralPath $base -Destination $aligned
  } else {
    $run = Invoke-Native -File $tools.ZipAlign -Arguments @("-f", "-P", "16", "4", $base, $aligned)
    if ($run.Code -ne 0) { throw ("zipalign fallito: " + ($run.Lines -join " | ")) }
  }

  # La firma, con il keystore di prova del caso.
  $signed = Join-Path $Dir ($Name + ".apk")
  if (Test-Path -LiteralPath $signed) { Remove-Item -LiteralPath $signed -Force }
  $v3 = if ($o.V3) { "true" } else { "false" }
  # apksigner (build-tools 35 e successive) allinea da solo le .so a 16 KB: per il caso "non allineata" gli si dice di non toccare l'allineamento.
  $signerExtra = if ($o.Lib -eq "unaligned") { @("--alignment-preserved", "true") } else { @() }
  $run = Invoke-Native -File $tools.ApkSigner -Arguments (@("sign", "--ks", $o.Keystore, "--ks-key-alias", "pampa", "--ks-pass", ("pass:" + $o.Password), "--key-pass", ("pass:" + $o.Password),
    "--v1-signing-enabled", "true", "--v2-signing-enabled", "true", "--v3-signing-enabled", $v3, "--min-sdk-version", "29") + $signerExtra + @("--out", $signed, $aligned))
  if ($run.Code -ne 0) { throw ("apksigner sign fallito: " + ($run.Lines -join " | ")) }
  Remove-Item -LiteralPath $work -Recurse -Force
  return $signed
}
