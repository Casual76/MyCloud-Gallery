package dev.pampa.mycloudgallery.core.backup.upload

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.PhoneBytes
import dev.pampa.mycloudgallery.core.backup.contract.PhoneFile
import dev.pampa.mycloudgallery.core.backup.contract.PhoneOpen
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.StopBy
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import java.util.Collections
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * I difetti del backup trovati sul telefono vero nella ripresa delle prove sul campo (`docs/prove-campo-esiti.md`, "Ripresa con `test`"), sul
 * motore vero ([RealEngineKit]).
 *
 * D-6: Android ha fermato il lavoro dell'utente a meta' di un video (il Wi-Fi spento ha tolto la rete al lavoro) e lo stato del motore e'
 * rimasto `Running`: la pagina e la Libreria dicevano "Backup in corso, avviato da te · meno di un minuto" per un lavoro che non c'era piu'.
 *
 * D-11: durante il caricamento dei due video da 2,5 GB la pagina e' rimasta per oltre 10 minuti su "109 kB di 111 kB": il motore ripubblicava lo
 * stato solo alla fine di ogni elemento, e un file grande non faceva mai salire i byte.
 */
class ProveCampoBackupEngineTest {

  private fun bytes(n: Int, seed: Int) = ByteArray(n) { (it * 131 + seed * 7 + (it shr 8)).toByte() }

  @Test
  fun d6_unGiroFermatoDaAndroidAMetaNonLasciaLoStatoInCorso() = runTest {
    val h = RealEngineKit()
    h.kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(20_000, 1)))
    // Il telefono si ferma sulla lettura (il lavoro e' a meta' giro) finche' Android non annulla il lavoro.
    val reading = CompletableDeferred<Unit>()
    h.phone = { inner ->
      object : PhoneBytes by inner {
        override suspend fun open(ref: PhoneRef): PhoneOpen {
          reading.complete(Unit)
          awaitCancellation()
        }
      }
    }
    h.restart()
    val engine = h.engine

    val job = launch { engine.run(BackupKind.PHOTOS, 1.hours, BackupTrigger.USER_NOW) }
    reading.await()
    assertThat(engine.status.value).isInstanceOf(BackupStatus.Running::class.java)
    // Il lavoro perde la sua rete: JobScheduler lo ferma e il servizio annulla il giro.
    job.cancelAndJoin()

    assertWithMessage("stato dopo l'annullamento").that(engine.status.value).isEqualTo(BackupStatus.Stopped(StopBy.SYSTEM))
    // Niente si e' perso: l'elemento resta aperto nel diario e il giro dopo lo porta al sicuro.
    assertThat(h.kit.store.all().single().state.terminal).isFalse()
    h.phone = { it }
    h.restart()
    h.resumeUntilQuiet()
    assertThat(h.kit.store.all().single().state).isEqualTo(UploadState.DONE)
  }

  @Test
  fun d11_iByteCaricatiSalgonoMentreIlVideoSale() = runTest(timeout = kotlin.time.Duration.parse("5m")) {
    val size = 40L shl 20
    val h = RealEngineKit(config = UploadConfig(parallel = 1))
    h.kit.addPhoneMedia("VID_1.mp4", NasContent.virtual(size, seed = 3), kind = MediaKind.VIDEO)
    // A ogni lettura di un pezzo del video si guarda cosa dice lo stato del motore.
    val seen = Collections.synchronizedList(ArrayList<Long>())
    h.phone = { inner ->
      object : PhoneBytes by inner {
        override suspend fun open(ref: PhoneRef): PhoneOpen = when (val opened = inner.open(ref)) {
          is PhoneOpen.Opened -> PhoneOpen.Opened(
            PhoneFile(
              object : MediaBytes by opened.file.bytes {
                override suspend fun read(range: LongRange): ByteArray {
                  (h.engine.status.value as? BackupStatus.Running)?.let { seen += it.bytesDone }
                  return opened.file.bytes.read(range)
                }
              },
              opened.file.stamp,
            ) { opened.file.close() },
          )
          else -> opened
        }
      }
    }
    h.restart()

    h.runAll()

    assertThat(h.kit.store.all().single().state).isEqualTo(UploadState.DONE)
    // Mentre il video saliva lo stato ha detto byte caricati che non erano ne' zero ne' il file intero.
    val midway = seen.filter { it in 1 until size }
    assertWithMessage("byte visti durante il caricamento: ${seen.distinct()}").that(midway).isNotEmpty()
    assertThat(seen.distinct().size).isAtLeast(3)
  }
}
