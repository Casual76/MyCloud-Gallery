package dev.pampa.mycloudgallery.core.network.link

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IpModelTest {

  private fun ip(text: String) = requireNotNull(IpAddr.parse(text)) { text }

  @Test
  fun laFormaTestualeEQuellaCanonica() {
    assertThat(ip("192.168.178.1").toText()).isEqualTo("192.168.178.1")
    assertThat(ip("FD12:3456:789A:0000:0000:0000:0000:0001").toText()).isEqualTo("fd12:3456:789a::1")
    assertThat(ip("fd12:3456:789a::").toText()).isEqualTo("fd12:3456:789a::")
    assertThat(ip("::1").toText()).isEqualTo("::1")
    assertThat(ip("::").toText()).isEqualTo("::")
    // Un solo gruppo a zero non si comprime (RFC 5952); la sequenza piu' lunga si'.
    assertThat(ip("2001:db8:0:1:1:1:1:1").toText()).isEqualTo("2001:db8:0:1:1:1:1:1")
    assertThat(ip("2001:0:0:1:0:0:0:1").toText()).isEqualTo("2001:0:0:1::1")
  }

  @Test
  fun unNomeNonEUnIndirizzo_neMaiSiRisolve() {
    for (bad in listOf("nas.fritz.box", "MyCloudEX2", "", "192.168.178", "fe80::1%wlan0", "1::2::3", "192.168.178.1/24")) {
      assertThat(IpAddr.parse(bad)).isNull()
    }
  }

  @Test
  fun unIpv6MappatoEUgualeAllIpv4() {
    assertThat(ip("::ffff:192.168.178.1")).isEqualTo(ip("192.168.178.1"))
    assertThat(ip("::ffff:192.168.178.1").isV4).isTrue()
  }

  @Test
  fun leClassiDiIndirizzo() {
    assertThat(ip("fd12:3456:789a::1").isUla).isTrue()
    assertThat(ip("fc00::1").isUla).isTrue()
    assertThat(ip("fe80::1").isUla).isFalse()
    assertThat(ip("fe80::1").isLinkLocal).isTrue()
    assertThat(ip("169.254.1.1").isLinkLocal).isTrue()
    assertThat(ip("2001:db8::1").isGlobalV6).isTrue()
    assertThat(ip("fd12::1").isGlobalV6).isFalse()
    assertThat(ip("192.168.178.1").isUla).isFalse()
  }

  @Test
  fun iPrefissi_contengonoSoloLoStessoGenere() {
    val v4 = IpPrefix.parse("192.168.178.7/24")!!
    assertThat(v4.toText()).isEqualTo("192.168.178.0/24")
    assertThat(v4.contains(ip("192.168.178.250"))).isTrue()
    assertThat(v4.contains(ip("192.168.179.1"))).isFalse()
    assertThat(v4.contains(ip("fd12::1"))).isFalse()

    val ula = IpPrefix.ula48Of(ip("fd12:3456:789a:1234:5678::9"))!!
    assertThat(ula.toText()).isEqualTo("fd12:3456:789a::/48")
    assertThat(ula.contains(ip("fd12:3456:789a:ffff::1"))).isTrue()
    assertThat(ula.contains(ip("fd12:3456:789b::1"))).isFalse()
    // Un /48 diverso solo per un bit non coincide.
    assertThat(ula.contains(ip("fd12:3456:78ba::1"))).isFalse()
    assertThat(IpPrefix.ula48Of(ip("2001:db8::1"))).isNull()
  }

  @Test
  fun contenimentoESovrapposizioneTraPrefissi() {
    val outer = IpPrefix.parse("fd12:3456:789a::/48")!!
    val inner = IpPrefix.parse("fd12:3456:789a:5::/64")!!
    assertThat(outer.contains(inner)).isTrue()
    assertThat(inner.contains(outer)).isFalse()
    assertThat(inner.overlaps(outer)).isTrue()
    assertThat(IpPrefix.parse("fd99::/48")!!.overlaps(outer)).isFalse()
    assertThat(IpPrefix.parse("0.0.0.0/0")!!.isDefault).isTrue()
  }

  @Test
  fun iPrefissiMalFormatiSiRifiutano() {
    for (bad in listOf("fd12::/", "/48", "fd12::/129", "192.168.1.1/33", "192.168.1.1/-1", "192.168.1.1/ab", "nome/24", "fd12::")) {
      assertThat(IpPrefix.parse(bad)).isNull()
    }
  }

  @Test
  fun toStringNonStampaGliIndirizzi() {
    assertThat(ip("192.168.178.1").toString()).doesNotContain("192")
    assertThat(IpPrefix.parse("fd12:3456:789a::/48").toString()).doesNotContain("fd12")
    assertThat(LinkAddr(ip("192.168.178.1"), 24).toString()).doesNotContain("192")
  }
}
