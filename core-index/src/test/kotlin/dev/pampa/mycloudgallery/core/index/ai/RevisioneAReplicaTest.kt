package dev.pampa.mycloudgallery.core.index.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.SyncScope
import dev.pampa.mycloudgallery.core.index.p3.TestFile
import dev.pampa.mycloudgallery.core.index.testing.FakeAiReplicaSink
import dev.pampa.mycloudgallery.core.model.OcrEngineCode
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.AiSegments
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.DeviceHead
import dev.pampa.mycloudgallery.core.trust.data.HeadKind
import dev.pampa.mycloudgallery.core.trust.data.HeadSeries
import dev.pampa.mycloudgallery.core.trust.data.OcrBody
import dev.pampa.mycloudgallery.core.trust.data.OcrLine
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.ShortBox
import dev.pampa.mycloudgallery.core.trust.data.WordSpan
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Revisione A della Fase 6 (docs/review/6-fase6.md), la replica e la pubblicazione `ai`: R2 (errori isolati, consegne a pezzi), R5 (la
 * revoca arriva all'indice locale), R14 (compattazione delle teste proprie ed E-I17 per chi legge), R15 (riconsegna dopo una
 * riparazione), R16 (ricetta 255 nella fusione), I10 (testa `ai` di un altro account in un'area privata).
 */
class RevisioneAReplicaTest {

  @get:Rule val tmp = TemporaryFolder()

  private val share = "test-comune"

  private suspend fun publish(device: AiDevice, entries: List<AiEntry>, share: String = this.share) {
    val publisher = device.publisher()
    publisher.stage(share, entries)
    check(publisher.flush(share, FlushReason.UNIT_FINISHED) is dev.pampa.mycloudgallery.core.index.contract.PublishOutcome.Published)
  }

  private fun files(ai: AiWorld, n: Int, prefix: String = "Foto", share: String = this.share): List<TestFile> =
    (1..n).map { ai.file(share, "$prefix/IMG_$it.jpg") }

  /** Un dispositivo compromesso pubblica nella sua testa `ai` i segmenti che vuole (versione [version]). */
  private suspend fun forge(ai: AiWorld, device: AiDevice, segments: List<Pair<ByteArray, ClassedSegment>>, after: Long, share: String = this.share) {
    val fs = ai.world.nas.share(share)
    segments.forEach { (bytes, seg) -> fs.publishExclusive(NasLayout.aiSegment(device.id, seg.ref.hash), bytes) }
    val state = ai.world.trust.state
    HeadSeries.publish(fs, state, device.base.signer, HeadKind.AI, after, device.base.account) { v ->
      DeviceHead(state.registry.familyId, device.id, fs.shareName, HeadKind.AI, v, state.registry.version, ai.clock.nowMillis(), segments.map { it.second }.sortedBy { it.ref.hash })
        .sign(device.base.signer)
    }
  }

  /** Un corpo `ocr` di circa 40 KB (40 righe da 1.000 caratteri). */
  private fun bigText(seed: Int): OcrBody = OcrBody(
    OcrEngineCode.MLKIT_V2_LATIN, 1, OcrSource.ANALYSIS,
    (1..40).map { OcrLine(ShortBox(0, 0, 60_000, 5_000), 0, listOf(WordSpan(0, 65_535)), ('a' + (seed + it) % 26).toString().repeat(1_000)) },
  )

  /** Un sink che non accetta niente (come l'indice locale con una cornice troppo grande, R2). */
  private class BrokenSink : AiReplicaSink by FakeAiReplicaSink() {
    override suspend fun apply(share: String, winners: List<AiWinner>) = throw IllegalArgumentException("Cornice troppo grande.")
  }

  private fun syncWith(device: AiDevice, sink: AiReplicaSink, knownRecipe: (AiClass) -> Int = { AiFusion.ANY_RECIPE }, maxBytesPerShare: Long = AiShareSync.DEFAULT_MAX_BYTES_PER_SHARE): AiShareSync {
    val remote = device.remote()
    val aiRemote = device.aiRemote()
    return AiShareSync(
      remote = aiRemote, store = device.aiStore, attestation = ReplicaAttestation(device.replicaStore, device.catalog), sink = sink,
      standings = device.ai.standings, counters = device.counters, listDevices = { remote.devices(it) }, startRound = { aiRemote.startRound() },
      maxBytesPerShare = maxBytesPerShare, knownRecipe = knownRecipe,
    )
  }

  /** R2: un errore dell'indice locale resta nella parte `ai`: il giro dell'indice (miniature comprese) finisce, l'errore si conta. */
  @Test
  fun r2_unErroreDellIndiceLocaleNonFermaIlGiro() = runTest {
    val ai = AiWorld(tmp.root)
    val preparer = ai.device("Tablet")
    val indexer = ai.device("Telefono")
    val reader = ai.device("Lettore")
    val fs = files(ai, 3)
    preparer.prepare(fs)
    publish(indexer, fs.map { indexer.text(it) })
    val remote = reader.remote()
    val report = reader.engine(remote, syncWith(reader, BrokenSink())).sync(SyncScope(setOf(share), setOf(SegmentClass.THUMB_S)), Duration.INFINITE)
    assertThat(report.complete).isFalse()
    assertThat(reader.counters.syncErrors.get()).isAtLeast(1)
    // Le chiavi restano da rifondere: con l'indice locale di nuovo a posto arrivano.
    reader.sync()
    assertThat(reader.winners()).hasSize(3)
  }

  /** R2: i corpi arrivano all'indice locale a pezzi di al piu' 4 MiB, mai tutti insieme. */
  @Test
  fun r2_leConsegneAllIndiceLocaleVannoAPezzi() = runTest {
    val ai = AiWorld(tmp.root)
    val preparer = ai.device("Tablet")
    val indexer = ai.device("Telefono")
    val reader = ai.device("Lettore")
    val fs = files(ai, 150)
    preparer.prepare(fs)
    publish(indexer, fs.mapIndexed { i, f -> AiEntry.Text(f.contentKey, bigText(i)) })
    val calls = ArrayList<Long>()
    val sink = object : AiReplicaSink by FakeAiReplicaSink() {
      override suspend fun apply(share: String, winners: List<AiWinner>) {
        calls += winners.sumOf { AiSegments.encodeBody((it.entry as AiEntry.Text).body).size.toLong() }
      }
    }
    reader.engine(reader.remote(), syncWith(reader, sink)).sync(SyncScope(setOf(share), setOf(SegmentClass.THUMB_S)), Duration.INFINITE)
    assertThat(calls.size).isAtLeast(2)
    assertThat(calls.max()).isAtMost(AiShareSync.APPLY_BATCH_BYTES + 64 * 1024)
    assertThat(calls.sum()).isGreaterThan(5L * 1024 * 1024)
  }

  /** R16: una ricetta 255 non vince la fusione senza l'id minore: con la ricetta nota (1) vince chi ha l'id minore, come per tutti. */
  @Test
  fun r16_unaRicetta255NonVinceLaFusioneSenzaLIdMinore() = runTest {
    val ai = AiWorld(tmp.root)
    val preparer = ai.device("Tablet")
    val (honest, rogue) = listOf(ai.device("Onesto"), ai.device("Compromesso")).sortedBy { it.id }
    val file = files(ai, 1).single()
    preparer.prepare(listOf(file))
    publish(honest, listOf(honest.text(file, "IBAN giusto")))
    publish(rogue, listOf(rogue.text(file, "IBAN falso", recipe = 255)))
    val reader = ai.device("Lettore")
    val sink = FakeAiReplicaSink()
    reader.engine(reader.remote(), syncWith(reader, sink, knownRecipe = { 1 })).sync(SyncScope(setOf(share), setOf(SegmentClass.THUMB_S)), Duration.INFINITE)
    assertThat(sink.applied.single().device).isEqualTo(honest.id)
  }

  /** R5: un dispositivo revocato si dimentica anche nell'indice locale (con cio' che questo dispositivo ha calcolato sulle sue anteprime). */
  @Test
  fun r5_laRevocaArrivaAllIndiceLocale() = runTest {
    val ai = AiWorld(tmp.root)
    val preparer = ai.device("Tablet")
    val indexer = ai.device("Telefono")
    val fs = files(ai, 2)
    preparer.prepare(fs)
    publish(indexer, fs.map { indexer.vector(it) })
    val reader = ai.device("Lettore")
    reader.sync()
    assertThat(reader.winners()).hasSize(2)
    ai.world.trust.changeStatus(indexer.base.signer, DeviceStatus.REVOKED)
    reader.sync()
    assertThat(reader.sink.forgottenDevices).contains(indexer.id)
    assertThat(reader.winners()).isEmpty()
  }

  /**
   * I10: le teste `ai` di un'area privata valgono solo se firmate da un dispositivo dell'account proprietario (chi legge): una testa di un
   * altro account (permessi SMB sbagliati, dispositivo compromesso) si rifiuta e non porta niente.
   */
  @Test
  fun i10_unaTestaAiDiUnAltroAccountInAreaPrivataSiRifiuta() = runTest {
    val ai = AiWorld(tmp.root)
    val mine = "test"
    val preparer = ai.device("Tablet")
    val stranger = ai.device("Di un altro", account = "altro")
    val fs = files(ai, 2, share = mine)
    preparer.prepare(fs, share = mine)
    publish(stranger, fs.map { stranger.text(it, "testo estraneo") }, share = mine)
    val reader = ai.device("Lettore")
    reader.sync(mine)
    assertThat(reader.winners()).isEmpty()
    assertThat(reader.counters.rejected()[RejectReason.NOT_OWNER]).isAtLeast(1)
  }

  /** R15: dopo una riparazione dell'indice locale la fusione riconsegna anche se il vincitore e' quello di prima. */
  @Test
  fun r15_laRiparazioneRiconsegnaLeChiavi() = runTest {
    val ai = AiWorld(tmp.root)
    val preparer = ai.device("Tablet")
    val indexer = ai.device("Telefono")
    val fs = files(ai, 2)
    preparer.prepare(fs)
    publish(indexer, fs.map { indexer.vector(it) })
    val reader = ai.device("Lettore")
    reader.sync()
    assertThat(reader.winners()).hasSize(2)
    // L'indice locale perde il vettore del primo file (record rovinato) e chiede di riaverlo.
    reader.sink.applied.removeAll { it.entry.contentKey == fs[0].contentKey }
    AiRedelivery(reader.aiStore).redeliver(listOf(fs[0].contentKey))
    reader.sync()
    assertThat(reader.winners().keys.map { it.second }).containsExactly(fs[0].contentKey, fs[1].contentKey)
  }

  /**
   * R14: la compattazione della testa propria toglie le voci dei file che non sono piu' della share (M3 adesso) e quelle che questo
   * dispositivo non sostiene piu' (calcolate su anteprime di un dispositivo revocato, R5): chi legge non le ha piu'.
   */
  @Test
  fun r14_laCompattazioneTogliLeVociCheNonValgonoPiu() = runTest {
    val ai = AiWorld(tmp.root)
    val preparer = ai.device("Tablet")
    val indexer = ai.device("Telefono")
    val reader = ai.device("Lettore")
    val fs = files(ai, 4)
    preparer.prepare(fs)
    val entries = fs.map { indexer.text(it) }
    publish(indexer, entries)
    reader.sync()
    assertThat(reader.winners()).hasSize(4)
    // Il documento del file 2 non e' piu' nella share; il testo del file 3 non e' piu' sostenuto (nato da un'anteprima di un revocato).
    ai.content.remove(share, fs[1].contentKey)
    val bodies = entries.associate { it.key to AiSegments.encodeBody((it as AiEntry.Text).body) }
    val publisher = OutboxAiIndexPublisher(
      identity = indexer.identity, clock = ai.clock, fileSystem = { ai.world.nas.share(it) }, store = indexer.publishStore, content = ai.content,
      rules = indexer.rules(), counters = indexer.counters, io = kotlinx.coroutines.Dispatchers.Unconfined,
      ownBodies = { _, key, _ -> if (key.contentKey == fs[2].contentKey) null else bodies[key] },
    )
    publisher.requestCompaction()
    publisher.flush(share, FlushReason.PERIODIC)
    assertThat(indexer.counters.compactions.get()).isEqualTo(1)
    val seen = aiReaderState(ai, share, indexer.id)
    assertThat(seen.keys.map { it.contentKey }).containsExactly(fs[0].contentKey, fs[3].contentKey)
    reader.sync()
    assertThat(reader.winners().keys.map { it.second }).containsExactly(fs[0].contentKey, fs[3].contentKey)
    // Le voci tenute restano vive per chi pubblica: una ripubblicazione identica non riscrive niente.
    assertThat(indexer.publishStore.liveAll(share).map { it.key.contentKey }).containsExactly(fs[0].contentKey, fs[3].contentKey)
  }

  /**
   * R14: la compattazione di ogni giorno toglie le voci dei file che non sono piu' della share ma non guarda i corpi: un indice locale
   * ricostruito da capo (nessun corpo sostenuto) non svuota la testa, e un corpo che non si sa leggere non toglie niente.
   */
  @Test
  fun r14_laCompattazioneDiOgniGiornoNonGuardaICorpi() = runTest {
    val ai = AiWorld(tmp.root)
    val preparer = ai.device("Tablet")
    val indexer = ai.device("Telefono")
    val fs = files(ai, 3)
    preparer.prepare(fs)
    publish(indexer, fs.map { indexer.text(it) })
    ai.content.remove(share, fs[0].contentKey)
    val publisher = OutboxAiIndexPublisher(
      identity = indexer.identity, clock = ai.clock, fileSystem = { ai.world.nas.share(it) }, store = indexer.publishStore, content = ai.content,
      rules = indexer.rules(), counters = indexer.counters, io = kotlinx.coroutines.Dispatchers.Unconfined,
      ownBodies = { _, _, _ -> null },
      compactEveryMillis = 0L,
    )
    publisher.flush(share, FlushReason.PERIODIC)
    assertThat(indexer.publishStore.liveAll(share).map { it.key.contentKey }).containsExactly(fs[1].contentKey, fs[2].contentKey)

    // Dopo una revoca i corpi contano; se non si sanno leggere (indice non aperto) non si toglie niente.
    val unsure = OutboxAiIndexPublisher(
      identity = indexer.identity, clock = ai.clock, fileSystem = { ai.world.nas.share(it) }, store = indexer.publishStore, content = ai.content,
      rules = indexer.rules(), counters = indexer.counters, io = kotlinx.coroutines.Dispatchers.Unconfined,
      ownBodies = { _, _, _ -> throw java.io.IOException("indice non aperto") },
    )
    unsure.requestCompaction()
    unsure.flush(share, FlushReason.PERIODIC)
    assertThat(indexer.publishStore.liveAll(share).map { it.key.contentKey }).containsExactly(fs[1].contentKey, fs[2].contentKey)
  }

  /**
   * E-I17 per le teste `ai` (R14): una testa nuova che non elenca piu' un segmento (compattato) non toglie le sue voci finche' i segmenti
   * nuovi non sono scaricati; poi le voci sono quelle della testa nuova.
   */
  @Test
  fun eI17_leVociDeiSegmentiTogliRestanoFinoAiNuovi() = runTest {
    val ai = AiWorld(tmp.root)
    val preparer = ai.device("Tablet")
    val indexer = ai.device("Telefono")
    val fs = files(ai, 3)
    preparer.prepare(fs)
    val first = AiSegments.encode(AiClass.OCR, fs.map { it.contentKey to AiBodies.ocr("vecchio ${it.path.name}".replace('.', ' ')) })
    forge(ai, indexer, listOf(first.bytes to first.classed), after = 0)
    val reader = ai.device("Lettore")
    reader.sync()
    assertThat(reader.winners()).hasSize(3)
    // La testa compattata: un segmento nuovo con due sole voci, piu' grande del budget del giro.
    val second = AiSegments.encode(AiClass.OCR, fs.take(2).map { it.contentKey to AiBodies.ocr("nuovo ${it.path.name}".replace('.', ' ')) })
    forge(ai, indexer, listOf(second.bytes to second.classed), after = 1)
    reader.engine(reader.remote(), syncWith(reader, reader.sink, maxBytesPerShare = 10)).sync(SyncScope(setOf(share), setOf(SegmentClass.THUMB_S)), Duration.INFINITE)
    // Il segmento nuovo non e' arrivato: le voci di prima restano tutte.
    assertThat(reader.winners()).hasSize(3)
    reader.sync()
    assertThat(reader.winners().keys.map { it.second }).containsExactly(fs[0].contentKey, fs[1].contentKey)
  }
}
