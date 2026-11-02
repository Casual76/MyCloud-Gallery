<#
.SYNOPSIS
  Prove del controllo delle recinzioni (Fase 10 P10, tools\release\recinzioni.ps1) con COPIE TEMPORANEE sotto build\prova-recinzioni\.

.DESCRIPTION
  Non cambia mai FeatureFlags.kt ne' recinzioni.json veri: le quattro recinzioni "spente" esistono solo in copie sotto build\. Prova:
  il lettore del sorgente (accese, spente, miste, forma illeggibile), il lettore del bytecode su due dump veri di dexdump della classe
  FeatureFlags di una release con le recinzioni accese e di una con le quattro spente (tools\release\fixtures\), il consenso unico
  (riga di docs\decisioni.md, data, quattro id) e il ritorno indietro parziale. Codice di uscita 0 solo se tutte le prove passano.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
. (Join-Path $PSScriptRoot "recinzioni.ps1")
$work = Join-Path $root "build\prova-recinzioni"
if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
New-Item -ItemType Directory -Path $work | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)
$failures = New-Object System.Collections.Generic.List[string]
$count = 0
$today = [datetime]"2026-11-05"
$date = "2026-11-02"
$marker = "PASSO-UNICO-PROVA-7"
$partialMarker = "RITORNO-PARZIALE-PROVA-8"

function Assert-That([string]$name, [bool]$condition, [string]$detail = "") {
  $script:count++
  if (-not $condition) { $script:failures.Add($name + $(if ($detail) { ": " + $detail } else { "" })); Write-Host ("  [KO] " + $name + $(if ($detail) { "`n" + $detail } else { "" })) -ForegroundColor Red }
  else { Write-Host ("  [ok] " + $name) -ForegroundColor DarkGray }
}

function Test-Has([string[]]$problems, [string]$pattern) { return @($problems | Where-Object { $_ -match $pattern }).Count -gt 0 }

$realJson = Join-Path $PSScriptRoot "recinzioni.json"
$realFlags = Join-Path $root "core-common\src\main\kotlin\dev\pampa\mycloudgallery\core\common\featureflags\FeatureFlags.kt"
$realDecisions = Join-Path $root "docs\decisioni.md"
$flagsText = [System.IO.File]::ReadAllText($realFlags, $utf8)

function New-Config() { return (Read-RecinzioniJson $realJson) }
function Set-Declared($cfg, [string[]]$off) { foreach ($r in $cfg.recinzioni) { $r.stato = $(if ($off -contains $r.id) { "spenta" } else { "accesa" }) } }
function Set-Consent($cfg) { $cfg.consenso.data = $date; $cfg.consenso.marcatore = $marker }
function Set-Partial($cfg) { $cfg.ritornoParziale.dichiarato = $true; $cfg.ritornoParziale.data = "2026-11-04"; $cfg.ritornoParziale.marcatore = $partialMarker; $cfg.ritornoParziale.motivo = "prova" }

# Il sorgente con le recinzioni indicate spente (la copia vive solo in memoria o sotto build\).
function Get-FlippedFlags([string]$text, [string[]]$off) {
  $map = @{
    "F4-D31" = @('(?m)^(\s+)indexPublishTestSharesOnly = true,', '${1}indexPublishTestSharesOnly = false,')
    "F5-D12" = @('(private const val BACKUP_TEST_SHARES_ONLY: Boolean = )true', '${1}false')
    "F7-D30" = @('(private const val SHARED_ALBUMS_TEST_SHARES_ONLY: Boolean = )true', '${1}false')
    "F8-D34" = @('(private const val FACES_SYNC_TEST_SHARES_ONLY: Boolean = )true', '${1}false')
  }
  foreach ($id in $off) {
    $new = [regex]::Replace($text, $map[$id][0], $map[$id][1])
    if ($new -eq $text) { throw "FeatureFlags.kt non ha la forma attesa per $id" }
    $text = $new
  }
  return $text
}

function Get-DexStatesOf([string]$fixture) {
  $lines = [System.IO.File]::ReadAllLines((Join-Path $PSScriptRoot ("fixtures\" + $fixture)), $utf8)
  return Get-FenceDexStates (Get-FlagsDexFacts $lines)
}

function Get-Decisions([string[]]$extra) { return @("# decisioni di prova", "riga qualunque") + $extra }
$consentLine = "| consenso | $marker | $date | F4-D31 F5-D12 F7-D30 F8-D34 passo unico |"
$partialLine = "| ritorno | $partialMarker | 2026-11-04 | F5-D12 torna accesa |"

$all = $script:FenceIds
$srcOn = Get-FlagsSourceStates $flagsText
$srcOff = Get-FlagsSourceStates (Get-FlippedFlags $flagsText $all)
$dexOn = Get-DexStatesOf "featureflags-accesa.dexdump.txt"
$dexOff = Get-DexStatesOf "featureflags-spenta.dexdump.txt"

Write-Host "Lettore del sorgente (FeatureFlags.kt)"
Assert-That "accese: le quattro 'accesa'" (@($all | Where-Object { $srcOn[$_] -eq "accesa" }).Count -eq 4)
Assert-That "spente (copia in memoria): le quattro 'spenta'" (@($all | Where-Object { $srcOff[$_] -eq "spenta" }).Count -eq 4)
$srcMixed = Get-FlagsSourceStates (Get-FlippedFlags $flagsText @("F5-D12"))
Assert-That "una sola spenta: solo F5-D12 'spenta'" ($srcMixed["F5-D12"] -eq "spenta" -and $srcMixed["F4-D31"] -eq "accesa" -and $srcMixed["F7-D30"] -eq "accesa" -and $srcMixed["F8-D34"] -eq "accesa")
$srcOdd = Get-FlagsSourceStates ($flagsText -replace '(?m)^(\s+)indexPublishTestSharesOnly = true,', '${1}indexPublishTestSharesOnly = unaAltraCosa,')
Assert-That "forma illeggibile: 'ignota', mai indovinata" ($srcOdd["F4-D31"] -eq "ignota" -and $srcOdd["F5-D12"] -eq "accesa")

Write-Host "Lettore del bytecode (dump veri di una release accesa e di una con le quattro spente)"
Assert-That "release accesa: le quattro 'accesa', senza problemi" (@($all | Where-Object { $dexOn[$_].Stato -eq "accesa" -and $dexOn[$_].Problemi.Count -eq 0 }).Count -eq 4)
Assert-That "release accesa: F4-D31 ha la prova del toString, le altre tre prove (getter, decisione, toString)" ($dexOn["F4-D31"].Prove -eq 1 -and $dexOn["F5-D12"].Prove -eq 3 -and $dexOn["F7-D30"].Prove -eq 3 -and $dexOn["F8-D34"].Prove -eq 3)
Assert-That "release spenta: le quattro 'spenta', senza problemi" (@($all | Where-Object { $dexOff[$_].Stato -eq "spenta" -and $dexOff[$_].Problemi.Count -eq 0 }).Count -eq 4)
$onLines = [System.IO.File]::ReadAllLines((Join-Path $PSScriptRoot "fixtures\featureflags-accesa.dexdump.txt"), $utf8)
$mixedLines = @($onLines | ForEach-Object { $_ -replace 'backupTestSharesOnly=true', 'backupTestSharesOnly=false' })
$mixedStates = Get-FenceDexStates (Get-FlagsDexFacts $mixedLines)
Assert-That "prove discordanti (toString dice spenta, getter e decisione accesa): 'incerta'" ($mixedStates["F5-D12"].Stato -eq "incerta" -and (Test-Has $mixedStates["F5-D12"].Problemi "discordanti"))
$noGetter = Get-FenceDexStates (Get-FlagsDexFacts @($onLines | ForEach-Object { $_ -replace "'getFacesSyncTestSharesOnly'", "'getAltro'" }))
Assert-That "getter tolto da R8: 'incerta' con il motivo" ($noGetter["F8-D34"].Stato -eq "incerta" -and (Test-Has $noGetter["F8-D34"].Problemi "getter"))
$empty = Get-FenceDexStates (Get-FlagsDexFacts @())
Assert-That "classe non letta: 'incerta' per tutte" (@($all | Where-Object { $empty[$_].Stato -eq "incerta" }).Count -eq 4)

Write-Host "Stato 'tutte accese' (com'e' oggi)"
$cfg = New-Config
Assert-That "recinzioni.json di oggi: quattro accese e consenso vuoto" (@($cfg.recinzioni | Where-Object { $_.stato -eq "accesa" }).Count -eq 4 -and -not $cfg.consenso.data -and -not $cfg.consenso.marcatore -and -not $cfg.ritornoParziale.dichiarato)
Assert-That "tutte accese, solo sorgente: nessun problema" ((Test-RecinzioniStato $cfg $srcOn $null (Get-Decisions @()) $today).Count -eq 0)
Assert-That "tutte accese, sorgente e bytecode: nessun problema" ((Test-RecinzioniStato $cfg $srcOn $dexOn (Get-Decisions @()) $today).Count -eq 0)
$real = Invoke-RecinzioniCheck $realJson $realFlags $realDecisions $dexOn $today
Assert-That "i file veri del repository (accese) con il bytecode accesa: nessun problema" ($real.Count -eq 0) ($real -join "; ")
$c = New-Config; Set-Consent $c
Assert-That "accese con un consenso scritto: problema" (Test-Has (Test-RecinzioniStato $c $srcOn $dexOn (Get-Decisions @($consentLine)) $today) "non e' vuoto")

Write-Host "Stato 'tutte spente' (un solo consenso)"
$c = New-Config; Set-Declared $c $all; Set-Consent $c
$decisions = Get-Decisions @($consentLine)
$p = Test-RecinzioniStato $c $srcOff $dexOff $decisions $today
Assert-That "spente con consenso, sorgente e bytecode spenti: nessun problema" ($p.Count -eq 0) ($p -join "; ")
$c2 = New-Config; Set-Declared $c2 $all
Assert-That "spente senza consenso: problema" (Test-Has (Test-RecinzioniStato $c2 $srcOff $dexOff $decisions $today) "senza il consenso unico")
$c2 = New-Config; Set-Declared $c2 $all; Set-Consent $c2; $c2.consenso.marcatore = "NON-C-E"
Assert-That "marcatore che non compare in decisioni.md: problema" (Test-Has (Test-RecinzioniStato $c2 $srcOff $dexOff $decisions $today) "non compare")
Assert-That "marcatore in due righe: problema" (Test-Has (Test-RecinzioniStato $c $srcOff $dexOff (Get-Decisions @($consentLine, $consentLine)) $today) "compare in 2 righe")
Assert-That "riga senza la data: problema" (Test-Has (Test-RecinzioniStato $c $srcOff $dexOff (Get-Decisions @("| consenso | $marker | F4-D31 F5-D12 F7-D30 F8-D34 |")) $today) "non porta la data")
Assert-That "riga che non nomina una recinzione: problema" (Test-Has (Test-RecinzioniStato $c $srcOff $dexOff (Get-Decisions @("| consenso | $marker | $date | F4-D31 F5-D12 F7-D30 |")) $today) "non nomina F8-D34")
$c2 = New-Config; Set-Declared $c2 $all; Set-Consent $c2; $c2.consenso.data = "02/11/2026"
Assert-That "data in un altro formato: problema" (Test-Has (Test-RecinzioniStato $c2 $srcOff $dexOff $decisions $today) "AAAA-MM-GG")
$c2 = New-Config; Set-Declared $c2 $all; Set-Consent $c2; $c2.consenso.data = "2026-12-24"
Assert-That "data nel futuro: problema" (Test-Has (Test-RecinzioniStato $c2 $srcOff $dexOff (Get-Decisions @($consentLine -replace $date, "2026-12-24")) $today) "futuro")

Write-Host "Stato dichiarato diverso da quello osservato"
$c = New-Config; Set-Declared $c $all; Set-Consent $c
$p = Test-RecinzioniStato $c $srcOn $null $decisions $today
Assert-That "dichiarate spente ma il sorgente le ha accese: un problema per ognuna" (@($p | Where-Object { $_ -match "FeatureFlags.kt la ha accesa" }).Count -eq 4) ($p -join "; ")
$c = New-Config
$p = Test-RecinzioniStato $c $srcOff $null (Get-Decisions @()) $today
Assert-That "dichiarate accese ma il sorgente le ha spente di nascosto: un problema per ognuna" (@($p | Where-Object { $_ -match "FeatureFlags.kt la ha spenta" }).Count -eq 4) ($p -join "; ")
$p = Test-RecinzioniStato $c $srcOn $dexOff (Get-Decisions @()) $today
Assert-That "dichiarate accese ma il bytecode della release le ha spente: un problema per ognuna" (@($p | Where-Object { $_ -match "il bytecode della release la ha spenta" }).Count -eq 4) ($p -join "; ")
$p = Test-RecinzioniStato $c $srcOn $empty (Get-Decisions @()) $today
Assert-That "bytecode che non dice lo stato: il controllo fallisce, non passa a vuoto" (@($p | Where-Object { $_ -match "non dice lo stato" }).Count -eq 4)
$p = Test-RecinzioniStato $c $srcOdd $null (Get-Decisions @()) $today
Assert-That "sorgente illeggibile: il controllo fallisce" (Test-Has $p "F4-D31 : FeatureFlags.kt non si legge")

Write-Host "Stato misto e ritorno indietro parziale"
$mixedSrc = Get-FlagsSourceStates (Get-FlippedFlags $flagsText @("F4-D31", "F7-D30", "F8-D34"))
$mixedLines2 = @($onLines | ForEach-Object { $_ })
$mixedDex = @{}
foreach ($id in $all) { $mixedDex[$id] = $(if ($id -eq "F5-D12") { $dexOn[$id] } else { $dexOff[$id] }) }
$c = New-Config; Set-Declared $c @("F4-D31", "F7-D30", "F8-D34"); Set-Consent $c
$d2 = Get-Decisions @($consentLine, $partialLine)
$p = Test-RecinzioniStato $c $mixedSrc $mixedDex $d2 $today
Assert-That "misto senza ritorno parziale dichiarato: problema" (Test-Has $p "senza un ritorno indietro parziale") ($p -join "; ")
Set-Partial $c
$p = Test-RecinzioniStato $c $mixedSrc $mixedDex $d2 $today
Assert-That "misto con ritorno parziale, consenso e righe: nessun problema" ($p.Count -eq 0) ($p -join "; ")
$c3 = New-Config; Set-Declared $c3 @("F4-D31", "F7-D30", "F8-D34"); Set-Consent $c3; Set-Partial $c3; $c3.ritornoParziale.motivo = ""
Assert-That "ritorno parziale senza motivo: problema" (Test-Has (Test-RecinzioniStato $c3 $mixedSrc $mixedDex $d2 $today) "manca il motivo")
$c3.ritornoParziale.motivo = "prova"; $c3.ritornoParziale.data = "2026-11-01"
Assert-That "ritorno parziale con data prima del consenso: problema" (Test-Has (Test-RecinzioniStato $c3 $mixedSrc $mixedDex (Get-Decisions @($consentLine, ($partialLine -replace "2026-11-04", "2026-11-01"))) $today) "anteriore al consenso")
Assert-That "ritorno parziale senza la sua riga in decisioni.md: problema" (Test-Has (Test-RecinzioniStato $c $mixedSrc $mixedDex (Get-Decisions @($consentLine)) $today) "ritornoParziale")
Assert-That "ritorno parziale senza consenso: problema" (Test-Has (Test-RecinzioniStato $c $mixedSrc $mixedDex (Get-Decisions @($partialLine)) $today) "consenso")
$c4 = New-Config; Set-Declared $c4 $all; Set-Consent $c4; Set-Partial $c4
Assert-That "ritorno parziale dichiarato con tutte spente: problema" (Test-Has (Test-RecinzioniStato $c4 $srcOff $dexOff (Get-Decisions @($consentLine, $partialLine)) $today) "non e' un ritorno parziale")
$c5 = New-Config; Set-Partial $c5
Assert-That "ritorno parziale dichiarato con tutte accese: problema" (Test-Has (Test-RecinzioniStato $c5 $srcOn $dexOn (Get-Decisions @($partialLine)) $today) "non e' un ritorno parziale")

Write-Host "Forma di recinzioni.json"
$c = New-Config; $c.recinzioni = @($c.recinzioni | Select-Object -First 3)
Assert-That "tre recinzioni invece di quattro: problema" (Test-Has (Test-RecinzioniStato $c $srcOn $null (Get-Decisions @()) $today) "esattamente quattro")
$c = New-Config; $c.recinzioni[1].campo = "altroCampo"
Assert-That "campo cambiato: problema" (Test-Has (Test-RecinzioniStato $c $srcOn $null (Get-Decisions @()) $today) "campo")
$c = New-Config; $c.recinzioni[1].id = "F4-D31"
Assert-That "recinzione doppia: problema" (Test-Has (Test-RecinzioniStato $c $srcOn $null (Get-Decisions @()) $today) "due volte")
$c = New-Config; $c.recinzioni[0].stato = "forse"
Assert-That "stato che non e' accesa o spenta: problema" (Test-Has (Test-RecinzioniStato $c $srcOn $null (Get-Decisions @()) $today) "solo accesa o spenta")
$c = New-Config; $c.aree[1].cartelleAttese.privata = @("Backup", "../fuori")
Assert-That "cartella attesa con '..': problema" (Test-Has (Test-RecinzioniStato $c $srcOn $null (Get-Decisions @()) $today) "cartella attesa non valida")

Write-Host "Copie temporanee su file (build\prova-recinzioni)"
$tmpFlags = Join-Path $work "FeatureFlags-spente.kt"
$tmpJson = Join-Path $work "recinzioni-spente.json"
$tmpDec = Join-Path $work "decisioni-prova.md"
[System.IO.File]::WriteAllText($tmpFlags, (Get-FlippedFlags $flagsText $all), $utf8)
$c = New-Config; Set-Declared $c $all; Set-Consent $c
[System.IO.File]::WriteAllText($tmpJson, ($c | ConvertTo-Json -Depth 8), $utf8)
[System.IO.File]::WriteAllLines($tmpDec, (Get-Decisions @($consentLine)), $utf8)
$p = Invoke-RecinzioniCheck $tmpJson $tmpFlags $tmpDec $dexOff $today
Assert-That "quattro spente su file temporanei, consenso e bytecode spenti: nessun problema" ($p.Count -eq 0) ($p -join "; ")
$p = Invoke-RecinzioniCheck $tmpJson $realFlags $tmpDec $dexOff $today
Assert-That "stessi file ma FeatureFlags.kt vero (accese): il controllo fallisce" ($p.Count -eq 4)
$flagsAfter = [System.IO.File]::ReadAllText($realFlags, $utf8)
Assert-That "FeatureFlags.kt vero non e' stato toccato dalle prove" ($flagsAfter -eq $flagsText)
Assert-That "recinzioni.json vero non e' stato toccato dalle prove" (@((Read-RecinzioniJson $realJson).recinzioni | Where-Object { $_.stato -eq "accesa" }).Count -eq 4)

if ($failures.Count -gt 0) {
  Write-Host ("recinzioni: {0} prove, {1} fallite" -f $count, $failures.Count) -ForegroundColor Red
  foreach ($f in $failures) { Write-Host ("  - " + $f) -ForegroundColor Red }
  exit 1
}
Write-Host ("recinzioni: {0} prove, tutte passate (copie temporanee sotto build\prova-recinzioni)" -f $count) -ForegroundColor Green
exit 0
