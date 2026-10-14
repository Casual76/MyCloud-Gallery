package dev.pampa.mycloudgallery.core.index

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.index.contract.BlobScope
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.MetaChange
import dev.pampa.mycloudgallery.core.index.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.index.contract.WorkPass
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.index.p3.SimSource
import dev.pampa.mycloudgallery.core.index.p3.TestWorld
import dev.pampa.mycloudgallery.core.index.p3.simMedia
import dev.pampa.mycloudgallery.core.index.publish.IndexWriteRules
import dev.pampa.mycloudgallery.core.index.publish.OutboxIndexPublisher
import dev.pampa.mycloudgallery.core.index.publish.ShareLocks
import dev.pampa.mycloudgallery.core.index.replica.BlobIndex
import dev.pampa.mycloudgallery.core.index.replica.LocalReplica
import dev.pampa.mycloudgallery.core.index.replica.pack.PackDirectory
import dev.pampa.mycloudgallery.core.index.replica.store.BlobRow
import dev.pampa.mycloudgallery.core.index.replica.store.MemoryReplicaCatalog
import dev.pampa.mycloudgallery.core.index.replica.store.MemoryReplicaStore
import dev.pampa.mycloudgallery.core.index.store.InMemoryIndexPublishStore
import dev.pampa.mycloudgallery.core.index.store.InMemoryOutboxBlobStore
import dev.pampa.mycloudgallery.core.index.sync.Fx
import dev.pampa.mycloudgallery.core.index.sync.Harness
import dev.pampa.mycloudgallery.core.index.testing.FakeUnitPriority
import dev.pampa.mycloudgallery.core.index.testing.InMemoryRemoteIndex
import dev.pampa.mycloudgallery.core.index.testing.MapWriteGate
import dev.pampa.mycloudgallery.core.index.work.LeasedWorkUnits
import dev.pampa.mycloudgallery.core.index.work.RemotePublishedClasses
import dev.pampa.mycloudgallery.core.index.work.TrustWorkLeases
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.data.PrepFailure
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Le correzioni della revisione di sicurezza della Fase 4A (`docs/review/4a-fase4a.md`) che `SecurityReview4aTest` non
 * copre da solo: R1 (share e chiave intera, attestazione), R2 (niente outbox per le share non pubblicabili, tetto, pulizia),
 * R5 (una famiglia di decoder dichiarata fallita da un altro non ferma chi prepara).
 */
class SecurityFixes4aTest {

  @get:Rule val tmp = TemporaryFolder()

  // --- R1 ------------------------------------------------------------------------------------------------------------

  @Test
  fun r1_conLaShareDelMediaVinceIlBlobDellaSuaAreaAncheControUnaChiaveInteraUgualeInUnAltraShare() = runTest {
    val nas = InMemoryRemoteIndex(nasNow = { Fx.NOW })
    val victim = Fx.file(7, prepared = setOf(SegmentClass.THUMB_S))
    val key = victim.contentKey
    val honest = "MINIATURA VERA".toByteArray()
    nas.publish("test", "mmmmmmmmmmmmmmmmmmmmmmmmmm", listOf(Fx.meta(Fx.put("Foto/7.jpg", 1, victim)), Fx.images(SegmentClass.THUMB_S, key to honest)))

    // L'attaccante conosce la chiave intera (ha una copia degli stessi byte) e, con un id minore, pubblica nell'area comune
    // un record per un percorso inventato con lo stesso `qh1` e la stessa dimensione: il suo blob e' "attestato" li'.
    val evil = "IMMAGINE DELL'ATTACCANTE".toByteArray()
    nas.publish("test-comune", "aaaaaaaaaaaaaaaaaaaaaaaaaa", listOf(Fx.meta(Fx.put("Falso/7.jpg", 1, victim)), Fx.images(SegmentClass.THUMB_S, key to evil)))

    val reader = Harness("zzzzzzzzzzzzzzzzzzzzzzzzzz", tmp.root, nas)
    reader.sync("test", "test-comune")

    // Senza sapere dove sta il media vince l'ordine D6 (id minore): e' il limite L27 per la stessa chiave intera.
    assertThat(String(reader.blob(key, SegmentClass.THUMB_S)!!)).isEqualTo(String(evil))
    // Con la share del media (la sua area per prima) e la chiave intera dal catalogo: la miniatura vera.
    val scoped = reader.replica.locate(key.short, SegmentClass.THUMB_S, BlobScope(listOf("TEST"), key))!!
    assertThat(String(reader.bytes(scoped))).isEqualTo(String(honest))
    // Una chiave intera diversa con gli stessi primi 64 bit non trova niente, nemmeno nella share giusta.
    val forged = ContentKey(key.hex.take(16) + "0".repeat(48))
    assertThat(reader.replica.locate(key.short, SegmentClass.THUMB_S, BlobScope(listOf("test"), forged))).isNull()
  }

  @Test
  fun r1_unBlobSenzaUnRecordDelSuoDispositivoNellaSuaShareNonSiConsegnaMaiNemmenoDopoLaRiapertura() = runTest {
    val nas = InMemoryRemoteIndex(nasNow = { Fx.NOW })
    val file = Fx.file(3)
    // Solo il blob, senza record: la chiave e' giusta ma nessun record del dispositivo la attesta.
    nas.publish("test-comune", "aaaaaaaaaaaaaaaaaaaaaaaaaa", listOf(Fx.images(SegmentClass.THUMB_S, file.contentKey to "senza record".toByteArray())))
    val reader = Harness("zzzzzzzzzzzzzzzzzzzzzzzzzz", tmp.root, nas)
    reader.sync("test-comune")
    assertThat(reader.blob(file.contentKey, SegmentClass.THUMB_S)).isNull()
    assertThat(reader.replica.chosenBlobs()).isEmpty()

    // Il record arriva con una testa nuova: il blob gia' scaricato diventa valido (si riattesta, nessun nuovo download).
    nas.publish("test-comune", "aaaaaaaaaaaaaaaaaaaaaaaaaa", listOf(Fx.meta(Fx.put("Foto/3.jpg", 1, file))))
    reader.sync("test-comune")
    assertThat(String(reader.blob(file.contentKey, SegmentClass.THUMB_S)!!)).isEqualTo("senza record")

    // Una replica riaperta (processo nuovo) attesta di nuovo dai record salvati.
    val again = LocalReplica(reader.store, reader.packs, MemoryReplicaCatalog(), reader.own)
    again.open()
    assertThat(again.locate(file.contentKey.short, SegmentClass.THUMB_S)).isNotNull()
  }

  @Test
  fun r1_lIndiceDeiBlobRiattestaQuandoIRecordCambiano() {
    val index = BlobIndex()
    val key = Fx.key(1)
    val row = BlobRow(key, SegmentClass.THUMB_S, packId = 1, offset = 0, length = 10, deviceId = "d", segment = "s")
    index.add(listOf(row), { "test" }, { false })
    assertThat(index.locate(key.short, SegmentClass.THUMB_S)).isNull()
    assertThat(index.reattest("test", "d", setOf(key))).isEqualTo(1)
    assertThat(index.locate(key.short, SegmentClass.THUMB_S)).isNotNull()
    assertThat(index.locate(key.short, SegmentClass.THUMB_S, BlobScope(listOf("altra")))).isNull()
    index.reattest("test", "d", emptySet())
    assertThat(index.locate(key.short, SegmentClass.THUMB_S)).isNull()
  }

  // --- R2 ------------------------------------------------------------------------------------------------------------

  private fun localReplica(name: String, id: String) =
    LocalReplica(MemoryReplicaStore(), PackDirectory(File(tmp.root, name)), MemoryReplicaCatalog(), id)

  private fun publisherOf(
    world: TestWorld,
    device: dev.pampa.mycloudgallery.core.index.p3.TestDevice,
    store: InMemoryIndexPublishStore,
    blobs: InMemoryOutboxBlobStore,
    sink: LocalReplica,
    gate: MapWriteGate = MapWriteGate(),
    flags: FeatureFlags = FeatureFlags.RELEASE,
    maxOutboxBlobBytes: Long = OutboxIndexPublisher.DEFAULT_MAX_OUTBOX_BLOB_BYTES,
  ) = OutboxIndexPublisher(
    identity = device.identity,
    clock = world.clock,
    fileSystem = { world.nas.share(it) },
    store = store,
    blobStore = blobs,
    catalog = world.catalog,
    rules = IndexWriteRules(flags, gate),
    sink = sink,
    locks = ShareLocks(),
    io = Dispatchers.Unconfined,
    maxOutboxBlobBytes = maxOutboxBlobBytes,
  )

  @Test
  fun r2_suUnaShareNonPubblicabileNienteVaNellOutboxMaLaLibreriaHaLeMiniature() = runTest {
    val world = TestWorld()
    val phone = world.device()
    val real = "alessio"
    val files = (1..3).map { world.file(real, "Foto/$it.jpg") }
    val store = InMemoryIndexPublishStore()
    val blobs = InMemoryOutboxBlobStore()
    val replica = localReplica("r2a", phone.id)
    val publisher = publisherOf(world, phone, store, blobs, replica)
    val all = setOf(SegmentClass.THUMB_S, SegmentClass.THUMB_M, SegmentClass.ANALYSIS, SegmentClass.HQ)

    for (f in files) publisher.stage(real, listOf(f.put(all)), all.map { f.thumb(it) })

    // D31: niente righe ne' byte nell'outbox, niente da pubblicare.
    assertThat(store.outbox(real).isEmpty).isTrue()
    assertThat(blobs.handles()).isEmpty()
    assertThat(publisher.pending.value.isEmpty).isTrue()
    assertThat(publisher.flush(real, FlushReason.UNIT_FINISHED)).isEqualTo(PublishOutcome.NothingPending)
    assertThat(publisher.stats.localOnly.get()).isGreaterThan(0)
    // La replica locale ha le miniature del livello (S e M), mai l'anteprima di analisi ne' l'HQ a Standard.
    for (f in files) {
      assertThat(replica.locate(f.contentKey.short, SegmentClass.THUMB_S)).isNotNull()
      assertThat(replica.locate(f.contentKey.short, SegmentClass.THUMB_M)).isNotNull()
      assertThat(replica.locate(f.contentKey.short, SegmentClass.ANALYSIS)).isNull()
      assertThat(replica.locate(f.contentKey.short, SegmentClass.HQ)).isNull()
    }
    // I record locali hanno `seq` della share, crescenti (una modifica dopo vince).
    val matched = replica.matching(real, files.associate { it.path to it.stamp })
    assertThat(matched.values.map { it.seq }.sorted()).containsExactly(1L, 2L, 3L).inOrder()
    publisher.stage(real, listOf(files[0].put(setOf(SegmentClass.THUMB_S))), emptyList())
    assertThat(replica.matching(real, mapOf(files[0].path to files[0].stamp)).values.single().seq).isEqualTo(4L)

    // Anche un'area in sola lettura per questo account (E-I19) e' un blocco permanente.
    val share = "test"
    val f = world.file(share, "Foto/ro.jpg")
    val gate = MapWriteGate().apply { block(share, WriteBlock.READ_ONLY_AREA) }
    val readOnly = publisherOf(world, phone, store, blobs, replica, gate = gate)
    readOnly.stage(share, listOf(f.put(setOf(SegmentClass.THUMB_S))), listOf(f.thumb()))
    assertThat(store.outbox(share).isEmpty).isTrue()
  }

  @Test
  fun r2_conUnBloccoTemporaneoLOutboxHaUnTettoEOltreLeImmaginiRestanoSoloNellaReplica() = runTest {
    val world = TestWorld()
    val phone = world.device()
    val share = "test"
    val files = (1..3).map { world.file(share, "Foto/$it.jpg") }
    val store = InMemoryIndexPublishStore()
    val blobs = InMemoryOutboxBlobStore()
    val replica = localReplica("r2b", phone.id)
    val gate = MapWriteGate().apply { block(share, WriteBlock.FROZEN) }
    val oneThumb = files[0].thumb().bytes.size.toLong()
    val publisher = publisherOf(world, phone, store, blobs, replica, gate = gate, maxOutboxBlobBytes = oneThumb)

    for (f in files) publisher.stage(share, listOf(f.put(setOf(SegmentClass.THUMB_S))), listOf(f.thumb()))

    val outbox = store.outbox(share)
    assertThat(outbox.records).hasSize(3)
    assertThat(outbox.blobs).hasSize(1)
    assertThat(publisher.pending.value.bytes).isAtMost(oneThumb)
    assertThat(publisher.stats.overCap.get()).isEqualTo(2)
    for (f in files) assertThat(replica.locate(f.contentKey.short, SegmentClass.THUMB_S)).isNotNull()
  }

  @Test
  fun r2_lOutboxDelleShareNonPiuConfigurateONonPubblicabiliSiToglie() = runTest {
    val world = TestWorld()
    val phone = world.device()
    val store = InMemoryIndexPublishStore()
    val blobs = InMemoryOutboxBlobStore()
    val replica = localReplica("r2c", phone.id)
    val gate = MapWriteGate().apply {
      block("test", WriteBlock.FROZEN)
      block("test2", WriteBlock.FROZEN)
    }
    val publisher = publisherOf(world, phone, store, blobs, replica, gate = gate)
    val a = world.file("test", "Foto/a.jpg")
    val b = world.file("test2", "Foto/b.jpg")
    publisher.stage("test", listOf(a.put(setOf(SegmentClass.THUMB_S))), listOf(a.thumb()))
    publisher.stage("test2", listOf(b.put(setOf(SegmentClass.THUMB_S))), listOf(b.thumb()))
    assertThat(blobs.handles()).hasSize(2)

    // L'area `test` non e' piu' configurata: la sua outbox (righe e byte) se ne va; quella di `test2` resta.
    val removed = publisher.purgeOutbox(setOf("TEST2"))
    assertThat(removed).isEqualTo(2)
    assertThat(store.outbox("test").isEmpty).isTrue()
    assertThat(store.outbox("test2").isEmpty).isFalse()
    assertThat(blobs.handles()).hasSize(1)
    // La replica locale non perde le miniature (servono ancora alla Libreria finche' la share e' nel catalogo).
    assertThat(replica.locate(a.contentKey.short, SegmentClass.THUMB_S)).isNotNull()
  }

  // --- R5 ------------------------------------------------------------------------------------------------------------

  @Test
  fun r5_unaFamigliaDiDecoderDichiarataFallitaDaUnAltroDispositivoNonFermaChiPrepara() = runTest {
    val world = TestWorld()
    val share = "test-comune"
    val honest = world.device("Telefono onesto", "test")
    val attacker = world.device("Tablet compromesso", "test2")
    val media = simMedia(world, share, listOf("2026-09"), perMonth = 5, random = Random(9))

    // Il dispositivo compromesso dichiara per ogni media "il decoder c2_onesto ha fallito".
    val publisher = attacker.publisher()
    val failed = media.map { m -> MetaChange.Put(m.file.path, m.file.meta().copy(failure = PrepFailure("DECODER_MISSING", "c2_onesto"))) }
    publisher.stage(share, failed, emptyList())
    publisher.flush(share, FlushReason.UNIT_FINISHED)

    // Senza prenotazioni (le tre prove chiedono la stessa unita'): conta solo cosa resta da fare e con quali decoder.
    honest.gate.block(share, WriteBlock.FROZEN)

    fun units(ownFamily: String?): LeasedWorkUnits {
      val remote = honest.remote()
      return LeasedWorkUnits(
        identity = honest.identity,
        source = SimSource(media, emptySet()),
        published = RemotePublishedClasses(remote, startRound = { remote.startRound() }),
        leases = TrustWorkLeases({ world.nas.share(it) }, honest.signer, world.clock, { world.trust.state }, Dispatchers.Unconfined),
        rules = IndexWriteRules(honest.flags, honest.gate),
        ownDecoderFamily = ownFamily,
      )
    }

    // Prima (senza sapere la propria famiglia) l'onesto avrebbe saltato tutti i media.
    val before = units(null).next(share, WorkPass.PREP, FakeUnitPriority())!!
    assertThat(before.items.all { "c2_onesto" in it.failedDecoders }).isTrue()
    // Ora la dichiarazione di un altro dispositivo sulla sua famiglia non vale: ci prova una volta da se'.
    val claim = units("c2_onesto").next(share, WorkPass.PREP, FakeUnitPriority())!!
    assertThat(claim.items).hasSize(media.size)
    assertThat(claim.items.none { "c2_onesto" in it.failedDecoders }).isTrue()
    // Una famiglia diversa dalla propria passa di mano come prima (D14).
    val other = units("c2_altro").next(share, WorkPass.PREP, FakeUnitPriority())!!
    assertThat(other.items.all { "c2_onesto" in it.failedDecoders }).isTrue()
  }
}
