package dev.pampa.mycloudgallery.gallery

import android.content.Context
import android.os.UserManager
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.mediastore.PhoneLibraryGraph
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreSource
import dev.pampa.mycloudgallery.core.mediastore.contract.MetaPriority
import dev.pampa.mycloudgallery.core.mediastore.contract.MetadataPass
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccessReader
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneSyncRunner
import dev.pampa.mycloudgallery.core.mediastore.sync.DefaultPhoneLibrarySync
import dev.pampa.mycloudgallery.core.mediastore.sync.DeferredFullHashes
import dev.pampa.mycloudgallery.core.mediastore.sync.MediaStoreRowLookup
import dev.pampa.mycloudgallery.core.mediastore.sync.PhoneLibraryStats
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataExtractor
import java.time.ZoneId

/**
 * Cio' che serve per montare la lettura del telefono: il catalogo (che esiste solo quando si conosce l'account, vedi
 * [GalleryGraph]) e le parti che le girano intorno.
 */
class PhoneInputs(
  val context: Context,
  val database: GalleryDatabase,
  val catalog: MediaCatalog,
  val extractor: MetadataExtractor,
  val clock: () -> Long,
  val priority: MetaPriority,
)

/**
 * Il MediaStore come lo vede il backup (Fase 5A, P7): le righe da cercare per `_ID` e i byte da aprire. In produzione e' un solo oggetto
 * (`AndroidMediaStoreSource`), nei test il `FakeMediaStore`; il backup lo legge e basta, non scrive mai niente sul telefono (5A).
 */
class PhoneRowsSource(val source: MediaStoreSource, val lookup: MediaStoreRowLookup)

/**
 * Le parti della lettura del telefono (P2) che il resto dell'app usa. In produzione vengono **tutte** da
 * [PhoneLibraryGraph] (D23: `core-*` senza Hilt, il montaggio e' suo); nei test d'integrazione le stesse classi sono
 * costruite sopra il `FakeMediaStore`.
 *
 * @property sync la sincronizzazione vera: `attach` al primo piano, `runWithin(MAINTENANCE)` nella manutenzione.
 * @property deferredHashes gli hash completi rimandati della manutenzione in carica (aggiunta di P11).
 * @property rows (Fase 5A, P7) le righe e i byte del MediaStore per il backup; null (i montaggi che non lo danno) = il backup non legge il
 *   telefono e i suoi elementi aspettano.
 */
class PhoneLibrary(
  val sync: DefaultPhoneLibrarySync,
  val access: PhoneAccessReader,
  val metadataPass: MetadataPass,
  val runner: PhoneSyncRunner,
  val stats: PhoneLibraryStats,
  val diagnostics: DiagnosticsContributor,
  val deferredHashes: DeferredFullHashes,
  val rows: PhoneRowsSource? = null,
) {
  companion object {

    /** Il montaggio vero: [PhoneLibraryGraph] sopra il MediaStore del telefono. */
    fun android(inputs: PhoneInputs): PhoneLibrary {
      val app = inputs.context.applicationContext
      val userManager = app.getSystemService(UserManager::class.java)
      val graph = PhoneLibraryGraph(
        context = app,
        database = inputs.database,
        catalog = inputs.catalog,
        extractor = inputs.extractor,
        clock = inputs.clock,
        zone = ZoneId::systemDefault,
        // E-P2, E-P10, E-P13: prima del primo sblocco l'archivio e' cifrato; la sincronizzazione aspetta.
        storageReady = { userManager?.isUserUnlocked != false },
        priority = inputs.priority,
      )
      return PhoneLibrary(
        sync = graph.sync,
        access = graph.access,
        metadataPass = graph.metadataPass,
        runner = graph.runner,
        stats = graph.stats,
        diagnostics = graph.diagnostics,
        deferredHashes = DeferredFullHashes(graph.source, graph.access, inputs.catalog, inputs.database),
        rows = PhoneRowsSource(graph.source, graph.source),
      )
    }
  }
}
