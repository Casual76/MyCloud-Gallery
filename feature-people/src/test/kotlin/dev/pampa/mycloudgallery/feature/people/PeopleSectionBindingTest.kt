package dev.pampa.mycloudgallery.feature.people

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.PeopleList
import dev.pampa.mycloudgallery.core.faces.contract.PersonCard
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntries
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntry
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntryGroup
import dev.pampa.mycloudgallery.feature.library.collections.CollectionSections
import dev.pampa.mycloudgallery.feature.library.collections.FixedCollectionsSection
import dev.pampa.mycloudgallery.feature.people.contract.PeopleSectionState
import dev.pampa.mycloudgallery.feature.people.section.PeopleSectionSources
import dev.pampa.mycloudgallery.feature.people.section.SectionData
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Il legame della sezione Persone con le Raccolte (Fase 8, P9; §2.6, D25; come `CollectionsSectionBindingTest` di feature-albums). `feature-people` non vede
 * `feature-library` (i feature non si conoscono), quindi produce dati di forma identica a `CollectionEntryGroup`; `:app` (P13) li trasforma con `toGroup()`
 * e li registra con un `CollectionsSectionProvider`. Questo test **vede** `feature-library` (solo nei test) e prova il legame: lo stesso codice, il gruppo
 * vero accetta i dati (codici unici e scrivibili nelle impostazioni), il fornitore vero li consegna.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PeopleSectionBindingTest {

  private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources

  /** Quello che P13 scrive in `:app`: la trasformazione campo per campo. */
  private fun SectionData.toGroup(): CollectionEntryGroup = CollectionEntryGroup(
    title = title,
    entries = entries.map { CollectionEntry(it.code, it.title, it.subtitle, it.icon, it.tone, it.badge, it.trailing) },
    code = code,
    hideable = hideable,
    hint = hint,
  )

  private fun card(name: String?, photos: Int) = PersonCard(PersonId.random(), name, photos, null)

  @Test
  fun ilCodiceEQuelloDelleRaccolte() {
    assertThat(PeopleSectionSources.CODE).isEqualTo(CollectionSections.PEOPLE)
    assertThat(CollectionSections.isValidCode(PeopleSectionSources.CODE)).isTrue()
    // Persone e' una delle sezioni riservate che la Fase 8 riempie: l'ordine ha gia' il suo posto.
    assertThat(CollectionSections.defaultOrder).contains(CollectionSections.PEOPLE)
  }

  @Test
  fun ilGruppoVeroAccettaLaSezioneInOgniStato() {
    val anna = card("Anna", 10)
    val states = listOf(
      PeopleSectionState.Off,
      PeopleSectionState.OffElsewhere,
      PeopleSectionState.AwaitingNotice,
      PeopleSectionState.Excluded,
      PeopleSectionState.FirstRun(12),
      PeopleSectionState.Ready(listOf(anna), 1, 3),
      PeopleSectionState.LocalOnly(listOf(anna), 1, 0),
    )
    for (state in states) {
      val group = PeopleSectionSources.sectionOf(resources, state, PeopleList(listOf(anna), emptyList(), 3, 0, null)).toGroup()
      assertThat(group.code).isEqualTo(CollectionSections.PEOPLE)
      assertThat(group.hideable).isTrue() // Persone si nasconde dalle Raccolte: non e' "Album"
      assertThat(group.entries).isNotEmpty()
      // Codici unici dentro la sezione (la regola del gruppo vero, che qui non e' scattata) e ritrovabili dalle Raccolte per aprirli.
      assertThat(group.entries.map { it.code }.toSet()).hasSize(group.entries.size)
      for (entry in group.entries) assertThat(CollectionEntries.find(listOf(group), entry.code)).isEqualTo(entry)
    }
  }

  @Test
  fun ilFornitoreVeroDelleRaccolteConsegnaLaSezione() {
    val anna = card("Anna", 10)
    val section = PeopleSectionSources.sectionOf(resources, PeopleSectionState.Ready(listOf(anna), 1, 0), PeopleList(listOf(anna), emptyList(), 0, 0, null))
    val provider = FixedCollectionsSection(PeopleSectionSources.CODE, section.toGroup())
    assertThat(provider.code).isEqualTo(CollectionSections.PEOPLE)
    assertThat(provider.group.value?.entries?.map { it.title }).contains("Anna")
    assertThat(provider.group.value?.code).isEqualTo(provider.code)
  }

  /** Le Raccolte non sono lo stato di Persone: da spenta la sezione c'e' lo stesso e porta alla pagina di attivazione. */
  @Test
  fun daSpentaLaSezioneCeLoStesso() {
    val off = PeopleSectionSources.sectionOf(resources, PeopleSectionState.Off, PeopleList.EMPTY).toGroup()
    assertThat(off.entries.single().title).isEqualTo("Attiva riconoscimento dei volti")
    assertThat(off.isEmpty).isFalse()
  }
}
