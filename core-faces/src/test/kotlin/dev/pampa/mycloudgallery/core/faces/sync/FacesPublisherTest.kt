package dev.pampa.mycloudgallery.core.faces.sync

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublishOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublishRules
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteBlock
import dev.pampa.mycloudgallery.core.faces.contract.FlushReason
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.data.FacesDocs
import dev.pampa.mycloudgallery.core.trust.data.FacesHeadLocation
import dev.pampa.mycloudgallery.core.trust.data.FacesSegmentContent
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import java.util.Random
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Il pubblicatore dei dati dei volti (Fase 8, P3, "Fatto quando": scrive solo sotto `faces/<G>/<io>/` dell'area privata, solo con
 * `publishExclusive`, mai nell'area comune (proprieta'); niente si pubblica senza lo stato fresco; la recinzione delle share vere; §2.4
 * "Pubblicazione", D30, D34, D35, D36, Q5).
 */
open class FacesPublisherTest {

  /** Il mondo della prova: in memoria (P3). La versione sul database dei volti (P6) lo sostituisce con lo stesso mondo sopra `RoomSyncStorage`. */
  protected open fun newWorld(): FacesSyncWorld = FacesSyncWorld()

  /** La testa piu' recente di [device] letta come la leggerebbe un altro dispositivo dell'account. */
  private fun latestHead(world: FacesSyncWorld, device: SyncDevice): dev.pampa.mycloudgallery.core.trust.data.FacesHead? {
    val versions = device.share.paths().filter { it.isWithin(NasLayout.facesHeadDir(world.generation, device.id)) }.mapNotNull { NasLayout.parseVersionName(it.name) }
    val version = versions.maxOrNull() ?: return null
    val bytes = (device.share.read(NasLayout.facesHead(world.generation, device.id, version), Int.MAX_VALUE) as NasRead.Data).bytes
    val verdict = FacesDocs.decodeHead(bytes, world.family.state, FacesHeadLocation(world.generation, device.id, version), "test", null)
    return (verdict as Verdict.Accepted).value.head
  }

  @Test
  fun pubblicaIlContributoIntero() = runTest {
    val world = newWorld()
    val phone = world.device("Telefono", "test")
    phone.compute(1)
    phone.compute(2, faces = 3)
    val a = FacesSyncWorld.person(1)
    phone.edit(PeopleEdit.Name(a, "Persona Uno"), PeopleEdit.Hidden(a, false))
    phone.snapshot(2, 1, mapOf(a to listOf(1 to 0, 2 to 0, 2 to 1)))
    val outcome = phone.publisher.flush(FlushReason.UNIT_FINISHED)
    assertThat(outcome).isInstanceOf(FacesPublishOutcome.Published::class.java)
    assertThat(phone.publisher.pending.value).isEqualTo(0)
    val head = latestHead(world, phone)!!
    assertThat(head.segments.map { it.cls }.toSet()).containsExactly(SegmentClass.FACES, SegmentClass.PEOPLE, SegmentClass.CLUSTER)
    assertThat(head.snapshot!!.seq).isEqualTo(2)
    // Gli stessi dati, ripubblicati, non fanno una testa nuova.
    world.clock.advanceMillis(FacesPublishRules.MIN_HEAD_INTERVAL_MS)
    assertThat(phone.publisher.flush(FlushReason.SYNC_ROUND)).isEqualTo(FacesPublishOutcome.NothingToPublish)
  }

  /** Proprieta': operazioni a caso di tre dispositivi di due account non scrivono mai fuori da `faces/<G>/<io>/`, mai nell'area comune. */
  @Test
  fun scriveSoloSottoLaPropriaCartellaMaiNellAreaComune() = runTest {
    val world = newWorld()
    val devices = listOf(world.device("Telefono", "test"), world.device("Tablet", "test"), world.device("Telefono di Test2", "test2"))
    val common = world.family.common.paths().toSet()
    val random = Random(8)
    repeat(300) { step ->
      val d = devices[random.nextInt(devices.size)]
      when (random.nextInt(7)) {
        0, 1 -> d.compute(random.nextInt(40), faces = 1 + random.nextInt(4), recipe = 1 + random.nextInt(2))
        2 -> d.edit(PeopleEdit.Name(FacesSyncWorld.person(random.nextInt(5)), "Nome ${random.nextInt(9)}"), dated = random.nextBoolean())
        3 -> d.publisher.flush(FlushReason.entries[random.nextInt(FlushReason.entries.size)])
        4 -> d.round()
        5 -> world.clock.advanceMillis(random.nextInt(400_000).toLong())
        else -> d.restart(loseLocal = random.nextInt(10) == 0)
      }
      for (device in devices) {
        for ((who, path) in device.written) {
          assertWithMessage("passo $step: ${device.name}").that(who).isEqualTo(device.id)
          assertWithMessage("passo $step: fuori da faces/<G>/<io>/").that(path.isWithin(NasLayout.facesDeviceDir(world.generation, device.id))).isTrue()
        }
      }
      assertWithMessage("passo $step: area comune").that(world.family.common.paths().toSet()).isEqualTo(common)
    }
    // Ogni scrittura e' passata da publishExclusive: la share in memoria non ha altro modo di scrivere, e nessun nome si e' riscritto.
    for (share in world.shares.values) assertThat(share.paths().size).isAtMost(share.writes.toSet().size)
    assertThat(devices.sumOf { it.written.size }).isGreaterThan(10)
  }

  /** L23, E-F42: una "area privata" che e' l'area comune non riceve niente, nemmeno una lettura. */
  @Test
  fun nellAreaComuneNiente() = runTest {
    val world = newWorld()
    val phone = world.device("Telefono", "test", share = "test-comune")
    phone.compute(1)
    assertThat(phone.publisher.flush(FlushReason.UNIT_FINISHED)).isEqualTo(FacesPublishOutcome.Blocked(FacesWriteBlock.NO_PRIVATE_AREA))
    assertThat(phone.round().stop).isEqualTo(dev.pampa.mycloudgallery.core.faces.contract.FacesSyncStop.UNAVAILABLE)
    assertThat(phone.written).isEmpty()
    assertThat(phone.share.writes).isEmpty()
    assertThat(phone.share.reads).isEmpty()
    assertThat(world.family.common.reads.none { it.isWithin(NasLayout.FACES_DIR) }).isTrue()
  }

  /** D30: niente si pubblica senza lo stato fresco che dice `On(G)` qui; nemmeno in attesa dell'avviso, esclusi o in cancellazione. */
  @Test
  fun nienteSenzaLoStatoFresco() = runTest {
    val world = newWorld()
    val phone = world.device("Telefono", "test")
    phone.compute(1)
    phone.switch.fresh.value = false
    assertThat(phone.publisher.flush(FlushReason.UNIT_FINISHED)).isEqualTo(FacesPublishOutcome.NotFresh)
    phone.switch.fresh.value = true
    for (state in listOf(
      FacesState.AwaitingNotice(world.generation, 1),
      FacesState.On(world.generation, HereState.EXCLUDED),
      FacesState.Erasing(world.generation),
      FacesState.Off,
      FacesState.NeedsUpdate,
    )) {
      phone.switch.state.value = state
      assertWithMessage(state.toString()).that(phone.publisher.flush(FlushReason.UNIT_FINISHED)).isEqualTo(FacesPublishOutcome.NotFresh)
    }
    assertThat(phone.written).isEmpty()
    assertThat(phone.share.reads).isEmpty()
    phone.switch.state.value = FacesState.On(world.generation, HereState.ACTIVE)
    assertThat(phone.publisher.flush(FlushReason.UNIT_FINISHED)).isInstanceOf(FacesPublishOutcome.Published::class.java)
  }

  /** "Un test per pacchetto con la generazione che cambia a meta'" (§4.3): i segmenti scritti restano orfani, la testa no. */
  @Test
  fun laGenerazioneCheCambiaAMetaNonPubblicaLaTesta() = runTest {
    val world = newWorld()
    val phone = world.device("Telefono", "test")
    phone.compute(1)
    phone.edit(PeopleEdit.Name(FacesSyncWorld.person(1), "Uno"))
    val other = FaceGeneration.fromBytes(ByteArray(16) { 77 })
    val flipping = object : dev.pampa.mycloudgallery.core.faces.contract.FacesRemote by phone.remote {
      override suspend fun publishSegment(generation: FaceGeneration, hash: String, bytes: ByteArray) =
        phone.remote.publishSegment(generation, hash, bytes).also { phone.switch.state.value = FacesState.On(other, HereState.ACTIVE) }
    }
    val publisher = NasFacesPublisher(phone.identity, phone.switch, flipping, phone.archives, phone.publishStore, phone.attestation, phone.gate, world.clock)
    assertThat(publisher.flush(FlushReason.UNIT_FINISHED)).isEqualTo(FacesPublishOutcome.NotFresh)
    assertThat(phone.written.none { it.second.isWithin(NasLayout.facesHeadDir(world.generation, phone.id)) }).isTrue()
    assertThat(phone.written).isNotEmpty()
  }

  /** D34, Q5: sulle share vere niente sul NAS (ne' letture ne' scritture) finche' l'utente non dice di si'; sulle share di prova si'. */
  @Test
  fun recinzioneDelleShareVere() = runTest {
    val world = newWorld()
    val real = world.device("Telefono", "mamma", share = "foto-mamma")
    real.compute(1)
    assertThat(real.publisher.flush(FlushReason.UNIT_FINISHED)).isEqualTo(FacesPublishOutcome.Blocked(FacesWriteBlock.TEST_SHARES_ONLY))
    assertThat(real.round().stop).isEqualTo(dev.pampa.mycloudgallery.core.faces.contract.FacesSyncStop.LOCAL_ONLY)
    assertThat(real.share.writes).isEmpty()
    assertThat(real.share.reads).isEmpty()
    // Una share di famiglia resta vera anche se il nome somiglia a una di prova.
    val lookalike = world.device("Tablet", "test", share = "test ")
    lookalike.compute(1)
    assertThat(lookalike.publisher.flush(FlushReason.UNIT_FINISHED)).isEqualTo(FacesPublishOutcome.Blocked(FacesWriteBlock.TEST_SHARES_ONLY))
    // Sulla share di prova si pubblica; con l'interruttore in "solo locale" no.
    val test = world.device("Telefono di prova", "test")
    test.compute(1)
    test.switch.state.value = FacesState.On(world.generation, HereState.LOCAL_ONLY)
    assertThat(test.publisher.flush(FlushReason.UNIT_FINISHED)).isEqualTo(FacesPublishOutcome.Blocked(FacesWriteBlock.TEST_SHARES_ONLY))
    test.switch.state.value = FacesState.On(world.generation, HereState.ACTIVE)
    assertThat(test.publisher.flush(FlushReason.UNIT_FINISHED)).isInstanceOf(FacesPublishOutcome.Published::class.java)
  }

  /** D35: i cancelli delle scritture (congelamento, emergenza, salto dell'ora, fiducia, sola lettura) e il dispositivo non piu' approvato. */
  @Test
  fun cancelli() = runTest {
    val world = newWorld()
    val phone = world.device("Telefono", "test")
    phone.compute(1)
    for (block in listOf(FacesWriteBlock.FROZEN, FacesWriteBlock.EMERGENCY_STOP, FacesWriteBlock.CLOCK_JUMP, FacesWriteBlock.READ_ONLY)) {
      phone.block = block
      assertThat(phone.publisher.flush(FlushReason.UNIT_FINISHED)).isEqualTo(FacesPublishOutcome.Blocked(block))
    }
    phone.block = null
    world.family.changeStatus(phone.signer, DeviceStatus.RETIRED)
    assertThat(phone.publisher.flush(FlushReason.UNIT_FINISHED)).isEqualTo(FacesPublishOutcome.Blocked(FacesWriteBlock.NOT_TRUSTED))
    assertThat(phone.written).isEmpty()
  }

  /** Una testa ogni 5 minuti d'ora del NAS salvo la fine di un'unita' o della manutenzione; l'accorpamento di 30 s. */
  @Test
  fun ritmo() = runTest {
    val world = newWorld()
    val phone = world.device("Telefono", "test")
    phone.compute(1)
    assertThat(phone.publisher.flush(FlushReason.SYNC_ROUND)).isInstanceOf(FacesPublishOutcome.Published::class.java)
    phone.compute(2)
    assertThat(phone.publisher.flush(FlushReason.DEBOUNCE)).isEqualTo(FacesPublishOutcome.TooSoon(FacesPublishRules.DEBOUNCE_MS))
    world.clock.advanceMillis(60_000)
    assertThat(phone.publisher.flush(FlushReason.SYNC_ROUND)).isEqualTo(FacesPublishOutcome.TooSoon(FacesPublishRules.MIN_HEAD_INTERVAL_MS - 60_000))
    assertThat(phone.publisher.flush(FlushReason.UNIT_FINISHED)).isInstanceOf(FacesPublishOutcome.Published::class.java)
    world.clock.advanceMillis(FacesPublishRules.MIN_HEAD_INTERVAL_MS)
    assertThat(phone.publisher.flush(FlushReason.SYNC_ROUND)).isEqualTo(FacesPublishOutcome.NothingToPublish)
  }

  /** "M3 dei volti": solo le chiavi attestate; tutti i propri corpi, vincenti o no. */
  @Test
  fun soloICorpiAttestati() = runTest {
    val world = newWorld()
    val phone = world.device("Telefono", "test")
    phone.compute(1)
    phone.compute(2)
    phone.compute(3, recipe = 1)
    phone.attested = setOf(FacesSyncWorld.key(1), FacesSyncWorld.key(3))
    // Il contenuto 3 ha un corpo migliore (ricetta piu' alta) di un altro dispositivo: vince quello.
    val tablet = world.device("Tablet", "test")
    phone.archive.saveBody(FacesWire.build(FacesSyncWorld.key(3), dev.pampa.mycloudgallery.core.model.FaceSpaceCode.CURRENT,
      dev.pampa.mycloudgallery.core.model.FaceDetectorCode.YUNET_2023MAR, 2, dev.pampa.mycloudgallery.core.model.FaceSource.ANALYSIS, tablet.id,
      listOf(FacesSyncWorld.entry(0)))!!)
    phone.publisher.flush(FlushReason.UNIT_FINISHED)
    val head = latestHead(world, phone)!!
    val keys = head.segments.filter { it.cls == SegmentClass.FACES }.flatMap { s ->
      val bytes = (phone.share.read(NasLayout.facesSegment(world.generation, phone.id, s.ref.hash), Int.MAX_VALUE) as NasRead.Data).bytes
      ((FacesDocs.decodeSegment(bytes, s.ref, s.cls) as Verdict.Accepted).value as FacesSegmentContent.Bodies).entries.map { it.contentKey }
    }
    // Il contenuto 3 si pubblica anche se qui vince il corpo del tablet: il contributo di un dispositivo non si restringe mai.
    assertThat(keys).containsExactly(FacesSyncWorld.key(1), FacesSyncWorld.key(3))
  }

  /** D36: una scelta fatta senza l'ora del NAS prende l'ora alla pubblicazione e da li' vale l'ultima scrittura. */
  @Test
  fun leVociDaDatarePrendonoLOraAllaPubblicazione() = runTest {
    val world = newWorld()
    val phone = world.device("Telefono", "test")
    val a = FacesSyncWorld.person(1)
    phone.edit(PeopleEdit.Name(a, "Uno"), dated = false)
    assertThat(phone.archive.people().values.single().atNas).isNull()
    phone.publisher.flush(FlushReason.UNIT_FINISHED)
    assertThat(phone.archive.people().values.single().atNas).isEqualTo(world.clock.nasNow)
  }

  /**
   * La richiesta di P5: un'istantanea messa in coda dal custode si pubblica anche se intanto la prenotazione `faces/<G>/cluster` e' passata
   * a un altro (il custode che perde pubblica comunque cio' che ha calcolato; vince la seq piu' alta, D21, E-F26).
   */
  @Test
  fun lIstantaneaInCodaSiPubblicaAncheDopoLaPrenotazionePersa() = runTest {
    val world = newWorld()
    val phone = world.device("Telefono", "test")
    val tablet = world.device("Tablet", "test")
    val phoneLeases = NasFacesLeases(phone.identity, phone.switch, phone.share, world.clock, phone.gate, world.flags)
    val tabletLeases = NasFacesLeases(tablet.identity, tablet.switch, tablet.share, world.clock, tablet.gate, world.flags)
    assertThat(phoneLeases.acquire(world.generation, dev.pampa.mycloudgallery.core.faces.contract.FacesResource.Cluster))
      .isInstanceOf(dev.pampa.mycloudgallery.core.faces.contract.FacesLease.Held::class.java)
    phone.compute(1)
    phone.snapshot(2, 1, mapOf(FacesSyncWorld.person(1) to listOf(1 to 0, 1 to 1)))
    // La pubblicazione non riesce (NAS giu'), poi la prenotazione scade e la prende il tablet.
    phone.share.failNext = 1
    assertThat(phone.publisher.flush(FlushReason.UNIT_FINISHED)).isEqualTo(FacesPublishOutcome.Failed)
    world.clock.advanceMillis(dev.pampa.mycloudgallery.core.trust.TrustLimits.MAX_LEASE_MILLIS + 1)
    assertThat(tabletLeases.acquire(world.generation, dev.pampa.mycloudgallery.core.faces.contract.FacesResource.Cluster))
      .isInstanceOf(dev.pampa.mycloudgallery.core.faces.contract.FacesLease.Held::class.java)
    assertThat(phone.publisher.flush(FlushReason.SYNC_ROUND)).isInstanceOf(FacesPublishOutcome.Published::class.java)
    assertThat(latestHead(world, phone)!!.snapshot!!.seq).isEqualTo(2)
  }
}
