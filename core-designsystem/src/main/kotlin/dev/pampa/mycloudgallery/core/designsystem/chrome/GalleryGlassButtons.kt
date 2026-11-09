package dev.pampa.mycloudgallery.core.designsystem.chrome

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import dev.antigravity.fluidengine.ui.fluid.FluidContextAction
import dev.antigravity.fluidengine.ui.fluid.FluidGlassIconButton
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.currentGlassBackdrop
import dev.antigravity.fluidengine.ui.fluidphysics.FluidMorphMenuButton
import dev.antigravity.fluidengine.ui.fluidphysics.FluidMorphMenuState
import dev.pampa.mycloudgallery.core.designsystem.R
import dev.pampa.mycloudgallery.core.designsystem.a11y.LocalGalleryAccessibility
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens

// I TASTI DI VETRO DEL VIEWER (grafica beta, G3, B4). L'utente: "il tasto con 3 puntini in alto a destra applica il suo blur senza considerare la parte piu chiara dietro e il blur applicati
// alla parte superiore dello schermo, mentre il tasto per chiudere la modalita' fullscreen applica il vetro in maniera migliore".
//
// La causa: il tasto di chiusura prende il fondale dalla barra (`currentGlassBackdrop()`: la foto **e** il vetro della barra, che la barra pubblica per i suoi controlli), mentre il ⋮ riceveva dal
// viewer la sola foto (`backdrop = backdrop`) e ignorava il vetro della barra. Qui i due tasti sono lo stesso pezzo dell'engine, con il fondale che l'engine da' da solo.
//
// Solo per il viewer: `GalleryBarButton` resta il tasto del resto dell'app (24 usi), con il suo materiale e il suo bersaglio.

/**
 * Un tasto tondo di vetro sopra una foto: `FluidGlassIconButton` dell'engine (la lente da 44 dp dentro un riquadro da 48, la pressione che cresce e si accende, l'aptica), con l'inchiostro
 * della barra ([galleryBarInk]: sopra una foto l'accento non regge il contrasto) e il nome per TalkBack sull'icona, come vuole l'engine.
 *
 * @param backdrop di serie quello della barra in cui sta ([currentGlassBackdrop]): la foto e il vetro della barra. Chi lo passa sceglie un altro vetro da rifrangere.
 */
@Composable
fun GalleryGlassIconButton(
  icon: ImageVector,
  contentDescription: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  backdrop: GlassBackdropState = currentGlassBackdrop(),
) {
  // Il nodo semantico e' il riquadro da 48 dp intero (e non la lente da 44 che e' il bersaglio dentro): nome, ruolo e azione stanno li', e quelli della lente (che l'engine fa
  // coincidere con il suo bersaglio da 44) non si leggono due volte. TalkBack lo evidenzia e lo misura di 48 dp; il tocco vero resta alla lente, che lo allarga a 48 dp da se'.
  FluidGlassIconButton(
    onClick = onClick,
    modifier = modifier.clearAndSetSemantics {
      this.contentDescription = contentDescription
      role = Role.Button
      if (enabled) onClick(label = null) { onClick(); true } else disabled()
    },
    backdrop = backdrop,
    enabled = enabled,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = contentDescription,
      tint = galleryBarInk(),
      modifier = Modifier.size(GlassGlyph),
    )
  }
}

/**
 * Il tasto ⋮ del viewer: lo stesso vetro del tasto di chiusura, e un menu che si apre **dalla goccia del tasto** (`FluidMorphMenuButton(opensOnTap)`): il tasto diventa il menu e al congedo
 * torna tasto; tenendo premuto il dito passa sulle voci e la scelta si fa rilasciando. Le voci sono quelle di [entries] (un'interruzione di gruppo diventa l'inizio di una sezione).
 *
 * Il menu vive in un `FluidMorphMenuHost` che il chiamante monta nel viewer, sopra tutto il resto, con lo stesso [morph]: senza l'host il tasto non apre niente.
 *
 * **Quando il menu a goccia non va bene si torna al pop-up di sempre** ([GalleryMenuButton], con lo stesso tasto di vetro):
 * - con le **superfici piene** chieste dal sistema ("meno trasparenza", "piu' contrasto"): il pannello a goccia e' vetro dell'engine e non si puo' rendere pieno da qui, il pop-up si';
 * - quando le voci sono **troppe per lo schermo**: il pannello a goccia non scorre e un "Elimina" in fondo resterebbe sotto il bordo, irraggiungibile.
 */
@Composable
fun GalleryViewerMenuButton(
  entries: List<GalleryMenuEntry>,
  morph: FluidMorphMenuState,
  modifier: Modifier = Modifier,
  contentDescription: String = stringResource(R.string.topbar_more),
  backdrop: GlassBackdropState = currentGlassBackdrop(),
) {
  val solid = LocalGalleryAccessibility.current.solidSurfaces
  val rowHeight = menuRowHeight()
  val screenHeight = LocalConfiguration.current.screenHeightDp.dp
  if (solid || !galleryMenuFits(entries, rowHeight, screenHeight - MenuReservedHeight)) {
    GalleryMenuButton(entries = entries, modifier = modifier, contentDescription = contentDescription, backdrop = backdrop, glassButton = true)
    return
  }
  val latest by rememberUpdatedState(entries)
  FluidMorphMenuButton(
    state = morph,
    actions = { latest.toContextActions() },
    onClick = {},
    // Il bersaglio e' di 48 dp come quello del tasto di chiusura: l'engine disegna l'ancora da 44.
    modifier = modifier
      .heightIn(min = GalleryDimens.MinTouchTarget)
      .semantics { this.contentDescription = contentDescription },
    icon = Icons.Rounded.MoreVert,
    backdrop = backdrop,
    opensOnTap = true,
  )
}

/**
 * Le voci del menu della galleria come voci del menu dell'engine: stesse etichette, icone, stato (spenta, distruttiva) e azioni; un'interruzione di gruppo fra due voci diventa
 * `startsSection` sulla voce che segue (la banda del menu di iOS). Un'interruzione in testa, in coda o doppia non conta.
 */
fun List<GalleryMenuEntry>.toContextActions(): List<FluidContextAction> {
  val actions = ArrayList<FluidContextAction>(size)
  var startsSection = false
  for (entry in this) {
    when (entry) {
      GalleryMenuEntry.GroupBreak -> startsSection = actions.isNotEmpty()
      is GalleryMenuEntry.Item -> {
        actions += FluidContextAction(
          label = entry.label,
          icon = entry.icon,
          destructive = entry.destructive,
          enabled = entry.enabled,
          startsSection = startsSection,
          onClick = entry.onClick,
        )
        startsSection = false
      }
    }
  }
  return actions
}

/**
 * Se un menu a goccia con le voci [entries] sta nello spazio [available]: le righe dell'engine alte [rowHeight] piu' il respiro del pannello e una banda per ogni sezione
 * (`fluidMenuPanelLayout`: 6 dp sopra e sotto, 8 dp a sezione). Pura, per provarla.
 */
fun galleryMenuFits(entries: List<GalleryMenuEntry>, rowHeight: Dp, available: Dp): Boolean {
  val actions = entries.toContextActions()
  val height = MenuPanelPadding * 2 + rowHeight * actions.size + MenuSectionGap * actions.count { it.startsSection }
  return height <= available
}

/** L'altezza di una riga del menu dell'engine: almeno 48 dp, o la riga del testo piu' 24 dp (cresce col testo grande). */
@Composable
private fun menuRowHeight(): Dp {
  val density = LocalDensity.current
  val line = MaterialTheme.typography.bodyLarge.lineHeight
  return with(density) { if (line.isSpecified) maxOf(MenuMinRow, line.toDp() + 24.dp) else MenuMinRow }
}

private val GlassGlyph = 22.dp
private val MenuMinRow = 48.dp
private val MenuPanelPadding = 6.dp
private val MenuSectionGap = 8.dp

/** Sopra il pannello ci sono la barra di stato e la riga dei tasti, e attorno un margine: quanto lo schermo non da' al menu. */
private val MenuReservedHeight = 120.dp
