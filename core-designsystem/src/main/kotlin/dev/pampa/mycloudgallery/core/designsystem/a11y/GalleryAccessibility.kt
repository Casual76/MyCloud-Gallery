package dev.pampa.mycloudgallery.core.designsystem.a11y

import android.app.UiModeManager
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

// Fase 10, P8 (D26, E-AC5): "piu' contrasto" e "meno trasparenza" chiesti dal sistema. Qui si LEGGONO le due impostazioni;
// `GalleryGlass.kt` fa le superfici piene e `GalleryHighContrast.kt` lo schema a contrasto alto.

/**
 * Cosa l'utente ha chiesto al sistema per la leggibilita' dello schermo.
 *
 * - [highContrast]: **piu' contrasto** (`UiModeManager.getContrast()` > 0, Android 14+) oppure "testo ad alto contrasto";
 * - [reduceTransparency]: **meno trasparenza** ("Riduci trasparenza e sfocatura" di One UI e simili).
 *
 * Con una delle due il vetro diventa una **superficie piena** ([solidSurfaces]); con la prima anche lo schema di colori
 * diventa quello a contrasto alto.
 */
@Immutable
data class GalleryAccessibilityPrefs(
  val highContrast: Boolean = false,
  val reduceTransparency: Boolean = false,
) {
  /** Vero se il vetro deve lasciare il posto a una superficie piena. */
  val solidSurfaces: Boolean get() = highContrast || reduceTransparency

  companion object {
    /** Nessuna richiesta: il vetro com'e'. */
    val Standard: GalleryAccessibilityPrefs = GalleryAccessibilityPrefs()
  }
}

/** Le preferenze in scope. Il tema le fornisce (dal sistema o, nei test e negli screenshot, da chi lo posa). */
val LocalGalleryAccessibility = staticCompositionLocalOf { GalleryAccessibilityPrefs.Standard }

/**
 * Legge e osserva le impostazioni di accessibilita' del sistema che riguardano il vetro.
 *
 * **Che cosa si legge**
 * - il contrasto: `UiModeManager.getContrast()` (0 standard, 0,5 medio, 1 alto), da Android 14; piu' `high_text_contrast_enabled`
 *   di `Settings.Secure` ("testo ad alto contrasto");
 * - la trasparenza: [REDUCE_TRANSPARENCY_KEYS], le chiavi con cui i produttori espongono "Riduci trasparenza e sfocatura", cercate in
 *   `Settings.Secure`, `Global` e `System`. **Nessuna e' documentata da Android**: sull'emulatore AOSP non esiste, la chiave di
 *   One UI va confermata sul telefono (F182-F185). Una chiave che non c'e' vale 0, mai un errore.
 */
object GalleryAccessibilityReader {

  /** "testo ad alto contrasto" (`Settings.Secure.ACCESSIBILITY_HIGH_TEXT_CONTRAST_ENABLED`, nascosta). */
  const val HIGH_TEXT_CONTRAST_KEY: String = "high_text_contrast_enabled"

  /** Le chiavi candidate per "meno trasparenza", da confermare sul telefono (vedi sopra). */
  val REDUCE_TRANSPARENCY_KEYS: List<String> = listOf(
    "accessibility_reduce_transparency",
    "reduce_transparency",
    "reduce_transparency_and_blur",
    "accessibility_reduce_transparency_blur",
  )

  /** Il contrasto oltre il quale e' "piu' contrasto": qualunque livello sopra lo standard. */
  const val CONTRAST_THRESHOLD: Float = 0f

  /** La regola pura: da cio' che il sistema dice a cio' che l'app fa. Provata senza un dispositivo. */
  fun fromValues(contrast: Float, highTextContrast: Boolean, reduceTransparency: Boolean): GalleryAccessibilityPrefs =
    GalleryAccessibilityPrefs(
      highContrast = (contrast.isFinite() && contrast > CONTRAST_THRESHOLD) || highTextContrast,
      reduceTransparency = reduceTransparency,
    )

  /** Le preferenze di adesso. Mai un'eccezione: un'impostazione illeggibile e' "non chiesta". */
  fun read(context: Context): GalleryAccessibilityPrefs {
    val contrast = runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.contrast ?: 0f
      } else {
        0f
      }
    }.getOrDefault(0f)
    val resolver = context.contentResolver
    val highText = runCatching { Settings.Secure.getInt(resolver, HIGH_TEXT_CONTRAST_KEY, 0) != 0 }.getOrDefault(false)
    val reduce = REDUCE_TRANSPARENCY_KEYS.any { key ->
      runCatching { Settings.Secure.getInt(resolver, key, 0) != 0 }.getOrDefault(false) ||
        runCatching { Settings.Global.getInt(resolver, key, 0) != 0 }.getOrDefault(false) ||
        runCatching { Settings.System.getInt(resolver, key, 0) != 0 }.getOrDefault(false)
    }
    return fromValues(contrast, highText, reduce)
  }

  /**
   * Chiama [onChanged] quando una delle impostazioni cambia (osservatori sulle chiavi e ascoltatore del contrasto). Restituisce
   * la funzione che li toglie. Niente rete, niente file: solo letture del sistema.
   */
  fun observe(context: Context, onChanged: () -> Unit): () -> Unit {
    val resolver = context.contentResolver
    val handler = Handler(Looper.getMainLooper())
    val observer = object : ContentObserver(handler) {
      override fun onChange(selfChange: Boolean) = onChanged()
    }
    val uris: List<Uri> = (REDUCE_TRANSPARENCY_KEYS + HIGH_TEXT_CONTRAST_KEY).flatMap { key ->
      listOf(Settings.Secure.getUriFor(key), Settings.Global.getUriFor(key), Settings.System.getUriFor(key))
    }
    uris.forEach { uri -> runCatching { resolver.registerContentObserver(uri, false, observer) } }

    var removeContrast: (() -> Unit)? = null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      runCatching {
        val manager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (manager != null) {
          val listener = UiModeManager.ContrastChangeListener { onChanged() }
          manager.addContrastChangeListener(context.mainExecutor, listener)
          removeContrast = { runCatching { manager.removeContrastChangeListener(listener) } }
        }
      }
    }
    return {
      runCatching { resolver.unregisterContentObserver(observer) }
      removeContrast?.invoke()
    }
  }
}

/** Le preferenze del sistema, che si aggiornano da sole quando l'utente cambia l'impostazione. */
@Composable
fun rememberGalleryAccessibilityPrefs(): GalleryAccessibilityPrefs {
  val context = LocalContext.current
  var prefs by remember(context) { mutableStateOf(GalleryAccessibilityReader.read(context)) }
  DisposableEffect(context) {
    val stop = GalleryAccessibilityReader.observe(context) { prefs = GalleryAccessibilityReader.read(context) }
    onDispose { stop() }
  }
  return prefs
}
