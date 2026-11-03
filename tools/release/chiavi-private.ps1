# Il riconoscimento delle chiavi private nel CONTENUTO dei file (passo 3b di tools\check.ps1; C442; R6 della revisione di sicurezza della
# Fase 10). Lo carica con il punto (". file") tools\check.ps1 e lo prova tools\release\chiavi-prove.ps1 con chiavi vere generate da Java.
#
# Le forme riconosciute: l'intestazione PEM di una chiave privata; e, per ogni inizio di struttura DER (PKCS#8 EC P-256 nella forma corta
# di getEncoded() di Java e in quella lunga con la chiave pubblica, SEC1 di OpenSSL, PKCS#8 RSA), il suo testo in base64 (nei tre
# allineamenti possibili: solo i caratteri che l'inizio determina per intero), in esadecimale (anche con separatori, 0x, .toByte()),
# in decimale (Arrays.toString, con o senza segno) e come byte grezzi (un file .der). Anche un file UTF-16 (reindirizzamento di
# Windows PowerShell 5.1) si legge. NON si vedono: base64 o esadecimale spezzati su piu' righe o piu' stringhe, file compressi, cifrati o
# nei formati binari saltati (immagini, archivi, modelli); chiavi non P-256 e RSA solo nella forma PKCS#8 (quella di Java; PKCS#1 solo
# se in PEM). Non ne usiamo altre.
#
# Questo file non contiene mai un marcatore intero (le strutture si compongono a pezzi a ogni esecuzione), cosi' non si segnala da solo.

# Le strutture ASN.1 a pezzi (esadecimale). Un pezzo solo non e' una chiave: la chiave pubblica contiene l'identificatore dell'algoritmo.
$script:KeyOidEc = "2a8648ce3d0201"       # id-ecPublicKey
$script:KeyOidP256 = "2a8648ce3d030107"   # prime256v1
$script:KeyAlgEc = "3013" + "0607" + $script:KeyOidEc + "0608" + $script:KeyOidP256
$script:KeyEcInner = "020101" + "0420"    # ECPrivateKey: versione 1, poi i 32 byte della chiave

# Gli inizi riconosciuti: nome e byte (esadecimale) fino al primo byte della chiave, che e' variabile.
function Get-PrivateKeyPrefixes {
  return @(
    [pscustomobject]@{ Name = "PKCS#8 EC P-256 (forma corta, quella di getEncoded di Java)"; Hex = "3041" + "020100" + $script:KeyAlgEc + "0427" + "3025" + $script:KeyEcInner },
    [pscustomobject]@{ Name = "PKCS#8 EC P-256 (forma lunga, con la chiave pubblica)"; Hex = "308187" + "020100" + $script:KeyAlgEc + "046d" + "306b" + $script:KeyEcInner },
    [pscustomobject]@{ Name = "SEC1 EC P-256 (OpenSSL, con la chiave pubblica)"; Hex = "3077" + $script:KeyEcInner },
    [pscustomobject]@{ Name = "SEC1 EC P-256 (senza la chiave pubblica)"; Hex = "3031" + $script:KeyEcInner },
    [pscustomobject]@{ Name = "PKCS#8 RSA"; Hex = "020100" + "300d" + "06092a864886f70d010101" + "0500" + "0482" }
  )
}

function ConvertFrom-HexText([string]$hex) {
  $bytes = New-Object byte[] ($hex.Length / 2)
  for ($i = 0; $i -lt $bytes.Length; $i++) { $bytes[$i] = [Convert]::ToByte($hex.Substring(2 * $i, 2), 16) }
  return , $bytes
}

# Il base64 che l'inizio determina per intero, per ognuno dei tre allineamenti (quanti byte lo precedono nel gruppo da tre). Il
# carattere in fondo che dipende dal primo byte della chiave (e i precedenti incerti) non entra: era il difetto del marcatore di P0.
function Get-Base64Markers([byte[]]$prefix) {
  $markers = @()
  foreach ($before in 0, 1, 2) {
    $padded = New-Object byte[] ($before + $prefix.Length)
    [Array]::Copy($prefix, 0, $padded, $before, $prefix.Length)
    $text = [Convert]::ToBase64String($padded)
    $first = [int][Math]::Ceiling(8 * $before / 6.0)
    $last = [int][Math]::Floor(8 * ($before + $prefix.Length) / 6.0) - 1
    $markers += $text.Substring($first, $last - $first + 1)
  }
  return $markers
}

function Get-HexPattern([byte[]]$prefix) {
  $parts = foreach ($b in $prefix) { '(?:0x|\\x)?' + $b.ToString("x2") + '(?:\.toByte\(\))?' }
  return '(?i:' + ($parts -join '[\s,:;_-]{0,3}') + ')'
}

function Get-DecimalPattern([byte[]]$prefix) {
  $parts = foreach ($b in $prefix) {
    $value = [int]$b
    $number = if ($value -ge 128) { "(?:$value|$($value - 256))" } else { "$value" }
    '(?<!\d)' + $number + '(?!\d)'
  }
  return ($parts -join '\s*[,;]\s*')
}

function Get-RawText([byte[]]$prefix) {
  return (-join ($prefix | ForEach-Object { [char]$_ }))
}

# Le forme. Per velocita' (il passo legge ~5000 file) i testi fissi (base64, byte grezzi) si cercano con IndexOf e le espressioni
# regolari sono poche: il PEM, l'esadecimale con i separatori, il decimale. Una per tutte le forme: lo spezzarle una a una costa
# di piu', il compilarle ancora di piu'.
function Get-PrivateKeyForms {
  if ($script:PrivateKeyForms) { return $script:PrivateKeyForms }
  $forms = @([pscustomobject]@{ Name = "PEM"; Literals = @(); Patterns = @('-----BEGIN [A-Z0-9 ]*' + "PRIVATE KEY" + '( BLOCK)?-----') })
  foreach ($prefix in Get-PrivateKeyPrefixes) {
    $bytes = ConvertFrom-HexText $prefix.Hex
    $literals = @(Get-Base64Markers $bytes)
    $literals += Get-RawText $bytes
    $forms += [pscustomobject]@{ Name = $prefix.Name; Literals = $literals; Patterns = @((Get-HexPattern $bytes), (Get-DecimalPattern $bytes)) }
  }
  $script:PrivateKeyForms = $forms
  $options = [System.Text.RegularExpressions.RegexOptions]::None
  $script:PrivateKeyLiterals = @($forms | ForEach-Object { $_.Literals } | Where-Object { $_ })
  $script:PrivateKeyRegexes = @(
    [regex]::new($forms[0].Patterns[0], $options),
    [regex]::new((($forms | Select-Object -Skip 1 | ForEach-Object { $_.Patterns[0] }) -join "|"), $options),
    [regex]::new((($forms | Select-Object -Skip 1 | ForEach-Object { $_.Patterns[1] }) -join "|"), $options)
  )
  return $forms
}

# Vero se il testo contiene una chiave privata (la via veloce).
function Test-PrivateKeyText([string]$text) {
  Get-PrivateKeyForms | Out-Null
  foreach ($literal in $script:PrivateKeyLiterals) {
    if ($text.IndexOf($literal, [System.StringComparison]::Ordinal) -ge 0) { return $true }
  }
  foreach ($regex in $script:PrivateKeyRegexes) { if ($regex.IsMatch($text)) { return $true } }
  return $false
}

# I testi da cercare in un file: i byte come Latin-1 (un carattere per byte: il DER grezzo e il resto) e, se il file e' UTF-16 (con il
# segno d'ordine dei byte o con un carattere su due a zero), anche letto cosi'.
function Get-ScanTexts([byte[]]$bytes) {
  $texts = @([System.Text.Encoding]::GetEncoding(28591).GetString($bytes))
  $little = $bytes.Length -ge 2 -and $bytes[0] -eq 0xFF -and $bytes[1] -eq 0xFE
  $big = $bytes.Length -ge 2 -and $bytes[0] -eq 0xFE -and $bytes[1] -eq 0xFF
  if (-not ($little -or $big)) {
    $sample = [Math]::Min($bytes.Length, 1024)
    $evenZero = 0; $oddZero = 0
    for ($i = 0; $i -lt $sample; $i++) { if ($bytes[$i] -eq 0) { if ($i % 2 -eq 0) { $evenZero++ } else { $oddZero++ } } }
    $little = $sample -ge 64 -and $oddZero * 3 -gt $sample
    $big = $sample -ge 64 -and $evenZero * 3 -gt $sample
  }
  if ($little) { $texts += [System.Text.Encoding]::Unicode.GetString($bytes) }
  elseif ($big) { $texts += [System.Text.Encoding]::BigEndianUnicode.GetString($bytes) }
  return $texts
}

# Il nome della prima forma che riconosce il testo (solo per il messaggio: la via lenta, per i pochi file segnalati).
function Get-PrivateKeyFormName([string[]]$texts) {
  foreach ($form in Get-PrivateKeyForms) {
    foreach ($text in $texts) {
      foreach ($literal in $form.Literals) { if ($text.IndexOf($literal, [System.StringComparison]::Ordinal) -ge 0) { return $form.Name } }
      foreach ($pattern in $form.Patterns) { if ([regex]::IsMatch($text, $pattern)) { return $form.Name } }
    }
  }
  return "?"
}

# Legge i file (percorsi relativi a $Root, con la barra) e dice quali contengono una chiave privata. Fuori da `src/test/` e
# `src/testDebug/` (dove possono stare solo chiavi di prova generate apposta: KT, keystore di prova) e' un problema.
function Find-PrivateKeyFiles {
  param([string]$Root, [string[]]$Paths)
  $binary = '\.(png|jpg|jpeg|webp|gif|heic|jar|aar|so|tflite|onnx|litertlm|task|zip|gz|ttf|otf|pbf|mbtiles|db|keystore)$'
  $problems = @()
  $inTests = @()
  $read = 0
  foreach ($path in @($Paths | Where-Object { $_.Trim().Length -gt 0 })) {
    if ($path -match $binary) { continue }
    $full = Join-Path $Root $path
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { continue }
    if ((Get-Item -LiteralPath $full).Length -gt 4MB) { continue }
    $read++
    $texts = Get-ScanTexts ([System.IO.File]::ReadAllBytes($full))
    $hit = $false
    foreach ($text in $texts) { if (Test-PrivateKeyText $text) { $hit = $true; break } }
    if (-not $hit) { continue }
    if ($path -match '(^|/)src/(test|testDebug)/') { $inTests += $path; continue }
    $problems += ("{0} ({1})" -f $path, (Get-PrivateKeyFormName $texts))
  }
  return [pscustomobject]@{ Read = $read; Problems = $problems; InTests = $inTests }
}
