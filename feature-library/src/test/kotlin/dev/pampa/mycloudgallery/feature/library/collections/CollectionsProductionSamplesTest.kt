package dev.pampa.mycloudgallery.feature.library.collections

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.feature.library.collections.testing.CollectionsSectionSamples
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import dev.pampa.mycloudgallery.feature.library.utility.UtilityPage
import dev.pampa.mycloudgallery.feature.library.utility.utilityEntryGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CU-9 (fase H, giro 1): gli esempi che fanno gli screenshot devono dire la verita' sulla produzione.
 *
 *  - le voci dell'Utility degli esempi hanno i titoli e le icone del costruttore vero (`utilityEntryGroup`): niente piu' matita e "copia";
 *  - le Raccolte come le compone il controllore vero con le sezioni di produzione non hanno due righe con lo stesso titolo ("Eliminati di recente" una
 *    volta sola: la sezione "Da tenere al sicuro" di `:app` e' vuota e toglie quella della scheda), ne' sul telefono ne' sul tablet (lo stato e' lo
 *    stesso, cambia solo quanti pannelli lo mostrano);
 *  - la foto di un luogo e' la piu' recente scattata li', presa dalla Libreria visibile: mai un nascosto.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CollectionsProductionSamplesTest {

  private val resources = ApplicationProvider.getApplicationContext<Application>().resources
  private val world = FakeCollectionsWorld()
  private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

  @After
  fun stop() {
    scope.cancel()
  }

  @Test
  fun leVociDellUtilityDegliEsempiSonoQuelleDellaProduzione() {
    val real = utilityEntryGroup(resources, mapOf(UtilityPage.EDITED to 37), canTrash = true).entries.associateBy { it.code }
    val sample = CollectionsSectionSamples.utility().group.value!!.entries.filter { it.code.startsWith("utility/") }
    assertThat(sample.map { it.code }).containsExactlyElementsIn(real.keys)
    for (entry in sample) {
      val truth = real.getValue(entry.code)
      assertWithMessage(entry.code).that(entry.title).isEqualTo(truth.title)
      assertWithMessage(entry.code).that(entry.icon).isEqualTo(truth.icon)
    }
  }

  /** CU-18 (giro 2): "Modificate" senza foto modificate dice "Nessuna", non "0 elementi"; con delle foto, il conto. Sottotitoli corti, una riga. */
  @Test
  fun modificateSenzaFotoDiceNessuna() {
    fun edited(count: Int) = utilityEntryGroup(resources, mapOf(UtilityPage.EDITED to count), canTrash = true).entries
      .first { it.code == UtilityPage.EDITED.entryCode }.subtitle
    assertThat(edited(0)).isEqualTo("Nessuna")
    assertThat(edited(37)).isEqualTo("37 elementi")
    val deleted = utilityEntryGroup(resources, emptyMap(), canTrash = true).entries.first { it.code == UtilityPage.RECENTLY_DELETED.entryCode }
    assertThat(deleted.subtitle).isEqualTo("Telefono e NAS")
  }

  @Test
  fun inProduzioneNessunaVoceCompareDueVolte() {
    for (fresh in listOf(false, true)) {
      val providers = CollectionsSectionSamples.production(resources, world.visibleIds.toList(), emptyList(), fresh)
      val c = CollectionsLayoutController(true, providers, MemoryCollectionsLayoutStore(), scope, SharingStarted.Eagerly)
      val inputs = c.inputs.value
      val state = CollectionsLayout.state(inputs.present, emptyList(), inputs.saved, inputs.hidden, inputs.editing, inputs.saveFailed)
      val titles = state.visible.flatMap { section ->
        section.rows.map { row ->
          when (row) {
            is SectionRow.Entry -> row.entry.title
            is SectionRow.Kind -> resources.getString(row.row.kind.titleRes)
          }
        }
      }
      assertWithMessage("libreria appena installata: $fresh").that(titles).containsNoDuplicates()
      assertThat(titles.count { it == "Eliminati di recente" }).isEqualTo(1)
      assertThat(state.visible.map { it.code }).doesNotContain(CollectionSections.SAFE)
    }
  }

  @Test
  fun laFotoDiUnLuogoELaPiuRecenteEMaiUnNascosto() {
    val view = world.source.items(LibraryFilter.ALL).view.value
    val places = CollectionsSectionSamples.PRODUCTION_PLACES.map { it.first }.toSet()
    val found = CollectionCovers.newestByPlace(view, places)
    assertThat(found).isNotEmpty()
    for ((place, media) in found) {
      assertThat(media).isNotIn(world.hiddenIds)
      // La prima della vista (la piu' recente) con quel luogo.
      val first = (0 until view.size).first { view.snapshot.placeAt(view.snapshotIndex(it)) == place }
      assertThat(media).isEqualTo(view.snapshot.idAt(view.snapshotIndex(first)))
    }
    // Un luogo che non c'e' non ha una foto, e non si scorre oltre il necessario per gli altri.
    assertThat(CollectionCovers.newestByPlace(view, setOf(1))).isEmpty()
  }
}
