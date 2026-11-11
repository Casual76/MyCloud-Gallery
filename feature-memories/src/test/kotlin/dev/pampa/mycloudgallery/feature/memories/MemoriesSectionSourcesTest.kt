package dev.pampa.mycloudgallery.feature.memories

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesSectionState
import dev.pampa.mycloudgallery.feature.memories.contract.NoMemoryPlaceNames
import dev.pampa.mycloudgallery.feature.memories.section.MemoriesEntryTarget
import dev.pampa.mycloudgallery.feature.memories.section.MemoriesSectionSources
import dev.pampa.mycloudgallery.feature.memories.section.MemoriesSectionStates
import dev.pampa.mycloudgallery.feature.memories.ui.FinalFilter
import dev.pampa.mycloudgallery.feature.memories.ui.MemoryNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * La sezione Ricordi delle Raccolte come dato (Fase 9, P9; §1.5 `MemoriesSectionState`, §2.5, D14, D31, E-MEM1, "Avvio e pagine istantanei"): disegnata
 * **dall'ultimo scaffale** senza aspettare, mai senza una riga (altrimenti sparirebbe dal layout), con il segno "nuovo", i codici stabili e il filtro finale
 * dei Nascosti che rifa' conti e copertine un istante dopo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MemoriesSectionSourcesTest {

  private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources

  private fun sources(rig: MemoriesRig, scope: CoroutineScope, mask: Boolean = true) =
    MemoriesSectionSources(resources, rig.memories, MemoryNames(rig.people, NoMemoryPlaceNames), FinalFilter(if (mask) rig.lib.visibility else NothingHidden), scope)

  @Test
  fun siDisegnaSubitoDallUltimoScaffale_primaChePartaQualunqueLavoro() = runTest {
    val rig = MemoriesRig()
    rig.refresh()
    val s = sources(rig, backgroundScope)
    // Nessun runCurrent: il valore di partenza e' gia' la sezione vera (istantaneo).
    val state = s.sectionState.value as MemoriesSectionState.Ready
    assertThat(state.cards).hasSize(3)
    assertThat(s.section.value.entries).hasSize(3 + 1)
    assertThat(s.section.value.code).isEqualTo(MemoriesSectionSources.CODE)
    assertThat(s.section.value.hideable).isTrue()
  }

  /** D10: "visto" si segna solo aprendo la pagina o la presentazione, mai guardando la sezione o "Tutti i ricordi". */
  @Test
  fun guardareLaSezioneNonSegnaVisto() = runTest {
    val rig = MemoriesRig()
    rig.refresh()
    val s = sources(rig, backgroundScope)
    val all = dev.pampa.mycloudgallery.feature.memories.list.AllMemoriesController(rig.memories, MemoryNames(rig.people, NoMemoryPlaceNames), FinalFilter(rig.lib.visibility), backgroundScope)
    runCurrent()
    assertThat(s.section.value.entries.count { it.trailing == "Nuovo" }).isEqualTo(3)
    assertThat(all.ui.value.snapshot).isNotNull()
    assertThat(rig.memories.shelf.value.unseen).isEqualTo(3)
    assertThat(rig.cards().all { it.isNew }).isTrue()
  }

  @Test
  fun leVociHannoTitoloContoESegnoNuovo_primaINonVisti() = runTest {
    val rig = MemoriesRig()
    rig.refresh()
    val seen = rig.card(MemoryKind.ON_THIS_DAY, 1)
    rig.memories.markSeen(seen.id)
    val s = sources(rig, backgroundScope)
    runCurrent()
    val entries = s.section.value.entries
    // Le due nuove prima, quella vista dopo; poi "Tutti i ricordi".
    val titles = entries.map { it.title }
    assertThat(titles.first { "2 anni fa" == it }).isNotNull()
    assertThat(titles).contains("Un anno fa")
    assertThat(titles).contains("Il meglio di ottobre 2026")
    assertThat(entries[0].trailing).isEqualTo("Nuovo")
    assertThat(entries[1].trailing).isEqualTo("Nuovo")
    assertThat(entries[2].trailing).isNull()
    assertThat(entries[2].title).isEqualTo("Un anno fa")
    assertThat(entries.dropLast(1).all { it.subtitle.contains("foto") }).isTrue()
    assertThat(entries.take(3).any { it.subtitle.startsWith("9 foto") }).isTrue()
    val all = entries.last()
    assertThat(all.code).isEqualTo(MemoriesEntryTarget.ALL)
    assertThat(all.subtitle).isEqualTo("3 ricordi")
    // I codici sono unici e stabili (m_ e 16 caratteri), mai un dato dell'utente.
    assertThat(entries.map { it.code }.toSet()).hasSize(entries.size)
    assertThat(entries.take(3).all { it.code.startsWith("m_") && it.code.length == 18 }).isTrue()
  }

  @Test
  fun daVuotaDiceChePocheFoto_inGenerazioneDicePrepara_laSezioneNonSparisce() = runTest {
    val rig = MemoriesRig()
    // Mai un giro: "Sto preparando i ricordi".
    val working = sources(rig, backgroundScope)
    runCurrent()
    assertThat(working.sectionState.value).isEqualTo(MemoriesSectionState.Working)
    assertThat(working.section.value.entries.single().title).isEqualTo("Sto preparando i ricordi")
    assertThat(MemoriesEntryTarget.parse(working.section.value.entries.single().code)).isEqualTo(MemoriesEntryTarget.Inert)
    // Libreria senza abbastanza foto: dopo il giro "Ancora nessun ricordo" / "Arrivano quando ci sono abbastanza foto" (E-MEM1; H5 ha accorciato la riga).
    val poor = MemoriesRig(seeded = false).also { it.lib.photos(it.today.minusDays(40), 2) }
    poor.refresh()
    val empty = sources(poor, backgroundScope)
    runCurrent()
    assertThat(empty.sectionState.value).isInstanceOf(MemoriesSectionState.Empty::class.java)
    assertThat(empty.section.value.entries.single().title).isEqualTo("Ancora nessun ricordo")
    assertThat(empty.section.value.entries.single().subtitle).isEqualTo("Arrivano quando ci sono abbastanza foto")
    assertThat(empty.section.value.entries).isNotEmpty()
  }

  /** D31: la copertina nascosta un attimo fa non c'e' piu' (il filtro finale rifa' copertina e conto); senza maschera restano le voci del servizio. */
  @Test
  fun unNascostoDellUltimoMomentoEsceDallaVoce_ilConto_Scende() = runTest {
    val rig = MemoriesRig()
    rig.refresh()
    val card = rig.card(MemoryKind.ON_THIS_DAY, 1)
    val members = rig.members(card.id)
    rig.lib.visibility.hidden += card.cover
    val s = sources(rig, backgroundScope)
    runCurrent()
    val refined = (s.sectionState.value as MemoriesSectionState.Ready).cards.first { it.id == card.id }
    assertThat(refined.cover).isNotEqualTo(card.cover)
    assertThat(refined.cover).isIn(members)
    assertThat(refined.count).isEqualTo(card.count - 1)
    // Senza maschera la voce e' quella del servizio.
    val plain = sources(rig, backgroundScope, mask = false)
    runCurrent()
    assertThat((plain.sectionState.value as MemoriesSectionState.Ready).cards.first { it.id == card.id }.count).isEqualTo(card.count)
  }

  @Test
  fun unRicordoConTroppiNascostiNonCePiuNellaSezione() = runTest {
    val rig = MemoriesRig()
    rig.refresh()
    val card = rig.card(MemoryKind.ON_THIS_DAY, 1)
    val members = rig.members(card.id)
    members.take(7).forEach { rig.lib.visibility.hidden += it }
    val s = sources(rig, backgroundScope)
    runCurrent()
    val ready = s.sectionState.value as MemoriesSectionState.Ready
    assertThat(ready.cards.map { it.id }).doesNotContain(card.id)
  }

  @Test
  fun unaVoceSiRisolveNelRicordo_unaChePiuNonCeNo() = runTest {
    val rig = MemoriesRig()
    rig.refresh()
    val s = sources(rig, backgroundScope)
    runCurrent()
    val card = rig.card(MemoryKind.MONTH)
    assertThat(s.resolve(MemoriesSectionStates.codeOf(card.id))).isEqualTo(card.id)
    assertThat(s.resolve("m_0000000000000000")).isNull()
    // Il ricordo non mostrare: la voce sparisce e il codice non si risolve piu'.
    rig.memories.dismiss(card.id)
    runCurrent()
    assertThat(s.resolve(MemoriesSectionStates.codeOf(card.id))).isNull()
  }

  @Test
  fun iNomiDelleVociSiLeggonoAlMomento_eNonSiSalvano() = runTest {
    val rig = MemoriesRig()
    val anna = PersonId.fromBytes(ByteArray(16) { (it + 9).toByte() })
    rig.people.person(anna, "Anna", emptyList())
    val names = MemoryNames(rig.people, NoMemoryPlaceNames)
    assertThat(names.cachedPerson(anna)).isNull()
    assertThat(names.person(anna)).isEqualTo("Anna")
    assertThat(names.cachedPerson(anna)).isEqualTo("Anna")
    // Persone non e' piu' attiva qui (o la persona e' nascosta): il nome non si ricorda piu'.
    rig.people.available.value = false
    assertThat(names.person(anna)).isNull()
    assertThat(names.cachedPerson(anna)).isNull()
  }
}
