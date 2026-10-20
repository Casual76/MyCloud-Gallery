package dev.pampa.mycloudgallery.core.designsystem.state

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Dove finisce una foto che l'app manda nel cestino del telefono (`MediaStore.createTrashRequest`) e da dove si ripristina (D-3 delle prove
 * sul campo, `docs/prove-campo-esiti.md`).
 *
 * - [ANDROID]: Android "puro". La riga resta nel MediaStore con lo stesso `_ID` e `IS_TRASHED = 1`: l'app la mostra in "Eliminati di recente"
 *   e la ripristina con il dialogo di sistema.
 * - [GALLERY]: One UI. Il sistema sposta il file nel Cestino di Galleria (`Android/.Trash/com.sec.android.gallery3d`, con `.nomedia`): la
 *   riga non e' piu' ne' nella sua cartella ne' fra le cestinate, l'app non la vede e non la puo' ripristinare. Si ripristina da Galleria →
 *   Cestino; tornata al suo posto (sull'S25 con lo stesso `_ID`), la lettura del telefono la ricollega al suo media dalle impronte (E-P11),
 *   senza ricaricarla.
 *
 * Le schermate scelgono le parole da qui: mai promettere "Eliminati di recente" o un "Ripristina" che su quel telefono non c'e'. Lo fornisce
 * `MainActivity` ([LocalPhoneTrash]); il valore di partenza e' quello di Android.
 */
enum class PhoneTrash {
  ANDROID,
  GALLERY,
  ;

  companion object {
    /** Dal costruttore del telefono (`Build.MANUFACTURER`): su Samsung il cestino di sistema e' quello di Galleria. */
    fun of(manufacturer: String?): PhoneTrash = if (manufacturer.equals(SAMSUNG, ignoreCase = true)) GALLERY else ANDROID

    private const val SAMSUNG = "samsung"
  }
}

/** Il cestino del telefono per le schermate (D-3): lo fornisce `MainActivity`, nei test e nelle anteprime vale [PhoneTrash.ANDROID]. */
val LocalPhoneTrash = staticCompositionLocalOf { PhoneTrash.ANDROID }

/** La frase giusta per il cestino di questo telefono: [android] per il cestino di sistema, [gallery] per il Cestino di Galleria (D-3). */
@Composable
@ReadOnlyComposable
@StringRes
fun phoneTrashText(@StringRes android: Int, @StringRes gallery: Int): Int = if (LocalPhoneTrash.current == PhoneTrash.GALLERY) gallery else android
