package dev.pampa.mycloudgallery.feature.viewer.cutout

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.viewer.ViewerMessage
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Cio' che il ritaglio chiede al viewer: i servizi di adesso, dove stanno i byte, di che tipo e', se si guarda dai Nascosti, l'avviso breve. */
internal class CutoutHost(
  val services: () -> ViewerServices,
  val refOf: (MediaId) -> MediaRef?,
  val kindOf: (MediaId) -> MediaKind?,
  val currentId: () -> MediaId,
  val announce: (ViewerMessage) -> Unit,
  /** Il viewer e' sui Nascosti: un ritaglio salvato come foto nuova nasce nascosto (D47, E-V11). */
  val hidden: () -> Boolean,
  /** Dove si preparano le figure del disegno (fuori dal thread principale). */
  val work: () -> CoroutineDispatcher,
)

/**
 * Il ritaglio del soggetto del viewer (D44, D45, D46, D47; E-V10, E-V11, E-V12, E-H1): lo stato (`CutoutState` di P0), il soggetto
 * staccato con le sue figure, e le azioni (Copia, Condividi, Salva come nuova foto, Contorno). Non disegna: lo fa `CutoutOverlay`.
 *
 * **Lo stato porta con se' il media a cui appartiene** ([owner]): cambiando media si chiude ([bind]) e il ritaglio di una foto non compare
 * mai su un'altra (E-H1). **Nessun soggetto chiaro** (E-V10): niente, una vibrazione leggera, nessun messaggio d'errore; solo se si
 * e' chiesto esplicitamente (voce del menu, TalkBack) si dice "Nessun soggetto chiaro". **Dai Nascosti** ([CutoutHost.hidden]) la foto
 * salvata nasce nascosta (E-V11).
 *
 * Il ritaglio parte solo con un [ViewerSubjectCutter] collegato; senza, la funzione non c'e' (D13). Mai un nome di file, una posizione o
 * il contenuto di una foto nei log (D40): questa classe non scrive log.
 */
@Stable
internal class CutoutController(
  private val scope: CoroutineScope,
  private val host: CutoutHost,
) {

  var state: CutoutState by mutableStateOf(CutoutState.Idle)
    private set

  /** Il media a cui appartiene [state]; null con [CutoutState.Idle]. */
  var owner: MediaId? by mutableStateOf(null)
    private set

  /** Le figure del soggetto staccato (maschera, bagliore, contorno): null finche' non sono pronte. */
  var art: CutoutArt? by mutableStateOf(null)
    private set

  /** Dove e' caduto il dito (normalizzato sull'immagine), per il velo che pulsa mentre il modello lavora. */
  var origin: Pair<Float, Float>? by mutableStateOf(null)
    private set

  /** Un'azione (Copia, Condividi, Salva) e' in corso: la barra non ne accetta un'altra. */
  var working: Boolean by mutableStateOf(false)
    private set

  /**
   * Dai Nascosti "Salva come nuova foto" chiede prima conferma (I6): nel telefono il ritaglio e' una foto pubblica (la vedono Galleria di Samsung e le altre
   * app che leggono le foto), anche se quella da cui viene e' nascosta. Il foglio lo dice e propone "Salva" o "Annulla".
   */
  var confirmingHiddenSave: Boolean by mutableStateOf(false)
    private set

  /** La vista per le vibrazioni e gli annunci di TalkBack: la imposta la schermata. */
  var view: View? = null

  /** Il gesto di adesso si e' mosso oltre la soglia (un pizzico): un "tocco" che ne esce non deve chiudere il ritaglio (D45, E-V13). */
  var touchMoved: Boolean = false

  private var job: Job? = null

  /** Il ritaglio c'e' in questo viewer: il ritagliatore e' collegato (D13). */
  val available: Boolean get() = host.services().subjectCutter != null

  /** C'e' un soggetto staccato: il pager e il trascinamento verso il basso sono fermi, il doppio tocco e' ignorato (D45, E-V13). */
  val active: Boolean get() = state is CutoutState.Lifted || state is CutoutState.Saving || state is CutoutState.Saved

  /** Il modello sta lavorando (il velo che pulsa). */
  val lifting: Boolean get() = state is CutoutState.Lifting

  /** Indietro e "tocco fuori" hanno qualcosa da chiudere. */
  val canDismiss: Boolean get() = state !is CutoutState.Idle && state !is CutoutState.Saving

  val outline: Boolean get() = (state as? CutoutState.Lifted)?.outline == true

  val subject: ViewerCutout.Subject? get() = (state as? CutoutState.Lifted)?.cutout

  fun touchDown() {
    touchMoved = false
  }

  // --- Cambio di media e chiusura -----------------------------------------------------------------------------------------------

  /** Il viewer e' sul media [id]: il ritaglio del media di prima si chiude. */
  fun bind(id: MediaId) {
    if (owner != null && owner != id) reset()
  }

  /** Chiude cio' che e' aperto (tocco fuori, Indietro, dopo la copia). Un salvataggio in corso non si interrompe. */
  fun dismiss() {
    if (state !is CutoutState.Saving) reset()
  }

  private fun reset() {
    job?.cancel()
    job = null
    state = CutoutState.Idle
    owner = null
    origin = null
    working = false
    confirmingHiddenSave = false
    art?.release()
    art = null
  }

  // --- Pressione prolungata e voce del menu --------------------------------------------------------------------------------------

  /**
   * La pressione prolungata fuori dal testo (l'arbitro, D45): ([x], [y]) e' il punto normalizzato sull'immagine raddrizzata, dopo la
   * trasformazione dello zoom. Una vibrazione all'inizio, un velo che pulsa mentre il modello lavora.
   */
  fun onPress(id: MediaId, x: Float, y: Float) {
    val cutter = host.services().subjectCutter ?: return
    val ref = host.refOf(id) ?: return
    if (host.kindOf(id) == MediaKind.VIDEO) return
    start(id, x, y, silent = true) { cutter.cut(id, ref, x, y) }
  }

  /**
   * La voce del menu e l'azione di TalkBack "Ritaglia il soggetto" (D46): il soggetto piu' grande fra cinque punti di prova, il centro e
   * i quattro punti a un terzo. Un punto che cade su un soggetto gia' trovato non si prova (e' lo stesso). Qui un "niente" si dice.
   */
  fun cutLargest(id: MediaId) {
    val cutter = host.services().subjectCutter ?: return
    val ref = host.refOf(id) ?: return
    if (host.kindOf(id) == MediaKind.VIDEO) return
    start(id, PROBES.first().first, PROBES.first().second, silent = false) {
      var best: ViewerCutout.Subject? = null
      var failure: ViewerCutout.Failed? = null
      for ((x, y) in PROBES) {
        if (best != null && best.contains(x, y)) continue
        when (val result = cutter.cut(id, ref, x, y)) {
          is ViewerCutout.Subject -> if (best == null || result.area > best.area) best = result
          is ViewerCutout.Failed -> failure = result
          ViewerCutout.NoSubject -> Unit
        }
      }
      best ?: failure ?: ViewerCutout.NoSubject
    }
  }

  private fun start(id: MediaId, x: Float, y: Float, silent: Boolean, cut: suspend () -> ViewerCutout) {
    job?.cancel()
    art?.release()
    art = null
    owner = id
    origin = x to y
    state = CutoutState.Lifting(progress = null)
    working = false
    haptic(HapticFeedbackConstants.LONG_PRESS)
    job = scope.launch {
      val result = try {
        cut()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        ViewerCutout.Failed(CODE_FAILED)
      } catch (_: OutOfMemoryError) {
        // R20: una foto grande con poca memoria non fa cadere l'app: il ritaglio non riesce e lo dice.
        ViewerCutout.Failed(CODE_FAILED, CutoutFailure.LOW_MEMORY)
      }
      // Cambiato media o chiuso nel frattempo: il risultato di una foto non si mostra mai su un'altra (E-H1).
      if (owner != id || id != host.currentId()) return@launch
      when (result) {
        is ViewerCutout.Subject -> lift(id, result)
        ViewerCutout.NoSubject -> {
          // E-V10: niente. Con il dito (la pressione) solo una vibrazione leggera; chiesto dal menu o da TalkBack si dice.
          reset()
          haptic(HapticFeedbackConstants.CLOCK_TICK)
          if (!silent) host.announce(ViewerMessage.CutoutNone)
        }
        is ViewerCutout.Failed -> {
          reset()
          host.announce(
            when (result.reason) {
              CutoutFailure.LOW_MEMORY -> ViewerMessage.CutoutLowMemory
              CutoutFailure.NO_SPACE -> ViewerMessage.CutoutNoSpace
              CutoutFailure.OTHER -> ViewerMessage.CutoutFailed
            },
          )
        }
      }
    }
  }

  private suspend fun lift(id: MediaId, subject: ViewerCutout.Subject) {
    var lowMemory = false
    val built = try {
      withContext(host.work()) { CutoutArt.build(subject) }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      null
    } catch (_: OutOfMemoryError) {
      lowMemory = true
      null
    }
    if (owner != id || id != host.currentId()) {
      built?.release()
      return
    }
    if (built == null) {
      reset()
      host.announce(if (lowMemory) ViewerMessage.CutoutLowMemory else ViewerMessage.CutoutFailed)
      return
    }
    art = built
    state = CutoutState.Lifted(subject, outline = false)
  }

  // --- Barra: Contorno, Copia, Condividi, Salva come nuova foto -------------------------------------------------------------------

  /** L'interruttore "Contorno": l'adesivo con il bordo bianco (D44). Cio' che si copia, si condivide e si salva e' quello che si vede. */
  fun toggleOutline() {
    val current = state as? CutoutState.Lifted ?: return
    if (working) return
    state = CutoutState.Lifted(current.cutout, !current.outline)
  }

  /** "Copia": il PNG negli appunti (sensibile, D47); poi il ritaglio si chiude. */
  fun copy() {
    val lifted = state as? CutoutState.Lifted ?: return
    val saver = host.services().cutoutSaver ?: return
    val id = owner ?: return
    val ref = host.refOf(id) ?: return
    if (working) return
    working = true
    scope.launch {
      var lowMemory = false
      val ok = try {
        saver.copy(id, ref, lifted.cutout, lifted.outline)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        false
      } catch (_: OutOfMemoryError) {
        lowMemory = true
        false
      }
      working = false
      if (owner != id) return@launch
      host.announce(if (ok) ViewerMessage.CutoutCopied else if (lowMemory) ViewerMessage.CutoutLowMemory else ViewerMessage.ActionFailed)
      if (ok) reset()
    }
  }

  /** "Condividi": lo stesso PNG con `ACTION_SEND` (D47). Il soggetto resta staccato: si torna dall'altra app e si puo' salvare. */
  fun share() {
    val lifted = state as? CutoutState.Lifted ?: return
    val saver = host.services().cutoutSaver ?: return
    val id = owner ?: return
    val ref = host.refOf(id) ?: return
    if (working) return
    working = true
    scope.launch {
      var lowMemory = false
      val ok = try {
        saver.share(id, ref, lifted.cutout, lifted.outline)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        false
      } catch (_: OutOfMemoryError) {
        lowMemory = true
        false
      }
      working = false
      if (owner == id && !ok) host.announce(if (lowMemory) ViewerMessage.CutoutLowMemory else ViewerMessage.ActionFailed)
    }
  }

  /**
   * "Salva come nuova foto": un PNG in `Pictures/MyCloud Gallery/Ritagli/`; dai Nascosti la foto nuova nasce nascosta (D47, E-V11), ma prima si chiede
   * conferma (I6): nel telefono e' una foto pubblica, con un nome generico.
   */
  fun save() {
    if (state !is CutoutState.Lifted || working) return
    if (host.hidden()) {
      confirmingHiddenSave = true
      return
    }
    startSaving()
  }

  /** "Salva" nel foglio dei Nascosti (I6). */
  fun confirmHiddenSave() {
    if (!confirmingHiddenSave) return
    confirmingHiddenSave = false
    startSaving()
  }

  /** "Annulla" nel foglio dei Nascosti: il soggetto resta staccato, non si salva niente. */
  fun cancelHiddenSave() {
    confirmingHiddenSave = false
  }

  private fun startSaving() {
    val lifted = state as? CutoutState.Lifted ?: return
    val saver = host.services().cutoutSaver ?: return
    val id = owner ?: return
    val ref = host.refOf(id) ?: return
    if (working) return
    working = true
    state = CutoutState.Saving
    scope.launch {
      var lowMemory = false
      val result = try {
        saver.saveAsNewPhoto(id, ref, lifted.cutout, lifted.outline, fromHidden = host.hidden())
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        CutoutSaveResult.Failed
      } catch (_: OutOfMemoryError) {
        lowMemory = true
        CutoutSaveResult.Failed
      }
      working = false
      if (owner != id) return@launch
      when (result) {
        is CutoutSaveResult.Saved -> {
          state = CutoutState.Saved
          host.announce(ViewerMessage.CutoutSaved)
          delay(SAVED_MILLIS)
          if (owner == id && state is CutoutState.Saved) reset()
        }
        CutoutSaveResult.Failed -> {
          // Non salvato (spazio o permesso, CUTOUT_SAVE_FAILED): il soggetto resta staccato, si puo' riprovare.
          state = lifted
          host.announce(if (lowMemory) ViewerMessage.CutoutLowMemory else ViewerMessage.CutoutSaveFailed)
        }
      }
    }
  }

  private fun haptic(kind: Int) {
    view?.performHapticFeedback(kind)
  }

  companion object {
    /** Il codice stabile della Diagnostica (§2.6). */
    const val CODE_FAILED: String = "CUTOUT_FAILED"
    const val CODE_SAVE_FAILED: String = "CUTOUT_SAVE_FAILED"

    /** Quanto resta il "Salvato" prima che il ritaglio si chiuda. */
    const val SAVED_MILLIS: Long = 900L

    /** I cinque punti di prova di TalkBack e della voce del menu (D46): il centro e quattro punti a un terzo. */
    val PROBES: List<Pair<Float, Float>> = listOf(
      0.5f to 0.5f,
      1f / 3f to 1f / 3f,
      2f / 3f to 1f / 3f,
      1f / 3f to 2f / 3f,
      2f / 3f to 2f / 3f,
    )
  }
}
