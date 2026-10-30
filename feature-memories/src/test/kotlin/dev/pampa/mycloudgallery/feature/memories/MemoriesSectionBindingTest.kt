package dev.pampa.mycloudgallery.feature.memories

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.ShelfEmptyReason
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntries
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntry
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntryGroup
import dev.pampa.mycloudgallery.feature.library.collections.CollectionSections
import dev.pampa.mycloudgallery.feature.library.collections.CollectionsLayout
import dev.pampa.mycloudgallery.feature.library.collections.FixedCollectionsSection
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesSectionState
import dev.pampa.mycloudgallery.feature.memories.contract.NoMemoryPlaceNames
import dev.pampa.mycloudgallery.feature.memories.section.MemoriesEntryTarget
import dev.pampa.mycloudgallery.feature.memories.section.MemoriesSectionSources
import dev.pampa.mycloudgallery.feature.memories.section.SectionData
import dev.pampa.mycloudgallery.feature.memories.testing.MemoriesSamples
import dev.pampa.mycloudgallery.feature.memories.ui.MemoryNames
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Il legame della sezione Ricordi con le Raccolte (Fase 9, P9; §2.5, D14, E-MEM1; come `PeopleSectionBindingTest`). `feature-memories` non vede
 * `feature-library` (i feature non si conoscono), quindi produce dati di forma identica a `CollectionEntryGroup`; `:app` (P13) li trasforma con
 * `toGroup()`. Questo test **vede** `feature-library` (solo nei test) e prova il legame: lo stesso codice, il gruppo vero accetta i dati, il layout vero
 * **tiene la sezione anche da vuota** (E-MEM1) e il fornitore vero li consegna.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MemoriesSectionBindingTest {

  private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources
  private val names = MemoryNames(null, NoMemoryPlaceNames)

  /** Quello che P13 scrive in `:app`: la trasformazione campo per campo. */
  private fun SectionData.toGroup(): CollectionEntryGroup = CollectionEntryGroup(
    title = title,
    entries = entries.map { CollectionEntry(it.code, it.title, it.subtitle, it.icon, it.tone, it.badge, it.trailing) },
    code = code,
    hideable = hideable,
    hint = hint,
  )

  private val ready = MemoriesSectionState.Ready(listOf(MemoriesSamples.otd, MemoriesSamples.trip, MemoriesSamples.personCard, MemoriesSamples.month), unseen = 2)

  private val states = listOf(
    ready,
    MemoriesSectionState.Empty(ShelfEmptyReason.NOT_ENOUGH_PHOTOS),
    MemoriesSectionState.Empty(ShelfEmptyReason.LIBRARY_NOT_READY),
    MemoriesSectionState.Working,
  )

  @Test
  fun ilCodiceEQuelloDelleRaccolte() {
    assertThat(MemoriesSectionSources.CODE).isEqualTo(CollectionSections.MEMORIES)
    assertThat(CollectionSections.isValidCode(MemoriesSectionSources.CODE)).isTrue()
    assertThat(CollectionSections.defaultOrder).contains(CollectionSections.MEMORIES)
  }

  @Test
  fun ilGruppoVeroAccettaLaSezioneInOgniStato() {
    for (state in states) {
      val group = MemoriesSectionSources.sectionOf(resources, state, names).toGroup()
      assertThat(group.code).isEqualTo(CollectionSections.MEMORIES)
      // I Ricordi si nascondono dalle Raccolte (D14).
      assertThat(group.hideable).isTrue()
      assertThat(group.entries).isNotEmpty()
      assertThat(group.entries.map { it.code }.toSet()).hasSize(group.entries.size)
      for (entry in group.entries) {
        assertThat(CollectionEntries.find(listOf(group), entry.code)).isEqualTo(entry)
        // Ogni voce ha un bersaglio che `:app` riconosce dal codice.
        assertThat(MemoriesEntryTarget.parse(entry.code)).isNotNull()
      }
    }
  }

  /** E-MEM1: "Senza voci non c'e' sezione" per il layout: da vuota o in generazione la sezione ha comunque una riga, e il layout vero la tiene. */
  @Test
  fun ilLayoutVeroTieneLaSezioneAnchePerVuotaEInGenerazione() {
    for (state in states) {
      val group = MemoriesSectionSources.sectionOf(resources, state, names).toGroup()
      val kept = CollectionsLayout.sectionOf(MemoriesSectionSources.CODE, group)
      assertThat(kept).isNotNull()
      assertThat(kept!!.rows).isNotEmpty()
    }
  }

  @Test
  fun ilFornitoreVeroDelleRaccolteConsegnaLaSezione() {
    val section = MemoriesSectionSources.sectionOf(resources, ready, names)
    val provider = FixedCollectionsSection(MemoriesSectionSources.CODE, section.toGroup())
    assertThat(provider.code).isEqualTo(CollectionSections.MEMORIES)
    assertThat(provider.group.value?.entries?.map { it.title }).contains("Un anno fa")
    assertThat(provider.group.value?.code).isEqualTo(provider.code)
  }

  /** Il bersaglio di ogni voce di un ricordo e' un ricordo; "Tutti i ricordi" apre la sua pagina; le righe di stato non aprono niente. */
  @Test
  fun iBersagliDelleVoci() {
    val group = MemoriesSectionSources.sectionOf(resources, ready, names)
    val targets = group.entries.map { MemoriesEntryTarget.parse(it.code) }
    assertThat(targets.count { it is MemoriesEntryTarget.Memory }).isEqualTo(4)
    assertThat(targets.last()).isEqualTo(MemoriesEntryTarget.All)
    val empty = MemoriesSectionSources.sectionOf(resources, MemoriesSectionState.Working, names)
    assertThat(MemoriesEntryTarget.parse(empty.entries.single().code)).isEqualTo(MemoriesEntryTarget.Inert)
  }
}
