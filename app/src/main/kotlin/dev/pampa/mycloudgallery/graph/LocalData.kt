package dev.pampa.mycloudgallery.graph

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
class HeartbeatStore(private val file: File) : HeartbeatSource {

  override suspend fun lastBeatAt(): Long? = withContext(Dispatchers.IO) {
    try {
      if (file.exists()) file.readText(Charsets.US_ASCII).trim().toLongOrNull()?.takeIf { it > 0 } else null
    } catch (e: IOException) {
      null
    }
  }

  suspend fun beat(at: Long) = withContext(Dispatchers.IO) {
    try {
      file.parentFile?.mkdirs()
      val tmp = File(file.parentFile, file.name + ".tmp")
      tmp.writeText(at.toString(), Charsets.US_ASCII)
      if (!tmp.renameTo(file)) {
        file.delete()
        tmp.renameTo(file)
      }
    } catch (e: IOException) {
      GalleryLog.w(TAG) { "battito non salvato" }
    }
  }

  suspend fun clear() = withContext(Dispatchers.IO) { file.delete() }

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
   * ([AppGraph.galleryMayRun]): una galleria mai partita non ha niente da fermare, e `GalleryGraph.shutdown` toglierebbe le porte
   * delle integrazioni per il resto del processo. Una galleria fermata non riparte nello stesso processo.
   *
   * Vero se e' andato tutto bene; un errore lascia la fine dell'onboarding ferma e si riprova (e' idempotente).
   */
  suspend fun eraseAccountData(stopGallery: Boolean): Boolean {
    val steps = Steps()
    eraseGalleryData(steps, stopGallery = stopGallery, allDatabases = false)
    return steps.ok
  }

  private suspend fun eraseGalleryData(steps: Steps, stopGallery: Boolean, allDatabases: Boolean) {
    // Fase 6 (P11, §1.4): `AiGraph.stop()` e `SearchGraph.stop()` PER PRIMI (motori rilasciati, indice di ricerca chiuso), poi la galleria.
    val aiStopped = if (stopGallery) steps.run("ai") { graph.aiStopper?.stop() } else true
    if (!aiStopped) return
    // Revisione B (AZ-2): cio' che l'AI ricorda in memoria di questo account (scelta della lettura del testo, esclusione, avvisi, scelte
    // personali in sospeso) si dimentica ora, prima dei file: il prossimo account nello stesso processo non eredita niente.
    steps.run("memoria dell'AI") { graph.aiForgetter?.stop() }
    // R7: la galleria viva si ferma (e si aspetta) PRIMA di toccare i suoi file; se non si ferma, i file restano.
    val stopped = if (stopGallery) steps.run("galleria") { graph.galleryStopper?.stop() } else true
    if (!stopped) return
    // Fase 5A (P7): il backup si e' gia' fermato per primo (`GalleryGraph.shutdown`); le sue impostazioni (cartella del dispositivo,
    // sorgenti scelte) appartengono alla famiglia che si lascia.
    steps.run("backup") { graph.backupSettingsStorage.clear() }
    steps.run("file") {
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
        for (name in NAS_FILES + AI_FILES + DeveloperTools.erasableDirs()) {
          val target = File(context.noBackupFilesDir, name)
          if (target.exists() && !target.deleteRecursively()) steps.ok = false
        }
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
    ) + BackupRunMemory.FILES

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
 * "Configura di nuovo" ha gia' riavviato il processo se la galleria era partita (`AppGraph`), quindi qui di norma non c'e' niente da
 * fermare. Se c'e' (il riavvio non e' riuscito) si ferma la galleria, si tolgono i dati e il processo si riavvia subito dopo:
 * una galleria fermata non riparte, e la Libreria resterebbe vuota fino al prossimo avvio.
 */
class AppPreviousAccountData(private val eraser: AppLocalDataEraser, private val graph: AppGraph) : PreviousAccountData {

  override suspend fun erase(): Boolean {
    val running = graph.galleryMayRun()
    val erased = eraser.eraseAccountData(stopGallery = running)
    if (erased && running) graph.restartSoon()
    return erased
  }
}
