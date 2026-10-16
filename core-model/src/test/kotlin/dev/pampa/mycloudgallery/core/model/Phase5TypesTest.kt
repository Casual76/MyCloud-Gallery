package dev.pampa.mycloudgallery.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** I tipi della Fase 5 fissati da P0 (`docs/fase5-progetto.md` D15, D36): bit della cella, segno "problema", copie simili. */
class Phase5TypesTest {

  @Test
  fun ilProblemaDelBackupVinceSuOgniAltroSegno() {
    val safe = CellFlags.ON_PHONE or CellFlags.ON_NAS or CellFlags.SAFE
    assertThat(PresenceBadge.of(safe)).isEqualTo(PresenceBadge.SAFE)
    // Un problema non si nasconde mai dietro "al sicuro" (D15) ne' dietro "da verificare".
    assertThat(PresenceBadge.of(safe or CellFlags.BACKUP_PROBLEM)).isEqualTo(PresenceBadge.PROBLEM)
    assertThat(PresenceBadge.of(CellFlags.ON_PHONE or CellFlags.TO_VERIFY or CellFlags.BACKUP_PROBLEM)).isEqualTo(PresenceBadge.PROBLEM)
    // Anche senza bit di presenza (perso prima del backup, E-B4).
    assertThat(PresenceBadge.of(CellFlags.BACKUP_PROBLEM)).isEqualTo(PresenceBadge.PROBLEM)
    // La copertina delle copie simili non e' un segno di presenza.
    assertThat(PresenceBadge.of(CellFlags.ON_PHONE or CellFlags.SIMILAR_COPIES or CellFlags.GROUP_COVER)).isEqualTo(PresenceBadge.PHONE_ONLY)
  }

  @Test
  fun leCopieSimiliHannoIlLoroCodiceStabile() {
    assertThat(MediaGroupKind.SIMILAR_COPIES.code).isEqualTo("similar_copies")
    assertThat(MediaGroupKind.entries.map { it.code }).containsAtLeast("burst", "raw_jpeg_pair", "probable_duplicate", "similar_copies")
  }

  @Test
  fun laFirmaVisivaEUnContenitoreImmutabileEConfrontabileSoloConLaSuaForma() {
    val bytes = ByteArray(3 * 32 * 32) { it.toByte() }
    val a = VisualSignature.of(algorithm = 1, frames = 1, bytes = bytes)
    bytes[0] = 99
    assertThat(a.bytes()[0]).isEqualTo(0.toByte())
    assertThat(a).isEqualTo(VisualSignature.of(1, 1, ByteArray(3 * 32 * 32) { it.toByte() }))
    assertThat(a.comparableWith(VisualSignature.of(2, 1, ByteArray(3 * 32 * 32)))).isFalse()
    assertThat(a.comparableWith(VisualSignature.of(1, 2, ByteArray(3 * 32 * 32 * 2)))).isFalse()
    // Mai i byte nei log.
    assertThat(a.toString()).doesNotContain(",0,")
    assertThat(a.toString()).contains("3072 B")
    assertThrows(IllegalArgumentException::class.java) { VisualSignature.of(0, 1, ByteArray(4)) }
    assertThrows(IllegalArgumentException::class.java) { VisualSignature.of(1, 1, ByteArray(0)) }
    assertThrows(IllegalArgumentException::class.java) { VisualSignature.of(1, 2, ByteArray(5)) }
    assertThrows(IllegalArgumentException::class.java) { VisualSignature.of(1, 1, ByteArray(VisualSignature.MAX_BYTES + 1)) }
  }
}
