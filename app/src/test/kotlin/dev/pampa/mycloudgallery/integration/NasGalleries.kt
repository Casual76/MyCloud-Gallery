package dev.pampa.mycloudgallery.integration

import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccess
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
import dev.pampa.mycloudgallery.gallery.PhoneRowsSource
import dev.pampa.mycloudgallery.nas.NasAiHooks
import java.io.File

/** La galleria di un dispositivo come in produzione, con il NAS (`AppGraph.nasInputs`) e il MediaStore finto vuoto (Fase 6, P11). */
internal object NasGalleries {
  private const val PRIMARY = "external_primary"

  /** Un MediaStore finto con il volume principale. */
  fun newStore(): FakeMediaStore = FakeMediaStore().apply { addVolume(PRIMARY) }

  fun galleryOf(
    device: TestDevice,
    account: String,
    time: TestTime,
    ai: () -> NasAiHooks? = { null },
    store: FakeMediaStore = newStore(),
  ): GalleryGraph {
    val access = FakePhoneAccessReader(PhoneAccess(MediaAccess.FULL, mediaLocation = true, volumes = setOf(PRIMARY)))
    fun phone(inputs: PhoneInputs): PhoneLibrary {
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
        // Fase 6 (P11b): i byte del telefono per il calcolo AI dei media del solo telefono (e per le anteprime dal telefono).
        rows = PhoneRowsSource(store, store),
      )
    }
    return GalleryGraph(
      context = device.context,
      scope = device.scope,
      parts = GalleryParts(
        databaseName = null,
        replicaDir = File(device.context.noBackupFilesDir, "replica"),
        preferencesFile = File(device.context.noBackupFilesDir, "library.preferences_pb"),
        access = access,
        intents = FakePhoneMediaIntents(),
        recentlyDeleted = FakeRecentlyDeletedSource(),
        phone = ::phone,
        snapshotDelayMs = 100L,
        nas = device.graph.nasInputs,
        monotonicMs = { System.nanoTime() / 1_000_000 },
        ai = ai,
      ),
      setup = device.graph.setup,
      ownerAccount = { AccountId.of(account) },
      clock = { time.now() },
    )
  }
}
