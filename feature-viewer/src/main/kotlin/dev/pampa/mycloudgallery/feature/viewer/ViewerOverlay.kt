package dev.pampa.mycloudgallery.feature.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryNavigationIcon
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTopBar
import dev.pampa.mycloudgallery.core.imaging.contract.ImageFailure
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageSource
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageState
import dev.pampa.mycloudgallery.core.imaging.noop.NoViewerImageSource
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest

/**
 * Il viewer: una **sovrapposizione nella stessa schermata**, non una rotta (D12). `:app` lo ospita sopra la pillola e lo
 * mostra quando [request] c'e'; [onClosed] lo toglie. La miniatura diventa il viewer (espansione con
 * `GalleryViewerTransitionState` e `fluidPhysicsClip`), si chiude con un tocco, lo swipe giu' o l'Indietro predittivo.
 *
 * **Stub di P0**: un pager sulla vista della Libreria (lo stesso filtro) che mostra l'immagine della sua
 * [ViewerImageSource] e un tasto per chiudere, **senza** espansione, zoom, video, motion photo, info, menu, chrome che si
 * nasconde ne' `SecureScreen` per `request.secure`. Quello e' di P8: la firma di questa funzione resta, il parametro
 * [images] sparisce (lo prendera' dal ViewModel). Se la vista si svuota (E-V6) si chiude da sola.
 *
 * @param images da dove arrivano i pixel: i null-object finche' non c'e' P6, il viewer finto nei test e in debug.
 */
@Composable
fun ViewerOverlay(
  request: ViewerRequest?,
  onClosed: () -> Unit,
  modifier: Modifier = Modifier,
  images: ViewerImageSource = NoViewerImageSource,
) {
  if (request == null) return
  ViewerStub(request = request, onClosed = onClosed, images = images, modifier = modifier)
}

@Composable
private fun ViewerStub(
  request: ViewerRequest,
  onClosed: () -> Unit,
  images: ViewerImageSource,
  modifier: Modifier,
) {
  val view by request.items.view.collectAsState()
  // E-V6: la lista e' vuota (cancellata da Galleria o da un altro dispositivo): il viewer si chiude.
  LaunchedEffect(view.size) { if (view.size == 0) onClosed() }
  if (view.size == 0) return

  val pagerState = rememberPagerState(initialPage = view.viewIndexOf(request.start).coerceAtLeast(0)) { view.size }
  BackHandler(onBack = onClosed)
  val backdrop = rememberGlassBackdrop()
  Box(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      // Il viewer copre la griglia e la pillola: i tocchi non devono passargli sotto.
      .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
  ) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().glassBackdropSource(backdrop)) {
      val density = LocalDensity.current
      val screen = IntSize(with(density) { maxWidth.roundToPx() }, with(density) { maxHeight.roundToPx() })
      HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        val index = page.coerceAtMost(view.size - 1)
        val id = view.snapshot.idAt(view.snapshotIndex(index))
        ViewerStubPage(ref = request.items.ref(id), images = images, screen = screen)
      }
    }
    GalleryTopBar(
      title = stringResource(R.string.viewer_position, pagerState.currentPage + 1, view.size),
      backdrop = backdrop,
      modifier = Modifier.align(Alignment.TopCenter),
      onPhoto = true,
      onNavigate = onClosed,
      navigationIcon = GalleryNavigationIcon.Close,
    )
  }
}

@Composable
private fun ViewerStubPage(
  ref: MediaRef,
  images: ViewerImageSource,
  screen: IntSize,
) {
  var shown by remember(ref) { mutableStateOf<ImageBitmap?>(null) }
  var message by remember(ref) { mutableStateOf<Int?>(null) }
  LaunchedEffect(ref) {
    images.state(ref, screen).collect { state ->
      when (state) {
        is ViewerImageState.Preview -> shown = state.bitmap
        is ViewerImageState.Sharp -> shown = state.bitmap
        is ViewerImageState.Loading -> Unit
        is ViewerImageState.Unavailable -> message = R.string.viewer_unavailable
        is ViewerImageState.Failed -> message = if (state.code == ImageFailure.UNSUPPORTED_FORMAT) R.string.viewer_unsupported else R.string.viewer_failed
      }
    }
  }
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    val bitmap = shown
    if (bitmap != null) {
      Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    } else {
      message?.let {
        Text(
          text = stringResource(it),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
