package dev.pampa.mycloudgallery.core.index.p3

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.index.contract.BlobPut
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.MetaChange
import dev.pampa.mycloudgallery.core.index.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.index.contract.VideoFrame
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.index.publish.IndexProvenanceException
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/** Il pubblicatore (§2.1): segmenti e testa, regole prima di firmare, D5, E-I6, ripresa. */
open class PublisherTest {

  private val world = TestWorld()
  private val share = "test-comune"
  private val phone = world.device("Telefono")
  private val files = (1..6).map { world.file(share, "Foto/2024/IMG_$it.jpg") }

  private fun published(outcome: PublishOutcome) = outcome as PublishOutcome.Published

  @Test
  fun pubblicaSegmentiETesta_eChiLeggeVedeLoStessoStato() = runTest {
    val publisher = phone.publisher()
    publisher.stage(share, files.take(3).map { it.put(setOf(SegmentClass.THUMB_S)) }, files.take(3).map { it.thumb() })

    val outcome = published(publisher.flush(share, FlushReason.UNIT_FINISHED))
    val state = ReaderState.read(world, share, phone.id)

    assertThat(outcome.headVersion).isEqualTo(1)
    assertThat(outcome.segments).isEqualTo(2)
    assertThat(state.records.values.map { it.path }).containsExactlyElementsIn(files.take(3).map { it.path })
    assertThat(state.records.values.map { it.seq }).containsExactly(1L, 2L, 3L)
    assertThat(state.blobs.keys).containsExactlyElementsIn(files.take(3).map { SegmentClass.THUMB_S to it.contentKey })
    assertThat(publisher.pending.value.isEmpty).isTrue()
    // Chi prepara vede subito le proprie miniature nella replica locale (D31).
    assertThat(phone.replica.locate(files[0].contentKey.short, SegmentClass.THUMB_S)).isNotNull()
    // Niente da pubblicare: nessuna testa nuova.
    assertThat(publisher.flush(share, FlushReason.UNIT_FINISHED)).isEqualTo(PublishOutcome.NothingPending)
    assertThat(headVersions(world, share, phone.id)).containsExactly(1L)
  }

  @Test
  fun laTestaElencaTuttiISegmentiValidi_eIlSeqPiuAltoVince_D5() = runTest {
    val publisher = phone.publisher()
    publisher.stage(share, files.take(2).map { it.put() }, emptyList())
    publisher.flush(share, FlushReason.UNIT_FINISHED)
    publisher.stage(share, listOf(MetaChange.Remove(files[0].path), files[2].put()), emptyList())

    val second = published(publisher.flush(share, FlushReason.UNIT_FINISHED))
    val state = ReaderState.read(world, share, phone.id)

    assertThat(second.headVersion).isEqualTo(2)
    assertThat(state.segments).isEqualTo(2)
    assertThat(state.records.values.map { it.path }).containsExactly(files[1].path, files[2].path)
  }

  @Test
  fun d31_soloLeShareDiProva_ancheConIlCancelloAperto() = runTest {
    val real = "alessio"
    val file = world.file(real, "Foto/vera.jpg")
    val publisher = phone.publisher()
    publisher.stage(real, listOf(file.put()), listOf(file.thumb()))

    assertThat(publisher.flush(real, FlushReason.UNIT_FINISHED)).isEqualTo(PublishOutcome.Blocked(WriteBlock.LOCAL_ONLY))
    assertThat(world.nas.share(real).fileCount()).isEqualTo(0)
    assertThat(publisher.pending.value.records).isEqualTo(1)
    assertThat(phone.replica.locate(file.contentKey.short, SegmentClass.THUMB_S)).isNotNull()

    // In debug, con la protezione spenta dall'utente, la stessa outbox si pubblica (niente si e' perso).
    phone.flags = FeatureFlags.forBuild(debugBuild = true, indexPublishTestSharesOnly = false)
    assertThat(phone.publisher().flush(real, FlushReason.UNIT_FINISHED)).isInstanceOf(PublishOutcome.Published::class.java)
  }

  @Test
  fun ilCancelloEIlRegistroFermanoPrimaDiFirmare() = runTest {
    val publisher = phone.publisher()
    publisher.stage(share, listOf(files[0].put()), emptyList())
    for (block in listOf(WriteBlock.FROZEN, WriteBlock.CLOCK_JUMP, WriteBlock.READ_ONLY_AREA, WriteBlock.EMERGENCY_STOP)) {
      phone.gate.block(share, block)
      assertThat(publisher.flush(share, FlushReason.UNIT_FINISHED)).isEqualTo(PublishOutcome.Blocked(block))
    }
    phone.gate.block(share, null)
    world.trust.changeStatus(phone.signer, DeviceStatus.REVOKED)

    assertThat(publisher.flush(share, FlushReason.UNIT_FINISHED)).isEqualTo(PublishOutcome.Blocked(WriteBlock.NOT_TRUSTED))
    assertThat(headVersions(world, share, phone.id)).isEmpty()
    assertThat(publisher.pending.value.records).isEqualTo(1)
  }

  @Test
  fun unaTestaOgniCinqueMinuti_salvoLaFineDiUnUnita_M3() = runTest {
    val publisher = phone.publisher()
    publisher.stage(share, listOf(files[0].put()), emptyList())
    publisher.flush(share, FlushReason.PERIODIC)
    publisher.stage(share, listOf(files[1].put()), emptyList())

    val tooSoon = publisher.flush(share, FlushReason.PERIODIC)
    assertThat(tooSoon).isEqualTo(PublishOutcome.TooSoon(NAS_NOW + TrustLimits.HEAD_MIN_INTERVAL_MILLIS))
    assertThat(published(publisher.flush(share, FlushReason.UNIT_FINISHED)).headVersion).isEqualTo(2)

    publisher.stage(share, listOf(files[2].put()), emptyList())
    world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS)
    assertThat(published(publisher.flush(share, FlushReason.PERIODIC)).headVersion).isEqualTo(3)
  }

  @Test
  fun nasPieno_siPubblicaPerPriorita_eIlRestoAspetta_EI6() = runTest {
    val plan = FaultPlan()
    val publisher = phone.publisher(plan)
    val hq = files.take(2).map { it.thumb(SegmentClass.HQ) }
    val thumbs = files.take(2).map { it.thumb(SegmentClass.THUMB_S) }
    publisher.stage(share, files.take(2).map { it.put(setOf(SegmentClass.THUMB_S, SegmentClass.HQ)) }, hq + thumbs)
    var thumbSegments = 0
    plan.failWhen = { op, path ->
      // Il NAS si riempie dopo il segmento delle miniature S: meta e thumbS passano, hq no.
      if (op == "publish" && path.components.contains("seg") && ++thumbSegments > 2) NasError.NasFull else null
    }

    val partial = publisher.flush(share, FlushReason.UNIT_FINISHED) as PublishOutcome.Partial
    assertThat(partial.published).containsExactly(SegmentClass.META, SegmentClass.THUMB_S)
    assertThat(partial.failure).isEqualTo(RemoteFailure.DISK_FULL)
    assertThat(partial.remaining.blobs).isEqualTo(2)
    assertThat(ReaderState.read(world, share, phone.id).blobs.keys.map { it.first }.toSet()).containsExactly(SegmentClass.THUMB_S)

    plan.failWhen = null
    assertThat(published(publisher.flush(share, FlushReason.UNIT_FINISHED)).segments).isEqualTo(1)
    assertThat(ReaderState.read(world, share, phone.id).blobs.keys.map { it.first }.toSet()).containsExactly(SegmentClass.THUMB_S, SegmentClass.HQ)
  }

  @Test
  fun nasIrraggiungibile_niente_eLOutboxResta() = runTest {
    val plan = FaultPlan()
    val publisher = phone.publisher(plan)
    publisher.stage(share, listOf(files[0].put()), listOf(files[0].thumb()))
    plan.failWhen = { op, _ -> if (op == "publish") NasError.Timeout else null }

    assertThat(publisher.flush(share, FlushReason.UNIT_FINISHED)).isEqualTo(PublishOutcome.Failed(RemoteFailure.UNREACHABLE))
    assertThat(publisher.pending.value.records).isEqualTo(1)
    assertThat(headVersions(world, share, phone.id)).isEmpty()
    plan.failWhen = null
    assertThat(published(publisher.flush(share, FlushReason.UNIT_FINISHED)).headVersion).isEqualTo(1)
  }

  @Test
  fun spazzaturaAlNomeDellaTesta_siScavalca() = runTest {
    world.nas.share(share).put(NasLayout.headFile(phone.id, "index", 1), "spazzatura".toByteArray())
    val publisher = phone.publisher()
    publisher.stage(share, listOf(files[0].put()), emptyList())

    assertThat(published(publisher.flush(share, FlushReason.UNIT_FINISHED)).headVersion).isEqualTo(2)
    assertThat(publisher.stats.junkSkipped.get()).isEqualTo(1)
    assertThat(ReaderState.read(world, share, phone.id).headVersion).isEqualTo(2)
  }

  @Test
  fun ciòCheChiLeggeRifiuterebbe_nonEntraNellOutbox() = runTest {
    val publisher = phone.publisher()
    val png = BlobPut.Image(SegmentClass.THUMB_S, files[0].contentKey, byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(30))
    val huge = BlobPut.Image(SegmentClass.HQ, files[1].contentKey, Images.jpeg(20_000, 20_000))
    val frames = BlobPut.Frames(files[2].contentKey, listOf(VideoFrame(0, Images.jpeg()), VideoFrame(30_000, Images.jpeg(marker = Random(1).nextBytes(8)))))
    publisher.stage(share, files.take(3).map { it.put() }, listOf(png, huge, frames))

    assertThat(publisher.stats.unpublishable.get()).isEqualTo(2)
    published(publisher.flush(share, FlushReason.UNIT_FINISHED))
    assertThat(ReaderState.read(world, share, phone.id).blobs.keys).containsExactly(SegmentClass.FRAMES to files[2].contentKey)
  }

  @Test
  fun regolaM3_percorsiEBlobDiUnAltraShareRifiutati_lapidiInutiliScartate() = runTest {
    val publisher = phone.publisher()
    val private = world.file("test", "Privato/segreto.jpg")

    assertThrows(IndexProvenanceException::class.java) { kotlinx.coroutines.runBlocking { publisher.stage(share, listOf(private.put()), emptyList()) } }
    // Una lapide per un percorso mai pubblicato qui non cambia lo stato e non deve portare il nome nell'indice: si scarta.
    publisher.stage(share, listOf(MetaChange.Remove(private.path)), emptyList())
    assertThat(publisher.stats.pointlessRemoves.get()).isEqualTo(1)
    assertThrows(IndexProvenanceException::class.java) {
      kotlinx.coroutines.runBlocking { publisher.stage(share, listOf(files[0].put()), listOf(private.thumb())) }
    }
    assertThat(publisher.pending.value.isEmpty).isTrue()
    assertThat(phone.blobs.handles()).isEmpty()

    // Un timbro cambiato dopo la lettura (il file e' stato riscritto dal PC) non e' una violazione: si scarta.
    world.catalog.put(share, files[1].path, files[1].stamp.copy(modifiedAtMillis = NAS_NOW), files[1].meta().quickHash)
    publisher.stage(share, listOf(files[1].put(), files[2].put()), listOf(files[1].thumb(), files[2].thumb()))
    assertThat(publisher.stats.staleChanges.get()).isEqualTo(1)
    assertThat(publisher.pending.value.records).isEqualTo(1)
    assertThat(publisher.pending.value.blobs).isEqualTo(1)
  }

  @Test
  fun ripresa_byteOrfaniTolti_eRigheRipassateAllaReplica() = runTest {
    val plan = FaultPlan()
    val dying = phone.publisher(plan)
    // Passi di stage: byte, byte, righe, replica, "passate alla replica". Morte subito dopo le righe: da ripassare.
    plan.afterEffect = true
    plan.dieAt = 3
    runCatching { dying.stage(share, listOf(files[0].put(), files[1].put()), listOf(files[0].thumb(), files[1].thumb())) }
    assertThat(plan.died).isTrue()
    assertThat(phone.replica.locate(files[0].contentKey.short, SegmentClass.THUMB_S)).isNull()
    // Morte a meta' di un altro stage: un byte orfano.
    plan.dieAt = plan.step + 2
    runCatching { dying.stage(share, listOf(files[2].put(), files[3].put()), listOf(files[2].thumb(), files[3].thumb())) }
    assertThat(phone.blobs.handles()).hasSize(4)

    val fresh = phone.publisher()
    fresh.recover()

    assertThat(phone.replica.locate(files[0].contentKey.short, SegmentClass.THUMB_S)).isNotNull()
    assertThat(phone.blobs.handles()).isEqualTo(phone.store.referencedHandles())
    assertThat(phone.blobs.handles()).hasSize(2)
    assertThat(fresh.pending.value.records).isEqualTo(2)
  }
}
