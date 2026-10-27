package dev.pampa.mycloudgallery.albums

import android.content.Context
import dev.pampa.mycloudgallery.core.albums.contract.SafeCopyRequester
import dev.pampa.mycloudgallery.core.backup.upload.AlbumFolderUploads
import dev.pampa.mycloudgallery.core.search.contract.SearchEngine
import dev.pampa.mycloudgallery.core.search.contract.Today
import dev.pampa.mycloudgallery.debugtools.DeveloperTools
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.work.AlbumWorkScheduling
import dev.pampa.mycloudgallery.work.BackupWorkScheduling
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Come si monta [AlbumsGraph] nel processo (Fase 7, P13; §1.4): le porte di core-albums legate a cio' che l'app ha gia' (registro, aree, orologi,
 * cancelli dell'indice e recinzioni, operazioni della 4B, caricamenti `INCOMING` e guardiano del backup, catalogo, dati personali, ricerca).
 * Le stesse righe le usano i test d'integrazione (Robolectric), cambiando solo la piattaforma sotto [AppGraph].
 */
object AlbumsWiring {

  /** I file degli album in `noBackupFilesDir` (fuori dai backup; l'azzeramento toglie la cartella). */
  const val DIR: String = "albums"

  fun build(context: Context, graph: AppGraph, gallery: GalleryGraph, search: SearchEngine?, today: Today): AlbumsGraph {
    val noBackup = context.noBackupFilesDir
    val inputs = graph.nasInputs
    val nas = gallery.nas
    val monotonic = gallery.parts.monotonicMs
    val family = AppAlbumFamily(inputs) { ownBackupFolder(gallery) }
    val clocks = AppAlbumClocks(inputs, monotonic) { nas?.clockGuard?.blocked == true }
    val identity = AppAlbumIdentity(inputs)
    val writeGate = AppAlbumWriteGate(graph.flags, DeveloperTools.shareGuard(), { inputs.account() }, { nas?.writeGate })
    val journal = FileAlbumUploadJournal(File(noBackup, "$DIR/upload-journal"))
    val uploads: AlbumFolderUploads? by lazy {
      gallery.backup?.let { backup -> AlbumFolderUploads(backup.store, journal, backup.gate, backup.fiveB.incomingPorts(), family::commonShare) }
    }
    val ready: StateFlow<Boolean> = if (nas == null) {
      MutableStateFlow(false)
    } else {
      combine(gallery.started, nas.organizer.partsState) { started, parts -> started && parts != null }.stateIn(graph.scope, SharingStarted.Eagerly, false)
    }
    // I motori degli smart e dei viaggi contano i loro errori e dicono l'esito di una ricerca dei viaggi al grafo, che nasce dopo di loro.
    val graphRef = arrayOfNulls<AlbumsGraph>(1)
    val build: suspend (kotlinx.coroutines.CoroutineScope) -> AlbumsLive? = build@{ scope ->
      val organizer = nas?.organizer ?: return@build null
      val parts = organizer.parts ?: return@build null
      val core = gallery.coreIfReady() ?: return@build null
      val catalog = core.catalog
      val mover = OpsAlbumMover(organizer.operations, organizer.opsStore, { catalog }, family::me, TransferPlans(File(noBackup, "$DIR/transfer-plans")), monotonic)
      val uploader = FolderAlbumUploader({ uploads }, { catalog }, monotonic) { BackupWorkScheduling.incoming(context) }
      buildAlbumsLive(
        scope,
        AlbumsInputs(
          database = gallery.database,
          catalog = catalog,
          personal = parts.personalStore,
          visible = gallery.visibleMedia,
          mask = gallery.hiddenMask,
          identity = identity,
          family = family,
          clocks = clocks,
          writeGate = writeGate,
          readGate = FlagsAlbumReadGate(graph.flags),
          fileSystem = { share -> inputs.session.fileSystem(share) },
          publishAuthor = { inputs.deviceId()?.let { device -> family.me()?.let { device to it } } },
          mover = mover,
          uploader = uploader,
          tickets = AlbumTickets(mover, uploader),
          // D28: la richiesta si registra sul disco del backup prima del ritorno; senza il backup non si registra (l'orchestratore riprova).
          safeCopies = SafeCopyRequester { media ->
            val requests = gallery.backup?.safeCopyRequests ?: throw IllegalStateException("Backup non pronto.")
            requests.requestNow(media)
          },
          search = search,
          places = gallery.places,
          placeNames = gallery.places,
          today = today,
          deviceNow = { graph.platform.deviceNow() },
          noticesFile = File(noBackup, "$DIR/notices-seen"),
          seenFile = File(noBackup, "$DIR/smart-seen"),
          ownBackupFolder = { ownBackupFolder(gallery) },
          onError = { error -> graphRef[0]?.noteError(error) },
          onTripReport = { report -> graphRef[0]?.noteTrips(report) },
        ),
      )
    }
    return AlbumsGraph(graph.scope, build, ready, shareWork = { AlbumWorkScheduling.shareNow(context) }).also { graphRef[0] = it }
  }

  /**
   * Il nome della cartella `Backup/<dispositivo>/` di questo telefono (senza il prefisso), null finche' il backup non ha una cartella: la regola B degli
   * album smart (le foto del proprio backup sono "dello stesso dispositivo") e lo strumento di valutazione di P12 (`ownBackupFolder`).
   */
  fun ownBackupFolder(gallery: GalleryGraph): String? = gallery.backup?.settings?.deviceFolder?.value?.name
}
