package dev.pampa.mycloudgallery.core.common.diagnostics

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.security.SecureRandom
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticsTest {

  @get:Rule
  val temp = TemporaryFolder()

  private enum class Sample { HOME_TUNNEL }

  private fun event(at: Long, values: Map<String, ExportValue> = emptyMap()) =
    DiagEvent(at, DiagCode("NET_SAMPLE"), DiagSeverity.INFO, values)

  @Test
  fun lAnelloTieneSoloGliUltimiEventi() {
    val log = RingDiagnosticsLog(capacity = 3)
    (1L..5L).forEach { log.record(event(it)) }
    assertThat(log.snapshot().map { it.at }).containsExactly(3L, 4L, 5L).inOrder()
  }

  @Test
  fun ilFileSopravviveAlRiavvioESiCompatta() {
    val file = File(temp.root, "diagnostics/events.log")
    val hasher = DiagHasher.withRandomKey(SecureRandom())
    val values = mapOf(
      "count" to ExportValue.Num(42),
      "encrypted" to ExportValue.Flag(true),
      "elapsed" to ExportValue.Dur(250),
      "vpn" to ExportValue.Enum.of(Sample.HOME_TUNNEL),
      "account" to hasher.hash("mario"),
    )
    val first = RingDiagnosticsLog(capacity = 4, store = FileDiagnosticsLogStore(file, keep = 4, compactAt = 6))
    (1L..10L).forEach { first.record(event(it, values)) }

    // Un "riavvio": un anello nuovo sullo stesso file ritrova gli ultimi 4 eventi, con i valori intatti.
    val second = RingDiagnosticsLog(capacity = 4, store = FileDiagnosticsLogStore(file, keep = 4, compactAt = 6))
    val restored = second.snapshot()
    assertThat(restored.map { it.at }).containsExactly(7L, 8L, 9L, 10L).inOrder()
    assertThat(restored.last().values).isEqualTo(values)
    // Il file non cresce senza limite.
    assertThat(file.readLines().size).isAtMost(6)
  }

  @Test
  fun unaRigaManomessaSiScartaENonPortaTestoLibero() {
    val file = File(temp.root, "events.log")
    file.writeText(
      "1|NET_SAMPLE|INFO|count=n1\n" +
        "2|NET_SAMPLE|INFO|name=Mario Rossi\n" +
        "3|net sample|INFO|\n" +
        "4|NET_SAMPLE|INFO|ip=e192.168.1.10\n" +
        "5|NET_SAMPLE|INFO|account=hnon-esadecimale\n",
    )
    val events = FileDiagnosticsLogStore(file).load()
    assertThat(events.map { it.at }).containsExactly(1L)
  }

  /** P9 (nota di P8): un identificatore che ha la forma di un nome non torna come costante di un enum. */
  @Test
  fun unNomeTravestitoDaEnumSiScartaGiaAllaRilettura() {
    val file = File(temp.root, "events.log")
    file.writeText(
      "1|NET_SAMPLE|INFO|vpn=eSample.HOME_TUNNEL\n" +
        "2|NET_SAMPLE|INFO|who=eMario.Rossi\n" +
        "3|NET_SAMPLE|INFO|who=eMARIO.ROSSI\n" +
        "4|NET_SAMPLE|INFO|who=emario.ROSSI\n" +
        "5|NET_SAMPLE|INFO|who=eSample.Rossi\n",
    )
    val events = FileDiagnosticsLogStore(file).load()
    assertThat(events.map { it.at }).containsExactly(1L)
    assertThat(events.single().values["vpn"]).isEqualTo(ExportValue.Enum.of(Sample.HOME_TUNNEL))
  }

  @Test
  fun lImprontaEStabileConLaStessaChiaveENonContieneIlDato() {
    val key = ByteArray(32) { it.toByte() }
    val a = DiagHasher(key).hash("192.168.178.58")
    val b = DiagHasher(key).hash("192.168.178.58")
    val other = DiagHasher(ByteArray(32) { (it + 1).toByte() }).hash("192.168.178.58")
    assertThat(a).isEqualTo(b)
    assertThat(a).isNotEqualTo(other)
    assertThat(a.hex).matches("[0-9a-f]{12}")
    assertThat(a.toString()).doesNotContain("192.168")
  }

  @Test
  fun codiciEChiaviDiEsportazioneSonoValidati() {
    assertThrows(IllegalArgumentException::class.java) { DiagCode("minuscolo") }
    assertThrows(IllegalArgumentException::class.java) { DiagCode("NET_OK", edgeCases = setOf("qualunque cosa")) }
    assertThat(DiagCode("NET_FOREIGN_VPN", setOf("E-S16", "E-R11", "C13", "T-17")).id).isEqualTo("NET_FOREIGN_VPN")
    assertThrows(IllegalArgumentException::class.java) {
      DiagItem(DiagCode("NET_OK"), DiagSection.NETWORK_AND_NAS, DiagSeverity.OK, titleRes = 1, export = mapOf("con spazio" to ExportValue.Num(1)))
    }
    assertThat(ExportValue.Enum.of(Sample.HOME_TUNNEL)).isEqualTo(ExportValue.Enum.of(Sample.HOME_TUNNEL))
  }
}
