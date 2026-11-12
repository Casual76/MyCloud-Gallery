package dev.pampa.mycloudgallery.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.CellContent
import dev.pampa.mycloudgallery.core.library.testing.InMemoryTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.VersionId
import java.io.File
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pacchetto X: il registro delle urgenze (sopravvive al processo, ha un tetto, l'azzeramento lo toglie) e il **cancello** del nastro
 * della pagina "Analisi delle foto": un media che la Libreria non mostra adesso non diventa mai un'anteprima. Mutazioni: togliere il
 * controllo dell'istantanea fa cadere `unMediaNascostoNonSiMostra`; togliere quello sulle chiavi della cella fa cadere
 * `unaChiaveDiUnAltraCellaNonSiMostra`.
 */
class AnalysisGateAndUrgentTest {

  @get:Rule
  val temp = TemporaryFolder()

  private fun key(n: Int) = ContentKey(n.toString(16).padStart(64, '0'))

  private fun id(n: Long) = MediaId(UUID(0L, n).toString())

  private fun snapshotOf(ids: List<MediaId>): InMemoryTimelineSnapshot {
    val n = ids.size
    val uuids = ids.map { UUID.fromString(it.value) }
    return InMemoryTimelineSnapshot(
      seq = 1,
      idMsb = LongArray(n) { uuids[it].mostSignificantBits },
      idLsb = LongArray(n) { uuids[it].leastSignificantBits },
      sortTime = LongArray(n),
      localDay = IntArray(n) { 20240301 },
      kinds = ByteArray(n) { MediaKind.PHOTO.ordinal.toByte() },
      sources = ByteArray(n) { MediaSource.CAMERA.ordinal.toByte() },
      flags = IntArray(n),
      aspectMilli = IntArray(n) { 1000 },
      durationMs = LongArray(n),
      place = IntArray(n),
      groupSize = IntArray(n) { 1 },
    )
  }

  private fun fact(id: MediaId) = ContentFact(id, VersionId(UUID(0L, 99L).toString()), size = 10, source = MediaSource.CAMERA)

  @Test
  fun unaCellaVisibilePassaIlCancello() {
    val shown = id(1)
    val k = key(7)
    val snap = snapshotOf(listOf(id(9), shown))
    val at = AnalysisGate.positionOf(k, fact(shown), snap) { CellContent(1, shown, listOf(k), emptyList()) }
    assertThat(at).isEqualTo(1)
  }

  @Test
  fun unMediaNascostoNonSiMostra() {
    val hidden = id(2)
    val k = key(8)
    // La Libreria di adesso non lo contiene piu' (nascosto mentre la pagina era aperta): il cancello resta chiuso.
    val snap = snapshotOf(listOf(id(1)))
    val at = AnalysisGate.positionOf(k, fact(hidden), snap) { CellContent(1, hidden, listOf(k), emptyList()) }
    assertThat(at).isNull()
  }

  @Test
  fun unaChiaveDiUnAltraCellaNonSiMostra() {
    val shown = id(3)
    val snap = snapshotOf(listOf(shown))
    val at = AnalysisGate.positionOf(key(99), fact(shown), snap) { CellContent(1, shown, listOf(key(3)), emptyList()) }
    assertThat(at).isNull()
  }

  @Test
  fun nelDubbioNonSiMostra() {
    val shown = id(4)
    val snap = snapshotOf(listOf(shown))
    val cell: (MediaId) -> CellContent? = { CellContent(1, shown, listOf(key(1)), emptyList()) }
    assertThat(AnalysisGate.positionOf(key(1), null, snap, cell)).isNull()
    assertThat(AnalysisGate.positionOf(key(1), fact(shown), null, cell)).isNull()
    assertThat(AnalysisGate.positionOf(key(1), fact(shown), snap) { null }).isNull()
  }

  @Test
  fun leUrgenzeSopravvivonoAlProcessoESiTolgono() {
    val file = File(temp.root, AiUrgentFile.FILE)
    val first = AiUrgentFile(file)
    first.add(key(1))
    first.add(key(2))

    val again = AiUrgentFile(file)
    assertThat(again.urgent()).containsExactly(key(1), key(2)).inOrder()

    again.done(key(1))
    assertThat(AiUrgentFile(file).urgent()).containsExactly(key(2))

    again.remove(listOf(key(2)))
    assertThat(AiUrgentFile(file).urgent()).isEmpty()
  }

  @Test
  fun leUrgenzeHannoUnTettoEIlPiuVecchioEsce() {
    val file = File(temp.root, AiUrgentFile.FILE)
    val urgent = AiUrgentFile(file)
    for (i in 1..(AiUrgentFile.MAX_KEYS + 5)) urgent.add(key(i))
    assertThat(urgent.urgent()).hasSize(AiUrgentFile.MAX_KEYS)
    assertThat(urgent.urgent()).doesNotContain(key(1))
    assertThat(urgent.urgent()).contains(key(AiUrgentFile.MAX_KEYS + 5))
  }

  @Test
  fun laPuliziaToglieIlFile() {
    val file = File(temp.root, AiUrgentFile.FILE)
    val urgent = AiUrgentFile(file)
    urgent.add(key(3))
    assertThat(file.isFile).isTrue()
    urgent.clear()
    assertThat(file.exists()).isFalse()
    assertThat(urgent.urgent()).isEmpty()
  }
}
