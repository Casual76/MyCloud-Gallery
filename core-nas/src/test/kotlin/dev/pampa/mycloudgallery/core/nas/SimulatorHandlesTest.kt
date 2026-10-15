package dev.pampa.mycloudgallery.core.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nas.smb.DirAccess
import dev.pampa.mycloudgallery.core.nas.smb.NtStatus
import dev.pampa.mycloudgallery.core.nas.smb.SmbConnectionLostException
import dev.pampa.mycloudgallery.core.nas.smb.SmbShare
import dev.pampa.mycloudgallery.core.nas.smb.SmbStatusException
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Il simulatore SMB e gli **handle dei client** come su Samba (prova F16 sul NAS vero, 15/10): ogni apertura resta viva sul server con i
 * suoi diritti e le sue condivisioni finche' il client non la chiude o la connessione non cade per il server. Prima il simulatore non li
 * teneva, e un nostro handle rimasto aperto (che sul WD blocca la rinomina del file) qui non si vedeva.
 */
class SimulatorHandlesTest {

  private val backend = Harness.defaultBackend()

  private fun client(): Pair<SmbShare, () -> Unit> {
    val connection = backend.connect(backend.binding(HOME_WIFI), NasAddress("192.168.178.58"), 5.seconds)
    val share = connection.authenticate(USER, GOOD_PASSWORD.toCharArray()).connectShare(SHARE)
    return share to { connection.close() }
  }

  private fun status(block: () -> Unit): Long = assertThrows(SmbStatusException::class.java) { block() }.status

  @Test
  fun unaLetturaApertaLasciaRinominareMaNonCancellareInEsclusiva() {
    backend.put(SHARE, "Foto/a.jpg", ByteArray(10) { 1 })
    val (share, _) = client()
    val read = share.openRead("Foto\\a.jpg")
    assertThat(backend.openHandlesOn(SHARE, "Foto/a.jpg")).isEqualTo(1)
    // La rinomina (diritto DELETE, condivide la lettura) passa: la lettura condivide la cancellazione.
    share.openForChange("Foto\\a.jpg", exclusive = false).use { it.renameNoReplace("Foto\\b.jpg") }
    assertThat(backend.exists(SHARE, "Foto/b.jpg")).isTrue()
    // L'apertura esclusiva (cancellazione con il controllo della dimensione) no, finche' la lettura e' aperta.
    assertThat(status { share.openForChange("Foto\\b.jpg", exclusive = true) }).isEqualTo(NtStatus.SHARING_VIOLATION)
    read.close()
    assertThat(backend.openHandles).isEqualTo(0)
    share.openForChange("Foto\\b.jpg", exclusive = true).use { it.deleteOnClose() }
    assertThat(backend.exists(SHARE, "Foto/b.jpg")).isFalse()
  }

  @Test
  fun unTemporaneoInScritturaNonSiApreNeSiRinominaFinoAllaChiusura() {
    val (share, _) = client()
    share.mkdir("tmp")
    val writer = share.createExclusive("tmp\\t")
    assertThat(status { share.openRead("tmp\\t") }).isEqualTo(NtStatus.SHARING_VIOLATION)
    assertThat(status { share.openForChange("tmp\\t", exclusive = false) }).isEqualTo(NtStatus.SHARING_VIOLATION)
    writer.close()
    share.openForChange("tmp\\t", exclusive = false).use { it.renameNoReplace("pubblicato.jpg") }
    assertThat(backend.exists(SHARE, "pubblicato.jpg")).isTrue()
  }

  @Test
  fun gliHandleMuoionoConLaConnessioneMaNonConUnaConnessionePersaDalTelefono() {
    backend.put(SHARE, "a.jpg", ByteArray(3))
    backend.put(SHARE, "b.jpg", ByteArray(3))
    // Chiusa dal client (il sistema chiude il socket di un processo morto): il server chiude i suoi handle.
    val (first, closeFirst) = client()
    first.openRead("a.jpg")
    closeFirst()
    assertThat(backend.openHandles).isEqualTo(0)
    // Persa dal telefono (Wi-Fi caduto): per il client e' morta, il server la tiene con i suoi handle finche' non se ne accorge.
    val (second, _) = client()
    second.openRead("b.jpg")
    backend.abandonAllConnections()
    assertThrows(SmbConnectionLostException::class.java) { second.stat("b.jpg") }
    assertThat(backend.openHandlesOn(SHARE, "b.jpg")).isEqualTo(1)
    val (third, _) = client()
    assertThat(status { third.openForChange("b.jpg", exclusive = true) }).isEqualTo(NtStatus.SHARING_VIOLATION)
    backend.reapAbandoned()
    third.openForChange("b.jpg", exclusive = true).close()
    assertThat(backend.openHandles).isEqualTo(0)
  }

  @Test
  fun unaCancellazioneConAltriHandleApertiAvvieneAllaChiusuraDellUltimo() {
    backend.put(SHARE, "a.jpg", ByteArray(3))
    val (share, _) = client()
    val read = share.openRead("a.jpg")
    share.deleteFile("a.jpg")
    // Ancora li' (Samba cancella alla chiusura), ma non si apre piu'.
    assertThat(backend.exists(SHARE, "a.jpg")).isTrue()
    assertThat(status { share.openRead("a.jpg") }).isEqualTo(NtStatus.DELETE_PENDING)
    read.close()
    assertThat(backend.exists(SHARE, "a.jpg")).isFalse()
  }

  @Test
  fun unaCartellaConUnFileApertoSottoDalloStessoClientNonSiRinomina() {
    backend.put(SHARE, "Gita/Mare/a.jpg", ByteArray(3))
    val (share, _) = client()
    val read = share.openRead("Gita\\Mare\\a.jpg")
    // Samba (`have_file_open_below`): ACCESS_DENIED per gli handle dello stesso client; quelli di un altro (il PC) non contano.
    assertThat(status { share.openForChange("Gita", exclusive = false).use { it.renameNoReplace("Viaggio") } }).isEqualTo(NtStatus.ACCESS_DENIED)
    val (pc, _) = client()
    read.close()
    val pcRead = pc.openRead("Gita\\Mare\\a.jpg")
    share.openForChange("Gita", exclusive = false).use { it.renameNoReplace("Viaggio") }
    assertThat(backend.exists(SHARE, "Viaggio/Mare/a.jpg")).isTrue()
    pcRead.close()
    // Un elenco aperto sulla cartella stessa condivide tutto: non blocca la rinomina.
    share.openDirectory("Viaggio", DirAccess.LIST).use {
      share.openForChange("Viaggio", exclusive = false).use { h -> h.renameNoReplace("Gita") }
    }
    assertThat(backend.openHandles).isEqualTo(0)
  }

  @Test
  fun lePrimitiveDiProvaDelPcNonVedonoGliHandle() {
    // Le azioni "del PC" del simulatore (put, rename, delete) restano dirette: preparano i casi dei test, non sono un client SMB.
    val sim = InMemorySmbBackend().apply {
      addUser(USER, GOOD_PASSWORD)
      addShare(SHARE, mapOf(USER to dev.pampa.mycloudgallery.core.nas.testing.SimAccess.READ_WRITE))
    }
    sim.put(SHARE, "a.jpg", ByteArray(1))
    sim.rename(SHARE, "a.jpg", "b.jpg")
    sim.delete(SHARE, "b.jpg")
    assertThat(sim.exists(SHARE, "b.jpg")).isFalse()
  }
}
