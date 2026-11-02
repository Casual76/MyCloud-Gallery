# Due catture FINTE nel formato di `emulator -tcpdump` (libpcap classico, Ethernet) per provare Read-SecPcap (S8 (b)) senza emulatore:
# -Clean: DNS verso il resolver di slirp e due SYN verso 443 di due host; altrimenti: un SYN verso 445 del NAS e una SMB2 SESSION_SETUP
# con dentro NTLMSSP (il caso rosso: un'autenticazione partita da una rete estranea).

function New-FakeFrame([byte[]]$Src, [byte[]]$Dst, [int]$Proto, [int]$SrcPort, [int]$DstPort, [int]$Flags, [byte[]]$Payload) {
  $l4 = New-Object System.Collections.Generic.List[byte]
  $l4.AddRange([byte[]](@((($SrcPort -shr 8) -band 0xff), ($SrcPort -band 0xff), (($DstPort -shr 8) -band 0xff), ($DstPort -band 0xff))))
  if ($Proto -eq 6) {
    $l4.AddRange([byte[]](0, 0, 0, 1, 0, 0, 0, 0, 0x50, $Flags, 0xff, 0xff, 0, 0, 0, 0))
  } else {
    $len = 8 + $Payload.Length
    $l4.AddRange([byte[]](@((($len -shr 8) -band 0xff), ($len -band 0xff), 0, 0)))
  }
  $l4.AddRange($Payload)
  $total = 20 + $l4.Count
  $ip = [byte[]](@(0x45, 0, (($total -shr 8) -band 0xff), ($total -band 0xff), 0, 1, 0x40, 0, 64, $Proto, 0, 0)) + $Src + $Dst
  $eth = [byte[]](0x52, 0x54, 0, 0x12, 0x35, 2, 0x52, 0x54, 0, 0x12, 0x34, 0x56, 0x08, 0x00)
  return [byte[]]($eth + $ip + $l4.ToArray())
}

function New-FakePcap([string]$Path, [switch]$Clean) {
  $out = New-Object System.Collections.Generic.List[byte]
  $out.AddRange([BitConverter]::GetBytes([uint32]2712847316))   # 0xa1b2c3d4, little endian
  $out.AddRange([byte[]](2, 0, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 0, 0, 1, 0, 0, 0))
  $me = [byte[]](10, 0, 2, 15)
  $frames = @()
  if ($Clean) {
    foreach ($hostName in @("tiles.openfreemap.org", "esempio.invalid")) {
      $q = New-Object System.Collections.Generic.List[byte]
      $q.AddRange([byte[]](0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
      foreach ($label in $hostName.Split(".")) { $q.Add([byte]$label.Length); $q.AddRange([System.Text.Encoding]::ASCII.GetBytes($label)) }
      $q.AddRange([byte[]](0, 0, 1, 0, 1))
      $frames += , (New-FakeFrame $me ([byte[]](10, 0, 2, 3)) 17 40000 53 0 $q.ToArray())
    }
    $frames += , (New-FakeFrame $me ([byte[]](1, 2, 3, 4)) 6 50000 443 0x02 ([byte[]]@()))
    $frames += , (New-FakeFrame $me ([byte[]](5, 6, 7, 8)) 6 50001 443 0x02 ([byte[]]@()))
  } else {
    $nas = [byte[]](192, 168, 178, 58)
    $frames += , (New-FakeFrame $me $nas 6 50002 445 0x02 ([byte[]]@()))
    $smb = New-Object System.Collections.Generic.List[byte]
    $smb.AddRange([byte[]](0xFE, 0x53, 0x4D, 0x42, 64, 0, 1, 0, 0, 0, 0, 0, 1, 0))
    $smb.AddRange((New-Object byte[] 50))
    $smb.AddRange([System.Text.Encoding]::ASCII.GetBytes("NTLMSSP"))
    $smb.AddRange([byte[]](0, 1, 0, 0, 0))
    $nb = [byte[]](0, 0, (($smb.Count -shr 8) -band 0xff), ($smb.Count -band 0xff))
    $frames += , (New-FakeFrame $me $nas 6 50002 445 0x18 ([byte[]]($nb + $smb.ToArray())))
  }
  foreach ($f in $frames) {
    $out.AddRange([BitConverter]::GetBytes([uint32]1793000000))
    $out.AddRange([BitConverter]::GetBytes([uint32]0))
    $out.AddRange([BitConverter]::GetBytes([uint32]$f.Length))
    $out.AddRange([BitConverter]::GetBytes([uint32]$f.Length))
    $out.AddRange([byte[]]$f)
  }
  [System.IO.File]::WriteAllBytes($Path, $out.ToArray())
}
