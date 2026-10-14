package dev.pampa.mycloudgallery.feature.library.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.pampa.mycloudgallery.feature.library.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Le direzioni grafiche della Libreria (G1, `docs/grafica-progetto.md`). **Solo debug** finche' l'utente non sceglie: si
 * cambiano dal menu della vista ("Prototipo grafico") o da `adb` scrivendo [marker] nel file [DesignMarkerFile]; in
 * release le manopole di debug non esistono e la Libreria e' sempre [Current].
 *
 *  - [Current]: la Libreria della Fase 3, invariata;
 *  - [A] "sopra le foto": la chrome di un visore, sfumatura scura e testo bianco in alto, parete di foto squadrata;
 *  - [B] "barra solida": barra e fascia in basso opache, la data agganciata nella barra, nessun vetro sopra il testo;
 *  - [C] "vetro adattivo": capsule di vetro con pellicola e inchiostro scelti dal contenuto che passa sotto.
 */
enum class LibraryDesign(val marker: String) {
  Current("attuale"),
  A("A"),
  B("B"),
  C("C"),
  ;

  /** Il nome nel menu di debug. */
  @Composable
  fun label(): String = stringResource(
    R.string.library_debug_design,
    stringResource(
      when (this) {
        Current -> R.string.library_design_current
        A -> R.string.library_design_a
        B -> R.string.library_design_b
        C -> R.string.library_design_c
      },
    ),
  )

  companion object {
    /** La direzione scritta da `adb` (lettera o "attuale"), o null se il testo non ne nomina una. */
    fun fromMarker(text: String?): LibraryDesign? = text?.trim()?.let { t -> entries.firstOrNull { it.marker.equals(t, ignoreCase = true) } }
  }
}

/** Il file con cui `adb` sceglie il prototipo grafico in debug (G1): una lettera, `A`, `B`, `C`, o `attuale`. */
const val DesignMarkerFile: String = "g1-design"

/**
 * Solo debug (G1): il prototipo grafico si legge dal file [DesignMarkerFile] nella cartella dei file esterni dell'app e,
 * scegliendolo dal menu, si scrive li' (come la dimensione della Libreria sintetica, M1). Prima si legge, poi si scrive.
 */
@Composable
internal fun DebugDesignChoice(hooks: LibraryDebugHooks) {
  val context = LocalContext.current
  LaunchedEffect(hooks) {
    val file = context.getExternalFilesDir(null)?.let { File(it, DesignMarkerFile) }
    // Un file scritto da `adb` puo' non essere leggibile dall'app (proprietario `shell`): si ignora, mai un crash.
    val wanted = withContext(Dispatchers.IO) { runCatching { file?.takeIf { it.exists() }?.readText() }.getOrNull() }
    LibraryDesign.fromMarker(wanted)?.let { hooks.chooseDesign(it) }
    snapshotFlow { hooks.design }.collect { design ->
      withContext(Dispatchers.IO) { runCatching { file?.writeText(design.marker) } }
    }
  }
}
