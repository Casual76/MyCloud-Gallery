package dev.pampa.mycloudgallery.feature.library.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridNotice
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.feature.library.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Correzioni 5B (R4, MC-2, docs/review/5b-fase5b.md, docs/review/funzionale-5b.md): "Condividi" dalla selezione rispetta "Rimuovi la posizione".
// Prima la selezione mandava sempre gli originali del MediaStore, con il GPS. Con l'impostazione accesa `:app` prepara una copia senza posizione di
// ogni media (la stessa via del viewer, `LocationFreeShare`), e puo' durare: la Libreria mostra l'avanzamento con "Annulla" e, se la posizione non
// si toglie, il foglio "Condivisione ferma" con il motivo. "Condividi con la posizione" e' solo una scelta esplicita dell'utente; chiudere il foglio
// non condivide niente.

/** Lo stato di una condivisione senza posizione dalla selezione. Il testo del motivo lo scrive `:app` (frase fissa, mai il nome di un file). */
@Immutable
sealed interface LibraryShare {
  data object Idle : LibraryShare

  /** Si preparano le copie: [done] di [total]. */
  data class Preparing(val done: Int, val total: Int) : LibraryShare

  /** Ferma, con il motivo; [canShareWithLocation] = il foglio offre "Condividi con la posizione". */
  data class Refused(val message: String, val canShareWithLocation: Boolean) : LibraryShare
}

/** Nessuna condivisione in corso: il valore delle porte che non la legano. */
internal val NoLibraryShare: StateFlow<LibraryShare> = MutableStateFlow(LibraryShare.Idle)

/** L'avviso sopra la griglia mentre si preparano le copie senza posizione, con "Annulla". */
@Composable
internal fun LibraryShareNotice(share: LibraryShare.Preparing, backdrop: GlassBackdropState, onCancel: () -> Unit, modifier: Modifier = Modifier) {
  GalleryGridNotice(
    icon = Icons.Rounded.IosShare,
    title = stringResource(R.string.library_share_preparing),
    message = stringResource(R.string.library_share_progress, share.done.coerceAtMost(share.total), share.total),
    backdrop = backdrop,
    modifier = modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    actionLabel = stringResource(R.string.library_share_cancel),
    onAction = onCancel,
  )
}

/** Il foglio di una condivisione ferma: il motivo e, se si puo', la scelta esplicita "Condividi con la posizione". */
@Composable
internal fun LibraryShareRefusedSheet(state: LibraryShare.Refused?, onShareWithLocation: () -> Unit, onDismiss: () -> Unit) {
  // Il foglio che si chiude fa la sua uscita con la frase che aveva.
  var shown by remember { mutableStateOf<LibraryShare.Refused?>(null) }
  if (state != null) shown = state
  val showing = shown
  GalleryConfirmSheet(visible = state != null, onDismissRequest = onDismiss, paneTitle = stringResource(R.string.library_share_stopped_title)) {
    if (showing != null) {
      GallerySheetScaffold(
        icon = Icons.Rounded.LocationOff,
        tone = GallerySheetTone.Caution,
        title = stringResource(R.string.library_share_stopped_title),
        message = showing.message,
        actions = {
          if (showing.canShareWithLocation) {
            FluidButton(
              text = stringResource(R.string.library_share_with_location),
              onClick = onShareWithLocation,
              style = FluidButtonStyle.Tinted,
              size = FluidButtonSize.Large,
              fillWidth = true,
            )
            FluidButton(text = stringResource(R.string.library_share_cancel), onClick = onDismiss, size = FluidButtonSize.Large, fillWidth = true)
          } else {
            FluidButton(text = stringResource(R.string.library_share_understood), onClick = onDismiss, size = FluidButtonSize.Large, fillWidth = true)
          }
        },
      )
    }
  }
}
