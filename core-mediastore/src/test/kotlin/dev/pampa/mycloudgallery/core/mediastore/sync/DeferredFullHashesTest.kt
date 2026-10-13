package dev.pampa.mycloudgallery.core.mediastore.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaContent
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gli hash completi rimandati (P11): due copie identiche di un file grande nascono separate durante la lettura (l'hash
 * completo oltre la soglia non si calcola mentre si scorre) e la manutenzione in carica le rilegge e le unisce.
 */
@RunWith(AndroidJUnit4::class)
class DeferredFullHashesTest : PhoneSyncTestBase() {

  /** Soglia minuscola: i file della prova sono "grandi" gia' a 5 KB. */
  override val config = PhoneSyncConfig(batchSize = 3, vanishCap = 2, vanishAlarmMin = 50, fullHashMaxBytes = 1_000)

  private fun pass() = DeferredFullHashes(store, access, catalog, db, config, monotonicMs = { mono }, dispatchers = InlineDispatchers)

  @Test
  fun dueCopieIdenticheGrandiSiUnisconoInManutenzione() = runBlocking<Unit> {
    val bytes = FakeMediaContent.jpeg(5_000, seed = 9)
    val a = store.put(photo(1, taken = T0, size = 5_000), bytes)
    val b = store.put(photo(2, taken = T0 + MINUTE, name = "copia.jpg", path = "Download/", size = 5_000), bytes.copyOf())
    newSync().syncNow(SyncReason.APP_START)
    // Durante la lettura: nel dubbio non si unisce (l'hash completo sopra la soglia e' rimandato).
    assertThat(mediaAt(a)).isNotEqualTo(mediaAt(b))
    assertThat(mediaCount()).isEqualTo(2)

    val report = pass().run(5.minutes)

    assertThat(report.hashed).isEqualTo(2)
    assertThat(report.merged).isEqualTo(1)
    assertThat(report.completed).isTrue()
    assertThat(mediaCount()).isEqualTo(1)
    assertThat(mediaAt(a)).isEqualTo(mediaAt(b))
    // Un secondo giro non ha piu' niente da leggere.
    assertThat(pass().run(5.minutes).hashed).isEqualTo(0)
  }

  @Test
  fun byteDiversiConLaStessaImprontaRapidaRestanoSeparati() = runBlocking<Unit> {
    // Stessa testa e stessa coda (impronta rapida uguale), un byte diverso in mezzo: due file diversi.
    val first = FakeMediaContent.jpeg(300_000, seed = 4)
    val second = first.copyOf().also { it[150_000] = (it[150_000] + 1).toByte() }
    val a = store.put(photo(1, taken = T0, size = 300_000), first)
    val b = store.put(photo(2, taken = T0 + MINUTE, name = "altro.jpg", path = "Download/", size = 300_000), second)
    newSync().syncNow(SyncReason.APP_START)

    val report = pass().run(5.minutes)

    assertThat(report.hashed).isEqualTo(2)
    assertThat(report.merged).isEqualTo(0)
    assertThat(mediaAt(a)).isNotEqualTo(mediaAt(b))
  }

  @Test
  fun senzaAccessoCompletoNonLeggeNiente() = runBlocking<Unit> {
    val bytes = FakeMediaContent.jpeg(5_000, seed = 9)
    store.put(photo(1, taken = T0, size = 5_000), bytes)
    store.put(photo(2, taken = T0 + MINUTE, name = "copia.jpg", path = "Download/", size = 5_000), bytes.copyOf())
    newSync().syncNow(SyncReason.APP_START)
    val opens = store.opens

    setAccess(MediaAccess.PARTIAL)
    val report = pass().run(5.minutes)

    assertThat(report.completed).isFalse()
    assertThat(report.hashed).isEqualTo(0)
    assertThat(store.opens).isEqualTo(opens)
  }

  @Test
  fun lePhotoSenzaPermessoDellaPosizioneAspettano() = runBlocking<Unit> {
    val bytes = FakeMediaContent.jpeg(5_000, seed = 9)
    store.put(photo(1, taken = T0, size = 5_000), bytes)
    store.put(photo(2, taken = T0 + MINUTE, name = "copia.jpg", path = "Download/", size = 5_000), bytes.copyOf())
    newSync().syncNow(SyncReason.APP_START)

    setAccess(MediaAccess.FULL, mediaLocation = false)
    val report = pass().run(5.minutes)

    // D9: mai un'impronta su byte oscurati.
    assertThat(report.hashed).isEqualTo(0)
    assertThat(report.skipped).isEqualTo(2)
    assertThat(mediaCount()).isEqualTo(2)
  }
}
