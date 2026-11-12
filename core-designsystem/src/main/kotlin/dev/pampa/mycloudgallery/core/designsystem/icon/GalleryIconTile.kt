package dev.pampa.mycloudgallery.core.designsystem.icon

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.theme.AccentPreset
import dev.antigravity.fluidengine.ui.theme.FluidToneTileSize
import dev.pampa.mycloudgallery.core.designsystem.R
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryAccent
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryOnPhoto

// LE ICONE ESPRESSIVE (I3, fase I, 11/11). L'utente: «le icone sono troppo poco espressive»; il suo metro: «se non sapessi leggere, potrei
// usare l'app?». Una riga delle Impostazioni con il glifo grigio su grigio si distingue dalla vicina solo leggendo.
//
// La piastrella di questo file e' **una** per tutta l'app (Impostazioni, Backup, Raccolte, fogli): la stessa forma continua della piastrella
// delle righe dell'engine ([FluidToneTileSize], raggio che scala col lato), un **colore pieno per famiglia** in una sfumatura leggera (in alto a
// sinistra il colore, in basso a destra un poco piu' profondo, come le copertine delle Raccolte) e il glifo **bianco**. Il colore dice la
// famiglia (foto, backup, persone, ricordi, luoghi, ricerca, sicurezza, rete, sistema, pericolo), la forma del glifo dice la cosa.
//
// **Da dove vengono i colori.** Non sono scritti qui: sono i colori che l'engine e il marchio gia' danno all'utente da scegliere come accento
// (l'Ametista e i suoi poli, i preset Fluid «Blu», «Arancio», «Indaco», «Verde»), piu' due ruoli dello schema (il grafite e il grigio, che
// seguono il tema). Una famiglia non segue l'accento scelto: come in un'impostazione di sistema, la sicurezza resta grafite e il backup resta
// blu anche con Material You, cosi' l'occhio la ritrova sempre nello stesso posto.
//
// **Contrasto.** Il glifo bianco sta sopra un colore pieno: ogni colore si scurisce quanto basta perche' il bianco regga almeno 3:1 (la soglia
// delle icone che accompagnano un'etichetta) sul punto piu' chiaro della sfumatura, in chiaro e in scuro. Lo verifica `GalleryIconTileTest`.
//
// **Costo.** Una piastrella e' due rettangoli e un vettore: niente stato letto per fotogramma, niente vetro. I colori si risolvono una volta per
// schema ([rememberGalleryIconColors]).

/**
 * La famiglia di una voce: il colore della sua piastrella. Pochi colori, ognuno con un significato: dove due voci sono della stessa famiglia
 * (il backup e il NAS) hanno lo stesso colore e un glifo diverso.
 */
enum class GalleryIconFamily {
  /** Le foto e la Libreria: l'Ametista, il colore dell'app. */
  Photos,

  /** Il backup, il NAS, la nuvola: blu. */
  Backup,

  /** Le persone e la famiglia: arancio. */
  People,

  /** I ricordi e i viaggi: il rosa del polo terziario del marchio. */
  Memories,

  /** I luoghi e la mappa: verde. */
  Places,

  /** La ricerca e l'IA: indaco. */
  Search,

  /** La sicurezza, le chiavi, i posti protetti: grafite (come i posti protetti delle Raccolte). */
  Security,

  /** La rete, il Wi-Fi, la VPN: verde acqua (fra il blu e il verde). */
  Network,

  /** Il sistema: informazioni, widget, avanzate. Grigio. */
  System,

  /** Cio' che cancella o rompe: il rosso dell'errore. */
  Danger,
}

/**
 * Lo stato facoltativo di una piastrella: un segno piccolo nell'angolo in basso a destra, con il suo colore e la sua forma (mai solo il colore).
 * Il lettore dello schermo lo dice con [label].
 */
enum class GalleryIconStatus {
  /** Fatto, al sicuro: la spunta su verde. */
  Done,

  /** In corso: le frecce che girano su blu. */
  Running,

  /** In pausa, ripartira' quando la si riprende (o tolta la recinzione): le due barre su grigio. */
  Paused,

  /** In attesa, riparte da sola (la coda, il NAS che torna, le condizioni giuste): la clessidra su grigio. Mai le due barre per l'attesa (CU-33). */
  Waiting,

  /** Fermo, spento: il trattino su grafite. */
  Stopped,

  /** Attenzione, serve uno sguardo: il punto esclamativo su arancio. */
  Attention,

  /** Un problema: la croce su rosso. */
  Problem,
}

/** Le taglie della piastrella. Il raggio, il glifo e il segno di stato scalano con il lato. */
object GalleryIconTileDefaults {

  /** Una riga delle Impostazioni o di un elenco: il lato della piastrella delle righe dell'engine. */
  val Row: Dp = FluidToneTileSize

  /** Una riga piu' alta (le Raccolte), la testata di un foglio di conferma. */
  val Medium: Dp = 44.dp

  /** Una piastrella grande: la testata di una pagina, una scheda delle Raccolte. */
  val Large: Dp = 64.dp

  /** Il glifo occupa questa parte del lato (19 dp su 32, come nella piastrella delle righe dell'engine). */
  const val GlyphFraction: Float = 0.6f

  /** Il segno di stato e' questa parte del lato, fra [MarkMin] e [MarkMax]. */
  const val MarkFraction: Float = 0.42f

  /** Il segno di stato non scende sotto (si deve riconoscere la forma). */
  val MarkMin: Dp = 14.dp

  /** ...e non sale sopra (su una piastrella grande sarebbe un secondo protagonista). */
  val MarkMax: Dp = 22.dp

  /** Il filo che stacca il segno dalla piastrella: del colore del fondo su cui sta la piastrella. */
  val MarkRing: Dp = 2.dp

  /** Il glifo dentro il segno, rispetto al segno. */
  const val MarkGlyphFraction: Float = 0.68f
}

/** I colori di una piastrella: la sfumatura ([top] in alto a sinistra, [bottom] in basso a destra) e il glifo. */
@Immutable
data class GalleryIconColors(val top: Color, val bottom: Color, val glyph: Color)

/** La forma della piastrella di lato [side]: il raggio scala con il lato, come in `FluidToneTile` e nelle copertine delle Raccolte. */
fun galleryIconTileShape(side: Dp): Shape = ContinuousCornerShape(FluidRadius.Small * (side / FluidToneTileSize))

/**
 * La piastrella con un'icona: il colore della [family], il glifo [icon] bianco, il segno [status] facoltativo nell'angolo.
 *
 * @param contentDescription cio' che la piastrella dice da sola, quando non c'e' un testo accanto; null in una riga con il titolo (la piastrella
 *   e' decorativa, il segno di stato invece si legge comunque).
 * @param statusDescription cio' che il lettore dello schermo dice del segno; di serie l'etichetta dello stato ([GalleryIconStatus.label]).
 * @param ring il colore del filo intorno al segno: il fondo su cui sta la piastrella (di serie quello dei gruppi di righe dell'engine).
 */
@Composable
fun GalleryIconTile(
  icon: ImageVector,
  family: GalleryIconFamily,
  modifier: Modifier = Modifier,
  size: Dp = GalleryIconTileDefaults.Row,
  status: GalleryIconStatus? = null,
  contentDescription: String? = null,
  statusDescription: String? = null,
  ring: Color = MaterialTheme.colorScheme.surfaceContainerLow,
) {
  val colors = rememberGalleryIconColors(family)
  GalleryIconTileFrame(
    colors = colors,
    modifier = modifier,
    size = size,
    status = status,
    contentDescription = contentDescription,
    statusDescription = statusDescription,
    ring = ring,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = colors.glyph,
      modifier = Modifier.align(Alignment.Center).size(size * GalleryIconTileDefaults.GlyphFraction),
    )
  }
}

/** La piastrella di una voce del vocabolario ([GalleryIcons]): l'icona e la famiglia insieme, cosi' la stessa cosa ha lo stesso segno ovunque. */
@Composable
fun GalleryIconTile(
  spec: GalleryIconSpec,
  modifier: Modifier = Modifier,
  size: Dp = GalleryIconTileDefaults.Row,
  status: GalleryIconStatus? = null,
  contentDescription: String? = null,
  statusDescription: String? = null,
  ring: Color = MaterialTheme.colorScheme.surfaceContainerLow,
) {
  GalleryIconTile(
    icon = spec.icon,
    family = spec.family,
    modifier = modifier,
    size = size,
    status = status,
    contentDescription = contentDescription,
    statusDescription = statusDescription,
    ring = ring,
  )
}

/**
 * La piastrella con un contenuto suo al posto del glifo (una copertina, una miniatura): la stessa forma, il colore della [family] sotto (si vede
 * finche' la foto non c'e', e non salta quando arriva) e il segno [status] nell'angolo. Il [content] e' ritagliato alla forma.
 */
@Composable
fun GalleryIconTile(
  family: GalleryIconFamily,
  modifier: Modifier = Modifier,
  size: Dp = GalleryIconTileDefaults.Row,
  status: GalleryIconStatus? = null,
  contentDescription: String? = null,
  statusDescription: String? = null,
  ring: Color = MaterialTheme.colorScheme.surfaceContainerLow,
  content: @Composable BoxScope.() -> Unit,
) {
  GalleryIconTileFrame(
    colors = rememberGalleryIconColors(family),
    modifier = modifier,
    size = size,
    status = status,
    contentDescription = contentDescription,
    statusDescription = statusDescription,
    ring = ring,
    content = content,
  )
}

@Composable
private fun GalleryIconTileFrame(
  colors: GalleryIconColors,
  modifier: Modifier,
  size: Dp,
  status: GalleryIconStatus?,
  contentDescription: String?,
  statusDescription: String?,
  ring: Color,
  content: @Composable BoxScope.() -> Unit,
) {
  val shape = galleryIconTileShape(size)
  Box(
    modifier = modifier
      .size(size)
      .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
  ) {
    Box(
      modifier = Modifier.matchParentSize().clip(shape).background(Brush.linearGradient(listOf(colors.top, colors.bottom))),
      content = content,
    )
    if (status != null) {
      val mark = galleryStatusMarkSize(size)
      GalleryStatusMark(
        status = status,
        size = mark,
        ring = ring,
        contentDescription = statusDescription ?: status.label(),
        // Il segno sporge dall'angolo di un terzo del suo raggio: si legge come appoggiato sulla piastrella, non dentro.
        modifier = Modifier.align(Alignment.BottomEnd).offset(x = mark / MarkOverhang, y = mark / MarkOverhang),
      )
    }
  }
}

/** Il lato del segno di stato su una piastrella di lato [tile]. */
fun galleryStatusMarkSize(tile: Dp): Dp =
  min(GalleryIconTileDefaults.MarkMax, max(GalleryIconTileDefaults.MarkMin, tile * GalleryIconTileDefaults.MarkFraction))

/**
 * Il segno di uno stato da solo: un tondo del colore dello stato con il suo glifo bianco e un filo del colore del fondo. Si appoggia su una
 * piastrella ([GalleryIconTile] lo mette da solo), su una copertina o in fondo a una riga.
 *
 * @param contentDescription cio' che il lettore dello schermo dice; null = decorativo (lo stato e' gia' detto altrove).
 * @param ring il colore del filo; [Color.Transparent] per un segno che non sta sopra niente.
 */
@Composable
fun GalleryStatusMark(
  status: GalleryIconStatus,
  modifier: Modifier = Modifier,
  size: Dp = GalleryIconTileDefaults.MarkMin,
  ring: Color = MaterialTheme.colorScheme.surfaceContainerLow,
  contentDescription: String? = status.label(),
) {
  val colors = rememberGalleryStatusColors(status)
  val shape = ContinuousCornerShape(size / 2)
  Box(
    modifier = modifier
      .size(size)
      .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
      .clip(shape)
      .background(colors.top)
      .border(GalleryIconTileDefaults.MarkRing, ring, shape),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = status.glyph(),
      contentDescription = null,
      tint = colors.glyph,
      modifier = Modifier.size(size * GalleryIconTileDefaults.MarkGlyphFraction),
    )
  }
}

/** Il glifo del segno di uno stato: la forma dice lo stato anche a chi non distingue i colori. */
fun GalleryIconStatus.glyph(): ImageVector = when (this) {
  GalleryIconStatus.Done -> Icons.Rounded.Check
  GalleryIconStatus.Running -> Icons.Rounded.Sync
  GalleryIconStatus.Paused -> Icons.Rounded.Pause
  GalleryIconStatus.Waiting -> Icons.Rounded.HourglassTop
  GalleryIconStatus.Stopped -> Icons.Rounded.Remove
  GalleryIconStatus.Attention -> Icons.Rounded.PriorityHigh
  GalleryIconStatus.Problem -> Icons.Rounded.Close
}

/** L'etichetta corta di uno stato, per il lettore dello schermo (e per chi la vuole scrivere accanto). */
@Composable
fun GalleryIconStatus.label(): String = stringResource(
  when (this) {
    GalleryIconStatus.Done -> R.string.icon_status_done
    GalleryIconStatus.Running -> R.string.icon_status_running
    GalleryIconStatus.Paused -> R.string.icon_status_paused
    GalleryIconStatus.Waiting -> R.string.icon_status_waiting
    GalleryIconStatus.Stopped -> R.string.icon_status_stopped
    GalleryIconStatus.Attention -> R.string.icon_status_attention
    GalleryIconStatus.Problem -> R.string.icon_status_problem
  },
)

/** I colori di [family] nel tema di adesso, risolti una volta per schema. */
@Composable
fun rememberGalleryIconColors(family: GalleryIconFamily): GalleryIconColors {
  val scheme = MaterialTheme.colorScheme
  val dark = GlassDefaults.isDarkSurface()
  return remember(family, scheme, dark) { GalleryIconPalette.colors(family, scheme, dark) }
}

/** I colori del segno di [status] nel tema di adesso. */
@Composable
fun rememberGalleryStatusColors(status: GalleryIconStatus): GalleryIconColors {
  val scheme = MaterialTheme.colorScheme
  val dark = GlassDefaults.isDarkSurface()
  return remember(status, scheme, dark) { GalleryIconPalette.status(status, scheme, dark) }
}

/**
 * La tavolozza delle famiglie, come funzioni pure: si verifica il contrasto di ogni coppia senza comporre (`GalleryIconTileTest`), e chi ha bisogno
 * del colore di una famiglia fuori da una piastrella (un anello, un segmento) lo prende da qui.
 */
object GalleryIconPalette {

  /** Il punto piu' chiaro di una sfumatura sta sotto questa luminanza: il bianco ci regge 3:1 ((1 + 0,05) / (0,30 + 0,05) = 3). */
  const val MaxTileLuminance: Float = 0.30f

  /** Quanto si scurisce l'angolo in basso della sfumatura: abbastanza per dare profondita', poco per non cambiare colore. */
  const val Depth: Float = 0.16f

  /** Il colore pieno di [family] (prima del contrasto): dai preset dell'engine, dal marchio e da due ruoli dello schema. */
  fun hue(family: GalleryIconFamily, scheme: ColorScheme, dark: Boolean): Color = when (family) {
    GalleryIconFamily.Photos -> GalleryAccent.Amethyst.resolve(dark)
    GalleryIconFamily.Backup -> preset(BLUE).resolve(dark)
    GalleryIconFamily.People -> preset(ORANGE).resolve(dark)
    GalleryIconFamily.Memories -> GalleryAccent.Amethyst.poles?.let { if (dark) it.tertiaryDark else it.tertiaryLight } ?: scheme.tertiary
    GalleryIconFamily.Places -> preset(GREEN).resolve(dark)
    GalleryIconFamily.Search -> preset(INDIGO).resolve(dark)
    GalleryIconFamily.Network -> lerp(preset(BLUE).resolve(dark), preset(GREEN).resolve(dark), NETWORK_MIX)
    // Il grafite: scuro su chiaro; in scuro un grigio caldo poco piu' chiaro dei gruppi, che non diventi la cosa piu' luminosa della pagina.
    GalleryIconFamily.Security -> if (dark) lerp(scheme.surfaceContainerHighest, scheme.onSurface, GRAPHITE_LIFT) else scheme.inverseSurface
    // Il grigio del sistema: il bordo del tema chiaro (scurito per il glifo diventa il grigio delle impostazioni di sistema); in scuro il bordo e' quasi il
    // grafite della sicurezza, quindi il testo secondario, che scurito per il glifo e' lo stesso grigio medio.
    GalleryIconFamily.System -> if (dark) scheme.onSurfaceVariant else scheme.outline
    // Il rosso dell'errore anche in scuro (il contenitore dell'errore, in scuro, e' un bruno spento): lo scurimento lo porta dove il bianco regge.
    GalleryIconFamily.Danger -> scheme.error
  }

  /** I colori della piastrella di [family]: il colore pieno scurito quanto serve al glifo bianco, e la sua sfumatura. */
  fun colors(family: GalleryIconFamily, scheme: ColorScheme, dark: Boolean): GalleryIconColors = tile(hue(family, scheme, dark))

  /** I colori del segno di [status]. */
  fun status(status: GalleryIconStatus, scheme: ColorScheme, dark: Boolean): GalleryIconColors = when (status) {
    GalleryIconStatus.Done -> tile(preset(GREEN).resolve(dark))
    GalleryIconStatus.Running -> tile(preset(BLUE).resolve(dark))
    GalleryIconStatus.Paused, GalleryIconStatus.Waiting -> colors(GalleryIconFamily.System, scheme, dark)
    GalleryIconStatus.Stopped -> colors(GalleryIconFamily.Security, scheme, dark)
    GalleryIconStatus.Attention -> tile(preset(ORANGE).resolve(dark))
    GalleryIconStatus.Problem -> colors(GalleryIconFamily.Danger, scheme, dark)
  }

  /** La sfumatura di un colore pieno con il glifo bianco sopra. */
  fun tile(hue: Color): GalleryIconColors {
    val top = deepen(hue)
    return GalleryIconColors(top = top, bottom = shade(top, Depth), glyph = TileGlyph)
  }

  /**
   * [color] scurito (verso il nero, nella stessa tinta) finche' la sua luminanza scende sotto [MaxTileLuminance]: il blu e l'Ametista restano
   * come sono, l'arancio e il verde (chiari per natura) diventano quelli piu' profondi che il bianco regge. Una ricerca a meta' di pochi passi.
   */
  fun deepen(color: Color): Color {
    if (color.luminance() <= MaxTileLuminance) return color
    var low = 0f
    var high = 1f
    repeat(DEEPEN_STEPS) {
      val mid = (low + high) / 2
      if (shade(color, mid).luminance() <= MaxTileLuminance) high = mid else low = mid
    }
    return shade(color, high)
  }

  /** [color] con i canali ridotti di [amount] (0 = com'e', 1 = nero): la stessa tinta, piu' profonda. */
  fun shade(color: Color, amount: Float): Color {
    val keep = 1f - amount.coerceIn(0f, 1f)
    return color.copy(red = color.red * keep, green = color.green * keep, blue = color.blue * keep)
  }

  /** Il glifo sopra un colore pieno: il bianco, come sopra una foto (un colore pieno non segue il tema, come una foto). */
  val TileGlyph: Color get() = GalleryOnPhoto.Content

  private fun preset(name: String): AccentPreset = GalleryAccent.presets.first { it.name == name }

  private const val BLUE = "fluid"
  private const val ORANGE = "ember"
  private const val INDIGO = "ocean"
  private const val GREEN = "jade"

  /** Il verde acqua della rete: a meta' fra il blu e il verde. */
  private const val NETWORK_MIX = 0.5f

  /** Quanto il grafite del tema scuro sale verso il testo. */
  private const val GRAPHITE_LIFT = 0.18f

  private const val DEEPEN_STEPS = 12
}

/** Il segno sporge oltre l'angolo di un terzo del suo lato. */
private const val MarkOverhang = 3
