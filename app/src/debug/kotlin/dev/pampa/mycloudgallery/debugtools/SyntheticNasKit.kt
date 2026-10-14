package dev.pampa.mycloudgallery.debugtools

import android.content.Context
import android.graphics.Bitmap
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.imaging.ImagingPipeline
import dev.pampa.mycloudgallery.core.imaging.budget.ImagingBudget
import dev.pampa.mycloudgallery.core.imaging.contract.UnavailableReason
import dev.pampa.mycloudgallery.core.imaging.nas.NasThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.testing.SyntheticPixels
import dev.pampa.mycloudgallery.core.imaging.viewer.ImageOrigin
import dev.pampa.mycloudgallery.core.imaging.viewer.OriginResult
import dev.pampa.mycloudgallery.core.imaging.viewer.PipelineViewerImageSource
import dev.pampa.mycloudgallery.core.index.contract.ProvisionalThumbnails
import dev.pampa.mycloudgallery.core.index.noop.EmptyReplicaBlobs
import dev.pampa.mycloudgallery.core.index.noop.NoReplicaRecords
import dev.pampa.mycloudgallery.core.index.replica.ReplicaProvisionalThumbnails
import dev.pampa.mycloudgallery.core.index.replica.store.RoomProvisionalStore
import dev.pampa.mycloudgallery.core.library.contract.LibraryNasStatus
import dev.pampa.mycloudgallery.core.library.contract.LocalMediaActions
import dev.pampa.mycloudgallery.core.library.contract.TimelineRepository
import dev.pampa.mycloudgallery.core.library.data.CatalogLocalMediaActions
import dev.pampa.mycloudgallery.core.library.data.CatalogTimelineRepository
import dev.pampa.mycloudgallery.core.library.data.CatalogTimelineSource
import dev.pampa.mycloudgallery.core.library.store.SnapshotStore
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncStatus
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasScanStatus
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.nasmedia.detect.NasDetector
import dev.pampa.mycloudgallery.core.nasmedia.scan.DefaultNasScanner
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.feature.library.grid.MediaGridState
import dev.pampa.mycloudgallery.feature.library.library.LibraryCommands
import dev.pampa.mycloudgallery.feature.library.library.LibraryDebugHooks
import dev.pampa.mycloudgallery.feature.library.library.LibraryPhoneStatus
import dev.pampa.mycloudgallery.feature.library.library.LibraryTabState
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.gallery.DebugLibraryKit
import dev.pampa.mycloudgallery.gallery.DebugLibraryStack
import dev.pampa.mycloudgallery.gallery.DeferredTimelineSource
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.gallery.GuardedMediaActions
import dev.pampa.mycloudgallery.nas.MediaCatalogScanStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SOLO debug (Fase 4A, P11, M1): il **NAS sintetico**. Un NAS finto in memoria ([InMemoryNasMedia]) con [FILES] file,
 * passato dalla **scansione vera** (P5: `DefaultNasScanner`, `NasDetector` con l'estrattore dell'app, `MediaCatalogScanStore`)
 * in un **database separato** (`gallery-nas-synthetic.db`, mai `gallery.db`). La Libreria mostra i media **solo sul NAS**:
 * date dai nomi e dalle date dei file, presenza "solo NAS" nell'indice, riferimenti `MediaRef.Nas` e le **miniature EXIF
 * provvisorie** (D17) lette al rilevamento, servite da `NasThumbnailSource` come nella Libreria vera.
 *
 * Le foto sono finte ma con la forma di quelle vere quanto basta alla scansione: SOI, APP1 `Exif` con la miniatura nell'IFD1
 * (un gradiente JPEG vero, 160x120), SOF con le dimensioni della foto, SOS, corpo pseudo-casuale, EOI. Nella share comune ci
 * sono anche album di musica (tracce e copertine, escluse come in E-N14). Nessuna rete, nessun NAS vero: solo memoria.
 *
 * Si chiede da Impostazioni → Sviluppo; la Libreria lo apre e il menu "Vista" riporta al telefono. La release non ha niente
 * di tutto questo (tools/check.ps1 cerca `SyntheticNasKit` e `nas-synthetic` nel dex di release).
 */
internal object SyntheticNasKit : DebugLibraryKit {

  /** I file sul NAS sintetico (foto, tracce e copertine). */
  const val FILES: Int = 20_000

  /** La forma del database scritto: se cambia, il database separato si rifa' da capo. */
  const val VERSION: Int = 1

  private val wanted = MutableStateFlow(false)

  override val requested: StateFlow<Boolean> = wanted

  /**
   * G2: la "libreria mista" ([MixedPresenceRepository]): presenza, preferiti e video assegnati sopra l'indice, per vedere
   * tutti i segni sulla griglia, e lo stato del piede scelto da `adb` ([DebugNasFooter]).
   */
  @Volatile
  var mixed: Boolean = false
    private set

  /** Impostazioni → Sviluppo → "NAS sintetico" (o, con [mixed], "NAS sintetico, libreria mista"). */
  fun request(mixed: Boolean = false) {
    this.mixed = mixed
    wanted.value = true
  }

  override fun dismiss() {
    wanted.value = false
  }

  override fun open(context: Context, gallery: GalleryGraph, hooks: LibraryDebugHooks): DebugLibraryStack =
    SyntheticNasStack(context.applicationContext, gallery, hooks)
}

/** La Libreria del NAS sintetico: stesso codice della Libreria vera, database e miniature propri. */
internal class SyntheticNasStack(
  private val context: Context,
  private val gallery: GalleryGraph,
  hooks: LibraryDebugHooks,
) : DebugLibraryStack {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val ready = CompletableDeferred<CatalogTimelineSource>()
  private val catalogActions = CompletableDeferred<CatalogLocalMediaActions>()
  @Volatile private var database: GalleryDatabase? = null
  @Volatile private var thumbs: ProvisionalThumbnails? = null
  private val phone = MutableStateFlow(LibraryPhoneStatus(MediaAccess.FULL, SyncStatus.Scanning(0, SyntheticNasKit.FILES, full = true)))

  private val repository = CatalogTimelineRepository(
    DeferredTimelineSource { ready.await() },
    SnapshotStore(File(context.noBackupFilesDir, REPLICA_DIR)),
    scope,
  )

  /** Cio' che la Libreria guarda: l'indice com'e', o la libreria mista (G2). */
  private val timeline: TimelineRepository = if (SyntheticNasKit.mixed) MixedPresenceRepository(repository, scope) else repository

  /** Lo stato del NAS del piede: nessuno, o (libreria mista) quello scelto da `adb` (G2). */
  private val nasFooter = MutableStateFlow<LibraryNasStatus>(LibraryNasStatus.Idle)

  /** Le miniature provvisorie del database separato, appena e' aperto (prima: nessuna, la griglia mostra i segnaposto). */
  private val provisional = object : ProvisionalThumbnails {
    override suspend fun put(contentKey: ContentKey, jpeg: ByteArray) = thumbs?.put(contentKey, jpeg) ?: Unit

    override fun get(contentKey: Long): ByteArray? = thumbs?.get(contentKey)

    override suspend fun forget(contentKey: Long) = thumbs?.forget(contentKey) ?: Unit
  }

  private val budget = ImagingBudget.of(context)
  private val source = ImagingPipeline.thumbnailSource(context, nas = NasThumbnailSource(EmptyReplicaBlobs, provisional))
  private val loader = ImagingPipeline.thumbnailLoader(source, budget)
  /** Gli originali del NAS sintetico non si aprono (il NAS finto vive solo durante la scansione): come un NAS spento (E-L4). */
  private val images = PipelineViewerImageSource(origin = NasOff, previews = source, budget = budget)

  private val actions: LocalMediaActions = GuardedMediaActions(
    object : LocalMediaActions {
      override suspend fun setFavorite(ids: Set<MediaId>, on: Boolean) = catalogActions.await().setFavorite(ids, on)

      override suspend fun setHidden(ids: Set<MediaId>, on: Boolean) = catalogActions.await().setHidden(ids, on)
    },
  )

  override val state: LibraryTabState
  override val viewerServices: ViewerServices

  init {
    val prefs = gallery.preferences
    val grid = MediaGridState(
      view = timeline.view(prefs.filter.value).value,
      loader = loader,
      placeholders = gallery.placeholders,
      placeNames = gallery.places,
      engine = gallery.layoutEngine,
      refOf = { id -> repository.mediaRefOf(id) ?: throw NoSuchElementException("Nessuna copia leggibile adesso.") },
      level = prefs.level.value,
      style = prefs.style.value,
      refAt = { snapshot, index ->
        snapshot.columnar()?.thumbRefAt(index) ?: repository.mediaRefOf(snapshot.idAt(index))
      },
      versionKeyAt = { snapshot, index -> snapshot.columnar()?.versionKeyAt(index) ?: 1L },
    )
    state = LibraryTabState(
      grid = grid,
      viewerItems = grid.viewerItems,
      viewerImages = images,
      photoCount = 0,
      videoCount = 0,
      timeline = timeline,
      preferences = prefs,
      actions = actions,
      phone = phone,
      commands = NoCommands,
      debug = hooks,
      nas = nasFooter,
    )
    viewerServices = ViewerServices(
      images = images,
      thumbnails = loader,
      placeholders = gallery.placeholders,
      places = gallery.places,
      actions = actions,
    )
    scope.launch { open() }
    if (SyntheticNasKit.mixed) {
      scope.launch {
        while (true) {
          nasFooter.value = withContext(Dispatchers.IO) { DebugNasFooter.read(context.getExternalFilesDir(null)) }
          delay(FOOTER_POLL_MS)
        }
      }
    }
  }

  private suspend fun open() {
    try {
      val marker = File(context.noBackupFilesDir, "$REPLICA_DIR/$COUNT_FILE")
      val wanted = "${SyntheticNasKit.FILES}/${SyntheticNasKit.VERSION}"
      val known = withContext(Dispatchers.IO) { runCatching { marker.readText().trim() }.getOrNull() }
      val reuse = known == wanted && context.getDatabasePath(DATABASE_NAME).exists()
      val db = withContext(Dispatchers.IO) {
        if (!reuse) {
          context.deleteDatabase(DATABASE_NAME)
          File(context.noBackupFilesDir, REPLICA_DIR).deleteRecursively()
        }
        GalleryDatabase.build(context, DATABASE_NAME)
      }
      database = db
      thumbs = ReplicaProvisionalThumbnails(RoomProvisionalStore(db))
      val catalog = MediaCatalog(db, OWNER, ZoneId::systemDefault) { System.currentTimeMillis() }
      val timeline = CatalogTimelineSource(db, catalog, nasRefsLookup = catalog::nasRefs)
      catalogActions.complete(CatalogLocalMediaActions(catalog, timeline, repository))
      ready.complete(timeline)
      repository.start()
      if (!reuse) {
        scan(catalog)
        withContext(Dispatchers.IO) {
          marker.parentFile?.mkdirs()
          marker.writeText(wanted)
        }
      }
      phone.value = LibraryPhoneStatus(MediaAccess.FULL, SyncStatus.Idle(atMs = System.currentTimeMillis()))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.e(TAG, e) { "NAS sintetico non preparato" }
    }
  }

  /** La scansione vera (P5) delle due share del NAS finto: la Libreria si riempie mentre procede. */
  private suspend fun scan(catalog: MediaCatalog) {
    val nas = SyntheticNasContent.build()
    val store = MediaCatalogScanStore(catalog)
    val detector = NasDetector(gallery.newExtractor())
    val clock = NasClock { nas.nasNow() }
    var before = 0
    for (share in listOf(SyntheticNasContent.PRIVATE, SyntheticNasContent.COMMON)) {
      val scanner = DefaultNasScanner(
        account = OWNER,
        share = share,
        reader = nas.reader,
        store = store,
        detector = detector,
        replica = NoReplicaRecords,
        provisional = provisional,
        clock = clock,
      )
      val progress = scope.launch {
        scanner.status.collect { status ->
          if (status is NasScanStatus.Scanning) {
            val done = (before + status.filesSeen).coerceAtMost(SyntheticNasKit.FILES)
            phone.value = LibraryPhoneStatus(MediaAccess.FULL, SyncStatus.Scanning(done, SyntheticNasKit.FILES, full = true))
          }
        }
      }
      val report = scanner.scan(ScanMode.Full, SCAN_BUDGET)
      progress.cancel()
      before += report.files
      GalleryLog.i(TAG) { "NAS sintetico: ${report.files} file, ${report.detected} rilevati, ${report.albumCovers} copertine, ${report.notMedia} non media" }
    }
  }

  override fun close() {
    scope.launch {
      runCatching { repository.flush() }
      runCatching { loader.close() }
      database?.close()
      scope.cancel()
    }
  }

  /** Nessun comando verso il sistema: niente permessi, niente da condividere o eliminare (i file non esistono). */
  private object NoCommands : LibraryCommands

  /** Il NAS sintetico non serve originali: il viewer mostra l'anteprima e spiega che il NAS non risponde. */
  private object NasOff : ImageOrigin {
    override suspend fun open(ref: MediaRef): OriginResult = OriginResult.Unreachable(UnavailableReason.NAS_UNREACHABLE)
  }

  companion object {
    const val DATABASE_NAME: String = "gallery-nas-synthetic.db"
    private const val REPLICA_DIR = "replica-nas-synthetic"
    private const val COUNT_FILE = "count"
    private const val TAG = "SyntheticNas"
    private val OWNER: AccountId = AccountId.of("test")
    private val SCAN_BUDGET = 30.minutes
    private const val FOOTER_POLL_MS = 1_500L
  }
}

/**
 * I file del NAS sintetico: [SyntheticNasKit.FILES] in tutto, deterministici. Share privata `test` (fotocamera, WhatsApp,
 * schermate, vecchie scansioni) e comune `test-comune` (foto di famiglia e album di musica con le copertine).
 */
internal object SyntheticNasContent {
  const val PRIVATE: String = "test"
  const val COMMON: String = "test-comune"

  private const val CAMERA = 9_000
  private const val WHATSAPP = 4_000
  private const val SCREENSHOTS = 1_500
  private const val OLD_SCANS = 1_500
  private const val FAMILY = 3_000
  private const val ALBUMS = 250
  private const val TRACKS = 3

  private const val TEMPLATES = 16
  private const val DAY = 24L * 60 * 60 * 1000

  /** Il NAS con tutti i file (in memoria: le foto sono intestazione vera + corpo generato a richiesta). */
  fun build(): InMemoryNasMedia {
    val now = System.currentTimeMillis()
    val nas = InMemoryNasMedia(nasNow = { System.currentTimeMillis() })
    nas.addShare(PRIVATE)
    nas.addShare(COMMON)
    val landscape = (0 until TEMPLATES).map { head(it.toLong(), 4032, 3024, 160, 120) }
    val portrait = (0 until TEMPLATES).map { head(1_000L + it, 3024, 4032, 120, 160) }
    val random = Random(SEED)
    var seed = 1L
    fun photo(share: String, path: String, at: Long) {
      val heads = if (random.nextInt(4) == 0) portrait else landscape
      val size = 2_400_000L + random.nextLong(0, 2_000_000L)
      nas.putFile(share, path, PhotoContent(size, seed++, heads[random.nextInt(heads.size)]), modifiedAt = at)
    }
    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)
    val day = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT)
    val zone = ZoneId.systemDefault()
    fun local(at: Long): LocalDateTime = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(at), zone)

    // Fotocamera: sei anni, a gruppi di giornate (vacanze, compleanni), qualche foto al giorno.
    var at = now - 2 * DAY
    repeat(CAMERA) { i ->
      at -= if (i % 12 == 0) random.nextLong(DAY, 6 * DAY) else random.nextLong(60_000L, 3 * 60 * 60 * 1000L)
      photo(PRIVATE, "Camera/IMG_${local(at).format(stamp)}.jpg", at)
    }
    at = now - 3 * DAY
    repeat(WHATSAPP) { i ->
      at -= random.nextLong(30 * 60 * 1000L, 20 * 60 * 60 * 1000L)
      photo(PRIVATE, "WhatsApp Images/IMG-${local(at).format(day)}-WA${String.format(Locale.ROOT, "%04d", i % 10_000)}.jpg", at)
    }
    at = now - 4 * DAY
    repeat(SCREENSHOTS) {
      at -= random.nextLong(60 * 60 * 1000L, 2 * DAY)
      photo(PRIVATE, "Screenshots/Screenshot_${local(at).format(stamp)}.jpg", at)
    }
    // Vecchie scansioni: niente data nel nome, quella del file (1998-2005).
    val oldStart = LocalDateTime.of(1998, 1, 1, 10, 0).atZone(zone).toInstant().toEpochMilli()
    repeat(OLD_SCANS) { i ->
      photo(PRIVATE, "Vecchie foto/scansione_${String.format(Locale.ROOT, "%04d", i + 1)}.jpg", oldStart + i * 2 * DAY + random.nextLong(0, DAY))
    }
    // Share comune: foto di famiglia per evento.
    at = now - 10 * DAY
    var event = 0
    repeat(FAMILY) { i ->
      if (i % 60 == 0) {
        event++
        at -= random.nextLong(10 * DAY, 40 * DAY)
      }
      at += random.nextLong(60_000L, 20 * 60 * 1000L)
      val year = local(at).year
      photo(COMMON, "Foto di famiglia/$year Evento $event/DSC_${String.format(Locale.ROOT, "%05d", i + 1)}.jpg", at)
    }
    // Album di musica: tracce (non media) e copertina (esclusa di default, E-N14).
    val musicAt = now - 400 * DAY
    repeat(ALBUMS) { a ->
      val folder = "Musica/Artista ${a / 10 + 1}/Album ${a % 10 + 1}"
      repeat(TRACKS) { t ->
        nas.putFile(COMMON, "$folder/${t + 1} Traccia.mp3", NasContent.virtual(4_000_000L + t, seed++, ID3), modifiedAt = musicAt)
      }
      photo(COMMON, "$folder/cover.jpg", musicAt)
    }
    return nas
  }

  /** L'intestazione di una foto: SOI, APP1 Exif con la miniatura (IFD1), SOF0 con le dimensioni, SOS. */
  private fun head(seed: Long, width: Int, height: Int, thumbWidth: Int, thumbHeight: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(SyntheticPixels.argb(seed, thumbWidth, thumbHeight), thumbWidth, thumbHeight, Bitmap.Config.ARGB_8888)
    val thumb = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, it) }.toByteArray()
    bitmap.recycle()
    val tiff = ByteArrayOutputStream().apply {
      write(byteArrayOf('M'.code.toByte(), 'M'.code.toByte()))
      write(u16(42))
      write(u32(8))
      // IFD0: Orientation = 1, poi il puntatore all'IFD1.
      write(u16(1))
      write(entry(0x0112, 3, 1, 1, short = true))
      val ifd1 = 8 + 2 + 12 + 4
      write(u32(ifd1.toLong()))
      // IFD1: compressione JPEG, offset e lunghezza della miniatura.
      val thumbOffset = ifd1 + 2 + 3 * 12 + 4
      write(u16(3))
      write(entry(0x0103, 3, 1, 6, short = true))
      write(entry(0x0201, 4, 1, thumbOffset, short = false))
      write(entry(0x0202, 4, 1, thumb.size, short = false))
      write(u32(0))
      write(thumb)
    }.toByteArray()
    val exif = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff
    return ByteArrayOutputStream().apply {
      write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
      write(byteArrayOf(0xFF.toByte(), 0xE1.toByte()))
      write(u16(exif.size + 2))
      write(exif)
      // SOF0, tre componenti.
      write(byteArrayOf(0xFF.toByte(), 0xC0.toByte()))
      write(u16(17))
      write(byteArrayOf(8))
      write(u16(height))
      write(u16(width))
      write(byteArrayOf(3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1))
      // SOS.
      write(byteArrayOf(0xFF.toByte(), 0xDA.toByte()))
      write(u16(12))
      write(byteArrayOf(3, 1, 0, 2, 0x11, 3, 0x11, 0, 0x3F, 0))
    }.toByteArray()
  }

  private fun entry(tag: Int, type: Int, count: Int, value: Int, short: Boolean): ByteArray =
    u16(tag) + u16(type) + u32(count.toLong()) + if (short) u16(value) + byteArrayOf(0, 0) else u32(value.toLong())

  private fun u16(v: Int): ByteArray = byteArrayOf((v shr 8).toByte(), v.toByte())

  private fun u32(v: Long): ByteArray = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

  private val ID3: ByteArray = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 0)

  private const val SEED = 20_000L
  private const val THUMB_QUALITY = 85

  /**
   * Una foto finta: [head] vero davanti, poi byte pseudo-casuali dal [seed] (mai `FF`, cosi' nessun marcatore finto nei dati
   * compressi) e l'EOI in fondo. Stessi byte per la stessa posizione: le letture a intervalli sono ripetibili.
   */
  private class PhotoContent(override val size: Long, private val seed: Long, private val head: ByteArray) : NasContent {
    override fun copy(offset: Long, dst: ByteArray, dstOffset: Int, len: Int) {
      for (i in 0 until len) {
        val pos = offset + i
        dst[dstOffset + i] = when {
          pos < head.size -> head[pos.toInt()]
          pos == size - 2 -> 0xFF.toByte()
          pos == size - 1 -> 0xD9.toByte()
          else -> byteAt(pos)
        }
      }
    }

    private fun byteAt(pos: Long): Byte {
      var z = seed * -0x61c8864680b583ebL + (pos ushr 3)
      z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
      z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
      z = z xor (z ushr 31)
      val b = (z ushr (((pos and 7L).toInt()) * 8)).toInt() and 0xFF
      return (if (b == 0xFF) 0x7F else b).toByte()
    }
  }
}
