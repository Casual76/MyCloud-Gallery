package dev.pampa.mycloudgallery.feature.viewer.livetext

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.viewer.ViewerMessage
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.feature.viewer.findActivity
import dev.pampa.mycloudgallery.feature.viewer.gesture.LongPressArbiter
import dev.pampa.mycloudgallery.feature.viewer.gesture.LongPressOutcome
import dev.pampa.mycloudgallery.feature.viewer.gesture.TextClaim
import dev.pampa.mycloudgallery.feature.viewer.gesture.ViewerSubjectPress
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Cio' che il testo dal vivo chiede al viewer: i servizi di adesso, dove stanno i byte, di che tipo e' il media, l'avviso breve. */
internal class LiveTextHost(
  val services: () -> ViewerServices,
  val refOf: (MediaId) -> MediaRef?,
  val kindOf: (MediaId) -> MediaKind?,
  val currentId: () -> MediaId,
  val announce: (ViewerMessage) -> Unit,
  /**
   * Dove va la pressione prolungata fuori dal testo (D45): il ritaglio del soggetto (Fase 6, P13). Di serie la porta dei servizi
   * (`ViewerServices.subjectPress`); il viewer vero la unisce al suo ritagliatore. Null = il ritaglio non c'e': la pressione non fa niente (D13).
   */
  val subject: () -> ViewerSubjectPress? = { services().subjectPress },
)

/** Quale delle due maniglie si trascina. */
internal enum class HandleSide { Start, End }

/**
 * Il testo dal vivo del viewer (D42, D43, D45, D46): lo stato (`LiveTextState` di P0), il testo letto di ogni media visto, la selezione
 * con le sue maniglie, la lettura al volo e le azioni (copia, condividi, foglio). Non disegna: lo fa `LiveTextOverlay`.
 *
 * **La selezione non sopravvive al cambio di media** ([bind]) e non mostra mai il testo di un altro media (E-H1): lo stato porta con se'
 * l'id a cui appartiene ([owner]) e i layout sono per id.
 *
 * **Lettura spenta** (D41): [ViewerTextReader.readNow] si chiama solo se [ViewerTextReader.available] e solo da una pressione prolungata
 * su un media che l'indice non ha mai letto (`ViewerTextSource.layout` nullo) o dall'azione "Testo nella foto". Altrimenti mai: il fake
 * fallisce se chiamato. Un media gia' letto, anche "nessun testo", non si rilegge.
 *
 * Il testo letto non va mai nei log (D40): questa classe non scrive log.
 */
@Stable
internal class LiveTextController(
  private val scope: CoroutineScope,
  private val context: Context,
  private val host: LiveTextHost,
) {

  var state: LiveTextState by mutableStateOf(LiveTextState.Idle)
    private set

  /** Il media a cui appartiene [state]; null con [LiveTextState.Idle]. */
  var owner: MediaId? by mutableStateOf(null)
    private set

  /** La maniglia in movimento, o null. */
  var dragging: HandleSide? by mutableStateOf(null)
    private set

  /**
   * Il gesto di adesso si e' mosso oltre la soglia (un trascinamento, un pizzico): se Compose lo legge come tocco non deve chiudere cio' che e'
   * aperto (D45, E-V13). Lo imposta `touchMovement` della pagina; [touchDown] lo azzera a ogni primo dito.
   */
  var touchMoved: Boolean = false

  fun touchDown() {
    touchMoved = false
  }

  private val layouts = mutableStateMapOf<MediaId, ViewerTextLayout>()

  /** I media il cui testo e' stato letto al volo qui e che l'indice non ha (ancora) dato: una rilettura non li toglie (VT-2). */
  private val readHere = HashSet<MediaId>()
  private var bound: MediaId? = null
  private var fixed: TextPosition = TextPosition(0, 0)
  private var cachedGeometry: LiveTextGeometry? = null

  // --- Cio' che la schermata legge --------------------------------------------------------------------------------------

  fun layoutOf(id: MediaId): ViewerTextLayout? = layouts[id]

  /** La foto ha testo letto (da qualunque dispositivo, D42): il pulsante "Testo" e la voce ⋮ compaiono. */
  fun hasText(id: MediaId): Boolean = layouts[id]?.isEmpty == false

  /** L'azione "Testo nella foto" c'e': o c'e' il testo, o lo si puo' leggere al volo (lettura attiva qui). */
  fun canOpenSheet(id: MediaId): Boolean = hasText(id) || readerAvailable()

  /** Una selezione e' aperta: il pager e il trascinamento verso il basso sono fermi, il doppio tocco e' ignorato (E-V13). */
  val active: Boolean get() = state is LiveTextState.Selecting || state is LiveTextState.Actions

  val sheetOpen: Boolean get() = state is LiveTextState.Sheet

  /** Indietro e "tocco fuori" hanno qualcosa da chiudere. */
  val canDismiss: Boolean get() = active || sheetOpen || state is LiveTextState.Highlighted

  val selection: TextSelection?
    get() = when (val s = state) {
      is LiveTextState.Selecting -> s.selection
      is LiveTextState.Actions -> s.selection
      else -> null
    }

  /** La pressione prolungata ha qualcosa da fare su questo media (E-V9): testo, lettura al volo o ritaglio. */
  fun wantsLongPress(id: MediaId): Boolean =
    host.kindOf(id) != MediaKind.VIDEO && (host.services().textSource != null || readerAvailable() || host.subject() != null)

  /** La geometria del testo di [id] alla proporzione [aspect], tenuta finche' non cambiano. */
  fun geometry(id: MediaId, aspect: Float): LiveTextGeometry? {
    val layout = layouts[id] ?: return null
    val cached = cachedGeometry
    if (cached != null && cached.layout === layout && cached.aspect == aspect) return cached
    return LiveTextGeometry(layout, aspect).also { cachedGeometry = it }
  }

  private fun readerAvailable(): Boolean = host.services().textReader?.available == true

  // --- Il media corrente --------------------------------------------------------------------------------------------------

  /**
   * Il viewer e' sul media [id]: la selezione del media di prima si chiude e si legge il testo gia' nell'indice (D42). Revisione A (VT-2):
   * si rilegge ogni volta (il testo puo' essere cambiato mentre si guardava un altro media).
   */
  suspend fun bind(id: MediaId) {
    if (bound != id) {
      bound = id
      if (owner != id) reset()
    }
    reload(id)
  }

  /**
   * VT-2: il testo dell'indice e' cambiato ([ViewerTextSource.changes]): il media [id] si rilegge. Un testo arrivato fa comparire il
   * pulsante "Testo"; uno ritirato (o di un dispositivo revocato) esce, e cio' che era aperto su quelle righe si chiude. Il testo letto
   * al volo qui resta finche' l'indice non ne da' un altro (se il salvataggio non e' riuscito non c'e' altro da mostrare).
   */
  suspend fun refresh(id: MediaId) = reload(id)

  private suspend fun reload(id: MediaId) {
    val source = host.services().textSource ?: return
    val fresh = try {
      source.layout(id)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      // Una lettura dell'indice che fallisce non cambia cio' che si vede.
      return
    }
    val old = layouts[id]
    when {
      fresh != null -> {
        readHere -= id
        if (fresh == old) return
        layouts[id] = fresh
      }
      old != null && id !in readHere -> layouts.remove(id)
      else -> return
    }
    // Cio' che era aperto valeva per le righe di prima: si chiude (una lettura al volo in corso continua).
    if (owner == id && state !is LiveTextState.ReadingNow) reset()
  }

  private fun reset() {
    state = LiveTextState.Idle
    owner = null
    dragging = null
  }

  private fun show(id: MediaId, next: LiveTextState) {
    owner = id
    state = next
  }

  /** Chiude cio' che e' aperto (tocco fuori, Indietro, dopo la copia). */
  fun dismiss() {
    if (state !is LiveTextState.ReadingNow) reset()
  }

  // --- Pulsante "Testo", foglio -------------------------------------------------------------------------------------------

  /** Il pulsante "Testo" (D43): il primo tocco evidenzia le righe, il secondo apre il foglio; da una selezione torna all'evidenziazione. */
  fun toggleHighlight(id: MediaId) {
    val layout = layouts[id]?.takeUnless { it.isEmpty } ?: return
    when (state) {
      is LiveTextState.Highlighted -> show(id, LiveTextState.Sheet(layout))
      is LiveTextState.Sheet, LiveTextState.ReadingNow -> Unit
      else -> show(id, LiveTextState.Highlighted(layout))
    }
  }

  /** La voce ⋮ "Testo nella foto" e l'azione di TalkBack (D46): il foglio, leggendo al volo se serve e se la lettura e' attiva. */
  fun openSheet(id: MediaId) {
    scope.launch {
      val layout = resolveLayout(id)?.takeUnless { it.isEmpty }
      if (id != host.currentId()) return@launch
      if (layout == null) host.announce(ViewerMessage.NoText) else show(id, LiveTextState.Sheet(layout))
    }
  }

  fun closeSheet() {
    if (state is LiveTextState.Sheet) reset()
  }

  // --- Pressione prolungata --------------------------------------------------------------------------------------------------

  /**
   * L'arbitro (D45): [point] e' il punto normalizzato sull'immagine raddrizzata, [tolerance] la distanza (in altezze dell'immagine) entro
   * cui una parola si considera toccata. Su una parola: si seleziona; altrove su una foto: il ritaglio, se c'e'; su un video niente.
   */
  fun onLongPress(id: MediaId, aspect: Float, point: Offset, tolerance: Float) {
    scope.launch {
      val claim = TextClaim { p -> claim(id, aspect, p, tolerance) }
      val outcome = LongPressArbiter.decide(host.kindOf(id), point, claim, subject = host.subject() != null)
      if (id != host.currentId()) return@launch
      when (outcome) {
        is LongPressOutcome.Text -> {
          val layout = layouts[id] ?: return@launch
          show(id, LiveTextState.Actions(layout, TextSelections.word(outcome.position)))
        }
        is LongPressOutcome.Subject -> {
          if (state !is LiveTextState.ReadingNow) dismiss()
          host.subject()?.onPress(id, outcome.x, outcome.y)
        }
        LongPressOutcome.None -> if (state is LiveTextState.ReadingNow) reset()
      }
    }
  }

  private suspend fun claim(id: MediaId, aspect: Float, point: Offset, tolerance: Float): TextPosition? {
    val layout = resolveLayout(id) ?: return null
    return LiveTextGeometry(layout, aspect).wordAt(point, tolerance)
  }

  /**
   * Il testo letto di [id]: dalla memoria, dall'indice (D42), o al volo (D42, E-V9) solo con la lettura attiva qui, al piu' [READ_MILLIS].
   * Null = non c'e' niente da mostrare ora. Un media letto senza testo si ricorda come vuoto: non si rilegge.
   */
  private suspend fun resolveLayout(id: MediaId): ViewerTextLayout? {
    layouts[id]?.let { return it }
    val services = host.services()
    services.textSource?.layout(id)?.let { layouts[id] = it; return it }
    val reader = services.textReader ?: return null
    val ref = host.refOf(id)
    if (!reader.available || ref == null) return null
    if (owner == null || owner == id) show(id, LiveTextState.ReadingNow)
    val read = try {
      withTimeoutOrNull(READ_MILLIS) { reader.readNow(id, ref) }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      null
    } finally {
      if (state is LiveTextState.ReadingNow && owner == id) reset()
    }
    if (read != null) {
      layouts[id] = read
      readHere += id
    }
    return read
  }

  // --- Maniglie ---------------------------------------------------------------------------------------------------------------

  /** Una maniglia comincia a muoversi: l'altra estremita' resta ferma, la barra si nasconde. */
  fun beginDrag(side: HandleSide) {
    val current = state as? LiveTextState.Actions ?: state as? LiveTextState.Selecting ?: return
    val id = owner ?: return
    val selection = when (current) {
      is LiveTextState.Actions -> current.selection
      is LiveTextState.Selecting -> current.selection
      else -> return
    }
    val layout = layouts[id] ?: return
    fixed = if (side == HandleSide.Start) selection.to else selection.from
    dragging = side
    show(id, LiveTextState.Selecting(layout, selection))
  }

  /** La maniglia e' ora su [point] (normalizzato sull'immagine): si aggancia alla parola piu' vicina, in tutto il testo, nell'ordine di lettura. */
  fun dragTo(point: Offset, aspect: Float) {
    val id = owner ?: return
    if (dragging == null) return
    val geometry = geometry(id, aspect) ?: return
    val position = geometry.nearestWord(point) ?: return
    val layout = layouts[id] ?: return
    show(id, LiveTextState.Selecting(layout, TextSelections.between(fixed, position)))
  }

  fun endDrag() {
    val current = state as? LiveTextState.Selecting ?: return
    dragging = null
    show(owner ?: return, LiveTextState.Actions(current.layout, current.selection))
  }

  // --- Barra: Copia, Seleziona tutto, Condividi testo --------------------------------------------------------------------

  fun selectAll(aspect: Float) {
    val id = owner ?: return
    val layout = layouts[id] ?: return
    val all = TextSelections.all(LiveTextGeometry(layout, aspect)) ?: return
    show(id, LiveTextState.Actions(layout, all))
  }

  /** "Copia": il testo selezionato negli appunti, segnato sensibile (D43); poi la selezione si chiude. */
  fun copySelection(label: String) {
    val current = selection ?: return
    val layout = layouts[owner ?: return] ?: return
    copyText(label, TextSelections.text(layout, current))
    reset()
  }

  /** "Condividi testo": `ACTION_SEND` di testo semplice, come ogni condivisione dell'app. */
  fun shareSelection() {
    val current = selection ?: return
    val layout = layouts[owner ?: return] ?: return
    shareText(TextSelections.text(layout, current))
    reset()
  }

  /** "Copia tutto" del foglio (D46). */
  fun copyAll(label: String) {
    val id = owner ?: return
    val layout = layouts[id] ?: return
    copyText(label, TextSelections.allText(layout))
    reset()
  }

  private fun copyText(label: String, text: String) {
    host.announce(if (LiveTextSystem.copy(context, label, text)) ViewerMessage.TextCopied else ViewerMessage.ActionFailed)
  }

  private fun shareText(text: String) {
    val chooser = LiveTextSystem.shareIntent(text).apply { if (context.findActivity() == null) addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
    try {
      context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
      host.announce(ViewerMessage.ActionFailed)
    }
  }

  private companion object {
    /** La lettura al volo dura al piu' due secondi (§2.10): oltre, niente testo e la pressione va al ritaglio. */
    const val READ_MILLIS = 2_000L
  }
}
