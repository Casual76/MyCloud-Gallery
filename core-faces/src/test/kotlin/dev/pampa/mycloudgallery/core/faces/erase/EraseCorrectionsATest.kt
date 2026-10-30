package dev.pampa.mycloudgallery.core.faces.erase

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.AckState
import dev.pampa.mycloudgallery.core.faces.contract.EraseStart
import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import dev.pampa.mycloudgallery.core.faces.contract.FacesLocal
import dev.pampa.mycloudgallery.core.faces.contract.StepState
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * Correzioni dopo le revisioni della Fase 8, pacchetto A (cancellazione e stato locale), sul mondo con piu' dispositivi:
 * - **R13**: un file della generazione tenuto aperto dal PC (il giro non toglie niente e il tempo non e' finito) e' un'attesa **dichiarata**
 *   (`FILE_OPEN`), non un "in corso" muto che riparte ogni minuto;
 * - **R1**: un dispositivo il cui `faces-local` ha perso la generazione ma che ha i file sul disco non "conferma" la cancellazione finche' i file ci sono, e alla
 *   fine non resta niente.
 */
class EraseCorrectionsATest {

  private val world = EraseWorld()

  @After
  fun close() = world.close()

  @Test
  fun r13_unFileTenutoApertoEUnAttesaDichiarataEUnSoloGiroPerRipresa() = runTest {
    val (a, _) = world.activeFamily("A", "B")
    val g = a.generation()
    world.filesHeldOpen = true
    assertThat(a.confirmErase()).isEqualTo(EraseStart.Started)
    val before = a.ops.calls.size
    val report = a.resume()
    // Una sola richiesta al NAS per la generazione (nessun giro stretto), e il motivo e' dichiarato.
    assertThat(a.ops.calls.drop(before).count { it.first == g && it.second == null }).isEqualTo(1)
    assertThat(report.done).isFalse()
    assertThat(report.waiting).isEqualTo(EraseWait.FILE_OPEN)
    val status = a.eraser.status.value!!
    assertThat(status.waiting).isEqualTo(EraseWait.FILE_OPEN)
    assertThat(status.nas).isEqualTo(StepState.WAITING)
    assertThat(world.facesFiles(g)).isNotEmpty()
    // Il PC chiude il file: la cancellazione riparte da sola e arriva in fondo.
    world.filesHeldOpen = false
    assertThat(world.settle(a)).isTrue()
    assertThat(world.allFacesFiles()).isEmpty()
    assertThat(a.eraser.status.value!!.waiting).isNull()
  }

  @Test
  fun r13_ilTempoFinitoNonESempreUnFileAperto() = runTest {
    val (a, _) = world.activeFamily("A", "B")
    // Pochi file per giro: ogni giro toglie qualcosa, il resto al giro dopo. Non e' un'attesa: e' un avanzamento.
    world.maxFilesPerRound = 1
    a.confirmErase()
    val report = a.resume()
    assertThat(report.waiting).isNotEqualTo(EraseWait.FILE_OPEN)
    assertThat(a.eraser.status.value!!.waiting).isNotEqualTo(EraseWait.FILE_OPEN)
  }

  @Test
  fun r1_unDispositivoCheHaPersoLaGenerazioneNonConfermaFincheISuoiFileCiSono() = runTest {
    val (a, b) = world.activeFamily("A", "B")
    val g = a.generation()
    // B: la scrittura di `faces-local` non e' mai arrivata al disco (spazio finito, morte del processo): niente ricorda la generazione, ma i dati ci sono.
    b.localStore.update { FacesLocal.EMPTY }
    b.restart()
    assertThat(b.localStore.local.value.dataGeneration).isNull()
    assertThat(b.local.has(g)).isTrue()
    assertThat(a.confirmErase()).isEqualTo(EraseStart.Started)
    // A annuncia `off|G` con la sua testa personale (la parte locale e' fatta).
    a.personalRound()
    val headBefore = world.heads[b.id]
    // Le prime due cancellazioni di B non riescono (un file bloccato: una dell'allineamento che parte dalla voce nuova, una del gancio): il gancio non lascia
    // uscire una testa `off|G` con i file ancora li'.
    b.local.failErase = 2
    var rounds = 0
    while (b.local.has(g) && rounds++ < 5) {
      b.personalRound()
      // L'invariante: nessuna testa nuova finche' i file di G sono sul disco.
      if (b.local.has(g)) assertThat(world.heads[b.id]).isEqualTo(headBefore)
    }
    assertThat(rounds).isAtLeast(2)
    // Appena i file sono tolti B puo' dire di aver cancellato.
    b.personalRound()
    assertThat(b.local.has(g)).isFalse()
    assertThat(b.local.generationsOnDisk()).isEmpty()
    val said = FacesSettingCodec.parse(world.heads[b.id]!!.entry!!.value)
    assertThat(said).isInstanceOf(FacesSetting.Off::class.java)
    assertThat(world.settle(a)).isTrue()
    assertThat(a.eraser.status.value!!.devices.map { it.state }).containsExactly(AckState.CONFIRMED, AckState.CONFIRMED)
    assertThat(world.allFacesFiles()).isEmpty()
  }
}
