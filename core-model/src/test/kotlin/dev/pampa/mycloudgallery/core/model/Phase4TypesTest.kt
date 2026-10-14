package dev.pampa.mycloudgallery.core.model

import com.google.common.truth.Truth.assertThat
import java.security.MessageDigest
import org.junit.Assert.assertThrows
import org.junit.Test

/** Le firme della Fase 4 fissate da P0 (docs/fase4-progetto.md §1.2, §1.3, §1.5). */
class Phase4TypesTest {

  private val qh = QuickHashAlgorithm.ofContent(ByteArray(1000) { it.toByte() })

  /**
   * La chiave di contenuto e' un contratto fra dispositivi (formato-nas.md §5.5-bis): si ricalcola qui a mano, byte
   * per byte, cosi' un cambio involontario della codifica (testo invece di byte, little-endian...) rompe il test.
   */
  @Test
  fun laChiaveDiContenutoEQuellaDelFormato() {
    val size = 1000L
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("mcg-ck".toByteArray(Charsets.US_ASCII) + 0)
    digest.update(qh.hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
    digest.update(byteArrayOf(0, 0, 0, 0, 0, 0, 0x03, 0xe8.toByte()))
    val expected = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }

    val key = ContentKey.of(qh, size)

    assertThat(key.hex).isEqualTo(expected)
    assertThat(ContentKey.of(Fingerprint(size, qh))).isEqualTo(key)
    assertThat(key.bytes()).hasLength(32)
    assertThat(key.short).isEqualTo(java.nio.ByteBuffer.wrap(key.bytes()).long)
    // La dimensione entra nella chiave: stessi byte di testa e coda, dimensione diversa, chiave diversa.
    assertThat(ContentKey.of(qh, size + 1)).isNotEqualTo(key)
    assertThrows(IllegalArgumentException::class.java) { ContentKey("ABC") }
    assertThat(key.toString()).doesNotContain(key.hex)
  }

  @Test
  fun ilTimbroDelNasConfrontaIlFileIdSoloSeNotoDaEntrambi() {
    val a = NasStamp(10, 1_700_000_000_000, fileId = 7)
    assertThat(a.sameAs(a.copy())).isTrue()
    assertThat(a.sameAs(a.copy(fileId = null))).isTrue()
    assertThat(a.sameAs(a.copy(fileId = 8))).isFalse()
    assertThat(a.sameAs(a.copy(size = 11))).isFalse()
    assertThat(a.toFileStamp()).isEqualTo(FileStamp(10, 1_700_000_000_000))
    assertThrows(IllegalArgumentException::class.java) { NasStamp(-1, 0) }
  }

  @Test
  fun ilRiferimentoNasNonPortaPercorsi() {
    val ref = MediaRef.Nas(locationId = 5, contentKey = ContentKey.of(qh, 1000).short, mime = "image/jpeg")
    // Nessun percorso, share o nome nel riferimento: si risolvono dal database solo quando serve.
    assertThat(MediaRef.Nas::class.java.declaredFields.map { it.name }).containsNoneOf("path", "share", "name")
    assertThat(ref.contentKey).isNotEqualTo(0L)
    assertThrows(IllegalArgumentException::class.java) { MediaRef.Nas(0, 1, null) }
  }

  @Test
  fun ilFileIdNonFaParteDelPosto() {
    val path = NasPath.of("Foto/IMG_1.JPG")
    val a = Location.Nas(AccountId("alessio"), "alessio", path)
    val b = a.copy(fileId = 99)
    assertThat(b.key).isEqualTo(a.key)
    assertThat(b.folderKey).isEqualTo(a.folderKey)
  }

  @Test
  fun ilBadgeDiPresenzaSiRicavaDaiSoliFlag() {
    assertThat(PresenceBadge.of(0)).isNull()
    assertThat(PresenceBadge.of(CellFlags.ON_PHONE)).isEqualTo(PresenceBadge.PHONE_ONLY)
    assertThat(PresenceBadge.of(CellFlags.ON_NAS)).isEqualTo(PresenceBadge.NAS_ONLY)
    assertThat(PresenceBadge.of(CellFlags.ON_PHONE or CellFlags.ON_NAS)).isEqualTo(PresenceBadge.BOTH)
    assertThat(PresenceBadge.of(CellFlags.ON_PHONE or CellFlags.ON_NAS or CellFlags.TO_VERIFY)).isEqualTo(PresenceBadge.TO_VERIFY)
    assertThat(PresenceBadge.of(CellFlags.ON_PHONE or CellFlags.ON_NAS or CellFlags.SAFE)).isEqualTo(PresenceBadge.SAFE)
    // Gli altri flag non cambiano il badge.
    assertThat(PresenceBadge.of(CellFlags.ON_NAS or CellFlags.FAVORITE or CellFlags.EDITED)).isEqualTo(PresenceBadge.NAS_ONLY)
  }

  @Test
  fun ilGruppoProvvisorioHaIlSuoCodice() {
    assertThat(MediaGroupKind.entries.map { it.code }).contains("probable_duplicate")
  }
}
