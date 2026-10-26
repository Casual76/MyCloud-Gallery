package dev.pampa.mycloudgallery.core.ai.ocr

import android.content.Context
import com.google.mlkit.common.MlKit
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingState

/**
 * **Il cancello di ML Kit** (docs/fase6-progetto.md D41, risposta Q4): l'UNICO punto dell'app che avvia ML Kit.
 *
 * `MlKitInitProvider`, con cui la libreria si inizializzerebbe da sola all'avvio del processo, e' tolto dal manifest fuso (manifest
 * di core-ai, `tools:node="remove"`: la via documentata da Google per l'avvio manuale). Con la lettura del testo spenta ML Kit non si
 * inizializza, non carica classi e non manda niente; DataTransport (le statistiche tecniche) resta inerte. L'inizializzazione la
 * chiede soltanto [ensureInitialized], e solo se la lettura e' attiva **su questo dispositivo** ([TextReadingState.activeHere]: attiva
 * per l'account e questo dispositivo non si e' escluso, E-A19).
 *
 * Phase6ArchitectureTest fa fallire la build se un altro sorgente di produzione chiama l'inizializzazione, o se `com.google.mlkit`
 * compare fuori da `core-ai/.../ocr/`; `tools/check.ps1` (passo 7e) lo ricontrolla nel bytecode dell'APK di release.
 *
 * @param state lo stato della lettura del testo visto da QUESTO dispositivo (`TextReadingSwitch.state`), letto a ogni richiesta: lo
 *   spegnimento vale subito.
 */
class MlKitBootstrap(
  context: Context,
  private val state: () -> TextReadingState,
) {
  private val appContext: Context = context.applicationContext

  /**
   * Vero se ML Kit e' partito **in questo processo** (da qualunque istanza di questo cancello: la libreria si avvia una volta per
   * processo e resta avviata fino alla fine, non si "spegne"). Lo legge la pulizia dello spegnimento per sapere se DataTransport
   * tiene aperta la coda.
   */
  val isInitialized: Boolean get() = MlKitProcess.started

  /**
   * Avvia ML Kit se serve. Con la lettura spenta (o questo dispositivo escluso) lancia [AiEngineException] con
   * [AiEngineError.TEXT_READING_OFF] **senza toccare la libreria**. Piu' chiamate, anche da istanze diverse, avviano la libreria una
   * volta sola (la libreria lancia `IllegalStateException` se la si avvia due volte: scoperto dalla prova strumentata).
   */
  fun ensureInitialized() {
    if (!state().activeHere) throw AiEngineException(AiEngineError.TEXT_READING_OFF)
    synchronized(MlKitProcess.LOCK) {
      // Dopo uno spegnimento il JobService di DataTransport e' spento (R7): si riaccende prima di qualunque lettura, anche se la libreria era
      // gia' avviata in questo processo (spenta e riaccesa senza riavvio).
      if (!MlKitProcess.dataTransportEnabled) DataTransportCleanup(appContext).enableJobService()
      if (MlKitProcess.started) return
      try {
        MlKit.initialize(appContext)
      } catch (_: IllegalStateException) {
        // "gia' avviato" da un'altra strada nello stesso processo: la libreria e' avviata, che e' quel che serviva.
      }
      MlKitProcess.started = true
    }
  }
}

/**
 * Se ML Kit e' partito in questo processo. Sta in una classe sua, **senza nessun riferimento a ML Kit**: lo legge anche la pulizia
 * dello spegnimento a ogni avvio dell'app (con la lettura spenta), e se la leggesse da [MlKitBootstrap] la verifica del bytecode di
 * quest'ultimo trascinerebbe in memoria le classi della libreria (lo mostra `TextReadingOffDeviceTest`).
 */
internal object MlKitProcess {
  val LOCK = Any()

  @Volatile
  var started = false

  /**
   * Il JobService di DataTransport e' acceso in questo processo? Falso di partenza (non lo sappiamo: il sistema ricorda lo stato di un
   * processo precedente) e dopo uno spegnimento; il cancello lo riaccende prima di leggere (`DataTransportCleanup.enableJobService`).
   */
  @Volatile
  var dataTransportEnabled = false
}
