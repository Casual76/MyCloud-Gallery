package dev.pampa.mycloudgallery.feature.library.grid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassTint
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.pampa.mycloudgallery.core.designsystem.design.ContentRange
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryScrubber
import dev.pampa.mycloudgallery.core.library.contract.GridLayout
import dev.pampa.mycloudgallery.core.library.contract.RowKind
import java.time.YearMonth
import kotlin.math.max

// La griglia vista dalla chrome che le sta sopra (G1, G2, docs/grafica-progetto.md): quale sezione e' sotto la linea della
// chrome, lo scrubber disegnato fuori dalla griglia, e che cosa passa sotto un rettangolo di chrome (per il vetro adattivo).
// Niente di questo ricompone la griglia: si legge lo stato dello scorrimento e basta.

/** La sezione sotto la linea della chrome: il titolo ("Venerdì 9 ottobre", "Giugno 2023", "2024") e il luogo. */
@Immutable
data class MediaGridSection(val title: String, val place: String?)

/**
 * La sezione che sta sotto [line] (distanza dal bordo alto della griglia): e' l'intestazione agganciata, che l'etichetta
 * della chrome in alto mostra al posto di una seconda fascia di vetro. Al livello "Tutte" (senza intestazioni) e'
 * il mese della riga. Null se sotto la linea non c'e' ancora contenuto (la Libreria piu' corta dello schermo).
 *
 * Si ricompone solo quando la sezione cambia: lo scorrimento si legge in un `derivedStateOf`.
 */
@Composable
fun rememberMediaGridSection(state: MediaGridState, line: Dp): MediaGridSection? {
  // Si legge dove l'intestazione della lista e' gia' sparita (`handOverLine`): l'etichetta la sostituisce, non la ripete.
  val linePx = with(LocalDensity.current) { handOverLine(line).roundToPx() }
  val strings = rememberGridStrings()
  val today = remember(state) { state.today() }
  val key by remember(state, linePx) { derivedStateOf { sectionKeyAt(state, linePx) } }
  val k = key ?: return null
  return remember(k, strings, today) {
    val l = k.layout
    if (k.section >= 0) {
      val section = l.sections[k.section]
      MediaGridSection(GridText.sectionTitle(section, today, strings), GridText.sectionPlace(section, state.placeNames))
    } else {
      MediaGridSection(GridText.month(checkNotNull(k.month)), null)
    }
  }
}

/** Cio' che decide la sezione mostrata: il layout, la sezione (o, a "Tutte", il mese). */
private data class SectionKey(val layout: GridLayout, val section: Int, val month: YearMonth?)

private fun sectionKeyAt(state: MediaGridState, linePx: Int): SectionKey? {
  val l = state.layout ?: return null
  if (l.rowCount == 0) return null
  val v = state.viewport
  val y = GridGeometry.contentY(linePx.toFloat(), state.contentTop(l), v)
  if (y < 0f) return null
  val row = l.rowAtOffset(y.toInt().coerceAtMost(max(0, l.totalHeight - 1)))
  // A "Tutte" (senza intestazioni) conta il mese: righe dello stesso mese danno la stessa chiave.
  if (!l.spec.level.hasHeaders) return SectionKey(l, -1, state.scrubber.label(l, row))
  // Un'intestazione che comincia proprio sulla linea si vede ancora nella lista: la chrome mostra la sezione prima (nessun
  // doppione), e in cima alla Libreria nessuna.
  val section = l.sectionOfRow(row)
  if (l.rowKind(row) == RowKind.HEADER && y.toInt() <= l.rowTop(row)) return if (section > 0) SectionKey(l, section - 1, null) else null
  return SectionKey(l, section, null)
}

/**
 * Lo **scrubber** della griglia disegnato da chi la ospita, **fuori** dal fondale della pagina (G1, G2): cosi' maniglia e
 * bolla rifrangono il fondale della pagina senza esserci dentro, e la griglia non deve registrare un secondo fondale a
 * tutto schermo solo per loro. Il [modifier] dice dove corre: chi lo posa gli lascia sopra la chrome e sotto la pillola,
 * cosi' la maniglia non arriva mai sotto un altro controllo.
 */
@Composable
fun MediaGridScrubber(
  state: MediaGridState,
  backdrop: GlassBackdropState,
  modifier: Modifier = Modifier,
  tint: GlassTint? = null,
  ink: Color? = null,
) {
  val shown = state.layout ?: return
  if (shown.rowCount == 0 || shown.totalHeight <= state.viewport.contentHeight * ScrubberScreens) return
  val strings = rememberGridStrings()
  val month by remember(state, shown) { derivedStateOf { state.scrubber.label(shown, state.rowAtCenter(shown)) } }
  GalleryScrubber(
    state = state.scrubberState,
    fraction = { state.scrollFraction() },
    label = GridText.month(month),
    contentDescription = strings.scrubber,
    onScrub = { state.scrubTo(it) },
    backdrop = backdrop,
    modifier = modifier,
    reducedMotion = LocalFluidMotionPolicy.current.reducedMotion,
    onScrubStarted = { state.userScrolled = true },
    tint = tint,
    ink = ink,
  )
}

/**
 * Le righe della griglia sotto [rect] (pixel della griglia), impacchettate in un numero: cambia solo quando sotto il
 * rettangolo entra o esce una riga, ed e' la chiave con cui il vetro adattivo ricalcola (non a ogni fotogramma).
 */
fun MediaGridState.rowsUnder(rect: Rect): Long {
  val l = layout ?: return -1L
  if (l.rowCount == 0) return -2L
  val v = viewport
  val top = contentTop(l)
  val y0 = GridGeometry.contentY(rect.top, top, v)
  val y1 = GridGeometry.contentY(rect.bottom, top, v)
  val first = if (y0 < 0f) -1 else l.rowAtOffset(y0.toInt().coerceAtMost(max(0, l.totalHeight - 1)))
  val last = if (y1 < 0f) -1 else if (y1 >= l.totalHeight) l.rowCount else l.rowAtOffset(y1.toInt())
  return (System.identityHashCode(l).toLong() shl 32) xor ((first + 1).toLong() shl 16) xor (last + 1).toLong()
}

/**
 * Che cosa passa sotto [rect] (pixel della griglia): per ogni cella che lo tocca, i colori che il suo ThumbHash dice
 * (il medio e gli estremi della luminanza), piu' [page] per cio' che sta fuori dalla lista, e [page] con
 * [text] dove ci sono scritte (le intestazioni, il riepilogo in fondo): anche il testo della pagina passa sotto il vetro.
 * Una cella senza ThumbHash conta come il caso peggiore (nero e bianco). Lavoro di CPU, poche celle per volta, nessuna
 * lettura di pixel dalla GPU.
 */
fun MediaGridState.contentUnder(rect: Rect, page: Color, text: Color): ContentRange {
  val l = layout ?: return ContentRange(listOf(page))
  if (l.rowCount == 0) return ContentRange(listOf(page))
  val v = viewport
  val top = contentTop(l)
  val y0 = GridGeometry.contentY(rect.top, top, v)
  val y1 = GridGeometry.contentY(rect.bottom, top, v)
  val samples = LinkedHashSet<Color>()
  if (y0 < 0f) samples += page
  if (y1 >= l.totalHeight) {
    samples += page
    samples += text
  }
  if (y1 > 0f && y0 < l.totalHeight) {
    var row = l.rowAtOffset(y0.coerceAtLeast(0f).toInt().coerceAtMost(max(0, l.totalHeight - 1)))
    val snapshot = shownView.snapshot
    while (row < l.rowCount && l.rowTop(row) < y1) {
      if (l.rowKind(row) == RowKind.HEADER) {
        samples += page
        samples += text
      } else {
        // Le fessure fra le celle (1-2 dp, del colore della pagina) non contano: sotto la sfocatura spariscono, e
        // contarle tirava sempre la polarita' verso il tema.
        val first = l.firstItemIn(row)
        for (position in first until first + l.itemCountIn(row)) {
          val left = l.itemLeft(position).toFloat()
          if (left + l.itemWidth(position) < rect.left || left > rect.right) continue
          val hash = snapshot.thumbHashAt(snapshotIndexAt(l, position))?.bytes()
          if (hash == null) {
            samples += UnknownDark
            samples += UnknownLight
          } else {
            samples.addAll(thumbHashTones(hash))
          }
        }
      }
      row++
    }
  }
  // Sotto il rettangolo solo fessure o il vuoto di una riga incompleta: c'e' la pagina.
  if (samples.isEmpty()) samples += page
  return ContentRange(samples.toList())
}

/**
 * I toni di un ThumbHash senza decodificarlo: dall'intestazione il colore medio (L, P, Q) e l'ampiezza della luminanza,
 * quindi il medio, il piu' scuro e il piu' chiaro plausibili. Il formato e' quello pubblico di ThumbHash: L in 6 bit,
 * P e Q in 6 bit centrati, la scala di L in 5 bit; RGB da LPQ con b = l - 2/3 p, r = (3l - b + q) / 2, g = r - q.
 */
internal fun thumbHashTones(hash: ByteArray): List<Color> {
  if (hash.size < 5) return listOf(UnknownDark, UnknownLight)
  val header = (hash[0].toInt() and 0xFF) or ((hash[1].toInt() and 0xFF) shl 8) or ((hash[2].toInt() and 0xFF) shl 16)
  val l = (header and 63) / 63f
  val p = ((header shr 6) and 63) / 31.5f - 1f
  val q = ((header shr 12) and 63) / 31.5f - 1f
  val spread = ((header shr 18) and 31) / 31f
  return listOf(lpq(l, p, q), lpq(l - spread, p, q), lpq(l + spread, p, q))
}

private fun lpq(l: Float, p: Float, q: Float): Color {
  val lc = l.coerceIn(0f, 1f)
  val b = lc - 2f / 3f * p
  val r = (3f * lc - b + q) / 2f
  val g = r - q
  return Color(red = r.coerceIn(0f, 1f), green = g.coerceIn(0f, 1f), blue = b.coerceIn(0f, 1f))
}

/** Gli estremi per una cella di cui non si sa niente: il caso peggiore e' il nero e il bianco pieni. */
private val UnknownDark = Color(red = 0f, green = 0f, blue = 0f)
private val UnknownLight = Color(red = 1f, green = 1f, blue = 1f)

/** Lo scrubber serve solo a una Libreria lunga: almeno tre schermate. */
private const val ScrubberScreens = 3
