package dev.pampa.mycloudgallery.core.index.quality

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.index.contract.QualityLevel
import dev.pampa.mycloudgallery.core.index.sync.Fx
import dev.pampa.mycloudgallery.core.index.sync.Harness
import dev.pampa.mycloudgallery.core.index.testing.InMemoryRemoteIndex
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass.HQ
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass.THUMB_M
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass.THUMB_S
import kotlin.math.abs
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * F12 delle prove sul campo (S25, 22/10): dopo "Alta" la stima di "Risparmio" era a 407 kB e lo spazio occupato a 519 kB (+27,6%, la tolleranza del
 * progetto e' ±15%, `fase4-progetto.md` §2.10). Qui, sul NAS finto: a convergenza finita, a ogni cambio di livello (in su e in giu', piu' volte),
 * lo spazio occupato torna dentro la stima e le anteprime fuori livello sono tutte rilasciate.
 */
open class ReplicaQualityEstimateTest {

  @get:Rule val tmp = TemporaryFolder()

  private val nas = InMemoryRemoteIndex(nasNow = { Fx.NOW })
  private val files = 1..50
  private val sizes = mapOf(THUMB_S to 60, THUMB_M to 200, HQ to 1_000)

  private fun publishAll() {
    val records = files.map { Fx.put("Foto/$it.jpg", it.toLong(), Fx.file(it, prepared = setOf(THUMB_S, THUMB_M, HQ))) }
    nas.publish(
      "alessio",
      "a",
      listOf(Fx.meta(*records.toTypedArray())) + listOf(THUMB_S, THUMB_M, HQ).map { cls ->
        Fx.images(cls, *files.map { Fx.key(it) to Fx.pixels(cls, it, "a", sizes.getValue(cls)) }.toTypedArray())
      },
    )
  }

  private fun deviation(current: Long, estimate: Long): Double = abs(current - estimate).toDouble() / estimate.coerceAtLeast(1)

  @Test
  fun f12_loSpazioOccupatoSegueLaStimaAOgniCambioDiLivello() = runTest {
    publishAll()
    val me = Harness("b", tmp.root, nas)
    me.quality.load()
    me.sync("alessio")

    for (level in listOf(QualityLevel.HIGH, QualityLevel.SAVER, QualityLevel.STANDARD, QualityLevel.SAVER, QualityLevel.HIGH, QualityLevel.STANDARD)) {
      me.quality.set(level)
      repeat(4) { me.sync("alessio") }
      val state = me.quality.state.value
      val estimate = state.estimates.getValue(level).bytes
      assertWithMessage("$level: ancora in convergenza").that(state.converging).isFalse()
      assertWithMessage("$level: occupati ${state.currentBytes} B, stima $estimate B").that(deviation(state.currentBytes, estimate)).isAtMost(0.15)
      // Le anteprime fuori livello sono tutte rilasciate: niente "di troppo" che resta sul telefono.
      assertWithMessage("$level: anteprime fuori livello rimaste").that(me.replica.bytesOutside(level.classes)).isEqualTo(0)
    }
  }

  @Test
  fun f12_ancheConUnaConvergenzaInPiuPassiLoSpazioArrivaAllaStima() = runTest {
    publishAll()
    // Un pacco grande per giro verso il basso (come in produzione, ma con pacchi piccoli): serve piu' di un giro.
    val me = Harness("b", tmp.root, nas, maxReleasePerRound = 20_000)
    me.quality.load()
    me.quality.set(QualityLevel.HIGH)
    repeat(3) { me.sync("alessio") }
    me.quality.set(QualityLevel.SAVER)
    me.sync("alessio")
    assertThat(me.quality.state.value.converging).isTrue()
    repeat(4) { me.sync("alessio") }

    val state = me.quality.state.value
    assertThat(state.converging).isFalse()
    assertWithMessage("occupati ${state.currentBytes} B, stima ${state.estimates.getValue(QualityLevel.SAVER).bytes} B")
      .that(deviation(state.currentBytes, state.estimates.getValue(QualityLevel.SAVER).bytes)).isAtMost(0.15)
  }
}
