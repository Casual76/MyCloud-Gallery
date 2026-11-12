# Libreria di tools\release\verifica-elenco.ps1 (pacchetto U, giro 1 delle critiche, US-1): la verifica, dopo la pubblicazione, della voce di un canale
# nell'elenco dell'app (manifest.json) contro l'APK finale conservato in locale. Si carica dopo release-lib-core.ps1 (Read-Utf8, Get-FileSha256,
# Test-VersionName). Solo ASCII, PowerShell 5.1.
#
# Nessuna rete qui dentro: lo scaricamento e la lettura dell'APK sono passati da chi chiama (-Download, -ReadFacts), cosi' le prove li fingono e lo
# strumento vero usa Invoke-GuardedDownload (host ammessi a ogni salto, come l'app) e apksigner/aapt2.

$script:ElencoAppId = "dev.pampa.mycloudgallery"
# = UpdateSourceConfig.MAX_APK_BYTES di feature-update.
$script:ElencoMaxApk = 400MB
$script:ElencoNamePattern = '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'
# = UpdateSourceConfig.DOWNLOAD_HOSTS di feature-update.
$script:ElencoDownloadHosts = @("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")

# Owner e repository dall'unico posto in cui stanno (feature-update, UpdateSource.kt): lo strumento non ne tiene una copia.
function Get-UpdateSourceRepo([string]$SourcePath) {
  $text = Read-Utf8 $SourcePath
  $owner = [regex]::Match($text, 'const val OWNER: String = "([^"]+)"')
  $repo = [regex]::Match($text, 'const val REPO: String = "([^"]+)"')
  if (-not $owner.Success -or -not $repo.Success) { throw "non trovo OWNER e REPO in $SourcePath" }
  return [pscustomobject]@{ Owner = $owner.Groups[1].Value; Repo = $repo.Groups[1].Value }
}

function Test-SafeAssetName([string]$Name) {
  return ($Name -cmatch $script:ElencoNamePattern) -and -not $Name.Contains("..")
}

# --- Un lettore minimo del JSON, solo per sapere DOVE sta un oggetto nel testo (le stringhe con graffe, virgolette e barre non lo ingannano) ---------

function Skip-JsonSpace([string]$T, [int]$i) {
  while ($i -lt $T.Length -and " `t`r`n".Contains([string]$T[$i])) { $i++ }
  return $i
}

# L'indice subito dopo la stringa che comincia a $i (le virgolette di apertura).
function Skip-JsonString([string]$T, [int]$i) {
  if ($T[$i] -ne '"') { throw "JSON: attesa una stringa alla posizione $i" }
  $i++
  while ($i -lt $T.Length) {
    $c = $T[$i]
    if ($c -eq [char]92) { $i += 2; continue }
    if ($c -eq '"') { return $i + 1 }
    $i++
  }
  throw "JSON: stringa senza fine"
}

# L'indice subito dopo il valore che comincia a $i (oggetto, lista, stringa, numero, true/false/null).
function Skip-JsonValue([string]$T, [int]$i) {
  $i = Skip-JsonSpace $T $i
  $c = $T[$i]
  if ($c -eq '"') { return (Skip-JsonString $T $i) }
  if ($c -eq '{' -or $c -eq '[') {
    $depth = 0
    while ($i -lt $T.Length) {
      $c = $T[$i]
      if ($c -eq '"') { $i = Skip-JsonString $T $i; continue }
      if ($c -eq '{' -or $c -eq '[') { $depth++ }
      elseif ($c -eq '}' -or $c -eq ']') { $depth--; if ($depth -eq 0) { return $i + 1 } }
      $i++
    }
    throw "JSON: oggetto senza fine"
  }
  while ($i -lt $T.Length -and -not ",}] `t`r`n".Contains([string]$T[$i])) { $i++ }
  return $i
}

# Dove sta (inizio '{', fine '}') l'oggetto al percorso $Path di chiavi (per esempio "app", "stable"); null se non c'e'.
function Find-JsonObjectSpan([string]$T, [string[]]$Path) {
  $i = Skip-JsonSpace $T 0
  foreach ($key in $Path) {
    if ($T[$i] -ne '{') { return $null }
    $i++
    $found = $false
    while ($true) {
      $i = Skip-JsonSpace $T $i
      if ($T[$i] -eq '}') { return $null }
      $keyEnd = Skip-JsonString $T $i
      $name = $T.Substring($i + 1, $keyEnd - $i - 2)
      $i = Skip-JsonSpace $T $keyEnd
      if ($T[$i] -ne ':') { throw "JSON: attesi i due punti alla posizione $i" }
      $i = Skip-JsonSpace $T ($i + 1)
      if ($name -ceq $key) { $found = $true; break }
      $i = Skip-JsonSpace $T (Skip-JsonValue $T $i)
      if ($T[$i] -eq ',') { $i++ }
    }
    if (-not $found) { return $null }
  }
  if ($T[$i] -ne '{') { return $null }
  return [pscustomobject]@{ Start = $i; End = (Skip-JsonValue $T $i) - 1 }
}

# Aggiunge in fondo all'oggetto $Span le righe "chiave": valore di $Fields (testo gia' pronto), con il rientro delle altre righe dell'oggetto.
function Add-JsonFields([string]$T, $Span, [string[]]$Fields) {
  $eol = if ($T.Contains("`r`n")) { "`r`n" } else { "`n" }
  $inside = $T.Substring($Span.Start + 1, $Span.End - $Span.Start - 1)
  $indent = "    "
  $match = [regex]::Match($inside, '\n([ \t]+)"')
  if ($match.Success) { $indent = $match.Groups[1].Value }
  $last = $Span.End - 1
  while ($last -gt $Span.Start -and " `t`r`n".Contains([string]$T[$last])) { $last-- }
  $empty = ($last -eq $Span.Start)
  $text = ""
  foreach ($field in $Fields) {
    if ($text -or -not $empty) { $text += "," }
    $text += $eol + $indent + $field
  }
  return $T.Substring(0, $last + 1) + $text + $T.Substring($last + 1)
}

# --- Lo scaricamento vero (solo lo strumento vero, mai le prove) -----------------------------------------------------------------------------------

# Scarica ESATTAMENTE $Url in $Target come fa l'app: solo https, ogni salto verso un host ammesso, al piu' 3 rimandi seguiti a mano, nessun cookie,
# al piu' $MaxBytes. Lancia se qualcosa non va.
function Invoke-GuardedDownload([string]$Url, [string]$Target, [long]$MaxBytes) {
  [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
  $current = [Uri]$Url
  for ($hop = 0; $hop -le 3; $hop++) {
    if ($current.Scheme -ne "https" -or $script:ElencoDownloadHosts -notcontains $current.Host.ToLowerInvariant() -or $current.UserInfo) {
      throw "indirizzo non ammesso: $($current.Host)"
    }
    $request = [Net.HttpWebRequest]::Create($current)
    $request.AllowAutoRedirect = $false
    $request.UserAgent = "MyCloudGallery-verifica-elenco"
    $request.CookieContainer = $null
    try { $response = $request.GetResponse() } catch [Net.WebException] { $response = $_.Exception.Response; if (-not $response) { throw } }
    try {
      $code = [int]$response.StatusCode
      if ($code -ge 300 -and $code -lt 400) {
        $location = $response.Headers["Location"]
        if (-not $location) { throw "rimando senza indirizzo" }
        $current = New-Object Uri($current, $location)
        continue
      }
      if ($code -ne 200) { throw "risposta $code" }
      if ($response.ContentLength -gt $MaxBytes) { throw "file troppo grande" }
      $in = $response.GetResponseStream()
      $out = [IO.File]::Create($Target)
      try {
        $buffer = New-Object byte[] 65536
        $total = 0L
        while (($read = $in.Read($buffer, 0, $buffer.Length)) -gt 0) {
          $total += $read
          if ($total -gt $MaxBytes) { throw "file troppo grande" }
          $out.Write($buffer, 0, $read)
        }
      } finally { $out.Dispose(); $in.Dispose() }
      return
    } finally { $response.Dispose() }
  }
  throw "troppi rimandi"
}

# --- La verifica --------------------------------------------------------------------------------------------------------------------------------------

# Verifica la voce del canale $Channel nell'elenco $ElencoPath dopo la pubblicazione della versione $Version, contro l'APK finale $Apk:
#  1. la versione ha la forma ammessa, l'elenco e' dell'app e del repository giusto (owner e repo di UpdateSource.kt), la voce dice $Version, tag e file
#     hanno nomi sicuri;
#  2. l'APK locale e' firmato con pampa.jks (impronta di docs/sicurezza.md par. 5.6, un firmatario), e' questa app, con versionName $Version e il
#     versionCode di release/versions.txt;
#  3. si scarica ESATTAMENTE l'asset che la voce indica (https://github.com/<owner>/<repo>/releases/download/<tag>/<file>) e deve essere byte per
#     byte l'APK locale (stesso peso, stesso SHA-256);
#  4. sha256 e sizeBytes della voce, se ci sono, devono essere quelli; se mancano (un elenco scritto prima della correzione del publisher) si scrivono,
#     nella voce di quel canale e in quella dell'altro canale se indica lo stesso file, solo se l'elenco non e' cambiato nel frattempo, in un colpo.
# Ritorna Problems (vuoto = a posto) e Wrote (i campi scritti). Niente commit, niente push.
function Invoke-ElencoCheck {
  param(
    [string]$Channel,
    [string]$Version,
    [string]$Apk,
    [string]$ElencoPath,
    [string]$Owner,
    [string]$Repo,
    [string]$ExpectedFingerprint,
    [int]$ExpectedCode,
    [scriptblock]$Download,
    [scriptblock]$ReadFacts,
    [string]$WorkDir
  )
  $problems = New-Object System.Collections.Generic.List[string]
  $wrote = New-Object System.Collections.Generic.List[string]
  $result = { [pscustomobject]@{ Problems = @($problems); Wrote = @($wrote) } }

  # 1. La forma e l'elenco.
  if (@("stable", "beta") -notcontains $Channel) { $problems.Add("canale '$Channel' non valido (stable o beta)"); return (& $result) }
  if (-not (Test-VersionName $Version)) { $problems.Add("versione '$Version' fuori forma (1.0.0, oppure 1.1.0-beta.02)") }
  if ($Channel -eq "stable" -and $Version.Contains("-")) { $problems.Add("il canale stable vuole una versione senza -beta.NN") }
  if (-not (Test-Path -LiteralPath $ElencoPath)) { $problems.Add("manca l'elenco $ElencoPath"); return (& $result) }
  $before = Get-FileSha256 $ElencoPath
  $text = Read-Utf8 $ElencoPath
  try { $json = $text | ConvertFrom-Json } catch { $problems.Add("l'elenco non e' JSON valido"); return (& $result) }
  $app = $json.app
  if (-not $app) { $problems.Add("l'elenco non ha 'app'"); return (& $result) }
  if ($app.repository -and ($app.repository.repoOwner -cne $Owner -or $app.repository.repoName -cne $Repo)) {
    $problems.Add("l'elenco indica il repository $($app.repository.repoOwner)/$($app.repository.repoName), non $Owner/$Repo")
  }
  $entry = $app.$Channel
  if (-not $entry) { $problems.Add("l'elenco non ha la voce '$Channel'"); return (& $result) }
  if ($entry.version -cne $Version) { $problems.Add("la voce '$Channel' dice la versione '$($entry.version)', non $Version") }
  $tag = [string]$entry.releaseTag
  $asset = [string]$entry.apkAsset
  if (-not (Test-SafeAssetName $tag)) { $problems.Add("tag '$tag' non ammesso") }
  if (-not (Test-SafeAssetName $asset) -or -not $asset.EndsWith(".apk")) { $problems.Add("file '$asset' non ammesso") }

  # 2. L'APK locale.
  if (-not (Test-Path -LiteralPath $Apk)) { $problems.Add("manca l'APK finale $Apk") }
  if ($problems.Count -gt 0) { return (& $result) }
  $localSize = (Get-Item -LiteralPath $Apk).Length
  $localSha = Get-FileSha256 $Apk
  if ($localSize -le 0 -or $localSize -gt $script:ElencoMaxApk) { $problems.Add("l'APK pesa $localSize byte: fuori dai limiti dell'app") }
  $facts = & $ReadFacts $Apk
  if (-not $facts.Verified) { $problems.Add("la firma dell'APK non si verifica (apksigner)") }
  if ($facts.Signers -ne 1) { $problems.Add("l'APK ha $($facts.Signers) firmatari, ne serve uno") }
  if (-not $ExpectedFingerprint) { $problems.Add("manca l'impronta di pampa.jks in docs/sicurezza.md par. 5.6") }
  elseif ([string]$facts.CertSha256 -ne $ExpectedFingerprint.ToLowerInvariant()) { $problems.Add("l'APK non e' firmato con pampa.jks (impronta del certificato diversa)") }
  if ($facts.Package -cne $script:ElencoAppId) { $problems.Add("l'APK e' del pacchetto '$($facts.Package)', non $($script:ElencoAppId)") }
  if ($facts.VersionName -cne $Version) { $problems.Add("l'APK ha versionName '$($facts.VersionName)', non $Version") }
  if ([int]$facts.VersionCode -ne $ExpectedCode) { $problems.Add("l'APK ha versionCode $($facts.VersionCode), release/versions.txt dice $ExpectedCode") }
  if ($problems.Count -gt 0) { return (& $result) }

  # 3. L'asset pubblicato, proprio quello della voce.
  $url = "https://github.com/$Owner/$Repo/releases/download/$tag/$asset"
  if (-not (Test-Path -LiteralPath $WorkDir)) { New-Item -ItemType Directory -Path $WorkDir -Force | Out-Null }
  $downloaded = Join-Path $WorkDir ("scaricato-" + [guid]::NewGuid().ToString("N") + ".apk")
  try {
    try { & $Download $url $downloaded } catch { $problems.Add("non riesco a scaricare $url ($($_.Exception.Message))"); return (& $result) }
    if (-not (Test-Path -LiteralPath $downloaded)) { $problems.Add("lo scaricamento di $url non ha prodotto un file"); return (& $result) }
    $remoteSize = (Get-Item -LiteralPath $downloaded).Length
    $remoteSha = Get-FileSha256 $downloaded
    if ($remoteSize -ne $localSize -or $remoteSha -ne $localSha) {
      $problems.Add("l'asset pubblicato ($remoteSize byte, $($remoteSha.Substring(0, 12))...) non e' l'APK locale ($localSize byte, $($localSha.Substring(0, 12))...)")
      return (& $result)
    }
  } finally {
    Remove-Item -LiteralPath $downloaded -Force -ErrorAction SilentlyContinue
  }

  # 4. sha256 e peso nella voce (e nell'altra voce, se indica lo stesso file).
  $channels = @($Channel)
  $other = if ($Channel -eq "stable") { "beta" } else { "stable" }
  $twin = $app.$other
  if ($twin -and $twin.version -ceq $entry.version -and [string]$twin.releaseTag -ceq $tag -and [string]$twin.apkAsset -ceq $asset) { $channels += $other }
  $plans = @()
  foreach ($name in $channels) {
    $e = $app.$name
    $fields = @()
    $sha = [string]$e.sha256
    if ($sha) {
      if ($sha -cne $localSha) { $problems.Add("la voce '$name' dice sha256 $($sha.Substring(0, [Math]::Min(12, $sha.Length)))..., l'APK pubblicato e' $($localSha.Substring(0, 12))...") }
    } else {
      $fields += ('"sha256": "' + $localSha + '"')
    }
    $size = 0L
    if ($null -ne $e.sizeBytes) { [void][long]::TryParse([string]$e.sizeBytes, [ref]$size) }
    if ($null -ne $e.sizeBytes -and $size -gt 0) {
      if ($size -ne $localSize) { $problems.Add("la voce '$name' dice sizeBytes $size, l'APK pubblicato pesa $localSize") }
    } elseif ($null -ne $e.sizeBytes) {
      $problems.Add("la voce '$name' ha sizeBytes non valido: si corregge a mano, non lo sovrascrivo")
    } else {
      $fields += ('"sizeBytes": ' + $localSize)
    }
    if ($fields.Count -gt 0) { $plans += [pscustomobject]@{ Channel = $name; Fields = $fields } }
  }
  if ($problems.Count -gt 0 -or $plans.Count -eq 0) { return (& $result) }

  # La scrittura: solo se l'elenco e' quello letto all'inizio; il testo nuovo si rilegge prima di sostituire il file.
  if ((Get-FileSha256 $ElencoPath) -ne $before) { $problems.Add("l'elenco e' cambiato mentre lo verificavo: non scrivo niente, rilancia"); return (& $result) }
  $newText = $text
  foreach ($plan in $plans) {
    $span = Find-JsonObjectSpan $newText @("app", $plan.Channel)
    if (-not $span) { $problems.Add("non trovo la voce '$($plan.Channel)' nel testo dell'elenco"); return (& $result) }
    $newText = Add-JsonFields $newText $span $plan.Fields
  }
  try { $check = $newText | ConvertFrom-Json } catch { $problems.Add("il testo nuovo dell'elenco non e' JSON valido: non scrivo niente"); return (& $result) }
  foreach ($name in $channels) {
    if ([string]$check.app.$name.sha256 -cne $localSha -or [long]$check.app.$name.sizeBytes -ne $localSize) { $problems.Add("rilettura della voce '$name' non coerente: non scrivo niente"); return (& $result) }
  }
  $temp = $ElencoPath + ".nuovo"
  [IO.File]::WriteAllText($temp, $newText, (New-Object Text.UTF8Encoding($false)))
  if ((Get-FileSha256 $ElencoPath) -ne $before) { Remove-Item -LiteralPath $temp -Force; $problems.Add("l'elenco e' cambiato mentre lo scrivevo: non scrivo niente, rilancia"); return (& $result) }
  Move-Item -LiteralPath $temp -Destination $ElencoPath -Force
  foreach ($plan in $plans) { foreach ($field in $plan.Fields) { $wrote.Add($plan.Channel + ": " + ($field -split ':', 2)[0].Trim('"')) } }
  return (& $result)
}

# I fatti dell'APK con apksigner e aapt2 (come release-lib-verify.ps1): firma verificata, firmatari, impronta del certificato, pacchetto e versioni.
function Get-ApkIdentity($Tools, [string]$Apk) {
  $facts = [ordered]@{ Verified = $false; Signers = 0; CertSha256 = ""; Package = ""; VersionCode = 0; VersionName = "" }
  $verify = Invoke-Native -File $Tools.ApkSigner -Arguments @("verify", "--print-certs", "-v", "--min-sdk-version", "24", $Apk)
  $facts.Verified = ($verify.Code -eq 0)
  $digests = @{}
  foreach ($line in $verify.Lines) {
    if ($line -match 'Number of signers:\s*(\d+)') { $facts.Signers = [int]$Matches[1] }
    elseif ($line -match 'certificate SHA-256 digest:\s*([0-9a-fA-F:]+)') { $digests[($Matches[1] -replace ':', '').ToLowerInvariant()] = $true }
  }
  if ($digests.Count -eq 1) { $facts.CertSha256 = @($digests.Keys)[0] }
  $badging = Invoke-Native -File $Tools.Aapt2 -Arguments @("dump", "badging", $Apk)
  foreach ($line in $badging.Lines) {
    if ($line -match "^package: name='([^']*)' versionCode='(\d+)' versionName='([^']*)'") {
      $facts.Package = $Matches[1]; $facts.VersionCode = [int]$Matches[2]; $facts.VersionName = $Matches[3]
      break
    }
  }
  return [pscustomobject]$facts
}
