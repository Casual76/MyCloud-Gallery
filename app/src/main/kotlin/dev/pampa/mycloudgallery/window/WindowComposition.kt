package dev.pampa.mycloudgallery.window

import android.app.Activity
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavController
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryWindow
import dev.pampa.mycloudgallery.core.designsystem.adaptive.NewWindowPort
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.feature.albums.nav.openAlbum
import dev.pampa.mycloudgallery.feature.map.nav.openMap
import dev.pampa.mycloudgallery.feature.memories.nav.openMemory

// LA FINESTRA DENTRO LA COMPOSIZIONE (Fase 10, P4, D16-D18). Cio' che ogni finestra sa di se': il suo ruolo, la pagina da cui e' partita, la porta per aprirne
// un'altra. Lo mette `MainActivity` alla radice di ogni finestra; fuori dall'app (test, anteprime) i valori di serie sono quelli di una finestra sola.

/**
 * Il ruolo di questa finestra. Di serie **principale** (una finestra sola). Chi serve un ingresso dall'esterno (la notifica dei ricordi, il tocco sul widget) lo fa solo se
 * [WindowRole.servesEntryRequests]: con due finestre una sola prende la richiesta, e deve essere quella che l'utente si aspetta (D16).
 */
val LocalWindowRole = staticCompositionLocalOf { WindowRole.PRIMARY }

/** La pagina da cui e' partita questa finestra, una volta sola (D16). Di serie nessuna. */
class WindowStartRequest(private var target: WindowTarget?) {
  /** La prende chi apre la pagina; dopo, `null`. */
  fun take(): WindowTarget? = target.also { target = null }
}

val LocalWindowStart = staticCompositionLocalOf<WindowStartRequest?> { null }

/**
 * Porta la finestra nuova alla pagina da cui e' partita (D16): una volta, quando la galleria e' composta (cioe' **dopo il blocco app**). Un evento interno: nessun dato
 * nell'intent. La Libreria e' la pagina di sempre e non fa niente. Si mette in `MainDestination` (`AppNavHost`), dove c'e' il `NavController` della galleria.
 */
@Composable
fun WindowStartEffect(nav: NavController) {
  WindowStartEffect(
    onMap = { nav.openMap() },
    onAlbum = { nav.openAlbum(it) },
    onMemory = { nav.openMemory(it) },
  )
}

/** Come sopra, con le uscite in mano (le prova la composizione senza una navigazione vera). */
@Composable
internal fun WindowStartEffect(onMap: () -> Unit, onAlbum: (AlbumId) -> Unit, onMemory: (MemoryId) -> Unit) {
  val start = LocalWindowStart.current ?: return
  LaunchedEffect(start) {
    when (val target = start.take()) {
      null, WindowTarget.Library -> Unit
      WindowTarget.Map -> onMap()
      is WindowTarget.Album -> onAlbum(target.id)
      is WindowTarget.Memory -> onMemory(target.id)
    }
  }
}

/**
 * Mette [content] solo nella finestra che serve gli ingressi dall'esterno ([WindowRole.servesEntryRequests]): la principale. Serve a chi consuma un evento del processo che
 * puo' prendere una finestra sola (la notifica dei ricordi, con P3 il tocco sul widget): con due finestre composte lo prenderebbe la prima che arriva, e non e' detto che sia
 * quella che l'utente si aspetta (D16: notifiche e widget riaprono la finestra principale).
 */
@Composable
fun ServesEntryRequests(content: @Composable () -> Unit) {
  if (LocalWindowRole.current.servesEntryRequests) content()
}

/**
 * La porta delle finestre nuove di una finestra (`LocalNewWindow`): si offre solo dove [WindowPolicy.offersNewWindow] dice di si', e apre con [WindowLauncher]. Un id che non e' un
 * id vero (un album e un ricordo hanno un formato fisso) non apre niente: il menu non dovrebbe mai passarlo, ma qui non si fida.
 */
class AppNewWindowPort(
  private val activity: Activity,
  private val registry: WindowRegistry,
  private val starts: WindowStarts,
  private val sdkInt: Int = Build.VERSION.SDK_INT,
) : NewWindowPort {

  override fun isOffered(window: GalleryWindow): Boolean =
    WindowPolicy.offersNewWindow(window.widthDp, activity.isInMultiWindowMode, sdkInt)

  override fun openLibrary() = open(WindowTarget.Library)

  override fun openAlbum(albumId: String) {
    val id = runCatching { AlbumId(albumId) }.getOrNull() ?: return
    open(WindowTarget.Album(id))
  }

  override fun openMemory(memoryId: String) {
    val id = runCatching { MemoryId(memoryId) }.getOrNull() ?: return
    open(WindowTarget.Memory(id))
  }

  override fun openMap() = open(WindowTarget.Map)

  private fun open(target: WindowTarget) {
    WindowLauncher.open(activity, target, registry, starts, sdkInt)
  }
}
