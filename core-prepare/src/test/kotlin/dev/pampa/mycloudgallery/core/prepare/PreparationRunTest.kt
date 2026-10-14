package dev.pampa.mycloudgallery.core.prepare

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.contract.BlobPut
import dev.pampa.mycloudgallery.core.index.contract.UnitOutcome
import dev.pampa.mycloudgallery.core.index.contract.WorkItem
import dev.pampa.mycloudgallery.core.index.contract.WorkPass
import dev.pampa.mycloudgallery.core.index.contract.WorkUnitKey
import dev.pampa.mycloudgallery.core.index.testing.FakeIndexPublisher
import dev.pampa.mycloudgallery.core.index.testing.FakeUnitPriority
import dev.pampa.mycloudgallery.core.index.testing.FakeWorkUnits
import dev.pampa.mycloudgallery.core.index.testing.fakeIndexDevice
import dev.pampa.mycloudgallery.core.metadata.testing.ByteArrayMediaBytes
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.prepare.contract.DecodedFacts
import dev.pampa.mycloudgallery.core.prepare.contract.PrepAttempts
import dev.pampa.mycloudgallery.core.prepare.contract.PrepBudget
import dev.pampa.mycloudgallery.core.prepare.contract.PrepFailCode
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOpen
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOutput
import dev.pampa.mycloudgallery.core.prepare.contract.PrepResult
import dev.pampa.mycloudgallery.core.prepare.contract.PrepSpec
import dev.pampa.mycloudgallery.core.prepare.contract.PrepStop
import dev.pampa.mycloudgallery.core.prepare.contract.PrepVerdict
import dev.pampa.mycloudgallery.core.prepare.contract.PrepareSource
import dev.pampa.mycloudgallery.core.prepare.contract.Preparer
import dev.pampa.mycloudgallery.core.prepare.run.DefaultPreparationRun
import dev.pampa.mycloudgallery.core.prepare.run.PrepRecords
import dev.pampa.mycloudgallery.core.prepare.testing.FakePrepInputs
import dev.pampa.mycloudgallery.core.prepare.testing.InMemoryPrepAttempts
import dev.pampa.mycloudgallery.core.prepare.testing.ManualPrepClock
import dev.pampa.mycloudgallery.core.prepare.testing.MapPrepRecords
import dev.pampa.mycloudgallery.core.prepare.testing.ScriptedPrepControl
import dev.pampa.mycloudgallery.core.trust.data.MetaFile
import dev.pampa.mycloudgallery.core.trust.data.MetaRecord
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

/** Il giro di preparazione sui contratti: unita', apertura, preparazione, pubblicazione, fallimenti, tappe e prenotazioni. */
class PreparationRunTest {

  private val share = "test-comune"

  private class Media(val bytes: ByteArray, val item: WorkItem, val base: MetaFile)

  private var counter = 0

  private fun media(
    name: String,
    kind: MediaKind = MediaKind.PHOTO,
    size: Int = 5_000,
    missing: Set<SegmentClass> = setOf(SegmentClass.THUMB_S, SegmentClass.THUMB_M, SegmentClass.ANALYSIS, SegmentClass.HQ),
    failedDecoders: Set<String> = emptySet(),
  ): Media {
    val bytes = Random(counter++).nextBytes(size)
    val qh = QuickHashAlgorithm.ofContent(bytes)
    val stamp = NasStamp(bytes.size.toLong(), 1_800_000_000_000L + counter, 5)
    val mediaId = MediaId.random()
    val versionId = VersionId.random()
    val item = WorkItem(ContentKey.of(qh, bytes.size.toLong()), SharePath.parseMediaPath(name), stamp, kind, null, mediaId, versionId, missing, failedDecoders)
    val base = MetaFile(stamp = stamp, quickHash = qh, mediaId = mediaId, versionId = versionId, kind = kind, source = MediaSource.OTHER)
    return Media(bytes, item, base)
  }

  private class Rig(
    val units: FakeWorkUnits,
    val publisher: FakeIndexPublisher,
    val inputs: FakePrepInputs,
    val records: MapPrepRecords,
    val closed: AtomicInteger,
  )

  private fun rig(): Rig {
    val (publisher, _) = fakeIndexDevice("devicea0000000000000000000")
    return Rig(FakeWorkUnits(), publisher, FakePrepInputs(), MapPrepRecords(), AtomicInteger())
  }

  private fun Rig.register(vararg medias: Media) {
    for (m in medias) {
      records.records[m.item.contentKey.hex] = m.base
      inputs.opens[m.item.contentKey.hex] = PrepOpen.Ready(PrepareSource.Bytes(ByteArrayMediaBytes(m.bytes), m.item.kind, null)) { closed.incrementAndGet() }
    }
  }

  private fun Rig.offer(month: String, part: Int, vararg medias: Media) {
    units.offer(WorkUnitKey(share, WorkPass.PREP, month, part), medias.map { it.item })
    register(*medias)
  }

  private fun images(vararg classes: SegmentClass) = classes.map { PrepOutput.Image(it, byteArrayOf(0x52, 0x49, 0x46, 0x46, 1, 2, 3, 4, 0x57, 0x45, 0x42, 0x50), 100, 80) }

  private fun done(item: WorkItem, bytes: ByteArray, classes: Set<SegmentClass> = item.missing) = PrepResult.Done(
    outputs = images(*classes.filter { it != SegmentClass.FRAMES }.toTypedArray()),
    fullHash = Sha256.of(bytes),
    facts = DecodedFacts(4000, 3000),
    thumbHash = ThumbHash.of(byteArrayOf(1, 2, 3, 4, 5, 6)),
    quickHash = QuickHashAlgorithm.ofContent(bytes),
  )

  /** Un preparatore a copione: per ogni sorgente (riconosciuta dai byte) decide il risultato. */
  private class ScriptedPreparer(val handler: suspend (PrepareSource, PrepSpec) -> PrepResult) : Preparer {
    val calls = AtomicInteger()
    val running = AtomicInteger()
    val peak = AtomicInteger()

    override suspend fun prepare(source: PrepareSource, spec: PrepSpec): PrepResult {
      calls.incrementAndGet()
      val now = running.incrementAndGet()
      peak.updateAndGet { maxOf(it, now) }
      try {
        return handler(source, spec)
      } finally {
        running.decrementAndGet()
      }
    }
  }

  private fun Rig.runner(
    preparer: Preparer,
    limits: PrepBudget = PrepBudget(256, 8),
    attempts: PrepAttempts = PrepAttempts.NONE,
    control: dev.pampa.mycloudgallery.core.prepare.contract.PrepControl = dev.pampa.mycloudgallery.core.prepare.contract.PrepControl.ALWAYS,
    clock: dev.pampa.mycloudgallery.core.prepare.contract.PrepClock = dev.pampa.mycloudgallery.core.prepare.contract.PrepClock.SYSTEM,
    storage: dev.pampa.mycloudgallery.core.prepare.contract.PrepStorage? = null,
  ) = DefaultPreparationRun(
    workUnits = units,
    inputs = inputs,
    preparer = preparer,
    publisher = publisher,
    records = records,
    limits = limits,
    decoderFamily = "c2_me",
    attempts = attempts,
    control = control,
    clock = clock,
    slowPause = 20.milliseconds,
    dispatcher = Dispatchers.Default,
    storage = storage,
  )

  private fun bytesOf(source: PrepareSource): ByteArray {
    val media = (source as PrepareSource.Bytes).bytes
    return kotlinx.coroutines.runBlocking { media.read(0L until media.size) }
  }

  // --- Il giro felice ---------------------------------------------------------------------------------------------------

  @Test
  fun unGiroPubblicaIRecordCompletatiEIBlobDiOgniMedia() = runTest {
    val rig = rig()
    val a = media("2024/a.jpg")
    val b = media("2024/b.jpg", missing = setOf(SegmentClass.THUMB_S, SegmentClass.THUMB_M))
    rig.offer("2024-03", 0, a, b)
    val preparer = ScriptedPreparer { source, spec ->
      val bytes = bytesOf(source)
      val item = if (bytes.contentEquals(a.bytes)) a.item else b.item
      // Il giro chiede solo le classi che mancano.
      assertThat(spec.classes).isEqualTo(item.missing)
      done(item, bytes, spec.classes)
    }

    val result = rig.runner(preparer).run(share, 8.minutes, FakeUnitPriority())

    assertThat(result.units).isEqualTo(1)
    assertThat(result.prepared).isEqualTo(2)
    assertThat(result.failed).isEqualTo(0)
    assertThat(result.bytesRead).isEqualTo(a.bytes.size + b.bytes.size.toLong())
    assertThat(result.stoppedBy).isEqualTo(PrepStop.NO_WORK)
    assertThat(rig.units.finished.single().second).isEqualTo(UnitOutcome.Completed(prepared = 2, failed = 0))
    assertThat(rig.closed.get()).isEqualTo(2)

    val published = rig.publisher.published.single()
    val recordA = published.records.map { it as MetaRecord.Present }.single { it.path == a.item.path }.file
    assertThat(recordA.fullHash).isEqualTo(Sha256.of(a.bytes))
    assertThat(recordA.width to recordA.height).isEqualTo(4000 to 3000)
    assertThat(recordA.thumbHash).isNotNull()
    assertThat(recordA.preparedClasses).containsExactly(SegmentClass.THUMB_S, SegmentClass.THUMB_M, SegmentClass.ANALYSIS, SegmentClass.HQ)
    assertThat(recordA.failure).isNull()
    // I blob stanno sotto la chiave di contenuto del lavoro.
    val blobsOfB = published.blobs.filter { it.contentKey == b.item.contentKey }
    assertThat(blobsOfB.map { it.cls }).containsExactly(SegmentClass.THUMB_S, SegmentClass.THUMB_M)
    assertThat(published.blobs.filterIsInstance<BlobPut.Image>()).hasSize(6)
  }

  @Test
  fun iFotogrammiDiUnVideoDiventanoUnBlobFrames() = runTest {
    val rig = rig()
    val video = media("2024/v.mp4", kind = MediaKind.VIDEO, missing = setOf(SegmentClass.THUMB_S, SegmentClass.FRAMES))
    rig.offer("2024-03", 0, video)
    val preparer = ScriptedPreparer { source, _ ->
      PrepResult.Done(
        outputs = images(SegmentClass.THUMB_S) + PrepOutput.Frames(listOf(dev.pampa.mycloudgallery.core.index.contract.VideoFrame(1000, byteArrayOf(-1, -40, -1, 1)))),
        fullHash = null,
        facts = DecodedFacts(1920, 1080, 90_000),
        thumbHash = null,
        quickHash = QuickHashAlgorithm.ofContent(bytesOf(source)),
      )
    }
    val result = rig.runner(preparer).run(share, 8.minutes, FakeUnitPriority())
    assertThat(result.prepared).isEqualTo(1)
    val published = rig.publisher.published.single()
    assertThat(published.blobs.map { it.cls }).containsExactly(SegmentClass.THUMB_S, SegmentClass.FRAMES)
    val record = (published.records.single() as MetaRecord.Present).file
    assertThat(record.durationMs).isEqualTo(90_000)
    assertThat(record.fullHash).isNull()
  }

  // --- D8: i byte letti devono essere quelli del lavoro -----------------------------------------------------------

  @Test
  fun seIlFileECambiatoDopoLElencoNienteSiPubblica() = runTest {
    val rig = rig()
    val a = media("2024/a.jpg")
    val changedSize = media("2024/b.jpg")
    rig.offer("2024-03", 0, a, changedSize)
    // b: stessa dimensione ma contenuto diverso (impronta rapida diversa); a: dimensione diversa dal timbro dell'elenco.
    val other = Random(99).nextBytes(changedSize.bytes.size)
    rig.inputs.opens[changedSize.item.contentKey.hex] = PrepOpen.Ready(PrepareSource.Bytes(ByteArrayMediaBytes(other), MediaKind.PHOTO, null)) { rig.closed.incrementAndGet() }
    rig.inputs.opens[a.item.contentKey.hex] = PrepOpen.Ready(PrepareSource.Bytes(ByteArrayMediaBytes(a.bytes + 1), MediaKind.PHOTO, null)) { rig.closed.incrementAndGet() }
    val preparer = ScriptedPreparer { source, _ ->
      val bytes = bytesOf(source)
      PrepResult.Done(images(SegmentClass.THUMB_S), Sha256.of(bytes), DecodedFacts(10, 10), null, QuickHashAlgorithm.ofContent(bytes))
    }

    val result = rig.runner(preparer).run(share, 8.minutes, FakeUnitPriority())

    assertThat(result.prepared).isEqualTo(0)
    assertThat(result.failed).isEqualTo(0)
    assertThat(rig.publisher.published).isEmpty()
    // a non e' nemmeno stato preparato (timbro diverso); b si', ma l'esito e' stato scartato (impronta rapida diversa).
    assertThat(preparer.calls.get()).isEqualTo(1)
    assertThat(rig.closed.get()).isEqualTo(2)
  }

  @Test
  fun unMediaSenzaRecordNelCatalogoSiSalta() = runTest {
    val rig = rig()
    val a = media("2024/a.jpg")
    rig.offer("2024-03", 0, a)
    rig.records.records.clear()
    val preparer = ScriptedPreparer { _, _ -> error("non deve essere chiamato") }
    val result = rig.runner(preparer).run(share, 8.minutes, FakeUnitPriority())
    assertThat(result.prepared).isEqualTo(0)
    assertThat(preparer.calls.get()).isEqualTo(0)
    assertThat(rig.inputs.requests).isEmpty()
  }

  // --- Fallimenti -----------------------------------------------------------------------------------------------------------

  @Test
  fun unDecoderMancantePassaDiManoENonSiRiprovaConLaStessaFamiglia() = runTest {
    val rig = rig()
    val raw = media("2024/a.dng", kind = MediaKind.RAW_PHOTO)
    val seenBefore = media("2024/b.dng", kind = MediaKind.RAW_PHOTO, failedDecoders = setOf("c2_me"))
    val other = media("2024/c.dng", kind = MediaKind.RAW_PHOTO, failedDecoders = setOf("c2_altro"))
    rig.offer("2024-03", 0, raw, seenBefore, other)
    val preparer = ScriptedPreparer { _, _ -> PrepResult.Failed(PrepFailCode.DECODER_MISSING, retryable = false, decoderFamily = "c2_me") }

    val result = rig.runner(preparer).run(share, 8.minutes, FakeUnitPriority())

    // Il media gia' fallito dalla nostra famiglia non si apre nemmeno; quello fallito da un'altra si prova una volta.
    assertThat(rig.inputs.requests.map { it.contentKey }).containsExactly(raw.item.contentKey, other.item.contentKey)
    assertThat(result.handedOver).isEqualTo(2)
    assertThat(result.failed).isEqualTo(2)
    val records = rig.publisher.published.single().records.map { it as MetaRecord.Present }
    assertThat(records).hasSize(2)
    for (record in records) {
      assertThat(record.file.failure!!.code).isEqualTo("DECODER_MISSING")
      assertThat(record.file.failure!!.decoder).isEqualTo("c2_me")
      assertThat(record.file.prepared).isEqualTo(0)
    }
    assertThat(rig.publisher.published.single().blobs).isEmpty()
  }

  @Test
  fun r2_conPocoSpazioSulTelefonoLaPreparazioneAspettaSenzaAprireNiente() = runTest {
    val rig = rig()
    val m = media("2024/a.jpg")
    rig.offer("2024-03", 0, m)
    val preparer = ScriptedPreparer { source, _ -> done(m.item, bytesOf(source)) }
    val free = java.util.concurrent.atomic.AtomicLong(dev.pampa.mycloudgallery.core.prepare.contract.PrepStorage.MIN_FREE_BYTES - 1)

    val result = rig.runner(preparer, storage = { free.get() }).run(share, 8.minutes, FakeUnitPriority())

    // Sotto 1 GiB libero (R2 della revisione 4A) non si apre ne' si prepara niente: il giro si ferma, non e' un errore.
    assertThat(result.stoppedBy).isEqualTo(PrepStop.STORAGE)
    assertThat(result.prepared).isEqualTo(0)
    assertThat(result.failed).isEqualTo(0)
    assertThat(rig.inputs.requests).isEmpty()

    // Con lo spazio tornato si riprende.
    free.set(dev.pampa.mycloudgallery.core.prepare.contract.PrepStorage.MIN_FREE_BYTES * 2)
    val again = rig.runner(preparer, storage = { free.get() }).run(share, 8.minutes, FakeUnitPriority())
    assertThat(again.prepared).isEqualTo(1)
  }

  @Test
  fun unFileRovinatoSiRiprovaCinqueVolteEPoiSiPubblicaNonLeggibile() = runTest {
    val rig = rig()
    val bad = media("2024/rotto.jpg")
    rig.register(bad)
    val attempts = InMemoryPrepAttempts()
    val preparer = ScriptedPreparer { _, _ -> PrepResult.Failed(PrepFailCode.DECODE_FAILED, retryable = false) }
    for (round in 1..PrepAttempts.MAX_ATTEMPTS) {
      // A ogni giro il media torna in un'unita' nuova (il mese e' lo stesso, la parte cambia).
      rig.units.offer(WorkUnitKey(share, WorkPass.PREP, "2024-03", round), listOf(bad.item))
      val result = rig.runner(preparer, attempts = attempts).run(share, 8.minutes, FakeUnitPriority())
      assertThat(result.failed).isEqualTo(1)
      assertThat(attempts.countOf(share, bad.item)).isEqualTo(round)
      if (round < PrepAttempts.MAX_ATTEMPTS) {
        assertThat(rig.publisher.published).isEmpty()
      }
    }
    // Alla quinta il fallimento e' pubblicato (E-M10): "non leggibile", mai bloccante.
    val record = rig.publisher.published.single().records.single() as MetaRecord.Present
    assertThat(record.file.failure!!.code).isEqualTo("DECODE_FAILED")
  }

  @Test
  fun unaFotoOltreIlTettoSiPubblicaComeTroppoGrandeSenzaRiprovare() = runTest {
    val rig = rig()
    val big = media("2024/enorme.jpg")
    rig.offer("2024-03", 0, big)
    val result = rig.runner(ScriptedPreparer { _, _ -> PrepResult.Failed(PrepFailCode.TOO_LARGE, retryable = false) }).run(share, 8.minutes, FakeUnitPriority())
    assertThat(result.failed).isEqualTo(1)
    assertThat(((rig.publisher.published.single().records.single()) as MetaRecord.Present).file.failure!!.code).isEqualTo("TOO_LARGE")
  }

  @Test
  fun ilNasCheNonRispondeFermaIlGiroSenzaContareIlTentativo() = runTest {
    val rig = rig()
    val a = media("2024/a.jpg")
    val b = media("2024/b.jpg")
    val c = media("2024/c.jpg")
    rig.offer("2024-03", 0, a, b, c)
    val attempts = InMemoryPrepAttempts()
    val preparer = ScriptedPreparer { _, _ -> PrepResult.Failed(PrepFailCode.NAS_UNREACHABLE, retryable = true) }
    val result = rig.runner(preparer, limits = PrepBudget(256, 1), attempts = attempts).run(share, 8.minutes, FakeUnitPriority())

    assertThat(result.stoppedBy).isEqualTo(PrepStop.UNAVAILABLE)
    assertThat(result.failed).isEqualTo(0)
    assertThat(attempts.countOf(share, a.item)).isEqualTo(0)
    // Con un solo media alla volta, dopo il primo errore gli altri non si cominciano.
    assertThat(preparer.calls.get()).isEqualTo(1)
    assertThat(rig.units.finished.single().second).isEqualTo(UnitOutcome.Interrupted("NAS_UNREACHABLE"))
    // L'apertura che non riesce vale lo stesso.
    val rig2 = rig()
    val d = media("2024/d.jpg")
    rig2.offer("2024-03", 0, d)
    rig2.inputs.opens[d.item.contentKey.hex] = PrepOpen.Unavailable(PrepFailCode.NAS_UNREACHABLE)
    assertThat(rig2.runner(ScriptedPreparer { _, _ -> error("no") }).run(share, 8.minutes, FakeUnitPriority()).stoppedBy).isEqualTo(PrepStop.UNAVAILABLE)
  }

  @Test
  fun unaEccezioneDiUnMediaNonFermaLUnita() = runTest {
    val rig = rig()
    val a = media("2024/a.jpg")
    val b = media("2024/b.jpg")
    rig.offer("2024-03", 0, a, b)
    val preparer = ScriptedPreparer { source, _ ->
      val bytes = bytesOf(source)
      if (bytes.contentEquals(a.bytes)) error("boom")
      done(b.item, bytes)
    }
    val result = rig.runner(preparer, limits = PrepBudget(256, 1)).run(share, 8.minutes, FakeUnitPriority())
    assertThat(result.prepared).isEqualTo(1)
    assertThat(result.failed).isEqualTo(1)
  }

  // --- Tappe, regole, prenotazioni --------------------------------------------------------------------------------------

  @Test
  fun alPiuDueMediaInsiemeEUnoSoloSeLeRegoleDiconoPiano() = runTest(timeout = 60.seconds) {
    val fast = rig()
    val medias = (0 until 6).map { media("2024/m$it.jpg") }
    fast.offer("2024-03", 0, *medias.toTypedArray())
    val parallel = ScriptedPreparer { source, _ ->
      delay(50)
      done(medias.first { it.bytes.contentEquals(bytesOf(source)) }.item, bytesOf(source))
    }
    fast.runner(parallel, limits = PrepBudget(256, 8)).run(share, 8.minutes, FakeUnitPriority())
    assertThat(parallel.peak.get()).isEqualTo(2)

    val slow = rig()
    val medias2 = (10 until 14).map { media("2024/s$it.jpg") }
    slow.offer("2024-03", 0, *medias2.toTypedArray())
    val sequential = ScriptedPreparer { source, _ ->
      delay(50)
      done(medias2.first { it.bytes.contentEquals(bytesOf(source)) }.item, bytesOf(source))
    }
    val result = slow.runner(sequential, control = ScriptedPrepControl(PrepVerdict.Slow)).run(share, 8.minutes, FakeUnitPriority())
    assertThat(result.prepared).isEqualTo(4)
    assertThat(sequential.peak.get()).isEqualTo(1)

    // Con un solo processore e' sempre uno solo.
    val single = rig()
    val medias3 = (20 until 24).map { media("2024/u$it.jpg") }
    single.offer("2024-03", 0, *medias3.toTypedArray())
    val one = ScriptedPreparer { source, _ ->
      delay(50)
      done(medias3.first { it.bytes.contentEquals(bytesOf(source)) }.item, bytesOf(source))
    }
    single.runner(one, limits = PrepBudget(256, 2)).run(share, 8.minutes, FakeUnitPriority())
    assertThat(one.peak.get()).isEqualTo(1)
  }

  @Test
  fun unaTappaDaOttoMinutiSiFermaEIlLavoroFattoEGiaNellOutbox() = runTest {
    val rig = rig()
    val medias = (0 until 5).map { media("2024/m$it.jpg") }
    rig.offer("2024-03", 0, *medias.toTypedArray())
    val clock = ManualPrepClock()
    val preparer = ScriptedPreparer { source, _ ->
      // Ogni media "costa" 3 minuti di orologio monotono.
      clock.advanceMillis(3 * 60_000)
      done(medias.first { it.bytes.contentEquals(bytesOf(source)) }.item, bytesOf(source))
    }
    val result = rig.runner(preparer, limits = PrepBudget(256, 1), clock = clock).run(share, 8.minutes, FakeUnitPriority())

    assertThat(result.stoppedBy).isEqualTo(PrepStop.BUDGET)
    assertThat(result.prepared).isEqualTo(3)
    assertThat(rig.units.finished.single().second).isEqualTo(UnitOutcome.Interrupted("BUDGET"))
    // Quello che era pronto e' stato messo nell'outbox e pubblicato a fine unita'.
    assertThat(rig.publisher.published.single().records).hasSize(3)
  }

  @Test
  fun leRegoleCheDiconoFermatiFermanoIlGiroDopoIlMediaInCorso() = runTest {
    val rig = rig()
    val medias = (0 until 4).map { media("2024/m$it.jpg") }
    rig.offer("2024-03", 0, *medias.toTypedArray())
    val control = ScriptedPrepControl(PrepVerdict.Continue, PrepVerdict.Continue, PrepVerdict.Continue, PrepVerdict.Stop(PrepStop.RULES))
    val preparer = ScriptedPreparer { source, _ -> done(medias.first { it.bytes.contentEquals(bytesOf(source)) }.item, bytesOf(source)) }
    val result = rig.runner(preparer, limits = PrepBudget(256, 1), control = control).run(share, 8.minutes, FakeUnitPriority())
    assertThat(result.stoppedBy).isEqualTo(PrepStop.RULES)
    assertThat(result.prepared).isEqualTo(2)
    assertThat(rig.units.finished.single().second).isEqualTo(UnitOutcome.Interrupted("RULES"))
  }

  @Test
  fun laPrenotazioneSiRinnovaOgniVentiMinutiESePersaSiLasciaLUnita() = runTest {
    val rig = rig()
    val first = (0 until 3).map { media("2024/a$it.jpg") }
    rig.offer("2024-03", 0, *first.toTypedArray())
    val second = media("2024/z.jpg")
    rig.offer("2024-02", 0, second)
    val clock = ManualPrepClock()
    val all = first + second
    val preparer = ScriptedPreparer { source, _ ->
      clock.advanceMillis(15 * 60_000)
      done(all.first { it.bytes.contentEquals(bytesOf(source)) }.item, bytesOf(source))
    }
    rig.runner(preparer, limits = PrepBudget(256, 1), clock = clock).run(share, 600.minutes, FakeUnitPriority())
    // 3 media da 15 minuti: rinnovata prima del secondo (15 < 20 no) e del terzo (30 >= 20 si').
    assertThat(rig.units.renewals).isNotEmpty()

    // Prenotazione persa: si finisce il media in corso, l'unita' non si chiude e non e' un arresto del giro.
    val lost = rig()
    val items = (0 until 3).map { media("2024/l$it.jpg") }
    lost.offer("2024-03", 0, *items.toTypedArray())
    lost.offer("2024-02", 0, media("2024/m.jpg"))
    lost.units.loseOnKeepAlive = true
    val clock2 = ManualPrepClock()
    val preparer2 = ScriptedPreparer { source, _ ->
      clock2.advanceMillis(25 * 60_000)
      val bytes = bytesOf(source)
      PrepResult.Done(images(SegmentClass.THUMB_S), Sha256.of(bytes), DecodedFacts(10, 10), null, QuickHashAlgorithm.ofContent(bytes))
    }
    val result = lost.runner(preparer2, limits = PrepBudget(256, 1), clock = clock2).run(share, 600.minutes, FakeUnitPriority())
    assertThat(result.units).isEqualTo(2)
    // Mai `finish` sull'unita' persa (e' di un altro).
    assertThat(lost.units.finished.map { it.first.month }).doesNotContain("2024-03")
  }

  @Test
  fun annullareIlGiroLasciaLUnitaECompletaLaPubblicazione() = kotlinx.coroutines.runBlocking {
    val rig = rig()
    val a = media("2024/a.jpg")
    val b = media("2024/b.jpg")
    rig.offer("2024-03", 0, a, b)
    val preparer = ScriptedPreparer { source, _ ->
      val bytes = bytesOf(source)
      if (bytes.contentEquals(a.bytes)) done(a.item, bytes) else awaitCancellation()
    }
    val job = launch(Dispatchers.Default) {
      rig.runner(preparer, limits = PrepBudget(256, 8)).run(share, 8.minutes, FakeUnitPriority())
    }
    // Si aspetta (in tempo vero) che il primo sia nell'outbox e il secondo in corso.
    kotlinx.coroutines.withTimeout(30.seconds) {
      while (rig.publisher.pending.value.records < 1 || preparer.calls.get() < 2) delay(10)
    }
    job.cancel()
    kotlinx.coroutines.withTimeout(30.seconds) { job.join() }
    assertThat(job.isCancelled).isTrue()
    assertThat(rig.units.finished.single().second).isEqualTo(UnitOutcome.Interrupted("CANCELLED"))
    // Quello gia' preparato non si perde.
    assertThat(rig.publisher.published.flatMap { it.records }).hasSize(1)
    assertThat(rig.closed.get()).isEqualTo(2)
  }

  @Test
  fun ilGiroPrendeLeUnitaFinoAlleFineDelLavoro() = runTest {
    val rig = rig()
    val medias = (0 until 3).map { media("2024/m$it.jpg") }
    rig.offer("2024-03", 0, medias[0])
    rig.offer("2024-02", 0, medias[1])
    rig.offer("2024-01", 0, medias[2])
    val preparer = ScriptedPreparer { source, _ -> done(medias.first { it.bytes.contentEquals(bytesOf(source)) }.item, bytesOf(source)) }
    val result = rig.runner(preparer).run(share, 8.minutes, FakeUnitPriority())
    assertThat(result.units).isEqualTo(3)
    assertThat(result.prepared).isEqualTo(3)
    // Dal mese piu' recente (D12).
    assertThat(rig.units.finished.map { it.first.month }).containsExactly("2024-03", "2024-02", "2024-01").inOrder()
    // Una testa per unita' (flush a fine unita').
    assertThat(rig.publisher.published).hasSize(3)
  }

  // --- Un'unita' che torna uguale ------------------------------------------------------------------------------------------

  /** Le unita' come le restituisce il vero `WorkUnits`: finche' i media mancano, la stessa unita' torna a ogni `next`. */
  private class RepeatingWorkUnits(private val key: WorkUnitKey, private val items: List<WorkItem>) : dev.pampa.mycloudgallery.core.index.contract.WorkUnits {
    val nexts = AtomicInteger()

    override suspend fun next(share: String, pass: WorkPass, priority: dev.pampa.mycloudgallery.core.index.contract.UnitPriority) =
      dev.pampa.mycloudgallery.core.index.contract.UnitClaim(key, items, dev.pampa.mycloudgallery.core.index.contract.UnitLease.Unleased).also { nexts.incrementAndGet() }

    override suspend fun keepAlive(claim: dev.pampa.mycloudgallery.core.index.contract.UnitClaim) =
      dev.pampa.mycloudgallery.core.index.contract.KeepAliveResult.Renewed(claim)

    override suspend fun finish(claim: dev.pampa.mycloudgallery.core.index.contract.UnitClaim, outcome: UnitOutcome) = Unit
  }

  @Test
  fun unFileRovinatoSiProvaUnaVoltaPerGiroEUnUnitaIdenticaNonSiRipete() = runTest {
    val bad = media("2024/rotto.jpg")
    val skipped = media("2024/vecchio.dng", kind = MediaKind.RAW_PHOTO, failedDecoders = setOf("c2_me"))
    val rig = rig()
    rig.register(bad, skipped)
    val units = RepeatingWorkUnits(WorkUnitKey(share, WorkPass.PREP, "2024-03", 0), listOf(bad.item, skipped.item))
    val attempts = InMemoryPrepAttempts()
    val preparer = ScriptedPreparer { _, _ -> PrepResult.Failed(PrepFailCode.DECODE_FAILED, retryable = false) }
    val run = DefaultPreparationRun(units, rig.inputs, preparer, rig.publisher, rig.records, PrepBudget(256, 8), "c2_me", attempts = attempts)

    val result = run.run(share, 8.minutes, FakeUnitPriority())

    // Il primo passaggio conta un tentativo; il secondo trova gli stessi media (provati o da saltare) e non fa niente: il giro finisce.
    assertThat(attempts.countOf(share, bad.item)).isEqualTo(1)
    assertThat(preparer.calls.get()).isEqualTo(1)
    assertThat(result.stoppedBy).isEqualTo(PrepStop.NO_WORK)
    assertThat(units.nexts.get()).isEqualTo(2)
    assertThat(result.failed).isEqualTo(1)
  }

  // --- La fusione del record ------------------------------------------------------------------------------------------------

  @Test
  fun laFusioneCompletaIlRecordSenzaInventareNiente() {
    val m = media("2024/a.jpg")
    val failedBefore = m.base.copy(failure = dev.pampa.mycloudgallery.core.trust.data.PrepFailure("DECODER_MISSING", "c2_altro"), prepared = SegmentClass.THUMB_S.mask)
    val merged = PrepRecords.merge(failedBefore, done(m.item, m.bytes, setOf(SegmentClass.THUMB_M, SegmentClass.HQ)))
    assertThat(merged.failure).isNull()
    assertThat(merged.preparedClasses).containsExactly(SegmentClass.THUMB_S, SegmentClass.THUMB_M, SegmentClass.HQ)
    assertThat(merged.fullHash).isEqualTo(Sha256.of(m.bytes))
    assertThat(merged.mediaId).isEqualTo(m.base.mediaId)
    assertThat(merged.stamp).isEqualTo(m.base.stamp)
    assertThat(merged.quickHash).isEqualTo(m.base.quickHash)
    // Senza fatti nuovi restano quelli della scansione.
    val noFacts = PrepRecords.merge(m.base.copy(width = 10, height = 20), PrepResult.Done(emptyList(), null, null, null))
    assertThat(noFacts.width to noFacts.height).isEqualTo(10 to 20)
  }
}
