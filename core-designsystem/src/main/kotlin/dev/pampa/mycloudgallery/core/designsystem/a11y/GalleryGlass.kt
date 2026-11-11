package dev.pampa.mycloudgallery.core.designsystem.a11y

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassEdge
import dev.antigravity.fluidengine.ui.fluid.GlassFalloff
import dev.antigravity.fluidengine.ui.fluid.GlassOptics
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.GlassTint
import dev.antigravity.fluidengine.ui.fluid.glassSurface

// Fase 10, P8 (D26, E-AC5): il vetro con l'uscita sicura. Con "piu' contrasto" o "meno trasparenza" chiesti dal sistema ogni
// pezzo di vetro della galleria diventa una **superficie piena**: niente rifrazione, niente trasparenza, la stessa sagoma e
// una linea sottile (forte con il contrasto alto) che la separa dal contenuto. Il testo sopra e' quello che il vetro
// aveva scelto per il caso peggiore: contro un fondo pieno del suo opposto il contrasto e' il massimo possibile.

/**
 * Come `glassSurface` dell'engine, ma rispetta [LocalGalleryAccessibility]: con le superfici piene chieste dal sistema non
 * disegna vetro. Va usato **al posto di** `glassSurface` in tutta la galleria (lo controlla `GalleryGlassUseTest`).
 *
 * @param exports come in `glassSurface`; con la superficie piena non si pubblica niente (non c'e' piu' vetro da rifrangere).
 * @param optics come in `glassSurface` (per esempio l'ottica dei pannelli dell'engine); con la superficie piena non conta.
 */
@Composable
fun Modifier.galleryGlass(
  state: GlassBackdropState,
  tint: GlassTint,
  shape: Shape = RectangleShape,
  edge: GlassEdge = GlassEdge.None,
  falloff: GlassFalloff = GlassFalloff.Uniform,
  role: GlassRole = if (falloff == GlassFalloff.FadeDown) GlassRole.Bar else GlassRole.Floating,
  exports: GlassBackdropState? = null,
  optics: GlassOptics = GlassDefaults.optics(role),
): Modifier {
  val prefs = LocalGalleryAccessibility.current
  return if (prefs.solidSurfaces) {
    gallerySolidSurface(tint = tint, shape = shape, edge = edge, falloff = falloff)
  } else {
    glassSurface(state = state, tint = tint, shape = shape, edge = edge, falloff = falloff, role = role, optics = optics, exports = exports)
  }
}

/**
 * La superficie piena al posto del vetro: la pellicola di ripiego del vetro ([GlassTint.fallback]) portata a opacita' piena
 * sopra il colore della pagina, la stessa sagoma, e un bordo.
 */
@Composable
fun Modifier.gallerySolidSurface(
  tint: GlassTint,
  shape: Shape = RectangleShape,
  edge: GlassEdge = GlassEdge.None,
  falloff: GlassFalloff = GlassFalloff.Uniform,
): Modifier {
  val scheme = MaterialTheme.colorScheme
  val high = LocalGalleryAccessibility.current.highContrast
  val fill = solidFill(tint, scheme)
  val line = if (high) scheme.outline else tint.hairline
  val width = if (high) HighContrastLine else HairlineLine
  val bar = shape == RectangleShape && (edge != GlassEdge.None || falloff == GlassFalloff.FadeDown)
  return if (bar) {
    // Una barra a tutta larghezza: la linea sta sul lato che incontra il contenuto (sotto, di norma; sopra se `Top`).
    background(fill, shape).drawWithContent {
      drawContent()
      val y = if (edge == GlassEdge.Top) 0f else size.height
      drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = width.toPx())
    }
  } else {
    background(fill, shape).border(width, line, shape)
  }
}

/**
 * Il fondo pieno dentro un pop-up o un foglio dell'engine (il cui vetro non si puo' cambiare da qui): con le superfici piene il
 * contenuto sta su `surface` senza alfa, e il vetro dietro non si vede. Senza, non fa niente.
 */
@Composable
fun Modifier.gallerySolidFill(): Modifier {
  val solid = LocalGalleryAccessibility.current.solidSurfaces
  val fill = MaterialTheme.colorScheme.surface.copy(alpha = 1f)
  return if (solid) background(fill) else this
}

/** Il colore pieno di una superficie al posto del vetro [tint]: la pellicola di ripiego sopra la pagina, senza alfa. */
fun solidFill(tint: GlassTint, scheme: ColorScheme): Color =
  tint.fallback.compositeOver(scheme.surface.copy(alpha = 1f)).copy(alpha = 1f)

private val HairlineLine = 1.dp
private val HighContrastLine = 2.dp
