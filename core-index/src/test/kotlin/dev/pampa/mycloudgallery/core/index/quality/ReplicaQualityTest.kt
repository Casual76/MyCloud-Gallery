package dev.pampa.mycloudgallery.core.index.quality

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.contract.BlobPut
import dev.pampa.mycloudgallery.core.index.contract.QualityLevel
import dev.pampa.mycloudgallery.core.index.replica.store.SegmentState
import dev.pampa.mycloudgallery.core.index.sync.Fx
import dev.pampa.mycloudgallery.core.index.sync.Harness
import dev.pampa.mycloudgallery.core.index.testing.InMemoryRemoteIndex
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass.HQ
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass.THUMB_M
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass.THUMB_S
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** D16, E-L6: stima prima di scaricare, convergenza piano verso l'alto e verso il basso. */
open class ReplicaQualityTest {

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

  @Test
  fun eL6_stimaDalleTesteEConvergenzaVersoLAltoEVersoIlBasso() = runTest {
    publishAll()
    // Al massimo un pacco grande per giro verso il basso: si vede la convergenza "piano".
    val me = Harness("b", tmp.root, nas, maxReleasePerRound = 20_000)
    me.quality.load()
    me.sync("alessio")
    var state = me.quality.state.value
    assertThat(state.level).isEqualTo(QualityLevel.STANDARD)
    assertThat(state.converging).isFalse()
    assertThat(me.blob(Fx.key(1), HQ)).isNull()
    assertThat(me.store.segmentsWhere(setOf(SegmentState.SKIPPED), setOf(HQ))).hasSize(1)

    // Le stime vengono dai byte elencati nelle teste: l'HQ si conosce prima di scaricarla.
    val saver = state.estimates.getValue(QualityLevel.SAVER)
    val standard = state.estimates.getValue(QualityLevel.STANDARD)
    val high = state.estimates.getValue(QualityLevel.HIGH)
    assertThat(high.bytes - standard.bytes).isEqualTo(50L * 1_000)
    assertThat(standard.bytes - saver.bytes).isEqualTo(50L * 200)
    assertThat(listOf(saver.items, standard.items, high.items)).containsExactly(50, 50, 50)

    // Su: la scelta mostra subito la stima e "in convergenza", senza scaricare niente da se'.
    val reads = nas.segmentReads.get()
    val before = state.currentBytes
    me.quality.set(QualityLevel.HIGH)
    state = me.quality.state.value
    assertThat(state.converging).isTrue()
    assertThat(state.currentBytes).isEqualTo(before)
    assertThat(nas.segmentReads.get()).isEqualTo(reads)
    me.sync("alessio")
    state = me.quality.state.value
    assertThat(state.converging).isFalse()
    assertThat(me.blob(Fx.key(1), HQ)).isEqualTo(Fx.pixels(HQ, 1, "a", 1_000))
    assertThat(state.currentBytes).isEqualTo(before + 50L * 1_000)

    // Giu': al massimo un pacco grande per giro (200 MB in produzione), i piu' grandi prima.
    me.quality.set(QualityLevel.SAVER)
    assertThat(me.quality.state.value.converging).isTrue()
    me.sync("alessio")
    assertThat(me.blob(Fx.key(1), HQ)).isNull()
    assertThat(me.blob(Fx.key(1), THUMB_M)).isNotNull()
    assertThat(me.quality.state.value.converging).isTrue()
    me.sync("alessio")
    assertThat(me.blob(Fx.key(1), THUMB_M)).isNull()
    assertThat(me.blob(Fx.key(1), THUMB_S)).isNotNull()
    state = me.quality.state.value
    assertThat(state.converging).isFalse()
    assertThat(state.currentBytes).isEqualTo(before - 50L * 200)
    assertThat(me.store.segmentsWhere(setOf(SegmentState.SKIPPED), setOf(THUMB_M, HQ))).hasSize(2)
    // Il livello sopravvive al riavvio.
    assertThat(me.store.quality()).isEqualTo(QualityLevel.SAVER)

    // Le proprie immagini fuori livello non entrano nella replica.
    me.replica.applyOwn("alessio", emptyList(), listOf(BlobPut.Image(HQ, Fx.key(99), Fx.pixels(HQ, 99)), BlobPut.Image(THUMB_S, Fx.key(99), Fx.pixels(THUMB_S, 99))))
    assertThat(me.replica.locate(Fx.key(99).short, HQ)).isNull()
    assertThat(me.replica.locate(Fx.key(99).short, THUMB_S)).isNotNull()

    // E di nuovo su: i segmenti "saltati" tornano a scaricarsi, senza testa nuova.
    me.quality.set(QualityLevel.STANDARD)
    me.sync("alessio")
    assertThat(me.blob(Fx.key(1), THUMB_M)).isNotNull()
    assertThat(me.quality.state.value.converging).isFalse()
  }
}
