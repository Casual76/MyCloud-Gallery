package dev.pampa.mycloudgallery.core.designsystem.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

// LE CLASSI DI FINESTRA E I PANNELLI (Fase 10, P0 firme, P5 implementazione; docs/fase10-progetto.md §1.2 "core-designsystem/adaptive", §1.5, §2.3, D12, D13).
// **File nuovi**: nessun componente esistente cambia (il filone grafico non e' toccato). Le schermate che oggi misurano da se' (Raccolte,
// viewer, Onboarding, Cartelle NAS) passano a [rememberGalleryWindow] con P5, senza cambiare soglia (840). Nessuna dipendenza nuova: niente
// `material3-adaptive` ne' `androidx.window`.

/** La larghezza **della finestra** (non dello schermo), con le soglie di D12. */
enum class GalleryWidth(val minDp: Int) {
  /** < 600 dp: il telefono, una finestra stretta in DeX. */
  COMPACT(0),

  /** 600-839 dp. */
  MEDIUM(600),

  /** 840-1199 dp: elenco e dettaglio, mappa e griglia della zona. */
  EXPANDED(840),

  /** >= 1200 dp: anche Libreria e viewer affiancati, a scelta ("Affianca"). */
  LARGE(1200),
  ;

  companion object {
    fun of(widthDp: Int): GalleryWidth = entries.last { widthDp >= it.minDp }
  }
}

/** Il puntatore della finestra (D15): il tocco, o un mouse (DeX, tastiera e mouse sul tablet). */
enum class PointerKind { TOUCH, MOUSE }

/**
 * La finestra come la vedono le schermate (D12): classe di larghezza, misure in dp, altezza bassa (< [SHORT_HEIGHT_DP]: un telefono in
 * orizzontale), il puntatore. Si legge con [rememberGalleryWindow]; nei test si impone con [ProvideGalleryWindow] (§5.2 `FakeWindow`).
 */
@Immutable
data class GalleryWindow(
  val width: GalleryWidth,
  val widthDp: Int,
  val heightDp: Int,
  val shortHeight: Boolean,
  val pointer: PointerKind,
) {
  companion object {
    const val SHORT_HEIGHT_DP: Int = 480

    /** La finestra minima di D15 (sotto, Android non scende): 320 x 400 dp. */
    const val MIN_WIDTH_DP: Int = 320
    const val MIN_HEIGHT_DP: Int = 400

    fun of(widthDp: Int, heightDp: Int, pointer: PointerKind = PointerKind.TOUCH): GalleryWindow =
      GalleryWindow(GalleryWidth.of(widthDp), widthDp, heightDp, heightDp < SHORT_HEIGHT_DP, pointer)
  }

  /** La stessa finestra con un'altra larghezza: la finestra **vista da un pannello** (P5), che e' piu' stretto di quella dell'app. */
  fun withWidthDp(widthDp: Int): GalleryWindow = of(widthDp, heightDp, pointer)
}

/**
 * I pannelli di una schermata (§1.5 `PanesState`, D13): uno solo; elenco e dettaglio con la frazione dell'elenco; griglia "agganciata" a
 * fianco del viewer o della mappa con la frazione della griglia. Le proporzioni le decide la fase grafica; qui solo lo stato.
 */
sealed interface GalleryPanes {
  data object Single : GalleryPanes

  data class ListDetail(val listFraction: Float) : GalleryPanes {
    init {
      require(listFraction > 0f && listFraction < 1f) { "Frazione non valida." }
    }
  }

  data class Docked(val gridFraction: Float) : GalleryPanes {
    init {
      require(gridFraction > 0f && gridFraction < 1f) { "Frazione non valida." }
    }
  }
}

/** La finestra imposta da un test o da un'anteprima; null = si legge quella vera. */
private val LocalGalleryWindowOverride = compositionLocalOf<GalleryWindow?> { null }

/** La finestra di un pannello (P5): vale per le schermate dentro il pannello; null = nessun pannello. */
private val LocalPaneWindow = compositionLocalOf<GalleryWindow?> { null }

/** Impone [window] a [content] (test sui nodi e screenshot a 360, 600, 840, 1280 dp; §5.1). */
@Composable
fun ProvideGalleryWindow(window: GalleryWindow, content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalGalleryWindowOverride provides window, content = content)
}

/**
 * La finestra **di un pannello** (Fase 10, P5, D13): quando l'app divide la finestra in due (Libreria e viewer affiancati) ogni pannello e' una finestra piu' stretta, e le schermate
 * dentro leggono la sua larghezza, non quella dell'app: il viewer in 60% di 1280 dp e' una finestra da 768 dp (informazioni in un foglio, non in colonna). [window] null = nessun pannello,
 * si legge la finestra vera; il posto nell'albero e' sempre lo stesso, cosi' passare da un pannello a due non ricrea niente.
 */
@Composable
fun ProvidePaneWindow(window: GalleryWindow?, content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalPaneWindow provides window, content = content)
}

/**
 * La finestra di adesso, da `LocalWindowInfo.containerSize` (la finestra, non lo schermo): cambia senza ricreare la pagina quando una finestra
 * libera attraversa una soglia (E-T2). Il puntatore e' [PointerKind.MOUSE] quando c'e' un mouse collegato (DeX, tablet con il mouse: D15), altrimenti il tocco.
 * Dentro un pannello ([ProvidePaneWindow]) e' la finestra del pannello.
 */
@Composable
fun rememberGalleryWindow(): GalleryWindow {
  LocalPaneWindow.current?.let { return it }
  return rememberAppWindow()
}

/**
 * La finestra **dell'app**, anche dentro un pannello: la regola di chi decide per l'intera finestra, non per il pannello in cui si trova (per esempio "Apri in una nuova finestra", che si offre da una
 * finestra estesa anche quando il viewer affiancato ne occupa solo il 60%). Nei test si impone con [ProvideGalleryWindow].
 */
@Composable
fun rememberAppWindow(): GalleryWindow {
  LocalGalleryWindowOverride.current?.let { return it }
  val size = LocalWindowInfo.current.containerSize
  val density = LocalDensity.current
  val pointer = rememberPointerKind()
  return remember(size, density, pointer) {
    with(density) { GalleryWindow.of(size.width.toDp().value.toInt(), size.height.toDp().value.toInt(), pointer) }
  }
}
