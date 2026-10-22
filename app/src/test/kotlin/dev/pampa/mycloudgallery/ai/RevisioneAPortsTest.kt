package dev.pampa.mycloudgallery.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.compute.AiInputOrigin
import dev.pampa.mycloudgallery.core.ai.compute.AreaView
import dev.pampa.mycloudgallery.core.ai.compute.DeviceRoleView
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Revisione A della Fase 6 nelle porte di :app senza grafo: i segni "non supportato" con la provenienza e la scadenza (R5, IX-7), la
 * memoria delle compattazioni (R14) e le aree senza indicizzatori (PR-1).
 */
class RevisioneAPortsTest {

  @get:Rule val tmp = TemporaryFolder()

  private fun key(n: Int): ContentKey = ContentKey.of(QuickHash("%064x".format(n)), 1_000L + n)

  @Test
  fun r5_unSegnoNatoDallAnteprimaDiUnAltroValeSoloNellaSuaShareESiTogliaConLaRevoca() = runBlocking<Unit> {
    var now = 1_000_000L
    val file = File(tmp.root, FileAiUnsupported.FILE)
    val marks = FileAiUnsupported(file) { now }
    marks.mark(key(1), AiClass.VEC, AiInputOrigin.Preview("Comune", "dev-b"))
    marks.mark(key(2), AiClass.OCR, AiInputOrigin.Own)

    assertThat(marks.classesOf(listOf(key(1)), "comune")).containsExactly(key(1), setOf(AiClass.VEC))
    assertThat(marks.classesOf(listOf(key(1)), "privata")).isEmpty()
    // Il telefono (i propri byte) non eredita il segno di un'anteprima altrui.
    assertThat(marks.classesOf(listOf(key(1)))).isEmpty()
    // Un segno dai propri byte vale ovunque.
    assertThat(marks.classesOf(listOf(key(2)), "comune")).containsExactly(key(2), setOf(AiClass.OCR))
    assertThat(marks.classesOf(listOf(key(2)))).containsExactly(key(2), setOf(AiClass.OCR))

    // Il file non porta il nome della share.
    assertThat(file.readText()).doesNotContain("omune")
    // Si rilegge uguale.
    assertThat(FileAiUnsupported(file) { now }.classesOf(listOf(key(1)), "COMUNE")).containsExactly(key(1), setOf(AiClass.VEC))

    marks.forgetDevice("dev-b")
    assertThat(marks.classesOf(listOf(key(1)), "comune")).isEmpty()
    assertThat(FileAiUnsupported(file) { now }.classesOf(listOf(key(1)), "comune")).isEmpty()
    assertThat(marks.classesOf(listOf(key(2)))).isNotEmpty()

    // IX-7: ogni segno scade e il media si riprova.
    now += FileAiUnsupported.EXPIRY_MILLIS
    assertThat(marks.classesOf(listOf(key(2)))).isEmpty()
  }

  @Test
  fun ix7_iSegniDiPrimaSenzaProvenienzaNonValgonoPiu() {
    val file = File(tmp.root, FileAiUnsupported.FILE)
    file.writeText("${key(3).hex} vec\n")
    assertThat(FileAiUnsupported(file).classesOf(listOf(key(3)))).isEmpty()
  }

  @Test
  fun r14_laMemoriaDelleCompattazioniSiRileggeESenzaNomiDiShare() {
    val file = File(tmp.root, FileCompactionMemory.FILE)
    FileCompactionMemory(file).done("Comune", 42L)
    assertThat(FileCompactionMemory(file).last("comune")).isEqualTo(42L)
    assertThat(FileCompactionMemory(file).last("altra")).isNull()
    assertThat(file.readText()).doesNotContain("omune")
  }

  private fun coverage(
    devices: List<DeviceRoleView>?,
    now: Long? = NOW,
    told: MutableList<List<AreaView>> = mutableListOf(),
  ) = AppCoverage(
    areas = { listOf(AreaView("privata-anna", "anna"), AreaView("Comune", null)) },
    devices = { devices },
    nasNow = { now },
    catalog = { null },
    indexerHere = { false },
    modelReadyHere = { true },
    unsupported = { null },
    readingOff = { false },
    told = { told += it },
  )

  @Test
  fun pr1_leAreeSenzaIndicizzatoriVengonoDagliStatiDeiDispositivi() = runBlocking<Unit> {
    val told = mutableListOf<List<AreaView>>()
    // Solo un indicizzatore di un altro account, visto ieri: l'area comune e' coperta, la privata no (D14).
    val other = DeviceRoleView("papa", indexer = true, lastSeenNasMillis = NOW - DAY)
    val me = DeviceRoleView("anna", indexer = false, lastSeenNasMillis = NOW)
    val partial = coverage(listOf(other, me), told = told)
    assertThat(partial.areasWithoutIndexer()).containsExactly(false)
    assertThat(partial.areasWithoutIndexer()).containsExactly(false)
    // La Diagnostica lo sa una volta sola.
    assertThat(told).hasSize(1)

    // Nessun indicizzatore vivo (l'ultimo visto 8 giorni fa): tutte e due.
    val stale = DeviceRoleView("anna", indexer = true, lastSeenNasMillis = NOW - 8 * DAY)
    assertThat(coverage(listOf(stale, me)).areasWithoutIndexer()).containsExactly(false, true)

    // Un indicizzatore dello stesso account copre tutto.
    assertThat(coverage(listOf(DeviceRoleView("Anna", true, NOW))).areasWithoutIndexer()).isEmpty()

    // Senza stati letti o senza l'ora del NAS non si dice niente (mai un'ipotesi).
    assertThat(coverage(null).areasWithoutIndexer()).isEmpty()
    assertThat(coverage(listOf(me), now = null).areasWithoutIndexer()).isEmpty()
  }

  private companion object {
    const val NOW = 1_800_000_000_000L
    const val DAY = 24L * 60 * 60 * 1000
  }
}
