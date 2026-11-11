package dev.pampa.mycloudgallery.backup

import android.content.Context
import dev.pampa.mycloudgallery.core.backup.contract.BackupAction
import dev.pampa.mycloudgallery.core.backup.contract.BackupFence
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.MediaBackupState
import dev.pampa.mycloudgallery.core.backup.contract.NotOnNasReason
import dev.pampa.mycloudgallery.core.backup.contract.SourceStatus
import dev.pampa.mycloudgallery.core.backup.contract.StopBy
import dev.pampa.mycloudgallery.core.backup.settings.DeviceFolderPolicy
import dev.pampa.mycloudgallery.core.backup.settings.FolderDecision
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.metadata.contract.SourceClassifier
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.settings.RuleSettingsStore
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.backup.contract.BackupActions
import dev.pampa.mycloudgallery.feature.backup.contract.BackupOverview
import dev.pampa.mycloudgallery.feature.backup.contract.BackupRules
import dev.pampa.mycloudgallery.feature.backup.contract.BackupScreen
import dev.pampa.mycloudgallery.feature.backup.contract.DeviceFolderProposal
import dev.pampa.mycloudgallery.feature.backup.contract.DeviceFolderProposals
import dev.pampa.mycloudgallery.feature.backup.contract.ExistingFolder
import dev.pampa.mycloudgallery.feature.backup.contract.PhoneFolder
import dev.pampa.mycloudgallery.feature.backup.contract.PhoneFolders
import dev.pampa.mycloudgallery.feature.nas.contract.NasScreen
import dev.pampa.mycloudgallery.feature.settings.backup.BackupEntryStatus
import dev.pampa.mycloudgallery.feature.viewer.info.MediaBackupStates
import dev.pampa.mycloudgallery.work.BackupWorkScheduling
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// LE PORTE DELLE SCHERMATE DEL BACKUP in :app (Fase 5A, P7; docs/fase5-progetto.md §1.5, §2.1, §2.6, D7, D16, D34). feature-backup,
// feature-settings e feature-viewer dichiarano le porte e non si conoscono fra loro: qui le adattatori sul grafo del backup. Nulla di
// quello che passa da qui porta nomi di file, percorsi di share o account nei log; i nomi delle cartelle sono dati dell'utente e si
// mostrano soltanto.

/** Lo stato del backup e le righe delle sorgenti per la pagina Backup, e lo stato in una frase per la voce delle Impostazioni. */
class AppBackupOverview(backup: BackupGraph) : BackupOverview, BackupEntryStatus {
  override val status: StateFlow<BackupStatus> = backup.status
  override val sources: StateFlow<List<SourceStatus>> = backup.sources
}

/**
 * Le cartelle del telefono che si possono scegliere come sorgente (§2.1): le righe del MediaStore **gia' lette** dal catalogo, una per
 * cartella con numero e peso. Il catalogo e' la stessa verita' da cui il piano sceglie i candidati: una seconda lettura completa del
 * MediaStore (decine di migliaia di righe) per riempire una pagina delle impostazioni non serve. Le cartelle della fotocamera e degli
 * screenshot hanno il loro interruttore e non si ripetono; con la recinzione di D13 (debug, account di prova) solo le due cartelle di prova.
 *
 * O-2 (prove sul campo): quelle cartelle si riconoscono dal **percorso** (`SourceClassifier.folderOwner`), non dal tipo del media. Prima si
 * toglievano gli screenshot ovunque fossero: due "Screenshot_….png" dentro `mcg-prova` non si contavano nella loro cartella, e una cartella
 * con soli screenshot non si poteva nemmeno scegliere. Un file in una cartella scelta si salva con quella cartella, qualunque sia il suo tipo.
 */
class CatalogPhoneFolders(
  private val database: GalleryDatabase,
  private val fence: BackupFence?,
  private val account: () -> String?,
) : PhoneFolders {

  override suspend fun folders(): List<PhoneFolder> = withContext(Dispatchers.IO) {
    val me = account()
    val out = ArrayList<PhoneFolder>()
    database.openHelper.readableDatabase.query(
      "SELECT l.volume, l.relative_path, COUNT(*), COALESCE(SUM(l.size), 0) FROM media_location l " +
        "JOIN media_version v ON v.version_id = l.version_id JOIN media m ON m.media_id = v.media_id " +
        "WHERE l.location_type = 'phone' AND l.state = 'present' AND l.volume IS NOT NULL AND l.relative_path IS NOT NULL " +
        "GROUP BY l.volume, l.relative_path ORDER BY COUNT(*) DESC LIMIT ?",
      arrayOf<Any?>(MAX_FOLDERS + OWNED_FOLDERS_ROOM),
    ).use { c ->
      while (c.moveToNext() && out.size < MAX_FOLDERS) {
        val volume = c.getString(0)
        val path = c.getString(1)
        if (volume.isBlank() || path.isBlank()) continue
        // Le cartelle della fotocamera e degli screenshot hanno il loro interruttore (O-2: per percorso, non per tipo).
        if (SourceClassifier.folderOwner(path) != null) continue
        // Fail closed: con una recinzione senza account noto non si offre niente.
        if (fence != null && (me == null || !fence.allowsPhoneFolder(me, path))) continue
        out += PhoneFolder(BackupSource.Folder(volume, path), media = c.getInt(2), bytes = c.getLong(3))
      }
    }
    out
  }

  private companion object {
    const val MAX_FOLDERS = 200

    /** Posto in piu' per le cartelle della fotocamera e degli screenshot, che si leggono e poi si saltano. */
    const val OWNED_FOLDERS_ROOM = 16
  }
}

/** "Proteggi subito le foto nuove" (D34, Q7 (a)): l'impostazione del dispositivo, nelle regole. */
class AppBackupRules(private val store: RuleSettingsStore, scope: CoroutineScope) : BackupRules {
  override val protectNewPhotos: StateFlow<Boolean> =
    store.observe().map { it.protectNewPhotos }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, true)

  override suspend fun setProtectNewPhotos(on: Boolean) {
    store.update { it.copy(protectNewPhotos = on) }
  }
}

/**
 * La proposta per la cartella del dispositivo (D7, E-B6, E-B16): il nome del dispositivo ripulito, l'elenco di `Backup/` letto in **sola
 * lettura** (con la recinzione di D13, `mcg-campo/Backup/`) e `DeviceFolderPolicy`. Null se il NAS non risponde o non c'e' un'area privata:
 * la pagina riprova quando il backup e' pronto.
 */
class AppDeviceFolderProposals(private val backup: BackupGraph) : DeviceFolderProposals {

  override suspend fun propose(): DeviceFolderProposal? {
    val share = backup.privateShare() ?: return null
    val name = backup.deviceName()?.takeIf { it.isNotBlank() } ?: DeviceFolderPolicy.FALLBACK_NAME
    val root = BackupRoots.of(backup.fenceRoot())
    val listing = when (val result = backup.browse(share).list(root, LIST_LIMIT)) {
      is NasResult.Ok -> result.value.entries.filter { it.isDirectory }.map { it.name }
      // `Backup/` non c'e' ancora: il primo backup la crea (nessuna cartella da continuare).
      is NasResult.Err -> if (result.error is NasError.NotFound) emptyList() else return null
    }
    val wanted = DeviceFolderPolicy.folderFor(name)
    val usedByOther = backup.otherDeviceNames().any { DeviceFolderPolicy.folderFor(it).name.equals(wanted.name, ignoreCase = true) }
    return when (val decision = DeviceFolderPolicy.decide(name, listing, usedByOther)) {
      is FolderDecision.Create -> DeviceFolderProposal(decision.folder, existing = null, alternative = null)
      is FolderDecision.AskExisting -> {
        val files = countFiles(share, root.child(decision.existing.name))
        DeviceFolderProposal(decision.existing, ExistingFolder(files), decision.newFolder)
      }
    }
  }

  /** Quanti file ci sono in una cartella `Backup/<nome>/` (anno e mese compresi), con un tetto: serve solo a dire "ci sono gia' dei file". */
  private suspend fun countFiles(share: String, folder: SharePath): Int {
    val browser = backup.browse(share)
    var files = 0
    var visited = 0
    val pending = ArrayDeque<SharePath>()
    pending.add(folder)
    while (pending.isNotEmpty() && visited < MAX_FOLDERS && files < MAX_FILES) {
      val dir = pending.removeFirst()
      visited++
      val page = (browser.list(dir, LIST_LIMIT) as? NasResult.Ok)?.value ?: continue
      for (entry in page.entries) {
        if (entry.isDirectory) {
          runCatching { dir.child(entry.name) }.getOrNull()?.let { pending.add(it) }
        } else {
          files++
        }
      }
    }
    return files.coerceAtMost(MAX_FILES)
  }

  private companion object {
    const val LIST_LIMIT = 500
    const val MAX_FOLDERS = 40
    const val MAX_FILES = 99_999
  }
}

/**
 * Sul telefono: le due letture del diario e del catalogo che servono alla riga "Backup" del viewer e che i contratti non offrono (il
 * diario ha l'elemento aperto di una **versione**, qui si parte dal media). Una query ciascuna, su indici.
 */
class BackupMediaProbe(private val database: GalleryDatabase) {

  /** L'elemento aperto del diario di un media: stato, byte gia' sul NAS e dimensione. */
  class OpenItem(val state: String, val resumeAt: Long, val size: Long)

  /** Da dove viene il media sul telefono: il codice della sorgente e la cartella. */
  class Origin(val source: String, val volume: String?, val relativePath: String?)

  suspend fun openItem(id: MediaId): OpenItem? = withContext(Dispatchers.IO) {
    runCatching {
      database.openHelper.readableDatabase.query(
        "SELECT state, resume_at, size FROM backup_item WHERE media_id = ? AND is_open = 1 ORDER BY item_id DESC LIMIT 1",
        arrayOf<Any?>(id.value),
      ).use { c -> if (c.moveToFirst()) OpenItem(c.getString(0), c.getLong(1), c.getLong(2)) else null }
    }.getOrNull()
  }

  suspend fun originOf(id: MediaId): Origin? = withContext(Dispatchers.IO) {
    runCatching {
      database.openHelper.readableDatabase.query(
        "SELECT m.source, l.volume, l.relative_path FROM media m JOIN media_version v ON v.media_id = m.media_id " +
          "JOIN media_location l ON l.version_id = v.version_id WHERE m.media_id = ? AND l.location_type = 'phone' AND l.state = 'present' LIMIT 1",
        arrayOf<Any?>(id.value),
      ).use { c -> if (c.moveToFirst()) Origin(c.getString(0), c.getString(1), c.getString(2)) else null }
    }.getOrNull()
  }
}

/**
 * La riga "Backup" del pannello informazioni del viewer (§1.5 `MediaBackupState`, D15, D16): dal catalogo (`mediaSafety`), dal diario
 * (l'elemento aperto) e dallo stato del motore, adesso e a ogni cambiamento. "In coda", "in caricamento" e "in verifica" stanno solo qui
 * e nella pagina Backup. Si ascolta soltanto con il pannello aperto.
 */
class AppMediaBackupStates(private val backup: BackupGraph, private val probe: BackupMediaProbe) : MediaBackupStates {

  override fun observe(id: MediaId): Flow<MediaBackupState> = flow {
    emit(stateOf(id))
    backup.changes().collect { emit(stateOf(id)) }
  }.distinctUntilChanged().flowOn(Dispatchers.Default)

  /** Lo stato di [id] adesso. */
  suspend fun stateOf(id: MediaId): MediaBackupState {
    val catalog = backup.catalogOrNull() ?: return MediaBackupState.NotOnNas(NotOnNasReason.NOT_YET)
    val safety = catalog.mediaSafety(id) ?: return MediaBackupState.NotOnNas(NotOnNasReason.NOT_YET)
    if (safety.status == SafetyStatus.NOT_MINE) return MediaBackupState.NotMine
    if (safety.status == SafetyStatus.LOST) return MediaBackupState.LostBeforeBackup
    // Un problema del media vince su "al sicuro" e su "in coda" (D15): il motivo preciso sta qui.
    safety.problem?.let { return MediaBackupState.Problem(it) }
    return when (safety.status) {
      SafetyStatus.SAFE -> MediaBackupState.Safe(safety.verifiedAtMillis ?: 0L)
      SafetyStatus.ONLY_COMMON_COPY -> MediaBackupState.OnlyCommonCopy
      SafetyStatus.PRIVATE_COPY_UNVERIFIED -> unverified(id)
      SafetyStatus.NO_NAS_COPY -> pending(id)
      SafetyStatus.NOT_MINE, SafetyStatus.LOST -> MediaBackupState.NotMine
    }
  }

  private suspend fun unverified(id: MediaId): MediaBackupState {
    val item = probe.openItem(id) ?: return MediaBackupState.Unverified
    return if (backup.status.value is BackupStatus.Running) MediaBackupState.Verifying(item.size, item.size) else MediaBackupState.Unverified
  }

  private suspend fun pending(id: MediaId): MediaBackupState {
    val status = backup.status.value
    if (status is BackupStatus.TestOnly || status is BackupStatus.NotConfigured) return MediaBackupState.NotOnNas(NotOnNasReason.BACKUP_OFF)
    val origin = probe.originOf(id)
    if (origin != null && !chosen(origin)) return MediaBackupState.NotOnNas(NotOnNasReason.SOURCE_OFF)
    if (backup.settings.deviceFolder.value == null) return MediaBackupState.NotOnNas(NotOnNasReason.NOT_YET)
    val reason = (status as? BackupStatus.Waiting)?.reason
    val running = status is BackupStatus.Running
    val item = probe.openItem(id) ?: return MediaBackupState.Queued(reason)
    return when (item.state) {
      "hashed", "uploading" -> if (running) MediaBackupState.Uploading(item.resumeAt, item.size) else MediaBackupState.Queued(reason)
      "uploaded", "verified", "publishing", "published", "linked", "copying", "copied" ->
        if (running) MediaBackupState.Verifying(item.size, item.size) else MediaBackupState.Queued(reason)
      else -> MediaBackupState.Queued(reason)
    }
  }

  private fun chosen(origin: BackupProbeOrigin): Boolean {
    val sources = backup.settings.sources.value
    return when {
      origin.source == MediaSource.CAMERA.code && BackupSource.Camera in sources -> true
      origin.source == MediaSource.SCREENSHOT.code && BackupSource.Screenshots in sources -> true
      origin.volume != null && origin.relativePath != null && BackupSource.Folder(origin.volume, origin.relativePath) in sources -> true
      else -> false
    }
  }
}

private typealias BackupProbeOrigin = BackupMediaProbe.Origin

/** Una conferma da chiedere all'utente prima di far partire un lavoro sui dati mobili o in roaming (proposta 10). */
class BackupPrompt(val message: String, internal val trigger: BackupTrigger) {
  override fun toString(): String = "BackupPrompt"
}

/**
 * Cio' che le azioni del backup chiedono alla schermata: navigazione, permessi, impostazioni di sistema. Lo registra la galleria
 * (`BackupHostEffect`) finche' e' visibile; senza, un'azione che ne ha bisogno non fa niente (mai una richiesta senza una schermata e
 * senza un tocco dell'utente).
 */
interface BackupActionsHost {
  fun openScreen(screen: BackupScreen)

  fun openDiagnostics()

  fun openPassword()

  fun openNas(screen: NasScreen)

  fun openGalleryTrash()

  fun requestMediaAccess()

  fun requestMediaLocation()

  fun openStorageSettings()

  fun openBatteryGuide()
}

/**
 * Le azioni della pagina Backup (§2.6, colonna "Azione"; D11; D7): partono **solo** da un tocco. "Fai ora" (e "Riprendi", che e' lo stesso
 * tasto dopo "Ferma") e "Inizia ora" finiscono la pausa dell'utente (R4) e sono lavori avviati dall'utente (UIDT, [BackupGraph.jobs]); sui dati mobili le regole chiedono prima una conferma con la stima ([prompt]). "Stanotte" non
 * avvia niente di nuovo: chiede al lavoro dei tipi di partire e le regole decidono quando (la fascia notturna). Prima di tutto la
 * cartella del dispositivo (D7): se sul NAS c'e' gia' una cartella con quel nome si chiede "Continua li'" o "Nuova cartella".
 */
class AppBackupActions(
  private val backup: BackupGraph,
  private val proposals: DeviceFolderProposals,
  private val scope: CoroutineScope,
  private val context: Context,
  /** Il verdetto delle regole per un lavoro dell'utente (stima in byte, conferma gia' data). Di norma quello vero; i test lo scelgono. */
  private val decide: suspend (Long?, Boolean) -> RuleDecision = { estimate, confirmed -> backup.userDecision(estimate, confirmed) },
) : BackupActions {

  @Volatile
  var host: BackupActionsHost? = null

  private val promptFlow = MutableStateFlow<BackupPrompt?>(null)

  /** La conferma aperta adesso (dati mobili, roaming, tetto), o null. */
  val prompt: StateFlow<BackupPrompt?> = promptFlow.asStateFlow()

  /** Il primo backup aspetta che l'utente scelga la cartella ("Continua li'" / "Nuova cartella"): null = niente in attesa. */
  @Volatile
  private var pendingFirst: Boolean? = null

  /**
   * BK-3: l'utente e' uscito dalla pagina per sistemare un problema che ha fermato un giro (la password, lo spazio sul NAS, i permessi). Al
   * ritorno ([onPageResumed]) il problema ricordato si toglie e il giro si rifa': se il guasto c'e' ancora, il giro lo ritrova e la pagina lo
   * ridice; se non c'e' piu', la pagina non resta ferma su un problema risolto fino alla notte.
   */
  @Volatile
  private var fixing: Boolean = false

  init {
    scope.launch {
      backup.settings.deviceFolder.filterNotNull().collect {
        val tonight = pendingFirst ?: return@collect
        pendingFirst = null
        proceedFirst(tonight)
      }
    }
  }

  override fun perform(action: BackupAction) {
    when (action) {
      BackupAction.RETRY -> scope.launch {
        runCatching { backup.retryProblems() }
        runCatching { BackupWorkScheduling.afterPhoneRead(context, backup) }
      }
      BackupAction.DIAGNOSTICS -> host?.openDiagnostics()
      BackupAction.NAS_SPACE -> goFix { it.openNas(NasScreen.SPACE) }
      // Solo un testo ("Chiedi all'admin del NAS"): nessun tasto.
      BackupAction.ASK_ADMIN -> Unit
      BackupAction.ENTER_PASSWORD -> goFix { it.openPassword() }
      // BK-9: la pagina "Impostazioni, Aree" non c'e' (le aree si fissano alla fondazione della famiglia e l'app non crea share): nessun
      // problema propone piu' questa azione, e un vecchio tocco non porta a una pagina dove le Aree non ci sono.
      BackupAction.AREAS_SETTINGS -> Unit
      BackupAction.GRANT_MEDIA, BackupAction.GRANT_ALL_MEDIA -> goFix { it.requestMediaAccess() }
      BackupAction.GRANT_MEDIA_LOCATION -> goFix { it.requestMediaLocation() }
      BackupAction.DO_NOW_CONFIRM -> backupNow()
      BackupAction.OPEN_GALLERY_TRASH -> host?.openGalleryTrash()
      BackupAction.OPEN_NAS_TRASH -> host?.openNas(NasScreen.TRASH)
      // Correzioni 5B (LS-9): "Memoria del telefono quasi piena" → "Libera spazio" apre la pagina della 5B (tutto il sicuro); le impostazioni di
      // Android solo dove la pagina non puo' esserci (senza il dialogo di sistema, Android 10).
      BackupAction.FREE_SPACE -> if (backup.fiveB.systemDialogs.available) host?.openScreen(BackupScreen.FREE_SPACE) else host?.openStorageSettings()
      BackupAction.BATTERY_GUIDE -> host?.openBatteryGuide()
    }
  }

  /** Va a sistemare un problema fuori dalla pagina (BK-3): si ricorda di rifare il giro al ritorno. Senza schermata non fa niente. */
  private fun goFix(go: (BackupActionsHost) -> Unit) {
    val target = host ?: return
    fixing = true
    go(target)
  }

  /**
   * La pagina Backup e' di nuovo davanti all'utente (BK-3, BK-13): lo stato si ricalcola sempre (il cambio di una cosa che nessuno annuncia,
   * come "Riattiva le scritture" in Diagnostica, si vede appena si torna) e, se l'utente era uscito per sistemare un problema, il problema
   * ricordato si toglie e il giro si rifa'.
   */
  override fun onPageResumed() {
    backup.kick()
    if (!fixing) return
    fixing = false
    scope.launch {
      runCatching { backup.forgetRememberedProblem() }
      runCatching { BackupWorkScheduling.afterPhoneRead(context, backup) }
    }
  }

  /** "Fai ora" / "Riprendi" (proposta 10). */
  override fun backupNow() {
    startUser(BackupTrigger.USER_NOW, confirmed = false)
  }

  override fun stop() {
    backup.requestStop(StopBy.USER)
  }

  override fun open(screen: BackupScreen) {
    host?.openScreen(screen)
  }

  override fun startFirstBackup(tonight: Boolean) {
    scope.launch {
      try {
        if (backup.settings.deviceFolder.value == null && !chooseFolder(tonight)) return@launch
        proceedFirst(tonight)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.w(TAG) { "primo backup non avviato: ${e.javaClass.simpleName}" }
      }
    }
  }

  /**
   * La cartella del dispositivo (D7): si sceglie da sola solo se sul NAS non c'e' una cartella con quel nome; altrimenti si apre la
   * scelta e il primo backup riprende quando l'utente ha scelto. Falso = non si prosegue adesso.
   */
  private suspend fun chooseFolder(tonight: Boolean): Boolean {
    val proposal = proposals.propose()
    if (proposal == null || proposal.existing != null) {
      pendingFirst = tonight
      host?.openScreen(BackupScreen.DEVICE_FOLDER)
      return false
    }
    backup.settings.chooseFolder(proposal.suggested)
    return true
  }

  private suspend fun proceedFirst(tonight: Boolean) {
    if (tonight) {
      // R4: chi sceglie "Stanotte" non e' piu' in pausa.
      backup.resumeFromPause()
      // "Stanotte" (la predefinita): il lavoro dei tipi segue il preset; qui si chiede solo che parta il giro delle regole.
      BackupWorkScheduling.afterPhoneRead(context, backup)
      backup.kick()
    } else {
      // "Inizia ora": la pausa finisce solo se il lavoro parte davvero (BK-4), non se l'utente annulla la conferma dei dati mobili.
      startUser(BackupTrigger.FIRST_BACKUP, confirmed = false)
    }
  }

  /** La conferma data dall'utente (dati mobili, roaming, tetto): il lavoro parte. */
  fun confirm() {
    val asked = promptFlow.value ?: return
    promptFlow.value = null
    startUser(asked.trigger, confirmed = true)
  }

  fun dismissPrompt() {
    promptFlow.value = null
  }

  private fun startUser(trigger: BackupTrigger, confirmed: Boolean) {
    scope.launch {
      try {
        // BK-8: la stima non deve far aspettare il tocco (in un processo nuovo il riepilogo costa secondi): al massimo pochi secondi, poi senza.
        val estimate = backup.uploadEstimateBytes()
        when (val decision = decide(estimate, confirmed)) {
          // Dati mobili, roaming o tetto superato: l'utente ha l'ultima parola, con la stima (proposta 10). La pausa di "Ferma" resta: se
          // annulla, il backup e' ancora in pausa come la pagina dice (BK-4).
          is RuleDecision.NeedsConfirmation -> promptFlow.value = BackupPrompt(decision.message, trigger)
          else -> {
            // R4: "Riprendi" e "Fai ora" finiscono la pausa di "Ferma", ma solo quando il lavoro parte davvero (BK-4): prima la pausa si
            // toglieva al tocco, anche se poi l'utente toccava "Annulla" sul foglio dei dati mobili, e il backup ripartiva al primo Wi-Fi.
            backup.resumeFromPause()
            backup.jobs.userInitiated(trigger, confirmed)
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.w(TAG) { "lavoro dell'utente non avviato: ${e.javaClass.simpleName}" }
      }
    }
  }

  private companion object {
    const val TAG = "BackupActions"
  }
}

/**
 * Tutte le porte del backup in un oggetto solo, per la galleria e per Hilt (`BackupModule`): ognuna e' un adattatore di poche righe sul
 * grafo del backup. Nasce con il grafo, senza I/O.
 */
class BackupUi(backup: BackupGraph, database: GalleryDatabase, scope: CoroutineScope, context: Context, account: () -> String?) {
  val overview: AppBackupOverview = AppBackupOverview(backup)
  val phoneFolders: PhoneFolders = CatalogPhoneFolders(database, backup.fence, account)
  val rules: BackupRules = AppBackupRules(backup.ruleSettings, scope)
  val proposals: DeviceFolderProposals = AppDeviceFolderProposals(backup)
  val actions: AppBackupActions = AppBackupActions(backup, proposals, scope, context.applicationContext)
  val mediaStates: MediaBackupStates = AppMediaBackupStates(backup, BackupMediaProbe(database))

  /** I3: le ultime foto di ogni sorgente, per le copertine (solo lettura del catalogo). */
  val newestMedia: CatalogSourceNewest = CatalogSourceNewest(database)
}
