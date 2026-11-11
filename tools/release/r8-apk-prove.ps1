# Esegue SOLO sezioni bytecode estratte via AST da check.ps1, mai lo script completo.
# Artefatti gia' copiati sotto build/hk: APK, mapping.txt e classes*.dex estratti.
param([switch]$Before, [switch]$All,
 [string]$BuildTools=(Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools\36.0.0'))
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $root
$source=if($Before) { git show 261a3553:tools/check.ps1 | Out-String } else { [IO.File]::ReadAllText((Join-Path $root 'tools/check.ps1')) }
$ast=[Management.Automation.Language.Parser]::ParseInput($source,[ref]$null,[ref]$null)
if(-not $Before) {
 Remove-Item build/hk-r8-provenance.dll -ErrorAction SilentlyContinue
 Add-Type -Path tools/release/r8-provenance.cs -OutputAssembly build/hk-r8-provenance.dll
 Add-Type -Path build/hk-r8-provenance.dll
 [R8Provenance]::MappingPath=Join-Path $root 'build/hk/mapping.txt'
}
foreach($f in $ast.FindAll({param($a) $a -is [Management.Automation.Language.FunctionDefinitionAst]},$false)) { Invoke-Expression $f.Extent.Text }
function Get-Aapt2 { return (Join-Path $BuildTools 'aapt2.exe') }
function Get-DexDump { return (Join-Path $BuildTools 'dexdump.exe') }
Initialize-ApkInspect
if(-not $Before) { foreach($dex in Get-ChildItem build/hk/classes*.dex) { [R8Provenance]::Prepare((Get-DexDump),$dex.FullName) } }
if(-not $Before) {
 foreach($probe in @(@('classes2.dex','5856b4:'),@('classes2.dex','7b0b9e:'),@('classes3.dex','1f60d2:'))) {
  $hit=Select-String -Path (Join-Path $root ('build/hk/'+$probe[0]+'.txt')) -Pattern ('^'+$probe[1]) | Select-Object -First 1
  $line=$hit.Line
  Write-Host ('ORIGINE '+$probe[0]+' '+$probe[1]+' '+[R8Provenance]::Origin((Join-Path $root ('build/hk/'+$probe[0])),$line))
 }
}
$script:results=New-Object 'System.Collections.Generic.List[object]'
$gradleOk=$true
$steps=$ast.FindAll({param($a) $a -is [Management.Automation.Language.CommandAst] -and $a.GetCommandName() -eq 'Step'},$false)
foreach($node in $steps) {
 $text=$node.Extent.Text
 if($text.StartsWith('Step "motori on-device')) {
  $body=$node.CommandElements[-1].Extent.Text
  $start=$body.IndexOf('  # (3) Il bytecode:')
  $apk=Get-Item (Join-Path $root 'build/hk/app-release-unsigned.apk')
  $problems=@()
  $text='Step "motori on-device: SOLO sezione bytecode ML Kit/HTTPS" {' + $body.Substring($start)
 } elseif(-not ($text.StartsWith('Step "OkHttp e MapLibre') -or ($All -and ($text.StartsWith('Step "LiteRT solo') -or $text.StartsWith('Step "Glance al suo posto') -or $text.StartsWith('Step "D12 e R2 nella release') -or $text.StartsWith('Step "D30 nella release') -or $text.StartsWith('Step "D34 nella release'))))) {continue}
 $text=$text.Replace('app\build\outputs\apk\release','build\hk').Replace('app\build\outputs\mapping\release','build\hk')
 Invoke-Expression $text
 [GC]::Collect()
}
$script:results | Format-List
if($script:failed) {exit 1}
