package dev.pampa.mycloudgallery.debugtools.probes

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Fase 5B (P0-B): il resoconto delle prove guidate e' fatto di **soli numeri**. Qui si prova che: le chiavi sono un insieme chiuso e ben formato; ogni
 * riga e' `chiave=numero`; nessun testo libero entra (nemmeno da un file toccato a mano); i numeri sopravvivono alla morte del processo (si
 * riapre lo store dalla stessa cartella); le misure ripetute hanno un indice; gli indirizzi delle condivisioni restano fuori dal resoconto.
 */
class FieldProbeReportTest {

  @get:Rule val temp = TemporaryFolder()

  private val dir: File get() = File(temp.root, "field-probe")

  @Test
  fun leChiaviSonoUnInsiemeChiusoEBenFormato() {
    val codes = FieldProbeKey.entries.map { it.code }
    assertThat(codes).containsNoDuplicates()
    for (code in codes) {
      assertThat(FieldProbeKey.isValidCode(code)).isTrue()
      assertThat(code).matches("(env|t09|t14|t15|t16)\\.[a-z0-9_]+")
    }
    // Nessuna chiave ha un nome di persona, di file, di percorso o di account nel suo codice.
    for (code in codes) assertThat(code).doesNotContainMatch("(mail|nome|path|percorso|account|share|uri|sha|hash)")
    assertThat(FieldProbeKey.isValidCode("T09.Maiuscole")).isFalse()
    assertThat(FieldProbeKey.isValidCode("con spazio")).isFalse()
    assertThat(FieldProbeKey.isValidCode("")).isFalse()
  }

  @Test
  fun ilResocontoHaUnaRigaChiaveNumeroPerMisuraInOrdine() {
    val store = FieldProbeStore.forTest(dir)
    store.set(FieldProbeKey.T14_SERVE_READS, 12)
    store.add(FieldProbeKey.T15_RECEIVED)
    store.add(FieldProbeKey.T15_RECEIVED)
    store.max(FieldProbeKey.T14_SERVE_DURATION_MS, 300)
    store.max(FieldProbeKey.T14_SERVE_DURATION_MS, 100) // il massimo non scende
    store.setIfAbsent(FieldProbeKey.T14_SERVE_FIRST_READ_MS, 40)
    store.setIfAbsent(FieldProbeKey.T14_SERVE_FIRST_READ_MS, 99) // la prima misura resta
    store.set(FieldProbeKey.T09_DIFF_MISSING, 1, index = 1)
    store.set(FieldProbeKey.T09_DIFF_MISSING, 0, index = 2)

    val report = store.render()
    assertThat(report.lines()).containsExactly(
      "t09.diff_missing.1=1",
      "t09.diff_missing.2=0",
      "t14.serve_duration_ms=300",
      "t14.serve_first_read_ms=40",
      "t14.serve_reads=12",
      "t15.received=2",
    ).inOrder()
    assertThat(FieldProbeReport.isNumbersOnly(report)).isTrue()
    assertThat(store.nextIndex(FieldProbeKey.T09_DIFF_MISSING)).isEqualTo(3)
    assertThat(store.nextIndex(FieldProbeKey.T16_VIEW_ROW_CHANGED)).isEqualTo(1)
  }

  @Test
  fun ilRendererRifiutaOgniRigaCheNonSiaUnNumero() {
    assertThat(FieldProbeReport.isNumbersOnly("t09.base_rows=302\nt14.form=2")).isTrue()
    assertThat(FieldProbeReport.isNumbersOnly("t09.base_rows=302\nIMG_1.jpg=1")).isFalse()
    assertThat(FieldProbeReport.isNumbersOnly("t09.base_rows=tanti")).isFalse()
    assertThat(FieldProbeReport.isNumbersOnly("t09.base_rows=1.5")).isFalse()
    assertThat(FieldProbeReport.isNumbersOnly("/storage/emulated/0/DCIM=3")).isFalse()
    assertThat(FieldProbeReport.isNumbersOnly("")).isTrue()
    assertThrows(IllegalStateException::class.java) { FieldProbeReport.render(mapOf("Nome Con Spazi" to 1L)) }
    assertThrows(IllegalStateException::class.java) { FieldProbeReport.render(mapOf("percorso/del/file" to 1L)) }
  }

  @Test
  fun sopravvivonoAllaMorteDelProcessoEUnFileToccatoAManoNonFaEntrareTesto() {
    val first = FieldProbeStore.forTest(dir)
    first.set(FieldProbeKey.T15_REOPEN_OK, 7)
    first.add(FieldProbeKey.T15_RECEIVED, 3)
    // Un altro avvio: stessa cartella, stato nuovo in memoria.
    File(dir, "counters.properties").appendText("estraneo.chiave=5\nnome file.jpg=1\nt09.base_rows=non-un-numero\n")
    val second = FieldProbeStore.forTest(dir)
    assertThat(second.get(FieldProbeKey.T15_REOPEN_OK)).isEqualTo(7L)
    assertThat(second.get(FieldProbeKey.T15_RECEIVED)).isEqualTo(3L)
    val report = second.render()
    assertThat(FieldProbeReport.isNumbersOnly(report)).isTrue()
    assertThat(report).doesNotContain("estraneo")
    assertThat(report).doesNotContain("nome file")
    assertThat(report).doesNotContain("non-un-numero")
    second.resetNumbers()
    assertThat(second.render()).isEmpty()
    assertThat(FieldProbeStore.forTest(dir).render()).isEmpty()
  }

  @Test
  fun gliIndirizziDelleCondivisioniNonEntranoMaiNelResoconto() {
    val store = FieldProbeStore.forTest(dir)
    store.addShares(listOf("content://com.example.secret/documento-privato-1", "content://com.example.secret/documento-privato-2"), receivedAtElapsedMs = 1_000)
    assertThat(store.shares().map { it.uri }).hasSize(2)
    assertThat(store.shares().first().toString()).doesNotContain("secret")
    assertThat(store.render()).doesNotContain("secret")
    assertThat(File(dir, "counters.properties").exists() && File(dir, "counters.properties").readText().contains("secret")).isFalse()
    store.clearShares()
    assertThat(store.shares()).isEmpty()
    // Lo stesso processo ha lo stesso numero; un altro avvio ne avrebbe uno diverso.
    assertThat(store.processMarker).isEqualTo(FieldProbeStore.forTest(dir).processMarker)
  }

  @Test
  fun iChiamantiSonoSoloNumeri() {
    val codes = FieldProbeCaller.entries.map { it.code }
    assertThat(codes).containsNoDuplicates()
    assertThat(FieldProbeCaller.codeOf("com.google.android.gm")).isEqualTo(1L)
    assertThat(FieldProbeCaller.codeOf("com.whatsapp")).isEqualTo(2L)
    assertThat(FieldProbeCaller.codeOf(null)).isEqualTo(0L)
    assertThat(FieldProbeCaller.codeOf("")).isEqualTo(0L)
    // Un'app che non e' in elenco diventa "altro": il suo nome non lascia l'elenco.
    assertThat(FieldProbeCaller.codeOf("com.esempio.sconosciuta")).isEqualTo(99L)
  }
}
