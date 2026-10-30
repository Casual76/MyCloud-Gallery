package dev.pampa.mycloudgallery.core.faces.erase

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.EraseStart
import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * Correzioni della Fase 8, pacchetto E (L320): durante "Disattiva e cancella" con un file tenuto aperto sul NAS (R13: l'attesa `FILE_OPEN`) la Diagnostica **conta** i
 * file trattenuti (`EraseStatus.heldFiles`, `FACES_ERASE_FILES_HELD`): solo il numero, mai un nome o un percorso.
 */
class EraseCorrectionsETest {

  private val world = EraseWorld()

  @After
  fun close() = world.close()

  @Test
  fun l320_conUnFileTenutoApertoLoStatoDiceQuantiFileIlNasTrattiene() = runTest {
    val (a, _) = world.activeFamily("A", "B")
    world.filesHeldOpen = true
    assertThat(a.confirmErase()).isEqualTo(EraseStart.Started)

    a.resume()

    val status = a.eraser.status.value!!
    assertThat(status.waiting).isEqualTo(EraseWait.FILE_OPEN)
    // Senza la correzione lo stato diceva l'attesa ma non quanti file: 0.
    assertThat(status.heldFiles).isGreaterThan(0)
    // Il PC chiude i file: la cancellazione arriva in fondo e il numero torna a zero.
    world.filesHeldOpen = false
    assertThat(world.settle(a)).isTrue()
    assertThat(a.eraser.status.value!!.heldFiles).isEqualTo(0)
  }

  @Test
  fun l320_unaltraAttesaNonConta() = runTest {
    val (a, _) = world.activeFamily("A", "B")
    // Pochi file per giro: ogni giro toglie qualcosa. Non c'e' un file tenuto aperto, quindi niente da contare.
    world.maxFilesPerRound = 1
    a.confirmErase()

    a.resume()

    val status = a.eraser.status.value!!
    assertThat(status.waiting).isNotEqualTo(EraseWait.FILE_OPEN)
    assertThat(status.heldFiles).isEqualTo(0)
  }
}
