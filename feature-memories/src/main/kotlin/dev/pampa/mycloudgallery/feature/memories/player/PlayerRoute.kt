package dev.pampa.mycloudgallery.feature.memories.player

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.fluid.rememberFluidMotionPolicy
import dev.antigravity.fluidengine.ui.theme.FluidTheme
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryAccent
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesThumbnails
import dev.pampa.mycloudgallery.feature.memories.contract.NoPlayerClipSurface
import dev.pampa.mycloudgallery.feature.memories.contract.NoPlayerImages
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerClipSurface
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerEnvironment
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerImages
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerUi
import dev.pampa.mycloudgallery.feature.memories.nav.MemoriesDependencies
import dev.pampa.mycloudgallery.feature.memories.nav.MemoryPlayerDestination
import dev.pampa.mycloudgallery.feature.memories.page.parseMemoryId
import javax.inject.Inject

// LA ROTTA DELLA PRESENTAZIONE (Fase 9, P9): lega il controllore, le immagini (miniature e anteprima locale), la musica e l'ambiente del dispositivo
// (animazioni ridotte, TalkBack). Nessuna logica propria: cio' che la presentazione fa lo decide `PlayerController`. Uscendo (la X, il gesto, "indietro")
// il controllore si ferma, musica compresa.

@HiltViewModel
class MemoryPlayerViewModel @Inject constructor(savedStateHandle: SavedStateHandle, private val deps: MemoriesDependencies) : ViewModel() {
  val id: MemoryId? = parseMemoryId(savedStateHandle.toRoute<MemoryPlayerDestination>().id)

  private var built: PlayerController? = null
  private var builtImages: PlayerImages = NoPlayerImages

  /** Le immagini della presentazione in corso (null-object finche' il controllore non c'e'). */
  val images: PlayerImages get() = builtImages

  /** Il controllore, costruito la prima volta che la rotta ha le miniature (che `:app` consegna alla composizione); null se l'id non e' di un ricordo. */
  fun controller(thumbnails: MemoriesThumbnails, environment: PlayerEnvironment): PlayerController? {
    val memory = id ?: return null
    built?.let { return it }
    val images = ThumbnailPlayerImages(thumbnails, viewModelScope)
    builtImages = images
    return PlayerController(
      memory, deps.memories, deps.names, deps.filter, deps.clips, images, deps.musicSettings, deps.musicLibrary, deps.playback, viewModelScope, environment,
    ).also { built = it }
  }

  override fun onCleared() {
    built?.exit()
  }
}

/** Come sta il dispositivo adesso per la presentazione: le animazioni di sistema (scala zero = ridotte) e se un lettore di schermo e' acceso. */
@Composable
fun rememberPlayerEnvironment(): PlayerEnvironment {
  val reduced = rememberFluidMotionPolicy().reducedMotion
  val context = LocalContext.current
  var talkBack by remember { mutableStateOf(isTouchExploring(context)) }
  DisposableEffect(context) {
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    val listener = AccessibilityManager.TouchExplorationStateChangeListener { talkBack = it }
    manager?.addTouchExplorationStateChangeListener(listener)
    onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
  }
  return PlayerEnvironment(reducedMotion = reduced, talkBack = talkBack)
}

private fun isTouchExploring(context: Context): Boolean =
  (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isTouchExplorationEnabled == true

/** La presentazione e' sempre scura (§2.4): un tema scuro dedicato, senza toccare le barre di sistema della pagina sotto. */
@Composable
internal fun PlayerTheme(content: @Composable () -> Unit) {
  FluidTheme(
    settings = EngineSettings(themeMode = ThemeMode.AMOLED),
    brand = GalleryAccent.Amethyst,
    presets = GalleryAccent.presets,
    systemBars = false,
    content = content,
  )
}

@Composable
fun MemoryPlayerRoute(
  thumbnails: MemoriesThumbnails,
  onExit: () -> Unit,
  modifier: Modifier = Modifier,
  clips: PlayerClipSurface = NoPlayerClipSurface,
  viewModel: MemoryPlayerViewModel = hiltViewModel(),
) {
  val environment = rememberPlayerEnvironment()
  val controller = remember(viewModel, thumbnails) { viewModel.controller(thumbnails, environment) }
  if (controller == null) {
    PlayerTheme { PlayerScreen(PlayerUi(gone = true), thumbnails, NoPlayerImages, clips, NoPlayerHandlers.withExit(onExit), modifier) }
    return
  }
  LaunchedEffect(controller, environment) { controller.setEnvironment(environment) }
  val ui by controller.ui.collectAsState()
  val handlers = remember(controller, onExit) {
    object : PlayerHandlers {
      override fun onTap() = controller.tap()

      override fun onPlay() = controller.resume()

      override fun onPause() = controller.pause()

      override fun onNext() = controller.next()

      override fun onPrevious() = controller.previous()

      override fun onRestart() = controller.restart()

      override fun onExit() {
        controller.exit()
        onExit()
      }
    }
  }
  PlayerTheme { PlayerScreen(ui, thumbnails, viewModel.images, clips, handlers, modifier) }
}

private fun PlayerHandlers.withExit(exit: () -> Unit): PlayerHandlers = object : PlayerHandlers by this {
  override fun onExit() = exit()
}
