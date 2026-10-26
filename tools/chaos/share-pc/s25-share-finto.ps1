<#
.SYNOPSIS
  Prova a secco COMPLETA dello script del telefono per la condivisione (tools\chaos\s25.ps1 -Mode share) contro un adb FINTO: nessun telefono, nessun adb
  vero, nessun NAS (docs/prova-500.md, Parte E, §E5). Nello stile di tools\chaos\s25-finto.ps1.

.DESCRIPTION
  Il telefono finto e' una cartella temporanea (tools\chaos\fake-adb\fake-adb.ps1). Lo script prova, in quest'ordine:
    0. l'autotest del modo (-SelfTest) e i piani a secco (nessun adb viene chiamato);
    1. la semina (-Step seed): il manifesto della condivisione (`# mcg-share-manifest 1`, le due cartelle, le impronte), nessun video grande, i file solo
       nelle due cartelle di `mcg-prova`;
    2. la verifica a telefono invariato (-Step verify -SkipNas): le impronte tornano;
    3. le interruzioni (-Step interrupt, 3 interruzioni con attese di 1 s) e la verifica finale; il Wi-Fi e' stato riacceso e l'adb finto non ha ricevuto
       altro che comandi previsti;
    4. un file tolto dal telefono (il guardiano, "Libera spazio"): la verifica fallisce (codice 4), con -AllowFreed passa;
    5. un file del telefono che cambia: la verifica deve FALLIRE (codice 4);
    6. la pulizia: senza -Yes rifiuta, con -Yes toglie solo le due cartelle di `mcg-prova`;
    7. le guardie: un pacchetto che non e' di debug, un Wi-Fi spento oltre 120 s, un numero di foto fuori misura (20-200), una share che non e' `test` e un
       passo che il modo non ha sono rifiutati prima di toccare il telefono.
  Il verificatore sul NAS (share-pc.ps1 -Mode verify) NON viene lanciato (-SkipNas): questo script non tocca mai il NAS. Esce con 0 se tutto e' come
  previsto, con 1 altrimenti. Non lascia niente fuori dalla cartella temporanea e da build\chaos\.

.PARAMETER Photos        Quante foto in tutto (default 30; il modo share vuole da 20 a 200).
.PARAMETER FixturesDir   I fixture gia' generati (default: li genera con tools\fixtures\generate.ps1 -SkipBig, serve il JDK).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\chaos\share-pc\s25-share-finto.ps1
#>
[CmdletBinding()]
param(
  [int]$Photos = 30,
  [string]$FixturesDir
)

$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$chaos = (Resolve-Path (Join-Path $here "..")).Path
$script = Join-Path $chaos "s25.ps1"
$work = Join-Path ([System.IO.Path]::GetTempPath()) ("mcg-s25-share-finto-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
$phone = Join-Path $work "telefono"
$manifest = Join-Path $work "manifesto.txt"
$log = Join-Path $work "racconto.log"
$failures = New-Object System.Collections.Generic.List[string]

function Check([string]$what, [bool]$ok) {
  if ($ok) { Write-Host ("  OK    {0}" -f $what) -ForegroundColor Green } else { Write-Host ("  FAIL  {0}" -f $what) -ForegroundColor Red; $failures.Add($what) }
}

# Esegue s25.ps1 in un processo a parte con l'adb finto e restituisce il codice di uscita.
function Invoke-S25([string[]]$arguments) {
  $env:MCG_ADB = Join-Path $chaos "fake-adb\adb.cmd"
  $env:FAKE_PHONE = $phone
  & powershell -NoProfile -ExecutionPolicy Bypass -File $script @arguments -Serial FAKE123 -Log $log | Out-Null
  return $LASTEXITCODE
}

function Get-CallCount { if (Test-Path -LiteralPath (Join-Path $phone "adb-calls.log")) { return @(Get-Content -LiteralPath (Join-Path $phone "adb-calls.log")).Count } else { return 0 } }

New-Item -ItemType Directory -Force -Path $phone | Out-Null
try {
  $run = "20261026-101500-1a2b3c4d"
  $common = @("-Mode", "share", "-Run", $run, "-Manifest", $manifest, "-Seed", "5")

  Write-Host "0. Autotest e piani a secco"
  & powershell -NoProfile -ExecutionPolicy Bypass -File $script -Mode share -SelfTest | Out-Null
  Check "l'autotest del modo share passa (nessun adb)" ($LASTEXITCODE -eq 0)
  foreach ($step in @("seed", "interrupt", "verify", "clean")) {
    $before = Get-CallCount
    Check "il piano a secco di '$step' finisce" ((Invoke-S25 ($common + @("-Step", $step, "-DryRun", "-Interruptions", "3"))) -eq 0)
    Check "il piano a secco di '$step' non chiama nemmeno adb" ((Get-CallCount) -eq $before)
  }

  Write-Host "1. Semina"
  $seed = $common + @("-Step", "seed", "-Photos", "$Photos")
  if ($FixturesDir) { $seed += @("-FixturesDir", $FixturesDir) }
  Check "la semina finisce" ((Invoke-S25 $seed) -eq 0)
  $lines = if (Test-Path -LiteralPath $manifest) { @(Get-Content -LiteralPath $manifest) } else { @() }
  $phoneLines = @($lines | Where-Object { $_ -like "phone=*" })
  Check "il manifesto e' quello della condivisione" (($lines[0] -eq "# mcg-share-manifest 1") -and ($lines -contains "run=$run"))
  Check "il manifesto dice dove stanno le copie private e l'area comune" (($lines -contains "private=test|mcg-campo") -and ($lines -contains "common=test-comune|Album condivisi"))
  Check "il manifesto ha almeno $Photos file del telefono" ($phoneLines.Count -ge $Photos)
  Check "ogni riga phone= ha impronta, byte ed etichetta" (@($phoneLines | Where-Object { $_ -notmatch '^phone=[0-9a-f]{64}\|\d+\|.+$' }).Count -eq 0)
  $onPhone = @(Get-ChildItem -LiteralPath (Join-Path $phone "sdcard") -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $_.FullName -notlike "*adb-calls.log" })
  Check "i file sono solo in DCIM\mcg-prova e Pictures\mcg-prova" (@($onPhone | Where-Object { $_.FullName -notmatch '\\sdcard\\(DCIM|Pictures)\\mcg-prova\\' }).Count -eq 0)
  Check "nessun video grande (il modo share non li semina)" (@($onPhone | Where-Object { $_.Name -like "VID_mcg_grande_*.mp4" }).Count -eq 0)
  Check "i fixture finti sono notmedia=, non phone=" ((@($lines | Where-Object { $_ -like "notmedia=*" }).Count -eq 3) -and (@($phoneLines | Where-Object { $_ -match '\|(casuali\.png|testo\.jpg|video-falso\.mp4)$' }).Count -eq 0))

  Write-Host "2. Verifica a telefono invariato"
  Check "le impronte tornano" ((Invoke-S25 ($common + @("-Step", "verify", "-SkipNas"))) -eq 0)

  Write-Host "3. Interruzioni"
  $interrupt = $common + @("-Step", "interrupt", "-Interruptions", "3", "-MinGapSeconds", "1", "-MaxGapSeconds", "1", "-WifiOffMinSeconds", "1", "-WifiOffMaxSeconds", "1", "-RelaunchDelaySeconds", "1", "-SettleSeconds", "1", "-Verify", "-SkipNas")
  Check "le interruzioni finiscono e la verifica passa" ((Invoke-S25 $interrupt) -eq 0)
  $calls = Get-Content -LiteralPath (Join-Path $phone "adb-calls.log")
  Check "il Wi-Fi e' stato riacceso alla fine" (@($calls | Where-Object { $_ -match 'svc \| wifi \| enable' }).Count -ge 1)
  Check "l'adb finto non ha ricevuto altro che comandi previsti" (@($calls | Where-Object { $_ -match 'non previsto' }).Count -eq 0)
  Check "l'app e' stata rilanciata dopo le morti" (@($calls | Where-Object { $_ -match 'monkey' }).Count -ge 1)

  Write-Host "4. Un file tolto dal telefono (guardiano, Libera spazio)"
  $freed = Get-ChildItem -LiteralPath (Join-Path $phone "sdcard") -Recurse -File | Where-Object { $_.FullName -match 'mcg-prova' -and $_.Extension -eq '.jpg' -and $_.Name -notin @("testo.jpg") } | Select-Object -First 1
  Remove-Item -LiteralPath $freed.FullName -Force
  Check "senza -AllowFreed la verifica fallisce (codice 4)" ((Invoke-S25 ($common + @("-Step", "verify", "-SkipNas"))) -eq 4)
  Check "con -AllowFreed passa" ((Invoke-S25 ($common + @("-Step", "verify", "-SkipNas", "-AllowFreed"))) -eq 0)

  Write-Host "5. Un file del telefono cambia"
  $victim = Get-ChildItem -LiteralPath (Join-Path $phone "sdcard") -Recurse -File | Where-Object { $_.FullName -match 'mcg-prova' -and $_.Extension -eq '.jpg' -and $_.Name -ne "testo.jpg" } | Select-Object -First 1
  Add-Content -LiteralPath $victim.FullName -Value "x"
  Check "la verifica fallisce con il codice 4" ((Invoke-S25 ($common + @("-Step", "verify", "-SkipNas", "-AllowFreed"))) -eq 4)

  Write-Host "6. Pulizia"
  Check "senza -Yes rifiuta" ((Invoke-S25 ($common + @("-Step", "clean"))) -ne 0)
  Check "senza -Yes non toglie niente" (@(Get-ChildItem -LiteralPath (Join-Path $phone "sdcard") -Recurse -File -ErrorAction SilentlyContinue).Count -gt 0)
  Check "con -Yes toglie le due cartelle" ((Invoke-S25 ($common + @("-Step", "clean", "-Yes"))) -eq 0)
  Check "non resta nessun file di mcg-prova" (@(Get-ChildItem -LiteralPath (Join-Path $phone "sdcard") -Recurse -File -ErrorAction SilentlyContinue).Count -eq 0)

  Write-Host "7. Le guardie"
  $before = Get-CallCount
  Check "un pacchetto che non e' di debug e' rifiutato" ((Invoke-S25 ($common + @("-Step", "seed", "-Package", "dev.pampa.mycloudgallery"))) -ne 0)
  Check "un Wi-Fi spento oltre 120 s e' rifiutato" ((Invoke-S25 ($common + @("-Step", "interrupt", "-WifiOffMaxSeconds", "121"))) -ne 0)
  Check "meno di 20 foto sono rifiutate" ((Invoke-S25 ($common + @("-Step", "seed", "-Photos", "5"))) -ne 0)
  Check "piu' di 200 foto sono rifiutate" ((Invoke-S25 ($common + @("-Step", "seed", "-Photos", "500"))) -ne 0)
  Check "una share che non e' test e' rifiutata" ((Invoke-S25 ($common + @("-Step", "seed", "-DestShare", "test2"))) -ne 0)
  Check "un passo che il modo non ha (edit) e' rifiutato" ((Invoke-S25 ($common + @("-Step", "edit"))) -ne 0)
  Check "i rifiuti non hanno toccato il telefono" ((Get-CallCount) -eq $before)
} finally {
  Remove-Item Env:MCG_ADB, Env:FAKE_PHONE -ErrorAction SilentlyContinue
  if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }
}

if ($failures.Count -gt 0) {
  Write-Host ("`n{0} controlli non passano." -f $failures.Count) -ForegroundColor Red
  exit 1
}
Write-Host "`nTutto come previsto: il modo share dello script del telefono funziona contro l'adb finto." -ForegroundColor Green
exit 0
