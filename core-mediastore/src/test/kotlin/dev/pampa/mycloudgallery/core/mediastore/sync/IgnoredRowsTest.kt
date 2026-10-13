package dev.pampa.mycloudgallery.core.mediastore.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreRow
import dev.pampa.mycloudgallery.core.mediastore.contract.RowType
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaContent
import dev.pampa.mycloudgallery.core.model.LocationState
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Le righe che non entrano in Libreria (E-M12): zero byte, byte iniziali non da media, nomi impossibili. Contate per la
 * Diagnostica, mai registrate. E le righe che non si leggono **adesso**: ne' ignorate per sempre ne' assenti.
 */
@RunWith(AndroidJUnit4::class)
class IgnoredRowsTest : PhoneSyncTestBase() {

  @Test
  fun zeroByteEFalsiMediaSonoContatiEMaiInLibreria() = runBlocking<Unit> {
    val empty = store.put(photo(1, taken = T0, size = 0), ByteArray(0))
    val zeros = store.put(photo(2, taken = T0 + MINUTE, size = 2_000), ByteArray(2_000))
    val html = "<!DOCTYPE html><html><body>Download non riuscito</body></html>".toByteArray()
    val page = store.put(photo(3, taken = T0 + 2 * MINUTE, size = html.size.toLong()), html)
    val impossible = store.put(photo(4, taken = T0 + 3 * MINUTE, name = "a:b.jpg"))
    val good = store.put(photo(5, taken = T0 + 4 * MINUTE))
    // Dichiarato 5 KB, in realta' vuoto (scrittura interrotta).
    val truncated = store.put(photo(6, taken = T0 + 5 * MINUTE, size = 5_000), ByteArray(0))

    val sync = newSync()
    val report = sync.syncNow(SyncReason.APP_START)

    assertThat(report.ignored).isEqualTo(5)
    assertThat(report.added).isEqualTo(1)
    assertThat(mediaCount()).isEqualTo(1)
    assertThat(mediaAt(good)).isNotNull()
    listOf(empty, zeros, page, truncated).forEach { assertThat(mediaAt(it)).isNull() }
    val ignored = sync.stats.snapshot.value.ignored
    assertThat(ignored[IgnoreReason.ZERO_BYTES]).isEqualTo(2L)
    assertThat(ignored[IgnoreReason.NOT_MEDIA]).isEqualTo(2L)
    assertThat(ignored[IgnoreReason.INVALID_NAME]).isEqualTo(1L)
    assertThat(impossible.id).isEqualTo(4L)
  }

  @Test
  fun unFormatoSconosciutoMaNonSospettoEntra() = runBlocking<Unit> {
    // Nessuna firma nota, binario, MIME senza firma precisa: decide META, non la lettura.
    val bytes = ByteArray(3_000) { (it * 31 + 7).toByte() }.also { it[0] = 0x13; it[1] = 0x37 }
    val row = MediaStoreRow(
      volume = PRIMARY, id = 1, type = RowType.IMAGE, displayName = "scatto.xyz", relativePath = "DCIM/Altro/",
      mime = "image/x-sconosciuto", size = 3_000, dateModifiedMs = T0, dateTakenMs = T0,
    )
    store.put(row, bytes)
    assertThat(newSync().syncNow(SyncReason.APP_START).added).isEqualTo(1)
  }

  @Test
  fun unFileCheNonSiApreAdessoNonEIgnoratoNeAssente() = runBlocking<Unit> {
    val known = store.put(photo(1, taken = T0))
    store.put(photo(2, taken = T0 + MINUTE))
    newSync().syncNow(SyncReason.APP_START)

    // Il file noto cambia ed e' illeggibile proprio durante una scansione completa.
    store.put(edited(known))
    store.failingOpens += 1L
    store.bumpVersion(PRIMARY, "v2")
    now += MINUTE
    val report = newSync().syncNow(SyncReason.APP_START)
    assertThat(report.vanished).isEqualTo(0)
    assertThat(stateAt(known)).isEqualTo(LocationState.PRESENT)

    // Al giro dopo si legge: e' una versione nuova dello stesso media.
    store.failingOpens.clear()
    val next = newSync().syncNow(SyncReason.CONTENT_CHANGE)
    assertThat(next.updated).isEqualTo(1)
    assertThat(versionCount()).isEqualTo(3)
  }

  @Test
  fun fileGrandiOltreLaSogliaNonSiLegonoPerInteroERestanoSeparati() = runBlocking<Unit> {
    // Due file da 300 KB con testa e coda uguali, soglia dell'hash completo a 100 KB: nessuna lettura intera.
    val sync = DefaultPhoneLibrarySync(
      source = store, access = access, catalog = catalog, reads = reads, clock = { now }, monotonicMs = { mono },
      dispatchers = InlineDispatchers, config = PhoneSyncConfig(batchSize = 3, fullHashMaxBytes = 100_000),
    )
    val first = FakeMediaContent.mp4(300_000, seed = 9)
    val second = first.copyOf().also { it[150_000] = (it[150_000] + 1).toByte() }
    store.put(video(1, taken = T0, size = 300_000), first)
    store.put(video(2, taken = T0 + MINUTE, size = 300_000), second)
    sync.syncNow(SyncReason.APP_START)

    assertThat(mediaCount()).isEqualTo(2)
    // Solo testa e coda (64 KiB ciascuna) per file.
    assertThat(store.bytesRead).isEqualTo(4L * 64 * 1024)
  }
}
