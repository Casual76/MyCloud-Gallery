package dev.pampa.mycloudgallery.feature.map

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.GeoIndexState
import dev.pampa.mycloudgallery.core.map.contract.MapPoint
import dev.pampa.mycloudgallery.core.map.contract.RebuildReason
import dev.pampa.mycloudgallery.core.map.testing.ReferenceClusterIndex
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntries
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntry
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntryGroup
import dev.pampa.mycloudgallery.feature.library.collections.CollectionSections
import dev.pampa.mycloudgallery.feature.library.collections.CollectionsLayout
import dev.pampa.mycloudgallery.feature.library.collections.FixedCollectionsSection
import dev.pampa.mycloudgallery.feature.map.contract.PlaceRow
import dev.pampa.mycloudgallery.feature.map.contract.PlacesSectionState
import dev.pampa.mycloudgallery.feature.map.places.MapSectionData
import dev.pampa.mycloudgallery.feature.map.places.PlacesEntryTarget
import dev.pampa.mycloudgallery.feature.map.places.PlacesSectionModel
import dev.pampa.mycloudgallery.feature.map.places.PlacesSectionSources
import dev.pampa.mycloudgallery.feature.map.places.PlacesStates
import dev.pampa.mycloudgallery.feature.map.support.Cities
import dev.pampa.mycloudgallery.feature.map.support.FakeMapPoints
import dev.pampa.mycloudgallery.feature.map.support.PLACE_BASE
import dev.pampa.mycloudgallery.feature.map.support.TestPlaceNames
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * La sezione Luoghi delle Raccolte (Fase 9, P4; docs/fase9-progetto.md §1.5 `PlacesSectionState`, §2.5, §2.11 `places-section`, D30, E-MAP5, E-MAP18, E-L14, E-H9): la riga "Mappa" **subito**, i luoghi piu'
 * fotografati (fino a 8, nome e numero, mai "casa"), "N foto senza posizione", i codici stabili, il legame con le Raccolte (come `MemoriesSectionBindingTest`) e che l'indice si costruisce solo quando qualcuno guarda.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PlacesSectionTest {

  private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources

  /** Un punto per [count] media di un luogo, vicino a [city]. */
  private fun photos(city: GeoE6, count: Int, place: Int, shift: Int = 0): List<MapPoint> =
    List(count) { MapPoint(MediaId.random(), city.latE6 + shift + it * 20, city.lonE6 + shift + it * 20, 20_260_101, 0, place) }

  private fun ready(points: List<MapPoint>, without: Int = 3) = GeoIndexState.Ready(ReferenceClusterIndex(points), without, 0)

  // --- Gli stati (funzioni pure) ----------------------------------------------------------------------------------------------------

  @Test
  fun iLuoghiPiuFotografatiInOrdineDiNumeroEId_ConIlConteggioDeiPunti() {
    val points = photos(Cities.FIRENZE, 9, PLACE_BASE + 1) + photos(Cities.ROMA, 5, PLACE_BASE + 2) + photos(Cities.OSLO, 5, PLACE_BASE + 3) + photos(Cities.LISBONA, 2, 0)
    val state = PlacesStates.of(ready(points, without = 4), TestPlaceNames()) as PlacesSectionState.Ready
    assertThat(state.withPosition).isEqualTo(21)
    assertThat(state.withoutPosition).isEqualTo(4)
    // Luogo 1 (9), poi 2 e 3 a pari (5): per id. Le foto senza luogo non sono un luogo.
    assertThat(state.places.map { it.count }).containsExactly(9, 5, 5).inOrder()
    assertThat(state.places.map { it.label }).containsExactly("Luogo 1", "Luogo 2", "Luogo 3").inOrder()
  }

  @Test
  fun alPiuOttoLuoghi_EMaiUnoCheSiChiamaCasa() {
    val points = (1..12).flatMap { photos(Cities.ALL[it % Cities.ALL.size], count = 13 - it, place = PLACE_BASE + it, shift = it * 5_000) }
    val names = TestPlaceNames(special = mapOf(PLACE_BASE + 1 to "Casa", PLACE_BASE + 2 to "Home"))
    val state = PlacesStates.of(ready(points), names) as PlacesSectionState.Ready
    assertThat(state.places).hasSize(PlacesSectionState.Ready.MAX_PLACES)
    // I due luoghi piu' grandi si chiamano "Casa" e "Home": non ci sono (D29), e i seguenti li sostituiscono fino a otto.
    assertThat(state.places.map { it.label.lowercase() }).containsNoneOf("casa", "home")
    assertThat(state.places.map { it.placeId }).containsNoneOf(PLACE_BASE + 1, PLACE_BASE + 2)
    assertThat(state.places.map { it.count }).isInOrder(compareByDescending<Int> { it })
  }

  @Test
  fun unLuogoSenzaNomeNonCe_EleStatiSenzaPunti() {
    val points = photos(Cities.FIRENZE, 4, 777)
    val state = PlacesStates.of(ready(points), TestPlaceNames(mapOf(777 to ""))) as PlacesSectionState.Ready
    assertThat(state.places).isEmpty()
    assertThat(PlacesStates.of(GeoIndexState.Building, TestPlaceNames())).isNull()
    assertThat(PlacesStates.of(GeoIndexState.Empty(12), TestPlaceNames())).isEqualTo(PlacesSectionState.NoGps(12))
  }

  // --- La sezione come dato ------------------------------------------------------------------------------------------------------------

  private val readyState = PlacesSectionState.Ready(9_000, List(8) { PlaceRow(PLACE_BASE + it + 1, "Luogo ${it + 1}", 900 - it * 50) }, 1_240)

  @Test
  fun laRigaMappaCeSubitoSenzaNumeri_PoiIConti() {
    val first = PlacesSectionSources.sectionOf(resources, null)
    assertThat(first.entries.map { it.code }).containsExactly(PlacesEntryTarget.MAP)
    assertThat(first.entries.single().title).isEqualTo("Mappa")
    assertThat(first.title).isEqualTo("Luoghi")
    assertThat(first.hideable).isTrue()
    val ready = PlacesSectionSources.sectionOf(resources, readyState)
    assertThat(ready.entries).hasSize(1 + 8 + 1)
    assertThat(ready.entries.first().subtitle).isEqualTo("9.000 foto con la posizione")
    assertThat(ready.entries[1].title).isEqualTo("Luogo 1")
    assertThat(ready.entries[1].subtitle).isEqualTo("900 foto")
    assertThat(ready.entries.last().title).isEqualTo("1.240 foto senza posizione")
    val noGps = PlacesSectionSources.sectionOf(resources, PlacesSectionState.NoGps(320))
    assertThat(noGps.entries.map { it.title }).containsExactly("Mappa", "320 foto senza posizione").inOrder()
    assertThat(noGps.entries.first().subtitle).isEqualTo("Nessuna foto con la posizione")
    // Nessuna riga "senza posizione" quando sono zero.
    assertThat(PlacesSectionSources.sectionOf(resources, PlacesSectionState.Ready(5, emptyList(), 0)).entries).hasSize(1)
  }

  @Test
  fun iCodiciSonoStabiliEIBersagliSiRiconoscono() {
    val section = PlacesSectionSources.sectionOf(resources, readyState)
    assertThat(section.entries.map { it.code }.toSet()).hasSize(section.entries.size)
    val targets = section.entries.map { PlacesEntryTarget.parse(it.code) }
    assertThat(targets.first()).isEqualTo(PlacesEntryTarget.OpenMap)
    assertThat(targets.filterIsInstance<PlacesEntryTarget.Place>()).hasSize(8)
    assertThat(targets.last()).isEqualTo(PlacesEntryTarget.Inert)
    assertThat(PlacesEntryTarget.parse("place_3000001")).isEqualTo(PlacesEntryTarget.Place(3_000_001))
    for (hostile in listOf("place_", "place_-1", "place_0", "place_12x", "place_99999999999", "mappa", "memories_all", "")) assertThat(PlacesEntryTarget.parse(hostile)).isNull()
    // Un luogo non e' mai nominato nei log: nemmeno col suo id.
    assertThat(section.entries[1].toString()).isEqualTo("MapSectionEntry(PlacesEntryTarget.Place)")
    assertThat(section.toString()).doesNotContain("Luogo")
  }

  /** CU-15 (fase H, giro 2): la Mappa e' un mosaico dei quattro luoghi piu' fotografati, non la foto del primo (che e' la riga sotto). */
  @Test
  fun laMappaHaIlMosaicoDeiLuoghiPiuFotografatiENonLaFotoDelPrimo() {
    val section = PlacesSectionSources.sectionOf(resources, readyState)
    val map = section.entries.first()
    assertThat(map.coverPlace).isEqualTo(0)
    assertThat(map.coverPlaces).containsExactlyElementsIn(readyState.places.take(PlacesSectionSources.MAP_MOSAIC).map { it.placeId }).inOrder()
    // Senza luoghi (prima lettura, nessun GPS) nessun mosaico: la piastrella della famiglia con la mappa.
    assertThat(PlacesSectionSources.sectionOf(resources, null).entries.first().coverPlaces).isEmpty()
    assertThat(PlacesSectionSources.sectionOf(resources, PlacesSectionState.NoGps(320)).entries.first().coverPlaces).isEmpty()
  }

  @Test
  fun nessunaVoceDirraMaiLaParolaCasa() {
    val names = TestPlaceNames(special = mapOf(PLACE_BASE + 1 to "Casa"))
    val state = PlacesStates.of(ready(photos(Cities.FIRENZE, 30, PLACE_BASE + 1) + photos(Cities.ROMA, 3, PLACE_BASE + 2)), names)
    val section = PlacesSectionSources.sectionOf(resources, state)
    for (entry in section.entries) assertThat((entry.title + " " + entry.subtitle).lowercase()).doesNotContain("casa")
  }

  // --- Il modello: l'indice solo quando qualcuno guarda ------------------------------------------------------------------------------

  @Test
  fun l_IndiceSiCostruisceAlPrimoSguardo_MaiPrima() = runTest {
    val points = FakeMapPoints()
    val model = PlacesSectionModel(points, TestPlaceNames(), backgroundScope, UnconfinedTestDispatcher(testScheduler))
    runCurrent()
    // Nessuno guarda ancora: niente letto, niente costruito (principio "Avvio e pagine istantanei").
    assertThat(points.rebuilds).isEmpty()
    val seen = ArrayList<PlacesSectionState?>()
    val job = backgroundScope.launch { model.state.collect { seen += it } }
    runCurrent()
    assertThat(points.rebuilds).containsExactly(RebuildReason.OPEN)
    assertThat(seen.last()).isNull()
    points.set(ready(photos(Cities.FIRENZE, 3, PLACE_BASE + 1)))
    runCurrent()
    assertThat(seen.last()).isInstanceOf(PlacesSectionState.Ready::class.java)
    job.cancel()
  }

  // --- Il legame con le Raccolte (come Persone e Ricordi) -------------------------------------------------------------------------------

  private fun MapSectionData.toGroup(): CollectionEntryGroup = CollectionEntryGroup(
    title = title,
    entries = entries.map { CollectionEntry(it.code, it.title, it.subtitle, it.icon, it.tone, it.badge, it.trailing) },
    code = code,
    hideable = hideable,
    hint = hint,
  )

  @Test
  fun ilCodiceEQuelloDelleRaccolte_EIlGruppoVeroAccettaLaSezioneInOgniStato() {
    assertThat(PlacesSectionSources.CODE).isEqualTo(CollectionSections.PLACES)
    assertThat(CollectionSections.isValidCode(PlacesSectionSources.CODE)).isTrue()
    // Subito dopo Ricordi nell'ordine predefinito (E-L14: in coda se l'ordine e' gia' salvato, lo fa feature-library).
    val order = CollectionSections.defaultOrder
    assertThat(order.indexOf(CollectionSections.PLACES)).isEqualTo(order.indexOf(CollectionSections.MEMORIES) + 1)
    for (state in listOf<PlacesSectionState?>(null, readyState, PlacesSectionState.NoGps(320))) {
      val group = PlacesSectionSources.sectionOf(resources, state).toGroup()
      assertThat(group.code).isEqualTo(CollectionSections.PLACES)
      assertThat(group.hideable).isTrue()
      assertThat(group.entries).isNotEmpty()
      for (entry in group.entries) {
        assertThat(CollectionEntries.find(listOf(group), entry.code)).isEqualTo(entry)
        assertThat(PlacesEntryTarget.parse(entry.code)).isNotNull()
      }
      // Il layout vero tiene la sezione anche prima dei numeri.
      val kept = CollectionsLayout.sectionOf(PlacesSectionSources.CODE, group)
      assertThat(kept).isNotNull()
      assertThat(kept!!.rows).isNotEmpty()
    }
  }

  @Test
  fun ilFornitoreVeroDelleRaccolteConsegnaLaSezione() {
    val provider = FixedCollectionsSection(PlacesSectionSources.CODE, PlacesSectionSources.sectionOf(resources, readyState).toGroup())
    assertThat(provider.code).isEqualTo(CollectionSections.PLACES)
    assertThat(provider.group.value?.entries?.map { it.title }).contains("Mappa")
    assertThat(provider.group.value?.code).isEqualTo(provider.code)
  }
}
