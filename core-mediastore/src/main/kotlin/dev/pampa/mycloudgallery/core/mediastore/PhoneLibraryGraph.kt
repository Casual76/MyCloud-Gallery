package dev.pampa.mycloudgallery.core.mediastore

import android.content.Context
import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.mediastore.android.AndroidMediaStoreSource
import dev.pampa.mycloudgallery.core.mediastore.android.AndroidPhoneAccessReader
import dev.pampa.mycloudgallery.core.mediastore.android.AndroidPhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.android.AndroidRecentlyDeletedSource
import dev.pampa.mycloudgallery.core.mediastore.contract.MetaPriority
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccessReader
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.contract.RecentlyDeletedSource
import dev.pampa.mycloudgallery.core.mediastore.diagnostics.PhoneLibraryDiagnostics
import dev.pampa.mycloudgallery.core.mediastore.meta.DefaultMetadataPass
import dev.pampa.mycloudgallery.core.mediastore.noop.NoMetaPriority
import dev.pampa.mycloudgallery.core.mediastore.sync.DefaultPhoneLibrarySync
import dev.pampa.mycloudgallery.core.mediastore.sync.DefaultPhoneSyncRunner
import dev.pampa.mycloudgallery.core.mediastore.sync.PhoneLibraryStats
import dev.pampa.mycloudgallery.core.mediastore.sync.PhoneSyncConfig
import dev.pampa.mycloudgallery.core.mediastore.sync.RoomPhoneCatalogReads
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataExtractor
import java.time.ZoneId

/**
 * Le implementazioni vere della lettura del telefono, montate insieme (D23: `core-*` senza Hilt; `:app` le espone).
 * Un grafo per processo: la sincronizzazione e META hanno i loro `Mutex` e devono essere uniche.
 *
 * @param clock l'orologio di riferimento (quello del catalogo).
 * @param zone il fuso del telefono **adesso**, per le date senza fuso (E-P5).
 * @param storageReady falso prima del primo sblocco (`UserManager.isUserUnlocked`).
 * @param priority la finestra visibile della griglia (P11 la collega).
 */
class PhoneLibraryGraph(
  context: Context,
  database: GalleryDatabase,
  catalog: MediaCatalog,
  extractor: MetadataExtractor,
  clock: () -> Long,
  zone: () -> ZoneId = ZoneId::systemDefault,
  dispatchers: DispatcherProvider = DefaultDispatcherProvider,
  storageReady: () -> Boolean = { true },
  priority: MetaPriority = NoMetaPriority,
  config: PhoneSyncConfig = PhoneSyncConfig(),
  visibilityChanged: () -> Unit = {},
  contentChanged: () -> Unit = {},
) {
  val source: AndroidMediaStoreSource = AndroidMediaStoreSource(context, visibilityChanged = contentChanged)

  val access: PhoneAccessReader = AndroidPhoneAccessReader(context).also { it.onChange(visibilityChanged) }

  val stats: PhoneLibraryStats = PhoneLibraryStats()

  private val reads = RoomPhoneCatalogReads(database)

  val metadataPass: DefaultMetadataPass = DefaultMetadataPass(
    source = source,
    lookup = source,
    access = access,
    catalog = catalog,
    reads = reads,
    extractor = extractor,
    zone = zone,
    clock = clock,
    dispatchers = dispatchers,
    config = config,
    stats = stats,
  )

  val sync: DefaultPhoneLibrarySync = DefaultPhoneLibrarySync(
    source = source,
    access = access,
    catalog = catalog,
    reads = reads,
    clock = clock,
    dispatchers = dispatchers,
    config = config,
    stats = stats,
    metadataPass = metadataPass,
    priority = priority,
    storageReady = storageReady,
    selectionChanged = visibilityChanged,
  )

  val runner: DefaultPhoneSyncRunner = DefaultPhoneSyncRunner(sync, metadataPass, priority)

  val intents: PhoneMediaIntents = AndroidPhoneMediaIntents(context)

  val recentlyDeleted: RecentlyDeletedSource = AndroidRecentlyDeletedSource(context)

  val diagnostics: PhoneLibraryDiagnostics = PhoneLibraryDiagnostics(sync, access, stats) { catalog.mediaStoreCursors() }
}
