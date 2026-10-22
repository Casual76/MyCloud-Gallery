package dev.pampa.mycloudgallery.core.backup

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.DestinationArea
import dev.pampa.mycloudgallery.core.backup.contract.IncomingDestination
import dev.pampa.mycloudgallery.core.backup.contract.NasDestination
import dev.pampa.mycloudgallery.core.backup.noop.NoSafeCopyRequests
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/** Fase 7 (P0, D12, D28): le firme che la condivisione di un album chiede al backup. La logica e' di P4. */
class AlbumSharingSignaturesTest {

  @Test
  fun leDestinazioniNonDiconoIPercorsiNeiLog() {
    val album = IncomingDestination.AlbumFolder("test-comune", SharePath.of("Album condivisi", "Oslo"))
    val chosen = IncomingDestination.Chosen(NasDestination("test", SharePath.of("Foto"), DestinationArea.PRIVATE))
    assertThat(album.toString()).doesNotContain("Oslo")
    assertThat(album.toString()).doesNotContain("test-comune")
    assertThat(chosen.toString()).doesNotContain("Foto")
  }

  /** Senza guardiano la richiesta della copia privata non e' registrata e lo dice (D28): chi chiama non segna "in arrivo". */
  @Test
  fun senzaGuardianoLaRichiestaNonERegistrata() {
    runBlocking { NoSafeCopyRequests.requestNow(emptyList()) }
    assertThrows(IllegalStateException::class.java) {
      runBlocking { NoSafeCopyRequests.requestNow(listOf(MediaId("00000000-0000-4000-8000-000000000001"))) }
    }
  }
}
