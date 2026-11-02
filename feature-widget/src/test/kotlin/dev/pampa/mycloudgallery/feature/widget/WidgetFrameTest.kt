package dev.pampa.mycloudgallery.feature.widget

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.describe.rules.MediaDescribers
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.widget.contract.WidgetCell
import dev.pampa.mycloudgallery.core.widget.contract.WidgetEmpty
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMode
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlan
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTitle
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetClock
import dev.pampa.mycloudgallery.core.widget.testing.InMemoryWidgetLedger
import dev.pampa.mycloudgallery.feature.widget.contract.WidgetUiState
import dev.pampa.mycloudgallery.feature.widget.frame.Prepared
import dev.pampa.mycloudgallery.feature.widget.frame.WidgetPreparer
import dev.pampa.mycloudgallery.feature.widget.frame.WidgetSizing
import dev.pampa.mycloudgallery.feature.widget.frame.WidgetTexts
import dev.pampa.mycloudgallery.feature.widget.host.WidgetPlaceNames
import dev.pampa.mycloudgallery.feature.widget.host.WidgetThumbnails
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Dal piano al fotogramma (Fase 10, P3, D4, D7, D8, D22): i testi (titolo del ricordo "Un anno fa · Oslo", "3 anni fa", l'etichetta del viaggio, "Il meglio di settembre"; mai
 * un nome, ripuliti), le descrizioni della superficie `WIDGET` (solo tipo e data), la misura dalle opzioni del launcher, la preparazione con i tetti di D7.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetFrameTest {

  private val context: Application get() = ApplicationProvider.getApplicationContext()
  private val slot = WidgetSlot(3)

  private fun texts(places: WidgetPlaceNames = WidgetPlaceNames { if (it == WidgetTestKit.OSLO) "Oslo" else null }) = WidgetTexts(context.resources, places)

  // --- I testi -------------------------------------------------------------------------------------------------------------------------

  @Test
  fun ilTitoloDelRicordo_ComeLaNotifica() {
    val t = texts()
    fun title(years: Int?, places: List<Int> = emptyList(), label: String? = null, month: YearMonth? = null) = t.compose(WidgetTitle(years, places, label, month))
    assertThat(title(1, listOf(WidgetTestKit.OSLO))).isEqualTo("Un anno fa · Oslo")
    assertThat(title(3, listOf(WidgetTestKit.OSLO))).isEqualTo("3 anni fa · Oslo")
    assertThat(title(1)).isEqualTo("Un anno fa")
    assertThat(title(5)).isEqualTo("5 anni fa")
    // Un luogo che non si conosce: solo gli anni (il primo luogo che si conosce vince).
    assertThat(title(2, listOf(999_999))).isEqualTo("2 anni fa")
    assertThat(title(2, listOf(999_999, WidgetTestKit.OSLO))).isEqualTo("2 anni fa · Oslo")
    assertThat(title(null, label = "Oslo, marzo 2025")).isEqualTo("Oslo, marzo 2025")
    assertThat(title(2, label = "Oslo, marzo 2025")).isEqualTo("2 anni fa · Oslo, marzo 2025")
    assertThat(title(null, month = YearMonth.of(2025, 9))).isEqualTo("Il meglio di settembre")
    assertThat(title(null)).isEqualTo("Un ricordo per te")
  }

  @Test
  fun unaEtichettaOstileSiRipulisce() {
    val t = texts()
    val label = "Viaggio‮evil\u0007  con\n\tspazi​" + "x".repeat(100)
    val text = t.compose(WidgetTitle(null, emptyList(), label.take(80), null))
    assertThat(text).doesNotContain("‮")
    assertThat(text).doesNotContain("\u0007")
    assertThat(text).doesNotContain("​")
    assertThat(text).doesNotContain("\n")
    assertThat(text).doesNotContain("  ")
    assertThat(text.length).isAtMost(WidgetTexts.MAX_LENGTH)
    // Un'etichetta fatta solo di controlli non e' un titolo: il testo generico.
    assertThat(t.compose(WidgetTitle(null, emptyList(), "\u0007‮ ", null))).isEqualTo("Un ricordo per te")
    // Il nome di un luogo ostile si ripulisce allo stesso modo.
    val hostile = texts(WidgetPlaceNames { "Os‮lo\n" })
    assertThat(hostile.compose(WidgetTitle(1, listOf(1), null, null))).isEqualTo("Un anno fa · Oslo")
  }

  @Test
  fun iMessaggi() {
    val t = texts()
    assertThat(t.message(WidgetEmpty.NOT_CONFIGURED)).isEqualTo("Configura MyCloud Gallery")
    for (reason in WidgetEmpty.entries.filter { it != WidgetEmpty.NOT_CONFIGURED }) assertThat(t.message(reason)).isEqualTo("Apri MyCloud Gallery")
    assertThat(t.cellFallback(MediaKind.PHOTO)).isEqualTo("Foto")
    assertThat(t.cellFallback(MediaKind.VIDEO)).isEqualTo("Video")
  }

  // --- Il fotogramma -------------------------------------------------------------------------------------------------------------------

  private fun prepared(plan: WidgetPlan) = Prepared(plan, plan.cells.map { thumb() })

  @Test
  fun leDescrizioniDelWidgetDiconoSoloTipoEData() {
    val plan = WidgetPlan(
      slot, WidgetSize.WIDE, WidgetMode.Photos,
      listOf(
        WidgetCell(0, mediaId(0), MediaKind.PHOTO, 20251031),
        WidgetCell(1, mediaId(1), MediaKind.VIDEO, 20240312),
        // Un giorno che non e' una data vera: solo il tipo.
        WidgetCell(2, mediaId(2), MediaKind.PHOTO, 20251399),
      ),
      epoch = 1,
    )
    val frame = WidgetPreparer.frame(prepared(plan), MediaDescribers.shared(), texts())
    assertThat(frame.cells[0].description).isEqualTo("Foto, 31 ottobre 2025")
    assertThat(frame.cells[1].description).startsWith("Video")
    assertThat(frame.cells[1].description).contains("12 marzo 2024")
    assertThat(frame.cells[2].description).isEqualTo("Foto")
    // Niente luogo, ora, persona, testo, durata, ne' id.
    for (cell in frame.cells) {
      assertThat(cell.description).doesNotContain(":")
      assertThat(cell.description).doesNotContain(mediaId(0).toString())
    }
    assertThat(frame.title).isNull()
    assertThat(frame.message).isNull()
  }

  @Test
  fun ilTitoloCe_SoloPerUnRicordo_MaiConIlBlocco() {
    val memory = MemoryIds.of(MemoryKey.Month(YearMonth.of(2025, 9)))
    val cells = List(4) { WidgetCell(it, mediaId(it), MediaKind.PHOTO, 20251031) }
    val withMemory = WidgetPreparer.frame(prepared(WidgetPlan(slot, WidgetSize.WIDE, WidgetMode.Memory(memory, WidgetTestKit.memoryTitle), cells, 1)), MediaDescribers.shared(), texts())
    assertThat(withMemory.title).isEqualTo("Un anno fa · Oslo")
    assertThat(withMemory.state).isInstanceOf(WidgetUiState.Memory::class.java)
    val locked = WidgetPreparer.frame(prepared(WidgetPlan(slot, WidgetSize.WIDE, WidgetMode.Locked, cells, 1)), MediaDescribers.shared(), texts())
    assertThat(locked.title).isNull()
    assertThat(locked.state).isInstanceOf(WidgetUiState.Locked::class.java)
    val message = WidgetPreparer.frame(Prepared(WidgetPlan(slot, WidgetSize.WIDE, WidgetMode.Message(WidgetEmpty.NO_MEDIA), emptyList(), 1), emptyList()), MediaDescribers.shared(), texts())
    assertThat(message.message).isEqualTo("Apri MyCloud Gallery")
    assertThat(message.cells).isEmpty()
    // Il fotogramma non porta mai testi nel toString (D39).
    assertThat(withMemory.toString()).doesNotContain("Oslo")
    assertThat(withMemory.cells.first().toString()).doesNotContain("Foto")
  }

  @Test
  fun lePreparazioneNonDecodificaUnPianoSenzaFotoEIlNotReadyNonSiDisegna() = runBlocking {
    var loads = 0
    val thumbs = WidgetThumbnails { _, _ ->
      loads++
      thumb()
    }
    val message = FakeServices(ScriptedPlanner(mutableListOf()) { s, size, _ -> WidgetPlan(s, size, WidgetMode.Message(WidgetEmpty.NO_MEDIA), emptyList(), 1) }, InMemoryWidgetLedger(), thumbs, clock = FakeWidgetClock())
    val prepared = WidgetPreparer(message).prepare(slot, WidgetSize.SMALL, 2_000)
    assertThat(prepared?.plan?.mode).isEqualTo(WidgetMode.Message(WidgetEmpty.NO_MEDIA))
    assertThat(loads).isEqualTo(0)
    val notReady = FakeServices(ScriptedPlanner(mutableListOf()) { s, size, _ -> WidgetPlan(s, size, WidgetMode.Message(WidgetEmpty.NOT_READY), emptyList(), 1) }, InMemoryWidgetLedger(), thumbs)
    assertThat(WidgetPreparer(notReady).prepare(slot, WidgetSize.SMALL, 2_000)).isNull()
  }

  // --- La misura -----------------------------------------------------------------------------------------------------------------------

  @Test
  fun laMisuraDalleOpzioniDelLauncher() {
    // Il minimo 2x2 e' la piccola, 4x2 la larga, 4x4 la grande (una cella da 70n - 30 dp).
    assertThat(WidgetSizing.classify(110, 110)).isEqualTo(WidgetSize.SMALL)
    assertThat(WidgetSizing.classify(180, 180)).isEqualTo(WidgetSize.SMALL)
    assertThat(WidgetSizing.classify(250, 110)).isEqualTo(WidgetSize.WIDE)
    assertThat(WidgetSizing.classify(320, 160)).isEqualTo(WidgetSize.WIDE)
    assertThat(WidgetSizing.classify(250, 250)).isEqualTo(WidgetSize.LARGE)
    assertThat(WidgetSizing.classify(360, 400)).isEqualTo(WidgetSize.LARGE)
    assertThat(WidgetSizing.classify(0, 0)).isEqualTo(WidgetSize.SMALL)
    // In verticale: larghezza = minimo, altezza = massimo; in orizzontale il contrario.
    assertThat(WidgetSizing.fromOptions(minWidthDp = 110, maxWidthDp = 250, minHeightDp = 110, maxHeightDp = 250, portrait = true)).isEqualTo(110 to 250)
    assertThat(WidgetSizing.fromOptions(minWidthDp = 110, maxWidthDp = 250, minHeightDp = 110, maxHeightDp = 250, portrait = false)).isEqualTo(250 to 110)
    assertThat(WidgetSizing.fromOptions(0, 0, 0, 0, portrait = true)).isEqualTo(0 to 0)
  }
}
