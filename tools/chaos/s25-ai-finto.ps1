<#
.SYNOPSIS
  Prova a secco COMPLETA del modo ai dello script del telefono (tools\chaos\s25.ps1 -Mode ai) contro un adb FINTO: nessun telefono, nessun
  adb vero, nessun NAS (docs/prova-500.md, sezione della Fase 6). Stesso stile di s25-finto.ps1 (modo backup).

.DESCRIPTION
  Il telefono finto e' una cartella temporanea (tools\chaos\fake-adb\fake-adb.ps1, che per il modo ai imita `run-as <pacchetto> find ...`,
  `exec-out run-as <pacchetto> cat ...`, il processo dell'app (`pidof`, `kill -9`, `monkey`) e la copia del set OCR). Il catalogo dei modelli e'
  finto e piccolo (`-CatalogFile`: un modello da 4 KB), perche' l'adb finto non tiene file da centinaia di MB.

  DB-2: i file dei vettori di prova hanno il NOME e il FORMATO che scrive l'app, non quelli che lo script si aspettava. Il nome e' quello di
  `LocalAiIndex.vectorFileFor` (`vectors-<codice dello spazio>-<generazione>.bin`, in `no_backup/search/index/`), i byte quelli di `VectorFile.append`
  (little-endian: il segno `MCGV` e' `56 47 43 4D`, il CRC32 e' little-endian). Il banco controlla anche che i sorgenti dell'app dicano ancora le
  stesse cose (segno, versione, intestazione, codice dello spazio): se l'app cambia formato, questo banco diventa rosso invece di continuare a
  provare un formato che non esiste piu'. Lo script provato legge lo stesso sorgente: il banco e lo script non possono piu' sbagliare insieme.

  Lo script prova, in quest'ordine:
    1. il formato dell'app (dai sorgenti) e il verificatore su un telefono in ordine: passa e legge davvero i 20 record;
    2. un file di `models/` con un byte cambiato (I2): fallisce con il codice 4; e uno che non e' del catalogo, uguale;
    3. un parziale rimasto (I9): fallisce, a meno di -NotSettled;
    4. un temporaneo orfano nel buffer (I9): fallisce;
    5. il file dei vettori: pochi record rovinati (quanti una morte ne spiega) passano, troppi (I5) falliscono; una coda troncata passa;
       un file con il nome giusto e il formato sbagliato (il vecchio ordine dei byte), uno con un nome che l'app non scrive, e uno vuoto;
    6. le interruzioni miste (Wi-Fi e morti): finiscono, il Wi-Fi e' riacceso, l'adb finto riceve solo comandi previsti e SOLO percorsi della cartella dell'app;
    7. la MORTE del processo (solo morti, con la scrittura lasciata a meta'): il processo muore e viene rilanciato (pid diverso), la coda troncata c'e' e
       la verifica passa;
    8. DB-4: `eval-push` porta il set OCR dentro la cartella dell'app (`adb push` + `run-as cp`), `eval-pull` porta fuori solo etichette e resoconti;
    9. le guardie: un altro pacchetto (anche di debug), il Wi-Fi spento oltre 120 s e un passo del modo backup sono rifiutati.
  Non lascia niente fuori dalla cartella temporanea e da build\chaos\. Esce con 0 se tutto e' come previsto, con 1 altrimenti.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\chaos\s25-ai-finto.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$root = (Resolve-Path (Join-Path $here "..\..")).Path
$script = Join-Path $here "s25.ps1"
$package = "dev.pampa.mycloudgallery.debug"
$work = Join-Path ([System.IO.Path]::GetTempPath()) ("mcg-s25-ai-finto-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
$phone = Join-Path $work "telefono"
$app = Join-Path $phone "data\data\$package"
$log = Join-Path $work "racconto.log"
$failures = New-Object System.Collections.Generic.List[string]

function Check([string]$what, [bool]$ok) {
  if ($ok) { Write-Host ("  OK    {0}" -f $what) -ForegroundColor Green } else { Write-Host ("  FAIL  {0}" -f $what) -ForegroundColor Red; $failures.Add($what) }
}

Add-Type -TypeDefinition @'
using System;
public static class McgFintoCrc {
  public static uint Crc32(byte[] b, int off, int len) {
    uint c = 0xFFFFFFFFu;
    for (int i = off; i < off + len; i++) { c ^= b[i]; for (int k = 0; k < 8; k++) c = ((c & 1) != 0) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1); }
    return c ^ 0xFFFFFFFFu;
  }
}
'@

# --- Il formato dell'app, letto dai sorgenti (non copiato a mano) ----------------------------------------------------------------------
$vectorSource = Get-Content -LiteralPath (Join-Path $root "core-search\src\main\kotlin\dev\pampa\mycloudgallery\core\search\index\VectorFile.kt") -Raw
$codesSource = Get-Content -LiteralPath (Join-Path $root "core-model\src\main\kotlin\dev\pampa\mycloudgallery\core\model\AiCodes.kt") -Raw
$indexSource = Get-Content -LiteralPath (Join-Path $root "core-search\src\main\kotlin\dev\pampa\mycloudgallery\core\search\index\LocalAiIndex.kt") -Raw
$magicMatch = [regex]::Match($vectorSource, 'const val MAGIC: Int = 0x([0-9A-Fa-f]{8})')
$versionMatch = [regex]::Match($vectorSource, 'const val VERSION: Byte = (\d+)')
$headerMatch = [regex]::Match($vectorSource, 'const val HEADER: Int = (\d+)')
$codeMatch = [regex]::Match($codesSource, 'EMBEDDINGGEMMA2_FLOAT_V1\(\s*1\s*,\s*"([a-z0-9_]+)"')
$dimensionMatch = [regex]::Match($codesSource, 'EmbeddingSpace\(EmbeddingSpace\.EMBEDDING_GEMMA2_FLOAT_V1,\s*(\d+),')
$allFound = $magicMatch.Success -and $versionMatch.Success -and $headerMatch.Success -and $codeMatch.Success -and $dimensionMatch.Success
if (-not $allFound) { Write-Host "  FAIL  i sorgenti dell'app non dicono piu' le cose che il banco legge (VectorFile.kt, AiCodes.kt)" -ForegroundColor Red; exit 1 }
$magic = [BitConverter]::GetBytes([uint32][Convert]::ToUInt32($magicMatch.Groups[1].Value, 16))   # little-endian, come ByteBuffer.order(LITTLE_ENDIAN)
$version = [byte][int]$versionMatch.Groups[1].Value
$header = [int]$headerMatch.Groups[1].Value
$spaceCode = $codeMatch.Groups[1].Value
$dimension = [int]$dimensionMatch.Groups[1].Value
$recordSize = $header + 2 * $dimension + 4
$vectorsName = "vectors-$spaceCode-1.bin"
$indexDir = "no_backup\search\index"
$oldNameStillThere = $indexSource.Contains('"vectors-${space.code}-$generation.bin"')

# Un record del file dei vettori, byte per byte come lo scrive `VectorFile.append`: segno, versione, ricetta, fotogramma, istante presente (0), chiave di 32 byte,
# istante (-1), `dim` float16 little-endian, CRC32 little-endian dei byte precedenti.
function New-Record([int]$seed, [bool]$oldOrder = $false) {
  $record = New-Object byte[] $recordSize
  $random = New-Object System.Random($seed)
  [byte[]]$sign = if ($oldOrder) { $magic[3], $magic[2], $magic[1], $magic[0] } else { $magic }   # l'ordine che lo script credeva: 4D 43 47 56
  [Array]::Copy($sign, 0, $record, 0, 4)
  $record[4] = $version; $record[5] = 1; $record[6] = 0; $record[7] = 0
  $key = New-Object byte[] 32
  $random.NextBytes($key)
  [Array]::Copy($key, 0, $record, 8, 32)
  $record[40] = 0xFF; $record[41] = 0xFF; $record[42] = 0xFF; $record[43] = 0xFF
  for ($i = 0; $i -lt $dimension; $i++) {
    $half = 0x3000 + $random.Next(0, 0x400)
    $record[$header + 2 * $i] = [byte]($half -band 0xFF)
    $record[$header + 2 * $i + 1] = [byte](($half -shr 8) -band 0xFF)
  }
  [Array]::Copy([BitConverter]::GetBytes([uint32][McgFintoCrc]::Crc32($record, 0, $recordSize - 4)), 0, $record, $recordSize - 4, 4)
  return , $record
}

function Write-Vectors([int]$valid, [int]$broken = 0, [int]$tornBytes = 0, [string]$name = $vectorsName, [bool]$oldOrder = $false) {
  $stream = New-Object System.IO.MemoryStream
  for ($i = 1; $i -le $valid; $i++) {
    $record = New-Record $i $oldOrder
    if ($i -le $broken) { $record[$recordSize - 10] = $record[$recordSize - 10] -bxor 1 }   # un byte cambiato: l'intestazione c'e', il CRC no
    $stream.Write($record, 0, $record.Length)
  }
  if ($tornBytes -gt 0) { $tail = New-Record 999 $oldOrder; $stream.Write($tail, 0, $tornBytes) }
  [System.IO.File]::WriteAllBytes((Join-Path $app "$indexDir\$name"), $stream.ToArray())
}

# Un telefono in ordine: un modello del catalogo finto, nessun parziale, un buffer pulito, l'indice con venti record veri, il suo registro e il file grosso,
# e il processo dell'app vivo (pid 4200).
function New-App {
  if (Test-Path -LiteralPath $phone) { Remove-Item -LiteralPath $phone -Recurse -Force }
  foreach ($d in @("no_backup\models\partial", "no_backup\ai-buffer", $indexDir)) { New-Item -ItemType Directory -Force -Path (Join-Path $app $d) | Out-Null }
  [System.IO.File]::WriteAllBytes((Join-Path $app "no_backup\models\$script:sha"), $script:modelBytes)
  for ($i = 1; $i -le 3; $i++) { [System.IO.File]::WriteAllBytes((Join-Path $app ("no_backup\ai-buffer\{0:x64}.analysis" -f $i)), [byte[]](1..40)) }
  Write-Vectors 20
  [System.IO.File]::WriteAllBytes((Join-Path $app "$indexDir\ledger.log"), [byte[]](1..90))
  [System.IO.File]::WriteAllBytes((Join-Path $app "$indexDir\coarse-$spaceCode.bin"), [byte[]](1..200))
  New-Item -ItemType Directory -Force -Path (Join-Path $phone "proc") | Out-Null
  Set-Content -LiteralPath (Join-Path $phone "proc\$package.pid") -Value 4200
  Set-Content -LiteralPath (Join-Path $phone "proc\next-pid") -Value 4201
}

# Esegue s25.ps1 in un processo a parte con l'adb finto e restituisce il codice di uscita.
function Invoke-S25([string[]]$arguments, [string]$mode = "ai") {
  $env:MCG_ADB = Join-Path $here "fake-adb\adb.cmd"
  $env:FAKE_PHONE = $phone
  & powershell -NoProfile -ExecutionPolicy Bypass -File $script -Mode $mode @arguments -Serial FAKE123 -CatalogFile $catalogFile -Log $log | Out-Null
  return $LASTEXITCODE
}

function Get-Calls { return @(Get-Content -LiteralPath (Join-Path $phone "adb-calls.log") -ErrorAction SilentlyContinue) }

function Get-RacContoText { return (Get-Content -LiteralPath $log -Raw -ErrorAction SilentlyContinue) }

New-Item -ItemType Directory -Force -Path $phone | Out-Null
try {
  # Il catalogo finto: un modello da 4 KB, nel formato del sorgente Kotlin che lo script legge.
  $modelBytes = New-Object byte[] 4096
  (New-Object System.Random(77)).NextBytes($modelBytes)
  $sha = ([System.BitConverter]::ToString([System.Security.Cryptography.SHA256]::Create().ComputeHash($modelBytes)) -replace '-', '').ToLowerInvariant()
  $catalogFile = Join-Path $work "catalogo-finto.kt"
  Set-Content -LiteralPath $catalogFile -Value ("fileName = `"modello-finto.litertlm`"`nsizeBytes = 4_096L`nsha256 = Sha256(`"$sha`")`n") -Encoding UTF8

  Write-Host "1. Il formato dell'app e un telefono in ordine"
  Check "il segno e' MAGIC little-endian (56 47 43 4D), non quello che lo script credeva (4D 43 47 56)" (($magic -join ",") -eq "86,71,67,77")
  Check "il file dei vettori si chiama vectors-<spazio>-<generazione>.bin come in LocalAiIndex" ($oldNameStillThere -and $vectorsName -eq "vectors-embeddinggemma2_float_v1-1.bin")
  Check "il record e' di $recordSize byte (intestazione $header, $dimension float16, CRC32)" ($recordSize -eq 1584)
  New-App
  Check "la verifica passa" ((Invoke-S25 @("-Step", "verify")) -eq 0)
  Check "la verifica ha letto davvero i 20 record dell'app" ((Get-RacContoText) -match '20 record buoni, 0 rovinati')
  Check "il registro dell'indice e il file dei suoi gruppi non sono file di vettori (se ne legge uno solo)" (([regex]::Matches((Get-RacContoText), 'un file di vettori')).Count -eq 1)

  Write-Host "2. models/"
  New-App
  $victim = Join-Path $app "no_backup\models\$sha"
  $bytes = [System.IO.File]::ReadAllBytes($victim); $bytes[100] = $bytes[100] -bxor 1; [System.IO.File]::WriteAllBytes($victim, $bytes)
  Check "un modello con un byte cambiato fa fallire (codice 4)" ((Invoke-S25 @("-Step", "verify")) -eq 4)
  New-App
  [System.IO.File]::WriteAllBytes((Join-Path $app "no_backup\models\$('a' * 64)"), [byte[]](1..30))
  Check "un file che non e' del catalogo fa fallire (codice 4)" ((Invoke-S25 @("-Step", "verify")) -eq 4)
  New-App
  [System.IO.File]::WriteAllBytes($victim, $modelBytes[0..4000])
  Check "un modello piu' corto del catalogo (un parziale promosso) fa fallire (codice 4)" ((Invoke-S25 @("-Step", "verify")) -eq 4)

  Write-Host "3. Parziali"
  New-App
  [System.IO.File]::WriteAllBytes((Join-Path $app "no_backup\models\partial\$sha.part"), [byte[]](1..50))
  Check "un parziale rimasto a ripresa finita fa fallire (codice 4)" ((Invoke-S25 @("-Step", "verify")) -eq 4)
  Check "con -NotSettled non e' un errore" ((Invoke-S25 @("-Step", "verify", "-NotSettled")) -eq 0)

  Write-Host "4. Buffer"
  New-App
  [System.IO.File]::WriteAllBytes((Join-Path $app ("no_backup\ai-buffer\{0:x64}.analysis.tmp" -f 9)), [byte[]](1..10))
  Check "un temporaneo orfano nel buffer fa fallire (codice 4)" ((Invoke-S25 @("-Step", "verify")) -eq 4)

  Write-Host "5. File dei vettori (nome e formato dell'app)"
  New-App
  Write-Vectors 20 3
  Check "tre record rovinati (una morte puo' spiegarli) passano" ((Invoke-S25 @("-Step", "verify", "-Interruptions", "1")) -eq 0)
  New-App
  Write-Vectors 40 15
  Check "quindici record rovinati con una sola interruzione (oltre 9) fanno fallire (codice 4)" ((Invoke-S25 @("-Step", "verify", "-Interruptions", "1")) -eq 4)
  Check "con piu' interruzioni gli stessi record si spiegano" ((Invoke-S25 @("-Step", "verify", "-Interruptions", "3")) -eq 0)
  New-App
  Write-Vectors 20 0 ($recordSize / 2)
  Check "una coda troncata (scrittura interrotta) passa" ((Invoke-S25 @("-Step", "verify")) -eq 0)
  New-App
  Write-Vectors 0 0 ($recordSize / 2)
  Check "la sola coda di una prima scrittura interrotta (meno di un record) passa" ((Invoke-S25 @("-Step", "verify")) -eq 0)
  New-App
  Write-Vectors 0
  Check "un file dei vettori vuoto passa (l'indice non ha ancora scritto niente)" ((Invoke-S25 @("-Step", "verify")) -eq 0)
  # DB-2: prima queste due prove passavano sempre. Il vecchio script cercava un altro nome e un altro ordine dei byte: non trovava niente o
  # "spazzatura", e diceva "tutto a posto".
  New-App
  Write-Vectors 20 0 0 $vectorsName $true
  Check "nome giusto e formato sbagliato (il vecchio ordine dei byte) fa fallire (codice 4): l'app non lo saprebbe leggere" ((Invoke-S25 @("-Step", "verify")) -eq 4)
  New-App
  Remove-Item -LiteralPath (Join-Path $app "$indexDir\$vectorsName")
  Write-Vectors 20 0 0 "vectors-1-1.bin"
  Check "un file vectors-* con un nome che l'app non scrive fa fallire (codice 4)" ((Invoke-S25 @("-Step", "verify")) -eq 4)
  New-App
  [System.IO.File]::WriteAllBytes((Join-Path $app "$indexDir\$vectorsName"), (New-Object byte[] 4000))
  Check "un file non vuoto senza nessun record riconoscibile fa fallire (codice 4)" ((Invoke-S25 @("-Step", "verify")) -eq 4)
  New-App
  Remove-Item -LiteralPath (Join-Path $app "$indexDir\$vectorsName")
  Check "nessun file dei vettori: non e' un errore (l'indicizzazione puo' non aver salvato niente)" ((Invoke-S25 @("-Step", "verify")) -eq 0)

  Write-Host "6. Interruzioni miste (Wi-Fi e morti)"
  New-App
  $interrupt = @("-Step", "interrupt", "-Interruptions", "3", "-MinGapSeconds", "1", "-MaxGapSeconds", "1", "-WifiOffMinSeconds", "1", "-WifiOffMaxSeconds", "1", "-RelaunchDelaySeconds", "1", "-SettleSeconds", "1", "-Seed", "5", "-Verify")
  Check "le interruzioni finiscono e la verifica passa" ((Invoke-S25 $interrupt) -eq 0)
  $calls = Get-Calls
  Check "il Wi-Fi e' stato riacceso alla fine" (@($calls | Where-Object { $_ -match 'svc \| wifi \| enable' }).Count -ge 1)
  $runAs = @($calls | Where-Object { $_ -match 'run-as' })
  Check "la verifica ha chiesto dati all'app" ($runAs.Count -ge 4)
  # Solo la cartella dell'app (no_backup/...), solo l'app di debug, solo letture (find, cat, kill): mai un rm, un cp, un push.
  Check "ogni run-as e' dell'app di debug" (@($runAs | Where-Object { $_ -notmatch [regex]::Escape($package) }).Count -eq 0)
  Check "ogni percorso letto e' dentro no_backup/" (@($runAs | Where-Object { ($_ -match 'find|cat') -and ($_ -notmatch 'no_backup/(models|ai-buffer|search)') }).Count -eq 0)
  Check "nessuna scrittura sul telefono (rm, cp, push, dd, mkdir)" (@($calls | Where-Object { $_ -match '\| (rm|cp|push|dd|mkdir) ?\||\| sh \| -c' }).Count -eq 0)
  Check "l'adb finto non ha ricevuto altro che comandi previsti" (@($calls | Where-Object { $_ -match 'non previsto' }).Count -eq 0)

  Write-Host "7. La morte del processo, con la scrittura lasciata a meta'"
  New-App
  $env:FAKE_TEAR_ON_KILL = "1"
  $deaths = @("-Step", "interrupt", "-Interruptions", "3", "-WifiPercent", "0", "-MinGapSeconds", "1", "-MaxGapSeconds", "1", "-RelaunchDelaySeconds", "1", "-SettleSeconds", "1", "-Seed", "5", "-Verify")
  $code = Invoke-S25 $deaths
  Remove-Item Env:FAKE_TEAR_ON_KILL -ErrorAction SilentlyContinue
  $calls = Get-Calls
  Check "la prova con sole morti finisce e la verifica passa" ($code -eq 0)
  Check "il processo e' morto 3 volte (kill -9 del pid, un pid diverso a ogni rilancio)" (@($calls | Where-Object { $_ -match '^morto: ' }).Count -eq 3)
  Check "l'app e' stata rilanciata a ogni morte e alla fine (monkey)" (@($calls | Where-Object { $_ -match 'monkey' }).Count -ge 4)
  Check "il pid finale non e' piu' il primo (4200): il processo e' rinato" ([int](Get-Content -LiteralPath (Join-Path $phone "proc\$package.pid") -Raw).Trim() -gt 4200)
  $size = (Get-Item -LiteralPath (Join-Path $app "$indexDir\$vectorsName")).Length
  Check "la morte ha lasciato mezzo record in coda al file (e la verifica lo ha accettato)" (($size % $recordSize) -ne 0)
  Check "il racconto dice che i 20 record buoni ci sono ancora e che c'e' una coda troncata" (((Get-RacContoText) -match '20 record buoni') -and ((Get-RacContoText) -notmatch 'coda troncata 0 byte'))
  New-App
  Remove-Item -LiteralPath (Join-Path $phone "proc\$package.pid")
  Remove-Item -LiteralPath (Join-Path $phone "adb-calls.log") -ErrorAction SilentlyContinue
  [void](Invoke-S25 @("-Step", "interrupt", "-Interruptions", "1", "-WifiPercent", "0", "-MinGapSeconds", "1", "-MaxGapSeconds", "1", "-RelaunchDelaySeconds", "1", "-SettleSeconds", "1", "-Seed", "5"))
  Check "se il processo non c'era non si uccide niente (nessun kill -9)" (@(Get-Calls | Where-Object { $_ -match 'kill' }).Count -eq 0)

  Write-Host "8. DB-4: il set OCR dentro l'app e le etichette fuori"
  New-App
  $ocr = Join-Path $work "MCG-ocr"
  New-Item -ItemType Directory -Force -Path (Join-Path $ocr "immagini") | Out-Null
  Set-Content -LiteralPath (Join-Path $ocr "manifest.tsv") -Value "ocr_001.png`tcodice 123456"
  [System.IO.File]::WriteAllBytes((Join-Path $ocr "immagini\ocr_001.png"), [byte[]](1..64))
  Remove-Item -LiteralPath (Join-Path $phone "adb-calls.log") -ErrorAction SilentlyContinue
  Check "eval-push porta il set OCR (codice 0)" ((Invoke-S25 @("-Step", "eval-push", "-EvalSource", $ocr)) -eq 0)
  Check "il manifesto e l'immagine sono dentro la cartella dell'app (no_backup/eval/ocr/)" ((Test-Path -LiteralPath (Join-Path $app "no_backup\eval\ocr\manifest.tsv")) -and (Test-Path -LiteralPath (Join-Path $app "no_backup\eval\ocr\immagini\ocr_001.png")))
  Check "l'immagine e' arrivata byte per byte" ([System.IO.File]::ReadAllBytes((Join-Path $app "no_backup\eval\ocr\immagini\ocr_001.png")).Length -eq 64)
  Check "la copia temporanea sul telefono e' stata tolta" (-not (Test-Path -LiteralPath (Join-Path $phone "data\local\tmp\mcg-eval")))
  $calls = Get-Calls
  Check "la scrittura nella cartella dell'app e' solo con run-as dell'app di debug" (@($calls | Where-Object { $_ -match '\| cp \|' -and $_ -notmatch 'run-as' }).Count -eq 0)
  Check "l'adb finto non ha ricevuto altro che comandi previsti" (@($calls | Where-Object { $_ -match 'non previsto' }).Count -eq 0)
  Check "senza -EvalSource (o senza manifest.tsv) il passo e' rifiutato" (((Invoke-S25 @("-Step", "eval-push")) -ne 0) -and ((Invoke-S25 @("-Step", "eval-push", "-EvalSource", $work)) -ne 0))
  Check "il piano a secco di eval-push non esegue niente" ((Invoke-S25 @("-Step", "eval-push", "-EvalSource", $ocr, "-DryRun")) -eq 0)

  $evalApp = Join-Path $app "no_backup\eval"
  New-Item -ItemType Directory -Force -Path (Join-Path $evalApp "ocr\immagini") | Out-Null
  Set-Content -LiteralPath (Join-Path $evalApp "labels.mcgeval") -Value "q01`tcane`tobjects"
  Set-Content -LiteralPath (Join-Path $evalApp "report-01.txt") -Value "numeri soltanto"
  Set-Content -LiteralPath (Join-Path $evalApp "ocr\letto.tsv") -Value "ocr_001.png`tcodice"
  Set-Content -LiteralPath (Join-Path $evalApp "ocr\ocr-resoconto.txt") -Value "resoconto"
  Set-Content -LiteralPath (Join-Path $evalApp "segreto.txt") -Value "non va portato"
  $dest = Join-Path $work "eval-fuori"
  Check "eval-pull porta le etichette e i resoconti (codice 0)" ((Invoke-S25 @("-Step", "eval-pull", "-EvalDir", $dest)) -eq 0)
  $pulled = @(Get-ChildItem -LiteralPath $dest -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName.Substring($dest.Length + 1).Replace('\', '/') } | Sort-Object)
  Check "arrivano solo labels.mcgeval, report-NN.txt, ocr/letto.tsv e ocr/ocr-resoconto.txt" (($pulled -join ",") -eq "labels.mcgeval,ocr/letto.tsv,ocr/ocr-resoconto.txt,report-01.txt")
  Check "le etichette arrivano identiche" ((Get-Content -LiteralPath (Join-Path $dest "labels.mcgeval") -Raw) -eq (Get-Content -LiteralPath (Join-Path $evalApp "labels.mcgeval") -Raw))
  Check "il piano a secco di eval-pull non esegue niente" ((Invoke-S25 @("-Step", "eval-pull", "-EvalDir", (Join-Path $work "mai"), "-DryRun")) -eq 0 -and -not (Test-Path -LiteralPath (Join-Path $work "mai")))

  Write-Host "9. Guardie"
  Check "un altro pacchetto di debug e' rifiutato" ((Invoke-S25 @("-Step", "verify", "-Package", "com.example.altra.debug")) -ne 0)
  Check "l'app di release e' rifiutata" ((Invoke-S25 @("-Step", "verify", "-Package", "dev.pampa.mycloudgallery")) -ne 0)
  Check "il Wi-Fi spento oltre 120 s e' rifiutato" ((Invoke-S25 @("-Step", "interrupt", "-WifiOffMaxSeconds", "121")) -ne 0)
  Check "un passo del modo backup (seed) e' rifiutato" ((Invoke-S25 @("-Step", "seed")) -ne 0)
  Check "i passi eval-* sono solo del modo ai" (((Invoke-S25 @("-Step", "eval-push", "-EvalSource", $ocr) "backup") -ne 0) -and ((Invoke-S25 @("-Step", "eval-pull") "move") -ne 0))
  Check "il piano a secco non esegue niente" ((Invoke-S25 @("-Step", "interrupt", "-DryRun", "-Interruptions", "5")) -eq 0)
} finally {
  Remove-Item Env:MCG_ADB, Env:FAKE_PHONE, Env:FAKE_TEAR_ON_KILL -ErrorAction SilentlyContinue
  if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }
}

if ($failures.Count -gt 0) {
  Write-Host ("`n{0} controlli non passano." -f $failures.Count) -ForegroundColor Red
  exit 1
}
Write-Host "`nTutto come previsto: il modo ai dello script del telefono funziona contro l'adb finto." -ForegroundColor Green
exit 0
