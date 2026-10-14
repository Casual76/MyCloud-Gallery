package dev.pampa.mycloudgallery.integration

import android.app.Application
import dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreRow
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.contract.RowType
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
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
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.gallery.GalleryParts
import dev.pampa.mycloudgallery.gallery.PhoneInputs
import dev.pampa.mycloudgallery.gallery.PhoneLibrary
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * La galleria del telefono montata come in produzione (`GalleryGraph`) sopra il MediaStore finto, per le prove di vita e
 * di morte (R5, R7): lo stesso montaggio di `GalleryIntegrationTest`, ma con il database su file (si puo' riaprire dopo uno
 * `shutdown`, come un avvio a freddo), l'attesa dell'istantanea scelta dal test e lo stato della famiglia che il test puo'
 * cambiare.
 *
 * @param databaseName il file del catalogo; null = in memoria (un solo [GalleryGraph] per prova).
 */
internal class GalleryRig(
  private val app: Application,
  private val replica: File,
  private val preferences: File,
  val store: FakeMediaStore,
  val access: FakePhoneAccessReader,
  databaseName: String? = null,
  snapshotDelayMs: Long = 150L,
  val family: MutableStateFlow<SetupState> = MutableStateFlow(SetupState.Ready(admin = false)),
  intents: PhoneMediaIntents = FakePhoneMediaIntents(),
) {

  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  val gallery: GalleryGraph = GalleryGraph(
    context = app,
    scope = scope,
    parts = GalleryParts(
      databaseName = databaseName,
      replicaDir = replica,
      preferencesFile = preferences,
      access = access,
      intents = intents,
      recentlyDeleted = FakeRecentlyDeletedSource(),
      phone = ::phone,
      snapshotDelayMs = snapshotDelayMs,
    ),
    setup = object : FamilySetupRepository {
      override val state = family
    },
    ownerAccount = { AccountId.of("test") },
    clock = { NOW },
  )

  /** La lettura del telefono con le classi vere di P2 sopra il MediaStore finto. */
  private fun phone(inputs: PhoneInputs): PhoneLibrary {
    val reads = RoomPhoneCatalogReads(inputs.database)
    val stats = PhoneLibraryStats()
    val meta = DefaultMetadataPass(store, store, access, inputs.catalog, reads, inputs.extractor, clock = inputs.clock, stats = stats)
    val sync = DefaultPhoneLibrarySync(
      store, access, inputs.catalog, reads, clock = inputs.clock, stats = stats, metadataPass = meta, priority = inputs.priority,
    )
    return PhoneLibrary(
      sync = sync,
      access = access,
      metadataPass = meta,
      runner = DefaultPhoneSyncRunner(sync, meta, inputs.priority),
      stats = stats,
      diagnostics = PhoneLibraryDiagnostics(sync, access, stats) { inputs.catalog.mediaStoreCursors() },
      deferredHashes = DeferredFullHashes(store, access, inputs.catalog, inputs.database),
    )
  }

  /** Avvia la galleria e fa una lettura intera: ritorna quando l'indice ha tutte le righe del MediaStore. */
  suspend fun startAndSync(reason: SyncReason = SyncReason.APP_START): TimelineView {
    gallery.startWhenReady()
    withTimeout(TIMEOUT_MS) { gallery.started.first { it } }
    gallery.core().phone.sync.syncNow(reason)
    val expected = store.rowCount()
    return awaitView { it.size == expected }
  }

  suspend fun awaitView(filter: LibraryFilter = LibraryFilter.ALL, predicate: (TimelineView) -> Boolean): TimelineView =
    withTimeout(TIMEOUT_MS) { gallery.repository.view(filter).first(predicate) }

  fun close() = closeScope(scope)

  private fun FakeMediaStore.rowCount(): Int = (1L..MAX_ID).count { row(PRIMARY, it) != null }

  companion object {
    const val PRIMARY = "external_primary"

    /** 12/10/2026 12:00 UTC. */
    const val NOW: Long = 1_791_806_400_000L
    const val MINUTE: Long = 60_000L
    const val TIMEOUT_MS: Long = 20_000L
    private const val MAX_ID = 50L

    fun newStore(): FakeMediaStore = FakeMediaStore().apply { addVolume(PRIMARY) }

    fun accessOf(media: MediaAccess): FakePhoneAccessReader =
      FakePhoneAccessReader(PhoneAccess(media, mediaLocation = true, volumes = setOf(PRIMARY)))

    fun photo(id: Long, minutesAgo: Long) = MediaStoreRow(
      volume = PRIMARY, id = id, type = RowType.IMAGE, displayName = "IMG_$id.jpg", relativePath = "DCIM/Camera/",
      mime = "image/jpeg", size = 4_000, dateModifiedMs = NOW - minutesAgo * MINUTE + 1_000, dateTakenMs = NOW - minutesAgo * MINUTE,
    )
  }
}
