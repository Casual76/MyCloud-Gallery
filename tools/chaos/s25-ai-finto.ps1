<#
.SYNOPSIS
  Prova a secco COMPLETA del modo ai dello script del telefono (tools\chaos\s25.ps1 -Mode ai) contro un adb FINTO: nessun telefono, nessun
  adb vero, nessun NAS (docs/prova-500.md, sezione della Fase 6). Stesso stile di s25-finto.ps1 (modo backup).

.DESCRIPTION
  Il telefono finto e' una cartella temporanea (tools\chaos\fake-adb\fake-adb.ps1, che per il modo ai imita anche `run-as <pacchetto> find ...`
  e `exec-out run-as <pacchetto> cat ...` nella cartella dell'app). Il catalogo dei modelli e' finto e piccolo (`-CatalogFile`: un modello da
  4 KB), perche' l'adb finto non tiene file da centinaia di MB. Lo script prova, in quest'ordine:
    1. il verificatore su un telefono in ordine (modello giusto, nessun parziale, buffer pulito, vettori con il CRC giusto): passa;
    2. un file di `models/` con un byte cambiato (I2): fallisce con il codice 4; e uno che non e' del catalogo, uguale;
    3. un parziale rimasto (I9): fallisce, a meno di -NotSettled;
    4. un temporaneo orfano nel buffer (I9): fallisce;
    5. un file dei vettori con record rovinati nel mezzo: pochi (quanti una morte ne spiega) passano, troppi (I5) falliscono; una coda troncata passa;
    6. le interruzioni (-Step interrupt, 3 interruzioni con attese di 1 s, con -Verify): finiscono, il Wi-Fi e' riacceso, l'adb finto riceve solo
       comandi previsti e SOLO percorsi della cartella dell'app (mai altro del telefono);
    7. le guardie: un altro pacchetto (anche di debug), il Wi-Fi spento oltre 120 s e un passo del modo backup sono rifiutati.
  Non lascia niente fuori dalla cartella temporanea e da build\chaos\. Esce con 0 se tutto e' come previsto, con 1 altrimenti.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\chaos\s25-ai-finto.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
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

$dimension = 768
$recordSize = 44 + 2 * $dimension + 4

# Un record del file dei vettori (core-search, VectorFile.kt), con la chiave e le componenti dal seme.
function New-Record([int]$seed) {
  $record = New-Object byte[] $recordSize
  [byte[]]$magic = 0x4D, 0x43, 0x47, 0x56
  [Array]::Copy($magic, 0, $record, 0, 4)
  $record[4] = 1; $record[5] = 1
  $filler = New-Object byte[] ($recordSize - 12)
  (New-Object System.Random($seed)).NextBytes($filler)
  [Array]::Copy($filler, 0, $record, 8, $filler.Length)
  [Array]::Copy([BitConverter]::GetBytes([uint32][McgFintoCrc]::Crc32($record, 0, $recordSize - 4)), 0, $record, $recordSize - 4, 4)
  return , $record
}

function Write-Vectors([int]$valid, [int]$broken = 0, [int]$tornBytes = 0) {
  $stream = New-Object System.IO.MemoryStream
  for ($i = 1; $i -le $valid; $i++) {
    $record = New-Record $i
    if ($i -le $broken) { $record[40] = $record[40] -bxor 1 }   # un byte cambiato: l'intestazione c'e', il CRC no
    $stream.Write($record, 0, $record.Length)
  }
  if ($tornBytes -gt 0) { $tail = New-Record 999; $stream.Write($tail, 0, $tornBytes) }
  [System.IO.File]::WriteAllBytes((Join-Path $app "no_backup\search\vectors-1-1.bin"), $stream.ToArray())
}

# Un telefono in ordine: un modello del catalogo finto, nessun parziale, un buffer pulito, venti record dei vettori.
function New-App {
  if (Test-Path -LiteralPath $phone) { Remove-Item -LiteralPath $phone -Recurse -Force }
  foreach ($d in @("no_backup\models\partial", "no_backup\ai-buffer", "no_backup\search")) { New-Item -ItemType Directory -Force -Path (Join-Path $app $d) | Out-Null }
  [System.IO.File]::WriteAllBytes((Join-Path $app "no_backup\models\$script:sha"), $script:modelBytes)
  for ($i = 1; $i -le 3; $i++) { [System.IO.File]::WriteAllBytes((Join-Path $app ("no_backup\ai-buffer\{0:x64}.analysis" -f $i)), [byte[]](1..40)) }
  Write-Vectors 20
}

# Esegue s25.ps1 in un processo a parte con l'adb finto e restituisce il codice di uscita.
function Invoke-S25([string[]]$arguments) {
  $env:MCG_ADB = Join-Path $here "fake-adb\adb.cmd"
  $env:FAKE_PHONE = $phone
  & powershell -NoProfile -ExecutionPolicy Bypass -File $script -Mode ai @arguments -Serial FAKE123 -CatalogFile $catalogFile -Log $log | Out-Null
  return $LASTEXITCODE
}

New-Item -ItemType Directory -Force -Path $phone | Out-Null
try {
  # Il catalogo finto: un modello da 4 KB, nel formato del sorgente Kotlin che lo script legge.
  $modelBytes = New-Object byte[] 4096
  (New-Object System.Random(77)).NextBytes($modelBytes)
  $sha = ([System.BitConverter]::ToString([System.Security.Cryptography.SHA256]::Create().ComputeHash($modelBytes)) -replace '-', '').ToLowerInvariant()
  $catalogFile = Join-Path $work "catalogo-finto.kt"
  Set-Content -LiteralPath $catalogFile -Value ("fileName = `"modello-finto.litertlm`"`nsizeBytes = 4_096L`nsha256 = Sha256(`"$sha`")`n") -Encoding UTF8

  Write-Host "1. Telefono in ordine"
  New-App
  Check "la verifica passa" ((Invoke-S25 @("-Step", "verify")) -eq 0)

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

  Write-Host "5. File dei vettori"
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

  Write-Host "6. Interruzioni"
  New-App
  Remove-Item -LiteralPath (Join-Path $phone "adb-calls.log") -ErrorAction SilentlyContinue
  $interrupt = @("-Step", "interrupt", "-Interruptions", "3", "-MinGapSeconds", "1", "-MaxGapSeconds", "1", "-WifiOffMinSeconds", "1", "-WifiOffMaxSeconds", "1", "-RelaunchDelaySeconds", "1", "-SettleSeconds", "1", "-Seed", "5", "-Verify")
  Check "le interruzioni finiscono e la verifica passa" ((Invoke-S25 $interrupt) -eq 0)
  $calls = @(Get-Content -LiteralPath (Join-Path $phone "adb-calls.log"))
  Check "il Wi-Fi e' stato riacceso alla fine" (@($calls | Where-Object { $_ -match 'svc \| wifi \| enable' }).Count -ge 1)
  $runAs = @($calls | Where-Object { $_ -match 'run-as' })
  Check "la verifica ha chiesto dati all'app" ($runAs.Count -ge 4)
  # Solo la cartella dell'app (no_backup/...), solo l'app di debug, solo letture (find, cat, kill): mai un rm, un cp, un push.
  Check "ogni run-as e' dell'app di debug" (@($runAs | Where-Object { $_ -notmatch [regex]::Escape($package) }).Count -eq 0)
  Check "ogni percorso letto e' dentro no_backup/" (@($runAs | Where-Object { ($_ -match 'find|cat') -and ($_ -notmatch 'no_backup/(models|ai-buffer|search)') }).Count -eq 0)
  Check "nessuna scrittura sul telefono (rm, cp, push, dd, mkdir)" (@($calls | Where-Object { $_ -match '\| (rm|cp|push|dd|mkdir) ?\||\| sh \| -c' }).Count -eq 0)
  Check "l'adb finto non ha ricevuto altro che comandi previsti" (@($calls | Where-Object { $_ -match 'non previsto' }).Count -eq 0)

  Write-Host "7. Guardie"
  Check "un altro pacchetto di debug e' rifiutato" ((Invoke-S25 @("-Step", "verify", "-Package", "com.example.altra.debug")) -ne 0)
  Check "l'app di release e' rifiutata" ((Invoke-S25 @("-Step", "verify", "-Package", "dev.pampa.mycloudgallery")) -ne 0)
  Check "il Wi-Fi spento oltre 120 s e' rifiutato" ((Invoke-S25 @("-Step", "interrupt", "-WifiOffMaxSeconds", "121")) -ne 0)
  Check "un passo del modo backup (seed) e' rifiutato" ((Invoke-S25 @("-Step", "seed")) -ne 0)
  Check "il piano a secco non esegue niente" ((Invoke-S25 @("-Step", "interrupt", "-DryRun", "-Interruptions", "5")) -eq 0)
} finally {
  Remove-Item Env:MCG_ADB, Env:FAKE_PHONE -ErrorAction SilentlyContinue
  if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }
}

if ($failures.Count -gt 0) {
  Write-Host ("`n{0} controlli non passano." -f $failures.Count) -ForegroundColor Red
  exit 1
}
Write-Host "`nTutto come previsto: il modo ai dello script del telefono funziona contro l'adb finto." -ForegroundColor Green
exit 0
