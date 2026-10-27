package dev.pampa.mycloudgallery.integration

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import dev.pampa.mycloudgallery.backup.AppBackupActions
import dev.pampa.mycloudgallery.backup.BackupGraph
import dev.pampa.mycloudgallery.backup.BackupInputs
import dev.pampa.mycloudgallery.backup.FiveBInputs
import dev.pampa.mycloudgallery.core.backup.contract.BackupFence
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.upload.BackupStageOutcome
import dev.pampa.mycloudgallery.core.family.contract.FamilyInvite
import dev.pampa.mycloudgallery.core.family.contract.InviteParams
import dev.pampa.mycloudgallery.core.family.contract.JoinStep
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.contract.StepInput
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.library.similar.SignatureSource
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreRow
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.RowType
import dev.pampa.mycloudgallery.core.mediastore.diagnostics.PhoneLibraryDiagnostics
import dev.pampa.mycloudgallery.core.mediastore.meta.DefaultMetadataPass
import dev.pampa.mycloudgallery.core.mediastore.sync.DefaultPhoneLibrarySync
import dev.pampa.mycloudgallery.core.mediastore.sync.DefaultPhoneSyncRunner
import dev.pampa.mycloudgallery.core.mediastore.sync.DeferredFullHashes
import dev.pampa.mycloudgallery.core.mediastore.sync.PhoneLibraryStats
import dev.pampa.mycloudgallery.core.mediastore.sync.RoomPhoneCatalogReads
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaStore
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneAccessReader
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeRecentlyDeletedSource
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.gallery.GalleryParts
import dev.pampa.mycloudgallery.gallery.PhoneInputs
import dev.pampa.mycloudgallery.gallery.PhoneLibrary
import dev.pampa.mycloudgallery.gallery.PhoneRowsSource
import dev.pampa.mycloudgallery.nas.NasGraph
import dev.pampa.mycloudgallery.nas.NasInputs
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository
import dev.pampa.mycloudgallery.work.BackupJobEnd
import dev.pampa.mycloudgallery.work.BackupJobs
import dev.pampa.mycloudgallery.work.BackupStages
import dev.pampa.mycloudgallery.work.networkChanges
import dev.pampa.mycloudgallery.work.userBackupRun
import kotlinx.coroutines.flow.Flow
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * Un telefono con il backup montato come in produzione (Fase 5A, P7): `GalleryGraph` con il grafo vero del NAS e del backup sopra il
 * MediaStore finto (con i byte delle foto) e il NAS finto di core-nas. Ne esiste uno per dispositivo; il test lo apre, sceglie la
 * cartella e lancia i giri come li lancerebbe un lavoro (`BackupStages`).
 */
internal class BackupRig(
  private val app: Application,
  val device: TestDevice,
  private val account: String,
  val store: FakeMediaStore = newStore(),
  private val jobs: BackupJobs = BackupJobs.NONE,
  private val fence: BackupFence? = null,
  private val signatures: ((MediaCatalog) -> SignatureSource)? = null,
  private val deviceName: String = "Pixel di prova",
  /** L'orologio monotono del backup (turni, riepilogo in cache): i test lo fanno correre per non aspettare minuti veri. */
  private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
  /** Cio' che il grafo del NAS vede dell'app: di norma quello del dispositivo; un test puo' cambiarne l'account (che arriva tardi). */
  private val nasInputs: NasInputs = device.graph.nasInputs,
  /** Fase 5B (P14): i dialoghi, il riscrittore e il controllo del telefono finti sopra [store]; null = quelli di Android (non usati dai test 5A). */
  fiveB: ((FakeMediaStore) -> FiveBInputs)? = null,
  /** Lo stato del dispositivo che la galleria vede: di norma quello del dispositivo; D-7 lo fa passare per "non configurato" all'avvio. */
  setup: FamilySetupRepository = device.graph.setup,
  /**
   * Fase 7 (P13): il database della galleria su file, con questo nome (unico per dispositivo e per prova), perche' sopravviva alla morte del
   * processo (il banco della condivisione); di norma null, in memoria.
   */
  databaseName: String? = null,
) {

  val access = FakePhoneAccessReader(PhoneAccess(MediaAccess.FULL, mediaLocation = true, volumes = setOf(PRIMARY)))

  val gallery: GalleryGraph = GalleryGraph(
    context = device.context,
    scope = device.scope,
    parts = GalleryParts(
      databaseName = databaseName,
      replicaDir = File(device.context.noBackupFilesDir, "replica"),
      preferencesFile = File(device.context.noBackupFilesDir, "library.preferences_pb"),
      access = access,
      intents = FakePhoneMediaIntents(),
      recentlyDeleted = FakeRecentlyDeletedSource(),
      phone = ::phone,
      snapshotDelayMs = 100L,
      nas = nasInputs,
      monotonicMs = monotonicMs,
      backup = BackupInputs(
        settingsStorage = device.graph.backupSettingsStorage,
        ruleSettings = device.graph.ruleSettings,
        shareProbe = device.graph.shareProbe,
        deviceName = { deviceName },
        fence = fence,
        jobs = jobs,
        fiveB = fiveB?.invoke(store),
      ),
      similarSignatures = signatures,
    ),
    setup = setup,
    ownerAccount = { AccountId.of(account) },
    clock = { device.platform.time.now() },
  )

  private fun phone(inputs: PhoneInputs): PhoneLibrary {
    val reads = RoomPhoneCatalogReads(inputs.database)
    val stats = PhoneLibraryStats()
    val meta = DefaultMetadataPass(store, store, access, inputs.catalog, reads, inputs.extractor, clock = inputs.clock, stats = stats)
    val sync = DefaultPhoneLibrarySync(store, access, inputs.catalog, reads, clock = inputs.clock, stats = stats, metadataPass = meta, priority = inputs.priority)
    return PhoneLibrary(
      sync = sync,
      access = access,
      metadataPass = meta,
      runner = DefaultPhoneSyncRunner(sync, meta, inputs.priority),
      stats = stats,
      diagnostics = PhoneLibraryDiagnostics(sync, access, stats) { inputs.catalog.mediaStoreCursors() },
      deferredHashes = DeferredFullHashes(store, access, inputs.catalog, inputs.database),
      rows = PhoneRowsSource(store, store),
    )
  }

  val nas: NasGraph get() = checkNotNull(gallery.nas)

  val backup: BackupGraph get() = checkNotNull(gallery.backup)

  /**
   * Avvia la galleria, il NAS e il backup; legge il telefono (righe e metadati) e fa la verifica completa del NAS: da qui il piano ha
   * l'area privata scansionata (§2.11 punto 1).
   */
  suspend fun open(adoptIndex: Boolean = false): BackupGraph {
    gallery.startWhenReady()
    withTimeout(TIMEOUT_MS) { gallery.started.first { it } }
    check(nas.start()) { "il NAS non parte" }
    check(backup.start()) { "il backup non parte" }
    // Un dispositivo nuovo scarica l'indice del NAS prima della prima scansione: i file che l'indice conosce prendono le identita' degli altri
    // dispositivi (D7), e le scelte personali (preferiti, "Separa") li ritrovano.
    if (adoptIndex) {
      device.graph.trustGuard.preflight(Purpose.BACKGROUND)
      checkNotNull(nas.syncIndex(5.minutes)) { "l'indice non si scarica" }
    }
    readPhone()
    checkNotNull(nas.scan(ScanMode.Full, 5.minutes))
    return backup
  }

  /** Una lettura del telefono, metadati compresi (come `PhoneSyncWorker`). */
  suspend fun readPhone() {
    gallery.core().phone.runner.run(5.minutes)
  }

  suspend fun chooseFolder(name: String) {
    backup.settings.chooseFolder(DeviceFolder(name))
  }

  /** Un giro del motore come lo fa un lavoro: le regole dell'utente (nessuna fascia, nessuna carica) salvo diversa richiesta. */
  suspend fun round(kind: BackupKind = BackupKind.PHOTOS, trigger: BackupTrigger = BackupTrigger.USER_NOW): BackupStageOutcome? =
    BackupStages(device.graph, backup).round(kind, trigger)

  /** Le verifiche e il guardiano come li lancia il lavoro delle verifiche. */
  suspend fun safety() = BackupStages(device.graph, backup).safety()

  /**
   * Il lavoro avviato dall'utente come lo fa `BackupJobService` (`userBackupRun`): foto, poi video, a tappe, dentro `runIfActive`, con l'attesa
   * del processo pronto (D-7) e della rete che torna (D-6, [changes]). Prima il rig chiamava `round()` a mano e saltava il lavoro dell'utente, il
   * turno e la fine del servizio (BK-6).
   */
  suspend fun userJob(trigger: BackupTrigger = BackupTrigger.USER_NOW, changes: Flow<Unit> = networkChanges(device.graph)): BackupJobEnd =
    userBackupRun(gallery, device.graph, backup, trigger, confirmed = false, userInitiated = true, changes = changes).run()

  /** Le azioni della pagina Backup sul grafo vero, con il verdetto delle regole scelto dal test (la rete di prova e' sempre il Wi-Fi di casa). */
  fun actions(decide: (suspend (Long?, Boolean) -> RuleDecision)? = null): AppBackupActions {
    val proposals = checkNotNull(gallery.backupUi).proposals
    return if (decide == null) AppBackupActions(backup, proposals, device.scope, app) else AppBackupActions(backup, proposals, device.scope, app, decide)
  }

  fun close() {
    device.close()
  }

  companion object {
    const val PRIMARY = "external_primary"

    fun newStore(): FakeMediaStore = FakeMediaStore().apply { addVolume(PRIMARY) }

    val TIMEOUT_MS: Long = Attese.TETTO_MS

    /** Una foto con i suoi byte: la riga del MediaStore coerente col file (dimensione, data). */
    fun photo(store: FakeMediaStore, id: Long, bytes: ByteArray, takenAt: Long, folder: String = "DCIM/Camera/", name: String = "IMG_$id.jpg"): MediaStoreRow =
      store.put(
        MediaStoreRow(
          volume = PRIMARY, id = id, type = RowType.IMAGE, displayName = name, relativePath = folder, mime = "image/jpeg",
          size = bytes.size.toLong(), dateModifiedMs = takenAt + 1_000, dateTakenMs = takenAt,
        ),
        bytes,
      )

    /**
     * [jpeg] con l'EXIF minimo di una foto scattata alle [dateOriginal] (`AAAA:MM:GG HH:MM:SS`, ora locale) con scarto [offset] (`+00:00`):
     * `DateTimeOriginal` e `OffsetTimeOriginal`, quanto basta perche' i metadati dicano l'ora di scatto (sorgente `exif_offset`), cioe' perche'
     * la foto sia una candidata alle copie simili (§2.12: mai una stima dal nome o dalla data del file).
     */
    fun withExif(jpeg: ByteArray, dateOriginal: String, offset: String = "+00:00"): ByteArray {
      require(dateOriginal.length == 19 && offset.length == 6) { "Formato dell'EXIF." }
      fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
      fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
      val date = dateOriginal.toByteArray(Charsets.US_ASCII) + 0
      val off = offset.toByteArray(Charsets.US_ASCII) + 0
      val ifd0Size = 2 + 12 + 4
      val exifIfdAt = 8 + ifd0Size
      val exifIfdSize = 2 + 2 * 12 + 4
      val dateAt = exifIfdAt + exifIfdSize
      val offsetAt = dateAt + date.size
      val tiff = ByteArrayOutputStream().apply {
        write(byteArrayOf('M'.code.toByte(), 'M'.code.toByte())); write(be16(0x002A)); write(be32(8))
        // IFD0: un solo ingresso, il puntatore all'IFD EXIF (0x8769, LONG).
        write(be16(1)); write(be16(0x8769)); write(be16(4)); write(be32(1)); write(be32(exifIfdAt)); write(be32(0))
        // IFD EXIF: DateTimeOriginal (0x9003) e OffsetTimeOriginal (0x9011), ASCII con valore fuori dall'ingresso.
        write(be16(2))
        write(be16(0x9003)); write(be16(2)); write(be32(date.size)); write(be32(dateAt))
        write(be16(0x9011)); write(be16(2)); write(be32(off.size)); write(be32(offsetAt))
        write(be32(0))
        write(date); write(off)
      }.toByteArray()
      val payload = "Exif".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0) + tiff
      val segment = byteArrayOf(0xFF.toByte(), 0xE1.toByte()) + be16(payload.size + 2) + payload
      check(jpeg.size > 2 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "Non e' un JPEG." }
      return jpeg.copyOfRange(0, 2) + segment + jpeg.copyOfRange(2, jpeg.size)
    }

    /** Un JPEG vero (Robolectric con grafica nativa), diverso per [tone]. */
    fun jpeg(tone: Int, width: Int = 320, height: Int = 240, quality: Int = 90): ByteArray {
      val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      Canvas(bitmap).drawColor(Color.rgb(tone, 255 - tone, (tone * 7) % 255))
      return ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        out.toByteArray()
      }
    }
  }
}

/** Tutti i file sotto [dir] in [share] (percorsi relativi alla share), ricorsivamente: per guardare cosa il backup ha scritto. */
internal fun filesUnder(backend: dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend, share: String, dir: String): List<String> {
  if (!backend.exists(share, dir)) return emptyList()
  return backend.children(share, dir).flatMap { name ->
    val path = "$dir/$name"
    if (backend.bytes(share, path) != null) listOf(path) else filesUnder(backend, share, path)
  }
}

/** Il dispositivo che entra con lo STESSO account dell'admin (area privata `test`): l'invito, la password di `test`, in attesa dell'approvazione. */
internal suspend fun AppGraph.joinSameAccount(invite: FamilyInvite): JoinStep.AwaitingApproval {
  awaitSetup { it == SetupState.NotConfigured }
  settle(coordinator.submit(StepInput.ChoosePath(OnboardingPath.JOIN_QR)))
  assertThat(joinFlow.start(invite)).isEqualTo(JoinStep.NeedsPassword)
  assertThat(joinFlow.authenticate(TestNas.ADMIN_PASSWORD.toCharArray())).isInstanceOf(JoinStep.NeedsName::class.java)
  val awaiting = joinFlow.register("Tablet di prova") as JoinStep.AwaitingApproval
  coordinator.progress.await { it.current == OnboardingStep.ROLES }
  localSteps(until = OnboardingStep.SUMMARY)
  settle(coordinator.submit(StepInput.Finish))
  awaitSetup { it is SetupState.AwaitingApproval }
  return awaiting
}

/** Il secondo dispositivo dello stesso account entra nella famiglia del primo: invito, richiesta, approvazione e attesa dello stato pronto. */
internal suspend fun enrolSecondDevice(admin: TestDevice, second: TestDevice) {
  second.graph.start()
  val invite = admin.graph.registry.createInvite(InviteParams(TestNas.ADMIN_USER, "Tablet di prova", TestNas.ADMIN_USER, presetName = null))
  val awaiting = second.graph.joinSameAccount(invite)
  admin.graph.registry.refresh(Purpose.INTERACTIVE)
  admin.graph.registry.approve(admin.graph.registry.pendingRequests.value.single().id, ApprovalProof.TypedCode(awaiting.code))
  second.graph.approvalWatcher.watch().first { it !is TrustStatus.Pending && it !is TrustStatus.Stale }
  second.graph.awaitSetup { it is SetupState.Ready }
  admin.graph.registry.refresh(Purpose.INTERACTIVE)
}
