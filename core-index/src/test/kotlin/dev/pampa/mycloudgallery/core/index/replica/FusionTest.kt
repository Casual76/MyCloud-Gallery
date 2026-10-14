package dev.pampa.mycloudgallery.core.index.replica

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.replica.store.RecordRow
import dev.pampa.mycloudgallery.core.index.sync.Fx
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.trust.data.MetaRecord
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass.HQ
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass.THUMB_M
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass.THUMB_S
import org.junit.Test

/** D5 e D6 su scenari scritti a mano: stesso ingresso → stessa vista, qualunque sia l'ordine. */
class FusionTest {

  private val path = "Foto/2024/IMG_0001.jpg"
  private val key = PathKeys.of(Fx.path(path))

  private fun row(device: String, segment: String, record: MetaRecord) = RecordRow(device, segment, key, record)

  private fun listingOf(n: Int, mtime: Long = Fx.NOW): NasStamp = NasStamp(Fx.content(n).size.toLong(), mtime)

  @Test
  fun d5_perDispositivoValeIlSeqPiuAltoFraISuoiSegmentiELaLapideRecenteToglieIlFile() {
    val rows = listOf(
      row("a", "s1", Fx.put(path, 3, Fx.file(1, prepared = setOf(THUMB_S)))),
      row("a", "s2", Fx.put(path, 9, Fx.file(1, prepared = setOf(THUMB_S, THUMB_M)))),
      row("b", "s3", Fx.put(path, 4, Fx.file(1))),
      row("b", "s4", Fx.gone(path, 5)),
    )
    val current = Fusion.latestByDevice(rows.shuffled())
    assertThat(current.map { it.deviceId to it.record.seq }).containsExactly("a" to 9L, "b" to 5L).inOrder()
    val merged = Fusion.merge(key, rows, listingOf(1))
    assertThat(merged.winnerDevice).isEqualTo("a")
    assertThat(merged.winner!!.seq).isEqualTo(9)
  }

  @Test
  fun d6_vincePiuClassiPreparatePoiIlDispositivoMinore() {
    val few = row("a", "s1", Fx.put(path, 1, Fx.file(1, media = 5, prepared = setOf(THUMB_S))))
    val many = row("z", "s2", Fx.put(path, 1, Fx.file(1, media = 6, prepared = setOf(THUMB_S, THUMB_M, HQ))))
    assertThat(Fusion.merge(key, listOf(few, many), listingOf(1)).winnerDevice).isEqualTo("z")

    val tieA = row("c", "s1", Fx.put(path, 1, Fx.file(1, media = 7, prepared = setOf(THUMB_S, THUMB_M))))
    val tieB = row("b", "s2", Fx.put(path, 1, Fx.file(1, media = 8, prepared = setOf(THUMB_S, HQ))))
    repeat(10) {
      assertThat(Fusion.merge(key, listOf(tieA, tieB).shuffled(), listingOf(1)).winnerDevice).isEqualTo("b")
    }
  }

  @Test
  fun d6_eN12_unRecordConUnTimbroDiversoDallUltimoElencoNonVale() {
    val stale = row("a", "s1", Fx.put(path, 1, Fx.file(1, prepared = setOf(THUMB_S, THUMB_M, HQ), mtime = Fx.NOW - 60_000)))
    val fresh = row("b", "s2", Fx.put(path, 1, Fx.file(1, mtime = Fx.NOW)))
    val merged = Fusion.merge(key, listOf(stale, fresh), listingOf(1, Fx.NOW))
    assertThat(merged.winnerDevice).isEqualTo("b")
    assertThat(merged.provisional).isFalse()

    // Nessun record con il timbro dell'elenco: nessun vincente (il file resta quello della scansione).
    val none = Fusion.merge(key, listOf(stale), listingOf(1, Fx.NOW))
    assertThat(none.winner).isNull()
    assertThat(none.identity).isNull()
  }

  @Test
  fun d6_senzaElencoIlRecordValeProvvisorio() {
    val merged = Fusion.merge(key, listOf(row("a", "s1", Fx.put(path, 1, Fx.file(1)))), listing = null)
    assertThat(merged.winner).isNotNull()
    assertThat(merged.provisional).isTrue()
  }

  @Test
  fun d6_ilFileIdSeNotoDaEntrambeLePartiDeveCoincidere() {
    val other = row("a", "s1", Fx.put(path, 1, Fx.file(1, fileId = 77)))
    val same = row("b", "s1", Fx.put(path, 1, Fx.file(1, fileId = 42)))
    val listing = NasStamp(Fx.content(1).size.toLong(), Fx.NOW, fileId = 42)
    assertThat(Fusion.merge(key, listOf(other, same), listing).winnerDevice).isEqualTo("b")
  }

  @Test
  fun d7_lIdentitaEIlMediaIdMinoreFraIRecordValidiConLoStessoContenuto() {
    val rows = listOf(
      row("a", "s1", Fx.put(path, 1, Fx.file(1, media = 30, prepared = setOf(THUMB_S, THUMB_M)))),
      row("b", "s2", Fx.put(path, 1, Fx.file(1, media = 10))),
      row("c", "s3", Fx.put(path, 1, Fx.file(1, media = 20))),
    )
    val merged = Fusion.merge(key, rows, listingOf(1))
    // Vince "a" per le classi (D6), ma l'identita' converge al minore (D7).
    assertThat(merged.winnerDevice).isEqualTo("a")
    assertThat(merged.identity!!.mediaId).isEqualTo(Fx.mediaId(10))
    assertThat(merged.identity!!.versionId).isEqualTo(Fx.versionId(10))
    assertThat(merged.identity!!.content).isEqualTo(Fx.key(1))
  }

  @Test
  fun d7_unRecordConUnAltroContenutoNonPortaLaSuaIdentita() {
    // Stesso timbro (dimensione e data), qh1 diverso: un dispositivo descrive altri byte. Non entra nell'identita'.
    val honest = Fx.file(1, media = 50, prepared = setOf(THUMB_S, THUMB_M))
    val liarFile = honest.copy(quickHash = Fx.file(2).quickHash, mediaId = Fx.mediaId(1))
    val rows = listOf(row("a", "s1", Fx.put(path, 1, honest)), row("b", "s2", Fx.put(path, 1, liarFile)))
    val merged = Fusion.merge(key, rows, listingOf(1))
    assertThat(merged.winnerDevice).isEqualTo("a")
    assertThat(merged.identity!!.mediaId).isEqualTo(Fx.mediaId(50))
  }
}
