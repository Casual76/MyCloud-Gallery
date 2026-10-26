package dev.pampa.mycloudgallery.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** Fase 7 (P0): i codici degli album sono contratti del database v6, dei dati personali e del formato `album`. */
class Phase7TypesTest {

  /** I codici scritti nel progetto (D4, D10, D21, D19): fissati, mai cambiati. */
  @Test
  fun iCodiciSonoQuelliDelProgetto() {
    assertThat(ShareLevel.entries.map { it.code }).containsExactly("all", "add", "view").inOrder()
    assertThat(AlbumKind.entries.map { it.code }).containsExactly("manual", "smart").inOrder()
    assertThat(AlbumSort.entries.map { it.code }).containsExactly("oldest", "newest", "custom").inOrder()
    assertThat(TripKind.entries.map { it.code }).containsExactly("trip", "event").inOrder()
  }

  /** Q2 (a): "Gli altri aggiungono"; D21: manuali dal piu' recente, smart dal piu' vecchio. */
  @Test
  fun iPredefinitiSonoQuelliDecisi() {
    assertThat(ShareLevel.DEFAULT).isEqualTo(ShareLevel.ADD)
    assertThat(AlbumSort.defaultFor(AlbumKind.MANUAL)).isEqualTo(AlbumSort.NEWEST_FIRST)
    assertThat(AlbumSort.defaultFor(AlbumKind.SMART)).isEqualTo(AlbumSort.OLDEST_FIRST)
    assertThat(ShareLevel.fromCode("view")).isEqualTo(ShareLevel.VIEW)
    assertThat(ShareLevel.fromCode("admin")).isNull()
    assertThat(AlbumKind.fromCode(null)).isNull()
    assertThat(TripKind.fromCode("event")).isEqualTo(TripKind.EVENT)
  }

  /** D7: l'id legato al creatore e' deterministico, cambia con l'account e con il nonce, ed e' un id d'album valido. */
  @Test
  fun lIdLegatoAlCreatoreEDeterministico() {
    val nonce = ByteArray(16) { it.toByte() }
    val a = AlbumId.derived(AccountId("alessio"), nonce)
    assertThat(AlbumId.derived(AccountId("alessio"), nonce.copyOf())).isEqualTo(a)
    assertThat(AlbumId.derived(AccountId("marco"), nonce)).isNotEqualTo(a)
    assertThat(AlbumId.derived(AccountId("alessio"), ByteArray(16))).isNotEqualTo(a)
    // Il valore e' la forma canonica dei primi 16 byte di SHA-256("mcg-album-id\0" ‖ account ‖ nonce).
    val digest = java.security.MessageDigest.getInstance("SHA-256").apply {
      update("mcg-album-id\u0000".toByteArray())
      update("alessio".toByteArray())
      update(nonce)
    }.digest()
    val hex = digest.copyOf(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    assertThat(a.value.replace("-", "")).isEqualTo(hex)
    assertThat(AlbumId(a.value)).isEqualTo(a)
    assertThrows(IllegalArgumentException::class.java) { AlbumId.derived(AccountId("alessio"), ByteArray(15)) }
  }
}
