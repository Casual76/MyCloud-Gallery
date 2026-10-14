package dev.pampa.mycloudgallery.core.index.sync

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.index.contract.IndexDiag
import dev.pampa.mycloudgallery.core.index.testing.InMemoryRemoteIndex
import dev.pampa.mycloudgallery.core.trust.RejectReason
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** La Diagnostica della replica: solo codici e numeri, chiavi d'esportazione valide, mai dati remoti. */
class IndexDiagnosticsTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun iConteggiDiventanoVociDellaSezioneNasLibrary() = runTest {
    val nas = InMemoryRemoteIndex(nasNow = { Fx.NOW })
    nas.publish("alessio", "a", listOf(Fx.meta(Fx.put("A/1.jpg", 1, Fx.file(1)))))
    nas.rejectHead("alessio", "c", RejectReason.BAD_SIGNATURE)
    nas.publish("alessio", "c", listOf(Fx.meta(Fx.put("A/2.jpg", 1, Fx.file(2)))))
    val me = Harness("b", tmp.root, nas)
    me.quality.load()
    me.sync("alessio")

    val items = IndexDiagnostics(me.engine.counters, me.quality) { 42 }.collect()
    assertThat(items.map { it.code }).containsExactly(IndexDiag.INDEX_REJECTED, IndexDiag.INDEX_REPLICA_STATE)
    assertThat(items.map { it.section }.toSet()).containsExactly(DiagSection.NAS_LIBRARY)
    val rejected = items.first { it.code == IndexDiag.INDEX_REJECTED }
    assertThat(rejected.export["count"]).isEqualTo(ExportValue.Num(1))
    assertThat(rejected.export["reason.BAD_SIGNATURE"]).isEqualTo(ExportValue.Num(1))
    items.flatMap { it.export.keys }.forEach { assertThat(DiagItem.EXPORT_KEY.matches(it)).isTrue() }
    val state = items.first { it.code == IndexDiag.INDEX_REPLICA_STATE }
    assertThat(state.export["complete"]).isEqualTo(ExportValue.Flag(true))
  }
}
