package dev.pampa.mycloudgallery.core.metadata

import android.media.MediaDataSource
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractHints
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.metadata.hash.Fingerprints
import dev.pampa.mycloudgallery.core.metadata.testing.ByteArrayMediaBytes
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import java.io.IOException
import java.time.ZoneId
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Le impronte generiche su [MediaBytes] (Fase 4, P2: lo spostamento di `PhoneFingerprints` di core-mediastore, stessi
 * casi): `qh1` e SHA-256 uguali a quelli calcolati sui byte in memoria, letture solo di testa e coda per l'hash rapido,
 * file cambiati o illeggibili come esiti e mai come eccezioni. In fondo, i nomi neutri di [ExtractHints].
 */
class FingerprintsTest {

  private fun content(size: Int, seed: Int = size) = Random(seed).nextBytes(size)

  @Test
  fun lImprontaRapidaECompletaSonoQuelleDelContratto() = runTest {
    for (size in listOf(1, 1_000, 2 * QuickHashAlgorithm.CHUNK_BYTES, 2 * QuickHashAlgorithm.CHUNK_BYTES + 1, 300_000)) {
      val bytes = content(size)
      val source = ByteArrayMediaBytes(bytes)
      val quick = Fingerprints.quick(source)
      assertThat(quick).isEqualTo(Fingerprints.Quick.Ok(Fingerprint(size.toLong(), QuickHashAlgorithm.ofContent(bytes))))
      // L'hash rapido legge al massimo 128 KiB, anche da un file grande.
      assertThat(source.bytesRead).isAtMost(2L * QuickHashAlgorithm.CHUNK_BYTES)

      val expected = (quick as Fingerprints.Quick.Ok).fingerprint
      // Pezzi piccoli e dispari: testa e coda si ricompongono attraverso i confini dei pezzi.
      assertThat(Fingerprints.full(ByteArrayMediaBytes(bytes), expected, chunkBytes = 4_099)).isEqualTo(Fingerprint.ofContent(bytes))
      assertThat(Fingerprints.full(ByteArrayMediaBytes(bytes), expected)).isEqualTo(Fingerprint.ofContent(bytes))
    }
  }

  @Test
  fun unFileCambiatoVuotoFalsoOIllegibileEUnEsitoNonUnEccezione() = runTest {
    val bytes = content(300_000)
    val expected = Fingerprint.ofContent(bytes).copy(fullHash = null)
    // Cambiato dopo l'impronta rapida (in testa, o accorciato): la lettura completa ricalcola il qh1 e non lo riconosce.
    val changedHead = bytes.copyOf().also { it[10] = (it[10] + 1).toByte() }
    assertThat(Fingerprints.full(ByteArrayMediaBytes(changedHead), expected)).isNull()
    assertThat(Fingerprints.full(ByteArrayMediaBytes(bytes.copyOf(299_999)), expected)).isNull()

    assertThat(Fingerprints.quick(ByteArrayMediaBytes(ByteArray(0)))).isEqualTo(Fingerprints.Quick.ZeroBytes)
    assertThat(Fingerprints.quick(ByteArrayMediaBytes(bytes)) { false }).isEqualTo(Fingerprints.Quick.NotMedia)
    val failing = object : MediaBytes {
      override val size: Long = 300_000
      override suspend fun read(range: LongRange): ByteArray = throw IOException("guasto")
      override fun dataSource(): MediaDataSource = throw UnsupportedOperationException()
    }
    assertThat(Fingerprints.quick(failing)).isEqualTo(Fingerprints.Quick.Unreadable)
    assertThat(Fingerprints.full(failing, expected)).isNull()
    // Un file che si accorcia mentre lo leggiamo (dichiara 300 KB, ne da' meno).
    val shrinking = object : MediaBytes {
      override val size: Long = 300_000
      override suspend fun read(range: LongRange): ByteArray = ByteArray(10)
      override fun dataSource(): MediaDataSource = throw UnsupportedOperationException()
    }
    assertThat(Fingerprints.quick(shrinking)).isEqualTo(Fingerprints.Quick.Unreadable)
  }

  @Suppress("DEPRECATION")
  @Test
  fun gliIndiziHannoINomiNeutriEIVecchiRestanoAlias() {
    val zone = ZoneId.of("Europe/Rome")
    val old = ExtractHints("video/mp4", "VID.mp4", "DCIM/Camera/", 1L, 1920, 1080, 90, 7_500, zone, 2L)
    val neutral = ExtractHints(
      mime = "video/mp4",
      displayName = "VID.mp4",
      relativePath = "DCIM/Camera/",
      scanZone = zone,
      dateTakenMs = 1L,
      dateModifiedMs = 2L,
      width = 1920,
      height = 1080,
      orientation = 90,
      durationMs = 7_500,
    )
    assertThat(old).isEqualTo(neutral)
    assertThat(old.msDateTakenMs).isEqualTo(neutral.dateTakenMs)
    assertThat(old.msWidth).isEqualTo(1920)
    assertThat(old.msDateModifiedMs).isEqualTo(2L)
    // Sul NAS basta il minimo: nome, cartella, fuso e data del file.
    assertThat(ExtractHints("image/jpeg", "IMG.jpg", "Foto/2024/", zone, dateModifiedMs = 3L).dateTakenMs).isNull()
  }
}
