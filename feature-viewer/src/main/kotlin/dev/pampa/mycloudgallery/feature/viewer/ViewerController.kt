package dev.pampa.mycloudgallery.feature.viewer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryDismissSpec
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryViewerTransitionState
import dev.pampa.mycloudgallery.core.imaging.contract.UnavailableReason
import dev.pampa.mycloudgallery.core.imaging.viewer.NasNetwork
import dev.pampa.mycloudgallery.core.library.contract.PixelRect
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.viewer.download.DownloadState
import dev.pampa.mycloudgallery.feature.viewer.gesture.VerticalIntent
import dev.pampa.mycloudgallery.feature.viewer.info.InfoPanelState
import dev.pampa.mycloudgallery.feature.viewer.cutout.CutoutController
import dev.pampa.mycloudgallery.feature.viewer.cutout.CutoutHost
import dev.pampa.mycloudgallery.feature.viewer.gesture.ViewerSubjectPress
import dev.pampa.mycloudgallery.feature.viewer.livetext.LiveTextController
import dev.pampa.mycloudgallery.feature.viewer.livetext.LiveTextHost
import dev.pampa.mycloudgallery.feature.viewer.motion.MotionDirector
import dev.pampa.mycloudgallery.feature.viewer.motion.MotionHost
import dev.pampa.mycloudgallery.feature.viewer.motion.PowerSaving
import dev.pampa.mycloudgallery.feature.viewer.page.fitRect
import dev.pampa.mycloudgallery.feature.viewer.video.PlaybackProblem
import dev.pampa.mycloudgallery.feature.viewer.video.ViewerPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** I fogli di conferma che il viewer apre: sono azioni dell'utente, mai automatiche. */
internal enum class ViewerSheet { Delete, Maps }

/**
 * La regia del viewer: tutto cio' che si decide a un gesto o a un tocco. Il composable la costruisce una volta e le
 * dice, a ogni ricomposizione, quanto e' grande lo schermo e quali sono la vista e i servizi di adesso; lei guida
 * l'espansione, la chiusura, il pannello, il lettore e le azioni.
 *
 * Non disegna niente e non sa nulla di Compose oltre agli stati osservabili: cosi' la logica delle azioni (che cosa
 * succede se l'utente tocca "Preferito" senza azioni locali, o "Elimina" su Android 10) si legge in un posto solo.
 */
@Stable
internal class ViewerController(
  val request: ViewerRequest,
  val state: ViewerState,
  val transition: GalleryViewerTransitionState,
  val info: InfoPanelState,
  val pager: PagerState,
  val playback: ViewerPlayback,
  private val scope: CoroutineScope,
  private val context: Context,
  private val onClosed: () -> Unit,
) {

  // --- Cio' che il composable aggiorna a ogni ricomposizione ---------------------------------------------------------
  var services: ViewerServices = ViewerServices()
  var view: TimelineView = request.items.view.value
  var stage: Size = Size.Zero
  var windowOffset: Offset = Offset.Zero
  var reducedMotion: Boolean = false
  var density: Float = 1f
  var sidePanel: Boolean = false
  var sheetPx: Float = 1f
  var launchSender: (IntentSender) -> Unit = {}

  /** Il viewer e' stato posizionato nella finestra: da qui [windowOffset] e' quello vero. */
  var positioned: Boolean by mutableStateOf(false)

  /** Da dove parte e dove torna l'espansione, nelle coordinate del viewer. Null finche' non si apre. */
  var origin: Rect? by mutableStateOf(null)

  /** Il viewer si sta chiudendo: i gesti e i tasti non fanno piu' niente. */
  var closing: Boolean by mutableStateOf(false)
    private set

  /** Il foglio di conferma aperto, se c'e'. */
  var sheet: ViewerSheet? by mutableStateOf(null)

  /**
   * Cosa mostrare del messaggio che ha causato la prossima sparizione dalla vista (nascosto, ripristinato). Si imposta
   * **solo a scrittura riuscita**: un "Nascosto" che precede l'esito direbbe una cosa non vera (R8).
   */
  var pendingMessage: ViewerMessage? = null

  /**
   * Quanti "Nascondi"/"Mostra" sono in corso. Finche' sono in corso l'elemento che sparisce dalla vista non e' ancora una
   * notizia: l'avviso lo da' l'esito (riuscito: "Nascosto"; fallito: "Non e' stato possibile nascondere").
   */
  var changingVisibility: Int = 0

  /** Gli id per cui la parte video di una motion photo non e' stata riconosciuta (E-M16). */
  val motionUnplayable: MutableSet<MediaId> = HashSet()
  var motionUnplayableVersion: Int by mutableIntStateOf(0)
    private set

  private val systemPowerSaving: PowerSaving by lazy(LazyThreadSafetyMode.NONE) { PowerSaving.system(context) }

  /**
   * Le foto in movimento (D50, D51): l'animazione d'apertura quando la pagina si ferma e il video intero dal badge. Dice al lettore
   * condiviso cosa riprodurre; non ne crea altri.
   */
  val motion: MotionDirector = MotionDirector(
    playback = playback,
    scope = scope,
    host = MotionHost(
      services = { services },
      refOf = ::refOf,
      kindOf = ::kindOf,
      photoAspect = { id ->
        val index = view.snapshot.indexOf(id)
        if (index >= 0) view.snapshot.aspectMilliAt(index) / MILLI else 0f
      },
      reducedMotion = { reducedMotion },
      powerSaving = { (services.powerSaving ?: systemPowerSaving).isOn() },
      onUnrecognized = { id -> if (motionUnplayable.add(id)) motionUnplayableVersion++ },
      zoomed = { state.zoomed },
      neighborsOf = { id ->
        val page = view.viewIndexOf(id)
        listOf(page - 1, page + 1).filter { it in 0 until view.size && page >= 0 }.map { view.snapshot.idAt(view.snapshotIndex(it)) }
      },
    ),
  )

  /**
   * Il ritaglio del soggetto (Fase 6, P13, D44, D45, D46, D47): il soggetto che si stacca, la barra "Copia · Condividi · Salva come nuova
   * foto", l'interruttore "Contorno". Lo avvia l'arbitro della pressione prolungata ([liveText]), la voce ⋮ e l'azione di TalkBack.
   */
  val cutout: CutoutController = CutoutController(
    scope = scope,
    host = CutoutHost(
      services = { services },
      refOf = ::refOf,
      kindOf = ::kindOf,
      currentId = { state.currentId },
      announce = { state.message = it },
      hidden = { request.secure },
      work = { services.dispatchers.default },
    ),
  )

  /** La pressione prolungata fuori dal testo: il ritaglio, e la porta dei servizi se qualcuno ne ha messa una (le prove dell'arbitro). */
  private val subjectPress = ViewerSubjectPress { id, x, y ->
    services.subjectPress?.onPress(id, x, y)
    if (cutout.available) cutout.onPress(id, x, y)
  }

  /**
   * Il testo dal vivo (Fase 6, P12, D43, D45, D46): selezione con le maniglie, foglio "Testo nella foto", lettura al volo. L'arbitro della
   * pressione prolungata gli e' dentro: cio' che non e' testo lo passa al ritaglio ([cutout], P13).
   */
  val liveText: LiveTextController = LiveTextController(
    scope = scope,
    context = context,
    host = LiveTextHost(
      services = { services },
      refOf = ::refOf,
      kindOf = ::kindOf,
      currentId = { state.currentId },
      announce = { state.message = it },
      subject = { if (services.subjectPress != null || cutout.available) subjectPress else null },
    ),
  )

  // --- Geometria -----------------------------------------------------------------------------------------------------

  /** La cella di [id] nelle coordinate del viewer; se non e' sullo schermo, un rettangolo al centro. */
  fun originFor(id: MediaId): Rect {
    val bounds: PixelRect? = request.anchor.boundsOf(id)
    if (bounds != null && !bounds.isEmpty) {
      return Rect(bounds.left - windowOffset.x, bounds.top - windowOffset.y, bounds.right - windowOffset.x, bounds.bottom - windowOffset.y)
    }
    return centeredFallback(id)
  }

  private fun centeredFallback(id: MediaId): Rect {
    val side = minOf(stage.width, stage.height) * FallbackFraction
    val index = view.snapshot.indexOf(id)
    val aspect = if (index >= 0) view.snapshot.aspectMilliAt(index) / MILLI else 1f
    val width = if (aspect >= 1f) side else side * aspect
    val height = if (aspect >= 1f) side / aspect else side
    return Rect(
      left = (stage.width - width) / 2f,
      top = (stage.height - height) / 2f,
      right = (stage.width + width) / 2f,
      bottom = (stage.height + height) / 2f,
    )
  }

  /** Il rettangolo della foto di [id] intera nello schermo (dove arriva l'espansione). */
  fun photoFit(id: MediaId): Rect {
    val index = view.snapshot.indexOf(id)
    val aspect = if (index >= 0) view.snapshot.aspectMilliAt(index) / MILLI else 0f
    return fitRect(stage, aspect)
  }

  // --- Apertura e chiusura -------------------------------------------------------------------------------------------

  /** Apre: la miniatura diventa il viewer. */
  suspend fun open() {
    // Le coordinate della cella sono della finestra: senza sapere dove sta il viewer il punto di partenza sarebbe sbagliato.
    snapshotFlow { positioned }.first { it }
    origin = originFor(state.currentId)
    // Dopo una ricreazione (rotazione, split screen) il viewer si ripresenta aperto: l'espansione l'ha gia' fatta.
    if (state.restored) transition.progress.snapTo(1f) else transition.open(reducedMotion)
  }

  /** Chiude (tasto, Indietro, swipe giu' gia' deciso): la foto torna alla cella di cio' che si sta guardando. */
  fun close() {
    if (closing) return
    closing = true
    scope.launch { closeNow() }
  }

  private suspend fun closeNow() {
    motion.stopAll()
    sheet = null
    // Il pannello e lo zoom tornano a riposo prima: l'espansione alla rovescia parte dalla foto intera.
    if (state.zoomed) state.zoomReset?.invoke()
    if (info.progress.value > INFO_REST) {
      state.infoOpen = false
      info.settle(false, reducedMotion)
    }
    val id = state.currentId
    // Un elemento sparito dalla vista (E-V6) non ha piu' una cella da portare in vista: si chiude sul rettangolo di ripiego.
    if (view.viewIndexOf(id) >= 0 && request.anchor.boundsOf(id) == null) request.anchor.reveal(id)
    origin = originFor(id)
    playback.clear()
    transition.close(reducedMotion)
    onClosed()
  }

  /** Il rilascio di un trascinamento verso il basso: chiude oppure torna a posto, con la stessa soglia dell'espansione. */
  suspend fun releaseDismiss(velocityY: Float) {
    val dragProgress = GalleryDismissSpec.dragProgress(transition.drag.value.y, stage.height)
    if (GalleryDismissSpec.shouldDismiss(dragProgress, velocityY, density)) {
      closing = true
      closeNow()
    } else {
      transition.release(velocityY, density, stage.height, reducedMotion)
    }
  }

  // --- Gesti verticali -----------------------------------------------------------------------------------------------

  fun intentFor(dy: Float): VerticalIntent? = when {
    closing -> null
    state.infoOpen -> if (dy > 0f) VerticalIntent.CloseInfo else null
    dy > 0f -> VerticalIntent.Dismiss
    !sidePanel -> VerticalIntent.OpenInfo
    else -> null
  }

  fun onVerticalDrag(intent: VerticalIntent, delta: Offset) {
    scope.launch {
      when (intent) {
        VerticalIntent.Dismiss -> transition.dragBy(delta, stage.height)
        VerticalIntent.OpenInfo, VerticalIntent.CloseInfo -> info.dragBy(-delta.y / sheetPx)
      }
    }
  }

  fun onVerticalEnd(intent: VerticalIntent, velocityY: Float) {
    scope.launch {
      when (intent) {
        VerticalIntent.Dismiss -> releaseDismiss(velocityY)
        VerticalIntent.OpenInfo -> state.infoOpen = info.release(-velocityY / sheetPx, reducedMotion, InfoPanelState.OpenBias)
        VerticalIntent.CloseInfo -> state.infoOpen = info.release(-velocityY / sheetPx, reducedMotion, InfoPanelState.CloseBias)
      }
    }
  }

  fun onVerticalCancel(intent: VerticalIntent) {
    scope.launch {
      when (intent) {
        VerticalIntent.Dismiss -> transition.release(0f, density, Float.MAX_VALUE, reducedMotion)
        VerticalIntent.OpenInfo, VerticalIntent.CloseInfo -> info.settle(state.infoOpen, reducedMotion)
      }
    }
  }

  // --- Barre, informazioni ------------------------------------------------------------------------------------------

  fun toggleChrome() {
    if (!closing) state.chromeVisible = !state.chromeVisible
  }

  fun toggleInfo() {
    if (!closing) state.infoOpen = !state.infoOpen
  }

  // --- Motion photo -------------------------------------------------------------------------------------------------

  /**
   * Il badge "Movimento", la voce ⋮ "Riproduci il movimento" e l'azione di TalkBack (D51): il video intero, una volta, con l'audio al
   * volume dei contenuti multimediali; poi torna la foto. Un altro tocco lo ferma. **La pressione lunga non c'entra piu'** (D45): resta
   * libera per il testo dal vivo e il ritaglio.
   */
  fun playMotion() {
    if (!closing) motion.playFull(state.currentId)
  }

  /** Un tocco, un pizzico, un doppio tocco o un trascinamento sulla pagina: l'animazione d'apertura si ferma subito (D50). */
  fun onMotionTouch() = motion.interruptOpening()

  /** L'utente accetta i dati mobili per la parte video di [id] (E-V15, D51): `:app` registra la conferma e il video parte. */
  fun confirmMotionMobileData(id: MediaId) {
    val ref = refOf(id) ?: return
    services.onConfirmMobileData?.invoke(ref)
    motion.state.notice = null
    motion.playFull(id)
  }

  /** Il badge si vede: una foto in movimento il cui video si trova; per un media del NAS solo con la lettura a intervalli collegata (D13). */
  fun motionAvailable(id: MediaId): Boolean {
    if (motionUnplayableVersion < 0 || kindOf(id) != MediaKind.MOTION_PHOTO || id in motionUnplayable) return false
    return when (refOf(id)) {
      is MediaRef.Phone -> true
      is MediaRef.Nas -> services.nasMotion != null
      else -> false
    }
  }

  // --- Media del NAS (Fase 4) ----------------------------------------------------------------------------------------

  /** "Riprova" dalla nota: la pagina si ricarica e, per un video, riparte. L'originale chiesto con lo zoom si richiede. */
  fun retry() {
    val id = state.currentId
    state.originals.remove(id)
    // Il video che era in errore va rifatto da capo: il lettore libera la sorgente e la pagina lo riavvia.
    if (playback.videoFor == id) playback.clear()
    state.retries[id] = (state.retries[id] ?: 0) + 1
  }

  /**
   * D-15 (prove sul campo): la rete verso il NAS e' cambiata ([network] e' quella di adesso). Quello che una pagina dice per colpa della rete ("Servono
   * i dati mobili", "NAS non raggiungibile fuori casa", "Nessuna rete", "Il NAS non risponde") non e' piu' vero com'era: si rifa il caricamento delle
   * pagine in quello stato, come dopo un "Riprova" (un video riparte da solo, una foto riprende la sua sorgente), cosi' lo stato segue la rete di adesso
   * invece di restare quello di quando e' stato calcolato (sul campo: dopo il ritorno del Wi-Fi il viewer restava su "Servono i dati mobili").
   *
   * Una pagina che funziona (un video che va, una foto nitida) non si tocca: un cambio di rete non la rilancia. Un video fermo da un errore di rete
   * riparte solo se adesso il NAS si puo' provare. Un download in attesa dei dati mobili si scarta: la prossima richiesta lo rivaluta con la rete di adesso.
   */
  fun networkChanged(network: NasNetwork) {
    if (closing) return
    val stale = LinkedHashSet<MediaId>()
    for ((id, original) in state.originals) if (original is OriginalProgress.Blocked && original.reason in NETWORK_REASONS) stale += id
    for ((id, image) in state.images) if (image.unavailable?.reason in NETWORK_REASONS) stale += id
    motion.networkChanged()
    val id = state.currentId
    val reachable = network == NasNetwork.OPEN || network == NasNetwork.METERED
    if (reachable && playback.videoFor == id && playback.player?.state?.value?.problem == PlaybackProblem.NAS_UNREACHABLE) stale += id
    for (page in stale) {
      state.originals.remove(page)
      // Il video che era in errore va rifatto da capo: il lettore libera la sorgente e la pagina lo riavvia.
      if (playback.videoFor == page) playback.clear()
      state.retries[page] = (state.retries[page] ?: 0) + 1
    }
    services.download?.let { downloads ->
      for ((locationId, download) in downloads.states.value) if (download is DownloadState.NeedsMobileData) downloads.acknowledge(locationId)
    }
  }

  /**
   * L'utente accetta i dati mobili per [ref] (E-V2): :app registra la conferma (`onConfirmMobileData`) e la pagina riparte da
   * sola, come dopo un "Riprova". Senza il collegamento di :app la nota non propone il tasto.
   */
  fun confirmMobileData(ref: MediaRef) {
    services.onConfirmMobileData?.invoke(ref)
    retry()
  }

  /** Come [confirmMobileData], per "Scarica sul telefono": dopo la conferma il download parte. */
  fun confirmMobileDataForDownload(ref: MediaRef.Nas) {
    services.onConfirmMobileData?.invoke(ref)
    services.download?.start(ref)
  }

  /** "Scarica sul telefono" dal menu: il download va in coda e continua anche se il viewer si chiude (D20). */
  fun download() {
    if (closing) return
    val ref = refOf(state.currentId) as? MediaRef.Nas ?: return
    services.download?.start(ref)
  }

  fun cancelDownload() {
    val ref = refOf(state.currentId) as? MediaRef.Nas ?: return
    services.download?.cancel(ref)
  }

  // --- Azioni --------------------------------------------------------------------------------------------------------

  /** Dove stanno i byte di [id], o null se adesso non c'e' una copia leggibile o l'elemento e' sparito del tutto. */
  fun refOf(id: MediaId): MediaRef? = request.items.refOrNull(id)

  fun isFavorite(id: MediaId): Boolean {
    state.favoriteOverride[id]?.let { return it }
    val index = view.snapshot.indexOf(id)
    return index >= 0 && CellFlags.has(view.snapshot.flagsAt(index), CellFlags.FAVORITE)
  }

  fun toggleFavorite() {
    val actions = services.actions ?: return
    val id = state.currentId
    val on = !isFavorite(id)
    state.favoriteOverride[id] = on
    scope.launch {
      if (!attempt { actions.setFavorite(setOf(id), on) }) {
        // Non salvato: la stella torna com'era e lo si dice (R8).
        state.favoriteOverride.remove(id)
        state.message = ViewerMessage.FavoriteFailed
      }
    }
  }

  /**
   * Nascondi (Libreria) o Mostra (Nascosti). L'elemento sparisce dalla vista: il viewer passa al vicino con un avviso, ma
   * solo se la scrittura e' riuscita; se non riesce l'avviso dice che non e' stato possibile e l'elemento resta com'era (R8).
   */
  fun toggleHidden() {
    val actions = services.actions ?: return
    val id = state.currentId
    val hide = !request.secure
    changingVisibility++
    scope.launch {
      var familySees = false
      val done = try {
        val written = attempt { actions.setHidden(setOf(id), hide) }
        // E-H2: la domanda sta dentro la finestra "sto cambiando la visibilita'", altrimenti l'elemento che esce dalla vista si annuncerebbe
        // come sparito (ItemGone) prima che il messaggio giusto sia pronto.
        if (written && hide) familySees = familyStillSees(actions, id)
        written
      } finally {
        changingVisibility--
      }
      val message = when {
        done && hide && familySees -> ViewerMessage.HiddenFamilySees
        done && hide -> ViewerMessage.Hidden
        done -> ViewerMessage.Unhidden
        hide -> ViewerMessage.HideFailed
        else -> ViewerMessage.UnhideFailed
      }
      when {
        !done -> state.message = message
        // E' gia' sparito dalla vista (il viewer e' passato al vicino senza dire niente): l'avviso adesso.
        request.items.view.value.viewIndexOf(id) < 0 -> state.message = message
        // Sparira' fra un attimo: lo dira' la vista che cambia.
        else -> pendingMessage = message
      }
    }
  }

  fun share() {
    val phone = refOf(state.currentId) as? MediaRef.Phone ?: return
    val intent = services.intents?.share(listOf(phone))
    if (intent == null) {
      state.message = ViewerMessage.ActionFailed
      return
    }
    val chooser = Intent.createChooser(intent, null).apply { if (context.findActivity() == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    try {
      context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
      state.message = ViewerMessage.ActionFailed
    }
  }

  fun copy(label: String) {
    val phone = refOf(state.currentId) as? MediaRef.Phone ?: return
    val uri = services.intents?.uriOf(phone) ?: return
    state.message = if (ViewerSystem.copyUri(context, uri, label, sensitive = request.secure)) ViewerMessage.Copied else ViewerMessage.ActionFailed
  }

  /** Il foglio di conferma di "Elimina dal telefono" (D14). Niente parte finche' l'utente non lo conferma. */
  fun askDelete() {
    if (!closing) sheet = ViewerSheet.Delete
  }

  /** Confermato: il dialogo di sistema del cestino (30 giorni, ripristinabile). L'app non cancella mai da sola. */
  fun confirmDelete() {
    sheet = null
    val phone = refOf(state.currentId) as? MediaRef.Phone ?: return
    val sender = services.intents?.trashRequest(listOf(phone), trash = true)
    if (sender == null) state.message = ViewerMessage.ActionFailed else launchSender(sender)
  }

  fun askMaps() {
    if (!closing) sheet = ViewerSheet.Maps
  }

  fun confirmMaps(point: GeoPoint?) {
    sheet = null
    if (point == null) return
    val intent = ViewerSystem.mapsIntent(point).apply { if (context.findActivity() == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    try {
      context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
      state.message = ViewerMessage.ActionFailed
    }
  }

  /** Il tipo di cio' che si sta guardando, dall'indice. */
  fun kindOf(id: MediaId): MediaKind? {
    val index = view.snapshot.indexOf(id)
    return if (index >= 0) view.snapshot.kindAt(index) else null
  }

  /** Ripristina la posizione di un trascinamento senza chiudere, dopo un Indietro predittivo annullato. */
  suspend fun cancelBack() = coroutineScope {
    transition.release(0f, density, Float.MAX_VALUE, reducedMotion)
  }

  private companion object {
    const val FallbackFraction = 0.3f
    const val MILLI = 1000f
    const val INFO_REST = 0.01f

    /** D-15: i motivi per cui una pagina non mostra l'originale che dipendono dalla rete di quel momento (non il volume tolto). */
    val NETWORK_REASONS: Set<UnavailableReason> = setOf(
      UnavailableReason.NAS_UNREACHABLE,
      UnavailableReason.NOT_ALLOWED_HERE,
      UnavailableReason.NEEDS_MOBILE_DATA_CONFIRMATION,
      UnavailableReason.VPN_OFF_AWAY,
      UnavailableReason.OFFLINE,
    )
  }
}
