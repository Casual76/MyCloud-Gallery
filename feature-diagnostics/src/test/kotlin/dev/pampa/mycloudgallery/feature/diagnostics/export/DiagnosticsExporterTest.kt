package dev.pampa.mycloudgallery.feature.diagnostics.export

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.feature.diagnostics.aggregate.DiagnosticsSnapshot
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import dev.pampa.mycloudgallery.feature.diagnostics.item
import dev.pampa.mycloudgallery.feature.diagnostics.testHasher
import kotlin.time.Duration.Companion.milliseconds
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Il formato dell'esportazione: JSON vero, una voce per riga, solo valori ammessi. */
@RunWith(RobolectricTestRunner::class)
class DiagnosticsExporterTest {

  private enum class Bucket { RESTRICTED }

  private fun snapshot(vararg sections: Pair<DiagSection, List<dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem>>) =
    DiagnosticsSnapshot(sections.toMap(), recentEvents = 0)

  private val sample = snapshot(
    DiagSection.NETWORK_AND_NAS to listOf(
      item(
        DiagCodes.NET_FOREIGN_VPN,
        severity = DiagSeverity.ERROR,
        export = mapOf(
          "ipv6" to ExportValue.Flag(true),
          "count" to ExportValue.Num(42),
          "elapsed" to ExportValue.Dur.of(250.milliseconds),
          "bucket" to ExportValue.Enum.of(Bucket.RESTRICTED),
          "account" to testHasher.hash("mario.rossi"),
        ),
      ),
      item(DiagCodes.NET_PLACE, severity = DiagSeverity.OK),
    ),
    DiagSection.TRUST to listOf(item(DiagCodes.TRUST_APPROVED, DiagSection.TRUST, DiagSeverity.OK)),
  )

  @Test
  fun ilFileEJsonVeroEPortaLeVociConIlLoroCodice() {
    val text = DiagnosticsExporter.render(sample, ExportEnvironment(sdkInt = 35)).text

    val json = JSONObject(text)
    assertThat(json.getString("format")).isEqualTo(DiagnosticsExporter.FORMAT)
    assertThat(json.getInt("version")).isEqualTo(DiagnosticsExporter.VERSION)
    assertThat(json.getInt("sdk")).isEqualTo(35)
    val network = json.getJSONObject("sections").getJSONArray("NETWORK_AND_NAS")
    assertThat(network.length()).isEqualTo(2)
    assertThat(network.getJSONObject(0).getString("code")).isEqualTo("NET_FOREIGN_VPN")
    assertThat(network.getJSONObject(0).getString("severity")).isEqualTo("ERROR")
    assertThat(json.getJSONObject("sections").has("STORAGE")).isFalse()
  }

  @Test
  fun iValoriHannoUnaFormaPerTipo() {
    val values = JSONObject(DiagnosticsExporter.render(sample).text)
      .getJSONObject("sections").getJSONArray("NETWORK_AND_NAS").getJSONObject(0).getJSONObject("values")

    assertThat(values.getBoolean("ipv6")).isTrue()
    assertThat(values.getLong("count")).isEqualTo(42L)
    assertThat(values.getString("elapsed")).isEqualTo("250ms")
    assertThat(values.getString("bucket")).isEqualTo("Bucket.RESTRICTED")
    assertThat(values.getString("account")).matches("#[0-9a-f]{12}")
  }

  @Test
  fun unaVoceSenzaValoriNonHaLaChiaveValues() {
    val text = DiagnosticsExporter.render(sample).text

    val trust = JSONObject(text).getJSONObject("sections").getJSONArray("TRUST").getJSONObject(0)
    assertThat(trust.has("values")).isFalse()
  }

  @Test
  fun ilFileEDeterministicoELeChiaviSonoInOrdine() {
    val first = DiagnosticsExporter.render(sample).text
    val second = DiagnosticsExporter.render(sample).text

    assertThat(second).isEqualTo(first)
    val line = first.lines().first { "NET_FOREIGN_VPN" in it }
    val keys = Regex("\"([a-z][A-Za-z0-9_.]*)\": ").findAll(line.substringAfter("\"values\"")).map { it.groupValues[1] }.toList()
    assertThat(keys).isInOrder()
    assertThat(keys).containsExactly("account", "bucket", "count", "elapsed", "ipv6")
  }

  @Test
  fun unaSnapshotVuotaDaUnFileValido() {
    val document = DiagnosticsExporter.render(DiagnosticsSnapshot.EMPTY)

    assertThat(JSONObject(document.text).getJSONObject("sections").length()).isEqualTo(0)
    assertThat(document.itemCount).isEqualTo(0)
    assertThat(DiagnosticsExporter.conforms(document.text)).isTrue()
  }

  @Test
  fun leRigheDellAnteprimaSonoLeRigheDelFile() {
    val document = DiagnosticsExporter.render(sample)

    assertThat(document.lines.joinToString("\n") + "\n").isEqualTo(document.text)
    assertThat(document.itemCount).isEqualTo(3)
  }

  @Test
  fun unFileManomessoNonRispettaLaGrammatica() {
    val clean = DiagnosticsExporter.render(sample).text
    assertThat(DiagnosticsExporter.conforms(clean)).isTrue()

    // Testo libero al posto di un valore: un nome, un IP, un percorso, un utente.
    listOf(
      """"ssid": "CasaRossi"""",
      """"ip": "192.168.178.58"""",
      """"user": "mario.rossi"""",
      """"path": "/Backup/Telefono di Mario"""",
      """"name": "Telefono di Mario"""",
      """"code": "x"""",
    ).forEach { pair ->
      val tampered = clean.replace("\"count\": 42", pair)
      assertThat(DiagnosticsExporter.conforms(tampered)).isFalse()
    }
    // Una riga in piu' che non e' nella grammatica.
    assertThat(DiagnosticsExporter.conforms(clean.replace("{\n  \"format\"", "{\n  \"note\": \"ciao\",\n  \"format\""))).isFalse()
    assertThat(DiagnosticsExporter.conforms(clean + "ciao\n")).isFalse()
    assertThat(DiagnosticsExporter.conforms(clean.trimEnd())).isFalse()
    assertThat(DiagnosticsExporter.conforms(clean.replace("\n", "\r\n"))).isFalse()
  }

  @Test
  fun unIndirizzoNonPassaPerUnaCostanteDiEnum() {
    // «192.168.178.58» ha la forma di una parola con i punti: la grammatica pretende Tipo.COSTANTE.
    val clean = DiagnosticsExporter.render(sample).text
    val tampered = clean.replace("\"Bucket.RESTRICTED\"", "\"192.168.178.58\"")
    assertThat(DiagnosticsExporter.conforms(tampered)).isFalse()
    assertThat(DiagnosticsExporter.conforms(clean.replace("\"Bucket.RESTRICTED\"", "\"mario.rossi\""))).isFalse()
    assertThat(DiagnosticsExporter.conforms(clean.replace("\"Bucket.RESTRICTED\"", "\"Mario.Rossi\""))).isFalse()
  }

  @Test
  fun unValoreRiletoDalDiscoCheNonHaLaFormaDiUnEnumVieneLasciatoFuori() {
    // L'anello degli eventi si rilegge da un file: `Enum.restore` accetta qualunque coppia di identificatori, e
    // un file manomesso potrebbe farne passare una che e' un nome. L'esportatore la scarta.
    val restored = dev.pampa.mycloudgallery.core.common.diagnostics.FileDiagnosticsLogStore(
      java.io.File.createTempFile("events", ".log").apply {
        writeText("1|NET_SAMPLE|INFO|owner=eMario.Rossi;count=n3\n")
        deleteOnExit()
      },
    ).load().single()
    assertThat(restored.values["owner"]).isNotNull() // la rilettura l'ha accettato...

    val text = DiagnosticsExporter.render(
      snapshot(DiagSection.RECENT_EVENTS to listOf(item(DiagCodes.EVT_GENERIC, DiagSection.RECENT_EVENTS, export = restored.values))),
    ).text

    assertThat(text).doesNotContain("Mario")
    assertThat(text).doesNotContain("Rossi")
    assertThat(text).contains("\"count\": 3") // ...ma il resto della voce c'e'
  }

  @Test
  fun unaDurataNegativaNonSiEsporta() {
    val text = DiagnosticsExporter.render(
      snapshot(DiagSection.STORAGE to listOf(item(DiagCodes.STORAGE_USAGE, DiagSection.STORAGE, export = mapOf("age" to ExportValue.Dur(-5))))),
    ).text

    assertThat(text).doesNotContain("-5ms")
    assertThat(DiagnosticsExporter.conforms(text)).isTrue()
  }
}
