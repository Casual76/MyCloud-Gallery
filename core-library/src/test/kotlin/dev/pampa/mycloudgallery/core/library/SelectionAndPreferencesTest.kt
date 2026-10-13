package dev.pampa.mycloudgallery.core.library

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.PositionIds
import dev.pampa.mycloudgallery.core.library.contract.SelectionModel
import dev.pampa.mycloudgallery.core.library.data.DataStoreLibraryPreferences
import dev.pampa.mycloudgallery.core.model.MediaId
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

/** La selezione a trascinamento (la prima cella decide) e le preferenze ricordate. */
class SelectionAndPreferencesTest {

  private val ids = List(200) { MediaId(UUID(7, it.toLong()).toString()) }
  private val at = PositionIds { ids[it] }

  @Test
  fun laPrimaCellaDecideSelezionaODeseleziona() {
    val s = SelectionModel()
    s.toggle(ids[8])
    s.toggle(ids[30])
    // Da una cella non selezionata: seleziona l'intervallo, in qualunque direzione.
    s.beginDrag(10, at)
    assertThat(s.isDragging).isTrue()
    s.dragTo(5)
    assertThat(s.selected.value.ids).containsExactlyElementsIn(listOf(ids[5], ids[6], ids[7], ids[8], ids[9], ids[10], ids[30]))
    // Passando dall'altra parte della prima cella l'intervallo si rigira; la 8 (gia' selezionata prima) resta.
    s.dragTo(12)
    assertThat(s.selected.value.ids).containsExactly(ids[8], ids[10], ids[11], ids[12], ids[30])
    s.endDrag()

    // Da una cella selezionata: deseleziona quelle che attraversa, e tornando indietro le restituisce com'erano.
    s.beginDrag(11, at)
    s.dragTo(8)
    assertThat(s.selected.value.ids).containsExactly(ids[12], ids[30])
    s.dragTo(10)
    assertThat(s.selected.value.ids).containsExactly(ids[8], ids[12], ids[30])
    s.dragTo(40)
    assertThat(s.selected.value.ids).containsExactly(ids[8], ids[10])
    s.endDrag()
    assertThat(s.isDragging).isFalse()
    assertThat(s.isActive).isTrue()
  }

  @Test
  fun laStessaPosizioneNonEmetteEUnTrascinamentoLungoEIncrementale() {
    val s = SelectionModel()
    s.beginDrag(0, at)
    s.dragTo(150)
    val before = s.selected.value
    s.dragTo(150)
    assertThat(s.selected.value).isSameInstanceAs(before)
    // Avanti e indietro a caso: il risultato e' sempre quello calcolato da zero.
    val r = Random(3)
    repeat(300) {
      val p = r.nextInt(ids.size)
      s.dragTo(p)
      assertThat(s.selected.value.ids).isEqualTo((minOf(0, p)..maxOf(0, p)).map { ids[it] }.toSet())
    }
    s.endDrag()
    s.dragTo(5)
    s.retain { it != ids[0] }
    assertThat(ids[0] in s.selected.value).isFalse()
    s.selectAll(listOf(ids[199]))
    assertThat(ids[199] in s.selected.value).isTrue()
    s.clear()
    assertThat(s.isActive).isFalse()
  }

  @Test
  fun lePreferenzeSiRicordanoFraUnAperturaELAltra() = runBlocking<Unit> {
    val dir: File = Files.createTempDirectory("prefs").toFile()
    val file = File(dir, "library.preferences_pb")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      val prefs = DataStoreLibraryPreferences.create(file, scope, storeScope = scope)
      prefs.ready()
      assertThat(prefs.filter.value).isEqualTo(LibraryFilter.ALL)
      assertThat(prefs.level.value).isEqualTo(GridLevel.DAYS)
      assertThat(prefs.style.value).isEqualTo(GridStyle.SQUARE)
      prefs.setFilter(LibraryFilter.WHATSAPP)
      prefs.setLevel(GridLevel.YEARS)
      prefs.setStyle(GridStyle.JUSTIFIED)
      withTimeout(5_000) {
        prefs.filter.first { it == LibraryFilter.WHATSAPP }
        prefs.level.first { it == GridLevel.YEARS }
        prefs.style.first { it == GridStyle.JUSTIFIED }
      }
    } finally {
      scope.coroutineContext[Job]!!.cancelAndJoin()
    }
    // Un altro processo (un altro DataStore sullo stesso file) le ritrova.
    val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      val again = DataStoreLibraryPreferences.create(file, scope2, storeScope = scope2)
      withTimeout(5_000) {
        again.filter.first { it == LibraryFilter.WHATSAPP }
        again.level.first { it == GridLevel.YEARS }
        again.style.first { it == GridStyle.JUSTIFIED }
      }
    } finally {
      scope2.coroutineContext[Job]!!.cancelAndJoin()
      dir.deleteRecursively()
    }
  }
}
