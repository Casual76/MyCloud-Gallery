package dev.pampa.mycloudgallery.core.network.discovery

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.network.testing.LinkFactsSamples
import dev.pampa.mycloudgallery.core.network.testing.LinkFactsSamples.ip
import org.junit.Test

/**
 * La scoperta e' solo dell'onboarding e accetta solo indirizzi della rete locale: un dispositivo malevolo che
 * annuncia un NAS con un indirizzo di Internet non porta l'app a connettersi li'.
 */
class LanAddressFilterTest {

  private val lan = LinkFactsSamples.homeWifi()

  @Test
  fun siAccettanoGliIndirizziDellaSottorete() {
    assertThat(LanAddressFilter.onLink(ip("192.168.178.50"), lan)).isTrue()
    assertThat(LanAddressFilter.onLink(ip("fd12:3456:789a::58"), lan)).isTrue()
    assertThat(LanAddressFilter.onLink(ip("2001:db8:abcd:1::58"), lan)).isTrue()
  }

  @Test
  fun siRifiutaTuttoIlResto() {
    // Un indirizzo pubblico annunciato da chiunque, un'altra sottorete privata, un'altra casa.
    assertThat(LanAddressFilter.onLink(ip("93.184.216.34"), lan)).isFalse()
    assertThat(LanAddressFilter.onLink(ip("10.0.0.5"), lan)).isFalse()
    assertThat(LanAddressFilter.onLink(ip("192.168.179.5"), lan)).isFalse()
    assertThat(LanAddressFilter.onLink(ip("fd5e:21c7:b0a4::58"), lan)).isFalse()
    assertThat(LanAddressFilter.onLink(ip("2001:db8:ffff::1"), lan)).isFalse()
    // Link-local: servirebbe la zona, che un IP letterale non porta.
    assertThat(LanAddressFilter.onLink(ip("fe80::1234"), lan)).isFalse()
    // L'indirizzo del telefono stesso non e' un NAS.
    assertThat(LanAddressFilter.onLink(ip("192.168.178.20"), lan)).isFalse()
  }

  @Test
  fun siSceglieIlPrimoIpv4DellaRete() {
    val picked = LanAddressFilter.pick(
      listOf(ip("fd12:3456:789a::58"), ip("8.8.8.8"), ip("192.168.178.50"), ip("192.168.178.51")),
      lan,
    )

    assertThat(picked).isEqualTo(ip("192.168.178.50"))
  }

  @Test
  fun seNonCeNullaDiValido_nulla() {
    assertThat(LanAddressFilter.pick(listOf(ip("8.8.8.8"), ip("fe80::1")), lan)).isNull()
    assertThat(LanAddressFilter.pick(emptyList(), lan)).isNull()
  }

  @Test
  fun iNomiDigitatiSonoPlausibili_oNo() {
    for (good in listOf("MyCloudEX2Ultra", "nas.fritz.box", "nas.fritz.box.", "my_nas", "a-b.c-d")) {
      assertThat(NsdNasDiscovery.isPlausibleHostName(good)).isTrue()
    }
    for (bad in listOf("", " ", "nas fritz", "http://nas", "nas/share", "-nas", "nas-", "nas..box", "a".repeat(64), "x".repeat(254))) {
      assertThat(NsdNasDiscovery.isPlausibleHostName(bad)).isFalse()
    }
  }

  @Test
  fun ilNomeAnnunciatoSiRipulisceSoloPerMostrarlo() {
    assertThat(NsdNasDiscovery.sanitizeName("  Il mio NAS \u0007\n")).isEqualTo("Il mio NAS")
    assertThat(NsdNasDiscovery.sanitizeName("x".repeat(200))).hasLength(63)
    assertThat(NsdNasDiscovery.sanitizeName("")).isEqualTo("NAS")
    assertThat(NsdNasDiscovery.sanitizeName(null)).isEqualTo("NAS")
  }
}
