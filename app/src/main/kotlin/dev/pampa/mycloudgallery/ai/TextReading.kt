package dev.pampa.mycloudgallery.ai

import android.content.Context
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingConsent
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingState
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingSwitch
import dev.pampa.mycloudgallery.core.ai.ocr.GatedTextReader
import dev.pampa.mycloudgallery.core.ai.ocr.TextReadingShutdown
import dev.pampa.mycloudgallery.core.personal.contract.TextReadingChoice
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Cio' che questo dispositivo ricorda della lettura del testo fra un processo e l'altro (D41), in un file di `noBackupFilesDir`:
 *
 * - [choice]: l'ultima scelta dell'account vista (le impostazioni personali arrivano solo con il NAS, dopo l'avvio) **oppure**, se
 *   [unsynced], la scelta che l'utente ha fatto qui e che non e' ancora nel registro dei dati personali (fuori casa, senza l'ora del NAS, o
 *   prima che il registro ci sia): vale lo stesso, subito, e il file la ricorda per l'avvio dopo (R6);
 * - [excluded]: l'esclusione di questo dispositivo ("Non su questo dispositivo", Q13), **locale** e valida anche da spenta (R8);
 * - [noticeVersion]: la versione del testo di cui questo dispositivo ha visto l'avviso (LT-2): con l'account acceso da un altro dispositivo
 *   la lettura non parte qui finche' non e' stato visto.
 *
 * Nel dubbio (file assente o rovinato) la lettura e' spenta, il dispositivo non e' escluso e nessun avviso e' stato visto.
 */
class TextReadingMemory(private val file: File) {
  private val lock = Any()

  @Volatile var choice: TextReadingChoice = TextReadingChoice.Off
    private set

  @Volatile var excluded: Boolean = false
    private set

  @Volatile var noticeVersion: Int = 0
    private set

  /** La scelta di [choice] e' dell'utente, fatta qui, e il registro non l'ha ancora (vedi [AppTextReadingSwitch.syncPending]). */
  @Volatile var unsynced: Boolean = false
    private set

  init {
    read()
  }

  fun state(): TextReadingState = stateOf(choice, excluded, noticeVersion)

  /** L'ultima scelta dell'account vista nel registro. Ignorata finche' una scelta fatta qui non e' arrivata al registro. */
  fun saveSeen(seen: TextReadingChoice) = update { if (!unsynced) choice = seen }

  /** Una scelta dell'utente, fatta qui: vale subito e resta finche' il registro non la raggiunge. */
  fun chooseLocally(chosen: TextReadingChoice) = update {
    choice = chosen
    unsynced = true
  }

  /** La scelta fatta qui e' nel registro: da adesso conta quello che dice il registro. */
  fun markSynced() = update { unsynced = false }

  fun setExcluded(value: Boolean) = update { excluded = value }

  /** L'avviso del testo [version] e' stato visto qui (la pagina di attivazione, o l'attivazione fatta da questo dispositivo). */
  fun acceptNotice(version: Int) = update { noticeVersion = maxOf(noticeVersion, version) }

  /** Dimentica tutto e toglie il file (azzeramento dei dati dell'account, AZ-2): il prossimo account parte da spento. */
  fun reset() {
    synchronized(lock) {
      choice = TextReadingChoice.Off
      excluded = false
      noticeVersion = 0
      unsynced = false
      file.delete()
      File(file.parentFile, file.name + ".tmp").delete()
    }
  }

  private fun update(block: () -> Unit) = synchronized(lock) {
    val before = listOf(choice, excluded, noticeVersion, unsynced)
    block()
    // Si scrive solo se qualcosa e' cambiato: una chiamata che non cambia niente non fa rinascere un file che l'azzeramento ha tolto (I13).
    if (listOf(choice, excluded, noticeVersion, unsynced) != before) write()
  }

  /** Tutto com'e' se il file non ci fosse: non c'e' niente da ricordare, e un file di soli default non si scrive (non rinasce dopo l'azzeramento). */
  private fun isDefault(): Boolean = choice == TextReadingChoice.Off && !excluded && noticeVersion == 0 && !unsynced

  private fun write() {
    if (isDefault()) {
      file.delete()
      return
    }
    val version = (choice as? TextReadingChoice.OnForAccount)?.acceptedTextVersion ?: 0
    try {
      file.parentFile?.mkdirs()
      val tmp = File(file.parentFile, file.name + ".tmp")
      tmp.writeText("$VERSION\n$version\n${if (excluded) 1 else 0}\n$noticeVersion\n${if (unsynced) 1 else 0}\n")
      if (!tmp.renameTo(file)) {
        file.delete()
        tmp.renameTo(file)
      }
    } catch (e: IOException) {
      // Resta in memoria: al prossimo avvio vale "spenta" (nel dubbio ML Kit non parte).
    }
  }

  private fun read() {
    val lines = try {
      if (file.isFile) file.readLines() else return
    } catch (e: IOException) {
      return
    }
    // Versione 1 (prima di LT-2): tre righe, nessun avviso visto. Versione 2: scelta, esclusione, avviso, scelta non ancora nel registro.
    val format = lines.firstOrNull()
    if (format != "1" && format != "2") return
    if (lines.size < 3) return
    val version = lines[1].toIntOrNull() ?: return
    choice = if (version > 0) TextReadingChoice.OnForAccount(version) else TextReadingChoice.Off
    excluded = lines[2] == "1"
    if (format == "2" && lines.size >= 5) {
      noticeVersion = lines[3].toIntOrNull()?.coerceAtLeast(0) ?: 0
      unsynced = lines[4] == "1"
    }
  }

  companion object {
    const val FILE = "text-reading"
    private const val VERSION = "2"

    /**
     * Lo stato visto da QUESTO dispositivo. L'esclusione vince su tutto (anche sull'avviso da vedere); con l'account acceso e l'avviso di
     * quella versione non ancora visto qui, la lettura **non e' attiva** ([TextReadingState.AwaitingNotice]).
     */
    fun stateOf(choice: TextReadingChoice, excluded: Boolean, noticeVersion: Int): TextReadingState = when (choice) {
      TextReadingChoice.Off -> TextReadingState.Off
      is TextReadingChoice.OnForAccount -> when {
        excluded -> TextReadingState.OnForAccount(thisDevice = false)
        noticeVersion < choice.acceptedTextVersion -> TextReadingState.AwaitingNotice(choice.acceptedTextVersion)
        else -> TextReadingState.OnForAccount(thisDevice = true)
      }
    }
  }
}

/**
 * L'INTERRUTTORE DELLA LETTURA DEL TESTO (D41, Q4, Q13) sopra l'impostazione personale sincronizzata `text_reading` ([choice], null finche'
 * le impostazioni personali non ci sono: allora vale l'ultima vista, [memory]) e l'esclusione locale di questo dispositivo.
 *
 * - **Le scelte dell'utente valgono subito, qui** (R6, LT-1): "Attiva", "Spegni" e le esclusioni cambiano lo stato di questo dispositivo
 *   prima di tutto il resto, anche senza l'ora del NAS e anche se i dati personali non ci sono ancora. La sincronizzazione con gli altri
 *   dispositivi viene dopo ([deliver]): la scelta resta in [memory] come "non ancora nel registro" ([syncPending]) e viaggia appena il
 *   registro c'e' (con l'ora del NAS, o "da datare" e datata alla prima sincronizzazione: l'ordine delle modifiche non si perde).
 * - **Spegnimento** ([disable], [excludeThisDevice], o un altro dispositivo dell'account che la spegne): `TextReadingShutdown.onTurnedOff`
 *   rilascia il motore e annulla i lavori di DataTransport; la coda si toglie al prossimo avvio ([TextReadingStartup]).
 * - **Il cancello di ML Kit legge [currentState]**, non [state]: [state] e' un flusso che si aggiorna su un altro thread, e fra lo
 *   spegnimento e il suo aggiornamento una lettura gia' in volo trovava ancora "attiva" (R7). Qui lo stato cambia in modo sincrono.
 * - **Avviso una tantum** (LT-2, R8): con l'account acceso da un altro dispositivo, o con un testo cambiato, lo stato di questo dispositivo
 *   e' [TextReadingState.AwaitingNotice] (non attivo) finche' l'utente non conferma qui, dalla pagina di attivazione, o si esclude.
 * - Lo stato `Disabling` dura il tempo dello spegnimento.
 * - [stop] ferma tutto prima dell'azzeramento (I13) e [forget] dimentica la scelta e l'esclusione (AZ-2).
 */
class AppTextReadingSwitch(
  scope: CoroutineScope,
  private val choice: StateFlow<TextReadingChoice?>,
  private val setChoice: suspend (TextReadingChoice) -> Boolean,
  private val memory: TextReadingMemory,
  private val shutdown: () -> TextReadingShutdown,
) : TextReadingSwitch {

  private val job = SupervisorJob(scope.coroutineContext[Job])
  private val own = CoroutineScope(scope.coroutineContext + job)

  private val excluded = MutableStateFlow(memory.excluded)
  private val disabling = MutableStateFlow(false)
  private val notice = MutableStateFlow(memory.noticeVersion)

  /** La scelta fatta qui e non ancora nel registro (null = niente in sospeso). */
  private val local = MutableStateFlow<TextReadingChoice?>(if (memory.unsynced) memory.choice else null)
  private val deliveryLock = Mutex()

  @Volatile private var stopped = false

  private fun compute(registry: TextReadingChoice?, pending: TextReadingChoice?, excludedHere: Boolean, seen: Int, off: Boolean): TextReadingState {
    if (stopped) return TextReadingState.Off
    if (off) return TextReadingState.Disabling
    return TextReadingMemory.stateOf(pending ?: registry ?: memory.choice, excludedHere, seen)
  }

  override fun currentState(): TextReadingState = compute(choice.value, local.value, excluded.value, notice.value, disabling.value)

  private val mutableState = MutableStateFlow(currentState())
  override val state: StateFlow<TextReadingState> = mutableState.asStateFlow()

  override val excludedHere: StateFlow<Boolean> = excluded.asStateFlow()

  private val pendingSync = MutableStateFlow(local.value != null)
  override val syncPending: StateFlow<Boolean> = pendingSync.asStateFlow()

  init {
    own.launch {
      combine(choice, local, excluded, notice, disabling) { registry, pending, ex, seen, off -> compute(registry, pending, ex, seen, off) }
        .collect { mutableState.value = it }
    }
    // Cio' che il registro dice si ricorda per l'avvio dopo (finche' non c'e' una scelta fatta qui che il registro non ha ancora).
    own.launch {
      choice.filterNotNull().collect { seen ->
        if (local.value == null) memory.saveSeen(seen) else deliver()
      }
    }
    // Lo spegnimento arriva anche da fuori (un altro dispositivo dell'account, la voce sincronizzata): ogni volta che "attiva qui" passa
    // a falso il motore si rilascia e i lavori si annullano.
    own.launch {
      var wasActive = mutableState.value.activeHere
      mutableState.collect { s ->
        if (wasActive && !s.activeHere && s !is TextReadingState.Disabling) runCatching { shutdown().onTurnedOff() }
        wasActive = s.activeHere
      }
    }
  }

  override suspend fun enableForAccount(consent: TextReadingConsent) {
    // Chi accende qui ha appena letto l'avviso: il consenso vale per questo dispositivo (e include il dispositivo, se si era escluso).
    memory.setExcluded(false)
    excluded.value = false
    memory.acceptNotice(consent.textVersion)
    notice.value = maxOf(notice.value, consent.textVersion)
    // L'account l'ha gia' accesa con questa versione (o una piu' nuova, da un'app piu' nuova): qui si conferma solo l'avviso, la voce
    // sincronizzata non si tocca (non si ritorna a una versione piu' vecchia per tutti i dispositivi).
    val account = local.value ?: choice.value ?: memory.choice
    if (account is TextReadingChoice.OnForAccount && account.acceptedTextVersion >= consent.textVersion) return
    chooseAndDeliver(TextReadingChoice.OnForAccount(consent.textVersion))
  }

  override suspend fun excludeThisDevice() {
    memory.setExcluded(true)
    excluded.value = true
  }

  override suspend fun includeThisDevice() {
    memory.setExcluded(false)
    excluded.value = false
  }

  override suspend fun disable() {
    // Prima di tutto lo stato di questo dispositivo, in modo sincrono: da qui il cancello di ML Kit non lascia passare altre letture.
    disabling.value = true
    try {
      choose(TextReadingChoice.Off)
      runCatching { shutdown().onTurnedOff() }
      deliver()
    } finally {
      disabling.value = false
    }
  }

  private suspend fun chooseAndDeliver(chosen: TextReadingChoice) {
    choose(chosen)
    deliver()
  }

  private fun choose(chosen: TextReadingChoice) {
    memory.chooseLocally(chosen)
    local.value = chosen
    pendingSync.value = true
  }

  /** Porta nel registro dei dati personali la scelta fatta qui, se il registro c'e'. Altrimenti resta in sospeso e riparte quando c'e'. */
  private suspend fun deliver() {
    deliveryLock.withLock {
      val wanted = local.value ?: return
      val delivered = try {
        setChoice(wanted)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        false
      }
      if (delivered && local.value == wanted) {
        memory.markSynced()
        local.value = null
        pendingSync.value = false
      }
    }
  }

  /** Ferma l'interruttore prima dell'azzeramento (I13): niente piu' emissioni che riscrivano `text-reading`. Lo stato resta spento. */
  fun stop() {
    stopped = true
    job.cancel()
    mutableState.value = TextReadingState.Off
  }

  /** L'azzeramento dei dati dell'account (AZ-2): scelta, esclusione e avvisi si dimenticano, qui e nel file; il prossimo account parte da spento. */
  fun forget() {
    memory.reset()
    local.value = null
    excluded.value = false
    notice.value = 0
    disabling.value = false
    pendingSync.value = false
    mutableState.value = if (stopped) TextReadingState.Off else currentState()
  }
}

/**
 * L'avvio del processo (D41, "prima di ogni altra cosa"): con la lettura non attiva qui (l'ultima scelta vista, l'avviso e l'esclusione
 * locale, [TextReadingMemory]) si annullano i lavori pianificati di DataTransport, si spegne il suo servizio e se ne tolgono coda e
 * identificativo. Lo chiama `MyCloudGalleryApp.onCreate` **prima** dell'iniezione di Hilt: nessuna classe di ML Kit si carica (il lettore
 * dietro il cancello non crea il motore da spento). [cleanedAtStartup] dice alla Diagnostica se ha tolto qualcosa
 * (`TEXT_READING_OFF_CLEANUP`).
 */
object TextReadingStartup {
  @Volatile var cleanedAtStartup: Boolean = false
    private set

  fun run(context: Context) {
    val memory = TextReadingMemory(File(context.noBackupFilesDir, TextReadingMemory.FILE))
    val state = memory.state()
    val reader = GatedTextReader.create(context, { state })
    val report = runCatching { TextReadingShutdown.create(context, reader).atStartup(state) }.getOrNull()
    cleanedAtStartup = report?.anythingRemoved == true
  }
}
