package dev.pampa.mycloudgallery.feature.albums

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.TripAcceptOutcome
import dev.pampa.mycloudgallery.core.albums.contract.TripId
import dev.pampa.mycloudgallery.core.albums.contract.TripProposal
import dev.pampa.mycloudgallery.core.albums.contract.TripProposals
import dev.pampa.mycloudgallery.core.albums.contract.TripReport
import dev.pampa.mycloudgallery.core.albums.contract.Trips
import dev.pampa.mycloudgallery.core.model.TripKind
import dev.pampa.mycloudgallery.feature.albums.contract.FixedAlbumScreensClock
import dev.pampa.mycloudgallery.feature.albums.sections.AlbumEntryTarget
import dev.pampa.mycloudgallery.feature.albums.sections.AlbumsSectionCodes
import dev.pampa.mycloudgallery.feature.albums.sections.AlbumsSectionSources
import dev.pampa.mycloudgallery.feature.albums.sections.SectionData
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntry
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntryGroup
import dev.pampa.mycloudgallery.feature.library.collections.CollectionSections
import dev.pampa.mycloudgallery.feature.library.collections.FixedCollectionsSection
import java.time.LocalDate
import kotlin.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Il legame delle sezioni Album, Album smart e Viaggi con le Raccolte (Fase 7, P10, D33, §2.7). `feature-albums` non vede `feature-library` (i feature non si
 * conoscono), quindi produce dati di forma identica a `CollectionEntryGroup`; `:app` (P13) li trasforma con `toGroup()` e li registra con un
 * `CollectionsSectionProvider`. Questo test **vede** `feature-library` (solo nei test) e prova il legame: i codici sono gli stessi, il gruppo vero accetta i dati
 * (le sue regole: codici unici, codice scrivibile nelle impostazioni), il fornitore vero li consegna.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CollectionsSectionBindingTest {

  private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources

  /** Quello che P13 scrive in `:app`: la trasformazione campo per campo. */
  private fun SectionData.toGroup(): CollectionEntryGroup = CollectionEntryGroup(
    title = title,
    entries = entries.map { CollectionEntry(it.code, it.title, it.subtitle, it.icon, it.tone, it.badge, it.trailing) },
    code = code,
    hideable = hideable,
    hint = hint,
  )

  private class StaticTrips(initial: TripProposals) : Trips {
    override val proposals = MutableStateFlow(initial)

    override suspend fun detect(budget: Duration) = TripReport(0, 0, true, false)

    override suspend fun accept(id: TripId): TripAcceptOutcome = TripAcceptOutcome.NotFound

    override suspend fun dismiss(id: TripId) = Unit
  }

  private fun trip(kind: TripKind, from: LocalDate, count: Int = 120): TripProposal {
    val range = LocalDayRange(from, from.plusDays(3))
    val places = if (kind == TripKind.TRIP) listOf(1) else emptyList()
    return TripProposal(TripId.of(kind, range, places), kind, range, "Proposta $from", count, ShareFixtures.media(7), places)
  }

  private fun TestScope.sources(scenes: Scenes, trips: Trips = StaticTrips(TripProposals(emptyList(), homeKnown = true))) =
    AlbumsSectionSources(resources, scenes.world.albums, trips, scenes.people, FixedAlbumScreensClock(LocalDate.of(2026, 10, 21)), backgroundScope)

  @Test
  fun iCodiciDelleSezioniSonoQuelliDelleRaccolte() {
    assertThat(AlbumsSectionCodes.ALBUMS).isEqualTo(CollectionSections.ALBUMS)
    assertThat(AlbumsSectionCodes.SMART).isEqualTo(CollectionSections.SMART)
    assertThat(AlbumsSectionCodes.TRIPS).isEqualTo(CollectionSections.TRIPS)
    for (code in listOf(AlbumsSectionCodes.ALBUMS, AlbumsSectionCodes.SMART, AlbumsSectionCodes.TRIPS)) assertThat(CollectionSections.isValidCode(code)).isTrue()
  }

  @Test
  fun laSezioneAlbumNonSiNascondeMaiEIlGruppoVeroLAccetta() = runTest(UnconfinedTestDispatcher()) {
    val scenes = Scenes()
    val section = sources(scenes).albums.value!!
    assertThat(section.hideable).isEqualTo(CollectionSections.ALBUMS !in CollectionSections.neverHidden)
    val group = section.toGroup()
    assertThat(group.code).isEqualTo(CollectionSections.ALBUMS)
    assertThat(group.hideable).isFalse()
    // Una voce per album (il condiviso di un altro con il nome di chi l'ha creato), piu' «Nuovo album».
    val entries = group.entries
    assertThat(entries.last().code).isEqualTo(AlbumEntryTarget.NEW_ALBUM)
    val natale = entries.single { it.title == "Natale in famiglia" }
    assertThat(natale.code).isEqualTo(AlbumEntryTarget.ALBUM_PREFIX + scenes.christmas.value)
    assertThat(natale.subtitle).contains("Marco")
    assertThat(natale.trailing).isEqualTo("Condiviso")
    assertThat(entries.single { it.title == "Compleanno di Anna" }.trailing).isNull()
  }

  @Test
  fun conPiuDiSeiAlbumArrivaTuttiGliAlbum() = runTest(UnconfinedTestDispatcher()) {
    val scenes = Scenes()
    for (n in 1..4) scenes.world.albums.create("Extra $n", dev.pampa.mycloudgallery.core.model.AlbumKind.MANUAL)
    val group = sources(scenes).albums.value!!.toGroup()
    assertThat(group.entries.map { it.code }).contains(AlbumEntryTarget.ALL_ALBUMS)
    // I primi sei, poi «Tutti gli album» e «Nuovo album».
    assertThat(group.entries.count { it.code.startsWith(AlbumEntryTarget.ALBUM_PREFIX) }).isEqualTo(6)
    assertThat(group.entries.takeLast(2).map { it.code }).containsExactly(AlbumEntryTarget.ALL_ALBUMS, AlbumEntryTarget.NEW_ALBUM).inOrder()
  }

  @Test
  fun laSezioneSmartHaUnaVocePerAlbumSmartESenzaAlbumSmartNonCe() = runTest(UnconfinedTestDispatcher()) {
    val scenes = Scenes()
    val group = sources(scenes).smart.value!!.toGroup()
    assertThat(group.code).isEqualTo(CollectionSections.SMART)
    assertThat(group.hideable).isTrue()
    assertThat(group.entries.map { it.code }).containsExactly(AlbumEntryTarget.SMART_PREFIX + scenes.oslo.value)
    assertThat(group.entries.single().subtitle).contains("suggerit")

    val empty = Scenes().also { s -> runKotlinBlocking { s.world.albums.delete(s.oslo) } }
    val none = sources(empty).smart.value!!
    assertThat(none.entries).isEmpty()
    assertThat(none.toGroup().isEmpty).isTrue()
  }

  @Test
  fun laSezioneViaggiMostraLeProposteESenzaNienteNonCe() = runTest(UnconfinedTestDispatcher()) {
    val scenes = Scenes()
    val proposals = TripProposals(listOf(trip(TripKind.TRIP, LocalDate.of(2026, 9, 1)), trip(TripKind.EVENT, LocalDate.of(2026, 9, 20), 40)), homeKnown = true)
    val group = sources(scenes, StaticTrips(proposals)).trips.value!!.toGroup()
    assertThat(group.code).isEqualTo(CollectionSections.TRIPS)
    assertThat(group.entries).hasSize(2)
    for (entry in group.entries) assertThat(entry.code).startsWith(AlbumEntryTarget.TRIP_PREFIX)

    val none = sources(scenes, StaticTrips(TripProposals(emptyList(), homeKnown = true))).trips.value!!
    assertThat(none.entries).isEmpty()
  }

  @Test
  fun ogniCodiceDiUnaVoceSiRiconoscePerLApertura_EnonPortaMaiUnDatoDellUtente() = runTest(UnconfinedTestDispatcher()) {
    val scenes = Scenes()
    val trips = StaticTrips(TripProposals(listOf(trip(TripKind.TRIP, LocalDate.of(2026, 9, 1))), homeKnown = true))
    val s = sources(scenes, trips)
    val all = listOf(s.albums.value!!, s.smart.value!!, s.trips.value!!).flatMap { it.entries }
    assertThat(all).isNotEmpty()
    val allowed = Regex("""^(album|smart)/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$|^trip/[0-9a-zA-Z_-]+$|^albums/(all|new)$|^trips/all$""")
    for (entry in all) {
      assertWithMessage("codice «${entry.code}»").that(allowed.matches(entry.code)).isTrue()
      assertWithMessage("codice «${entry.code}» non riconosciuto").that(AlbumEntryTarget.parse(entry.code)).isNotNull()
    }
  }

  @Test
  fun ilFornitoreVeroDelleRaccolteConsegnaLaSezione() = runTest(UnconfinedTestDispatcher()) {
    val scenes = Scenes()
    val section = sources(scenes).albums.value!!
    val provider = FixedCollectionsSection(AlbumsSectionCodes.ALBUMS, section.toGroup())
    assertThat(CollectionSections.isValidCode(provider.code)).isTrue()
    assertThat(provider.group.value?.entries?.map { it.title }).contains("Compleanno di Anna")
    assertThat(provider.group.value?.code).isEqualTo(provider.code)
  }

  private fun runKotlinBlocking(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }
}
