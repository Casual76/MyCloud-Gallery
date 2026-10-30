package dev.pampa.mycloudgallery.core.memories.plan

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.Memories
import dev.pampa.mycloudgallery.core.memories.contract.MemoriesContract
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPixels
import dev.pampa.mycloudgallery.core.memories.contract.PixelProbe
import dev.pampa.mycloudgallery.core.memories.pick.MemoryPhotoPicker
import dev.pampa.mycloudgallery.core.memories.pick.PickSignalsLoader
import dev.pampa.mycloudgallery.core.memories.pick.VisiblePhotoPicker
import dev.pampa.mycloudgallery.core.model.MediaId
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * Il motore con la scelta vera di P7 al passo 5 (docs/fase9-progetto.md §2.1, §2.2, D6, D31; P6 + P7): la suite di contratto del servizio, e due
 * cose che solo il raccoglitore dei segnali sa fare: le misure dei pixel **solo per i 200 finalisti** del ricordo (il mese da 45 giorni ne ha oltre 1.000
 * candidati) e una volta per ricordo.
 */
class MemoryPhotoChoiceTest : MemoriesContract() {

  private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
  private val rigs = ArrayList<EngineRig>()

  @After
  fun chiude() {
    scope.cancel()
    rigs.forEach { it.close() }
  }

  /** La suite di contratto con la scelta di P7 (e la maschera dei Nascosti di P7 sopra i fake). */
  override fun memories(parts: Parts): Memories = MemoryEngine(
    library = visibilityOf(parts.visible, parts.mask),
    personal = parts.personal,
    store = parts.store,
    clock = parts.clock,
    scope = scope,
    planner = IndexedPlanner(ReferencePeople),
    trips = parts.trips,
    people = parts.people,
    dispatcher = Dispatchers.Unconfined,
    choice = MemoryPhotoChoice(PickSignalsLoader(people = parts.people), VisiblePhotoPicker(MemoryPhotoPicker(), parts.mask)),
  )

  /** Il mese di una Libreria da 100 giorni di 15 scatti: oltre 1.000 candidati, ma le misure dei pixel sono chieste al piu' per 200 (D6). */
  @Test
  fun lePixelSiMisuranoSoloPerIFinalisti() = runTest {
    val shots = (1..30).flatMap { day -> dayShots(LocalDate.of(2026, 10, day), 40, series = 100L + day, everyMinutes = 7) }
    val asked = ArrayList<Int>()
    val pixels = MemoryPixels { ids, _ ->
      asked += ids.size
      emptyMap<MediaId, PixelProbe>()
    }
    val visibility = visibilityOver(shots)
    val rig = EngineRig(
      visibility = visibility, today = LocalDate.of(2026, 11, 3), pixels = pixels,
      choice = MemoryPhotoChoice(PickSignalsLoader(pixels = pixels), VisiblePhotoPicker(MemoryPhotoPicker(), visibility)),
    ).also { rigs += it }
    val report = rig.refresh()
    assertThat(report.published).isEqualTo(1)
    assertThat(rig.engine.current()!!.memories.single().kind).isEqualTo(dev.pampa.mycloudgallery.core.memories.contract.MemoryKind.MONTH)
    assertThat(asked).containsExactly(PickSignalsLoader.PIXEL_FINALISTS)
    // Senza vettori il giro lo dice (E-MEM16).
    assertThat(report.withoutVectors).isTrue()
    val calls = AtomicInteger(asked.size)
    assertThat(calls.get()).isEqualTo(1)
  }
}
