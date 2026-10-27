package dev.pampa.mycloudgallery.core.faces.sync

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublishOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesSyncStop
import dev.pampa.mycloudgallery.core.faces.contract.FlushReason
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.PeopleKey
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La replica dei dati dei volti (Fase 8, P3, "Fatto quando": anti-rollback; un dispositivo dismesso vale con le teste congelate, uno revocato
 * si ignora; le teste di un altro account non si leggono mai; niente senza lo stato fresco; la generazione che cambia a meta'; §2.4, D11,
 * D21, D30, D31, E-F41, E-F42).
 */
class FacesReplicaTest {

  private val a = FacesSyncWorld.person(1)
  private val b = FacesSyncWorld.person(2)

  private fun nameOf(device: SyncDevice, person: dev.pampa.mycloudgallery.core.model.PersonId): String? = kotlinx.coroutines.runBlocking {
    (device.archive.people()[PeopleKey(person.base32(), "name")]?.edit as? PeopleEdit.Name)?.name
  }

  @Test
  fun treDispositiviDellAccountConvergono() = runTest {
    val world = FacesSyncWorld()
    val phone = world.device("Telefono", "test")
    val tablet = world.device("Tablet", "test")
    val laptop = world.device("Portatile", "test")
    phone.compute(1)
    phone.compute(2, faces = 3)
    tablet.compute(2, faces = 3, recipe = 2)
    laptop.compute(3)
    phone.edit(PeopleEdit.Name(a, "Persona Uno"))
    world.clock.advanceMillis(10)
    tablet.edit(PeopleEdit.Name(a, "Persona Una"), PeopleEdit.Hidden(b, true))
    phone.snapshot(3, 2, mapOf(a to listOf(1 to 0, 1 to 1)))
    repeat(3) {
      for (d in listOf(phone, tablet, laptop)) {
        d.round()
        d.publisher.flush(FlushReason.UNIT_FINISHED)
      }
    }
    for (d in listOf(phone, tablet, laptop)) d.round()
    val reference = phone.archive.people()
    for (d in listOf(tablet, laptop)) assertThat(d.archive.people()).isEqualTo(reference)
    assertThat(nameOf(laptop, a)).isEqualTo("Persona Una")
    val keys = (1..3).map { FacesSyncWorld.key(it) }
    val winners = phone.archive.winners(keys).mapValues { it.value.author to it.value.recipe }
    for (d in listOf(tablet, laptop)) assertThat(d.archive.winners(keys).mapValues { it.value.author to it.value.recipe }).isEqualTo(winners)
    // Il contenuto 2: vince il corpo del tablet (ricetta 2); il raggruppamento del telefono parla del contenuto 1.
    assertThat(winners.getValue(FacesSyncWorld.key(2))).isEqualTo(tablet.id to 2)
    for (d in listOf(phone, tablet, laptop)) {
      val snapshot = d.sync.winningSnapshot()!!
      assertThat(snapshot.device).isEqualTo(phone.id)
      assertThat(snapshot.mark.seq).isEqualTo(3)
      assertThat(snapshot.state.assignments).hasSize(2)
      assertThat(snapshot.unresolved).isEqualTo(0)
    }
  }

  /** D11, C37: le cartelle dei dispositivi di un altro account non si leggono, nemmeno copiate nella mia area; una sua testa messa nella cartella di un mio dispositivo e' rifiutata. */
  @Test
  fun leTesteDiUnAltroAccountNonSiLegonoMai() = runTest {
    val world = FacesSyncWorld()
    val phone = world.device("Telefono", "test")
    val tablet = world.device("Tablet", "test")
    val stranger = world.device("Telefono di Test2", "test2")
    stranger.compute(1)
    stranger.edit(PeopleEdit.Name(a, "Nome di un altro account"))
    assertThat(stranger.publisher.flush(FlushReason.UNIT_FINISHED)).isInstanceOf(FacesPublishOutcome.Published::class.java)
    // Chi ha la password copia tutto nell'area di `test`, anche la testa al posto di quella del mio tablet.
    for (path in stranger.share.paths().filter { it.isWithin(NasLayout.FACES_DIR) }) phone.share.plant(path, stranger.share.bytes(path)!!)
    val headBytes = stranger.share.bytes(NasLayout.facesHead(world.generation, stranger.id, 1))!!
    phone.share.plant(NasLayout.facesHead(world.generation, tablet.id, 1), headBytes)
    val report = phone.round()
    assertThat(report.headsRejected[RejectReason.NOT_OWNER.name]).isEqualTo(2)
    assertThat(phone.share.reads.none { it.isWithin(NasLayout.facesDeviceDir(world.generation, stranger.id)) }).isTrue()
    assertThat(phone.archive.people()).isEmpty()
    assertThat(phone.archive.winners(listOf(FacesSyncWorld.key(1)))).isEmpty()
  }

  /** Un dispositivo dismesso vale con le sole teste congelate; uno revocato si ignora e i suoi dati escono dall'archivio (L99). */
  @Test
  fun dismessoConLeTesteCongelateRevocatoIgnorato() = runTest {
    val world = FacesSyncWorld()
    val tablet = world.device("Tablet", "test")
    val laptop = world.device("Portatile", "test")
    tablet.edit(PeopleEdit.Name(a, "Uno"))
    tablet.publisher.flush(FlushReason.UNIT_FINISHED)
    val frozen = tablet.publishStore.load(world.generation).publication!!.mark
    world.clock.advanceMillis(10)
    tablet.edit(PeopleEdit.Name(a, "Due"))
    tablet.publisher.flush(FlushReason.UNIT_FINISHED)
    laptop.compute(5)
    laptop.edit(PeopleEdit.Name(b, "Dal portatile"))
    laptop.publisher.flush(FlushReason.UNIT_FINISHED)
    world.family.changeStatus(tablet.signer, DeviceStatus.RETIRED, listOf(frozen.contentHash))
    val phone = world.device("Telefono", "test")
    val report = phone.round()
    assertThat(report.headsRejected[RejectReason.SIGNER_RETIRED.name]).isEqualTo(1)
    assertThat(nameOf(phone, a)).isEqualTo("Uno")
    assertThat(nameOf(phone, b)).isEqualTo("Dal portatile")
    assertThat(phone.archive.winners(listOf(FacesSyncWorld.key(5))).getValue(FacesSyncWorld.key(5)).author).isEqualTo(laptop.id)
    // Il portatile revocato: la sua cartella non si legge piu', i suoi corpi e le sue voci escono dall'archivio.
    world.family.changeStatus(laptop.signer, DeviceStatus.REVOKED)
    val before = phone.share.reads.size
    val after = phone.round()
    assertThat(after.headsRejected[RejectReason.SIGNER_REVOKED.name]).isEqualTo(1)
    assertThat(phone.share.reads.drop(before).none { it.isWithin(NasLayout.facesDeviceDir(world.generation, laptop.id)) }).isTrue()
    assertThat(nameOf(phone, b)).isNull()
    assertThat(phone.archive.winners(listOf(FacesSyncWorld.key(5)))).isEmpty()
    assertThat(nameOf(phone, a)).isEqualTo("Uno")
  }

  /** Anti-rollback: togliere dal NAS la testa piu' recente non riporta indietro chi l'ha gia' letta. */
  @Test
  fun antiRollback() = runTest {
    val world = FacesSyncWorld()
    val phone = world.device("Telefono", "test")
    val tablet = world.device("Tablet", "test")
    tablet.edit(PeopleEdit.Name(a, "Uno"))
    tablet.publisher.flush(FlushReason.UNIT_FINISHED)
    world.clock.advanceMillis(10)
    tablet.edit(PeopleEdit.Name(a, "Due"))
    tablet.publisher.flush(FlushReason.UNIT_FINISHED)
    phone.round()
    assertThat(nameOf(phone, a)).isEqualTo("Due")
    tablet.share.delete(NasLayout.facesHead(world.generation, tablet.id, 2))
    phone.restart()
    phone.round()
    assertThat(nameOf(phone, a)).isEqualTo("Due")
    assertThat(phone.replicaStore.load(world.generation).devices.getValue(tablet.id).mark!!.version).isEqualTo(2)
    // Una testa vecchia rimessa sotto un numero nuovo non e' al suo posto.
    val old = tablet.share.bytes(NasLayout.facesHead(world.generation, tablet.id, 1))!!
    tablet.share.plant(NasLayout.facesHead(world.generation, tablet.id, 3), old)
    val report = phone.round()
    assertThat(report.headsRejected[RejectReason.WRONG_LOCATION.name]).isEqualTo(1)
    assertThat(nameOf(phone, a)).isEqualTo("Due")
  }

  /** D30: senza lo stato fresco non parte nessuna richiesta; le generazioni non correnti si contano e non si leggono mai (D31). */
  @Test
  fun freschezzaEGenerazioniNonCorrenti() = runTest {
    val world = FacesSyncWorld()
    val phone = world.device("Telefono", "test")
    val tablet = world.device("Tablet", "test")
    tablet.edit(PeopleEdit.Name(a, "Uno"))
    tablet.publisher.flush(FlushReason.UNIT_FINISHED)
    val old = FaceGeneration.fromBytes(ByteArray(16) { 3 })
    phone.share.plant(NasLayout.facesHead(old, tablet.id, 1), tablet.share.bytes(NasLayout.facesHead(world.generation, tablet.id, 1))!!)
    phone.switch.fresh.value = false
    assertThat(phone.round().stop).isEqualTo(FacesSyncStop.NOT_FRESH)
    assertThat(phone.share.reads).isEmpty()
    phone.switch.state.value = FacesState.AwaitingNotice(world.generation, 1)
    phone.switch.fresh.value = true
    assertThat(phone.round().stop).isEqualTo(FacesSyncStop.NOT_ACTIVE)
    phone.switch.state.value = FacesState.On(world.generation, HereState.ACTIVE)
    val report = phone.round()
    assertThat(report.foreignGenerations).isEqualTo(1)
    assertThat(phone.share.reads.none { it.isWithin(NasLayout.facesGenerationDir(old)) }).isTrue()
    assertThat(nameOf(phone, a)).isEqualTo("Uno")
  }

  /** La generazione che cambia a meta' del giro: si ferma, e niente entra nell'archivio dopo. */
  @Test
  fun laGenerazioneCheCambiaAMeta() = runTest {
    val world = FacesSyncWorld()
    val phone = world.device("Telefono", "test")
    val tablet = world.device("Tablet", "test")
    tablet.compute(1)
    tablet.edit(PeopleEdit.Name(a, "Uno"))
    tablet.publisher.flush(FlushReason.UNIT_FINISHED)
    val other = FaceGeneration.fromBytes(ByteArray(16) { 9 })
    val flipping = object : dev.pampa.mycloudgallery.core.faces.contract.FacesRemote by phone.remote {
      override suspend fun readSegment(generation: FaceGeneration, deviceId: String, hash: String, maxBytes: Int) =
        phone.remote.readSegment(generation, deviceId, hash, maxBytes).also { phone.switch.state.value = FacesState.On(other, HereState.ACTIVE) }
    }
    val sync = NasFacesSync(phone.identity, phone.switch, flipping, phone.archives, phone.replicaStore, phone.gate, world.clock)
    assertThat(sync.sync(kotlin.time.Duration.parse("1h")).stop).isEqualTo(FacesSyncStop.NOT_FRESH)
    assertThat(phone.archive.people()).isEmpty()
    assertThat(phone.archive.winners(listOf(FacesSyncWorld.key(1)))).isEmpty()
  }
}
