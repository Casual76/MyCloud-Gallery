<#
.SYNOPSIS
  Prova a secco COMPLETA dello script del telefono per il backup (tools\chaos\s25.ps1 -Mode backup) contro un adb FINTO: nessun telefono,
  nessun adb vero, nessun NAS (docs/prova-500.md, Parte B9).

.DESCRIPTION
  Il telefono finto e' una cartella temporanea (tools\chaos\fake-adb\fake-adb.ps1 imita `adb push`, `shell find ... sha256sum`, `stat`, `cp`,
  `dd`, `rm`, `content call/query`, `pm path`, `svc wifi`...). Lo script prova, in quest'ordine:
    1. la semina (-Step seed): fixture di formati misti + jpeg unici + video "grandi" (di pochi byte), il manifesto con le impronte;
    2. la verifica a telefono invariato (-Step verify -SkipNas): le impronte tornano;
    3. le interruzioni (-Step interrupt, 3 interruzioni con attese di 1 s) e la verifica finale;
    3b. (5B) le modifiche dopo il primo backup (-Step edit): le righe `edit=` del manifesto, la verifica che le accetta, un file tolto da
        "Libera spazio" (fallisce, a meno di -AllowFreed);
    4. un file del telefono che cambia: la verifica deve FALLIRE (codice 4);
    5. la pulizia: senza -Yes rifiuta, con -Yes toglie solo le due cartelle di `mcg-prova`.
  Il verificatore sul NAS (area.ps1 -Mode backup -Stage verify) NON viene lanciato (-SkipNas): questo script non tocca mai il NAS.
  Esce con 0 se tutto e' come previsto, con 1 altrimenti. Non lascia niente fuori dalla cartella temporanea e da build\chaos\.

.PARAMETER Photos        Quante foto in tutto (default 60).
.PARAMETER FixturesDir   I fixture gia' generati (default: li genera con tools\fixtures\generate.ps1 -SkipBig, serve il JDK).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\chaos\s25-finto.ps1
#>
[CmdletBinding()]
param(
  [int]$Photos = 60,
  [string]$FixturesDir
)

$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$script = Join-Path $here "s25.ps1"
$work = Join-Path ([System.IO.Path]::GetTempPath()) ("mcg-s25-finto-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
$phone = Join-Path $work "telefono"
$manifest = Join-Path $work "manifesto.txt"
$log = Join-Path $work "racconto.log"
$failures = New-Object System.Collections.Generic.List[string]

function Check([string]$what, [bool]$ok) {
  if ($ok) { Write-Host ("  OK    {0}" -f $what) -ForegroundColor Green } else { Write-Host ("  FAIL  {0}" -f $what) -ForegroundColor Red; $failures.Add($what) }
}

# Esegue s25.ps1 in un processo a parte con l'adb finto e restituisce il codice di uscita.
function Invoke-S25([string[]]$arguments) {
  $env:MCG_ADB = Join-Path $here "fake-adb\adb.cmd"
  $env:FAKE_PHONE = $phone
  & powershell -NoProfile -ExecutionPolicy Bypass -File $script @arguments -Serial FAKE123 -Log $log | Out-Null
  return $LASTEXITCODE
}

New-Item -ItemType Directory -Force -Path $phone | Out-Null
try {
  $run = "20261016-101500-1a2b3c4d"
  $common = @("-Mode", "backup", "-Run", $run, "-Manifest", $manifest, "-Seed", "5")

  Write-Host "1. Semina"
  $seed = $common + @("-Step", "seed", "-Photos", "$Photos", "-BigVideos", "2", "-BigVideoMb", "2")
  if ($FixturesDir) { $seed += @("-FixturesDir", $FixturesDir) }
  Check "la semina finisce" ((Invoke-S25 $seed) -eq 0)
  $lines = if (Test-Path -LiteralPath $manifest) { @(Get-Content -LiteralPath $manifest) } else { @() }
  $phoneLines = @($lines | Where-Object { $_ -like "phone=*" })
  Check "il manifesto ha il giro e la destinazione" (($lines -contains "run=$run") -and ($lines -contains "dest=test|mcg-campo"))
  Check "il manifesto ha almeno $Photos file del telefono" ($phoneLines.Count -ge $Photos)
  $onPhone = @(Get-ChildItem -LiteralPath (Join-Path $phone "sdcard") -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $_.FullName -notlike "*adb-calls.log" })
  Check "i file sono solo in DCIM\mcg-prova e Pictures\mcg-prova" (@($onPhone | Where-Object { $_.FullName -notmatch '\\sdcard\\(DCIM|Pictures)\\mcg-prova\\' }).Count -eq 0)
  Check "ogni riga del manifesto ha impronta, byte ed etichetta" (@($phoneLines | Where-Object { $_ -notmatch '^phone=[0-9a-f]{64}\|\d+\|.+$' }).Count -eq 0)
  Check "ci sono i due video grandi" (@($onPhone | Where-Object { $_.Name -like "VID_mcg_grande_*.mp4" }).Count -eq 2)

  Write-Host "2. Verifica a telefono invariato"
  Check "le impronte tornano" ((Invoke-S25 ($common + @("-Step", "verify", "-SkipNas"))) -eq 0)

  Write-Host "3. Interruzioni"
  $interrupt = $common + @("-Step", "interrupt", "-Interruptions", "3", "-MinGapSeconds", "1", "-MaxGapSeconds", "1", "-WifiOffMinSeconds", "1", "-WifiOffMaxSeconds", "1", "-RelaunchDelaySeconds", "1", "-SettleSeconds", "1", "-Verify", "-SkipNas")
  Check "le interruzioni finiscono e la verifica passa" ((Invoke-S25 $interrupt) -eq 0)
  $calls = Get-Content -LiteralPath (Join-Path $phone "adb-calls.log")
  Check "il Wi-Fi e' stato riacceso alla fine" (@($calls | Where-Object { $_ -match 'svc \| wifi \| enable' }).Count -ge 1)
  Check "l'adb finto non ha ricevuto altro che comandi previsti" (@($calls | Where-Object { $_ -match 'non previsto' }).Count -eq 0)

  Write-Host "3b. Modifiche dopo il primo backup (5B: Samsung `"Salva`")"
  Check "il piano a secco della modifica non esegue niente" ((Invoke-S25 ($common + @("-Step", "edit", "-Edits", "5", "-DryRun"))) -eq 0)
  Check "la modifica finisce" ((Invoke-S25 ($common + @("-Step", "edit", "-Edits", "5"))) -eq 0)
  $editLines = @(Get-Content -LiteralPath $manifest | Where-Object { $_ -like "edit=*" })
  Check "il manifesto ha una riga edit= per ogni foto modificata" ($editLines.Count -eq 5)
  Check "ogni riga ha due impronte diverse e l'etichetta" (@($editLines | Where-Object { $part = ($_ -replace '^edit=', '') -split '\|'; ($part.Count -lt 3) -or ($part[0] -eq $part[1]) -or ($part[0] -notmatch '^[0-9a-f]{64}$') -or ($part[1] -notmatch '^[0-9a-f]{64}$') }).Count -eq 0)
  Check "le impronte tornano con le modifiche del manifesto" ((Invoke-S25 ($common + @("-Step", "verify", "-SkipNas"))) -eq 0)
  # Cio' che "Libera spazio" toglie dal telefono: senza -AllowFreed e' un cambiamento, con no (le altre impronte devono tornare).
  $editedLabels = @($editLines | ForEach-Object { ($_ -split '\|')[2] })
  $freed = Get-ChildItem -LiteralPath (Join-Path $phone "sdcard") -Recurse -File | Where-Object { $_.FullName -match 'mcg-prova' -and $editedLabels -notcontains $_.Name -and $_.Extension -ne '.mp4' } | Select-Object -First 1
  Remove-Item -LiteralPath $freed.FullName -Force
  Check "un file tolto dal telefono senza -AllowFreed fa fallire (codice 4)" ((Invoke-S25 ($common + @("-Step", "verify", "-SkipNas"))) -eq 4)
  Check "con -AllowFreed passa" ((Invoke-S25 ($common + @("-Step", "verify", "-SkipNas", "-AllowFreed"))) -eq 0)

  Write-Host "4. Un file del telefono cambia"
  $victim = Get-ChildItem -LiteralPath (Join-Path $phone "sdcard\DCIM\mcg-prova") -File | Select-Object -First 1
  Add-Content -LiteralPath $victim.FullName -Value "x"
  Check "la verifica fallisce con il codice 4" ((Invoke-S25 ($common + @("-Step", "verify", "-SkipNas", "-AllowFreed"))) -eq 4)

  Write-Host "5. Pulizia"
  Check "senza -Yes rifiuta" ((Invoke-S25 ($common + @("-Step", "clean"))) -ne 0)
  Check "senza -Yes non toglie niente" (@(Get-ChildItem -LiteralPath (Join-Path $phone "sdcard") -Recurse -File -ErrorAction SilentlyContinue).Count -gt 0)
  Check "con -Yes toglie le due cartelle" ((Invoke-S25 ($common + @("-Step", "clean", "-Yes"))) -eq 0)
  Check "non resta nessun file di mcg-prova" (@(Get-ChildItem -LiteralPath (Join-Path $phone "sdcard") -Recurse -File -ErrorAction SilentlyContinue).Count -eq 0)

  Write-Host "6. Un pacchetto che non e' di debug"
  Check "il modo backup lo rifiuta" ((Invoke-S25 ($common + @("-Step", "seed", "-Package", "dev.pampa.mycloudgallery"))) -ne 0)
} finally {
  Remove-Item Env:MCG_ADB, Env:FAKE_PHONE -ErrorAction SilentlyContinue
  if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }
}

if ($failures.Count -gt 0) {
  Write-Host ("`n{0} controlli non passano." -f $failures.Count) -ForegroundColor Red
  exit 1
}
Write-Host "`nTutto come previsto: lo script del telefono funziona contro l'adb finto." -ForegroundColor Green
exit 0
