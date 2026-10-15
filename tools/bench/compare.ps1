<#
.SYNOPSIS
  Confronta i risultati del Macrobenchmark con le soglie (benchmark/soglie.json) e con il riferimento accettato
  (docs/bench/riferimento-<dispositivo>.json). Fase 3, P9 (docs/fase3-progetto.md par. 2.6, D21).

.DESCRIPTION
  Legge i file *-benchmarkData.json che Macrobenchmark scrive (AGP li copia in
  benchmark\build\outputs\connected_android_test_additional_output\...), riconosce il dispositivo dal modello e, per
  ogni scenario di soglie.json applicabile a quel tipo di dispositivo:

    - soglia ASSOLUTA   metrica/statistica entro il massimo (o il minimo) di soglie.json;
    - REGRESSIONE       metrica/statistica entro l'aumento massimo sul riferimento accettato (+10% o +15%);
    - VALIDITA'         la prova ha misurato quello che doveva (per l'avvio: la Libreria era visibile).

  Esiti per riga:  OK  FAIL  MANCA (prova attesa senza risultati: saltata o non girata)  CAL (tetto da calibrare)
                   NUOVO (nessun riferimento per confrontare)  N/A (scenario non per questo tipo di dispositivo)

  Codice di uscita: 0 se nessun FAIL (e, con -RequireAll, nessun MANCA); 1 altrimenti; 2 per errori d'uso o file illeggibili.

  In -Smoke (emulatore, `check.ps1 -Bench` lo imposta da solo) NESSUNA soglia si applica: i numeri dell'emulatore non
  valgono. Si stampa che cosa e' girato, che cosa e' stato saltato e perche', e si esce con 0 se almeno una prova e' girata
  o se tutte le saltate hanno un motivo.

.PARAMETER Results       File *-benchmarkData.json o cartella da cercare (ricorsiva). Default: la cartella di AGP.
.PARAMETER Device        Chiave di soglie.json (s25, tab-s9, emulatore-telefono, emulatore-tablet). Default: dal modello.
.PARAMETER Thresholds    soglie.json. Default: benchmark\soglie.json.
.PARAMETER ReferenceDir  Dove stanno i riferimenti. Default: docs\bench.
.PARAMETER Report        Cartella dei rapporti JUnit (TEST-*.xml) per dire perche' una prova e' stata saltata. Default: quella di AGP.
.PARAMETER RefreshHz     Frequenza dello schermo letta da check.ps1 (solo annotazione; sotto la minima del dispositivo avvisa).
.PARAMETER Smoke         Nessuna soglia: verifica solo che gli scenari girino.
.PARAMETER RequireAll    Una prova attesa senza risultati e' un FAIL (chiusura della Fase 3).
.PARAMETER Accept        Scrive docs\bench\riferimento-<dispositivo>.json con questi risultati (solo se nessuna soglia assoluta fallisce, salvo -Force).
                         Da una misura parziale le prove non misurate tengono i valori del riferimento in uso; -Origin ne dice la provenienza.
.PARAMETER Calibrate     Scrive in soglie.json il tetto di PSS dello zoom a 200 MP (misura + margine), se e' ancora `null`.
.PARAMETER SummaryJson   Scrive un riassunto leggibile da macchina (una riga per verifica).
.PARAMETER SelfTest      Prova la logica del confronto su file di esempio (tools\bench\samples): non serve un dispositivo.
.PARAMETER NoGuard       Spegne la guardia della Fase 4A (par. 5.6): niente riferimento del 13/10, niente "NOTO", niente regole `guardia`.
.PARAMETER ExtractReference  Cartelle di risultati (la prima che ha una prova vince) da cui scrivere docs\bench\riferimento-<dispositivo>-fase3.json:
                         le misure del 13/10 con cui la guardia della Fase 4A confronta (vedi -ReferenceDate e -Origin). Non serve altro.
.PARAMETER ReferenceDate Data da scrivere nel riferimento estratto (default: oggi).
.PARAMETER Origin        Da dove vengono i numeri del riferimento estratto o accettato (una riga di testo, finisce nel file).

.NOTES
  Guardia della Fase 4A. Con un riferimento (docs\bench\riferimento-<dispositivo>.json accettato dall'utente, altrimenti
  docs\bench\riferimento-<dispositivo>-fase3.json, le misure del 13/10) e senza -Smoke:

    - le regole `guardia` di soglie.json si valutano (jank e fotogramma piu' lungo: "non peggiorare");
    - una soglia assoluta che il riferimento violava gia' risulta NOTO e non FAIL (vale la guardia);
    - le prove `mista` devono girare sulla libreria mista (libraryNasShare >= mistaMinimo), altrimenti NON VALIDE;
    - nelle regressioni `arrotondaSu` porta il limite a un intero (681 ms + 10% = 749,1 -> 750, come nel par. 5.6).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\bench\compare.ps1 -RequireAll
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\bench\compare.ps1 -SelfTest
#>
[CmdletBinding()]
param(
  [string]$Results,
  [string]$Device,
  [string]$Thresholds,
  [string]$ReferenceDir,
  [string]$Report,
  [double]$RefreshHz = 0,
  [switch]$Smoke,
  [switch]$RequireAll,
  [switch]$Accept,
  [switch]$Force,
  [switch]$Calibrate,
  [string]$SummaryJson,
  [switch]$SelfTest,
  [switch]$NoGuard,
  [string[]]$ExtractReference,
  [string]$ReferenceDate,
  [string]$Origin
)

$ErrorActionPreference = "Stop"
$inv = [System.Globalization.CultureInfo]::InvariantCulture
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
if (-not $Thresholds) { $Thresholds = Join-Path $root "benchmark\soglie.json" }
if (-not $ReferenceDir) { $ReferenceDir = Join-Path $root "docs\bench" }
if (-not $Results) { $Results = Join-Path $root "benchmark\build\outputs\connected_android_test_additional_output" }
if (-not $Report) { $Report = Join-Path $root "benchmark\build\outputs\androidTest-results" }

# --- Utilita' ---------------------------------------------------------------------------------------------

function Read-Json([string]$path) {
  $text = [System.IO.File]::ReadAllText($path, (New-Object System.Text.UTF8Encoding($false)))
  return $text | ConvertFrom-Json
}

function Format-Number($value, [string]$pattern = "0.###") {
  if ($null -eq $value) { return "-" }
  return ([double]$value).ToString($pattern, $inv)
}

function Has-Property($object, [string]$name) {
  return ($null -ne $object) -and ($null -ne $object.PSObject.Properties[$name])
}

# Percentile "nearest rank" su una lista di valori (p in 0..100). Per P50/P90/P95/P99 e per gli p90 sulle iterazioni.
function Get-Percentile($values, [double]$p) {
  $sorted = @($values | Sort-Object)
  if ($sorted.Count -eq 0) { return $null }
  $rank = [int][math]::Ceiling($p / 100.0 * $sorted.Count)
  if ($rank -lt 1) { $rank = 1 }
  if ($rank -gt $sorted.Count) { $rank = $sorted.Count }
  return [double]$sorted[$rank - 1]
}

# Il valore di metrica/statistica di una prova. Le metriche "campionate" (FrameTimingMetric: un valore per fotogramma)
# portano P50/P90/P95/P99 gia' calcolati; le altre portano le iterazioni (`runs`) da cui si calcola.
function Get-StatValue($bench, [string]$metric, [string]$stat) {
  if (Has-Property $bench "sampledMetrics") {
    $sampled = $bench.sampledMetrics.PSObject.Properties[$metric]
    if ($sampled -and $stat -match '^[Pp](\d+)$') {
      $property = $sampled.Value.PSObject.Properties["P" + $Matches[1]]
      if ($property) { return [double]$property.Value }
    }
  }
  if (Has-Property $bench "metrics") {
    $plain = $bench.metrics.PSObject.Properties[$metric]
    if ($plain) {
      $m = $plain.Value
      $runs = @()
      if (Has-Property $m "runs") { $runs = @($m.runs | ForEach-Object { [double]$_ }) }
      switch -regex ($stat.ToLowerInvariant()) {
        '^(min|minimum)$' { if (Has-Property $m "minimum") { return [double]$m.minimum }; if ($runs.Count) { return [double](($runs | Measure-Object -Minimum).Minimum) } }
        '^(max|maximum)$' { if (Has-Property $m "maximum") { return [double]$m.maximum }; if ($runs.Count) { return [double](($runs | Measure-Object -Maximum).Maximum) } }
        '^median$' { if (Has-Property $m "median") { return [double]$m.median }; if ($runs.Count) { return (Get-Percentile $runs 50) } }
        '^mean$' { if ($runs.Count) { return [double](($runs | Measure-Object -Average).Average) } }
        '^p(\d+)$' { if ($runs.Count) { return (Get-Percentile $runs ([double]$Matches[1])) } }
      }
    }
  }
  return $null
}

function Test-Emulator($context) {
  if (-not (Has-Property $context "build")) { return $false }
  $b = $context.build
  $model = if (Has-Property $b "model") { [string]$b.model } else { "" }
  $fingerprint = if (Has-Property $b "fingerprint") { [string]$b.fingerprint } else { "" }
  return ($model -match '^(sdk_gphone|Android SDK|AOSP on|Emulator)') -or ($fingerprint -match '/generic|sdk_gphone|emulator')
}

# La chiave del dispositivo in soglie.json: dal modello (prefisso) o, per gli emulatori, dalla classe (tablet se il nome dice tablet).
function Resolve-DeviceKey($context, $thr, [string]$forced) {
  if ($forced) {
    if (-not (Has-Property $thr.dispositivi $forced)) { throw "dispositivo '$forced' sconosciuto: vedi benchmark\soglie.json" }
    return $forced
  }
  $model = if ((Has-Property $context "build") -and (Has-Property $context.build "model")) { [string]$context.build.model } else { "" }
  foreach ($entry in $thr.dispositivi.PSObject.Properties) {
    if (Has-Property $entry.Value "modelli") {
      foreach ($prefix in $entry.Value.modelli) { if ($model.StartsWith([string]$prefix, [System.StringComparison]::OrdinalIgnoreCase)) { return $entry.Name } }
    }
  }
  if (Test-Emulator $context) {
    if ($model -match 'tablet' -or ((Has-Property $context.build "device") -and [string]$context.build.device -match 'tablet')) { return "emulatore-tablet" }
    return "emulatore-telefono"
  }
  return $null
}

function New-Row([string]$scenario, [string]$test, [string]$kind, [string]$metric, $value, [string]$limit, [string]$status, [string]$note = "") {
  return [pscustomobject]@{ Scenario = $scenario; Test = $test; Kind = $kind; Metric = $metric; Value = $value; Limit = $limit; Status = $status; Note = $note }
}

function Get-LimitText($rule) {
  if (Has-Property $rule "massimo") { if ($null -eq $rule.massimo) { return "da calibrare" }; return "<= " + (Format-Number $rule.massimo) }
  if (Has-Property $rule "massimoEsclusivo") { return "< " + (Format-Number $rule.massimoEsclusivo) }
  if (Has-Property $rule "minimo") { return ">= " + (Format-Number $rule.minimo) }
  return ""
}

# Una regola assoluta contro un valore: OK / FAIL / CAL.
function Test-Absolute($rule, $value) {
  if (Has-Property $rule "massimo") {
    if ($null -eq $rule.massimo) { return "CAL" }
    if ($value -le [double]$rule.massimo) { return "OK" } else { return "FAIL" }
  }
  if (Has-Property $rule "massimoEsclusivo") { if ($value -lt [double]$rule.massimoEsclusivo) { return "OK" } else { return "FAIL" } }
  if (Has-Property $rule "minimo") { if ($value -ge [double]$rule.minimo) { return "OK" } else { return "FAIL" } }
  return "OK"
}

# I motivi delle prove saltate, dai rapporti JUnit di AGP: nome della prova -> messaggio dell'Assume.
function Read-SkippedTests([string]$dir) {
  $skipped = @{}
  if (-not $dir -or -not (Test-Path -LiteralPath $dir)) { return $skipped }
  foreach ($file in Get-ChildItem -LiteralPath $dir -Recurse -Filter "TEST-*.xml" -ErrorAction SilentlyContinue) {
    try {
      [xml]$xml = [System.IO.File]::ReadAllText($file.FullName, (New-Object System.Text.UTF8Encoding($false)))
    } catch { continue }
    foreach ($case in $xml.SelectNodes("//testcase")) {
      $node = $case.SelectSingleNode("skipped")
      if ($node) {
        $message = $node.GetAttribute("message")
        if (-not $message) { $message = $node.InnerText }
        $skipped[[string]$case.GetAttribute("name")] = ([string]$message).Trim()
      }
    }
  }
  return $skipped
}

# --- Valutazione ------------------------------------------------------------------------------------------

# Il valore di riferimento di una prova per metrica.statistica, o $null.
function Get-ReferenceValue($reference, [string]$test, [string]$metric, [string]$stat) {
  $key = "$metric.$stat"
  if ($reference -and (Has-Property $reference "prove") -and (Has-Property $reference.prove $test) -and (Has-Property $reference.prove.$test $key)) {
    return [double]$reference.prove.$test.$key
  }
  return $null
}

# Il limite di una regressione: riferimento * (1 + aumento). Con `arrotondaSu` (passo in unita' della metrica) il riferimento si
# porta prima al passo piu' vicino e il limite si arrotonda per eccesso, come scrive il par. 5.6: TTFD 680,7 -> 681, * 1,10 =
# 749,1 -> 750; viewerOpenMs 21,1 -> 21, * 1,15 = 24,15 -> 25.
function Get-RegressionCap([double]$baseline, $rule) {
  $cap = $baseline * (1.0 + [double]$rule.aumentoMassimo)
  if (-not (Has-Property $rule "arrotondaSu")) { return $cap }
  $step = [double]$rule.arrotondaSu
  if ($step -le 0) { return $cap }
  $rounded = [math]::Round($baseline / $step) * $step
  return [math]::Ceiling($rounded * (1.0 + [double]$rule.aumentoMassimo) / $step - 1e-9) * $step
}

# Restituisce le righe del confronto per un dispositivo. $benches = elenco delle prove lette (oggetti `benchmarks[*]`),
# $reference = il riferimento accettato (o $null), $skipped = nome -> motivo. $guard = la guardia della Fase 4A (par. 5.6):
# regole `guardia`, soglie gia' fuori nel riferimento = NOTO, prove `mista` valide solo sulla libreria mista.
function Invoke-Evaluation($thr, $benches, [string]$deviceKey, $reference, $skipped, [bool]$smoke, [bool]$guard = $false) {
  $deviceClass = [string]$thr.dispositivi.$deviceKey.classe
  $mistaMinimo = if (Has-Property $thr "mistaMinimo") { [double]$thr.mistaMinimo } else { 0.55 }
  $rows = New-Object System.Collections.Generic.List[object]
  foreach ($scenario in $thr.scenari) {
    $applies = @($scenario.classiDispositivo) -contains $deviceClass
    $mine = @($benches | Where-Object { ([string]$_.name -replace '\[.*$', '') -match $scenario.prove })
    $expected = @($scenario.proveAttese)
    if (-not $applies -and $mine.Count -eq 0) {
      $rows.Add((New-Row $scenario.id "(tutte)" "scenario" "-" $null "" "N/A" "scenario per: $(@($scenario.classiDispositivo) -join ', ')"))
      continue
    }
    # Le prove attese senza risultati.
    foreach ($name in $expected) {
      if (-not ($mine | Where-Object { ([string]$_.name -replace '\[.*$', '') -eq $name })) {
        $why = if ($skipped.ContainsKey($name)) { $skipped[$name] } else { "nessun risultato (prova non girata o fallita prima della misura)" }
        $rows.Add((New-Row $scenario.id $name "presenza" "-" $null "" "MANCA" $why))
      }
    }
    foreach ($bench in $mine) {
      $test = [string]$bench.name -replace '\[.*$', ''
      if ($smoke) {
        $iterations = if (Has-Property $bench "repeatIterations") { [string]$bench.repeatIterations } else { "?" }
        $rows.Add((New-Row $scenario.id $test "smoke" "-" $null "" "OK" "girata ($iterations iterazioni)"))
        continue
      }
      # Validita': la prova ha misurato quello che doveva.
      $valid = $true
      if (Has-Property $scenario "validita") {
        $v = $scenario.validita
        $value = Get-StatValue $bench $v.metrica $v.statistica
        if ($null -eq $value) {
          $valid = $false
          $rows.Add((New-Row $scenario.id $test "validita" $v.metrica $null ">= $(Format-Number $v.minimo)" "MANCA" "metrica di validita' assente"))
        } elseif ($value -lt [double]$v.minimo) {
          $valid = $false
          $rows.Add((New-Row $scenario.id $test "validita" $v.metrica $value ">= $(Format-Number $v.minimo)" "FAIL" "NON VALIDA: $($v.spiega)"))
        }
      }
      # La libreria mista (P10): una prova che non gira sul 60% di media solo-NAS non e' quella che la guardia confronta.
      # La metrica manca nei risultati vecchi (Fase 3): allora non si puo' dire e la prova si valuta come prima.
      if ($valid -and (Has-Property $scenario "mista") -and $scenario.mista) {
        $share = Get-StatValue $bench "libraryNasShare" "min"
        if ($null -ne $share -and $share -lt $mistaMinimo) {
          $valid = $false
          $rows.Add((New-Row $scenario.id $test "validita" "libraryNasShare" $share (">= " + (Format-Number $mistaMinimo)) "FAIL" "NON VALIDA: la prova non gira sulla libreria mista (quota solo-NAS sotto il minimo): e' un dataset della Fase 3"))
        }
      }
      if (-not $valid) { continue }
      foreach ($rule in @($scenario.assolute)) {
        $value = Get-StatValue $bench $rule.metrica $rule.statistica
        $label = "$($rule.metrica) $($rule.statistica)"
        if ($null -eq $value) {
          $note = "metrica assente"
          if ($rule.metrica -eq "timeToFullDisplayMs") { $note = "TTFD assente: l'app non chiama reportFullyDrawn (P7/P11)" }
          if ($rule.metrica -eq "viewerOpenMs") { $note = "sezione di traccia MCG:viewerOpen assente: la emette il viewer (P8)" }
          if ($rule.metrica -eq "viewerOpenMaxFrameMs") { $note = "nessun fotogramma dell'app nella finestra dell'apertura (ViewerOpenMetric)" }
          if ($rule.metrica -eq "jankPercent") { $note = "FrameTimeline senza fotogrammi dell'app nel blocco misurato (FrameJankMetric): traccia senza actual_frame_timeline_slice?" }
          $rows.Add((New-Row $scenario.id $test "assoluta" $label $null (Get-LimitText $rule) "MANCA" $note))
          continue
        }
        $status = Test-Absolute $rule $value
        $note = ""
        if ($status -eq "CAL") { $note = "nessun tetto: -Calibrate lo scrive con questa misura" }
        # La guardia: una soglia che il riferimento violava gia' non fa fallire, vale "non peggiorare" (regole `guardia`).
        if ($status -eq "FAIL" -and $guard) {
          $refValue = Get-ReferenceValue $reference $test $rule.metrica $rule.statistica
          if ($null -ne $refValue -and (Test-Absolute $rule $refValue) -eq "FAIL") {
            $status = "NOTO"
            $note = "gia' fuori soglia nel riferimento ($(Format-Number $refValue)): vale la guardia, non deve peggiorare"
          }
        }
        $rows.Add((New-Row $scenario.id $test "assoluta" $label $value (Get-LimitText $rule) $status $note))
      }
      foreach ($rule in @($scenario.regressione)) {
        $value = Get-StatValue $bench $rule.metrica $rule.statistica
        $label = "$($rule.metrica) $($rule.statistica)"
        if ($null -eq $value) { continue }
        $baseline = Get-ReferenceValue $reference $test $rule.metrica $rule.statistica
        $pct = (Format-Number ([double]$rule.aumentoMassimo * 100) "0") + "%"
        if ($null -eq $baseline) {
          $rows.Add((New-Row $scenario.id $test "regressione" $label $value "ref + $pct" "NUOVO" "nessun riferimento: -Accept lo crea dopo una misura buona"))
        } elseif ($baseline -le 0) {
          $rows.Add((New-Row $scenario.id $test "regressione" $label $value "ref + $pct" "N/A" "riferimento non positivo ($(Format-Number $baseline))"))
        } else {
          $cap = Get-RegressionCap $baseline $rule
          $status = if ($value -le $cap) { "OK" } else { "FAIL" }
          $rows.Add((New-Row $scenario.id $test "regressione" $label $value ("<= " + (Format-Number $cap) + " (ref " + (Format-Number $baseline) + " + $pct)") $status ""))
        }
      }
      # La guardia della Fase 4A: "non peggiorare" per cio' che le soglie assolute non coprono o che falliva gia' il 13/10.
      if ($guard) {
        foreach ($rule in @($scenario.guardia)) {
          $value = Get-StatValue $bench $rule.metrica $rule.statistica
          $label = "$($rule.metrica) $($rule.statistica)"
          if ($null -eq $value) { continue }
          $baseline = Get-ReferenceValue $reference $test $rule.metrica $rule.statistica
          $pct = (Format-Number ([double]$rule.aumentoMassimo * 100) "0") + "%"
          if ($null -eq $baseline) {
            $rows.Add((New-Row $scenario.id $test "guardia" $label $value "ref + $pct" "NUOVO" "nessun riferimento per questa prova"))
            continue
          }
          $cap = $baseline * (1.0 + [double]$rule.aumentoMassimo)
          $limitNote = "ref " + (Format-Number $baseline) + " + $pct"
          if ((Has-Property $rule "pavimento") -and ($baseline + [double]$rule.pavimento) -gt $cap) {
            $cap = $baseline + [double]$rule.pavimento
            $limitNote = "ref " + (Format-Number $baseline) + " + " + (Format-Number $rule.pavimento) + " (rumore)"
          }
          if ((Has-Property $rule "mantieniSotto") -and $baseline -le [double]$rule.mantieniSotto -and $cap -gt [double]$rule.mantieniSotto) {
            $cap = [double]$rule.mantieniSotto
            $limitNote = "ref " + (Format-Number $baseline) + ", resta <= " + (Format-Number $rule.mantieniSotto)
          }
          $status = if ($value -le $cap) { "OK" } else { "FAIL" }
          $rows.Add((New-Row $scenario.id $test "guardia" $label $value ("<= " + (Format-Number $cap) + " (" + $limitNote + ")") $status ""))
        }
      }
    }
  }
  return $rows
}

# Il riferimento da accettare: tutte le coppie metrica.statistica che le regole di soglie.json usano, per ogni prova.
function Build-Reference($thr, $benches, [string]$deviceKey, $context, [double]$hz, [string]$date = "", [string]$origin = "", $keepFrom = $null) {
  $lines = New-Object System.Collections.Generic.List[string]
  $tests = New-Object System.Collections.Generic.List[string]
  $measured = @{}
  foreach ($scenario in $thr.scenari) {
    foreach ($bench in @($benches | Where-Object { ([string]$_.name -replace '\[.*$', '') -match $scenario.prove })) {
      $test = [string]$bench.name -replace '\[.*$', ''
      $entries = New-Object System.Collections.Generic.List[string]
      foreach ($rule in (@($scenario.assolute) + @($scenario.regressione) + @($scenario.guardia))) {
        $value = Get-StatValue $bench $rule.metrica $rule.statistica
        if ($null -ne $value) { $entries.Add('"' + $rule.metrica + "." + $rule.statistica + '": ' + (Format-Number $value "0.####")) }
      }
      $unique = @($entries | Select-Object -Unique)
      if ($unique.Count -gt 0) { $tests.Add('    "' + $test + '": { ' + ($unique -join ", ") + ' }'); $measured[$test] = $true }
    }
  }
  # Una misura parziale (solo alcune prove) non cancella le altre: restano quelle del riferimento in uso, cosi' la guardia
  # continua a valere per tutte (15/10: pinch e scorrimento rimisurati da soli dopo la correzione della G2).
  if ($null -ne $keepFrom -and (Has-Property $keepFrom "prove")) {
    foreach ($property in $keepFrom.prove.PSObject.Properties) {
      if ($measured.ContainsKey($property.Name)) { continue }
      $kept = @(foreach ($entry in $property.Value.PSObject.Properties) { '"' + $entry.Name + '": ' + (Format-Number $entry.Value "0.####") })
      if ($kept.Count -gt 0) { $tests.Add('    "' + $property.Name + '": { ' + ($kept -join ", ") + ' }') }
    }
  }
  $model = ""
  $sdk = ""
  if (Has-Property $context "build") {
    if (Has-Property $context.build "model") { $model = [string]$context.build.model }
    if ((Has-Property $context.build "version") -and (Has-Property $context.build.version "sdk")) { $sdk = [string]$context.build.version.sdk }
  }
  $lines.Add("{")
  $lines.Add('  "formato": 1,')
  $lines.Add('  "dispositivo": "' + $deviceKey + '",')
  $lines.Add('  "modello": "' + $model + '",')
  $lines.Add('  "sdk": ' + $(if ($sdk) { $sdk } else { "null" }) + ',')
  $lines.Add('  "frequenzaHz": ' + $(if ($hz -gt 0) { Format-Number $hz "0.#" } else { "null" }) + ',')
  $lines.Add('  "data": "' + $(if ($date) { $date } else { Get-Date -Format "yyyy-MM-dd" }) + '",')
  if ($origin) { $lines.Add('  "origine": "' + $origin.Replace('\', '\\').Replace('"', '\"') + '",') }
  $lines.Add('  "variante": "benchmarkRelease",')
  $lines.Add('  "prove": {')
  $lines.Add(($tests -join ",`n"))
  $lines.Add("  }")
  $lines.Add("}")
  return ($lines -join "`n") + "`n"
}

function Write-Table($rows) {
  $format = "  {0,-5} {1,-24} {2,-11} {3,-26} {4,10}  {5,-34} {6}"
  Write-Host ($format -f "ESITO", "PROVA", "TIPO", "METRICA", "VALORE", "SOGLIA", "NOTA") -ForegroundColor Cyan
  foreach ($row in $rows) {
    $color = switch ($row.Status) { "OK" { "Green" } "FAIL" { "Red" } "MANCA" { "Yellow" } "CAL" { "Yellow" } "NUOVO" { "DarkYellow" } "NOTO" { "DarkCyan" } default { "DarkGray" } }
    $line = $format -f $row.Status, $row.Test, $row.Kind, $row.Metric, (Format-Number $row.Value), $row.Limit, $row.Note
    Write-Host $line -ForegroundColor $color
  }
}

# --- Auto-test --------------------------------------------------------------------------------------------

if ($SelfTest) {
  $samples = Join-Path $PSScriptRoot "samples"
  $thr = Read-Json $Thresholds
  $failures = New-Object System.Collections.Generic.List[string]
  function Assert-That([bool]$condition, [string]$message) { if (-not $condition) { $failures.Add($message) } }

  # Percentili: nearest rank.
  Assert-That ((Get-Percentile @(1, 2, 3, 4, 5, 6, 7, 8, 9, 10) 90) -eq 9) "p90 di 1..10 deve essere 9"
  Assert-That ((Get-Percentile @(5) 99) -eq 5) "p99 di un solo valore"
  Assert-That ($null -eq (Get-Percentile @() 50)) "percentile di una lista vuota"

  $good = Read-Json (Join-Path $samples "s25-buono.json")
  $bad = Read-Json (Join-Path $samples "s25-regressione.json")
  $ref = Read-Json (Join-Path $samples "riferimento-s25.esempio.json")
  Assert-That ((Resolve-DeviceKey $good.context $thr $null) -eq "s25") "il modello SM-S931B deve dare s25"
  Assert-That ((Resolve-DeviceKey ([pscustomobject]@{ build = [pscustomobject]@{ model = "SM-X716B"; fingerprint = "samsung/x" } }) $thr $null) -eq "tab-s9") "SM-X716B deve dare tab-s9"
  Assert-That ((Resolve-DeviceKey ([pscustomobject]@{ build = [pscustomobject]@{ model = "sdk_gphone64_x86_64"; fingerprint = "google/sdk_gphone64" } }) $thr $null) -eq "emulatore-telefono") "sdk_gphone deve dare emulatore-telefono"

  $rowsGood = Invoke-Evaluation $thr @($good.benchmarks) "s25" $ref @{} $false
  Assert-That (@($rowsGood | Where-Object { $_.Status -eq "FAIL" }).Count -eq 0) "il campione buono non deve avere FAIL"
  Assert-That (@($rowsGood | Where-Object { $_.Status -eq "OK" -and $_.Kind -eq "assoluta" }).Count -ge 4) "il campione buono deve passare le soglie assolute"
  Assert-That (@($rowsGood | Where-Object { $_.Scenario -eq "zoom200" -and $_.Status -eq "N/A" }).Count -eq 1) "zoom200 su telefono deve essere N/A"
  $missing = @($rowsGood | Where-Object { $_.Status -eq "MANCA" })
  Assert-That ($missing.Count -ge 1) "le prove dello scroll non incluse nel campione devono risultare MANCA"

  $rowsBad = Invoke-Evaluation $thr @($bad.benchmarks) "s25" $ref @{} $false
  $badFails = @($rowsBad | Where-Object { $_.Status -eq "FAIL" })
  Assert-That (@($badFails | Where-Object { $_.Test -eq "scroll_days_square" -and $_.Kind -eq "assoluta" -and $_.Metric -eq "jankPercent mean" }).Count -eq 1) "scroll_days_square deve fallire la soglia assoluta (jankPercent medio > 1%)"
  Assert-That (@($rowsGood | Where-Object { $_.Test -eq "pinch" -and $_.Metric -eq "jankPercent mean" -and $_.Status -eq "OK" }).Count -eq 1) "pinch del campione buono passa il jank vero (<= 1%)"
  Assert-That (@($badFails | Where-Object { $_.Test -eq "viewerOpen" -and $_.Metric -eq "viewerOpenMaxFrameMs max" }).Count -eq 1) "viewerOpen del campione cattivo deve fallire il fotogramma piu' lungo dell'apertura (> 16,7 ms)"
  Assert-That (@($rowsGood | Where-Object { $_.Test -eq "viewerOpen" -and $_.Metric -eq "viewerOpenMaxFrameMs max" -and $_.Status -eq "OK" }).Count -eq 1) "viewerOpen del campione buono passa il fotogramma piu' lungo"
  Assert-That (@($badFails | Where-Object { $_.Test -eq "scroll_days_square" -and $_.Kind -eq "regressione" }).Count -eq 1) "scroll_days_square deve fallire la regressione (+10%)"
  Assert-That (@($badFails | Where-Object { $_.Test -eq "coldStart" -and $_.Kind -eq "assoluta" }).Count -eq 1) "coldStart deve fallire TTFD p90 < 1000"
  Assert-That (@($badFails | Where-Object { $_.Test -eq "viewerOpen" -and $_.Kind -eq "regressione" }).Count -eq 1) "viewerOpen deve fallire la regressione (+15%)"
  Assert-That (@($rowsBad | Where-Object { $_.Test -eq "pinch" -and $_.Status -eq "FAIL" }).Count -eq 0) "pinch del campione cattivo e' dentro le soglie"

  # Senza Libreria visibile l'avvio non e' valido e non si confronta con 1000 ms.
  $rowsNoLib = Invoke-Evaluation $thr @($bad.benchmarks | Where-Object { $_.name -eq "coldStartSenzaLibreria" } | ForEach-Object { $c = $_.PSObject.Copy(); $c.name = "coldStart"; $c }) "s25" $ref @{} $false
  Assert-That (@($rowsNoLib | Where-Object { $_.Kind -eq "validita" -and $_.Status -eq "FAIL" }).Count -eq 1) "coldStart senza Libreria visibile deve essere NON VALIDA"
  Assert-That (@($rowsNoLib | Where-Object { $_.Kind -eq "assoluta" }).Count -eq 0) "coldStart non valida non deve confrontare TTFD con la soglia"

  # Smoke: nessuna soglia.
  $rowsSmoke = Invoke-Evaluation $thr @($bad.benchmarks) "emulatore-telefono" $null @{} $true
  Assert-That (@($rowsSmoke | Where-Object { $_.Status -eq "FAIL" }).Count -eq 0) "in smoke non ci sono FAIL"

  # --- La guardia della Fase 4A (par. 5.6) su prove costruite a mano -----------------------------------------------------------
  function New-Bench([string]$name, $metrics) {
    $m = [ordered]@{}
    foreach ($key in $metrics.Keys) { $m[$key] = [pscustomobject]@{ minimum = $metrics[$key]; maximum = $metrics[$key]; median = $metrics[$key]; runs = @($metrics[$key]) } }
    return [pscustomobject]@{ name = $name; repeatIterations = 1; metrics = [pscustomobject]$m }
  }
  $fase3 = ('{ "formato": 1, "dispositivo": "s25", "prove": {' +
    ' "scroll_days_square": { "jankPercent.mean": 0.18, "frameDurationCpuMs.P95": 9.5 },' +
    ' "scroll_days_justified": { "jankPercent.mean": 3.56 },' +
    ' "pinch": { "jankPercent.mean": 0.54 },' +
    ' "coldStart": { "timeToFullDisplayMs.p90": 681 },' +
    ' "viewerOpen": { "viewerOpenMs.p90": 21, "viewerOpenMaxFrameMs.max": 97 } } }') | ConvertFrom-Json
  $mix = 0.6
  # Quadrata a giorni: 0,18% il 13/10. 0,30 sta nel pavimento (0,33), 0,50 no.
  $g1 = Invoke-Evaluation $thr @(New-Bench "scroll_days_square" @{ jankPercent = 0.30; libraryNasShare = $mix }) "s25" $fase3 @{} $false $true
  Assert-That (@($g1 | Where-Object { $_.Kind -eq "guardia" -and $_.Status -eq "OK" }).Count -eq 1) "guardia: 0,30% su ref 0,18% e' dentro il pavimento di rumore"
  $g2 = Invoke-Evaluation $thr @(New-Bench "scroll_days_square" @{ jankPercent = 0.50; libraryNasShare = $mix }) "s25" $fase3 @{} $false $true
  Assert-That (@($g2 | Where-Object { $_.Kind -eq "guardia" -and $_.Status -eq "FAIL" }).Count -eq 1) "guardia: 0,50% su ref 0,18% e' un peggioramento"
  # Giustificata a giorni: 3,56% il 13/10, gia' fuori soglia. 3,70 e' NOTO (non FAIL) e passa la guardia; 4,30 la fallisce.
  $g3 = Invoke-Evaluation $thr @(New-Bench "scroll_days_justified" @{ jankPercent = 3.70; libraryNasShare = $mix }) "s25" $fase3 @{} $false $true
  Assert-That (@($g3 | Where-Object { $_.Kind -eq "assoluta" -and $_.Status -eq "NOTO" }).Count -eq 1) "guardia: la soglia assoluta che il 13/10 violava gia' e' NOTO, non FAIL"
  Assert-That (@($g3 | Where-Object { $_.Kind -eq "guardia" -and $_.Status -eq "OK" }).Count -eq 1) "guardia: 3,70% su ref 3,56% passa (+10% = 3,92)"
  $g4 = Invoke-Evaluation $thr @(New-Bench "scroll_days_justified" @{ jankPercent = 4.30; libraryNasShare = $mix }) "s25" $fase3 @{} $false $true
  Assert-That (@($g4 | Where-Object { $_.Kind -eq "guardia" -and $_.Status -eq "FAIL" }).Count -eq 1) "guardia: 4,30% su ref 3,56% e' un peggioramento"
  $g4b = Invoke-Evaluation $thr @(New-Bench "scroll_days_justified" @{ jankPercent = 4.30; libraryNasShare = $mix }) "s25" $fase3 @{} $false $false
  Assert-That (@($g4b | Where-Object { $_.Kind -eq "assoluta" -and $_.Status -eq "FAIL" }).Count -eq 1) "senza guardia la stessa prova fallisce la soglia assoluta"
  # Una prova che era sotto l'1% deve restarci anche se il pavimento lo permetterebbe: pinch 0,54 + 0,15 = 0,69, 0,95 no.
  $g5 = Invoke-Evaluation $thr @(New-Bench "pinch" @{ jankPercent = 0.95; libraryNasShare = $mix }) "s25" $fase3 @{} $false $true
  Assert-That (@($g5 | Where-Object { $_.Kind -eq "guardia" -and $_.Status -eq "FAIL" }).Count -eq 1) "guardia: pinch 0,95% su ref 0,54% peggiora"
  $fase3b = ('{ "formato": 1, "dispositivo": "s25", "prove": { "pinch": { "jankPercent.mean": 0.95 } } }') | ConvertFrom-Json
  $g5b = Invoke-Evaluation $thr @(New-Bench "pinch" @{ jankPercent = 1.05; libraryNasShare = $mix }) "s25" $fase3b @{} $false $true
  Assert-That (@($g5b | Where-Object { $_.Kind -eq "guardia" -and $_.Status -eq "FAIL" }).Count -eq 1) "guardia: dove era sotto l'1% deve restarci (cap 1,0)"
  # Avvio: 681 ms + 10% = 749,1 -> 750.
  $g6 = Invoke-Evaluation $thr @(New-Bench "coldStart" @{ timeToFullDisplayMs = 750; libraryShown = 1; libraryNasShare = $mix }) "s25" $fase3 @{} $false $true
  Assert-That (@($g6 | Where-Object { $_.Kind -eq "regressione" -and $_.Status -eq "OK" }).Count -eq 1) "regressione: TTFD 750 su ref 681 e' dentro (749,1 arrotondato a 750)"
  $g7 = Invoke-Evaluation $thr @(New-Bench "coldStart" @{ timeToFullDisplayMs = 751; libraryShown = 1; libraryNasShare = $mix }) "s25" $fase3 @{} $false $true
  Assert-That (@($g7 | Where-Object { $_.Kind -eq "regressione" -and $_.Status -eq "FAIL" }).Count -eq 1) "regressione: TTFD 751 su ref 681 e' fuori"
  # Viewer: 21 ms + 15% = 24,15 -> 25; fotogramma piu' lungo 97 ms (13/10, gia' fuori soglia): 100 passa, 110 no.
  $g8 = Invoke-Evaluation $thr @(New-Bench "viewerOpen" @{ viewerOpenMs = 25; viewerOpenMaxFrameMs = 100; libraryNasShare = $mix }) "s25" $fase3 @{} $false $true
  Assert-That (@($g8 | Where-Object { $_.Status -eq "FAIL" }).Count -eq 0) "guardia: viewerOpen 25 ms e fotogramma 100 ms stanno nella guardia del 13/10"
  Assert-That (@($g8 | Where-Object { $_.Kind -eq "assoluta" -and $_.Status -eq "NOTO" }).Count -eq 1) "guardia: il fotogramma piu' lungo gia' fuori soglia il 13/10 e' NOTO"
  $g9 = Invoke-Evaluation $thr @(New-Bench "viewerOpen" @{ viewerOpenMs = 25; viewerOpenMaxFrameMs = 110; libraryNasShare = $mix }) "s25" $fase3 @{} $false $true
  Assert-That (@($g9 | Where-Object { $_.Kind -eq "guardia" -and $_.Status -eq "FAIL" }).Count -eq 1) "guardia: fotogramma piu' lungo 110 ms su ref 97 peggiora"
  # La libreria mista: un dataset della Fase 3 (nessuna riga solo-NAS) non vale come misura della guardia.
  $g10 = Invoke-Evaluation $thr @(New-Bench "scroll_days_square" @{ jankPercent = 0.1; libraryNasShare = 0.0 }) "s25" $fase3 @{} $false $true
  Assert-That (@($g10 | Where-Object { $_.Kind -eq "validita" -and $_.Status -eq "FAIL" }).Count -eq 1) "libreria non mista: la prova e' NON VALIDA"
  Assert-That (@($g10 | Where-Object { $_.Kind -eq "assoluta" -or $_.Kind -eq "guardia" }).Count -eq 0) "libreria non mista: nessun confronto con le soglie"
  # Le prove nuove senza riferimento risultano NUOVO, non FAIL; le soglie assolute valgono.
  $g11 = Invoke-Evaluation $thr @(New-Bench "viewerOpenNas" @{ viewerOpenMs = 80; libraryNasShare = $mix }) "s25" $fase3 @{} $false $true
  Assert-That (@($g11 | Where-Object { $_.Status -eq "FAIL" }).Count -eq 0) "viewerOpenNas 80 ms sta sotto i 100 ms"
  Assert-That (@($g11 | Where-Object { $_.Kind -eq "regressione" -and $_.Status -eq "NUOVO" }).Count -eq 1) "viewerOpenNas senza riferimento: NUOVO"
  $g12 = Invoke-Evaluation $thr @(New-Bench "viewerOpenNas" @{ viewerOpenMs = 120; libraryNasShare = $mix }) "s25" $fase3 @{} $false $true
  Assert-That (@($g12 | Where-Object { $_.Kind -eq "assoluta" -and $_.Status -eq "FAIL" }).Count -eq 1) "viewerOpenNas 120 ms fallisce i 100 ms"
  # Smoke: la guardia non si applica nemmeno se la si chiede.
  $g13 = Invoke-Evaluation $thr @(New-Bench "scroll_days_justified" @{ jankPercent = 9.0; libraryNasShare = $mix }) "emulatore-telefono" $null @{} $true $true
  Assert-That (@($g13 | Where-Object { $_.Status -eq "FAIL" }).Count -eq 0) "smoke: nessuna soglia, nemmeno la guardia"

  # Il riferimento accettato si rilegge: stesso dispositivo, stesse prove.
  $text = Build-Reference $thr @($good.benchmarks) "s25" $good.context 120
  $parsed = $text | ConvertFrom-Json
  Assert-That ($parsed.dispositivo -eq "s25") "il riferimento scritto si rilegge (dispositivo)"
  Assert-That ((Has-Property $parsed.prove "scroll_days_square") -and (Has-Property $parsed.prove.scroll_days_square "frameDurationCpuMs.P95")) "il riferimento contiene scroll_days_square / frameDurationCpuMs.P95"
  Assert-That (Has-Property $parsed.prove.scroll_days_square "jankPercent.mean") "il riferimento contiene scroll_days_square / jankPercent.mean"

  # Una misura parziale tiene le prove non misurate dal riferimento in uso; quelle misurate prendono i valori nuovi.
  $partial = Build-Reference $thr @(New-Bench "pinch" @{ jankPercent = 0.61; libraryNasShare = $mix }) "s25" $good.context 120 "2026-10-15" "prova" $fase3
  $parsedPartial = $partial | ConvertFrom-Json
  Assert-That ([double]$parsedPartial.prove.pinch."jankPercent.mean" -eq 0.61) "riferimento parziale: la prova misurata prende il valore nuovo"
  Assert-That ([double]$parsedPartial.prove.coldStart."timeToFullDisplayMs.p90" -eq 681) "riferimento parziale: le prove non misurate restano quelle del riferimento in uso"
  Assert-That ([double]$parsedPartial.prove.viewerOpen."viewerOpenMaxFrameMs.max" -eq 97) "riferimento parziale: tutte le metriche della prova tenuta"
  Assert-That (@($parsedPartial.prove.PSObject.Properties).Count -eq 5) "riferimento parziale: nessuna prova persa o doppia"
  Assert-That ($parsedPartial.origine -eq "prova") "riferimento parziale: l'origine si scrive"

  if ($failures.Count -gt 0) {
    foreach ($f in $failures) { Write-Host ("[FAIL] " + $f) -ForegroundColor Red }
    exit 1
  }
  Write-Host "compare.ps1: auto-test OK" -ForegroundColor Green
  exit 0
}

# --- Estrazione del riferimento della Fase 3 --------------------------------------------------------------
# `-ExtractReference <cartella>[,<cartella>]`: scrive docs\bench\riferimento-<dispositivo>-fase3.json dalle corse indicate
# (la prima cartella che ha una prova vince). Sono le misure del 13/10 con cui la guardia della Fase 4A confronta (par. 5.6).
if ($ExtractReference) {
  if (-not (Test-Path -LiteralPath $Thresholds)) { Write-Host "soglie non trovate: $Thresholds" -ForegroundColor Red; exit 2 }
  $thrX = Read-Json $Thresholds
  $seen = @{}
  $all = New-Object System.Collections.Generic.List[object]
  $ctx = $null
  foreach ($dir in $ExtractReference) {
    if (-not (Test-Path -LiteralPath $dir)) { Write-Host "cartella non trovata: $dir" -ForegroundColor Red; exit 2 }
    foreach ($f in @(Get-ChildItem -LiteralPath $dir -Recurse -Filter "*benchmarkData.json" | Sort-Object LastWriteTime -Descending)) {
      try { $j = Read-Json $f.FullName } catch { Write-Host ("file illeggibile: " + $f.FullName) -ForegroundColor Red; exit 2 }
      if (-not $ctx -and (Has-Property $j "context")) { $ctx = $j.context }
      foreach ($b in @($j.benchmarks)) {
        $name = [string]$b.name -replace '\[.*$', ''
        if (-not $seen.ContainsKey($name)) { $seen[$name] = $dir; $all.Add($b) }
      }
    }
  }
  if ($all.Count -eq 0) { Write-Host "nessuna prova nelle cartelle indicate" -ForegroundColor Red; exit 2 }
  try { $keyX = Resolve-DeviceKey $ctx $thrX $Device } catch { Write-Host $_.Exception.Message -ForegroundColor Red; exit 2 }
  if (-not $keyX) { Write-Host "dispositivo non riconosciuto: passa -Device" -ForegroundColor Red; exit 2 }
  $textX = Build-Reference $thrX $all.ToArray() $keyX $ctx $RefreshHz $ReferenceDate $Origin
  if (-not (Test-Path -LiteralPath $ReferenceDir)) { New-Item -ItemType Directory -Path $ReferenceDir | Out-Null }
  $pathX = Join-Path $ReferenceDir ("riferimento-" + $keyX + "-fase3.json")
  [System.IO.File]::WriteAllText($pathX, $textX, (New-Object System.Text.UTF8Encoding($false)))
  Write-Host ("riferimento scritto in {0}: {1} prove" -f $pathX, $all.Count) -ForegroundColor Green
  foreach ($name in ($seen.Keys | Sort-Object)) { Write-Host ("  {0,-24} da {1}" -f $name, (Split-Path -Leaf $seen[$name])) -ForegroundColor DarkGray }
  exit 0
}

# --- Esecuzione -------------------------------------------------------------------------------------------

if (-not (Test-Path -LiteralPath $Thresholds)) { Write-Host "soglie non trovate: $Thresholds" -ForegroundColor Red; exit 2 }
if (-not (Test-Path -LiteralPath $Results)) { Write-Host "nessun risultato in: $Results (il benchmark e' girato?)" -ForegroundColor Red; exit 2 }

$thr = Read-Json $Thresholds
$files = if ((Get-Item -LiteralPath $Results).PSIsContainer) {
  @(Get-ChildItem -LiteralPath $Results -Recurse -Filter "*benchmarkData.json" | Sort-Object LastWriteTime -Descending)
} else {
  @(Get-Item -LiteralPath $Results)
}
if ($files.Count -eq 0) { Write-Host "nessun file *benchmarkData.json in: $Results" -ForegroundColor Red; exit 2 }

# Un solo dispositivo per volta (check.ps1 -Bench): se ce ne sono piu', si prende la cartella piu' recente.
$newestDir = $files[0].DirectoryName
$files = @($files | Where-Object { $_.DirectoryName -eq $newestDir })

$benches = @()
$context = $null
foreach ($file in $files) {
  try { $json = Read-Json $file.FullName } catch { Write-Host ("file illeggibile: " + $file.FullName) -ForegroundColor Red; exit 2 }
  if (-not $context -and (Has-Property $json "context")) { $context = $json.context }
  if (Has-Property $json "benchmarks") { foreach ($b in $json.benchmarks) { $benches += $b } }
}
if ($benches.Count -eq 0) { Write-Host "nessuna prova nei file letti" -ForegroundColor Red; exit 2 }

try { $deviceKey = Resolve-DeviceKey $context $thr $Device } catch { Write-Host $_.Exception.Message -ForegroundColor Red; exit 2 }
if (-not $deviceKey) {
  $m = if ((Has-Property $context "build") -and (Has-Property $context.build "model")) { $context.build.model } else { "?" }
  Write-Host "dispositivo non riconosciuto (modello '$m'): passa -Device (s25, tab-s9, emulatore-telefono, emulatore-tablet) o aggiungilo a benchmark\soglie.json" -ForegroundColor Red
  exit 2
}
$deviceInfo = $thr.dispositivi.$deviceKey
$isEmulator = (Has-Property $deviceInfo "emulatore") -and $deviceInfo.emulatore
# Su un emulatore e' sempre smoke: nessuna soglia, nessun riferimento.
$smokeMode = [bool]$Smoke -or [bool]$isEmulator

# Il riferimento: quello che l'utente ha accettato (riferimento-<dispositivo>.json); se non c'e', le misure del 13/10 della
# Fase 3 (riferimento-<dispositivo>-fase3.json), con cui la guardia della Fase 4A confronta (par. 5.6: "la guardia confronta
# sempre con l'ultimo riferimento accettato dall'utente").
$referencePath = Join-Path $ReferenceDir ("riferimento-" + $deviceKey + ".json")
$referenceNote = ""
if (-not (Test-Path -LiteralPath $referencePath) -and -not $NoGuard) {
  $phase3Path = Join-Path $ReferenceDir ("riferimento-" + $deviceKey + "-fase3.json")
  if (Test-Path -LiteralPath $phase3Path) { $referencePath = $phase3Path; $referenceNote = " (le misure del 13/10, Fase 3)" }
}
$reference = $null
if (Test-Path -LiteralPath $referencePath) { $reference = Read-Json $referencePath }
$guardOn = (-not $NoGuard) -and (-not $smokeMode) -and ($null -ne $reference)
$skipped = Read-SkippedTests $Report

$model = if ((Has-Property $context "build") -and (Has-Property $context.build "model")) { [string]$context.build.model } else { "?" }
$sdk = if ((Has-Property $context "build") -and (Has-Property $context.build "version") -and (Has-Property $context.build.version "sdk")) { [string]$context.build.version.sdk } else { "?" }
Write-Host ""
Write-Host ("Benchmark - {0} ({1}, modello {2}, Android SDK {3})" -f $deviceInfo.nome, $deviceKey, $model, $sdk) -ForegroundColor Cyan
if ($RefreshHz -gt 0) {
  $note = ""
  if ((Has-Property $deviceInfo "frequenzaMinimaHz") -and $RefreshHz -lt [double]$deviceInfo.frequenzaMinimaHz) {
    $note = "  <-- sotto i $($deviceInfo.frequenzaMinimaHz) Hz del dispositivo: impostare 'Fluidita' movimento: Adattiva' e rifare la misura"
  }
  Write-Host ("  frequenza dello schermo letta: {0} Hz{1}" -f (Format-Number $RefreshHz "0.#"), $note) -ForegroundColor $(if ($note) { "Yellow" } else { "DarkGray" })
}
if ($smokeMode) {
  Write-Host "  SMOKE: i numeri dell'emulatore non valgono, nessuna soglia si applica. Si verifica solo che gli scenari girino." -ForegroundColor Yellow
}
Write-Host ("  riferimento: " + $(if ($reference) { $referencePath + $referenceNote } else { "nessuno (docs\bench\riferimento-$deviceKey.json): le regressioni risultano NUOVO" })) -ForegroundColor DarkGray
if (-not $smokeMode) {
  Write-Host ("  guardia della Fase 4A (par. 5.6): " + $(if ($guardOn) { "attiva (non peggiorare rispetto al riferimento; le prove devono girare sulla libreria mista)" } elseif ($NoGuard) { "spenta (-NoGuard)" } else { "spenta: nessun riferimento per questo dispositivo" })) -ForegroundColor DarkGray
}
Write-Host ""

$rows = Invoke-Evaluation $thr @($benches) $deviceKey $reference $skipped $smokeMode $guardOn
Write-Table $rows

$fail = @($rows | Where-Object { $_.Status -eq "FAIL" })
$manca = @($rows | Where-Object { $_.Status -eq "MANCA" })
$cal = @($rows | Where-Object { $_.Status -eq "CAL" })
$ok = @($rows | Where-Object { $_.Status -eq "OK" })
$noto = @($rows | Where-Object { $_.Status -eq "NOTO" })
Write-Host ""
Write-Host ("  OK {0}   FAIL {1}   MANCA {2}   da calibrare {3}   gia' fuori nel riferimento (NOTO) {4}" -f $ok.Count, $fail.Count, $manca.Count, $cal.Count, $noto.Count)

if ($SummaryJson) {
  $dir = Split-Path -Parent $SummaryJson
  if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
  $items = foreach ($row in $rows) {
    '  { "esito": "' + $row.Status + '", "scenario": "' + $row.Scenario + '", "prova": "' + $row.Test + '", "tipo": "' + $row.Kind + '", "metrica": "' + $row.Metric + '", "valore": ' + $(if ($null -eq $row.Value) { "null" } else { Format-Number $row.Value "0.####" }) + ' }'
  }
  [System.IO.File]::WriteAllText($SummaryJson, "[`n" + ($items -join ",`n") + "`n]`n", (New-Object System.Text.UTF8Encoding($false)))
}

# --- Calibrazione e riferimento ---------------------------------------------------------------------------
if ($Calibrate) {
  $zoom = @($thr.scenari | Where-Object { $_.id -eq "zoom200" })
  $rule = $null
  if ($zoom.Count -gt 0) { $rule = @($zoom[0].assolute | Where-Object { $_.metrica -eq "pssPeakKb" })[0] }
  $bench = @($benches | Where-Object { ([string]$_.name -replace '\[.*$', '') -eq "zoom200Mp" })[0]
  if (-not $rule -or -not $bench) {
    Write-Host "  -Calibrate: nessuna misura di zoom200Mp da cui calibrare" -ForegroundColor Yellow
  } elseif ($null -ne $rule.massimo) {
    Write-Host ("  -Calibrate: il tetto c'e' gia' ({0} KB): non lo cambio. Per ricalibrare rimetti `"massimo`": null in soglie.json." -f $rule.massimo) -ForegroundColor Yellow
  } elseif ($smokeMode) {
    Write-Host "  -Calibrate: non da un emulatore." -ForegroundColor Yellow
  } else {
    $peak = Get-StatValue $bench "pssPeakKb" "max"
    $margin = [double]$rule.calibrazione.margine
    $cap = [long]([math]::Ceiling($peak * (1.0 + $margin) / 1024.0) * 1024)
    $text = [System.IO.File]::ReadAllText($Thresholds, (New-Object System.Text.UTF8Encoding($false)))
    $pattern = '("metrica":\s*"pssPeakKb",\s*"statistica":\s*"max",\s*"massimo":\s*)null'
    if ($text -notmatch $pattern) {
      Write-Host "  -Calibrate: non trovo `"massimo`": null della PSS in soglie.json (formato cambiato?)" -ForegroundColor Red
      exit 2
    }
    $text = [regex]::Replace($text, $pattern, ('${1}' + $cap.ToString($inv)))
    [System.IO.File]::WriteAllText($Thresholds, $text, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host ("  -Calibrate: tetto di PSS scritto in soglie.json: {0} KB (misura {1} KB + {2}%, a multipli di 1 MB)" -f $cap, (Format-Number $peak "0"), (Format-Number ($margin * 100) "0")) -ForegroundColor Green
  }
}

if ($Accept) {
  if ($smokeMode) {
    Write-Host "  -Accept: un riferimento non si accetta da un emulatore." -ForegroundColor Red
    exit 2
  }
  if (($fail.Count -gt 0 -or $noto.Count -gt 0) -and -not $Force) {
    Write-Host "  -Accept: ci sono soglie fallite (o gia' fuori nel riferimento); un riferimento si accetta da una misura buona (o con -Force, e va scritto perche')." -ForegroundColor Red
    exit 1
  }
  if ($manca.Count -gt 0) {
    if ($reference) {
      Write-Host ("  -Accept: misura PARZIALE: le {0} verifiche senza risultati tengono i valori del riferimento in uso ({1})." -f $manca.Count, (Split-Path -Leaf $referencePath)) -ForegroundColor Yellow
    } else {
      Write-Host ("  -Accept: ATTENZIONE, il riferimento e' PARZIALE: {0} prove attese non hanno risultati e non ci saranno nel riferimento." -f $manca.Count) -ForegroundColor Yellow
    }
  }
  $text = Build-Reference $thr @($benches) $deviceKey $context $RefreshHz $ReferenceDate $Origin $reference
  if (-not (Test-Path -LiteralPath $ReferenceDir)) { New-Item -ItemType Directory -Path $ReferenceDir | Out-Null }
  # Si scrive sempre il riferimento ACCETTATO (riferimento-<dispositivo>.json), mai quello della Fase 3: prima, senza un
  # riferimento accettato, -Accept riscriveva riferimento-<dispositivo>-fase3.json (le misure del 13/10).
  $acceptedPath = Join-Path $ReferenceDir ("riferimento-" + $deviceKey + ".json")
  [System.IO.File]::WriteAllText($acceptedPath, $text, (New-Object System.Text.UTF8Encoding($false)))
  Write-Host ("  -Accept: riferimento scritto in " + $acceptedPath) -ForegroundColor Green
}

Write-Host ""
if ($smokeMode) {
  if ($ok.Count -gt 0 -or $manca.Count -gt 0) {
    $ran = @($rows | Where-Object { $_.Kind -eq "smoke" }).Count
    Write-Host ("SMOKE: {0} prove girate, {1} saltate o non girate." -f $ran, $manca.Count) -ForegroundColor $(if ($ran -gt 0) { "Green" } else { "Yellow" })
  }
  exit 0
}
if ($fail.Count -gt 0) {
  Write-Host ("FAIL: {0} verifiche fuori soglia." -f $fail.Count) -ForegroundColor Red
  exit 1
}
if ($RequireAll -and ($manca.Count -gt 0 -or $cal.Count -gt 0)) {
  Write-Host ("FAIL: {0} prove senza risultato e {1} tetti da calibrare (-RequireAll)." -f $manca.Count, $cal.Count) -ForegroundColor Red
  exit 1
}
if ($manca.Count -gt 0) {
  Write-Host ("OK con riserve: {0} prove senza risultato (la chiusura della fase le vuole tutte: -RequireAll)." -f $manca.Count) -ForegroundColor Yellow
} else {
  Write-Host "OK: dentro le soglie." -ForegroundColor Green
}
exit 0
