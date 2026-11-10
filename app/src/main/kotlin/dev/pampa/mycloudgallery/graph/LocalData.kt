package dev.pampa.mycloudgallery.graph

import androidx.core.app.NotificationManagerCompat
import dev.pampa.mycloudgallery.notify.AppNotifications
import dev.pampa.mycloudgallery.work.MemoriesWorkScheduling
import android.content.Context
import dev.pampa.mycloudgallery.ai.AiGraph
import dev.pampa.mycloudgallery.ai.AiStageMark
import dev.pampa.mycloudgallery.ai.AiStatusMemory
import dev.pampa.mycloudgallery.ai.AppIndexerControl
import dev.pampa.mycloudgallery.ai.FileAiUnsupported
import dev.pampa.mycloudgallery.ai.FileCompactionMemory
import dev.pampa.mycloudgallery.ai.PersonalLocalSettings
import dev.pampa.mycloudgallery.ai.TextReadingMemory
import dev.pampa.mycloudgallery.backup.BackupRunMemory
import dev.pampa.mycloudgallery.search.SearchGraph
import dev.pampa.mycloudgallery.work.AiStages
import dev.pampa.mycloudgallery.core.common.files.Patience
import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.family.contract.LocalDataEraser
import dev.pampa.mycloudgallery.core.family.lifecycle.FileWipeJournal
import dev.pampa.mycloudgallery.core.family.onboarding.PreviousAccountData
import dev.pampa.mycloudgallery.core.network.contract.VpnControlConfig
import dev.pampa.mycloudgallery.debugtools.DeveloperTools
import dev.pampa.mycloudgallery.feature.diagnostics.contributors.HeartbeatSource
import dev.pampa.mycloudgallery.nas.NasGraph
import dev.pampa.mycloudgallery.nas.NasOrganizer
import dev.pampa.mycloudgallery.work.NasStages
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Il "battito" del lavoro in background (§2.7, E-P19): l'ora del dispositivo dell'ultimo giro di `TrustSyncWorker`. La
 * Diagnostica ne ricava "ore dall'ultimo battito" e l'avviso "il profilo puo' restare fermo". Un file con un numero.
 */
class HeartbeatStore(private val file: File, private val patience: Patience = Patience.STANDARD) : HeartbeatSource {

  override suspend fun lastBeatAt(): Long? = withContext(Dispatchers.IO) {
    try {
      StateFiles.readText(file, Charsets.US_ASCII, patience = patience)?.trim()?.toLongOrNull()?.takeIf { it > 0 }
    } catch (e: IOException) {
      null
    }
  }

  suspend fun beat(at: Long) = withContext(Dispatchers.IO) {
    try {
      StateFiles.writeText(file, at.toString(), Charsets.US_ASCII, patience = patience)
    } catch (e: IOException) {
      GalleryLog.w(TAG) { "battito non salvato" }
    }
  }

  /** Toglie il file del battito. Se non ci riesce lancia `IOException`: un battito vecchio che resta direbbe "ancora vivo" a un profilo azzerato. */
  suspend fun clear() = withContext(Dispatchers.IO) {
    if (!StateFiles.delete(file, patience = patience)) throw IOException("battito non tolto")
  }

  private companion object {
    const val TAG = "Heartbeat"
  }
}

/** Ferma la galleria del telefono (sincronizzazione, repository della timeline, scrittore dell'istantanea, immagini, database). */
fun interface GalleryStopper {
  suspend fun stop()
}

/**
 * La fase (5) dell'azzeramento (`LocalDataEraser`, docs/famiglia-dispositivi.md §3): cancella cio' che l'app sa di
 * questa famiglia sul telefono. Lo stato fidato e le richieste viste li cancella gia' `DeviceSelfWipe`; le chiavi e le
 * credenziali sono gia' sparite nelle fasi (1)-(3).
 *
 * - **attraverso le loro API** (DataStore non tollera che gli si tolga il file da sotto): avanzamento dell'onboarding,
 *   account NAS, impronta di casa, controllo di WireGuard, battito;
 * - **come file**: cache, database (la replica dell'indice, Fase 4), miniature e anteprime future.
 *
 * **Prima si ferma la galleria viva** (R7): la sincronizzazione del telefono, i lavori in background, il repository della
 * timeline con il suo scrittore dell'istantanea, la pipeline delle immagini, il database. Solo dopo si cancellano
 * `replica/` e i database, cosi' niente puo' riscrivere `replica/timeline.idx` (gli id, le date e i riferimenti del
 * MediaStore) su un telefono azzerato. Se la fermata fallisce non si cancella niente: la fase resta da fare e si riprova
 * al prossimo avvio (e' idempotente).
 *
 * **Mai** il diario dell'azzeramento ([FileWipeJournal.FILE_NAME]): e' quello che fa riprendere un azzeramento
 * interrotto e mostrare "dispositivo azzerato". Restano anche il registro della Diagnostica (solo codici e numeri, mai
 * dati personali) e le preferenze del telefono (blocco app, preset delle regole): non dicono niente della famiglia.
 *
 * Vero se e' andato tutto bene; un errore si riprova al prossimo avvio (la fase resta da fare).
 */
class AppLocalDataEraser(private val context: Context, private val graph: AppGraph) : LocalDataEraser {

  /** Un passo dopo l'altro: un fallimento lo registra e segna l'azzeramento come non finito, senza fermare gli altri. */
  private class Steps {
    var ok = true

    /** Esegue un passo; vero se e' riuscito. */
    suspend fun run(name: String, block: suspend () -> Unit): Boolean {
      try {
        block()
        return true
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.e(TAG, e) { "cancellazione non riuscita: $name" }
        ok = false
        return false
      }
    }
  }

  override suspend fun eraseAll(): Boolean {
    val steps = Steps()
    // Fase 10 (P13, D42): PER PRIMI i widget, vuoti sulla Home prima di cancellare qualunque cosa (poi lavoro annullato e registro tolto), e le altre finestre chiuse.
    steps.run("widget") { graph.widgetStopper?.stop() }
    steps.run("finestre") { graph.windowsCloser?.invoke() }
    steps.run("onboarding") { graph.onboardingStore.clear() }
    steps.run("account") { graph.accountStore.clear() }
    steps.run("casa") { graph.homeStore.clear() }
    steps.run("vpn") { graph.vpnConfigStore.save(VpnControlConfig.OFF) }
    steps.run("battito") { graph.heartbeat.clear() }
    eraseGalleryData(steps, stopGallery = true, allDatabases = true)
    // Fase 6 (P11): i modelli verificati, i parziali e i programmi compilati (sono pubblici, ma di questo dispositivo si toglie tutto).
    // Le copie gia' sul NAS restano (gli altri le possono usare, l'impronta le rende sicure): si tolgono dal PC (§1.4).
    // Solo con l'AI ferma (motori rilasciati): altrimenti si riprova al prossimo avvio con il resto.
    if (steps.ok) steps.run("modelli") {
      withContext(Dispatchers.IO) {
        val models = File(context.noBackupFilesDir, AiGraph.MODELS_DIR)
        if (models.exists() && !models.deleteRecursively()) steps.ok = false
      }
    }
    // Fase 10 (P13, D42): lo stato del manifest firmato e l'ora della sua ultima lettura (la prima lettura dopo la riconfigurazione rimette un blocco, L561).
    steps.run("manifest") {
      graph.release?.clear()
      withContext(Dispatchers.IO) {
        val release = File(context.noBackupFilesDir, dev.pampa.mycloudgallery.release.ReleaseGraph.DIR)
        if (release.exists() && !release.deleteRecursively()) steps.ok = false
      }
    }
    return steps.ok
  }

  /**
   * I dati locali **di un account** e solo quelli (R6 della revisione 5B): catalogo (i media dei solo-NAS, le righe del telefono,
   * i diari del backup, di "Libera spazio" e delle sessioni di modifica), replica e pacchi dell'indice, dati personali,
   * miniature e anteprime, file preparati delle versioni, file in arrivo, cache (anche di modifica e di condivisione), impostazioni
   * del backup. **Non** l'avanzamento dell'onboarding in corso, l'account del NAS, l'impronta di casa, il controllo di
   * WireGuard, le chiavi, e mai le foto del telefono ne' il NAS. Chiude solo il database della galleria: gli altri (WorkManager)
   * restano aperti.
   *
   * [stopGallery]: ferma la galleria viva prima (R7). Serve solo se in questo processo la famiglia e' stata pronta
   * ([AppGraph.galleryMayRun]). Se non e' mai partita, [AppGraph.idleGalleryStopper] ferma comunque le query del backup e
   * chiude Room prima della cancellazione. Una galleria fermata non riparte nello stesso processo.
   *
   * Vero se e' andato tutto bene; un errore lascia la fine dell'onboarding ferma e si riprova (e' idempotente).
   */
  suspend fun eraseAccountData(stopGallery: Boolean): Boolean {
    val steps = Steps()
    eraseGalleryData(steps, stopGallery = stopGallery, allDatabases = false)
    return steps.ok
  }

  private suspend fun eraseGalleryData(steps: Steps, stopGallery: Boolean, allDatabases: Boolean) {
    // Fase 10 (P13, D42): i widget per primi anche per i soli dati di un account (le foto sulla Home sono del vecchio account). Non fa niente se gia' fermi.
    steps.run("widget") { graph.widgetStopper?.stop() }
    // Fase 9 (P13, §1.4, D37): ricordi e mappa PER PRIMI. Niente si monta o si ricostruisce piu', il lavoro `memories-notify` e la notifica in tendina si
    // tolgono; poi, con il resto dei file, `memories/` (scaffale, registro e preferenze delle notifiche, chi e' in tendina, musica) e `map/` (database e cache
    // di MapLibre, versione del TileJSON, impostazioni e camera). Anche per i soli dati di un account: lo scaffale e la camera sono del vecchio account.
    val memoriesStopped = if (stopGallery) steps.run("ricordi") { graph.memoriesStopper?.stop() } else true
    if (!memoriesStopped) return
    val mapStopped = if (stopGallery) steps.run("mappa") { graph.mapStopper?.stop() } else true
    if (!mapStopped) return
    // Il lavoro unico delle notifiche e la notifica in tendina si tolgono sempre (E-NT15): possono venire da un processo di prima.
    steps.run("notifica dei ricordi") {
      MemoriesWorkScheduling.cancel(context)
      NotificationManagerCompat.from(context).cancel(AppNotifications.ID_MEMORY)
      // I4: i permessi di lettura delle canzoni anche qui (sempre): il passo "ricordi" non gira se la galleria non e' mai partita in questo processo.
      dev.pampa.mycloudgallery.memories.MusicPermissions.releaseAll(context)
    }
    // Fase 8 (P13, §1.4, E-F35): le persone PER PRIME. Motori rilasciati, calcolo fermo, niente si apre piu' (il recinto), i database dei volti chiusi; poi le chiavi dei
    // volti (una per generazione) prima di qualunque dato, e i file. Anche per i soli dati di un account: le chiavi e i database dei volti sono del vecchio account.
    val facesStopped = if (stopGallery) steps.run("volti") { graph.facesStopper?.stop() } else true
    if (!facesStopped) return
    steps.run("chiavi dei volti") {
      if (!graph.facesKeys.destroyAll()) throw java.io.IOException("Chiavi dei volti non distrutte.")
    }
    // Fase 7 (P13, §1.4): gli album PER PRIMI. Diario della condivisione, replica, proposte e casa si tolgono solo a operazioni ferme: i giri
    // della condivisione si fermano e si aspettano qui, le operazioni sui file (4B) le ferma la galleria (sono sue figlie) prima dei file.
    // Gli album si montano solo con la galleria partita: se non si ferma la galleria (mai partita in questo processo) non c'e' niente da fermare.
    val albumsStopped = if (stopGallery) steps.run("album") { graph.albumsStopper?.stop() } else true
    if (!albumsStopped) return
    // Fase 6 (P11, §1.4): `AiGraph.stop()` e `SearchGraph.stop()` (motori rilasciati, indice di ricerca chiuso), poi la galleria.
    val aiStopped = if (stopGallery) steps.run("ai") { graph.aiStopper?.stop() } else true
    if (!aiStopped) return
    // Revisione B (AZ-2): cio' che l'AI ricorda in memoria di questo account (scelta della lettura del testo, esclusione, avvisi, scelte
    // personali in sospeso) si dimentica ora, prima dei file: il prossimo account nello stesso processo non eredita niente.
    steps.run("memoria dell'AI") { graph.aiForgetter?.stop() }
    // R7: la galleria viva si ferma (e si aspetta) PRIMA di toccare i suoi file; se non si ferma, i file restano.
    val stopped = steps.run("galleria") {
      if (stopGallery) graph.galleryStopper?.stop() else graph.idleGalleryStopper?.stop()
    }
    if (!stopped) return
    // Fase 5A (P7): il backup si e' gia' fermato per primo (`GalleryGraph.shutdown`); le sue impostazioni (cartella del dispositivo,
    // sorgenti scelte) appartengono alla famiglia che si lascia.
    steps.run("backup") { graph.backupSettingsStorage.clear() }
    steps.run("file") {
      // Avvio istantaneo: le forme delle pagine (solo numeri) non si riscrivono piu' in questo processo; il file sta in `replica/`.
      dev.pampa.mycloudgallery.core.common.perf.InstantShapes.clear()
      withContext(Dispatchers.IO) {
        if (!deleteChildren(context.cacheDir)) steps.ok = false
        if (allDatabases) {
          for (name in context.databaseList()) {
            if (!context.deleteDatabase(name) && File(context.getDatabasePath(name).path).exists()) steps.ok = false
          }
        } else if (!context.deleteDatabase(GalleryDatabase.NAME) && File(context.getDatabasePath(GalleryDatabase.NAME).path).exists()) {
          steps.ok = false
        }
        for (dir in MEDIA_DIRS) {
          val target = File(context.noBackupFilesDir, dir)
          if (target.exists() && !target.deleteRecursively()) steps.ok = false
        }
        // Fase 4A (P11): il NAS nella Libreria. I pacchi stanno gia' in `replica/`, l'outbox dell'indice no (miniature
        // preparate qui, in attesa di pubblicazione): si toglie con i piccoli file di stato (tentativi, emergenza, battiti).
        // Fase 4B (P16): il registro locale dei dati personali (`personal/`). Il diario delle operazioni e del cestino sta nel
        // database (tolto qui sopra); le operazioni in corso e i loro lavori li ha gia' fermati la galleria (NasOrganizer.stop).
        // I11: in debug anche i dati dello strumento di valutazione della ricerca (`eval/`); in release non ce ne sono.
        // Fase 8 (P13): i database dei volti (`faces/faces-<G>.db` con `-wal`, `-shm`, `-journal`, anche con `allDatabases = false`), le chiavi avvolte, la memoria
        // locale (`faces-local`), il diario della cancellazione, il registro delle teste personali e il battito del calcolo.
        for (name in NAS_FILES + AI_FILES + FACES_FILES + PHASE9_FILES + PHASE10_FILES + ALBUMS_TAB_FILES + DeveloperTools.erasableDirs()) {
          val target = File(context.noBackupFilesDir, name)
          if (target.exists() && !target.deleteRecursively()) steps.ok = false
        }
        // Fase 9 (R5, seconda difesa): se MapLibre non ha potuto spostare il suo database in `map/` (o l'ha creato prima di spostarlo), le tessere viste (le zone guardate) stanno nelle
        // cartelle di serie della libreria: `mbgl-offline.db` e i suoi `-journal`, `-wal`, `-shm`. L'azzeramento li toglie dovunque la libreria li metta.
        if (!deleteMapLibreDatabases(listOf(context.filesDir, context.getExternalFilesDir(null), context.noBackupFilesDir, context.cacheDir, context.externalCacheDir))) steps.ok = false
      }
    }
  }

  private fun deleteChildren(dir: File?): Boolean {
    if (dir == null || !dir.exists()) return true
    return dir.listFiles().orEmpty().all { it.deleteRecursively() }
  }

  companion object {
    private const val TAG = "LocalDataEraser"

    /**
     * Toglie i file del database di MapLibre (`mbgl-offline.db*`) che stanno **direttamente** in [dirs] (le cartelle di serie della libreria: l'azzeramento non guarda nelle altre,
     * mai la radice per intero). Vero se nessuno e' rimasto; una cartella assente o null non e' un errore.
     */
    internal fun deleteMapLibreDatabases(dirs: List<File?>): Boolean {
      var ok = true
      for (dir in dirs.filterNotNull().distinct()) {
        for (file in dir.listFiles().orEmpty()) {
          if (file.name.startsWith(dev.pampa.mycloudgallery.feature.map.map.MapLibreInit.DATABASE_PREFIX) && !file.deleteRecursively()) ok = false
        }
      }
      return ok
    }

    /**
     * Le cartelle dei dati della galleria in `noBackupFilesDir` (le riempiono le fasi 3-6). Mai la radice. Dalla 5B anche i file
     * preparati delle versioni (correzioni, risultati degli editor) e le copie dei file ricevuti: stavano fuori dall'elenco, e un
     * azzeramento li lasciava sul telefono.
     */
    val MEDIA_DIRS = listOf("thumbnails", "previews", "replica", VERSIONS_DIR, INCOMING_DIR)

    /** Le due cartelle della 5B in `noBackupFilesDir`: i nomi sono quelli di `FiveBGraph` (privati), e `CorrezioniConfigurazione5BAppTest` li incrocia con i file veri. */
    const val VERSIONS_DIR = "versions"
    const val INCOMING_DIR = "incoming"

    /**
     * I file e le cartelle del NAS nella Libreria in `noBackupFilesDir` (Fase 4A): outbox, tentativi, emergenza, battiti; dalla 4B il
     * registro dei dati personali; dalla 5A la memoria del backup (battiti, ultimo giro, ultimo problema).
     */
    val NAS_FILES = listOf(
      NasGraph.OUTBOX_DIR,
      NasGraph.ATTEMPTS_FILE,
      NasGraph.EMERGENCY_FILE,
      NasGraph.COVERS_FILE,
      NasOrganizer.PERSONAL_DIR,
      NasOrganizer.NASOPS_DIR,
      NasStages.HEARTBEAT_SCAN,
      NasStages.HEARTBEAT_SYNC,
      NasStages.HEARTBEAT_PREP,
      // Fase 7 (P13): i registri degli album (caricamenti, piani dei trasferimenti, richieste del guardiano, avvisi visti, segni "visto").
      dev.pampa.mycloudgallery.albums.AlbumsWiring.DIR,
    ) + BackupRunMemory.FILES

    /** Fase 8 (P13): i file locali delle persone (database, chiavi avvolte, memoria locale, diario della cancellazione, teste personali, battito). */
    val FACES_FILES: List<String> = dev.pampa.mycloudgallery.faces.FacesWiring.FILES

    /**
     * Fase 9 (P13, D37): `memories/` (scaffale, registro e preferenze delle notifiche, chi e' in tendina, musica, ritardi) e `map/` (database e cache di MapLibre,
     * versione del TileJSON, impostazioni della mappa e camera). I file della valutazione dei ricordi (`eval/`, solo debug) li toglie `DeveloperTools.erasableDirs`.
     */
    val PHASE9_FILES: List<String> = listOf(
      dev.pampa.mycloudgallery.memories.MemoriesWiring.DIR,
      dev.pampa.mycloudgallery.feature.map.map.MapLibreInit.MAP_DIR,
    )

    /**
     * FAB-13 (fase grafica, G2b): l'ultima apertura delle voci della scheda Album (DataStore `album_openings`). Le sue chiavi sono gli id degli album di un account: restava
     * dopo l'azzeramento e dopo il cambio di account, e l'ordine della scheda del nuovo account partiva da aperture del vecchio. Il processo si riavvia subito dopo (azzeramento e
     * cambio di account), quindi il DataStore non riscrive il vecchio valore.
     */
    val ALBUMS_TAB_FILES: List<String> = dev.pampa.mycloudgallery.albums.DataStoreAlbumOpenings.FILES

    /** Fase 10 (P13, D42): il registro del widget (`widget/`). Lo stato del manifest (`release/`) lo toglie solo l'azzeramento completo ([eraseAll]). */
    val PHASE10_FILES: List<String> = listOf(dev.pampa.mycloudgallery.widget.WidgetGraph.DIR)

    /**
     * Fase 6 (P11): il buffer di analisi, l'indice di ricerca (vettori, registro delle origini, righe del testo letto, corpi propri), i
     * media "non supportati", la memoria della lettura del testo e il battito del calcolo. Le tabelle `ai_*` e la cronologia stanno nel
     * database della galleria (tolto con lui). I modelli li toglie solo l'azzeramento completo ([eraseAll]). Revisione A (R14): anche
     * quando si sono compattate le teste `ai` proprie.
     */
    val AI_FILES = listOf(
      AiGraph.BUFFER_DIR,
      SearchGraph.DIR,
      FileAiUnsupported.FILE,
      TextReadingMemory.FILE,
      PersonalLocalSettings.FILE,
      AiStatusMemory.FILE,
      AiStageMark.FILE,
      AppIndexerControl.PROBE_FILE,
      AiStages.HEARTBEAT_AI,
      FileCompactionMemory.FILE,
    )
  }
}

/**
 * I dati del vecchio account, tolti alla fine di un onboarding fatto con un altro account o un altro NAS (R6 della revisione 5B):
 * l'azzeramento dei dati locali senza le parti che toccano l'onboarding in corso (`AppLocalDataEraser.eraseAccountData`).
 *
 * "Configura di nuovo" ha gia' riavviato il processo se la galleria era partita (`AppGraph`). Anche nel nuovo processo il backup
 * puo' aprire Room prima della famiglia pronta: se c'e' il grafo si ferma, si tolgono i dati e il processo si riavvia subito dopo.
 * Una galleria fermata non riparte, e la Libreria resterebbe vuota fino al prossimo avvio.
 */
class AppPreviousAccountData(private val eraser: AppLocalDataEraser, private val graph: AppGraph) : PreviousAccountData {

  override suspend fun erase(): Boolean {
    val running = graph.galleryMayRun()
    val needsRestart = running || graph.idleGalleryStopper != null
    val erased = eraser.eraseAccountData(stopGallery = running)
    if (erased && needsRestart) graph.restartSoon()
    return erased
  }
}
