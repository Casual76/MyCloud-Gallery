package dev.pampa.mycloudgallery.core.index.sync

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.replica.store.IdentityOutcome
import dev.pampa.mycloudgallery.core.index.testing.InMemoryRemoteIndex
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Fatto quando di P4: 20 mila record applicati in meno di 10 s su JVM (testa, segmenti `meta`, transazione, fusione D6,
 * adozione D7 nel catalogo in memoria). Il tempo misurato va nel report.
 */
class SyncPerformanceTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun ventimilaRecordApplicatiInMenoDiDieciSecondi() = runTest {
    val nas = InMemoryRemoteIndex(nasNow = { Fx.NOW })
    val perSegment = TrustLimits.MAX_SEGMENT_ENTRIES
    val segments = 5
    val total = perSegment * segments
    val prepared = setOf(SegmentClass.THUMB_S, SegmentClass.THUMB_M)
    val contents = (0 until segments).map { s ->
      Fx.meta(*(0 until perSegment).map { i ->
        val n = s * perSegment + i
        Fx.put("Archivio/${2000 + n % 25}/${n % 12 + 1}/IMG_$n.jpg", n + 1L, Fx.file(n, prepared = prepared))
      }.toTypedArray())
    }
    nas.publish("alessio", "a", contents)
    val me = Harness("b", tmp.root, nas)
    me.replica.open()

    val start = System.nanoTime()
    val report = me.sync("alessio", classes = emptySet())
    val elapsedMs = (System.nanoTime() - start) / 1_000_000
    println("P4: $total record applicati in $elapsedMs ms (${total * 1000L / elapsedMs.coerceAtLeast(1)} record/s)")

    assertThat(report.recordsApplied).isEqualTo(total)
    assertThat(me.store.recordCount()).isEqualTo(total)
    assertThat(me.catalog.outcomes[IdentityOutcome.ADOPTED]).isEqualTo(total)
    assertThat(elapsedMs).isLessThan(10_000)

    // Una testa nuova che aggiorna un segmento intero: solo i percorsi toccati si rifondono.
    nas.publish("alessio", "a", listOf(Fx.meta(*(0 until perSegment).map { i ->
      Fx.put("Archivio/${2000 + i % 25}/${i % 12 + 1}/IMG_$i.jpg", total + i + 1L, Fx.file(i, prepared = prepared + SegmentClass.HQ))
    }.toTypedArray())))
    val again = System.nanoTime()
    val update = me.sync("alessio", classes = emptySet())
    val updateMs = (System.nanoTime() - again) / 1_000_000
    println("P4: aggiornamento di $perSegment record in $updateMs ms")
    assertThat(update.recordsApplied).isEqualTo(perSegment)
    assertThat(updateMs).isLessThan(10_000)
  }
}
