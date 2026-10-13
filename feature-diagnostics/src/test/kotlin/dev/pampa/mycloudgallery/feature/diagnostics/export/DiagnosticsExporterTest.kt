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
          "ipv6Global" to ExportValue.Flag(true),
          "total" to ExportValue.Num(42),
          "elapsed" to ExportValue.Dur.of(250.milliseconds),
          "standbyBucket" to ExportValue.Enum.of(Bucket.RESTRICTED),
          "gateway" to testHasher.hash("mario.rossi"),
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

    assertThat(values.getBoolean("ipv6Global")).isTrue()
    assertThat(values.getLong("total")).isEqualTo(42L)
    assertThat(values.getString("elapsed")).isEqualTo("250ms")
    assertThat(values.getString("standbyBucket")).isEqualTo("Bucket.RESTRICTED")
    assertThat(values.getString("gateway")).matches("#[0-9a-f]{12}")
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
    assertThat(keys).containsExactly("elapsed", "gateway", "ipv6Global", "standbyBucket", "total")
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
      val tampered = clean.replace("\"total\": 42", pair)
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
    // L'anello degli eventi si rilegge da un file, che un'app manomessa potrebbe riscrivere con un "enum" che e' un
    // nome. Dal P9 lo scarta gia' la rilettura (`Enum.restore` accetta solo la forma di un enum dell'app)...
    val file = java.io.File.createTempFile("events", ".log").apply {
      writeText("1|NET_SAMPLE|INFO|owner=eMario.Rossi;total=n3\n2|NET_SAMPLE|INFO|total=n4\n")
      deleteOnExit()
    }
    val restored = dev.pampa.mycloudgallery.core.common.diagnostics.FileDiagnosticsLogStore(file).load()
    assertThat(restored.map { it.at }).containsExactly(2L)

    // ...e l'esportatore resta una seconda difesa: una costante che non ha quella forma non si esporta.
    val text = DiagnosticsExporter.render(
      snapshot(
        DiagSection.RECENT_EVENTS to listOf(
          item(DiagCodes.EVT_GENERIC, DiagSection.RECENT_EVENTS, export = restored.single().values),
        ),
      ),
    ).text
    assertThat(text).doesNotContain("Mario")
    assertThat(text).doesNotContain("Rossi")
    assertThat(text).contains("\"total\": 4")
  }

  @Test
  fun unaDurataNegativaNonSiEsporta() {
    val text = DiagnosticsExporter.render(
      snapshot(DiagSection.STORAGE to listOf(item(DiagCodes.STORAGE_USAGE, DiagSection.STORAGE, export = mapOf("lastExitAge" to ExportValue.Dur(-5))))),
    ).text

    assertThat(text).doesNotContain("-5ms")
    assertThat(DiagnosticsExporter.conforms(text)).isTrue()
  }

  /**
   * Revisione 2, F4: `DiagCode` ha un costruttore pubblico e la sola regex della forma: un codice costruito da un dato
   * (`DiagCode(utente.uppercase())`) la supera. Esce solo un codice del catalogo; gli altri come UNKNOWN, senza valori.
   */
  @Test
  fun unCodiceFuoriCatalogoEsceComeUnknownSenzaValori() {
    val text = DiagnosticsExporter.render(
      snapshot(
        DiagSection.NETWORK_AND_NAS to listOf(
          item(
            dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode("MARIO_ROSSI"),
            export = mapOf("total" to ExportValue.Num(3232281146)),
          ),
        ),
      ),
    ).text

    assertThat(text).doesNotContain("MARIO")
    assertThat(text).doesNotContain("3232281146")
    val entry = JSONObject(text).getJSONObject("sections").getJSONArray("NETWORK_AND_NAS").getJSONObject(0)
    assertThat(entry.getString("code")).isEqualTo(DiagnosticsExporter.UNKNOWN_CODE)
    assertThat(entry.has("values")).isFalse()
    assertThat(DiagnosticsExporter.conforms(text)).isTrue()
  }

  /**
   * Revisione 2, F4: anche le chiavi sono un elenco chiuso. `"share.${nome}"` o `"mario.rossi"` hanno la forma di una
   * chiave, e un numero puo' portare un IPv4: fuori elenco non si esportano.
   */
  @Test
  fun unaChiaveFuoriElencoNonSiEsporta() {
    val text = DiagnosticsExporter.render(
      snapshot(
        DiagSection.STORAGE to listOf(
          item(
            DiagCodes.STORAGE_USAGE,
            DiagSection.STORAGE,
            export = mapOf(
              "mario.rossi" to ExportValue.Num(1),
              "share.mario" to ExportValue.Num(3232281146),
              "ula9" to ExportValue.Flag(true),
              "rejected.Mario" to ExportValue.Num(2),
              "databaseBytes" to ExportValue.Num(4096),
            ),
          ),
        ),
      ),
    ).text

    assertThat(text).doesNotContain("mario")
    assertThat(text).doesNotContain("Mario")
    assertThat(text).doesNotContain("3232281146")
    assertThat(text).doesNotContain("ula9")
    assertThat(text).contains("\"databaseBytes\": 4096")
    assertThat(ExportKeys.isAllowed("ula0")).isTrue()
    assertThat(ExportKeys.isAllowed("rejected.bad_signature")).isTrue()
  }
}
