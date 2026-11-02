# Le prove sulle funzioni di release.ps1 (Fase 10, P12), caricato da test-release.ps1 nella sua portata: usa Check, $work, $utf8, $password, $ksA e le librerie.
# Solo ASCII, PowerShell 5.1.

Write-Host "`nL'albero (git): pulito, sul tag, sul ramo main" -ForegroundColor Cyan
$repo = Join-Path $work "gitrepo"
New-Item -ItemType Directory -Path $repo -Force | Out-Null
function Invoke-TestGit([string[]]$GitArgs) {
  $run = Invoke-Native -File "git" -Arguments (@("-C", $repo, "-c", "user.name=Prova", "-c", "user.email=prova@example.invalid", "-c", "commit.gpgsign=false", "-c", "init.defaultBranch=main") + $GitArgs)
  if ($run.Code -ne 0) { throw ("git " + ($GitArgs -join " ") + " fallito: " + ($run.Lines -join " | ")) }
}
Invoke-TestGit @("init", "-q")
[System.IO.File]::WriteAllText((Join-Path $repo "a.txt"), "uno`n", $utf8)
Invoke-TestGit @("add", "a.txt"); Invoke-TestGit @("commit", "-q", "-m", "uno"); Invoke-TestGit @("tag", "v1.0.0")
$p = @(Test-ReleaseTree $repo "1.0.0" "main" $false)
Check "albero pulito, HEAD sul tag di main: nessun problema" ($p.Count -eq 0) ($p -join "; ")
$p = @(Test-ReleaseTree $repo "1.0.1" "main" $false)
Check "tag mancante: fallisce" (($p -join "; ") -like "*manca il tag v1.0.1*")
[System.IO.File]::WriteAllText((Join-Path $repo "b.txt"), "sporco`n", $utf8)
$p = @(Test-ReleaseTree $repo "1.0.0" "main" $false)
Check "un file non committato: fallisce" (($p -join "; ") -like "*modifiche non committate*")
Remove-Item -LiteralPath (Join-Path $repo "b.txt")
Invoke-TestGit @("checkout", "-q", "-b", "lavoro")
[System.IO.File]::WriteAllText((Join-Path $repo "a.txt"), "due`n", $utf8)
Invoke-TestGit @("commit", "-q", "-am", "due"); Invoke-TestGit @("tag", "v2.0.0")
$p = @(Test-ReleaseTree $repo "2.0.0" "main" $false)
Check "un tag che non e' su main: fallisce" (($p -join "; ") -like "*non e' sul ramo main*")
$p = @(Test-ReleaseTree $repo "1.0.0" "main" $false)
Check "HEAD avanti rispetto al tag con altre modifiche: fallisce" (($p -join "; ") -like "*HEAD e' avanti rispetto al tag*")
Invoke-TestGit @("checkout", "-q", "main")
New-Item -ItemType Directory -Path (Join-Path $repo "release") -Force | Out-Null
[System.IO.File]::WriteAllText((Join-Path $repo "release\versions.txt"), "1.0.0  1  pubblicata  beta  2026-11-02  " + ("a" * 64) + "`n", $utf8)
Invoke-TestGit @("add", "release/versions.txt"); Invoke-TestGit @("commit", "-q", "-m", "segno la pubblicata")
$p = @(Test-ReleaseTree $repo "1.0.0" "main" $false)
Check "un commit dopo il tag che cambia solo release/versions.txt va bene (passaggio da beta a stable)" ($p.Count -eq 0) ($p -join "; ")
[System.IO.File]::WriteAllText((Join-Path $repo "a.txt"), "tre`n", $utf8)
Invoke-TestGit @("commit", "-q", "-am", "altro")
$p = @(Test-ReleaseTree $repo "1.0.0" "main" $false)
Check "un commit dopo il tag che cambia altro fallisce" (($p -join "; ") -like "*oltre a release/versions.txt*")
Invoke-TestGit @("reset", "-q", "--hard", "v1.0.0")
$p = @(Test-ReleaseTree $repo "1.0.0" "main" $true)
Check "senza engine-doctor la verifica dell'engine fallisce (non salta)" (($p -join "; ") -like "*engine*")

Write-Host "`nIl riferimento S25 e il riepilogo di check.ps1" -ForegroundColor Cyan
$fake = Join-Path $work "fake-root"
New-Item -ItemType Directory -Path (Join-Path $fake "docs\bench") -Force | Out-Null
$saved = $script:Root
$script:Root = $fake
$today = [datetime]"2026-11-02"
$p = @(Test-BenchReference 7 $today)
Check "senza riferimenti S25: fallisce" (($p -join "; ") -like "*nessun riferimento S25*")
[System.IO.File]::WriteAllText((Join-Path $fake "docs\bench\riferimento-s25-a.json"), '{ "data": "2026-10-26" }', $utf8)
$p = @(Test-BenchReference 7 $today)
Check "un riferimento di 7 giorni va bene" ($p.Count -eq 0) ($p -join "; ")
$p = @(Test-BenchReference 6 $today)
Check "un riferimento di 7 giorni con il limite a 6 fallisce" (($p -join "; ") -like "*ha 7 giorni*")
[System.IO.File]::WriteAllText((Join-Path $fake "docs\bench\riferimento-s25-b.json"), '{ "data": "2026-11-05" }', $utf8)
$p = @(Test-BenchReference 7 $today)
Check "un riferimento nel futuro fallisce" (($p -join "; ") -like "*nel futuro*")
Remove-Item -LiteralPath (Join-Path $fake "docs\bench\riferimento-s25-b.json")
[System.IO.File]::WriteAllText((Join-Path $fake "docs\bench\riferimento-s25-c.json"), '{ "data": "2026-10-13" }', $utf8)
$p = @(Test-BenchReference 7 $today)
Check "vale il riferimento piu' recente, non il piu' vecchio" ($p.Count -eq 0) ($p -join "; ")
$script:Root = $saved

$okSummary = @("[ OK ] x", "", "Riepilogo", "  OK    gradle: assembleDebug", "  OK    benchmark su dispositivo (-Bench): scenari del par. 2.6", "", "OK: tutto a posto.")
Check "check.ps1 tutto OK: nessun problema" (@(Test-CheckSummary $okSummary 0).Count -eq 0)
$failSummary = @("Riepilogo", "  FAIL  APK di release: non debuggable", "  OK    benchmark su dispositivo (-Bench): scenari")
Check "check.ps1 con un FAIL: fallisce" (((Test-CheckSummary $failSummary 1) -join "; ") -like "*check.ps1*")
$skipSummary = @("Riepilogo", "  OK    gradle", "  SKIP  benchmark su dispositivo (-Bench): scenari del par. 2.6")
Check "il benchmark saltato (S25 non collegato) fa fallire la release" (((Test-CheckSummary $skipSummary 0) -join "; ") -like "*benchmark su dispositivo e' SKIP*")
Check "un log senza riepilogo (check.ps1 interrotto) fallisce" (((Test-CheckSummary @("[ .. ] passo") 1) -join "; ") -like "*non c'e' il riepilogo*")

Write-Host "`nLe regole di versions.txt, -Prepara e -SegnaPubblicata" -ForegroundColor Cyan
$versions = Join-Path $work "versions-prova.txt"
[System.IO.File]::WriteAllText($versions, "# prova`n1.0.0  1  pubblicata  beta  2026-11-01  " + ("c" * 64) + "`n", $utf8)
$code = Add-PreparedVersion $versions "1.0.0" "stable"
Check "-Prepara: da beta a stable della stessa versione il numero resta" ($code -eq 1)
[System.IO.File]::WriteAllText($versions, "# prova`n1.0.0  1  pubblicata  beta  2026-11-01  " + ("c" * 64) + "`n", $utf8)
$code = Add-PreparedVersion $versions "1.1.0" "beta"
Check "-Prepara: una versione nuova prende il massimo + 1" ($code -eq 2)
$prepared = Test-VersionRows (Read-VersionRows $versions) "1.1.0" "beta"
Check "la riga preparata passa le regole" (@($prepared).Count -eq 0) (@($prepared) -join "; ")
$threw = $false
try { Add-PreparedVersion $versions "1.2.0" "beta" | Out-Null } catch { $threw = $true }
Check "-Prepara non aggiunge una seconda riga prevista" $threw
$shaNew = "d" * 64
Set-VersionPublished $versions "1.1.0" "beta" "2026-11-20" $shaNew
$rows = (Read-VersionRows $versions)
Check "-SegnaPubblicata scrive stato, data e impronta" (@($rows.Problems).Count -eq 0 -and @($rows.Rows)[1].State -eq "pubblicata" -and @($rows.Rows)[1].Date -eq "2026-11-20" -and @($rows.Rows)[1].Sha -eq $shaNew)
$threw = $false
try { Set-VersionPublished $versions "1.1.0" "beta" "2026-11-21" $shaNew } catch { $threw = $true }
Check "-SegnaPubblicata non cambia una riga gia' pubblicata" $threw
$p = @(Test-VersionRows (Read-VersionRows $versions) "1.1.0" "beta")
Check "con l'ultima riga gia' pubblicata si chiede -Prepara" (($p -join "; ") -like "*release.ps1 -Prepara*")

Write-Host "`nL'elenco dei resti di debug viene da check.ps1" -ForegroundColor Cyan
$names = @(Get-DebugRemnants)
Check "l'elenco letto da check.ps1 ha i nomi della release (KT, ManifestTool, debug)" ($names.Count -ge 60 -and $names -contains "ReleaseTestKeys" -and $names -contains "ManifestTool" -and $names -contains 'DeveloperTools$ReadyFamily') ("nomi: " + $names.Count)
