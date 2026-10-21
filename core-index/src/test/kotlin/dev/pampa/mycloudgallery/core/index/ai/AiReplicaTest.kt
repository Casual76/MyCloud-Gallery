package dev.pampa.mycloudgallery.core.index.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.MetaChange
import dev.pampa.mycloudgallery.core.index.contract.SyncScope
import dev.pampa.mycloudgallery.core.index.p3.TestFile
import dev.pampa.mycloudgallery.core.index.replica.store.SegmentState
import dev.pampa.mycloudgallery.core.index.sync.replicaDigest
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.data.AiBody
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.AiSegments
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.DeviceHead
import dev.pampa.mycloudgallery.core.trust.data.HeadKind
import dev.pampa.mycloudgallery.core.trust.data.HeadSeries
import dev.pampa.mycloudgallery.core.trust.data.IndexKeys
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentRef
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * La replica dei dati AI (D7; formato-nas.md §5.5-bis "Attestazione e fusione"): attestazione per share e per chiave intera, fusione
 * deterministica, revoche, segmenti ostili o assenti, compatibilita' con un lettore della versione 1, area persa, budget e ripresa.
 */
class AiReplicaTest {

  @get:Rule val tmp = TemporaryFolder()

  private val share = "test-comune"

  /** Un dispositivo compromesso (o difettoso) che pubblica nella sua testa `ai` cio' che vuole, con l'impronta giusta. */
  private suspend fun forge(ai: AiWorld, device: AiDevice, segments: List<Pair<ByteArray, ClassedSegment>>, after: Long = 0, share: String = this.share) {
    val fs = ai.world.nas.share(share)
    segments.forEach { (bytes, seg) -> fs.publishExclusive(NasLayout.aiSegment(device.id, seg.ref.hash), bytes) }
    val state = ai.world.trust.state
    HeadSeries.publish(fs, state, device.base.signer, HeadKind.AI, after, device.base.account) { v ->
      DeviceHead(state.registry.familyId, device.id, fs.shareName, HeadKind.AI, v, state.registry.version, ai.clock.nowMillis(), segments.map { it.second }.sortedBy { it.ref.hash })
        .sign(device.base.signer)
    }
  }

  /** Un segmento `ai` scritto a mano: classe, voci (chiave intera, byte del corpo) gia' in ordine. */
  private fun raw(cls: AiClass, entries: List<Pair<ContentKey, ByteArray>>): Pair<ByteArray, ClassedSegment> {
    val sorted = entries.sortedWith { a, b -> IndexKeys.compare(a.first.bytes(), b.first.bytes()) }
    val out = ByteArrayOutputStream()
    out.write("MCGB".toByteArray())
    out.write(2)
    out.write(byteArrayOf(cls.code.toByte(), 0, 0, 0))
    out.write(ByteBuffer.allocate(4).putInt(sorted.size).array())
    sorted.forEach { (k, b) -> out.write(k.bytes()); out.write(ByteBuffer.allocate(4).putInt(b.size).array()) }
    sorted.forEach { out.write(it.second) }
    val bytes = out.toByteArray()
    return bytes to ClassedSegment(cls.segmentClass, SegmentRef(Hashes.sha256Name(bytes), bytes.size.toLong()))
  }

  private fun encoded(cls: AiClass, entries: List<Pair<ContentKey, AiBody>>): Pair<ByteArray, ClassedSegment> =
    AiSegments.encode(cls, entries).let { it.bytes to it.classed }

  private suspend fun publish(device: AiDevice, entries: List<AiEntry>, share: String = this.share) {
    val publisher = device.publisher()
    publisher.stage(share, entries)
    check(publisher.flush(share, FlushReason.UNIT_FINISHED) is dev.pampa.mycloudgallery.core.index.contract.PublishOutcome.Published)
  }

  private fun files(ai: AiWorld, n: Int, prefix: String = "Foto", share: String = this.share): List<TestFile> =
    (1..n).map { ai.file(share, "$prefix/IMG_$it.jpg") }

  // --- Attestazione ------------------------------------------------------------------------------------------------------

  @Test
  fun attestazione_ilRecordDiUnAltroDispositivoBasta_ilCalcoloLoFaChiIndicizza() = runTest {
    val ai = AiWorld(tmp.root)
    val preparer = ai.device("Tablet")
    val indexer = ai.device("Telefono")
    val reader = ai.device("Telefono 2")
    val fs = files(ai, 4)
    preparer.prepare(fs)
    val entries = fs.map { indexer.vector(it) } + fs.map { indexer.text(it) }
    publish(indexer, entries) // l'indicizzatore non ha nessun record suo (D7)

    reader.sync()

    assertThat(reader.winners().keys).containsExactlyElementsIn(entries.map { Triple(share, it.contentKey, it.cls) })
    assertThat(reader.winners().values.map { it.first }.toSet()).containsExactly(indexer.id)
    // E l'indicizzatore stesso, che rilegge la propria testa, arriva allo stesso.
    indexer.sync()
    assertThat(indexer.winners()).isEqualTo(reader.winners())
  }

  @Test
  fun attestazione_senzaRecordLaVoceResta_inAttesa_eValeQuandoArrivaIlRecord_poiEsceConLaLapide() = runTest {
    val ai = AiWorld(tmp.root)
    val preparer = ai.device("Tablet")
    val indexer = ai.device("Telefono")
    val reader = ai.device("Telefono 2")
    val file = files(ai, 1).single()
    publish(indexer, listOf(indexer.vector(file)))
    reader.sync()
    assertThat(reader.winners()).isEmpty() // E-I21: nessun record presente, in attesa
    assertThat(reader.aiStore.keys(share)).hasSize(1)

    preparer.prepare(listOf(file))
    reader.sync() // riattestazione: la stessa voce, nessuna testa `ai` nuova
    assertThat(reader.winners().keys).containsExactly(Triple(share, file.contentKey, AiClass.VEC))

    // Il file sparisce (lapide del preparatore): la voce non ha piu' un vincitore ed esce dall'indice locale.
    val publisher = preparer.indexPublisher()
    publisher.stage(share, listOf(MetaChange.Remove(file.path)), emptyList())
    publisher.flush(share, FlushReason.UNIT_FINISHED)
    reader.sync()
    assertThat(reader.winners()).isEmpty()
  }

  @Test
  fun attestazione_unRecordDiUnAltraShareNonVale_eNemmenoUnaChiaveConGliStessiPrimi64Bit() = runTest {
    val ai = AiWorld(tmp.root)
    val preparer = ai.device("Tablet")
    val rogue = ai.device("Compromesso")
    val reader = ai.device("Telefono")
    val privateFile = files(ai, 1, prefix = "Privata", share = "test").single()
    val commonFile = files(ai, 1).single()
    preparer.prepare(listOf(privateFile), share = "test")
    preparer.prepare(listOf(commonFile))
    // Nell'area comune: una voce per la chiave del file privato (record solo nell'altra share) e una per una chiave con gli stessi
    // primi 64 bit di quella del file comune (record con la chiave intera diversa). Il pubblicatore vero non le scriverebbe (M3).
    val forged = ContentKey(commonFile.contentKey.hex.take(16) + "0".repeat(48))
    forge(ai, rogue, listOf(encoded(AiClass.VEC, listOf(privateFile.contentKey to AiBodies.vec(1), forged to AiBodies.vec(2)))))

    reader.sync("test-comune", "test")

    assertThat(reader.winners()).isEmpty()
    assertThat(reader.aiStore.keys(share).map { it.contentKey }).containsExactly(privateFile.contentKey, forged)
    // Con il record giusto nella share giusta la stessa voce vale (controprova).
    publish(rogue, listOf(rogue.vector(commonFile)))
    reader.sync("test-comune", "test")
    assertThat(reader.winners().keys).containsExactly(Triple(share, commonFile.contentKey, AiClass.VEC))
  }

  // --- Fusione -----------------------------------------------------------------------------------------------------------

  @Test
  fun fusione_ricettaPiuAlta_poiIdMinore_ugualePerTuttiInQualunqueOrdine() = runTest {
    val ai = AiWorld(tmp.root)
    val preparer = ai.device("Tablet")
    val (low, high) = listOf(ai.device("A"), ai.device("B")).sortedBy { it.id }
    val fs = files(ai, 3)
    preparer.prepare(fs)
    // file 0: stessa ricetta → vince l'id minore; file 1: il maggiore ha la ricetta 2 → vince lui; file 2: solo il maggiore.
    publish(low, listOf(low.vector(fs[0]), low.vector(fs[1], recipe = 1)))
    publish(high, listOf(high.vector(fs[0], seed = 99), high.vector(fs[1], recipe = 2), high.vector(fs[2])))

    val r1 = ai.device("Lettore 1")
    val r2 = ai.device("Lettore 2")
    r1.sync()
    r2.sync()
    val winners = r1.winners()
    assertThat(winners[Triple(share, fs[0].contentKey, AiClass.VEC)]!!.first).isEqualTo(low.id)
    assertThat(winners[Triple(share, fs[1].contentKey, AiClass.VEC)]).isEqualTo(Triple(high.id, 2, AiSegments.digest(AiSegments.encodeBody(AiBodies.vec(fs[1].path.hashCode(), 2)))))
    assertThat(winners[Triple(share, fs[2].contentKey, AiClass.VEC)]!!.first).isEqualTo(high.id)
    assertThat(r2.winners()).isEqualTo(winners)
    // Gli stessi vincitori anche da chi legge in un altro momento (E-I7) e da chi pubblica.
    low.sync()
    high.sync()
    assertThat(low.winners()).isEqualTo(winners)
    assertThat(high.winners()).isEqualTo(winners)
  }

  @Test
  fun fusione_disaccordoFraVettoriDellaStessaRicettaContato_nonQuandoConcordano() = runTest {
    val ai = AiWorld(tmp.root)
    val preparer = ai.device("Tablet")
    val a = ai.device("A")
    val b = ai.device("B")
    val fs = files(ai, 2)
    preparer.prepare(fs)
    publish(a, listOf(AiEntry.Vector(fs[0].contentKey, AiBodies.vec(1)), AiEntry.Vector(fs[1].contentKey, AiBodies.vec(5))))
    publish(b, listOf(AiEntry.Vector(fs[0].contentKey, AiBodies.vec(2)), AiEntry.Vector(fs[1].contentKey, AiBodies.near(5))))
    val reader = ai.device("Lettore")
    reader.sync()
    assertThat(AiFusion.cosine(AiBodies.vec(5).vectors[0].f16, AiBodies.near(5).vectors[0].f16)).isGreaterThan(0.99f)
    assertThat(reader.counters.disagree.get()).isEqualTo(1)
    assertThat(reader.winners()).hasSize(2)
    // Un giro senza novita' non riconta.
    reader.sync()
    assertThat(reader.counters.disagree.get()).isEqualTo(1)
  }

  @Test
  fun revoca_leVociDelDispositivoEscono_eVinceLAltroCandidato() = runTest {
    val ai = AiWorld(tmp.root)
    val preparer = ai.device("Tablet")
    val (first, second) = listOf(ai.device("A"), ai.device("B")).sortedBy { it.id }
    val fs = files(ai, 2)
    preparer.prepare(fs)
    publish(first, listOf(first.vector(fs[0]), first.vector(fs[1])))
    publish(second, listOf(second.vector(fs[0], seed = 7)))
    val reader = ai.device("Lettore")
    reader.sync()
    assertThat(reader.winners().values.map { it.first }.toSet()).containsExactly(first.id)

    ai.world.trust.changeStatus(first.base.signer, DeviceStatus.REVOKED)
    reader.sync()

    val winners = reader.winners()
    assertThat(winners.keys).containsExactly(Triple(share, fs[0].contentKey, AiClass.VEC))
    assertThat(winners.values.single().first).isEqualTo(second.id)
    assertThat(reader.aiStore.heads(share).map { it.deviceId }).doesNotContain(first.id)
    assertThat(reader.counters.devicesForgotten.get()).isAtLeast(1)
  }

  // --- Segmenti e teste ostili -------------------------------------------------------------------------------------------

  @Test
  fun unSegmentoOstileOAssenteNonFermaGliAltri() = runTest {
    val ai = AiWorld(tmp.root)
    val preparer = ai.device("Tablet")
    val rogue = ai.device("Compromesso")
    val reader = ai.device("Telefono")
    val fs = files(ai, 3)
    preparer.prepare(fs)
    val good = encoded(AiClass.VEC, listOf(fs[0].contentKey to AiBodies.vec(1)))
    val nan = AiSegments.encodeBody(AiBodies.vec(2)).also { it[8] = 0x7e; it[9] = 0 }
    val bad = raw(AiClass.VEC, listOf(fs[1].contentKey to nan))
    val absent = encoded(AiClass.OCR, listOf(fs[2].contentKey to AiBodies.ocr("non pubblicato")))
    forge(ai, rogue, listOf(good, bad))
    // La testa elenca anche un segmento mai scritto (E-I17).
    val state = ai.world.trust.state
    HeadSeries.publish(ai.world.nas.share(share), state, rogue.base.signer, HeadKind.AI, 1, rogue.base.account) { v ->
      DeviceHead(state.registry.familyId, rogue.id, share, HeadKind.AI, v, state.registry.version, ai.clock.nowMillis(), listOf(good.second, bad.second, absent.second).sortedBy { it.ref.hash })
        .sign(rogue.base.signer)
    }

    reader.sync()

    assertThat(reader.winners().keys).containsExactly(Triple(share, fs[0].contentKey, AiClass.VEC))
    assertThat(reader.counters.rejected()).containsEntry(RejectReason.INVALID_FIELD, 1)
    assertThat(reader.counters.segmentsMissing.get()).isEqualTo(1)
    // Senza una testa nuova i segmenti assenti o rifiutati non si riscaricano (E-I17), anche quando un altro segmento della stessa
    // testa e' rimasto in attesa (budget finito al giro prima): si riprende solo quello.
    val head = reader.aiStore.heads(share).single { it.deviceId == rogue.id }
    val rows = reader.aiStore.segments(share, rogue.id)
    assertThat(rows.map { it.state }).containsExactly(SegmentState.APPLIED, SegmentState.REJECTED, SegmentState.MISSING)
    reader.aiStore.applyHead(AiHeadApplication(head, rows.map { if (it.state == SegmentState.APPLIED) it.copy(state = SegmentState.PENDING) else it }, emptyMap()))
    val reads = reader.aiRemote()
    val remote = reader.remote()
    reader.engine(remote, reader.shareSync(remote, reads)).sync(SyncScope(setOf(share), setOf(SegmentClass.THUMB_S)), Duration.INFINITE)
    assertThat(reads.segmentReads.get()).isEqualTo(1)
    assertThat(reader.counters.segmentsMissing.get()).isEqualTo(1)
    assertThat(reader.aiStore.segments(share, rogue.id).map { it.state }).containsExactly(SegmentState.APPLIED, SegmentState.REJECTED, SegmentState.MISSING)
  }

  @Test
  fun unaTestaNonValidaNellaSerieAiEInteramenteRifiutata_nienteApplicato() = runTest {
    val ai = AiWorld(tmp.root)
    val preparer = ai.device("Tablet")
    val rogue = ai.device("Compromesso")
    val reader = ai.device("Telefono")
    val file = files(ai, 1).single()
    preparer.prepare(listOf(file))
    val fs = ai.world.nas.share(share)
    val vec = encoded(AiClass.VEC, listOf(file.contentKey to AiBodies.vec(1)))
    fs.publishExclusive(NasLayout.aiSegment(rogue.id, vec.second.ref.hash), vec.first)
    // Una testa `index` (versione 1, classi dell'indice) messa nella serie `ai`: il posto non torna, la testa non vale. (Le classi
    // `vec` e `ocr` in una testa `index`, `thumbS` in una testa `ai` e le versioni sbagliate per il tipo: AiHeadTest di core-trust.)
    val state = ai.world.trust.state
    val meta = dev.pampa.mycloudgallery.core.trust.data.Segments.wrap("x".toByteArray())
    val wrong = DeviceHead(state.registry.familyId, rogue.id, share, HeadKind.INDEX, 1, state.registry.version, ai.clock.nowMillis(), listOf(ClassedSegment(SegmentClass.META, meta.second)))
      .sign(rogue.base.signer)
    fs.publishExclusive(NasLayout.aiHead(rogue.id, 1), wrong)

    reader.sync()

    assertThat(reader.winners()).isEmpty()
    assertThat(reader.aiStore.keys(share)).isEmpty()
    assertThat(reader.counters.rejected()).containsEntry(RejectReason.WRONG_LOCATION, 1)
  }

  // --- Compatibilita' ----------------------------------------------------------------------------------------------------

  /**
   * E-I3, E-I22: "un lettore della versione 1 ignora `ai/`". Il motore della Fase 4 senza l'aggancio (come un'app senza la Fase 6)
   * non elenca e non legge niente sotto `ai/`, e la sua replica e' identica a quella di chi legge una share senza dati AI. Con
   * l'aggancio, le teste `ai` si leggono dopo quelle `index`.
   */
  @Test
  fun unLettoreDellaVersione1IgnoraAi_eConLaFase6SiLeggonoDopoLeTesteIndex() = runTest {
    val ai = AiWorld(tmp.root)
    val preparer = ai.device("Tablet")
    val indexer = ai.device("Telefono")
    val fs = files(ai, 5)
    preparer.prepare(fs)
    val before = ai.device("Vecchio prima")
    val recorder0 = RecordingFileSystem(ai.world.nas.share(share))
    before.engine(before.remote { recorder0 }, hook = null).sync(SyncScope(setOf(share), setOf(SegmentClass.THUMB_S)), Duration.INFINITE)
    publish(indexer, fs.map { indexer.vector(it) } + fs.map { indexer.text(it) })

    val old = ai.device("Vecchio")
    val recorder = RecordingFileSystem(ai.world.nas.share(share))
    val report = old.engine(old.remote { recorder }, hook = null).sync(SyncScope(setOf(share), setOf(SegmentClass.THUMB_S)), Duration.INFINITE)

    assertThat(report.complete).isTrue()
    assertThat((recorder.reads + recorder.lists).filter { "/ai/" in it }).isEmpty()
    assertThat(replicaDigest(old.replicaStore, old.replica, share)).isEqualTo(replicaDigest(before.replicaStore, before.replica, share))

    val fresh = ai.device("Nuovo")
    val recorder2 = RecordingFileSystem(ai.world.nas.share(share))
    val remote = fresh.remote { recorder2 }
    fresh.engine(remote, fresh.shareSync(remote, fresh.aiRemote { recorder2 })).sync(SyncScope(setOf(share), setOf(SegmentClass.THUMB_S)), Duration.INFINITE)
    val firstAi = recorder2.reads.indexOfFirst { "/ai/" in it }
    val lastIndex = recorder2.reads.indexOfLast { "/index/" in it }
    assertThat(firstAi).isGreaterThan(lastIndex)
    assertThat(fresh.winners()).hasSize(10)
    assertThat(replicaDigest(fresh.replicaStore, fresh.replica, share)).isEqualTo(replicaDigest(before.replicaStore, before.replica, share))
  }

  // --- Area persa, budget e ripresa --------------------------------------------------------------------------------------

  @Test
  fun eI8_unaShareNegataInDueGiorniTogliIDatiAi_ancheDallIndiceLocale() = runTest {
    val ai = AiWorld(tmp.root)
    val preparer = ai.device("Tablet")
    val indexer = ai.device("Telefono")
    val reader = ai.device("Lettore")
    val fs = files(ai, 2)
    preparer.prepare(fs)
    publish(indexer, fs.map { indexer.vector(it) })
    reader.sync()
    assertThat(reader.winners()).hasSize(2)

    ai.world.nas.share(share).failWith = NasError.PermissionDenied
    reader.sync()
    assertThat(reader.winners()).hasSize(2) // un giorno solo: "non raggiungibile"
    ai.clock.advance(24L * 60 * 60 * 1000)
    reader.sync()

    assertThat(reader.winners()).isEmpty()
    assertThat(reader.sink.forgottenShares).containsExactly(share)
    assertThat(reader.aiStore.keys(share)).isEmpty()
  }

  @Test
  fun budgetDeiByte_ilRestoInAttesa_eSiRiprendeAlGiroDopoSenzaTesteNuove() = runTest {
    val ai = AiWorld(tmp.root)
    val preparer = ai.device("Tablet")
    val indexer = ai.device("Telefono")
    val fs = files(ai, 6)
    preparer.prepare(fs)
    // Tre teste, un segmento `vec` ciascuna.
    for (part in fs.chunked(2)) publish(indexer, part.map { indexer.vector(it) })
    val reader = ai.device("Lettore")
    val segment = AiSegments.encode(AiClass.VEC, fs.take(2).map { it.contentKey to AiBodies.vec(it.path.hashCode()) }).bytes.size.toLong()
    val remote = reader.remote()
    val sync = reader.shareSync(remote, maxBytesPerShare = segment + segment / 2)
    reader.engine(remote, sync).sync(SyncScope(setOf(share), setOf(SegmentClass.THUMB_S)), Duration.INFINITE)
    assertThat(reader.winners()).hasSize(2)
    repeat(2) {
      val again = reader.remote()
      reader.engine(again, reader.shareSync(again, maxBytesPerShare = segment + segment / 2)).sync(SyncScope(setOf(share), setOf(SegmentClass.THUMB_S)), Duration.INFINITE)
    }
    assertThat(reader.winners()).hasSize(6)
  }

}
