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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Cio' che questo dispositivo ricorda della lettura del testo fra un processo e l'altro (D41): l'ultima scelta dell'account vista
 * (le impostazioni personali arrivano solo con il NAS, dopo l'avvio) e l'esclusione di questo dispositivo ("Non su questo dispositivo",
 * Q13), che e' **locale** e non si sincronizza. Due righe in un file di `noBackupFilesDir`; nel dubbio (file assente o rovinato) la
 * lettura e' spenta e il dispositivo non e' escluso.
 */
class TextReadingMemory(
  private val file: File,
  /** La versione del testo della pagina di quest'app (LT-3): una accettata piu' vecchia non accende la lettura qui. */
  private val current: Int = TextReadingSwitch.CURRENT_TEXT_VERSION,
) {
  private val lock = Any()

  @Volatile var choice: TextReadingChoice = TextReadingChoice.Off
    private set

  @Volatile var excluded: Boolean = false
    private set

  init {
    read()
  }

  fun state(): TextReadingState = stateOf(choice, excluded, current)

  /** Lo stato di [choice] e [excluded] con la versione del testo di quest'app (LT-3). */
  fun stateFor(choice: TextReadingChoice, excluded: Boolean): TextReadingState = stateOf(choice, excluded, current)

  fun save(choice: TextReadingChoice, excluded: Boolean) = synchronized(lock) {
    if (choice == this.choice && excluded == this.excluded && file.exists()) return
    this.choice = choice
    this.excluded = excluded
    val version = (choice as? TextReadingChoice.OnForAccount)?.acceptedTextVersion ?: 0
    try {
      file.parentFile?.mkdirs()
      val tmp = File(file.parentFile, file.name + ".tmp")
      tmp.writeText("$VERSION\n$version\n${if (excluded) 1 else 0}\n")
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
    if (lines.size < 3 || lines[0] != VERSION) return
    val version = lines[1].toIntOrNull() ?: return
    choice = if (version > 0) TextReadingChoice.OnForAccount(version) else TextReadingChoice.Off
    excluded = lines[2] == "1"
  }

  companion object {
    const val FILE = "text-reading"
    private const val VERSION = "1"

    /**
     * Lo stato da scelta ed esclusione. Revisione A (LT-3, I7; D41): una versione del testo accettata **piu' vecchia** di quella di
     * quest'app ([current]) non basta: la lettura resta "attiva per l'account" ma **non qui** (motore, lettura al volo e ML Kit fermi)
     * finche' il testo nuovo non si riaccetta sulla pagina, che lo dice ("Il testo e' cambiato").
     */
    fun stateOf(choice: TextReadingChoice, excluded: Boolean, current: Int = TextReadingSwitch.CURRENT_TEXT_VERSION): TextReadingState =
      when (choice) {
        TextReadingChoice.Off -> TextReadingState.Off
        is TextReadingChoice.OnForAccount -> TextReadingState.OnForAccount(thisDevice = !excluded && choice.acceptedTextVersion >= current)
      }
  }
}

/**
 * L'INTERRUTTORE DELLA LETTURA DEL TESTO (D41, Q4, Q13) sopra l'impostazione personale sincronizzata `text_reading` ([choice], null
 * finche' le impostazioni personali non ci sono: allora vale l'ultima vista, [memory]) e l'esclusione locale di questo dispositivo.
 *
 * - **Spegnimento** ([disable], [excludeThisDevice], o un altro dispositivo dell'account che la spegne): `TextReadingShutdown.onTurnedOff`
 *   rilascia il motore e annulla i lavori di DataTransport; la coda si toglie al prossimo avvio ([TextReadingStartup]).
 * - Lo stato `Disabling` dura il tempo dello spegnimento.
 */
class AppTextReadingSwitch(
  scope: CoroutineScope,
  choice: StateFlow<TextReadingChoice?>,
  private val setChoice: suspend (TextReadingChoice) -> Boolean,
  private val memory: TextReadingMemory,
  private val shutdown: () -> TextReadingShutdown,
) : TextReadingSwitch {

  private val excluded = MutableStateFlow(memory.excluded)
  private val disabling = MutableStateFlow(false)

  override val state: StateFlow<TextReadingState> = combine(choice, excluded, disabling) { c, ex, off ->
    val known = c ?: memory.choice
    memory.save(known, ex)
    if (off) TextReadingState.Disabling else memory.stateFor(known, ex)
  }.stateIn(scope, SharingStarted.Eagerly, memory.state())

  init {
    // Lo spegnimento arriva anche da fuori (un altro dispositivo dell'account, la voce sincronizzata): ogni volta che "attiva qui" passa
    // a falso il motore si rilascia e i lavori si annullano.
    scope.launch {
      var wasActive = state.value.activeHere
      state.collect { s ->
        if (wasActive && !s.activeHere && s !is TextReadingState.Disabling) runCatching { shutdown().onTurnedOff() }
        wasActive = s.activeHere
      }
    }
  }

  override suspend fun enableForAccount(consent: TextReadingConsent) {
    excluded.value = false
    setChoice(TextReadingChoice.OnForAccount(consent.textVersion))
  }

  override suspend fun excludeThisDevice() {
    excluded.value = true
  }

  override suspend fun includeThisDevice() {
    excluded.value = false
  }

  override suspend fun disable() {
    disabling.value = true
    try {
      runCatching { shutdown().onTurnedOff() }
      setChoice(TextReadingChoice.Off)
    } finally {
      disabling.value = false
    }
  }
}

/**
 * L'avvio del processo (D41, "prima di ogni altra cosa"): con la lettura non attiva qui (l'ultima scelta vista e l'esclusione locale,
 * [TextReadingMemory]) si annullano i lavori pianificati di DataTransport e se ne tolgono coda e identificativo. Lo chiama
 * `MyCloudGalleryApp.onCreate` **prima** dell'iniezione di Hilt: nessuna classe di ML Kit si carica (il lettore dietro il cancello non
 * crea il motore da spento). [cleanedAtStartup] dice alla Diagnostica se ha tolto qualcosa (`TEXT_READING_OFF_CLEANUP`).
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
