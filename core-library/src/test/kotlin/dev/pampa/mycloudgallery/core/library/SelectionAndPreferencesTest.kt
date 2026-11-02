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

  @Test
  fun affiancaSiRicordaPerProfilo_eDiBaseIlViewerEAFinestraIntera() = runBlocking<Unit> {
    // Un profilo del tablet e' un utente Android con la sua copia dell'app: il suo file di preferenze sta nei suoi dati. Due file = due profili.
    val first: File = Files.createTempDirectory("profilo1").toFile()
    val second: File = Files.createTempDirectory("profilo2").toFile()
    val file1 = File(first, "library.preferences_pb")
    val file2 = File(second, "library.preferences_pb")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      val one = DataStoreLibraryPreferences.create(file1, scope, storeScope = scope)
      val two = DataStoreLibraryPreferences.create(file2, scope, storeScope = scope)
      one.ready()
      two.ready()
      // Di base il viewer resta a finestra intera, come in Apple Foto.
      assertThat(one.viewerDocked.value).isFalse()
      one.setViewerDocked(true)
      withTimeout(5_000) { one.viewerDocked.first { it } }
      // L'altro profilo non la vede.
      assertThat(two.viewerDocked.value).isFalse()
      // Le altre preferenze non cambiano scrivendo questa (e viceversa).
      one.setLevel(GridLevel.YEARS)
      withTimeout(5_000) { one.level.first { it == GridLevel.YEARS } }
      assertThat(one.viewerDocked.value).isTrue()
    } finally {
      scope.coroutineContext[Job]!!.cancelAndJoin()
    }
    // Riaprendo (un altro avvio dell'app) la scelta c'e' ancora; "Schermo intero" la toglie e anche quello si ricorda.
    val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      val again = DataStoreLibraryPreferences.create(file1, scope2, storeScope = scope2)
      withTimeout(5_000) { again.viewerDocked.first { it } }
      assertThat(again.level.first { it == GridLevel.YEARS }).isEqualTo(GridLevel.YEARS)
      again.setViewerDocked(false)
      withTimeout(5_000) { again.viewerDocked.first { !it } }
    } finally {
      scope2.coroutineContext[Job]!!.cancelAndJoin()
    }
    val scope3 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      val last = DataStoreLibraryPreferences.create(file1, scope3, storeScope = scope3)
      last.ready()
      assertThat(last.viewerDocked.value).isFalse()
    } finally {
      scope3.coroutineContext[Job]!!.cancelAndJoin()
      first.deleteRecursively()
      second.deleteRecursively()
    }
  }

  @Test
  fun chiNonRicordaLaSceltaRestaAFinestraIntera_eNonLaSalva() = runBlocking<Unit> {
    // Le preferenze che non la implementano (i null-object, gli altri esecutori) hanno il default: mai affiancato, e salvare non fa niente.
    val none = dev.pampa.mycloudgallery.core.library.noop.NoLibraryPreferences
    none.setViewerDocked(true)
    assertThat(none.viewerDocked.value).isFalse()
    // I fake in memoria la ricordano (come il DataStore) e partono da finestra intera.
    val memory = dev.pampa.mycloudgallery.core.library.testing.InMemoryLibraryPreferences()
    assertThat(memory.viewerDocked.value).isFalse()
    memory.setViewerDocked(true)
    assertThat(memory.viewerDocked.value).isTrue()
  }
}
