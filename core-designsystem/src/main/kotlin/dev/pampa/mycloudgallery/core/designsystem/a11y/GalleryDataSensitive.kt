package dev.pampa.mycloudgallery.core.designsystem.a11y

import android.os.Build
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

// Fase 10, P8 (D24, E-AC10): solo gli strumenti di accessibilita' veri leggono i contenuti dell'app.
//
// Da Android 14 una vista si marca `accessibilityDataSensitive`: TalkBack, Switch Access e Voice Access (che dichiarano
// `isAccessibilityTool`) la leggono tutta; un servizio di accessibilita' qualunque (la via preferita del malware per leggere lo
// schermo) non vede quella finestra. `FLAG_SECURE` non li ferma e toglierebbe gli screenshot all'utente.
// Sotto Android 14 non c'e' niente da marcare (limite dichiarato nel progetto: L551).
//
// La prova che la marcatura sulla vista radice copra anche i nodi virtuali di Compose si fa con il servizio di prova **solo debug**
// (`FieldProbeA11yService`, `isAccessibilityTool="false"`): l'esito e' in `docs/decisioni.md`, "Fase 10 P8".

/** Vero se questo Android sa marcare una vista come dati sensibili per l'accessibilita' (Android 14+). */
val AccessibilityDataSensitiveSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

/**
 * Marca la vista che ospita la finestra dell'app come dati sensibili per l'accessibilita' finche' sta in composizione.
 * Il tema della galleria lo fa da solo ([MyCloudGalleryTheme], parametro `protectAccessibilityData`): ogni schermata dell'app,
 * compresi il selettore e la ricezione, lo ha senza saperlo.
 */
@Composable
fun GalleryAccessibilityDataSensitive(enabled: Boolean = true) {
  val view = LocalView.current
  DisposableEffect(view, enabled) {
    if (enabled && AccessibilityDataSensitiveSupported) {
      view.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
    }
    onDispose {
      if (enabled && AccessibilityDataSensitiveSupported) view.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_AUTO)
    }
  }
}
