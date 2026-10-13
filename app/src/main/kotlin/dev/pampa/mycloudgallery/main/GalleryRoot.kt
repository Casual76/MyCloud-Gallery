package dev.pampa.mycloudgallery.main

import android.os.Trace
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.fluidGlassModalObscured
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.viewer.ViewerOverlay
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices

/**
 * L'host del viewer (D12): il viewer e' una **sovrapposizione**, non una rotta. Uno solo per la galleria, sopra la pillola
 * e sopra le rotte delle Raccolte; la Libreria e le griglie filtrate gli mandano la richiesta con i servizi giusti
 * (quelli del grafo vero, o quelli della Libreria sintetica in debug).
 *
 * **E-V8**: se il viewer era aperto dalla Libreria, l'id dell'elemento guardato si salva; dopo la ricreazione
 * dell'activity (o la morte del processo) la Libreria lo riapre sullo stesso elemento ([pendingLibraryRestore]). Il resto
 * dello stato (barre, pannello, zoom) lo ritrova il viewer da se' (`rememberSaveable`). I Nascosti e le Raccolte non si
 * riaprono: i Nascosti chiedono di nuovo la credenziale (E-H3), le Raccolte tornano alla griglia.
 *
 * **Traccia `MCG:viewerOpen`** (P9, `TraceSectionMetric`): asincrona, dal tocco ([open]) al primo fotogramma disegnato
 * con il viewer, che parte gia' con la bitmap della miniatura (opaco dal primo fotogramma).
 */
@Stable
class ViewerHostState internal constructor(restoreLibraryId: String?) {

  var request: ViewerRequest? by mutableStateOf(null)
    private set

  var services: ViewerServices by mutableStateOf(ViewerServices())
    private set

  /** L'elemento guardato nel viewer aperto dalla Libreria (per E-V8); null se il viewer e' chiuso o viene da altrove. */
  private var libraryCurrent: String? = null
  private var fromLibrary = false

  /** Dopo una ricreazione: l'elemento da cui riaprire il viewer della Libreria. Lo consuma la Libreria. */
  var pendingLibraryRestore: String? by mutableStateOf(restoreLibraryId)
    private set

  private var traceCookie = 0
  private var tracing = false

  /** Apre il viewer (se non e' gia' aperto) con [services]. [fromLibrary] = la richiesta viene dalla scheda Libreria. */
  fun open(request: ViewerRequest, services: ViewerServices, fromLibrary: Boolean = false) {
    if (this.request != null) return
    traceCookie++
    Trace.beginAsyncSection(TRACE_VIEWER_OPEN, traceCookie)
    tracing = true
    this.fromLibrary = fromLibrary && !request.secure
    libraryCurrent = if (this.fromLibrary) request.start.value else null
    this.services = services
    this.request = request
  }

  internal fun onCurrentChanged(id: MediaId) {
    if (fromLibrary) libraryCurrent = id.value
  }

  internal fun onFirstFrame() {
    if (!tracing) return
    Trace.endAsyncSection(TRACE_VIEWER_OPEN, traceCookie)
    tracing = false
  }

  fun close() {
    request = null
    libraryCurrent = null
    fromLibrary = false
    if (tracing) onFirstFrame()
  }

  /** La Libreria ha riaperto (o rinunciato a riaprire) il viewer dopo una ricreazione. */
  fun consumeLibraryRestore(): String? = pendingLibraryRestore.also { pendingLibraryRestore = null }

  companion object {
    /** Il nome della sezione di traccia che misura `ViewerBenchmark` (P9, `BenchmarkTarget.TRACE_VIEWER_OPEN`). */
    const val TRACE_VIEWER_OPEN: String = "MCG:viewerOpen"

    /** Si salva solo l'id dell'elemento della Libreria (mai dei Nascosti: la richiesta sicura non si ricorda). */
    val Saver: Saver<ViewerHostState, String> = Saver(
      save = { it.libraryCurrent ?: it.pendingLibraryRestore },
      restore = { ViewerHostState(it) },
    )
  }
}

/** L'host del viewer della galleria, se c'e' (null nei test che compongono la sola scheda). */
val LocalViewerHost = staticCompositionLocalOf<ViewerHostState?> { null }

/**
 * La radice della galleria (stato `Main`): **l'host dei pop-up** (`FluidGlassModalHost`) e **l'host del viewer**, sopra
 * la navigazione. Cosi' i fogli (eliminazione, menu, conferme del viewer) stanno sopra la pillola e sopra il viewer
 * (prima il foglio di eliminazione della Libreria finiva sotto la pillola, nota di P7), e il viewer sta sopra la pillola e
 * sopra le rotte delle Raccolte.
 */
@Composable
fun GalleryRoot(content: @Composable () -> Unit) {
  val viewer = rememberSaveable(saver = ViewerHostState.Saver) { ViewerHostState(null) }
  val modal = rememberFluidGlassModalHostState()
  val backdrop = rememberGlassBackdrop()
  CompositionLocalProvider(LocalFluidGlassModalHostState provides modal, LocalViewerHost provides viewer) {
    Box(modifier = Modifier.fillMaxSize()) {
      // Il fondale della radice lo campiona solo il vetro dei pop-up (`FluidGlassModalHost`): c'e' solo mentre un pop-up
      // e' sullo schermo, dall'apertura alla fine dell'uscita (`isOnScreen`). Senza pop-up era un layer fuori schermo a
      // tutto schermo in piu', ridisegnato a ogni fotogramma di scorrimento, pinch e apertura del viewer (che ci sta
      // dentro), in un RenderThread gia' oltre il budget di memoria: sull'S25 le miniature si ricaricavano a ogni
      // fotogramma. Il pop-up si compone un fotogramma dopo l'apertura (il portale scrive `visible` in un SideEffect):
      // nello stesso fotogramma questa radice si ricompone con il fondale, che si registra prima che il vetro lo legga.
      val rootSource = if (modal.isOnScreen) Modifier.glassBackdropSource(backdrop) else Modifier
      Box(modifier = Modifier.fillMaxSize().then(rootSource).fluidGlassModalObscured()) {
        content()
        ViewerLayer(viewer)
      }
      FluidGlassModalHost(state = modal, backdrop = backdrop)
    }
  }
}

/** Il viewer di [host], con la fine della sezione di traccia al primo fotogramma disegnato. */
@Composable
internal fun ViewerLayer(host: ViewerHostState) {
  val request = host.request
  ViewerOverlay(
    request = request,
    onClosed = host::close,
    services = host.services,
    onCurrentChanged = host::onCurrentChanged,
  )
  if (request != null) {
    LaunchedEffect(request) {
      // La composizione con il viewer e' gia' avvenuta: il fotogramma successivo e' il primo disegnato.
      withFrameNanos { }
      host.onFirstFrame()
    }
  }
}
