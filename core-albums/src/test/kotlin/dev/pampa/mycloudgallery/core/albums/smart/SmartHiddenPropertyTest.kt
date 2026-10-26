package dev.pampa.mycloudgallery.core.albums.smart

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.parse.ItalianQueryParser
import java.time.LocalDate
import kotlin.random.Random
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * NESSUN NASCOSTO (E-AL8, E-H1, D22, riga P6 del §4.4; proprieta'). Mondi a caso con nascosti e con gruppi in cui uno e' nascosto (le query dei
 * candidati non lo sanno: lo fa `VisibleMedia`): in nessuna riga del motore (sicuri, suggeriti) ne' nell'anteprima compare un nascosto o un membro
 * di un gruppo con un nascosto. E, di piu', **un nascosto non cambia il risultato**: l'album e' lo stesso di un mondo in cui quei media non ci sono
 * (non regge una regola B, non fa da ancora, non da' una serie: nessuna traccia).
 */
class SmartHiddenPropertyTest {

  private suspend fun rowsOf(world: MemoryWorld, rules: SmartRules, id: AlbumId): Pair<Map<MediaId, Long>, Map<MediaId, String>> {
    val rows = InMemorySmartRows()
    rows.create(id, rules)
    RulesSmartEngine(rows, world, world, world, NoSmartContentSearch, DeviceNormalizer("Backup/questo-telefono"), Spots.LOOKUP, FakeAlbumClocks()).evaluate(id, Duration.INFINITE)
    val (auto, suggested) = rows.snapshot(id)
    return auto.mapValues { it.value.position } to suggested.mapValues { it.value.reason }
  }

  @Test
  fun unNascostoNonComparePerNienteENonCambiaIlRisultato() = runTest {
    var hiddenTotal = 0
    for (seed in 1..80) {
      val rnd = Random(seed.toLong() * 104_729)
      val rules = RandomWorlds.rules(rnd)
      val world = MemoryWorld()
      val all = (1..500).map { RandomWorlds.candidate(it, rnd) }
      world.put(*all.toTypedArray())
      val hidden = all.shuffled(rnd).take(rnd.nextInt(5, 60)).map { it.media }.toSet()
      world.hide(hidden.toList())
      // Gruppi: uno dei membri e' nascosto, gli altri no (le query dei candidati li restituiscono).
      val groups = all.filter { it.media !in hidden }.shuffled(rnd).chunked(3).take(8).map { g -> g.map { it.media }.toSet() + hidden.first() }
      world.groups += groups
      val contaminated = hidden + groups.flatten()
      val id = albumId(1)
      val real = rowsOf(world, rules, id)
      hiddenTotal += hidden.size
      for (media in real.first.keys) assertWithMessage("sicuro nascosto (seed $seed)").that(media in contaminated).isFalse()
      for (media in real.second.keys) assertWithMessage("suggerito nascosto (seed $seed)").that(media in contaminated).isFalse()
      // Lo stesso mondo senza quei media: stessa cosa.
      val clean = MemoryWorld()
      clean.put(*all.filter { it.media !in contaminated }.toTypedArray())
      assertWithMessage("risultato con e senza i nascosti (seed $seed)").that(real).isEqualTo(rowsOf(clean, rules, id))
      // L'anteprima.
      val engine = RulesSmartEngine(InMemorySmartRows(), world, world, world, NoSmartContentSearch, DeviceNormalizer(null), Spots.LOOKUP, FakeAlbumClocks())
      assertThat(engine.preview(rules).sample.filter { it in contaminated }).isEmpty()
    }
    assertThat(hiddenTotal).isGreaterThan(1_000)
  }

  @Test
  fun leSerieELaPropostaDiUnTitoloNonSiAccorgonoDeiNascosti() = runTest {
    val world = TitleLibrary.world()
    val visibleOnly = TitleLibrary.world()
    // Si nascondono tutte le foto di Lisbona: per chi guarda, a Lisbona non c'e' mai stato nessuno (nemmeno nel dizionario dei luoghi).
    val lisbona = world.items().filter { it.c.placeId == 2267057 }.map { it.media }
    world.hide(lisbona)
    world.publish()
    visibleOnly.remove(lisbona)
    visibleOnly.publish()
    val interpreter = { w: MemoryWorld -> RuleSmartNameInterpreter(ItalianQueryParser(), w, w, w, TitleLibrary.lookup) }
    val a = interpreter(world).propose("Lisbona 2025", LocalDate.of(2026, 12, 20))
    val b = interpreter(visibleOnly).propose("Lisbona 2025", LocalDate.of(2026, 12, 20))
    assertThat(a.rules.place).isNull()
    assertThat(TitleRenderer.columns(a)).isEqualTo(TitleRenderer.columns(b))
  }

  @Test
  fun unGruppoNascostoANessunaTraccia_ancheNelleSerieDiUnViaggio() = runTest {
    // Il giorno 14 marzo di Oslo ha due foto in un gruppo, una nascosta: per la serie e' come se quel giorno non ci fosse stato nessuno.
    val world = TitleLibrary.world()
    val march14 = world.items().filter { it.c.day == LocalDate.of(2026, 3, 14) && it.c.placeId == 3143244 }
    world.groups += march14.map { it.media }.toSet()
    world.hide(march14.first().media)
    world.publish()
    val proposal = RuleSmartNameInterpreter(ItalianQueryParser(), world, world, world, TitleLibrary.lookup).propose("Oslo marzo 2026", LocalDate.of(2026, 12, 20))
    val series = (proposal.rules.period as dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod.Ranges).ranges
    // Restano i giorni 15-18: la serie parte dal 14 (il giorno prima del 15), non dal 13.
    assertThat(series.single().from).isEqualTo(LocalDate.of(2026, 3, 14))
  }
}
