# Funzioni comuni degli strumenti di tools\security (Fase 10 P11): Gradle dietro il semaforo, lettura dei risultati dei test
# (XML di JUnit) e resoconto di soli numeri. Nessun nome di file dell'utente, nessuna credenziale: solo conteggi e le righe
# "MCG-SEC" che i test stampano (etichette e numeri).

function Invoke-SecurityGradle {
  param(
    [string]$Root,
    [string]$Slot,
    [string[]]$Pairs,
    [string[]]$Extra = @(),
    [switch]$DryRun
  )
  # Un task per modulo, con tutti i suoi filtri e --rerun (le variabili d'ambiente non sono ingressi del task).
  $byModule = [ordered]@{}
  foreach ($pair in $Pairs) {
    $m, $t = $pair -split '\|', 2
    if (-not $byModule.Contains($m)) { $byModule[$m] = @() }
    $byModule[$m] += $t
  }
  $gradleArgs = @("--no-daemon", "--max-workers=2") + $Extra
  foreach ($m in $byModule.Keys) {
    $gradleArgs += "${m}:testDebugUnitTest"
    foreach ($t in $byModule[$m]) { $gradleArgs += @("--tests", $t) }
    $gradleArgs += "--rerun"
  }
  Write-Host ("gradlew " + ($gradleArgs -join " "))
  if ($DryRun) { return 0 }
  $useSlot = Test-Path $Slot
  if ($useSlot) { powershell -NoProfile -File $Slot -Acquire -Name security-run-all | Out-Host }
  $code = 1
  try {
    Push-Location $Root
    & (Join-Path $Root "gradlew.bat") @gradleArgs | Out-Host
    $code = $LASTEXITCODE
  } finally {
    Pop-Location
    if ($useSlot) { powershell -NoProfile -File $Slot -Release -Name security-run-all | Out-Host }
  }
  return $code
}

function ConvertTo-ClassRegex([string]$Pattern) {
  # Il filtro di Gradle puo' finire con il nome di un metodo (minuscolo): per l'XML conta la classe.
  $cls = $Pattern -replace '\.[a-z][A-Za-z0-9_]*\*?$', ''
  return '^' + ([regex]::Escape($cls) -replace '\\\*', '.*') + '$'
}

function Get-SecurityResults {
  param([string]$Root, [string[]]$Pairs, [datetime]$Since = [datetime]::MinValue)
  $out = @()
  $seen = @{}
  foreach ($pair in $Pairs) {
    $m, $t = $pair -split '\|', 2
    $dir = Join-Path $Root (($m -replace '^:', '') + "\build\test-results\testDebugUnitTest")
    if (-not (Test-Path $dir)) { continue }
    $regex = ConvertTo-ClassRegex $t
    foreach ($f in Get-ChildItem $dir -Filter "TEST-*.xml") {
      if ($f.LastWriteTime -lt $Since -or $seen.ContainsKey($f.FullName)) { continue }
      [xml]$x = Get-Content -LiteralPath $f.FullName -Raw -Encoding UTF8
      $suite = $x.testsuite
      if ($suite.name -notmatch $regex) { continue }
      $seen[$f.FullName] = $true
      $lines = @()
      $sysout = [string]$suite.'system-out'.'#cdata-section'
      if (-not $sysout) { $sysout = [string]$suite.'system-out' }
      foreach ($l in ($sysout -split "`r?`n")) { if ($l -match '^MCG-SEC ') { $lines += $l.Trim() } }
      $out += [pscustomobject]@{
        Module   = $m
        Class    = ($suite.name -split '\.')[-1]
        Tests    = [int]$suite.tests
        Failures = [int]$suite.failures + [int]$suite.errors
        Skipped  = [int]$suite.skipped
        Seconds  = [double]$suite.time
        Lines    = $lines
      }
    }
  }
  return $out
}

function Write-SecurityReport {
  param([string]$Path, [string]$Level, [object[]]$Results, [int]$GradleExit)
  $tot = ($Results | Measure-Object -Property Tests -Sum).Sum
  $fail = ($Results | Measure-Object -Property Failures -Sum).Sum
  $skip = ($Results | Measure-Object -Property Skipped -Sum).Sum
  $text = @()
  $text += "# Revisione finale di sicurezza (Fase 10 P11), livello $Level, $(Get-Date -Format 'yyyy-MM-dd HH:mm')"
  $text += "livello=$Level classi=$($Results.Count) prove=$tot fallite=$fail saltate=$skip gradle_uscita=$GradleExit"
  foreach ($r in $Results) {
    $text += "classe=$($r.Class) modulo=$($r.Module) prove=$($r.Tests) fallite=$($r.Failures) saltate=$($r.Skipped) secondi=$([math]::Round($r.Seconds, 1))"
    foreach ($l in $r.Lines) { $text += "  $l" }
  }
  Set-Content -LiteralPath $Path -Value $text -Encoding UTF8
}
