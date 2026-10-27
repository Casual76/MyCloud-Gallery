package dev.pampa.mycloudgallery.core.albums.smart

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.HomeClearance
import dev.pampa.mycloudgallery.core.albums.contract.NoHomeClearance
import dev.pampa.mycloudgallery.core.albums.contract.SmartOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.personal.SmartRulesText
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.database.OriginDevice
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.search.parse.ItalianQueryParser
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.round
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Le correzioni degli smart dopo la revisione di fine Fase 7: R16 (il centro di un luogo scritto nei dati personali non e' la casa: senza le foto di casa,
 * su una griglia di 0,1 gradi), AS-9 (la cartella del proprio backup si legge a ogni uso) e AS-2 (aprire uno smart prima del suo primo calcolo non
 * rende «nuove» tutte le sue foto).
 */
class SmartReviewTest {
  private val today = LocalDate.of(2026, 12, 20)

  /** La casa a [home]: troppo vicino e' tutto cio' che sta a meno di [km] da lei. */
  private class NearHome(private val home: GeoPoint, private val km: Double = 5.0) : HomeClearance {
    override suspend fun closeToHome(points: List<GeoPoint>): List<Boolean> = points.map { SmartGeo.distanceKm(it, home) <= km }
  }

  private fun onGrid(value: Double): Boolean = abs(value * 10 - round(value * 10)) < 1e-6

  private fun interpreter(world: MemoryWorld, home: HomeClearance) =
    RuleSmartNameInterpreter(ItalianQueryParser(), world, world, world.candidates(), world.placeLookup, NoSmartContentSearch, null, home)

  /** Una citta' (Oslo) dove si abita: 30 foto a casa (meno di 1 km) e 10 in un altro quartiere, 8 km piu' a nord. */
  private fun cityWithHome(allAtHome: Boolean = false): MemoryWorld {
    val world = MemoryWorld(emptyList(), Spots.PLACES)
    var n = 1
    repeat(30) { k -> world.put(cand(n++, day(2026, 3, 10 + k % 5), 9 * 60 + k, gps = Spots.north(Spots.OSLO, 0.2 + (k % 5) * 0.15), placeId = Spots.OSLO_ID)) }
    repeat(if (allAtHome) 0 else 10) { k -> world.put(cand(n++, day(2026, 3, 10 + k % 5), 12 * 60 + k, gps = Spots.north(Spots.OSLO, 8.0 + k * 0.1), placeId = Spots.OSLO_ID)) }
    world.publish()
    return world
  }

  // --- R16 ----------------------------------------------------------------------------------------------------------------------

  @Test
  fun ilCentroDiUnLuogoNonEIlCentroDiCasa() = runTest {
    val world = cityWithHome()

    // Senza sapere dov'e' casa il centro e' la mediana delle foto: in pratica la casa (30 foto su 40), a precisione piena nei dati personali.
    val naive = interpreter(world, NoHomeClearance).propose("Oslo 2026", today).rules.place!!
    assertThat(SmartGeo.distanceKm(naive.center, Spots.OSLO)).isLessThan(3.5)

    val place = interpreter(world, NearHome(Spots.OSLO)).propose("Oslo 2026", today).rules.place!!
    // Su una griglia di 0,1 gradi (~11 km): dice la citta', non la casa.
    assertThat(onGrid(place.center.latitude)).isTrue()
    assertThat(onGrid(place.center.longitude)).isTrue()
    // Calcolato senza le foto di casa: il quartiere dove si e' stati, e a piu' di 5 km da casa.
    assertThat(SmartGeo.distanceKm(place.center, Spots.OSLO)).isGreaterThan(5.0)
    assertThat(SmartGeo.distanceKm(place.center, Spots.north(Spots.OSLO, 8.5))).isLessThan(8.0)
    // Quello che si scrive nei dati personali non ha piu' di un decimale.
    val text = SmartRulesText.encodePlace(place).split(':')
    assertThat(text[2].substringAfter('.', "").length).isAtMost(1)
    assertThat(text[3].substringAfter('.', "").length).isAtMost(1)
  }

  @Test
  fun seTutteLeFotoDelLuogoSonoACasaIlCentroSiSpostaSuUnPuntoDellaGrigliaLontanoDaCasa() = runTest {
    val world = cityWithHome(allAtHome = true)
    val place = interpreter(world, NearHome(Spots.OSLO)).propose("Oslo 2026", today).rules.place!!
    assertThat(onGrid(place.center.latitude)).isTrue()
    assertThat(onGrid(place.center.longitude)).isTrue()
    assertThat(SmartGeo.distanceKm(place.center, Spots.OSLO)).isGreaterThan(5.0)
    // Il raggio non cambia: la citta' resta la citta'.
    assertThat(place.radiusKm).isEqualTo(dev.pampa.mycloudgallery.core.albums.contract.SmartLimits.CITY_RADIUS_KM)
  }

  @Test
  fun ilPuntoInMezzoATrePostiIntornoACasaNonEllaStessa() = runTest {
    // Roma e Firenze, con casa a meta' strada: il punto «in mezzo» (centro del cerchio che le contiene) sarebbe la casa.
    val world = TitleLibrary.world()
    val home = SmartGeo.middle(listOf(Spots.ROMA, Spots.FIRENZE))
    val place = interpreter(world, NearHome(home)).propose("Roma Firenze 2025", today).rules.place!!
    assertThat(place.label).isEqualTo("Roma e Firenze")
    assertThat(onGrid(place.center.latitude)).isTrue()
    assertThat(onGrid(place.center.longitude)).isTrue()
    assertThat(SmartGeo.distanceKm(place.center, home)).isGreaterThan(5.0)
    // Il cerchio copre ancora le due citta'.
    assertThat(SmartGeo.distanceKm(place.center, Spots.ROMA)).isAtMost(place.radiusKm.toDouble())
    assertThat(SmartGeo.distanceKm(place.center, Spots.FIRENZE)).isAtMost(place.radiusKm.toDouble())
  }

  // --- AS-9 ---------------------------------------------------------------------------------------------------------------------

  @Test
  fun laCartellaDelBackupSiLeggeAOgniUsoNonUnaVoltaAlMontaggio() {
    var folder: String? = null
    val devices = DeviceNormalizer { folder }
    val backup = OriginDevice.backup("questo-telefono")

    // Le impostazioni del backup non sono ancora caricate: la copia nella cartella del backup sembra di un altro dispositivo.
    assertThat(devices.of(backup)).isEqualTo(backup)
    folder = "Backup/Questo-Telefono"
    // Appena la cartella e' nota la stessa copia vale «questo telefono» (prima restava un altro dispositivo per tutto il processo).
    assertThat(devices.of(backup)).isEqualTo(OriginDevice.PHONE)
    assertThat(devices.of(OriginDevice.PHONE)).isEqualTo(OriginDevice.PHONE)
    assertThat(devices.of(OriginDevice.backup("altro"))).isEqualTo(OriginDevice.backup("altro"))
    assertThat(devices.of(null)).isNull()

    folder = null
    assertThat(devices.of(backup)).isEqualTo(backup)
  }

  // --- AS-2 ---------------------------------------------------------------------------------------------------------------------

  @Test
  fun aprireLoSmartPrimaDelPrimoCalcoloNonRendeNuoveTutteLeFoto() = runTest {
    val world = MemoryWorld()
    val rows = InMemorySmartRows()
    val clocks = FakeAlbumClocks()
    val engine = RulesSmartEngine(rows, world, world, world, NoSmartContentSearch, DeviceNormalizer(null), Spots.LOOKUP, clocks, InMemorySeenMarks())
    val id = albumId(1)
    rows.create(id, SmartRules(ranges(day(2026, 3, 10) to day(2026, 3, 20)), null, null))
    for (n in 0 until 12) world.put(cand(n + 1, day(2026, 3, 10).plusDays((n % 5).toLong()), 600 + n))

    // L'utente apre la pagina appena creata: niente da aver visto.
    engine.markSeen(id)
    clocks.advance(5_000)
    val outcome = engine.evaluate(id, Duration.INFINITE) as SmartOutcome.Done
    assertThat(outcome.confident).isEqualTo(12)
    // Prima il segno era stato messo sull'album vuoto e le 12 foto del primo calcolo risultavano tutte «Nuove».
    assertThat(engine.newSinceSeen(id)).isEqualTo(0)

    // La prima apertura con le righe mette il segno; le foto che arrivano dopo sono davvero nuove.
    engine.markSeen(id)
    clocks.advance(5_000)
    world.put(cand(500, day(2026, 3, 11), 700))
    engine.reevaluate(id, listOf(day(2026, 3, 11)))
    assertThat(engine.newSinceSeen(id)).isEqualTo(1)
  }
}
