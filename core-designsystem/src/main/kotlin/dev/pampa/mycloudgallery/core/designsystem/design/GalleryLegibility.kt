package dev.pampa.mycloudgallery.core.designsystem.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

/**
 * La leggibilita' della chrome sopra le foto, scritta come aritmetica (G1, `docs/grafica-progetto.md` §3).
 *
 * Il principio e' uno: **il contrasto si garantisce sul caso peggiore del contenuto sotto, non sul caso medio**. Una
 * pellicola di vetro sopra una foto non ha un colore: ha un colore per ogni foto che le passa sotto. Il testo scuro su
 * una pellicola chiara regge finche' sotto non passa una foto nera; quello chiaro su una pellicola scura finche' non
 * passa una foto bianca. Quindi la pellicola si sceglie *contro* il fondo piu' sfavorevole all'inchiostro, e la si
 * sceglie la meno coprente possibile: il vetro deve restare vetro.
 *
 * Funzioni pure, fuori dalla composizione: si verificano con i numeri su tutte le foto peggiori (`GalleryLegibilityTest`).
 */
object GalleryLegibility {

  /** WCAG AA per il testo normale. */
  const val TextContrast: Float = 4.5f

  /** WCAG AA per il testo grande (>= 18 sp, o 14 sp grassetto) e per le icone. */
  const val LargeContrast: Float = 3f

  /** Il rapporto di contrasto WCAG fra due colori opachi. */
  fun contrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
  }

  /** Il contrasto di [ink] (anche traslucido) sopra [film] (con la sua opacita') steso su [under] (reso opaco). */
  fun contrastOver(ink: Color, film: Color, under: Color): Float {
    val background = film.compositeOver(under.copy(alpha = 1f))
    return contrast(ink.compositeOver(background), background)
  }

  /** Il contrasto peggiore di [ink] su [film] sopra ogni campione di [range]. */
  fun worstContrast(ink: Color, film: Color, range: ContentRange): Float = range.samples.minOf { contrastOver(ink, film, it) }

  /**
   * La pellicola meno coprente fra [floor] e [ceiling] che porta [ink] almeno a [target] sul caso peggiore di [range].
   * Se nemmeno [ceiling] basta, torna [ceiling] (chi chiama lo vede da [worstContrast]). Il contrasto cresce con l'opacita'
   * quando la pellicola va verso il polo opposto all'inchiostro: una ricerca binaria trova il minimo.
   */
  fun minimalFilmAlpha(ink: Color, film: Color, range: ContentRange, target: Float, floor: Float, ceiling: Float): Float {
    if (worstContrast(ink, film.copy(alpha = floor), range) >= target) return floor
    if (worstContrast(ink, film.copy(alpha = ceiling), range) < target) return ceiling
    var low = floor
    var high = ceiling
    repeat(SearchSteps) {
      val mid = (low + high) / 2f
      if (worstContrast(ink, film.copy(alpha = mid), range) >= target) high = mid else low = mid
    }
    return high
  }

  /**
   * Il velo minimo **sotto** una pellicola fissa: [underlay] (con opacita' fra [floor] e [ceiling]) steso sul contenuto, poi
   * [topFilm] sopra (il vetro di un componente che non si puo' cambiare, come la pillola dell'engine), e [ink] sopra
   * tutto. E' la sfumatura di bordo che garantisce un fondo a un vetro altrui: la piu' leggera che porta [ink] a [target]
   * sul caso peggiore di [range].
   */
  fun minimalUnderlayAlpha(ink: Color, topFilm: Color, underlay: Color, range: ContentRange, target: Float, floor: Float, ceiling: Float): Float {
    fun worst(alpha: Float): Float = range.samples.minOf { contrastOver(ink, topFilm, underlay.copy(alpha = alpha).compositeOver(it.copy(alpha = 1f))) }
    if (worst(floor) >= target) return floor
    if (worst(ceiling) < target) return ceiling
    var low = floor
    var high = ceiling
    repeat(SearchSteps) {
      val mid = (low + high) / 2f
      if (worst(mid) >= target) high = mid else low = mid
    }
    return high
  }

  /**
   * Il vetro **adattivo** (direzione C): sceglie la polarita' (inchiostro scuro su pellicola chiara, o chiaro su scura) e
   * l'opacita' minima della pellicola per [target] sul caso peggiore di [range].
   *
   * Vince la polarita' che chiede meno pellicola, cioe' quella che lascia passare piu' foto. [previousDark] da'
   * l'isteresi: si cambia polarita' solo se l'altra costa almeno [SwitchMargin] di pellicola in meno, cosi' un bordo che
   * passa sotto una capsula non la fa lampeggiare fra chiaro e scuro.
   */
  fun adapt(range: ContentRange, palette: AdaptivePalette, target: Float = TextContrast, previousDark: Boolean? = null): AdaptiveInk {
    val darkAlpha = minimalFilmAlpha(palette.darkInk, palette.lightFilm, range, target, palette.floor, palette.ceiling)
    val lightAlpha = minimalFilmAlpha(palette.lightInk, palette.darkFilm, range, target, palette.floor, palette.ceiling)
    val dark = when (previousDark) {
      true -> !(lightAlpha + SwitchMargin <= darkAlpha)
      false -> darkAlpha + SwitchMargin <= lightAlpha
      null -> darkAlpha <= lightAlpha
    }
    return if (dark) {
      val film = palette.lightFilm.copy(alpha = darkAlpha)
      AdaptiveInk(ink = palette.darkInk, film = film, darkInk = true, contrast = worstContrast(palette.darkInk, film, range))
    } else {
      val film = palette.darkFilm.copy(alpha = lightAlpha)
      AdaptiveInk(ink = palette.lightInk, film = film, darkInk = false, contrast = worstContrast(palette.lightInk, film, range))
    }
  }

  /** Quanta pellicola in meno deve costare l'altra polarita' per cambiare (isteresi di [adapt]). */
  const val SwitchMargin: Float = 0.08f

  private const val SearchSteps = 16
}

/**
 * Cio' che passa sotto un pezzo di chrome: alcuni colori opachi rappresentativi (il piu' scuro, il piu' chiaro, i medi
 * delle celle). Per un fondo sconosciuto si passano gli estremi.
 */
@Immutable
data class ContentRange(val samples: List<Color>) {
  init {
    require(samples.isNotEmpty()) { "Un intervallo di contenuto ha almeno un campione." }
  }
}

/** I due inchiostri e le due pellicole fra cui il vetro adattivo sceglie, con i limiti dell'opacita' della pellicola. */
@Immutable
data class AdaptivePalette(
  val darkInk: Color,
  val lightInk: Color,
  val lightFilm: Color,
  val darkFilm: Color,
  /** Sotto questa opacita' la pellicola non si vede piu' come un pannello. */
  val floor: Float,
  /** Sopra questa il vetro e' un riempimento: e' il tetto, e se non basta lo si dice. */
  val ceiling: Float,
) {
  /** Il caso peggiore assoluto: sotto puo' passare il nero e il bianco pieni. */
  val unknown: ContentRange get() = ContentRange(listOf(darkFilm.copy(alpha = 1f), lightFilm.copy(alpha = 1f)))
}

/** La scelta del vetro adattivo: inchiostro, pellicola (con la sua opacita') e il contrasto peggiore che garantisce. */
@Immutable
data class AdaptiveInk(val ink: Color, val film: Color, val darkInk: Boolean, val contrast: Float)
