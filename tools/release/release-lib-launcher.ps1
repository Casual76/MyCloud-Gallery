# Libreria condivisa (I5, 12/11/2026, icona dell'app a scelta): gli ingressi del launcher nell'APK. Sono i tre activity-alias della MainActivity,
# uno per icona (automatica, chiara, scura), e si ammettono come componenti esportati PER NOME E PER FORMA, con la stessa regola di
# gradle\verify-release-manifest.gradle (launcherAliasPolicy, verifyLauncherAliases):
#   - ogni alias dell'elenco c'e', e' un activity-alias, punta alla MainActivity, e' esportato;
#   - ha un solo intent-filter, con la sola azione MAIN e la sola categoria LAUNCHER, e nient'altro (niente data);
#   - ha lo stesso android:permission della MainActivity (nessun permesso in meno);
#   - solo l'alias di serie (enabledByDefault) e' abilitato, gli altri hanno enabled=false;
#   - nessun altro componente (la MainActivity compresa) dichiara la categoria LAUNCHER.
# La usano tools\check.ps1 (passo "APK di release") e tools\release\release.ps1 (Test-ReleaseApk); le prove sintetiche sono in
# tools\release\test-release.ps1 e test-release-parts.ps1. Solo ASCII, PowerShell 5.1. Usa Invoke-Native di chi la carica.

$script:LauncherCategory = "android.intent.category.LAUNCHER"
$script:LauncherAction = "android.intent.action.MAIN"

# La politica dal testo di gradle\verify-release-manifest.gradle: nomi degli alias, attivita' bersaglio, alias abilitato di serie.
function Get-LauncherAliasPolicy([string]$GradleText) {
  $block = [regex]::Match($GradleText, '(?s)launcherAliasPolicy\s*:\s*\[(.*?)\n\s*\],')
  if (-not $block.Success) { throw "non trovo launcherAliasPolicy in gradle\verify-release-manifest.gradle" }
  $map = @{}
  foreach ($line in ($block.Groups[1].Value -split '\r?\n')) {
    $code = ($line -replace '//.*$', '').Trim()
    $pair = [regex]::Match($code, "'([^']+)'\s*:\s*'([^']*)'")
    if ($pair.Success) { $map[$pair.Groups[1].Value] = $pair.Groups[2].Value }
  }
  foreach ($key in @("aliases", "target", "enabledByDefault")) {
    if (-not $map.ContainsKey($key) -or -not $map[$key]) { throw "launcherAliasPolicy senza '$key' in gradle\verify-release-manifest.gradle" }
  }
  return [pscustomobject]@{ Aliases = @($map["aliases"] -split '\|'); Target = $map["target"]; Default = $map["enabledByDefault"] }
}

function ConvertFrom-AaptString([string]$Value) {
  if ($Value -match '^"([^"]*)"') { return $Matches[1] }
  return $null
}

function Test-AaptTrue([string]$Value) {
  return ($Value -match '^true' -or $Value -match '0xffffffff')
}

# I componenti dalle righe di `aapt2 dump xmltree --file AndroidManifest.xml`: tipo, nome, esportato ($null se l'attributo non c'e'), abilitato
# (di serie si'), permesso, attivita' bersaglio e i filtri (azioni, categorie, altri figli). Ogni attributo va all'elemento piu' interno aperto
# (per rientro), cosi' il nome di un'azione o di un meta-data non diventa mai il nome del componente.
function Read-LauncherFacts([string[]]$Lines) {
  $stack = New-Object System.Collections.ArrayList
  $nodes = New-Object System.Collections.ArrayList
  foreach ($line in $Lines) {
    if ($line -match '^(\s*)E: ([\w\-]+) ') {
      $indent = $Matches[1].Length
      $element = $Matches[2]
      while ($stack.Count -gt 0 -and $stack[$stack.Count - 1].Indent -ge $indent) { $stack.RemoveAt($stack.Count - 1) }
      $parent = if ($stack.Count -gt 0) { $stack[$stack.Count - 1] } else { $null }
      $node = [pscustomobject]@{ Element = $element; Indent = $indent; Attrs = @{}; Children = (New-Object System.Collections.ArrayList) }
      if ($parent) { [void]$parent.Children.Add($node) }
      [void]$stack.Add($node)
      if (@("activity", "activity-alias", "service", "receiver", "provider") -contains $element) { [void]$nodes.Add($node) }
      continue
    }
    if ($stack.Count -gt 0 -and $line -match '^\s*A: .*android:([A-Za-z]+)\(0x[0-9a-fA-F]+\)=(.*)$') {
      $stack[$stack.Count - 1].Attrs[$Matches[1]] = $Matches[2].Trim()
    }
  }
  $components = @()
  foreach ($node in $nodes) {
    $filters = @()
    foreach ($filterNode in @($node.Children | Where-Object { $_.Element -eq "intent-filter" })) {
      $actions = @(); $categories = @(); $others = @()
      foreach ($child in $filterNode.Children) {
        $name = if ($child.Attrs.ContainsKey("name")) { ConvertFrom-AaptString $child.Attrs["name"] } else { $null }
        if ($child.Element -eq "action") { $actions += $name }
        elseif ($child.Element -eq "category") { $categories += $name }
        else { $others += $child.Element }
      }
      $filters += , ([pscustomobject]@{ Actions = $actions; Categories = $categories; Others = $others })
    }
    $a = $node.Attrs
    $components += , ([pscustomobject]@{
      Kind = $node.Element
      Name = $(if ($a.ContainsKey("name")) { ConvertFrom-AaptString $a["name"] } else { "" })
      Exported = $(if ($a.ContainsKey("exported")) { Test-AaptTrue $a["exported"] } else { $null })
      Enabled = $(if ($a.ContainsKey("enabled")) { Test-AaptTrue $a["enabled"] } else { $true })
      Permission = $(if ($a.ContainsKey("permission")) { [string](ConvertFrom-AaptString $a["permission"]) } else { "" })
      Target = $(if ($a.ContainsKey("targetActivity")) { [string](ConvertFrom-AaptString $a["targetActivity"]) } else { "" })
      Filters = $filters
    })
  }
  return , $components
}

# Un componente esportato e' ammesso come ingresso del launcher? Solo un activity-alias con il nome dell'elenco (la forma la guarda Test-LauncherAliases).
function Test-LauncherAliasName($Component, $Policy) {
  return ($Component.Kind -eq "activity-alias" -and $Policy.Aliases -contains $Component.Name)
}

# I problemi degli ingressi del launcher (un elenco vuoto = in regola).
function Test-LauncherAliases($Components, $Policy) {
  $problems = @()
  $target = @($Components | Where-Object { $_.Kind -eq "activity" -and $_.Name -eq $Policy.Target })
  if ($target.Count -eq 0) { return , @("gli ingressi del launcher puntano a $($Policy.Target), che nell'APK non c'e'") }
  foreach ($name in $Policy.Aliases) {
    $found = @($Components | Where-Object { $_.Name -eq $name })
    if ($found.Count -eq 0) { $problems += "manca l'ingresso del launcher <activity-alias> $name (I5)"; continue }
    $alias = $found[0]
    if ($alias.Kind -ne "activity-alias") { $problems += "$name deve essere un <activity-alias> (trovato <$($alias.Kind)>)"; continue }
    if ($alias.Target -ne $Policy.Target) { $problems += "<activity-alias> ${name}: deve puntare a $($Policy.Target) (trovato '$($alias.Target)')" }
    if ($alias.Exported -ne $true) { $problems += "<activity-alias> ${name}: e' un ingresso del launcher, deve essere esportato" }
    if ($alias.Permission -ne $target[0].Permission) {
      $problems += "<activity-alias> ${name}: android:permission deve essere quello di $($Policy.Target) ('$($target[0].Permission)'), trovato '$($alias.Permission)'"
    }
    if (@($alias.Filters).Count -ne 1) {
      $problems += "<activity-alias> ${name}: deve avere un solo intent-filter (trovati $(@($alias.Filters).Count))"
    } else {
      $filter = $alias.Filters[0]
      if ((@($filter.Actions) -join "|") -ne $script:LauncherAction) { $problems += "<activity-alias> ${name}: il filtro deve avere la sola azione MAIN (trovate $(@($filter.Actions) -join ', '))" }
      if ((@($filter.Categories) -join "|") -ne $script:LauncherCategory) { $problems += "<activity-alias> ${name}: il filtro deve avere la sola categoria LAUNCHER (trovate $(@($filter.Categories) -join ', '))" }
      if (@($filter.Others).Count -gt 0) { $problems += "<activity-alias> ${name}: il filtro non deve avere altro che MAIN e LAUNCHER (trovati $(@($filter.Others) -join ', '))" }
    }
    if ($name -eq $Policy.Default -and -not $alias.Enabled) { $problems += "<activity-alias> ${name}: e' l'icona di serie, deve essere abilitato" }
    if ($name -ne $Policy.Default -and $alias.Enabled) { $problems += "<activity-alias> ${name}: deve essere enabled=false di serie (un solo ingresso del launcher alla volta)" }
  }
  foreach ($component in $Components) {
    if (@("activity", "activity-alias") -notcontains $component.Kind) { continue }
    $launcher = @($component.Filters | Where-Object { @($_.Categories) -contains $script:LauncherCategory }).Count -gt 0
    if ($launcher -and $Policy.Aliases -notcontains $component.Name) {
      $problems += "<$($component.Kind)> $($component.Name) e' un ingresso del launcher fuori elenco (I5: solo $($Policy.Aliases -join ', '))"
    }
  }
  return , $problems
}

# Il controllo intero sull'APK: aapt2, la politica dal file gradle, i problemi.
function Test-ApkLauncherAliases([string]$Aapt2, [string]$Apk, [string]$GradleText) {
  $dump = Invoke-Native -File $Aapt2 -Arguments @("dump", "xmltree", "--file", "AndroidManifest.xml", $Apk)
  if ($dump.Code -ne 0) { return , @("aapt2 dump xmltree fallito (ingressi del launcher)") }
  $policy = Get-LauncherAliasPolicy $GradleText
  return , (Test-LauncherAliases (Read-LauncherFacts $dump.Lines) $policy)
}
