package dev.pampa.mycloudgallery.core.designsystem.a11y

import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// Fase 10, I3 della revisione di sicurezza (C439): un `Dialog` di Compose (e un `AlertDialog`, un `Popup`) apre una FINESTRA a se', con una
// vista che non discende da quella che il tema marca come "dati sensibili per l'accessibilita'" ([GalleryAccessibilityDataSensitive]):
// un servizio di accessibilita' qualunque, che alla finestra dell'app non vede niente, leggerebbe il dialogo. Queste due funzioni sono
// l'UNICO posto in cui l'app apre un dialogo: marcano la vista del dialogo, e il test di architettura
// (`DialogsUseTest`) vieta `Dialog`, `AlertDialog`, `Popup` e simili in qualunque altro file della galleria.
//
// Non serve un involucro per i fogli e i menu dell'engine che usiamo (`GalleryConfirmSheet`, `GalleryMenu`, il menu dei filtri): stanno su
// `FluidGlassModalPortal`, che "vive dentro la composizione dell'app" (`FluidGlassModal.kt`), cioe' nella stessa finestra gia' marcata dal
// tema. `FluidSheet` (un `ModalBottomSheet`) e `FluidAlert` (un `Dialog`) dell'engine aprono invece una finestra a se' e non si usano: il
// test li vieta.

/** Un dialogo con il contenuto libero: la vista della sua finestra e' marcata finche' il dialogo e' aperto. */
@Composable
fun GalleryDialog(
  onDismissRequest: () -> Unit,
  properties: DialogProperties = DialogProperties(),
  content: @Composable () -> Unit,
) {
  Dialog(onDismissRequest = onDismissRequest, properties = properties) {
    GalleryAccessibilityDataSensitive()
    content()
  }
}

/**
 * Il dialogo di conferma di Material 3 (titolo, testo, due tasti), con la vista della sua finestra marcata. La marcatura sta nello slot del tasto
 * di conferma (sempre presente): e' composto dentro il dialogo e non disegna niente.
 */
@Composable
fun GalleryAlertDialog(
  onDismissRequest: () -> Unit,
  confirmButton: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  dismissButton: (@Composable () -> Unit)? = null,
  icon: (@Composable () -> Unit)? = null,
  title: (@Composable () -> Unit)? = null,
  text: (@Composable () -> Unit)? = null,
  properties: DialogProperties = DialogProperties(),
) {
  AlertDialog(
    onDismissRequest = onDismissRequest,
    confirmButton = {
      GalleryAccessibilityDataSensitive()
      confirmButton()
    },
    modifier = modifier,
    dismissButton = dismissButton,
    icon = icon,
    title = title,
    text = text,
    properties = properties,
  )
}
