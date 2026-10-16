package dev.pampa.mycloudgallery.core.mediastore

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreRow
import dev.pampa.mycloudgallery.core.mediastore.contract.RowType
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaStore
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 5A (P0, `docs/fase5-progetto.md` §5.2): il telefono finto con le righe che cambiano **durante una lettura**, per il banco
 * delle interruzioni del backup: riscritta sul posto (E-B15), sparita (E-B4), `_ID` e versione cambiati (E-P12).
 */
class MidReadFakeTest {

  private val store = FakeMediaStore().apply { addVolume(VOLUME) }

  private fun row(id: Long, size: Long = 300_000, modified: Long = 1_000) = MediaStoreRow(
    volume = VOLUME, id = id, type = RowType.IMAGE, displayName = "IMG_$id.jpg", relativePath = "DCIM/Camera/", mime = "image/jpeg",
    size = size, dateModifiedMs = modified, dateTakenMs = 2_000,
  )

  private suspend fun readAll(bytes: dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes, chunk: Int = 64 * 1024): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    var at = 0L
    while (true) {
      val part = bytes.read(at until at + chunk)
      if (part.isEmpty()) break
      out.write(part)
      at += part.size
    }
    return out.toByteArray()
  }

  @Test
  fun unaRigaRiscrittaAMetaLetturaHaUnTimbroNuovoEByteNuovi() = runTest {
    val original = store.put(row(1))
    val before = readAll(store.open(original))
    val trigger = store.duringRead(VOLUME, 1, afterBytes = 100_000, event = FakeMediaStore.MidRead.Rewritten(size = 300_000, dateModifiedMs = 5_000))
    val during = readAll(store.open(original))
    assertThat(trigger.fired).isTrue()
    // I primi 128 KiB (due blocchi letti prima dell'evento) sono quelli di prima, il resto e' del file nuovo: i byte letti sono misti.
    assertThat(during.copyOf(100_000).toList()).isEqualTo(before.copyOf(100_000).toList())
    assertThat(during.toList()).isNotEqualTo(before.toList())
    val now = store.row(VOLUME, 1)!!
    assertThat(now.dateModifiedMs).isEqualTo(5_000)
    assertThat(now.stamp()).isNotEqualTo(original.stamp())
    // L'evento scatta una volta sola.
    assertThat(readAll(store.open(now)).toList()).isEqualTo(readAll(store.open(now)).toList())
  }

  @Test
  fun unaRigaCheSparisceFaFallireLaLetturaSuccessiva() = runTest {
    val original = store.put(row(2))
    store.duringRead(VOLUME, 2, afterBytes = 1, event = FakeMediaStore.MidRead.Vanished)
    val bytes = store.open(original)
    assertThat(bytes.read(0L until 1024L)).hasLength(1024)
    assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { bytes.read(1024L until 2048L) } }
    assertThat(store.row(VOLUME, 2)).isNull()
    assertThat(store.firedDuringReads()).hasSize(1)
  }

  @Test
  fun ilMediaStoreSiRicostruisceAMetaLetturaELIdCambia() = runTest {
    val original = store.put(row(3))
    val expected = readAll(store.open(original))
    store.duringRead(VOLUME, 3, afterBytes = 10, event = FakeMediaStore.MidRead.Renumbered(newVersion = "v2", newId = 33))
    // La lettura in corso continua con gli stessi byte (il descrittore resta valido)...
    assertThat(readAll(store.open(original)).toList()).isEqualTo(expected.toList())
    // ...ma il vecchio _ID non vale piu': la riga ha un id e una versione nuovi.
    assertThat(store.version(VOLUME)).isEqualTo("v2")
    assertThat(store.row(VOLUME, 3)).isNull()
    assertThat(store.row(VOLUME, 33)!!.stamp().size).isEqualTo(original.size)
  }

  private companion object {
    const val VOLUME = "external_primary"
  }
}
