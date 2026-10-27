package dev.pampa.mycloudgallery.core.faces.erase

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.EraseStart
import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraseConfirmation
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseReason
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import dev.pampa.mycloudgallery.core.faces.switch.LocalEraseWhy
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import dev.pampa.mycloudgallery.core.personal.contract.HeadValue
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * I cancelli di "Disattiva e cancella" (Fase 8, P8; D29-D31, E-F12): la conferma (scaduta, anteprima cambiata, gia' in corso, niente da cancellare,
 * diario che non si scrive), la voce sconosciuta che non si tocca, il recinto delle aperture, la cancellazione locale con la chiave per prima e la
 * cronologia, la pulizia del NAS di ogni dispositivo (generazioni non correnti, revocati, E-F9) e la **voce falsa** che tocca solo dati dei volti.
 */
private fun TrustIdsOf(signer: dev.pampa.mycloudgallery.core.trust.crypto.Signer): String =
  dev.pampa.mycloudgallery.core.trust.crypto.TrustIds.deviceIdOf(signer.publicKey)

class EraseGuardsTest {

  private val world = EraseWorld()
  private val invariants = EraseInvariants(world)

  @After
  fun close() = world.close()

  @Test
  fun laConfermaScadeEPortaLAnteprimaMostrata() = runTest {
    val (a) = world.activeFamily("A", "B")
    val g = a.generation()
    val preview = a.eraser.preview()
    val shown = world.clock.monotonic
    // Dieci minuti dopo la conferma non vale piu'.
    world.clock.advanceMillis(10 * 60_000L + 1)
    assertThat(a.eraser.erase(FacesEraseConfirmation(preview.digest, shown), PeopleAlbumsChoice.KEEP_PHOTOS)).isEqualTo(EraseStart.Expired)
    // Un'anteprima diversa (un nome nuovo dopo che il foglio e' comparso).
    val stale = a.eraser.preview()
    a.name(7, "Nuovo")
    assertThat(a.eraser.erase(FacesEraseConfirmation(stale.digest, world.clock.monotonic), PeopleAlbumsChoice.KEEP_PHOTOS)).isEqualTo(EraseStart.PreviewChanged)
    assertThat(a.journal.stored).isNull()
    assertThat(a.local.has(g)).isTrue()
    // Il diario che non si scrive: niente parte, niente si tocca.
    a.journal.failSaves = 1
    assertThat(a.confirmErase()).isEqualTo(EraseStart.Unavailable)
    assertThat(a.local.has(g)).isTrue()
    assertThat(a.switch.currentState()).isInstanceOf(FacesState.On::class.java)
    assertThat(a.confirmErase()).isEqualTo(EraseStart.Started)
    assertThat(a.confirmErase()).isEqualTo(EraseStart.NothingToErase)
  }

  @Test
  fun unaCancellazioneInCorsoNonRipartePrimaCheLaParteLocaleSiaFinita() = runTest {
    val (a) = world.activeFamily("A", "B")
    a.local.failErase = 10
    assertThat(a.confirmErase()).isEqualTo(EraseStart.Started)
    // La parte locale non e' finita (un file bloccato): lo stato e' Erasing e una seconda conferma non parte.
    assertThat(a.switch.currentState()).isInstanceOf(FacesState.Erasing::class.java)
    val again = a.eraser.preview()
    assertThat(a.eraser.erase(FacesEraseConfirmation(again.digest, world.clock.monotonic), PeopleAlbumsChoice.KEEP_PHOTOS)).isEqualTo(EraseStart.AlreadyRunning)
    a.local.failErase = 0
    a.resume()
    assertThat(a.journal.stored!!.local).isTrue()
    assertThat(a.switch.currentState()).isEqualTo(FacesState.Off)
  }

  @Test
  fun unaVoceSconosciutaNonSiCancellaENonSiSovrascrive() = runTest {
    val (a, b) = world.activeFamily("A", "B")
    val g = a.generation()
    // Un'app piu' nuova scrive una forma che questa non capisce (E-F12).
    val unknown = PersonalEntry(FacesSettingCodec.KEY, world.clock.nasNow!! + 1, b.id, PersonalValue.Text("2|nuova|forma"))
    world.heads[b.id] = HeadValue(unknown, world.clock.nasNow!! + 1)
    a.personalRound()
    assertThat(a.switch.currentState()).isEqualTo(FacesState.NeedsUpdate)
    assertThat(a.confirmErase()).isEqualTo(EraseStart.NothingToErase)
    assertThat(a.local.has(g)).isTrue()
    a.janitor.run(5.minutes)
    assertThat(world.facesFiles(g)).isNotEmpty()
    assertThat(world.deleted).isEmpty()
  }

  @Test
  fun ilRecintoNonRiapreUnaGenerazioneCancellataONonAttiva() = runTest {
    val (a, b) = world.activeFamily("A", "B")
    val g = a.generation()
    val other = FaceGeneration.fromBytes(ByteArray(16) { 9 })
    assertThat(a.fence.allows(g)).isTrue()
    assertThat(a.fence.allows(other)).isFalse()
    a.confirmErase()
    assertThat(a.fence.allows(g)).isFalse()
    assertThat(a.fence.open(g) { a.local.archive(g, create = true) }).isNull()
    assertThat(a.local.has(g)).isFalse()
    // Una morte del processo con il segno locale perso: il diario tiene chiusa la generazione.
    a.localStore.update { it.copy(erasing = null) }
    a.restart()
    assertThat(a.fence.open(g) { a.local.archive(g, create = true) }).isNull()
    // Su B (non ancora informato) la generazione e' aperta; dopo il giro no.
    assertThat(b.fence.allows(g)).isTrue()
    a.resume()
    b.personalRound()
    assertThat(b.fence.allows(g)).isFalse()
  }

  @Test
  fun laCancellazioneLocaleToglieLaChiavePerPrimaELaCronologia() = runTest {
    val (a) = world.activeFamily("A", "B")
    val g = a.generation()
    a.history.entries += "gatto"
    assertThat(a.localErase.erase(g, LocalEraseWhy.EXCLUDED)).isTrue()
    assertThat(a.local.log).containsExactly("key:${g.base32}", "files:${g.base32}").inOrder()
    assertThat(a.history.entries).containsExactly("gatto")
    // Idempotente; un guasto dice "non finito" e la ripresa lo finisce.
    assertThat(a.localErase.erase(g, LocalEraseWhy.EXCLUDED)).isTrue()
    val (_, b) = world.devices.let { it[0] to it[1] }
    b.local.failErase = 1
    assertThat(b.localErase.erase(g, LocalEraseWhy.ACCOUNT_OFF)).isFalse()
    assertThat(b.localErase.erase(g, LocalEraseWhy.ACCOUNT_OFF)).isTrue()
  }

  @Test
  fun laPuliziaDiOgniDispositivo_generazioniNonCorrentiRevocatiEUnaTrattenutaPerE_F9() = runTest {
    val (a, b, c) = world.activeFamily("A", "B", "C")
    val g = a.generation()
    val old = FaceGeneration.fromBytes(ByteArray(16) { 21 })
    val racing = FaceGeneration.fromBytes(ByteArray(16) { 22 })
    world.share.plant(NasLayout.facesHead(old, b.id, 1), byteArrayOf(1))
    world.share.plant(NasLayout.facesHead(racing, b.id, 1), byteArrayOf(1))
    // Una testa recente dice accesa la generazione "racing" (due attivazioni quasi insieme): non si tocca adesso.
    a.personal.view["intruso"] = HeadValue(PersonalEntry(FacesSettingCodec.KEY, world.clock.nasNow!!, c.id, FacesSettingCodec.on(racing, 1)), world.clock.nasNow!!)
    world.family.changeStatus(c.signer, dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus.LOST)
    val report = a.janitor.run(5.minutes)
    assertThat(report.waiting).isNull()
    assertThat(world.facesFiles(old)).isEmpty()
    assertThat(world.facesFiles(racing)).isNotEmpty()
    // La cartella del perso nella generazione corrente si toglie; quelle degli altri restano.
    assertThat(world.share.paths().filter { it.isWithin(NasLayout.facesDeviceDir(g, c.id)) }).isEmpty()
    assertThat(world.share.paths().filter { it.isWithin(NasLayout.facesDeviceDir(g, b.id)) }).isNotEmpty()
    assertThat(a.ops.calls.filter { it.third == NasEraseReason.DEVICE_REVOKED }.map { it.second }).containsExactly(c.id)
    // Un'ora dopo la testa non trattiene piu'.
    world.clock.advanceMillis(EraseRules.CLAIM_WINDOW_MILLIS + 1)
    a.personalRound()
    a.janitor.run(5.minutes)
    assertThat(world.facesFiles(racing)).isEmpty()
    assertThat(world.facesFiles(g)).isNotEmpty()
    invariants.check("pulizia")
  }

  @Test
  fun senzaStatoFrescoLaPuliziaNonParte() = runTest {
    val (a) = world.activeFamily("A", "B")
    a.restart()
    val report = a.janitor.run(5.minutes)
    assertThat(report.skipped).isTrue()
    assertThat(a.ops.calls).isEmpty()
  }

  /**
   * Una voce falsa (un dispositivo compromesso dell'account scrive `off|G`, o la revoca falsa di un dispositivo): i dispositivi cancellano i loro dati
   * dei volti e la cartella della generazione, **solo** quella; mai un media, mai un file fuori da `faces/`, mai i dati personali (L99).
   */
  @Test
  fun unaVoceFalsaToccaSoloDatiDeiVolti() = runTest {
    val (a, b) = world.activeFamily("A", "B", invariants = invariants)
    val g = a.generation()
    // Il dispositivo compromesso e' approvato nell'account: la sua testa firmata vale (la firma non dice se e' onesto).
    val compromised = TrustIdsOf(world.family.approve("Compromesso", "test"))
    val forged = PersonalEntry(FacesSettingCodec.KEY, world.clock.nasNow!! + 1, compromised, FacesSettingCodec.off(g))
    world.heads[compromised] = HeadValue(forged, world.clock.nasNow!! + 1)
    world.clock.advanceMillis(1_000)
    repeat(3) { world.turn(invariants) }
    assertThat(a.local.has(g)).isFalse()
    assertThat(world.facesFiles(g)).isEmpty()
    for (path in world.untouchable) assertThat(world.share.bytes(path)).isNotNull()
    assertThat(world.deleted.map { it.second }.all { it.isWithin(NasLayout.facesGenerationDir(g)) }).isTrue()
    // Una voce falsa con una generazione inventata: si cancellano i dati dei volti di questo account (la voce dice "spenta"), nient'altro.
    val fake = FaceGeneration.fromBytes(ByteArray(16) { 77 })
    world.heads[compromised] = HeadValue(PersonalEntry(FacesSettingCodec.KEY, world.clock.nasNow!! + 1, compromised, FacesSettingCodec.off(fake)), world.clock.nasNow!! + 1)
    world.share.plant(SharePath.parse("Foto/2026/IMG_9999.jpg"), byteArrayOf(9))
    repeat(2) { world.turn(invariants) }
    assertThat(world.share.bytes(SharePath.parse("Foto/2026/IMG_9999.jpg"))).isNotNull()
    invariants.check("voce falsa")
    assertThat(a.personal.current()).isInstanceOf(FacesSetting.Off::class.java)
    assertThat(EraseWait.entries).isNotEmpty()
  }
}
