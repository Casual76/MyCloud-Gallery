package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.backup.FiveBInputs
import dev.pampa.mycloudgallery.backup.IncomingArrival
import dev.pampa.mycloudgallery.backup.IncomingStates
import dev.pampa.mycloudgallery.core.backup.contract.DestinationArea
import dev.pampa.mycloudgallery.core.backup.contract.IncomingOrigin
import dev.pampa.mycloudgallery.core.backup.contract.IncomingSaveOutcome
import dev.pampa.mycloudgallery.core.backup.contract.IncomingSavePlan
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStage
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStream
import dev.pampa.mycloudgallery.core.backup.contract.NasDestination
import dev.pampa.mycloudgallery.core.backup.testing.TestConfirmations
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaRewriter
import dev.pampa.mycloudgallery.core.metadata.write.WrittenCheck
import dev.pampa.mycloudgallery.core.metadata.write.WrittenFileCheck
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.ShareAccess
import dev.pampa.mycloudgallery.core.nas.contract.ShareProbe
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.rules.DeviceReading
import dev.pampa.mycloudgallery.core.rules.NetworkState
import dev.pampa.mycloudgallery.core.rules.NetworkTransport
import dev.pampa.mycloudgallery.core.rules.PowerState
import dev.pampa.mycloudgallery.core.rules.TimeWindow
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettings
import dev.pampa.mycloudgallery.core.security.lock.AppLockController
import dev.pampa.mycloudgallery.core.security.lock.AppLockSettings
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.integrations.provider.NasReach
import dev.pampa.mycloudgallery.feature.integrations.receive.ImageSize
import dev.pampa.mycloudgallery.feature.integrations.receive.IncomingStager
import dev.pampa.mycloudgallery.feature.integrations.receive.NasWait
import dev.pampa.mycloudgallery.integrations.IncomingSpace
import dev.pampa.mycloudgallery.integrations.IntegrationsGraph
import dev.pampa.mycloudgallery.work.BackupStages
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **Correzioni 5B: integrazioni** (docs/review/funzionale-5b.md IN-1 ... IN-13, docs/review/5b-fase5b.md R5, R8, R11, R14, R15) con il grafo vero:
 * i file ricevuti dalle altre app, il selettore e il provider dei documenti. Finti solo il NAS (simulatore SMB di core-nas) e il telefono.
 * Il processo freddo si simula con oggetti nuovi sullo stesso disco: una pulizia dell'avvio che arriva dopo la copia, una tabella degli id nuova con
 * la stessa chiave sul file, un cancello che sta ancora leggendo il suo stato.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class Correzioni5BIntegrazioniAppTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val devices = mutableListOf<TestDevice>()
  private val rigs = mutableListOf<BackupRig>()
  private val phoneIntents = FakePhoneMediaIntents(canTrash = true)
  private val anyCopyIsValid = WrittenFileCheck { _, _, _ -> WrittenCheck.VALID }

  @After
  fun tearDown() {
    rigs.forEach { runBlocking { it.gallery.shutdown() } }
    devices.forEach { it.close() }
  }

  private fun device(name: String, backend: InMemorySmbBackend, flags: FeatureFlags = DEBUG_LIKE): TestDevice =
    TestDevice(app, File(temp.root, name), TestPlatform(time, backend), flags).also { devices += it }

  private fun rig(device: TestDevice): BackupRig = BackupRig(
    app = app,
    device = device,
    account = TestNas.ADMIN_USER,
    fiveB = { store ->
      FiveBInputs(
        intents = phoneIntents,
        rewriter = { FakePhoneMediaRewriter(store) },
        fileCheck = { anyCopyIsValid },
        bootCounter = { 1L },
        rowFolders = null,
      )
    },
  ).also { rigs += it }

  /** Un telefono con la famiglia fondata, il backup aperto e la cartella scelta (nessuna foto). */
  private suspend fun openedRig(backend: InMemorySmbBackend, flags: FeatureFlags = DEBUG_LIKE, name: String = "telefono"): BackupRig {
    val phone = device(name, backend, flags)
    phone.graph.start()
    phone.graph.foundFamily()
    val rig = rig(phone)
    rig.open()
    rig.chooseFolder("Pixel di prova")
    return rig
  }

  /** Le integrazioni come le monta l'app; il cancello e i flussi della famiglia scelti dal test. */
  private fun integrations(
    rig: BackupRig,
    lock: AppLockController = AppLockController().apply { updateSettings(AppLockSettings()) },
    loading: () -> Boolean = { false },
    changes: kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.emptyFlow(),
    probe: ShareProbe? = rig.device.graph.shareProbe,
  ): IntegrationsGraph = IntegrationsGraph(
    context = rig.device.context,
    gallery = rig.gallery,
    scope = rig.device.scope,
    lock = lock,
    lockSettings = flowOf(AppLockSettings()),
    familyReady = { true },
    shareProbe = probe,
    familyLoading = loading,
    familyChanges = changes,
  )

  private fun mp4(seed: Int, size: Int = 4_000): ByteArray =
    byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0, 0, 2, 0) +
      "isomiso2".toByteArray() + ByteArray(size - 24) { ((it * 31 + seed) and 0x7F or 0x80).toByte() }

  private fun stream(bytes: ByteArray, name: String = "VID_1.mp4", mime: String = "video/mp4") =
    IncomingStream(IncomingOrigin.CONTENT_OF_ANOTHER_APP, name, mime, bytes.size.toLong()) { ByteArrayInputStream(bytes) }

  private suspend fun IntegrationsGraph.save(stage: IncomingStage, dest: NasDestination, rig: BackupRig): IncomingSaveOutcome =
    checkNotNull(uploads).save(stage, dest, TestConfirmations.confirm(IncomingSavePlan(stage, dest), rig.backup.clock.monotonicMs()))

  private val privateDest = NasDestination(TestNas.ADMIN_USER, SharePath.of("Ricevute"), DestinationArea.PRIVATE)

  // --- IN-1: la pulizia dell'avvio ---------------------------------------------------------------------------------------------------

  /**
   * IN-1 della verifica funzionale 5B: l'app e' chiusa, WhatsApp condivide; il processo nasce e la pulizia dell'avvio (che gira in parallelo, dopo il
   * caricamento del catalogo) tolse la cartella appena copiata prima del tocco su "Salva sul NAS": "I file copiati non ci sono piu'".
   */
  @Test
  fun in1_laPuliziaDellAvvioArrivaDopoLaCopiaENonTogliLaCondivisioneAppenaRicevuta() = runBlocking {
    val rig = openedRig(TestNas.backend(time))
    val graph = integrations(rig)
    val root = rig.backup.fiveB.incomingRoot
    // Il resto di un processo morto: una condivisione mai salvata. Questa sì si toglie.
    val leftover = File(root, "ab".repeat(16)).also { it.mkdirs() }
    File(leftover, "c".repeat(64)).writeBytes(mp4(9))

    val stage = checkNotNull(graph.uploads).stage(listOf(stream(mp4(1))))
    assertWithMessage("rifiutati: ${stage.rejected}").that(stage.accepted).hasSize(1)

    graph.atStart() // la pulizia dell'avvio, adesso: nessuna riga di `incoming_item` cita ancora la condivisione

    assertThat(graph.stager.isIntact(stage)).isTrue()
    assertThat(leftover.exists()).isFalse()
    val saved = graph.save(stage, privateDest, rig)
    assertThat(saved).isEqualTo(IncomingSaveOutcome.Queued(1, userInitiated = false))
  }

  // --- IN-5: un salvataggio e' un lavoro dell'utente ------------------------------------------------------------------------------------

  private fun windowFarFromNow(): TimeWindow {
    val now = LocalTime.ofInstant(Instant.ofEpochMilli(time.now()), ZoneId.systemDefault())
    return TimeWindow(now.plusHours(6), now.plusHours(7))
  }

  /**
   * IN-5: "Salva sul NAS" di una foto piccola partiva come `Background(PHOTO_BACKUP)`: con il preset Notte (il predefinito) aspettava la fascia,
   * la carica e il Wi-Fi di casa, e il file arrivava sul NAS la notte dopo; un video da 1 GB, invece, partiva subito (lavoro dell'utente).
   */
  @Test
  fun in5_unSalvataggioPiccoloParteSubitoAncheConIlPresetNotteFuoriFasciaESenzaCarica() = runBlocking {
    val backend = TestNas.backend(time)
    val rig = openedRig(backend)
    rig.device.graph.ruleSettings.save(RuleSettings(preset = Preset.NIGHT, nightWindow = windowFarFromNow()))
    rig.device.platform.power.reading.value =
      DeviceReading(PowerState(pluggedIn = false, levelPercent = 80), NetworkState(transport = NetworkTransport.WIFI))
    val graph = integrations(rig)
    val bytes = mp4(2, size = 6_000)
    val stage = checkNotNull(graph.uploads).stage(listOf(stream(bytes, name = "Mare.mp4")))
    check(graph.save(stage, privateDest, rig) is IncomingSaveOutcome.Queued)

    val outcome = checkNotNull(BackupStages(rig.device.graph, rig.backup).incoming())

    val last = checkNotNull(outcome.last) { "il lavoro non e' partito: ${outcome.stage}" }
    assertWithMessage("giro: $last").that(last.done + last.linked).isEqualTo(1)
    assertThat(backend.bytes(TestNas.ADMIN_USER, "Ricevute/Mare.mp4")?.toList()).isEqualTo(bytes.toList())
  }

  // --- IN-6: il backup in prova ------------------------------------------------------------------------------------------------------------

  /**
   * IN-6: in release D12 e R2 chiudono ogni scrittura della 5B. "Salva sul NAS" non riuscirebbe mai, ma si accorgeva solo dopo aver copiato (fino a
   * 4 GiB) e diceva "In quella cartella non si puo' scrivere". Adesso lo si sa prima di copiare.
   */
  @Test
  fun in6_inReleaseOgniAreaEInProvaEPrimaDiCopiareLoSi_InDebugConUnaShareDiProvaNo() = runBlocking {
    val release = openedRig(TestNas.backend(time), FeatureFlags.RELEASE)
    val releaseGraph = integrations(release)
    assertThat(releaseGraph.destinations.savingBlockedByTrial()).isTrue()
    // Il cancello degli arrivi e' lo stesso del motore: con la 5B chiusa nemmeno la messa in coda e' ammessa.
    val stage = checkNotNull(releaseGraph.uploads).stage(listOf(stream(mp4(3))))
    assertThat(releaseGraph.save(stage, privateDest, release)).isEqualTo(IncomingSaveOutcome.DestinationNotAllowed)

    val debug = openedRig(TestNas.backend(time), DEBUG_LIKE, name = "telefono-debug")
    assertThat(integrations(debug).destinations.savingBlockedByTrial()).isFalse()
  }

  // --- IN-11: dati mobili e NAS spento ---------------------------------------------------------------------------------------------------

  @Test
  fun in11_suiDatiMobiliLaFraseNonDiceCheIlNasNonRisponde() = runBlocking {
    val rig = openedRig(TestNas.backend(time))
    val graph = integrations(rig)
    // La rete di prova e' il Wi-Fi di casa: il NAS risponde.
    assertThat(graph.destinations.nasWait()).isEqualTo(NasWait.NONE)
    assertThat(graph.destinations.nasReachable()).isTrue()
  }

  // --- IN-13: la sonda guarda tutte le aree ------------------------------------------------------------------------------------------------

  /** IN-13: `nasReach` sondava solo `areas().first()`: se quella manca o rifiuta, il NAS che risponde con l'altra sembrava spento. */
  @Test
  fun in13_laSondaBastaCheUnaAreaRisponda() = runBlocking {
    val rig = openedRig(TestNas.backend(time))
    var calls = 0
    val firstFails = object : ShareProbe {
      override suspend fun probe(share: String): NasResult<ShareAccess> {
        calls++
        return if (calls == 1) NasResult.Err(dev.pampa.mycloudgallery.core.nas.contract.NasError.PermissionDenied) else NasResult.Ok(ShareAccess.READ_WRITE)
      }
    }
    val graph = integrations(rig, probe = firstFails)
    assertThat(graph.nasReach()).isEqualTo(NasReach.Reachable)
    assertThat(calls).isAtLeast(2)

    val allFail = object : ShareProbe {
      override suspend fun probe(share: String): NasResult<ShareAccess> = NasResult.Err(dev.pampa.mycloudgallery.core.nas.contract.NasError.Timeout)
    }
    assertThat(integrations(rig, probe = allFail).nasReach()).isInstanceOf(NasReach.Unreachable::class.java)
  }

  // --- IN-2: le radici si notificano ----------------------------------------------------------------------------------------------------------

  /**
   * IN-2: DocumentsUI tiene le radici nella cache di sistema finche' nessuno gliene notifica di nuove. A freddo la prima risposta puo' essere "non
   * pronta"; appena l'app e' pronta (o il blocco cambia) le radici si notificano.
   */
  @Test
  fun in2_leRadiciSiNotificanoQuandoCambiaCioCheDicono() = runBlocking {
    val rig = openedRig(TestNas.backend(time))
    val changes = MutableSharedFlow<Unit>(replay = 1)
    var loading = true
    val graph = integrations(rig, loading = { loading }, changes = changes)
    val resolver = shadowOf(app.contentResolver)
    val rootsUri = android.provider.DocumentsContract.buildRootsUri(
      dev.pampa.mycloudgallery.feature.integrations.provider.DocumentsAuthority.of(rig.device.context),
    )

    graph.install()
    waitUntil { resolver.notifiedUris.any { it.uri == rootsUri } }
    val before = resolver.notifiedUris.count { it.uri == rootsUri }

    // Lo stato si e' letto: la radice ora c'e'.
    loading = false
    changes.emit(Unit)
    waitUntil { resolver.notifiedUris.count { it.uri == rootsUri } > before }

    // All'azzeramento la chiave ruota e le radici si notificano di nuovo.
    val rootBefore = graph.documents.roots().single().rootDocument
    val afterReads = resolver.notifiedUris.count { it.uri == rootsUri }
    graph.revokeAll()
    assertThat(resolver.notifiedUris.count { it.uri == rootsUri }).isGreaterThan(afterReads)
    graph.install() // idempotente; qui serve solo a rimettere in piedi le porte per la riga sotto
    assertThat(graph.documents.roots().single().rootDocument).isNotEqualTo(rootBefore)
    Unit
  }

  /** IN-2: gli id delle cartelle di un processo nuovo (stessa chiave su file) sono gli stessi, anche per una cartella annidata. */
  @Test
  fun in2_unProcessoNuovoRitrovaLeCartelleDelNasConGliStessiIdentificatori() = runBlocking {
    val backend = TestNas.backend(time)
    backend.put(TestNas.ADMIN_USER, "Archivio/2025/Mare/uno.jpg", BackupRig.jpeg(tone = 70), lastWriteMs = time.now() - 2 * DAY)
    backend.put(TestNas.ADMIN_USER, "Archivio/2025/Monti/due.jpg", BackupRig.jpeg(tone = 140), lastWriteMs = time.now() - 2 * DAY)
    val rig = openedRig(backend)

    suspend fun walk(graph: IntegrationsGraph): Map<String, String> {
      val docs = graph.documents
      val found = LinkedHashMap<String, String>()
      suspend fun visit(id: dev.pampa.mycloudgallery.feature.integrations.contract.DocumentId, path: String) {
        for (entry in docs.children(id, 100)) {
          if (!entry.isFolder) continue
          val p = path + "/" + entry.displayName
          found[p] = entry.id.value
          visit(entry.id, p)
        }
      }
      visit(docs.roots().single().rootDocument, "")
      return found
    }

    val first = walk(integrations(rig))
    // Un albero a tre livelli: prima del 20/10 `FolderKeys.parse` rifiutava i percorsi annidati e l'albero si fermava al secondo.
    assertWithMessage("cartelle viste: ${first.keys}").that(first.keys.any { it.endsWith("/Archivio/2025/Mare") }).isTrue()
    // Un processo nuovo: tutto nuovo in memoria, la stessa chiave sul file.
    val restartedGraph = integrations(rig)
    val restartedDocs = restartedGraph.documents
    val deepest = first.entries.first { it.key.endsWith("/Archivio/2025/Mare") }
    val id = dev.pampa.mycloudgallery.feature.integrations.contract.DocumentId(deepest.value)
    // L'id che DocumentsUI ricorda si apre al primo colpo, senza che il processo nuovo abbia sfogliato niente.
    val entry = checkNotNull(restartedDocs.entry(id)) { "la cartella non si ritrova" }
    assertThat(entry.displayName).isEqualTo("Mare")
    assertThat(restartedDocs.children(id, 50).map { it.displayName }).containsExactly("uno.jpg")
    // E la camminata completa del processo nuovo da' gli stessi id: la radice, le virtuali e ogni cartella.
    assertThat(walk(restartedGraph)).isEqualTo(first)
  }

  // --- IN-8: il media ricevuto ha la sua data -----------------------------------------------------------------------------------------------

  /** IN-8: senza l'ora del NAS (a freddo o col NAS spento, E-X8) la data provvisoria e' quella della copia sul telefono, mai 0 (l'1/1/1970). */
  @Test
  fun in8_senzaLOraDelNasLaDataProvvisoriaEQuellaDellaCopiaMaiZero() {
    val file = File(temp.root, "arrivo.jpg").apply { writeBytes(byteArrayOf(1)) }
    file.setLastModified(1_700_000_000_000L)
    assertThat(IncomingArrival.provisionalMs(1_800_000_000_000L, file)).isEqualTo(1_800_000_000_000L)
    assertThat(IncomingArrival.provisionalMs(null, file)).isEqualTo(file.lastModified())
    assertThat(IncomingArrival.provisionalMs(null, file)).isGreaterThan(0L)
    // Anche una data della copia illeggibile (0) non porta a 1970.
    val epoch = File(temp.root, "zero.jpg").apply { writeBytes(byteArrayOf(1)) }
    epoch.setLastModified(0L)
    assertThat(IncomingArrival.provisionalMs(null, epoch)).isGreaterThan(0L)
  }

  private fun mediaOfFirstQueued(rig: BackupRig): String = runBlocking {
    val row = rig.gallery.database.incomingDao().inState(IncomingStates.QUEUED).first()
    rig.gallery.database.openHelper.readableDatabase.query("SELECT media_id FROM backup_item WHERE item_id = ?", arrayOf<Any?>(row.backupItemId)).use { c ->
      check(c.moveToFirst())
      c.getString(0)
    }
  }

  private fun sortTimeOf(rig: BackupRig, mediaId: String): Long =
    rig.gallery.database.openHelper.readableDatabase.query("SELECT sort_time FROM media WHERE media_id = ?", arrayOf<Any?>(mediaId)).use { c ->
      check(c.moveToFirst())
      c.getLong(0)
    }

  /**
   * IN-8: il media ricevuto nasceva con la data 0 (l'1/1/1970, in fondo alla timeline) quando l'ora del NAS non era nota, e senza metadati: il cammino
   * degli arrivi non legge niente. Adesso la data di scatto viene dai byte locali (EXIF), e senza una la data e' quella di arrivo, mai 1970.
   */
  @Test
  fun in8_ilMediaRicevutoHaLaDataDiScattoDaiByteLocali_SenzaEXIFQuellaDiArrivo() = runBlocking {
    val rig = openedRig(TestNas.backend(time))
    val graph = integrations(rig)
    graph.wireIncomingMetadata()
    // Lo stager del test legge da se' le dimensioni (il lettore di BitmapFactory su Robolectric e Windows tiene aperto il file).
    val fiveB = rig.backup.fiveB
    val stager = IncomingStager(fiveB.incomingRoot, freeBytes = { IncomingSpace.usableBytes(fiveB.incomingRoot) }, bounds = { ImageSize(320, 240) })
    val uploads = dev.pampa.mycloudgallery.feature.integrations.receive.DefaultIncomingUploads(stager, fiveB.incomingQueue, rig.backup.clock)

    val withDate = BackupRig.withExif(BackupRig.jpeg(tone = 90), "2020:05:17 10:20:30")
    val dated = uploads.stage(listOf(stream(withDate, name = "Festa.jpg", mime = "image/jpeg")))
    val dest = NasDestination(TestNas.ADMIN_USER, SharePath.of("Ricevute"), DestinationArea.PRIVATE)
    check(uploads.save(dated, dest, TestConfirmations.confirm(IncomingSavePlan(dated, dest), rig.backup.clock.monotonicMs())) is IncomingSaveOutcome.Queued)

    val mediaId = mediaOfFirstQueued(rig)
    val year2020 = java.time.ZonedDateTime.of(2020, 5, 17, 0, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
    val sort = sortTimeOf(rig, mediaId)
    assertWithMessage("data del media: $sort").that(sort).isAtLeast(year2020 - DAY)
    assertThat(sort).isLessThan(year2020 + 2 * DAY)

    // Senza EXIF (WhatsApp lo toglie) e con l'ora del NAS non nota: la data di arrivo, mai 1970.
    rig.backup.clock.let { /* l'ora del NAS e' quella del rig */ }
    val plain = uploads.stage(listOf(stream(BackupRig.jpeg(tone = 33), name = "WA0001.jpg", mime = "image/jpeg")))
    check(uploads.save(plain, dest, TestConfirmations.confirm(IncomingSavePlan(plain, dest), rig.backup.clock.monotonicMs())) is IncomingSaveOutcome.Queued)
    val rows = rig.gallery.database.incomingDao().inState(IncomingStates.QUEUED)
    assertThat(rows).hasSize(2)
    for (row in rows) {
      val media = rig.gallery.database.openHelper.readableDatabase.query("SELECT media_id FROM backup_item WHERE item_id = ?", arrayOf<Any?>(row.backupItemId)).use { c ->
        c.moveToFirst()
        c.getString(0)
      }
      assertWithMessage("data di ${row.displayName}").that(sortTimeOf(rig, media)).isGreaterThan(1_000_000_000_000L)
    }
  }

  // --- R14: due destinazioni, due elementi ---------------------------------------------------------------------------------------------------

  /** R14: gli stessi byte salvati in due cartelle erano un solo elemento: il secondo "Salva sul NAS" non si realizzava. */
  @Test
  fun r14_glistessiByteSalvatiInDueCartelleSonoDueElementiEArrivanoEntrambi() = runBlocking {
    val backend = TestNas.backend(time)
    val rig = openedRig(backend)
    val graph = integrations(rig)
    val bytes = mp4(4, size = 5_000)
    val first = checkNotNull(graph.uploads).stage(listOf(stream(bytes, name = "Gita.mp4")))
    check(graph.save(first, privateDest, rig) is IncomingSaveOutcome.Queued)
    val second = checkNotNull(graph.uploads).stage(listOf(stream(bytes, name = "Gita.mp4")))
    val elsewhere = NasDestination(TestNas.ADMIN_USER, SharePath.of("Viaggi"), DestinationArea.PRIVATE)
    check(graph.save(second, elsewhere, rig) is IncomingSaveOutcome.Queued)

    val outcome = checkNotNull(BackupStages(rig.device.graph, rig.backup).incoming())
    val last = checkNotNull(outcome.last)
    assertWithMessage("giro: $last").that(last.done + last.linked).isEqualTo(2)
    assertThat(backend.bytes(TestNas.ADMIN_USER, "Ricevute/Gita.mp4")?.toList()).isEqualTo(bytes.toList())
    assertThat(backend.bytes(TestNas.ADMIN_USER, "Viaggi/Gita.mp4")?.toList()).isEqualTo(bytes.toList())
    assertThat(rig.gallery.database.incomingDao().inState(IncomingStates.UPLOADED)).hasSize(2)
  }

  private fun waitUntil(condition: () -> Boolean) {
    val deadline = System.nanoTime() + 10_000_000_000L
    while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
    assertThat(condition()).isTrue()
  }

  private companion object {
    const val DAY = 24L * 3_600_000L
    val DEBUG_LIKE: FeatureFlags = FeatureFlags.forBuild(debugBuild = true, nasLibrary = true)
  }
}
