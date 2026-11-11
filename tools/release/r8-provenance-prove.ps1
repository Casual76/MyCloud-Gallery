# Dati SINTETICI scritti a mano, senza Gradle o Android.
$ErrorActionPreference='Stop'
$root=(Resolve-Path "$PSScriptRoot\..\..").Path
$work=Join-Path $root 'build\prova-r8'
New-Item -ItemType Directory -Force $work | Out-Null
Add-Type -Path (Join-Path $PSScriptRoot 'r8-provenance.cs')
@'
allowed.Owner -> residual.Fused:
    1:2:void allowed.Owner.run():10:11 -> run
    3:4:void forbidden.Owner.run():20:21 -> run
    5:5:void forbidden.Inline.work():30:30 -> run
    5:5:void allowed.Owner.run():12 -> run
    6:6:void allowed.Owner.run():13:13 -> run
    6:6:java.lang.Object allowed.Owner.run(java.lang.Object):14:14 -> run
missing.Positions -> missing.Positions:
    1:1:void allowed.Owner.run():1 -> run
    2:2:void forbidden.Owner.run():2 -> run
ambiguous.Owner -> ambiguous.Owner:
    1:4:void allowed.Owner.run():1 -> run
    2:5:void forbidden.Owner.run():2 -> run
unique.Owner -> unique.Owner:
    void run() -> run
'@ | Set-Content (Join-Path $work 'mapping.txt')
$prefixes=@('core/models/download/https/', 'feature/map/net/', 'feature/map/', 'feature/widget/', 'core/ai/', 'core/ai/ocr/MlKitBootstrap', 'core/faces/sync/', 'albums/', 'di/')
foreach($prefix in $prefixes) {
 $owner='dev.pampa.mycloudgallery.'+$prefix.Replace('/','.')+'Owner'
 @("$owner -> ${owner}:", "    1:1:void run():1 -> run", "    2:2:void forbidden.Owner.run():2 -> run") | Add-Content (Join-Path $work 'mapping.txt')
}
[R8Provenance]::MappingPath=Join-Path $work 'mapping.txt'
$count=0
function Check($name,[bool]$ok) { if(-not $ok) {throw "FAIL $name"}; $script:count++; Write-Host "[OK] $name" }
function Throws($name,[scriptblock]$body) { $caught=$false; try { & $body | Out-Null } catch {$caught=$true}; Check $name $caught }
function Dump($class,$signature,$positions) {
 $lines=@("Class descriptor : '$class'", "name : 'run'", "type : '$signature'", '1000: 0000 |0000: invoke-static {}, Ljava/net/HttpURLConnection;.disconnect:()V // method@1') + $positions
 [R8Provenance]::ReadDump($class+$signature,[string[]]$lines)
 return $lines[3]
}
$call=Dump 'Lresidual/Fused;' '()V' @(' 0x0000 line=1')
Check 'fusione origine ammessa' ([R8Provenance]::Origin('Lresidual/Fused;()V',$call) -eq 'Lallowed/Owner;.run')
$call=Dump 'Lresidual/Fused;' '()V' @(' 0x0000 line=3')
Check 'fusione inversa origine vietata sotto nome ammesso' ([R8Provenance]::Origin('Lresidual/Fused;()V',$call) -eq 'Lforbidden/Owner;.run')
$call=Dump 'Lresidual/Fused;' '()V' @(' 0x0000 line=5')
Check 'inline frame interno vietato prevale' ([R8Provenance]::Origin('Lresidual/Fused;()V',$call) -eq 'Lforbidden/Inline;.work')
$call=Dump 'Lresidual/Fused;' '(Ljava/lang/Object;)Ljava/lang/Object;' @(' 0x0000 line=6')
Check 'overload firma residuale' ([R8Provenance]::Origin('Lresidual/Fused;(Ljava/lang/Object;)Ljava/lang/Object;',$call) -eq 'Lallowed/Owner;.run')
$call=Dump 'Lmissing/Positions;' '()V' @()
Throws 'fusione senza posizioni cade' { [R8Provenance]::Origin('Lmissing/Positions;()V',$call) }
$call=Dump 'Lambiguous/Owner;' '()V' @(' 0x0000 line=3')
Throws 'intervalli sovrapposti cadono' { [R8Provenance]::Origin('Lambiguous/Owner;()V',$call) }
$call=Dump 'Lunknown/Owner;' '()V' @(' 0x0000 line=1')
Throws 'mapping assente cade' { [R8Provenance]::Origin('Lunknown/Owner;()V',$call) }
$call=Dump 'Lunique/Owner;' '()V' @()
Check 'identita unica senza posizioni' ([R8Provenance]::Origin('Lunique/Owner;()V',$call) -eq 'Lunique/Owner;.run')
foreach($prefix in $prefixes) {
 $class='Ldev/pampa/mycloudgallery/'+$prefix+'Owner;'
 $call=Dump $class '()V' @(' 0x0000 line=1')
 Check "policy $prefix origine ammessa passa" ([R8Provenance]::Origin($class+'()V',$call).StartsWith('Ldev/pampa/mycloudgallery/'+$prefix))
 $call=Dump $class '()V' @(' 0x0000 line=2')
 Check "policy $prefix fusione inversa cade" (-not [R8Provenance]::Origin($class+'()V',$call).StartsWith('Ldev/pampa/mycloudgallery/'+$prefix))
}
function Outline($owner,$body) {
 $class='L'+$owner+'$$ExternalSyntheticApiModelOutline0;'
 [R8Provenance]::ReadDump('outline',[string[]]@("Class descriptor : '$class'", "name : 'm'", "type : '()V'", ('2000: 0000 |0000: '+$body), '2002: 0000 |0003: return-void'))
 return ('{}, '+$class+'.m:()V // method@1')
}
$ref=Outline 'org/maplibre/Fake' 'invoke-static {}, Landroid/app/Activity;.platform:()V'
Check 'outline nome MapLibre corpo Android accettato' ([R8Provenance]::PlatformOutline($ref))
foreach($target in @('Lorg/maplibre/Real;','Lokhttp3/Real;','Landroidx/glance/Real;','Ljava/net/HttpURLConnection;','Landroid/webkit/WebView;')) {
 $ref=Outline 'org/maplibre/Fake' ('invoke-static {}, '+$target+'.real:()V')
 Check "outline $target non esentato" (-not [R8Provenance]::PlatformOutline($ref))
 Check "outline $target propagato al chiamante" ([R8Provenance]::OutlineBody($ref).Contains($target))
}
Throws 'outline corpo assente cade' {[R8Provenance]::PlatformOutline('{}, Lmissing/Fake$$ExternalSyntheticApiModelOutline0;.m:()V // method@1')}
$ref=Outline 'org/maplibre/Fake' 'invoke-custom {}, call_site@1'
Throws 'outline indiretto cade' {[R8Provenance]::PlatformOutline($ref)}
$ref=Outline 'org/maplibre/Fake' 'invoke-static {}, Landroid/Fake$$ExternalSyntheticApiModelOutline1;.m:()V'
Throws 'outline annidato non dimostrato cade' {[R8Provenance]::PlatformOutline($ref)}
$ref=Outline 'org/maplibre/Fake' 'return-void'
Check 'outline no-op non chiama librerie' ([R8Provenance]::PlatformOutline($ref))
foreach($callee in @('Ldev/pampa/mycloudgallery/feature/map/net/Good;', 'Lforbidden/Bad;')) {
 $line='3000: 0000 |0000: invoke-static {}, '+$callee+'.$r8$lambda$0:(Lokhttp3/Client;)V // method@1'
 [R8Provenance]::ReadDump('bridge',[string[]]@('Class descriptor : ''Lallowed/Fused$$ExternalSyntheticLambda0;''', "name : 'run'", "type : '()V'", $line))
 Check "ponte sintetico attribuito a $callee" ([R8Provenance]::Origin('bridge',$line).StartsWith($callee))
}
Write-Host "$count prove OK"
