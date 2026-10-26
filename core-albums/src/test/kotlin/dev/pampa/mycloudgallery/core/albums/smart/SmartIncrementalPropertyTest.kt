package dev.pampa.mycloudgallery.core.albums.smart

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import java.time.LocalDate
import kotlin.random.Random
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * IL RICALCOLO INCREMENTALE E' UGUALE A QUELLO DA ZERO (D16, riga P6 del §4.4; proprieta'). Mondi a caso (GPS dentro, vicino, lontano e senza;
 * dispositivi, sorgenti, fusi, screenshot, video, nascosti, gruppi), regole a caso (periodo a serie, "ogni anno", giorni, nessuno; luogo attorno a
 * un punto, regione; esclusi e scelte dell'utente); a lotti arrivano, spariscono, si nascondono e cambiano giorno dei media. Dopo ogni lotto, le
 * righe che l'incrementale ha lasciato (solo sui giorni toccati) sono **esattamente** quelle di una valutazione intera dello stesso mondo.
 */
class SmartIncrementalPropertyTest {
  private val base = RandomWorlds.BASE

  private fun randomCandidate(n: Int, rnd: Random, day: LocalDate = base.plusDays(rnd.nextLong(0, 30))): SmartCandidate = RandomWorlds.candidate(n, rnd, day)

  private fun randomRules(rnd: Random): SmartRules = RandomWorlds.rules(rnd)

  private data class Snapshot(val auto: Map<MediaId, Long>, val suggested: Map<MediaId, String>)

  private fun InMemorySmartRows.view(id: AlbumId): Snapshot {
    val (auto, suggested) = snapshot(id)
    return Snapshot(auto.mapValues { it.value.position }, suggested.mapValues { it.value.reason })
  }

  private suspend fun scratch(world: MemoryWorld, rules: SmartRules, exclusions: Set<MediaId>, owned: Set<MediaId>): Snapshot {
    val rows = InMemorySmartRows()
    val id = albumId(1)
    val album = rows.create(id, rules)
    album.exclusions += exclusions
    album.owned += owned
    RulesSmartEngine(rows, world, world, world, NoSmartContentSearch, DeviceNormalizer("Backup/questo-telefono"), Spots.LOOKUP, FakeAlbumClocks()).evaluate(id, Duration.INFINITE)
    return rows.view(id)
  }

  @Test
  fun dopoOgniLottoLIncrementaleEUgualeAlRicalcoloDaZero() = runTest {
    var compared = 0
    var changesSeen = 0
    for (seed in 1..60) {
      val rnd = Random(seed.toLong() * 7919)
      val rules = randomRules(rnd)
      val world = MemoryWorld()
      val pool = (1..1_000).map { randomCandidate(it, rnd) }
      var next = 0
      world.put(*pool.take(450).toTypedArray())
      next = 450
      val rows = InMemorySmartRows()
      val id = albumId(1)
      val album = rows.create(id, rules)
      // Qualche scelta dell'utente: esclusi e elementi suoi.
      val choices = pool.shuffled(rnd).take(40).map { it.media }
      album.exclusions += choices.take(15)
      album.owned += choices.drop(15).take(15)
      val engine = RulesSmartEngine(rows, world, world, world, NoSmartContentSearch, DeviceNormalizer("Backup/questo-telefono"), Spots.LOOKUP, FakeAlbumClocks())
      engine.evaluate(id, Duration.INFINITE)
      assertWithMessage("subito dopo la valutazione intera (seed $seed)").that(rows.view(id)).isEqualTo(scratch(world, rules, album.exclusions, album.owned))
      repeat(8) { step ->
        val touched = LinkedHashSet<LocalDate>()
        // Arrivano media nuovi (da un backup, da una scansione).
        repeat(rnd.nextInt(5, 40)) {
          if (next < pool.size) {
            val c = pool[next++]
            world.put(c)
            touched += c.c.day
          }
        }
        // Spariscono (cancellati) e si nascondono / riappaiono.
        repeat(rnd.nextInt(0, 12)) {
          world.items().randomOrNull(rnd)?.let { c ->
            touched += c.c.day
            world.remove(c.media)
          }
        }
        repeat(rnd.nextInt(0, 10)) {
          world.items().randomOrNull(rnd)?.let { c ->
            touched += c.c.day
            if (c.media in world.hidden) world.unhide(listOf(c.media)) else world.hide(c.media)
          }
        }
        // Un media cambia giorno (data corretta): il catalogo da' il giorno nuovo, chi chiama porta anche quello vecchio.
        repeat(rnd.nextInt(0, 6)) {
          world.items().randomOrNull(rnd)?.let { c ->
            val newDay = base.plusDays(rnd.nextLong(0, 30))
            touched += c.c.day
            touched += newDay
            world.put(cand(idNumber(c.media), newDay, rnd.nextInt(24 * 60), c.c.gps, c.c.placeId, c.c.originDevice, c.c.source, c.c.kind, c.c.screen, owned = c.ownedByMe))
          }
        }
        // Un gruppo con un nascosto: il contagio lo fa VisibleMedia, non le query dei candidati.
        if (step == 3) {
          val members = world.items().take(60).filter { it.media !in world.hidden }.shuffled(rnd).take(3)
          if (members.size == 3) {
            world.groups += members.map { it.media }.toSet()
            world.hide(members.first().media)
            touched += members.map { it.c.day }
          }
        }
        val before = rows.view(id)
        engine.reevaluate(id, touched)
        val incremental = rows.view(id)
        if (incremental != before) changesSeen++
        val expected = scratch(world, rules, album.exclusions, album.owned)
        assertWithMessage("seed $seed, lotto $step (${describe(rules)}): ${diff(incremental, expected, world)}").that(incremental).isEqualTo(expected)
        compared++
      }
      // Un cambio di scelte (un'esclusione, un elemento accettato) e' la valutazione intera: torna uguale al ricalcolo da zero.
      album.exclusions += world.items().take(5).map { it.media }
      engine.evaluate(id, Duration.INFINITE)
      assertWithMessage("seed $seed, dopo un cambio di scelte").that(rows.view(id)).isEqualTo(scratch(world, rules, album.exclusions, album.owned))
    }
    assertWithMessage("confronti fatti").that(compared).isEqualTo(480)
    assertWithMessage("l'incrementale ha davvero cambiato delle righe").that(changesSeen).isGreaterThan(100)
  }

  private fun describe(rules: SmartRules): String =
    "periodo=${rules.period?.javaClass?.simpleName} luogo=${rules.place?.scope} sorgenti=${rules.sources.size} tipi=${rules.types.size} periodoDati=${rules.period}"

  /** Le differenze fra due viste, con i fatti dei media che differiscono (solo per capire un fallimento). */
  private fun diff(actual: Snapshot, expected: Snapshot, world: MemoryWorld): String {
    val byId = world.items().associateBy { it.media }
    fun fact(m: MediaId): String = byId[m]?.let { c ->
      "${idNumber(m)} giorno=${c.c.day} h=${c.c.localTime} gps=${c.c.gps != null} src=${c.c.source.code} dev=${c.c.originDevice} screen=${c.c.screen} nascosto=${m in world.hidden}"
    } ?: "${idNumber(m)} (assente)"
    val lines = ArrayList<String>()
    for (m in actual.auto.keys - expected.auto.keys) lines += "solo-incr sicuro: ${fact(m)}"
    for (m in expected.auto.keys - actual.auto.keys) lines += "solo-zero sicuro: ${fact(m)}"
    for (m in actual.suggested.keys - expected.suggested.keys) lines += "solo-incr suggerito ${actual.suggested[m]}: ${fact(m)}"
    for (m in expected.suggested.keys - actual.suggested.keys) lines += "solo-zero suggerito ${expected.suggested[m]}: ${fact(m)}"
    for (m in actual.suggested.keys.intersect(expected.suggested.keys)) if (actual.suggested[m] != expected.suggested[m]) lines += "motivo diverso ${actual.suggested[m]} / ${expected.suggested[m]}: ${fact(m)}"
    return lines.take(10).joinToString(" || ")
  }

  /** Il numero del media (gli id di prova sono `FakeAlbumLibrary.id(n)`: il numero sta negli ultimi 12 cifre). */
  private fun idNumber(media: MediaId): Int = media.value.substringAfterLast('-').toLong(16).toInt()

  @Test
  fun unGiornoNonToccatoNonSiRiscrive() = runTest {
    val rnd = Random(5)
    val world = MemoryWorld()
    world.put(*(1..300).map { randomCandidate(it, rnd) }.toTypedArray())
    val rows = InMemorySmartRows()
    val id = albumId(1)
    rows.create(id, SmartRules(SmartPeriod.Ranges(listOf(LocalDayRange(base, base.plusDays(29)))), null, null))
    val engine = RulesSmartEngine(rows, world, world, world, NoSmartContentSearch, DeviceNormalizer(null), Spots.LOOKUP, FakeAlbumClocks())
    engine.evaluate(id, Duration.INFINITE)
    val applies = rows.applies
    // Nessun giorno toccato: niente da fare.
    engine.reevaluate(id, emptyList())
    assertWithMessage("scritture").that(rows.applies).isEqualTo(applies)
    // Un giorno toccato senza cambiamenti veri: stesso risultato, nessuna riga riscritta.
    engine.reevaluate(id, listOf(base.plusDays(4)))
    assertWithMessage("scritture").that(rows.applies).isEqualTo(applies)
  }
}
