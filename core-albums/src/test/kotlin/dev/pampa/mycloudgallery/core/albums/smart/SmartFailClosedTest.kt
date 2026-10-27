package dev.pampa.mycloudgallery.core.albums.smart

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.SmartOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * R17 della revisione di fine Fase 7: "fallire chiusi" non deve diventare "vuoto". `CatalogVisibleMedia.visible` e `CatalogHiddenMask.hiddenAmong`
 * rispondono "niente e' visibile" / "tutto e' nascosto" se il database non risponde (giusto per chi mostra); il motore degli smart che confronta
 * con uno stato salvato lo prendeva per una Libreria vuota e **toglieva** le righe automatiche e i suggeriti. Ora usa le versioni che lanciano
 * (`visibleOrThrow`, `hiddenAmongOrThrow`): l'eccezione risale e non si scrive niente.
 */
class SmartFailClosedTest {

  /** L'eccezione che [block] lancia, o null. */
  private suspend fun thrown(block: suspend () -> Unit): Throwable? = try {
    block()
    null
  } catch (e: kotlinx.coroutines.CancellationException) {
    throw e
  } catch (e: Exception) {
    e
  }

  private val d1 = day(2026, 3, 10)
  private val rules = SmartRules(ranges(d1 to d1.plusDays(2)), around(Spots.OSLO, 25), null)

  private class Rig(rules: SmartRules) {
    val world = MemoryWorld()
    val flaky = FlakyWorld(world)
    val rows = InMemorySmartRows()
    val id = albumId(1)
    val album = rows.create(id, rules)
    val engine = RulesSmartEngine(rows, world, flaky, flaky, NoSmartContentSearch, DeviceNormalizer(null), Spots.LOOKUP, FakeAlbumClocks(), InMemorySeenMarks())
  }

  private fun Rig.fill() {
    for (n in 0 until 6) world.put(cand(100 + n, d1.plusDays(n % 3L), 9 * 60 + n, gps = Spots.north(Spots.OSLO, 2.0), placeId = Spots.OSLO_ID))
    world.put(cand(200, d1, 10 * 60, source = dev.pampa.mycloudgallery.core.albums.contract.SmartSource.FAMILY, device = null))
  }

  @Test
  fun unGuastoDiLetturaNonSvuotaLeRigheDelloSmart() = runTest {
    val rig = Rig(rules).also { it.fill() }
    val first = rig.engine.evaluate(rig.id, Duration.INFINITE)
    assertThat(first).isInstanceOf(SmartOutcome.Done::class.java)
    val before = rig.rows.snapshot(rig.id)
    assertThat(before.first).hasSize(6)
    assertThat(before.second).hasSize(1)
    val applies = rig.rows.applies

    rig.flaky.failing = true
    assertThat(thrown { rig.engine.evaluate(rig.id, Duration.INFINITE) }).isInstanceOf(IllegalStateException::class.java)
    assertThat(thrown { rig.engine.reevaluate(rig.id, listOf(d1)) }).isInstanceOf(IllegalStateException::class.java)

    assertThat(rig.rows.snapshot(rig.id)).isEqualTo(before)
    assertThat(rig.rows.applies).isEqualTo(applies)

    // Il guasto passa: il giro dopo e' uguale a prima.
    rig.flaky.failing = false
    assertThat(rig.engine.evaluate(rig.id, Duration.INFINITE)).isEqualTo(first)
    assertThat(rig.rows.snapshot(rig.id)).isEqualTo(before)
  }

  @Test
  fun ilRicalcoloDelCoordinatoreNonSvuotaNeppureLui() = runTest {
    val rig = Rig(rules).also { it.fill() }
    rig.engine.evaluate(rig.id, Duration.INFINITE)
    val before = rig.rows.snapshot(rig.id)
    val recompute = SmartRecompute(rig.rows, rig.world, rig.engine, FakeAlbumClocks())

    rig.flaky.failing = true
    assertThat(thrown { recompute.onCatalogChanged(listOf(id(100)), emptyList(), Duration.INFINITE) }).isInstanceOf(IllegalStateException::class.java)
    assertThat(rig.rows.snapshot(rig.id)).isEqualTo(before)
  }
}
