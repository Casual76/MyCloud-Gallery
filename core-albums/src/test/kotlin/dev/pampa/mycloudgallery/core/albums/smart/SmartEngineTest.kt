package dev.pampa.mycloudgallery.core.albums.smart

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.SmartOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.contract.SmartSource
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaId
import java.time.LocalDate
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Il motore (D14-D16) con le righe in memoria: cosa scrive, cosa non tocca mai (le scelte dell'utente), le esclusioni per sempre (E-AL5),
 * il congelato (E-AL14), le regole cambiate (E-AL13), i nascosti (E-AL8), il motore di ricerca che non c'e' (E-AL9, D15), il budget con la ripresa,
 * l'anteprima (E-AL6) e "Nuove: 12" (E-AL7).
 */
class SmartEngineTest {
  private val d1 = day(2026, 3, 10)
  private val d2 = d1.plusDays(1)
  private val d3 = d1.plusDays(2)
  private val trip = ranges(d1 to d3)
  private val oslo = around(Spots.OSLO, 25)

  private class Rig(rules: SmartRules, search: SmartContentSearch = NoSmartContentSearch, maskOf: ((MemoryWorld) -> HiddenMask)? = null) {
    val world = MemoryWorld()
    val rows = InMemorySmartRows()
    val clocks = FakeAlbumClocks()
    val seen = InMemorySeenMarks()
    val id = albumId(1)
    val album = rows.create(id, rules)
    val engine = RulesSmartEngine(rows, world, world, maskOf?.invoke(world) ?: world, search, DeviceNormalizer("Backup/questo-telefono"), Spots.LOOKUP, clocks, seen)
  }

  private fun inOslo(km: Double = 3.0): GeoPoint = Spots.north(Spots.OSLO, km)

  /**
   * Un giorno di viaggio a Oslo, 9 media da [first]: tre sicuri A (09:00, 12:00, 15:00), uno B (13:30 senza GPS fra due ancore), un S5 (20:30 senza
   * GPS, fuori dalla finestra), un S2 (screenshot), un S1 (familiare senza GPS), un S3 (30 km) e uno fuori (a Bergen).
   */
  private fun tripDay(world: MemoryWorld, first: Int, day: LocalDate) {
    world.put(
      cand(first, day, 9 * 60, gps = inOslo(), placeId = Spots.OSLO_ID),
      cand(first + 1, day, 12 * 60, gps = inOslo(), placeId = Spots.OSLO_ID),
      cand(first + 2, day, 15 * 60, gps = inOslo(), placeId = Spots.OSLO_ID),
      cand(first + 3, day, 13 * 60 + 30),
      cand(first + 4, day, 20 * 60 + 30),
      cand(first + 5, day, 10 * 60, screen = true),
      cand(first + 6, day, 11 * 60, source = SmartSource.FAMILY, device = null),
      cand(first + 7, day, 11 * 60 + 30, gps = Spots.north(Spots.OSLO, 30.0), placeId = Spots.OSLO_ID),
      cand(first + 8, day, 11 * 60 + 45, gps = Spots.BERGEN, placeId = Spots.BERGEN_ID),
    )
  }

  private fun Rig.fill() {
    tripDay(world, 100, d1)
    tripDay(world, 200, d2)
    tripDay(world, 300, d3)
  }

  private fun Rig.sure(): Set<MediaId> = rows.albums.getValue(id).auto.keys

  private fun Rig.suggested(): Map<MediaId, String> = rows.albums.getValue(id).suggestions.mapValues { it.value.reason }

  // --- Cosa scrive -----------------------------------------------------------------------------------------------------------------

  @Test
  fun scriveISicuriEISuggeritiConLaRegola() = runTest {
    val rig = Rig(SmartRules(trip, oslo, null)).also { it.fill() }
    val outcome = rig.engine.evaluate(rig.id, Duration.INFINITE)
    assertThat(outcome).isEqualTo(SmartOutcome.Done(confident = 12, suggested = 12, newSinceSeen = 0))
    assertThat(rig.sure()).containsExactlyElementsIn((0..3).flatMap { listOf(100, 200, 300).map { b -> id(b + it) } })
    val first = rig.suggested()
    assertThat(first[id(104)]).isEqualTo("s5")
    assertThat(first[id(105)]).isEqualTo("s2")
    assertThat(first[id(106)]).isEqualTo("s1")
    assertThat(first[id(107)]).isEqualTo("s3")
    assertThat(first).doesNotContainKey(id(108))
    // La posizione di un sicuro e' il suo istante di scatto: "Personalizzato" lo tiene in ordine di data.
    val row = rig.rows.albums.getValue(rig.id).auto.getValue(id(100))
    assertThat(row.position).isEqualTo(cand(100, d1, 9 * 60).c.takenAtUtcMs)
    assertThat(row.addedAt).isEqualTo(rig.clocks.nasNowMs())
  }

  @Test
  fun unSecondoGiroNonRiscriveNiente() = runTest {
    val rig = Rig(SmartRules(trip, oslo, null)).also { it.fill() }
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    val applies = rig.rows.applies
    val before = rig.rows.snapshot(rig.id)
    rig.clocks.advance(60_000)
    assertThat(rig.engine.evaluate(rig.id, Duration.INFINITE)).isEqualTo(SmartOutcome.Done(12, 12, 0))
    assertThat(rig.rows.applies).isEqualTo(applies)
    // Le righe che ci sono gia' conservano il loro istante (da quando sono arrivate).
    assertThat(rig.rows.snapshot(rig.id)).isEqualTo(before)
  }

  @Test
  fun unAlbumCheNonCEEUnoSmartCongelatoOCondivisoNonSiToccano() = runTest {
    val rig = Rig(SmartRules(trip, oslo, null)).also { it.fill() }
    assertThat(rig.engine.evaluate(albumId(99), Duration.INFINITE)).isEqualTo(SmartOutcome.NotFound)
    rig.album.frozen = true
    assertThat(rig.engine.evaluate(rig.id, Duration.INFINITE)).isEqualTo(SmartOutcome.Frozen)
    assertThat(rig.engine.reevaluate(rig.id, listOf(d1))).isEqualTo(SmartOutcome.Frozen)
    assertThat(rig.sure()).isEmpty()
    assertThat(rig.rows.applies).isEqualTo(0)
  }

  @Test
  fun e_AL14UnCongelatoNonApplicaLeRegoleQualunqueSiano() = runTest {
    val rig = Rig(SmartRules(trip, oslo, null)).also { it.fill() }
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    val before = rig.rows.snapshot(rig.id)
    rig.album.frozen = true
    rig.album.rules = SmartRules(trip, around(Spots.ROMA, 25, listOf(Spots.ROMA_ID)), null)
    assertThat(rig.engine.evaluate(rig.id, Duration.INFINITE)).isEqualTo(SmartOutcome.Frozen)
    assertThat(rig.rows.snapshot(rig.id)).isEqualTo(before)
  }

  // --- Le scelte dell'utente -------------------------------------------------------------------------------------------------------

  @Test
  fun e_AL5LEsclusioneEPerSempreEAncheSeLeRegoleCambiano() = runTest {
    val rig = Rig(SmartRules(trip, oslo, null)).also { it.fill() }
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    assertThat(rig.sure()).contains(id(100))
    rig.album.exclusions += id(100)
    rig.album.exclusions += id(106)
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    assertThat(rig.sure()).doesNotContain(id(100))
    assertThat(rig.suggested()).doesNotContainKey(id(106))
    // Le regole cambiano (un luogo piu' largo, un periodo piu' lungo): l'escluso resta fuori.
    rig.album.rules = SmartRules(ranges(d1.minusDays(5) to d3.plusDays(5)), around(Spots.OSLO, 100), null)
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    assertThat(rig.sure()).doesNotContain(id(100))
    assertThat(rig.sure()).contains(id(101))
  }

  @Test
  fun e_AL13UnaAccettazioneEUnElementoAMano_ILMotoreNonLoRiscriveMaiNeLoSuggerisce() = runTest {
    val rig = Rig(SmartRules(trip, oslo, null)).also { it.fill() }
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    // L'utente accetta il suggerito S1 (106) e aggiunge a mano un sicuro (101): diventano elementi suoi (origin accepted/manual).
    rig.album.owned += id(106)
    rig.album.owned += id(101)
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    assertThat(rig.sure()).doesNotContain(id(101))
    assertThat(rig.suggested()).doesNotContainKey(id(106))
    // Cambiano le regole e il periodo non li comprende piu': gli elementi dell'utente restano suoi (le righe automatiche no).
    rig.album.rules = SmartRules(ranges(d3 to d3), oslo, null)
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    assertThat(rig.album.owned).containsAtLeast(id(106), id(101))
    assertThat(rig.sure()).containsExactlyElementsIn(listOf(300, 301, 302, 303).map { id(it) })
  }

  // --- I nascosti (E-AL8, D22) -----------------------------------------------------------------------------------------------------

  @Test
  fun e_AL8UnNascostoNonEMaiUnElementoNeUnSuggeritoNeUnaAncora() = runTest {
    val rig = Rig(SmartRules(trip, oslo, null)).also { it.fill() }
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    assertThat(rig.sure()).contains(id(103))
    // Si nascondono due ancore (100 e 102) e il suggerito S1 (106): spariscono, e la B del 103 che contava su di loro cade.
    rig.world.hide(id(100))
    rig.world.hide(id(102))
    rig.world.hide(id(106))
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    assertThat(rig.sure()).containsNoneOf(id(100), id(102))
    assertThat(rig.suggested()).doesNotContainKey(id(106))
    // Dopo aver perso due ancore su tre, la foto delle 13:30 non e' piu' fra due sicuri: e' un dubbio.
    assertThat(rig.sure()).doesNotContain(id(103))
    assertThat(rig.suggested()[id(103)]).isEqualTo("s5")
    // Riappare: torna com'era (stesso risultato di prima).
    rig.world.unhide(listOf(id(100), id(102), id(106)))
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    assertThat(rig.sure()).containsAtLeast(id(100), id(102), id(103))
  }

  @Test
  fun unGruppoConUnMembroNascostoNonRestaAncheSeLeQueryDeiCandidatiNonLoSanno() = runTest {
    val rig = Rig(SmartRules(trip, oslo, null)).also { it.fill() }
    rig.world.groups += setOf(id(100), id(101))
    rig.world.hide(id(101))
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    assertThat(rig.sure()).containsNoneOf(id(100), id(101))
  }

  @Test
  fun unMediaNascostoUnAttimoFaNonDiventaUnElementoIlFiltroFinale() = runTest {
    val late = id(100)
    val rig = Rig(SmartRules(trip, oslo, null), maskOf = { w ->
      object : HiddenMask {
        override suspend fun hiddenAmong(ids: Collection<MediaId>): Set<MediaId> {
          // Il filtro finale, subito prima di scrivere, trova un media nascosto nel frattempo.
          return w.hiddenAmong(ids) + ids.filter { it == late }
        }
      }
    }).also { it.fill() }
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    assertThat(rig.sure()).doesNotContain(late)
    assertThat(rig.sure()).contains(id(101))
  }

  // --- Il motore di ricerca (D15, E-AL9) -------------------------------------------------------------------------------------------

  @Test
  fun senzaIlMotoreUnAlbumDiSolaDescrizioneEVuotoEComunqueValuta() = runTest {
    val rig = Rig(SmartRules(null, null, "gatto")).also { it.fill() }
    assertThat(rig.engine.evaluate(rig.id, Duration.INFINITE)).isEqualTo(SmartOutcome.Done(0, 0, 0))
    assertThat(rig.sure()).isEmpty()
    assertThat(rig.suggested()).isEmpty()
  }

  @Test
  fun conIlMotoreLaDescrizioneSicuraEntraELaMenoProbabileESuggerita() = runTest {
    val search = ScriptedContent()
    val rig = Rig(SmartRules(trip, null, "gatto"), search).also { it.fill() }
    search.sure = setOf(id(103), id(104))
    search.likely = setOf(id(105))
    val outcome = rig.engine.evaluate(rig.id, Duration.INFINITE)
    // 103 e 104 sono miei e sicuri (C); 105 e' uno screenshot "meno probabile": S4.
    assertThat(rig.sure()).containsExactly(id(103), id(104))
    assertThat(rig.suggested()).containsExactly(id(105), "s4")
    assertThat(outcome).isEqualTo(SmartOutcome.Done(2, 1, 0))
  }

  @Test
  fun unMotoreCheScompareNonSvuotaLAlbum() = runTest {
    val search = ScriptedContent()
    val rig = Rig(SmartRules(trip, null, "gatto"), search).also { it.fill() }
    search.sure = setOf(id(103), id(104))
    search.likely = setOf(id(105))
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    val before = rig.rows.snapshot(rig.id)
    // Il modello si sta ricaricando: le regole C e S4 tacciono, ma l'album non perde cio' che aveva.
    search.ready = false
    assertThat(rig.engine.evaluate(rig.id, Duration.INFINITE)).isEqualTo(SmartOutcome.Done(2, 1, 0))
    assertThat(rig.rows.snapshot(rig.id)).isEqualTo(before)
  }

  @Test
  fun conUnLuogoEUnMotoreAssenteLeRegoleDiDataEGpsAgisconoLoStesso() = runTest {
    val search = ScriptedContent(ready = false)
    val rig = Rig(SmartRules(trip, oslo, "gatto"), search).also { it.fill() }
    assertThat(rig.engine.evaluate(rig.id, Duration.INFINITE)).isEqualTo(SmartOutcome.Done(12, 12, 0))
  }

  @Test
  fun iSuggeritiS4SopravvivonoAlMotoreCheNonCe() = runTest {
    val search = ScriptedContent()
    // Senza periodo: una foto senza GPS entra solo dalla descrizione.
    val rig = Rig(SmartRules(null, oslo, "gatto"), search).also { it.fill() }
    search.likely = setOf(id(104))
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    assertThat(rig.suggested()).containsEntry(id(104), "s4")
    search.ready = false
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    assertThat(rig.suggested()).containsEntry(id(104), "s4")
    // Il motore torna e non la trova piu' come probabile: allora esce.
    search.ready = true
    search.likely = emptySet()
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    assertThat(rig.suggested()).doesNotContainKey(id(104))
  }

  @Test
  fun unAlbumSenzaRegoleEVuoto() = runTest {
    val rig = Rig(SmartRules(null, null, null)).also { it.fill() }
    assertThat(rig.engine.evaluate(rig.id, Duration.INFINITE)).isEqualTo(SmartOutcome.Done(0, 0, 0))
    assertThat(rig.engine.preview(SmartRules(null, null, null)).confident).isEqualTo(0)
  }

  // --- Il budget (D16) -------------------------------------------------------------------------------------------------------------

  @Test
  fun conUnBudgetPiccoloSiFermaESiRiprendeDalPunto() = runTest {
    val rig = Rig(SmartRules(ranges(d1 to d1.plusDays(60)), null, null))
    val items = (0 until 5_000).map { cand(it + 1, d1.plusDays((it % 40).toLong()), minutes = 600 + it % 300) }
    rig.world.put(*items.toTypedArray())
    val first = rig.engine.evaluate(rig.id, Duration.ZERO)
    assertThat(first).isEqualTo(SmartOutcome.Partial(2_000, 5_000))
    assertThat(rig.rows.applies).isEqualTo(0)
    assertThat(rig.engine.evaluate(rig.id, Duration.ZERO)).isEqualTo(SmartOutcome.Partial(4_000, 5_000))
    val done = rig.engine.evaluate(rig.id, Duration.ZERO)
    assertThat(done).isEqualTo(SmartOutcome.Done(5_000, 0, 0))
    // Una valutazione intera da zero scrive le stesse righe.
    val other = Rig(SmartRules(ranges(d1 to d1.plusDays(60)), null, null))
    other.world.put(*items.toTypedArray())
    other.engine.evaluate(other.id, Duration.INFINITE)
    assertThat(rig.sure()).isEqualTo(other.sure())
  }

  @Test
  fun leRegoleCambiateCoUnaValutazioneFermaLaFannoRicominciare() = runTest {
    val rig = Rig(SmartRules(ranges(d1 to d1.plusDays(60)), null, null))
    rig.world.put(*(0 until 3_000).map { cand(it + 1, d1.plusDays((it % 40).toLong())) }.toTypedArray())
    assertThat(rig.engine.evaluate(rig.id, Duration.ZERO)).isInstanceOf(SmartOutcome.Partial::class.java)
    rig.album.rules = SmartRules(ranges(d1 to d1.plusDays(5)), null, null)
    val outcome = rig.engine.evaluate(rig.id, Duration.ZERO)
    // Non riprende il caricamento di prima (periodo diverso): ricomincia, e con un solo giorno in meno di una pagina finisce.
    assertThat(outcome).isInstanceOf(SmartOutcome.Done::class.java)
    assertThat(rig.sure().size).isEqualTo(3_000 / 40 * 6)
  }

  // --- L'anteprima (E-AL6) -----------------------------------------------------------------------------------------------------------

  @Test
  fun lAnteprimaConta12MiniatureEIlMotoreCheHaContato() = runTest {
    val rig = Rig(SmartRules(trip, oslo, null)).also { it.fill() }
    rig.world.put(*(0 until 400).map { cand(10_000 + it, d1.minusDays(100)) }.toTypedArray())
    val preview = rig.engine.preview(SmartRules(trip, oslo, null))
    assertThat(preview.confident).isEqualTo(12)
    assertThat(preview.suggested).isEqualTo(12)
    assertThat(preview.sample).hasSize(12)
    assertThat(preview.sample.toSet()).hasSize(12)
    assertThat(preview.tooBroad).isFalse()
    assertThat(preview.searchUsed).isFalse()
    // Non scrive niente.
    assertThat(rig.rows.applies).isEqualTo(0)
  }

  @Test
  fun e_AL6OltreIlVentiPerCentoDellaLibreriaOTremilaElementiSonoTroppoLarghe() = runTest {
    val rig = Rig(SmartRules(trip, oslo, null)).also { it.fill() }
    // Il 12+12 su 27 media visibili e' oltre il 20% della Libreria.
    assertThat(rig.engine.preview(SmartRules(trip, oslo, null)).tooBroad).isTrue()
    // Una Libreria grande lo porta sotto la soglia.
    rig.world.put(*(0 until 400).map { cand(10_000 + it, d1.minusDays(100)) }.toTypedArray())
    assertThat(rig.engine.preview(SmartRules(trip, oslo, null)).tooBroad).isFalse()
    // Oltre 3.000 elementi e' troppo largo comunque.
    val big = Rig(SmartRules(ranges(d1 to d1.plusDays(60)), null, null))
    big.world.put(*(0 until 20_000).map { cand(it + 1, d1.plusDays((it % 50).toLong()) ) }.toTypedArray())
    big.world.put(*(0 until 80_000).map { cand(30_000 + it, d1.minusDays(1000)) }.toTypedArray())
    val preview = big.engine.preview(SmartRules(ranges(d1 to d1.plusDays(60)), null, null))
    assertThat(preview.confident).isEqualTo(20_000)
    assertThat(preview.tooBroad).isTrue()
  }

  @Test
  fun lAnteprimaDiUnaDescrizioneDiceSeIlMotoreHaContato() = runTest {
    val search = ScriptedContent()
    val rig = Rig(SmartRules(trip, null, "gatto"), search).also { it.fill() }
    search.sure = setOf(id(103))
    assertThat(rig.engine.preview(SmartRules(trip, null, "gatto")).searchUsed).isTrue()
    search.ready = false
    val silent = rig.engine.preview(SmartRules(trip, null, "gatto"))
    assertThat(silent.searchUsed).isFalse()
    assertThat(silent.confident).isEqualTo(0)
  }

  // --- "Nuove: 12" e i media arrivati dopo (E-AL7) ---------------------------------------------------------------------------------

  @Test
  fun e_AL7UnMediaArrivatoDopoEntraOVaInSuggeriteEContaTraLeNovita() = runTest {
    val rig = Rig(SmartRules(trip, oslo, null)).also { it.fill() }
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    rig.engine.markSeen(rig.id)
    assertThat(rig.engine.newSinceSeen(rig.id)).isEqualTo(0)
    rig.clocks.advance(86_400_000L * 30)
    // Un mese dopo arrivano da un backup: una foto col GPS giusto (sicura) e una senza GPS di un altro dispositivo (dubbio).
    rig.world.put(
      cand(900, d2, 17 * 60, gps = inOslo(), placeId = Spots.OSLO_ID),
      cand(901, d2, 18 * 60, device = "backup:tablet", source = SmartSource.MY_PRIVATE),
    )
    val outcome = rig.engine.reevaluate(rig.id, listOf(d2))
    assertThat(outcome).isEqualTo(SmartOutcome.Done(confident = 13, suggested = 13, newSinceSeen = 2))
    assertThat(rig.sure()).contains(id(900))
    assertThat(rig.suggested()).containsEntry(id(901), "s1")
    assertThat(rig.engine.newSinceSeen(rig.id)).isEqualTo(2)
    rig.engine.markSeen(rig.id)
    assertThat(rig.engine.newSinceSeen(rig.id)).isEqualTo(0)
  }

  @Test
  fun senzaUnSegnoDiVistoNonCeNessunaNovita() = runTest {
    val rig = Rig(SmartRules(trip, oslo, null)).also { it.fill() }
    assertThat((rig.engine.evaluate(rig.id, Duration.INFINITE) as SmartOutcome.Done).newSinceSeen).isEqualTo(0)
    assertThat(rig.engine.newSinceSeen(rig.id)).isEqualTo(0)
  }

  // --- Il coordinatore del ricalcolo ------------------------------------------------------------------------------------------------

  @Test
  fun ilCoordinatoreRicalcolaSoloGliSmartCheCoprono_iGiorniToccati() = runTest {
    val rig = Rig(SmartRules(trip, oslo, null)).also { it.fill() }
    val other = albumId(2)
    val summer = rig.rows.create(other, SmartRules(ranges(day(2026, 7, 1) to day(2026, 7, 10)), oslo, null))
    val frozen = albumId(3)
    rig.rows.create(frozen, SmartRules(trip, oslo, null)).frozen = true
    val recompute = SmartRecompute(rig.rows, rig.world, rig.engine, rig.clocks)
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    rig.engine.evaluate(other, Duration.INFINITE)
    val applies = rig.rows.applies
    rig.world.put(cand(950, d2, 16 * 60, gps = inOslo(), placeId = Spots.OSLO_ID))
    val report = recompute.onCatalogChanged(listOf(id(950)), budget = Duration.INFINITE)
    assertThat(report).isEqualTo(SmartRecomputeReport(albums = 3, recomputed = 1, skipped = 0))
    assertThat(rig.sure()).contains(id(950))
    assertThat(rig.rows.applies).isEqualTo(applies + 1)
    assertThat(summer.auto).isEmpty()
    assertThat(rig.rows.albums.getValue(frozen).auto).isEmpty()
    // Un cambio di regole e' la valutazione intera.
    rig.album.rules = SmartRules(trip, around(Spots.OSLO, 100), null)
    assertThat(recompute.onRulesChanged(rig.id, Duration.INFINITE)).isInstanceOf(SmartOutcome.Done::class.java)
  }

  @Test
  fun ilCoordinatoreSenzaGiorniNonFaNiente() = runTest {
    val rig = Rig(SmartRules(trip, oslo, null)).also { it.fill() }
    val recompute = SmartRecompute(rig.rows, rig.world, rig.engine, rig.clocks)
    assertThat(recompute.onCatalogChanged(listOf(id(777_777)), budget = Duration.INFINITE)).isEqualTo(SmartRecomputeReport(1, 0, 0))
    assertThat(recompute.onCatalogChanged(emptyList(), listOf(d2), Duration.INFINITE).recomputed).isEqualTo(1)
  }

  @Test
  fun e_AL16SenzaNessunGpsNienteLuogoMaIlPeriodoFunzionaLoStesso() = runTest {
    // Un telefono senza posizione nelle foto: nessuna foto col GPS, quindi nessun luogo nel dizionario e nessuna regola A con un luogo.
    val rig = Rig(SmartRules(trip, null, null))
    for (n in 0 until 6) rig.world.put(cand(n + 1, d1.plusDays((n % 3).toLong()), minutes = 600 + n * 30))
    rig.world.put(cand(50, d1, source = SmartSource.FAMILY, device = null), cand(51, d1, screen = true))
    assertThat(rig.engine.evaluate(rig.id, Duration.INFINITE)).isEqualTo(SmartOutcome.Done(6, 2, 0))
    assertThat(rig.suggested().values.toSet()).containsExactly("s1", "s2")
  }
}
