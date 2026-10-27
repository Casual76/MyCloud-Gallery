package dev.pampa.mycloudgallery.core.albums.smart

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.PlaceScope
import dev.pampa.mycloudgallery.core.albums.contract.ProposalNote
import dev.pampa.mycloudgallery.core.albums.contract.RuleProposal
import dev.pampa.mycloudgallery.core.albums.contract.SmartLimits
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.SmartSource
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.places.testing.FakePlaces
import dev.pampa.mycloudgallery.core.search.contract.PlaceLevel
import dev.pampa.mycloudgallery.core.search.contract.PlaceName
import dev.pampa.mycloudgallery.core.search.engine.LibraryPlaces
import dev.pampa.mycloudgallery.core.search.parse.ItalianQueryParser
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Il nome diventa regole (D13): centro e raggio del luogo, il raffinamento in serie di giorni (E-AL1, E-AL12), cio' che non conta (familiari,
 * screenshot, nascosti), la nota del motore di ricerca (D15), il dizionario dei luoghi uguale a quello di Cerca. La tabella dei titoli e' in
 * `TitleTableTest`.
 */
class SmartInterpreterTest {
  private val today = LocalDate.of(2026, 12, 20)

  private fun interpreter(world: MemoryWorld, search: SmartContentSearch = NoSmartContentSearch, geocoder: FakePlaces? = null) =
    RuleSmartNameInterpreter(ItalianQueryParser(), world, world, world.candidates(), world.placeLookup, search, geocoder)

  private suspend fun propose(world: MemoryWorld, title: String, search: SmartContentSearch = NoSmartContentSearch): RuleProposal =
    interpreter(world, search).propose(title, today)

  /** Un viaggio a Oslo: [days] giorni, quattro foto al giorno, tutte nostre col GPS. */
  private fun osloTrip(world: MemoryWorld, firstId: Int, vararg days: LocalDate, device: String? = "phone", source: SmartSource = SmartSource.MY_PHONE) {
    var n = firstId
    for (d in days) repeat(4) { k -> world.put(cand(n++, d, 9 * 60 + k * 60, gps = Spots.north(Spots.OSLO, 1.0 + k), placeId = Spots.OSLO_ID, device = device, source = source)) }
  }

  private fun osloWorld(vararg days: LocalDate): MemoryWorld = MemoryWorld(emptyList(), Spots.PLACES).also {
    osloTrip(it, 1, *days)
    it.publish()
  }

  private fun series(proposal: RuleProposal): List<LocalDayRange> = (proposal.rules.period as SmartPeriod.Ranges).ranges

  // --- Centro e raggio -----------------------------------------------------------------------------------------------------------

  @Test
  fun ilCentroDelLuogoELaMedianaDelleSueFoto() = runTest {
    val world = osloWorld(day(2026, 3, 14), day(2026, 3, 15))
    val place = propose(world, "Oslo 2026").rules.place!!
    assertThat(place.scope).isEqualTo(PlaceScope.AROUND)
    assertThat(place.radiusKm).isEqualTo(SmartLimits.CITY_RADIUS_KM)
    // R16: il centro scritto nei dati personali sta su una griglia di 0,1 gradi (~11 km): dice la citta', non il punto esatto (la casa).
    assertThat(SmartGeo.distanceKm(place.center, Spots.OSLO)).isLessThan(8.0)
    assertThat(kotlin.math.abs(place.center.latitude * 10 - kotlin.math.round(place.center.latitude * 10))).isLessThan(1e-6)
    assertThat(kotlin.math.abs(place.center.longitude * 10 - kotlin.math.round(place.center.longitude * 10))).isLessThan(1e-6)
  }

  @Test
  fun ilRaggioDiUnLuogoVicinoEQuindiciChilometri() = runTest {
    // Le foto stanno a 30 km dal punto di Oslo del dataset: "vicino a Oslo" (fra 15 e 50 km): 15 km attorno alle foto.
    val world = MemoryWorld(emptyList(), Spots.PLACES)
    var n = 1
    for (d in listOf(day(2026, 3, 14), day(2026, 3, 15))) repeat(3) { world.put(cand(n++, d, gps = Spots.north(Spots.OSLO, 30.0 + it * 0.2), placeId = Spots.OSLO_ID)) }
    world.publish()
    val near = interpreter(world, geocoder = FakePlaces()).propose("Oslo 2026", today).rules.place!!
    assertThat(near.radiusKm).isEqualTo(SmartLimits.NEAR_RADIUS_KM)
    // Senza il dataset (o con il luogo a meno di 15 km) vale la citta'.
    assertThat(interpreter(world).propose("Oslo 2026", today).rules.place!!.radiusKm).isEqualTo(SmartLimits.CITY_RADIUS_KM)
    val city = interpreter(osloWorld(day(2026, 3, 14)), geocoder = FakePlaces()).propose("Oslo 2026", today).rules.place!!
    assertThat(city.radiusKm).isEqualTo(SmartLimits.CITY_RADIUS_KM)
  }

  @Test
  fun regioneEPaeseHannoIlConfineEIlRaggioNonConta() = runTest {
    val world = TitleLibrary.world()
    val region = interpreter(world).propose("Toscana", today).rules.place!!
    assertThat(region.scope).isEqualTo(PlaceScope.REGION)
    assertThat(region.radiusKm).isEqualTo(SmartLimits.MAX_RADIUS_KM)
    val country = interpreter(world).propose("Norvegia", today).rules.place!!
    assertThat(country.scope).isEqualTo(PlaceScope.COUNTRY)
  }

  // --- Il raffinamento (D13) ----------------------------------------------------------------------------------------------------

  @Test
  fun ilPeriodoDiventaLaSerieDeiGiorniDelViaggioPiuOMenoUnGiorno() = runTest {
    val world = osloWorld(day(2026, 3, 14), day(2026, 3, 15), day(2026, 3, 16))
    val proposal = propose(world, "Oslo 2026")
    assertThat(series(proposal)).containsExactly(LocalDayRange(day(2026, 3, 13), day(2026, 3, 17)))
    assertThat(proposal.periodSeries).containsExactly(LocalDayRange(day(2026, 3, 13), day(2026, 3, 17)))
    assertThat(proposal.notes).isEmpty()
  }

  @Test
  fun e_AL1UnaSerieChePassaIl31DicembreRestaUna() = runTest {
    val world = osloWorld(day(2026, 12, 29), day(2026, 12, 30), day(2026, 12, 31), day(2027, 1, 1), day(2027, 1, 2))
    // L'anno del titolo e' il 2026, ma anche chi parte nel 2027 e' un solo viaggio quando il titolo li prende tutti ("inverno 2026").
    val winter = propose(world, "Oslo inverno 2026")
    assertThat(series(winter)).containsExactly(LocalDayRange(day(2026, 12, 28), day(2027, 1, 3)))
    val year = propose(world, "Oslo 2026")
    assertThat(series(year)).containsExactly(LocalDayRange(day(2026, 12, 28), day(2027, 1, 1)))
  }

  @Test
  fun e_AL12DueViaggiNelloStessoLuogoENelloStessoAnnoSonoDueSerie() = runTest {
    val world = osloWorld(day(2026, 3, 14), day(2026, 3, 15), day(2026, 12, 3), day(2026, 12, 4))
    val proposal = propose(world, "Oslo 2026")
    assertThat(series(proposal)).containsExactly(LocalDayRange(day(2026, 3, 13), day(2026, 3, 16)), LocalDayRange(day(2026, 12, 2), day(2026, 12, 5))).inOrder()
    assertThat(proposal.periodSeries).hasSize(2)
    assertThat(proposal.notes).containsExactly(ProposalNote.SEVERAL_SERIES)
  }

  @Test
  fun unBucoDiDueGiorniNonSpezzaIlViaggioTreSi() = runTest {
    // 14 e 15, poi 18 (due giorni senza foto: 16 e 17): una serie. Poi 22 (tre giorni senza foto): un'altra.
    val world = osloWorld(day(2026, 3, 14), day(2026, 3, 15), day(2026, 3, 18), day(2026, 3, 22))
    val s = series(propose(world, "Oslo 2026"))
    assertThat(s).containsExactly(LocalDayRange(day(2026, 3, 13), day(2026, 3, 19)), LocalDayRange(day(2026, 3, 21), day(2026, 3, 23))).inOrder()
  }

  @Test
  fun serieVicineAttaccateDopoLAllargamentoSiFondono() {
    // 14 e 17: distano 3 giorni (stessa serie); 14 e 18 distano 4 ma, allargate, [13..15] e [17..19] non si toccano.
    assertThat(PeriodSeries.build(listOf(day(2026, 3, 14), day(2026, 3, 17)))).containsExactly(LocalDayRange(day(2026, 3, 13), day(2026, 3, 18)))
    assertThat(PeriodSeries.build(listOf(day(2026, 3, 14), day(2026, 3, 18)))).containsExactly(
      LocalDayRange(day(2026, 3, 13), day(2026, 3, 15)), LocalDayRange(day(2026, 3, 17), day(2026, 3, 19)),
    ).inOrder()
    // Con un margine piu' largo [12..16] e [16..20] si toccano e diventano una.
    assertThat(PeriodSeries.build(listOf(day(2026, 3, 14), day(2026, 3, 18)), marginDays = 2)).containsExactly(LocalDayRange(day(2026, 3, 12), day(2026, 3, 20)))
    assertThat(PeriodSeries.build(emptyList())).isEmpty()
  }

  @Test
  fun unPeriodoCortoNonSiRaffina() = runTest {
    val world = osloWorld(day(2026, 3, 14), day(2026, 3, 15))
    // Un intervallo scelto dall'utente (7 giorni) e' quello: le foto di Oslo ci sono ma il periodo non cambia.
    val proposal = propose(world, "Oslo dal 12 al 18 marzo 2026")
    assertThat(series(proposal)).containsExactly(LocalDayRange(day(2026, 3, 12), day(2026, 3, 18)))
    assertThat(proposal.periodSeries).isEmpty()
  }

  @Test
  fun senzaFotoNelLuogoRestaIlPeriodoDelTitoloConUnaNota() = runTest {
    val world = osloWorld(day(2025, 6, 1))
    val proposal = propose(world, "Oslo 2026")
    assertThat(series(proposal)).containsExactly(LocalDayRange(day(2026, 1, 1), day(2026, 12, 31)))
    assertThat(proposal.notes).containsExactly(ProposalNote.NO_PHOTOS_IN_PLACE)
  }

  @Test
  fun leSerieNonContanoFamiliariScreenshotNeNascosti() = runTest {
    val world = osloWorld(day(2026, 3, 14))
    // Un familiare, uno screenshot col GPS e un nascosto a Oslo in altri giorni: non fanno parte del viaggio proprio e non si vedono.
    world.put(cand(500, day(2026, 7, 1), gps = Spots.north(Spots.OSLO, 1.0), placeId = Spots.OSLO_ID, source = SmartSource.FAMILY, device = null))
    world.put(cand(501, day(2026, 8, 1), gps = Spots.north(Spots.OSLO, 1.0), placeId = Spots.OSLO_ID, screen = true))
    world.put(cand(502, day(2026, 9, 1), gps = Spots.north(Spots.OSLO, 1.0), placeId = Spots.OSLO_ID))
    world.hide(id(502))
    world.publish()
    val proposal = propose(world, "Oslo 2026")
    assertThat(series(proposal)).containsExactly(LocalDayRange(day(2026, 3, 13), day(2026, 3, 15)))
    // Un mio media nell'area comune (E-AL17 al contrario) e' mio e conta.
    world.put(cand(503, day(2026, 10, 1), gps = Spots.north(Spots.OSLO, 1.0), placeId = Spots.OSLO_ID, source = SmartSource.COMMON, owned = true))
    world.publish()
    assertThat(series(propose(world, "Oslo 2026")).last()).isEqualTo(LocalDayRange(day(2026, 9, 30), day(2026, 10, 2)))
  }

  @Test
  fun unGruppoConUnNascostoNonLasciaTraccia() = runTest {
    // Le foto di un gruppo con un membro nascosto non entrano nel viaggio (le query dei candidati non hanno il contagio: lo fa VisibleMedia).
    val world = osloWorld(day(2026, 3, 14))
    world.put(cand(600, day(2026, 5, 1), gps = Spots.north(Spots.OSLO, 1.0), placeId = Spots.OSLO_ID))
    world.put(cand(601, day(2026, 5, 1), gps = Spots.north(Spots.OSLO, 1.0), placeId = Spots.OSLO_ID))
    world.groups += setOf(id(600), id(601))
    world.hide(id(601))
    world.publish()
    assertThat(series(propose(world, "Oslo 2026"))).containsExactly(LocalDayRange(day(2026, 3, 13), day(2026, 3, 15)))
  }

  // --- Omonimi e piu' luoghi -----------------------------------------------------------------------------------------------------

  @Test
  fun gliOmoniminiHannoIlPaeseELaRegioneNellEtichettaEIlConteggio() = runTest {
    val proposal = propose(TitleLibrary.world(), "San Marco")
    assertThat(proposal.rules.place!!.label).isEqualTo("San Marco (Veneto)")
    assertThat(proposal.alternatives.map { it.rule.label }).containsExactly("San Marco (Toscana)")
    assertThat(proposal.alternatives.map { it.mediaCount }).containsExactly(6)
    assertThat(proposal.alternatives.single().rule.placeIds).containsExactly(6004)
  }

  @Test
  fun lAlternativaDiUnOmonimoDiUnLuogoEUnPaeseHaIlTipoNellEtichetta() = runTest {
    val world = MemoryWorld(emptyList(), mapOf(1 to PlaceName("Panama", "Canale", "Panama-Stato"), 2 to PlaceName("Zeta", "Zeta", "Panama")))
    for (i in 1..4) world.put(cand(i, day(2026, 1, i), gps = Spots.LISBONA, placeId = 1))
    for (i in 5..6) world.put(cand(i, day(2026, 1, i), gps = Spots.ROMA, placeId = 2))
    world.publish()
    val proposal = propose(world, "Panama")
    // Il luogo "Panama" (4 media) e il paese "Panama" (2): uno ha il tipo "luogo", l'altro "paese".
    assertThat(proposal.rules.place!!.label).isEqualTo("Panama (luogo)")
    assertThat(proposal.alternatives.map { it.rule.label }).containsExactly("Panama (paese)")
    assertThat(proposal.alternatives.single().rule.scope).isEqualTo(PlaceScope.COUNTRY)
  }

  // --- Cio' che il titolo non dice -----------------------------------------------------------------------------------------------

  @Test
  fun senzaIlMotoreDiRicercaLaDescrizioneDicePerche() = runTest {
    val world = TitleLibrary.world()
    assertThat(propose(world, "Gatto").notes).contains(ProposalNote.NO_SEARCH)
    // Con il motore pronto la nota non c'e'; senza descrizione non c'e' mai.
    val ready = ScriptedContent(ready = true)
    assertThat(propose(world, "Gatto", ready).notes).doesNotContain(ProposalNote.NO_SEARCH)
    assertThat(propose(world, "Oslo 2026").notes).doesNotContain(ProposalNote.NO_SEARCH)
    // Il motore che si sta caricando non e' pronto: la descrizione tace e l'editor lo dice.
    assertThat(propose(world, "Gatto", ScriptedContent(ready = false)).notes).contains(ProposalNote.NO_SEARCH)
  }

  @Test
  fun unTitoloLungoOStranoNonLanciaMai() = runTest {
    val world = TitleLibrary.world()
    val interpreter = interpreter(world)
    for (title in listOf("a".repeat(400), "ŒŒ \u0000 2026", "2026 2026 2026", "Oslo ".repeat(60), "12/13/14", "dal 31 al 1", "   Oslo   2026   ")) {
      val proposal = interpreter.propose(title, today)
      assertThat(proposal.rules.text?.length ?: 0).isAtMost(400)
    }
    assertThat(interpreter.propose("   Oslo   2026   ", today).rules.place!!.placeIds).containsExactly(3143244)
  }

  @Test
  fun leLibrerieSenzaUnaIstantaneaNonConosconoLuoghi() = runTest {
    val world = MemoryWorld(emptyList(), Spots.PLACES)
    val proposal = propose(world, "Oslo 2026")
    assertThat(proposal.rules.place).isNull()
    assertThat(proposal.rules.text).isEqualTo("Oslo")
    assertThat(proposal.notes).containsAtLeast(ProposalNote.NO_PLACE, ProposalNote.NO_SEARCH)
  }

  // --- Il dizionario e' quello di Cerca --------------------------------------------------------------------------------------------

  @Test
  fun ilDizionarioDeiLuoghiEUgualeAQuelloDellaRicerca() {
    val world = TitleLibrary.world()
    val snapshot: TimelineSnapshot = world.snapshot.value!!
    val mine = SmartLibraryPlaces.build(snapshot, TitleLibrary.lookup).dictionary.entries
    val theirs = LibraryPlaces(TitleLibrary.lookup).dictionary(snapshot).entries
    assertThat(mine).containsExactlyElementsIn(theirs).inOrder()
    assertThat(mine.any { it.option.level == PlaceLevel.REGION }).isTrue()
    assertThat(mine.any { it.option.level == PlaceLevel.COUNTRY }).isTrue()
  }
}
