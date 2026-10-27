package dev.pampa.mycloudgallery.core.faces.erase

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.AckState
import dev.pampa.mycloudgallery.core.faces.contract.EraseStart
import dev.pampa.mycloudgallery.core.faces.contract.EraseStep
import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseReason
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import dev.pampa.mycloudgallery.core.faces.contract.StepState
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * "Disattiva e cancella" sul mondo con tre dispositivi dell'account (docs/fase8-progetto.md §2.8; casi della riga P8 del §4.4): tutto acceso (F100),
 * un dispositivo spento che torna e non pubblica mai `G` (E-F32, F101), un calcolo in corso durante la cancellazione, fuori casa (E-F30, F102), il
 * congelamento (D35), generazioni non correnti e dispositivi revocati (D31, E-F33, E-F35), il cestino di rete (E-F34), la recinzione (D34), la
 * cronologia e gli album smart. Gli invarianti del §2.8 si controllano dopo ogni passo.
 */
class EraseScenariosTest {

  private val world = EraseWorld()
  private val invariants = EraseInvariants(world)

  @After
  fun close() = world.close()

  @Test
  fun tuttoAccesoLaCancellazioneArrivaOvunque() = runTest {
    val (a, b, c) = world.activeFamily("A", "B", "C", invariants = invariants)
    val g = a.generation()
    assertThat(world.facesFiles(g)).isNotEmpty()
    a.history.entries += "tramonto al mare"
    assertThat(a.confirmErase(PeopleAlbumsChoice.KEEP_PHOTOS)).isEqualTo(EraseStart.Started)
    invariants.check("dopo la conferma")
    // Passi 1-6 subito: la chiave per prima, il database, la cronologia, gli album, la voce off.
    assertThat(a.local.has(g)).isFalse()
    assertThat(a.local.log).containsExactly("key:${g.base32}", "files:${g.base32}").inOrder()
    assertThat(a.history.entries).containsExactly("tramonto al mare")
    assertThat(a.albums.released).containsExactly(PeopleAlbumsChoice.KEEP_PHOTOS)
    assertThat(a.personal.current()).isEqualTo(FacesSetting.Off(g))
    assertThat(a.switch.currentState()).isEqualTo(FacesState.Off)
    assertThat(a.eraser.status.value!!.local).isEqualTo(StepState.DONE)
    assertThat(world.settle(a, invariants = invariants)).isTrue()
    invariants.final("fine", a)
    assertThat(world.allFacesFiles()).isEmpty()
    for (device in listOf(a, b, c)) {
      assertThat(device.local.generationsOnDisk()).isEmpty()
      assertThat(device.switch.currentState()).isEqualTo(FacesState.Off)
    }
    val status = a.eraser.status.value!!
    assertThat(status.step).isEqualTo(EraseStep.DONE)
    assertThat(status.devices.map { it.state }).containsExactly(AckState.CONFIRMED, AckState.CONFIRMED, AckState.CONFIRMED)
    assertThat(status.waiting).isNull()
  }

  @Test
  fun unDispositivoSpentoCheTornaCancellaPrimaDiPubblicareENonRipopolaIlNas() = runTest {
    val (a, _, c) = world.activeFamily("A", "B", "C", invariants = invariants)
    val g = a.generation()
    c.online = false
    a.confirmErase()
    world.settle(a, turns = 6, invariants = invariants)
    // Tutto fatto tranne la conferma di C, che e' spento.
    val waiting = a.eraser.status.value!!
    assertThat(waiting.waiting).isEqualTo(EraseWait.DEVICES)
    assertThat(waiting.devices.single { it.deviceId == c.id }.state).isEqualTo(AckState.WAITING)
    assertThat(world.facesFiles(g)).isEmpty()
    assertThat(c.local.has(g)).isTrue()
    // C torna: un processo nuovo, lo stato non e' fresco; nessuna pubblicazione dei volti prima del giro dei dati personali.
    c.restart()
    c.online = true
    c.publish()
    assertThat(world.facesFiles(g)).isEmpty()
    // Il giro: legge off|G, il gancio cancella i dati PRIMA di pubblicare la testa che lo dice.
    c.personalRound()
    invariants.check("ritorno di C")
    assertThat(c.local.has(g)).isFalse()
    assertThat(FacesSettingCodecs.of(world.heads.getValue(c.id))).isEqualTo(FacesSetting.Off(g))
    c.publish()
    assertThat(world.facesFiles(g)).isEmpty()
    assertThat(world.settle(a, invariants = invariants)).isTrue()
    invariants.final("fine", a)
  }

  @Test
  fun unDispositivoAncoraFrescoCheRipubblicaVieneRipulito() = runTest {
    val (a, b) = world.activeFamily("A", "B", invariants = invariants)
    val g = a.generation()
    b.online = false
    a.confirmErase()
    world.settle(a, turns = 4, invariants = invariants)
    assertThat(world.facesFiles(g)).isEmpty()
    // B torna entro il quarto d'ora con lo stato ancora fresco su G (non ha ancora letto off|G): pubblica prima di saperlo.
    b.online = true
    check(b.beginCompute())
    b.endCompute(99)
    b.publish()
    assertThat(world.facesFiles(g)).isNotEmpty()
    // La verifica di A la trova e la toglie di nuovo; B al suo giro cancella e conferma.
    assertThat(world.settle(a, invariants = invariants)).isTrue()
    invariants.final("fine", a)
    assertThat(world.facesFiles(g)).isEmpty()
    assertThat(b.local.has(g)).isFalse()
  }

  @Test
  fun unCalcoloInCorsoDuranteLaCancellazioneNonSalvaNienteDopo() = runTest {
    val (a, b) = world.activeFamily("A", "B", invariants = invariants)
    val g = a.generation()
    // A e B hanno un media in mano (archivio aperto) quando la cancellazione parte.
    check(a.beginCompute())
    check(b.beginCompute())
    a.confirmErase()
    assertThat(a.endCompute(77)).isFalse()
    assertThat(a.beginCompute()).isFalse()
    assertThat(a.local.has(g)).isFalse()
    // A annuncia; B riceve off|G: il gancio cancella; il media in mano non si salva, il recinto non riapre niente.
    a.resume()
    b.personalRound()
    assertThat(b.endCompute(78)).isFalse()
    assertThat(b.beginCompute()).isFalse()
    assertThat(b.local.has(g)).isFalse()
    invariants.check("calcolo")
    assertThat(world.settle(a, invariants = invariants)).isTrue()
    invariants.final("fine", a)
  }

  @Test
  fun fuoriCasaLocaleSubitoIlNasAlRitorno() = runTest {
    val (a, b) = world.activeFamily("A", "B", invariants = invariants)
    val g = a.generation()
    a.online = false
    assertThat(a.confirmErase()).isEqualTo(EraseStart.Started)
    assertThat(a.local.has(g)).isFalse()
    repeat(3) { a.resume() }
    val away = a.eraser.status.value!!
    assertThat(away.local).isEqualTo(StepState.DONE)
    assertThat(away.nas).isEqualTo(StepState.WAITING)
    assertThat(away.waiting).isEqualTo(EraseWait.NAS)
    assertThat(world.facesFiles(g)).isNotEmpty()
    a.online = true
    assertThat(world.settle(a, invariants = invariants)).isTrue()
    invariants.final("fine", a)
    assertThat(b.local.has(g)).isFalse()
  }

  @Test
  fun colCongelamentoIlNasAspettaIlLocaleNo() = runTest {
    val (a) = world.activeFamily("A", "B", invariants = invariants)
    val g = a.generation()
    world.frozen = true
    a.confirmErase()
    world.settle(a, turns = 3, invariants = invariants)
    assertThat(a.local.has(g)).isFalse()
    assertThat(a.eraser.status.value!!.waiting).isEqualTo(EraseWait.FROZEN)
    assertThat(world.facesFiles(g)).isNotEmpty()
    world.frozen = false
    assertThat(world.settle(a, invariants = invariants)).isTrue()
    invariants.final("fine", a)
  }

  @Test
  fun generazioniNonCorrentiERevocatiSiTolgono_iRevocatiNonSiAspettano() = runTest {
    val (a, b, c) = world.activeFamily("A", "B", "C", invariants = invariants)
    // Una generazione vecchia rimasta sul NAS (un'attivazione di prima) e C revocato (perso).
    val old = dev.pampa.mycloudgallery.core.model.FaceGeneration.fromBytes(ByteArray(16) { 42 })
    val stale = NasLayout.facesHead(old, b.id, 1).also { world.share.plant(it, byteArrayOf(1)) }
    world.family.changeStatus(c.signer, dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus.REVOKED)
    c.online = false
    a.confirmErase()
    assertThat(world.settle(a, invariants = invariants)).isTrue()
    invariants.final("fine", a)
    assertThat(world.share.bytes(stale)).isNull()
    assertThat(a.eraser.status.value!!.devices.single { it.deviceId == c.id }.state).isEqualTo(AckState.NOT_EXPECTED)
    assertThat(a.ops.calls.map { it.third }).contains(NasEraseReason.NOT_CURRENT)
  }

  @Test
  fun ilCestinoDiReteSiDiceFinoAQuandoNonSiSvuota() = runTest {
    val (a) = world.activeFamily("A", "B", invariants = invariants)
    world.recycleBin = true
    a.confirmErase()
    assertThat(world.settle(a, invariants = invariants)).isTrue()
    assertThat(a.eraser.status.value!!.waiting).isEqualTo(EraseWait.RECYCLE_BIN)
    world.binCopies.clear()
    a.resume()
    assertThat(a.eraser.status.value!!.waiting).isNull()
  }

  @Test
  fun conLaRecinzioneSiCancellaSoloQui() = runTest {
    val a = world.device("A")
    a.requirements.localOnly = true
    a.activate()
    a.personalRound()
    check(a.beginCompute())
    a.endCompute(1)
    val g = a.generation()
    val preview = a.eraser.preview()
    assertThat(preview.localOnly).isTrue()
    assertThat(preview.devices).isEmpty()
    a.confirmErase()
    a.resume()
    val status = a.eraser.status.value!!
    assertThat(status.step).isEqualTo(EraseStep.DONE)
    assertThat(status.nas).isEqualTo(StepState.NOT_NEEDED)
    assertThat(a.local.has(g)).isFalse()
    assertThat(a.ops.calls).isEmpty()
  }

  @Test
  fun cronologiaEAlbumSmart_soloINomiDiQuesteGenerazioni() = runTest {
    val (a) = world.activeFamily("A", "B", invariants = invariants)
    a.history.entries += listOf("cane al parco", "persona0x ieri")
    a.albums.albums = 3
    val preview = a.eraser.preview()
    assertThat(preview.namedPeople).isEqualTo(1)
    assertThat(preview.albumsWithPeople).isEqualTo(3)
    assertThat(preview.historyWithNames).isTrue()
    a.confirmErase(PeopleAlbumsChoice.DROP_RULE)
    assertThat(a.albums.released).containsExactly(PeopleAlbumsChoice.DROP_RULE)
    assertThat(a.history.entries).containsExactly("cane al parco")
  }

  @Test
  fun riattivareDopoDaUnaGenerazioneNuova_nienteDellaVecchiaTorna() = runTest {
    val (a, b) = world.activeFamily("A", "B", invariants = invariants)
    val g = a.generation()
    b.online = false
    a.confirmErase()
    world.settle(a, turns = 4, invariants = invariants)
    // Riattivazione mentre B e' ancora spento (E-F33): generazione nuova.
    assertThat(a.activate()).isInstanceOf(dev.pampa.mycloudgallery.core.faces.contract.ActivateOutcome.Activated::class.java)
    val g2 = a.generation()
    assertThat(g2).isNotEqualTo(g)
    b.online = true
    assertThat(world.settle(a, invariants = invariants)).isTrue()
    b.acceptNotice()
    world.turn(invariants)
    invariants.final("fine", a)
    assertThat(b.local.has(g)).isFalse()
    assertThat(world.facesFiles(g)).isEmpty()
    assertThat(b.switch.currentState()).isEqualTo(FacesState.On(g2, dev.pampa.mycloudgallery.core.faces.contract.HereState.ACTIVE))
  }
}

/** La voce `faces` di una testa. */
internal object FacesSettingCodecs {
  fun of(head: dev.pampa.mycloudgallery.core.personal.contract.HeadValue): FacesSetting? =
    dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec.parse(head.entry?.value)
}
