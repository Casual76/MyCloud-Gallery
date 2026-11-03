<#
.SYNOPSIS
  Prove del passo 3b di tools\check.ps1 (nessuna chiave privata nel contenuto dei file) con chiavi VERE generate da Java sotto
  build\prova-chiavi\ (mai committate; Fase 10, R6 della revisione di sicurezza, C442).

.DESCRIPTION
  Java (un sorgente scritto sotto build\) genera chiavi EC P-256 e RSA vere e le scrive nelle forme che produce o che si ottengono
  da getEncoded(): PKCS#8 corta (le quattro classi del primo byte della chiave: il difetto del marcatore di P0 era il quarto
  carattere a seconda di quel byte), PKCS#8 lunga con la chiave pubblica (verificata rileggendola con KeyFactory), SEC1 con e senza
  la pubblica, RSA; ognuna in DER grezzo, base64 (una riga, MIME, a 64 colonne, con 1 o 2 byte davanti), PEM, esadecimale
  (minuscolo, maiuscolo, con i due punti), decimale (con e senza segno), array alla Kotlin e UTF-16. Poi controlla che il passo le
  trovi TUTTE fuori da `src/test/`, che le trovi anche la copia della vera KT (core-release/src/test) messa fuori dai test, che le
  stesse sotto `src/test/` non siano un problema, e che le chiavi pubbliche, i byte casuali e i "quasi uguali" non siano segnalati.
  Codice di uscita 0 solo se tutte le prove passano. Lo lancia anche tools\check.ps1.

.PARAMETER Libreria
  Il file con `Find-PrivateKeyFiles` (di serie chiavi-private.ps1 accanto a questo script); serve solo a provare la prova con un
  marcatore sbagliato (una prova che non morde non prova niente).
#>
[CmdletBinding()]
param([string]$Libreria)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
if (-not $Libreria) { $Libreria = Join-Path $PSScriptRoot "chiavi-private.ps1" }
. $Libreria
$work = Join-Path $root "build\prova-chiavi"
if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
$tree = Join-Path $work "albero"
New-Item -ItemType Directory -Path (Join-Path $tree "tools\chiavi") -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $tree "tools\neutri") -Force | Out-Null
$failures = New-Object System.Collections.Generic.List[string]
$count = 0

function Assert-That([string]$name, [bool]$condition, [string]$detail = "") {
  $script:count++
  if (-not $condition) { $script:failures.Add($name + $(if ($detail) { ": " + $detail } else { "" })); Write-Host ("  [KO] " + $name + $(if ($detail) { "`n" + $detail.Substring(0, [Math]::Min(900, $detail.Length)) } else { "" })) -ForegroundColor Red }
  else { Write-Host ("  [ok] " + $name) -ForegroundColor DarkGray }
}

function Find-JavaExe {
  if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin\java.exe"))) { return (Join-Path $env:JAVA_HOME "bin\java.exe") }
  $command = Get-Command java -ErrorAction SilentlyContinue
  if ($command) { return $command.Source }
  $candidate = Get-ChildItem "C:\Program Files\Microsoft" -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
  if ($candidate) { return (Join-Path $candidate.FullName "bin\java.exe") }
  return $null
}

$javaSource = @'
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;

public class ChiaviProva {
  static Path positive;
  static Path neutral;

  static byte[] cat(byte[]... parts) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    for (byte[] p : parts) b.write(p);
    return b.toByteArray();
  }

  static byte[] der(int tag, byte[] body) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    b.write(tag);
    int n = body.length;
    if (n < 128) b.write(n);
    else if (n < 256) { b.write(0x81); b.write(n); }
    else { b.write(0x82); b.write(n >> 8); b.write(n & 0xff); }
    b.write(body);
    return b.toByteArray();
  }

  static byte[] hexBytes(String hex) {
    byte[] r = new byte[hex.length() / 2];
    for (int i = 0; i < r.length; i++) r[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
    return r;
  }

  static String hex(byte[] b, String sep, boolean upper) {
    StringBuilder s = new StringBuilder();
    for (int i = 0; i < b.length; i++) {
      if (i > 0) s.append(sep);
      s.append(String.format(upper ? "%02X" : "%02x", b[i] & 0xff));
    }
    return s.toString();
  }

  static void put(Path dir, String name, byte[] data) throws IOException { Files.write(dir.resolve(name), data); }
  static void put(Path dir, String name, String text) throws IOException { put(dir, name, text.getBytes(StandardCharsets.ISO_8859_1)); }

  // Le forme in cui una chiave DER puo' finire in un file.
  static void forms(String name, byte[] key) throws IOException {
    String b64 = Base64.getEncoder().encodeToString(key);
    put(positive, name + ".der", key);
    put(positive, name + ".b64.txt", "chiave = \"" + b64 + "\"\n");
    put(positive, name + ".mime.txt", Base64.getMimeEncoder().encodeToString(key) + "\r\n");
    put(positive, name + ".b64w64.txt", Base64.getMimeEncoder(64, new byte[] { '\n' }).encodeToString(key) + "\n");
    put(positive, name + ".al1.txt", Base64.getEncoder().encodeToString(cat(new byte[] { 0x11 }, key)) + "\n");
    put(positive, name + ".al2.txt", Base64.getEncoder().encodeToString(cat(new byte[] { 0x11, 0x22 }, key)) + "\n");
    put(positive, name + ".pem.txt", "-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, new byte[] { '\n' }).encodeToString(key) + "\n-----END " + "PRIVATE KEY-----\n");
    put(positive, name + ".hex.txt", hex(key, "", false) + "\n");
    put(positive, name + ".hexmaiusc.txt", hex(key, "", true) + "\n");
    put(positive, name + ".due-punti.txt", hex(key, ":", false) + "\n");
    put(positive, name + ".dec.txt", Arrays.toString(key) + "\n");
    StringBuilder unsigned = new StringBuilder();
    StringBuilder kotlin = new StringBuilder("val chiave = byteArrayOf(");
    for (int i = 0; i < key.length; i++) {
      int v = key[i] & 0xff;
      if (i > 0) { unsigned.append(", "); kotlin.append(", "); }
      unsigned.append(v);
      kotlin.append(String.format(v >= 0x80 ? "0x%02x.toByte()" : "0x%02x", v));
    }
    put(positive, name + ".decu.txt", "[" + unsigned + "]\n");
    put(positive, name + ".kotlin.txt", kotlin + ")\n");
    put(positive, name + ".utf16.txt", ("\uFEFF" + b64 + "\r\n").getBytes(StandardCharsets.UTF_16LE));
  }

  public static void main(String[] args) throws Exception {
    Path out = Paths.get(args[0]);
    positive = out.resolve("chiavi");
    neutral = out.resolve("neutri");
    KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
    ec.initialize(new ECGenParameterSpec("secp256r1"));
    // le quattro classi del primo byte della chiave (i due bit alti): cambiano il quarto carattere del base64 dopo l'intestazione
    KeyPair[] byClass = new KeyPair[4];
    int seen = 0;
    for (int i = 0; i < 4000 && seen < 4; i++) {
      KeyPair kp = ec.generateKeyPair();
      byte[] p8 = kp.getPrivate().getEncoded();
      if (p8.length != 67) continue;
      int c = (p8[35] & 0xff) >> 6;
      if (byClass[c] == null) { byClass[c] = kp; seen++; }
    }
    if (seen < 4) throw new IllegalStateException("non trovo le quattro classi del primo byte");
    for (int c = 0; c < 4; c++) forms("ec-corta-" + c, byClass[c].getPrivate().getEncoded());

    KeyPair kp = byClass[0];
    byte[] p8 = kp.getPrivate().getEncoded();
    byte[] d = Arrays.copyOfRange(p8, 35, 67);
    byte[] spki = kp.getPublic().getEncoded();
    byte[] point = Arrays.copyOfRange(spki, spki.length - 65, spki.length);
    byte[] oidP256 = der(0x06, hexBytes("2a8648ce3d030107"));
    byte[] alg = der(0x30, cat(der(0x06, hexBytes("2a8648ce3d0201")), oidP256));
    byte[] pub = der(0xa1, der(0x03, cat(new byte[] { 0 }, point)));
    byte[] version1 = der(0x02, new byte[] { 1 });
    byte[] longForm = der(0x30, cat(der(0x02, new byte[] { 0 }), alg, der(0x04, der(0x30, cat(version1, der(0x04, d), pub)))));
    // la forma lunga deve essere una chiave che Java rilegge, e che e' la stessa
    PrivateKey back = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(longForm));
    if (!((java.security.interfaces.ECPrivateKey) back).getS().equals(((java.security.interfaces.ECPrivateKey) kp.getPrivate()).getS())) throw new IllegalStateException("la forma lunga non e' la stessa chiave");
    forms("ec-lunga", longForm);
    forms("sec1-con-pubblica", der(0x30, cat(version1, der(0x04, d), der(0xa0, oidP256), pub)));
    forms("sec1-senza-pubblica", der(0x30, cat(version1, der(0x04, d), der(0xa0, oidP256))));

    KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(2048);
    KeyPair rk = rsa.generateKeyPair();
    forms("rsa-2048", rk.getPrivate().getEncoded());

    // i neutri: le chiavi pubbliche, byte casuali, un quasi uguale (un byte dell'inizio sbagliato)
    put(neutral, "pubblica-ec.b64.txt", Base64.getEncoder().encodeToString(spki) + "\n");
    put(neutral, "pubblica-ec.hex.txt", hex(spki, "", false) + "\n");
    put(neutral, "pubblica-rsa.b64.txt", Base64.getEncoder().encodeToString(rk.getPublic().getEncoded()) + "\n");
    byte[] random = new byte[300];
    new SecureRandom().nextBytes(random);
    put(neutral, "casuali.b64.txt", Base64.getEncoder().encodeToString(random) + "\n");
    put(neutral, "casuali.hex.txt", hex(random, "", false) + "\n");
    byte[] near = Arrays.copyOf(p8, 35);
    near[12] ^= 0x40;
    put(neutral, "quasi.hex.txt", hex(near, "", false) + "\n");
    put(neutral, "quasi.b64.txt", Base64.getEncoder().encodeToString(near) + "\n");
    put(neutral, "testo.txt", "Una riga qualunque, 48, 65, 2, 1, 0, 48, 19 e basta.\n");
  }
}
'@

$java = Find-JavaExe
if (-not $java) { Write-Host "ERRORE: non trovo java (JAVA_HOME, PATH o C:\Program Files\Microsoft\jdk-17*): senza non genero le chiavi" -ForegroundColor Red; exit 1 }
$sourcePath = Join-Path $work "ChiaviProva.java"
[System.IO.File]::WriteAllText($sourcePath, $javaSource, (New-Object System.Text.UTF8Encoding($false)))
$previous = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try {
  $lines = @(& $java $sourcePath (Join-Path $tree "tools") 2>&1 | ForEach-Object { $_.ToString() })
  $javaCode = $LASTEXITCODE
} finally {
  $ErrorActionPreference = $previous
}
Write-Host "Generazione delle chiavi con Java"
Assert-That "java ha generato le chiavi" ($javaCode -eq 0) ($lines -join "`n")
if ($javaCode -ne 0) { Write-Host "chiavi-prove: Java e' fallito" -ForegroundColor Red; exit 1 }

$positives = @(Get-ChildItem -LiteralPath (Join-Path $tree "tools\chiavi") -File | ForEach-Object { "tools/chiavi/" + $_.Name })
$neutrals = @(Get-ChildItem -LiteralPath (Join-Path $tree "tools\neutri") -File | ForEach-Object { "tools/neutri/" + $_.Name })
Assert-That "le chiavi generate sono tante (4 corte, lunga, due SEC1 e RSA, ognuna in 14 forme)" ($positives.Count -eq 8 * 14) ("trovati " + $positives.Count)

# La vera KT (la chiave di prova del manifest) fuori dai test e dentro i test; la stessa chiave sotto src/test/ non e' un problema.
$ktSource = Join-Path $root "core-release\src\test\kotlin\dev\pampa\mycloudgallery\core\release\testkeys\ReleaseTestPrivateKey.kt"
Assert-That "la vera KT c'e' (core-release/src/test)" (Test-Path -LiteralPath $ktSource)
New-Item -ItemType Directory -Path (Join-Path $tree "tools\copie"), (Join-Path $tree "core-x\src\test\kotlin"), (Join-Path $tree "core-x\src\testDebug\kotlin") -Force | Out-Null
Copy-Item -LiteralPath $ktSource -Destination (Join-Path $tree "tools\copie\KT.kt")
Copy-Item -LiteralPath $ktSource -Destination (Join-Path $tree "core-x\src\test\kotlin\KT.kt")
Copy-Item -LiteralPath (Join-Path $tree "tools\chiavi\ec-corta-0.b64.txt") -Destination (Join-Path $tree "core-x\src\testDebug\kotlin\chiave.txt")

Write-Host "Il passo 3b sulle chiavi generate"
$all = @($positives) + @($neutrals) + @("tools/copie/KT.kt", "core-x/src/test/kotlin/KT.kt", "core-x/src/testDebug/kotlin/chiave.txt")
$result = Find-PrivateKeyFiles -Root $tree -Paths $all
$flagged = @($result.Problems | ForEach-Object { ($_ -replace ' \(.*$', '') })
$missed = @($positives | Where-Object { $flagged -notcontains $_ })
Assert-That "il passo fallisce: ogni chiave generata, in ogni forma, e' riconosciuta fuori dai test" ($missed.Count -eq 0) ("non riconosciute (" + $missed.Count + "): " + ($missed -join ", "))
Assert-That "la copia della vera KT fuori dai test e' riconosciuta (il caso di R6)" ($flagged -contains "tools/copie/KT.kt")
Assert-That "le quattro classi del primo byte della chiave corta sono riconosciute in base64 e in esadecimale" (@(0..3 | Where-Object { ($flagged -contains "tools/chiavi/ec-corta-$_.b64.txt") -and ($flagged -contains "tools/chiavi/ec-corta-$_.hex.txt") }).Count -eq 4)
$falsePositives = @($neutrals | Where-Object { $flagged -contains $_ })
Assert-That "chiavi pubbliche, byte casuali e quasi uguali non sono segnalati" ($falsePositives.Count -eq 0) ($falsePositives -join ", ")
Assert-That "sotto src/test/ e src/testDebug/ le stesse chiavi non sono un problema ma sono viste" ($flagged -notcontains "core-x/src/test/kotlin/KT.kt" -and $flagged -notcontains "core-x/src/testDebug/kotlin/chiave.txt" -and $result.InTests.Count -eq 2)
Assert-That "tutti i file sono stati letti" ($result.Read -eq $all.Count) ("letti " + $result.Read + " di " + $all.Count)

# Il passo che fallisce davvero: con un problema, il corpo del passo 3b restituisce il testo dell'errore.
$problemText = if ($result.Problems.Count -gt 0) { "chiavi private (o il loro inizio) fuori dai test: " + ($result.Problems -join ", ") } else { $null }
Assert-That "il passo restituisce un errore (non passa)" ([bool]$problemText)

if ($failures.Count -gt 0) {
  Write-Host ("chiavi-prove: {0} prove, {1} fallite" -f $count, $failures.Count) -ForegroundColor Red
  foreach ($f in $failures) { Write-Host ("  - " + $f.Substring(0, [Math]::Min(500, $f.Length))) -ForegroundColor Red }
  exit 1
}
Write-Host ("chiavi-prove: {0} prove, tutte passate ({1} chiavi vere di Java in {2} forme, sotto build\prova-chiavi)" -f $count, 8, 14) -ForegroundColor Green
exit 0
