package dev.pampa.mycloudgallery.core.designsystem.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.currentGlassBackdrop
import dev.antigravity.fluidengine.ui.fluidphysics.FluidMorphMenuButton
import dev.antigravity.fluidengine.ui.fluidphysics.FluidMorphMenuHost
import dev.antigravity.fluidengine.ui.fluidphysics.FluidMorphMenuState
import dev.antigravity.fluidengine.ui.fluidphysics.rememberFluidMorphMenuState
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
//
// IL MENU ⋮ DEL VIEWER (K1, rilievi GF-1, GS-1 e GF-8 delle review sul viewer). Per la beta e' il **pop-up** dell'engine con il velo sotto le voci (come il
// pop-up della chrome della Libreria) e lo stesso tasto di vetro tondo della chiusura. Il menu a goccia di G3 resta qui, dietro un solo interruttore
// ([ViewerDropletMenu]), spento: il suo pannello non ha ne' velo ne' pellicola regolabile, e sopra una foto chiara in tema scuro le voci scendevano a
// 2,4:1 e "Elimina dal telefono" a 1,06:1.

/**
 * L'interruttore del menu a goccia del viewer: **spento** per la beta, e con lui ogni scelta a tempo di esecuzione fra goccia e pop-up (il ⋮ apre sempre
 * il pop-up con il velo, e l'host della goccia non si compone).
 *
 * Da riaccendere quando l'engine avra' **(1)** un velo o una pellicola regolabile per il pannello del menu a goccia (oggi `FluidGlassMenuPanel` disegna
 * le voci con un colore fisso e `FluidMorphMenu` stende la sua pellicola al 50% senza velo: il testo non regge sopra una foto chiara in tema scuro) e
 * **(2)** un'ancora tonda (oggi l'ancora di `FluidMorphMenuButton` e' una capsula di circa 60 x 48 dp accanto al cerchio della chiusura). Riaccendendolo
 * tornano anche le misure del pannello ricopiate dall'engine qui sotto ([galleryMenuFits]): vanno riviste sull'engine di quel momento.
 */
private const val ViewerDropletMenu: Boolean = false

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
 * Lo stato del menu ⋮ del viewer, di chi monta il viewer: [isOpen] dice se il menu e' aperto (o si sta aprendo), [close] lo chiude.
 *
 * Il viewer lo legge per due regole (GS-1): con il menu aperto le frecce della tastiera non cambiano foto, e se la foto cambia comunque (un'eliminazione
 * da un altro dispositivo, un tocco sulla griglia affiancata) il menu si chiude. Le voci agiscono sulla foto di quando si sono composte: un menu rimasto
 * aperto sopra un'altra foto agirebbe su quella di prima.
 */
@Stable
class GalleryViewerMenuState internal constructor(internal val morph: FluidMorphMenuState?) {

  /** Il pop-up e' aperto: dal tocco sul ⋮ alla richiesta di chiusura (una voce, Indietro, un tocco fuori, [close]). */
  internal var popoverOpen by mutableStateOf(false)

  /** Il menu e' aperto, pop-up o goccia: dal tocco sul ⋮ alla richiesta di chiusura. */
  val isOpen: Boolean get() = popoverOpen || morph?.isOpen == true

  /** Chiude il menu (con la sua animazione d'uscita). Senza menu aperto non fa niente. */
  fun close() {
    popoverOpen = false
    morph?.dismiss()
  }
}

/** Lo stato del menu ⋮ di un viewer: uno per viewer, ricordato. Con la goccia spenta ([ViewerDropletMenu]) non porta lo stato della goccia. */
@Composable
fun rememberGalleryViewerMenuState(): GalleryViewerMenuState {
  val morph = if (ViewerDropletMenu) rememberFluidMorphMenuState() else null
  return remember(morph) { GalleryViewerMenuState(morph) }
}

/**
 * Il tasto ⋮ del viewer: lo stesso tasto di vetro tondo del tasto di chiusura ([GalleryGlassIconButton], col fondale della barra) e, toccato, il **pop-up**
 * dell'engine che cresce dal tasto, con le voci di [entries] sopra un velo del colore della pagina che le fa leggere sopra qualunque foto
 * ([GalleryMenuLegibility.onPhoto]: voci a 4,5:1 col margine dei veli, la voce distruttiva a 4,5:1). Se le voci non ci stanno, scorrono.
 *
 * Il pop-up vive nel `FluidGlassModalHost` alla radice dell'app (senza host il tocco non apre niente) e prende il fuoco: con il menu aperto i tasti
 * vanno a lui e non al viewer. Il portale si compone dalla prima apertura: un viewer che non apre il menu non lo paga.
 *
 * @param state di chi monta il viewer ([rememberGalleryViewerMenuState]): dice al viewer se il menu e' aperto e glielo fa chiudere.
 */
@Composable
fun GalleryViewerMenuButton(
  entries: List<GalleryMenuEntry>,
  state: GalleryViewerMenuState,
  modifier: Modifier = Modifier,
  contentDescription: String = stringResource(R.string.topbar_more),
  backdrop: GlassBackdropState = currentGlassBackdrop(),
) {
  val morph = state.morph
  if (morph != null) {
    if (dropletMenuFits(entries)) {
      DropletMenuButton(entries = entries, morph = morph, modifier = modifier, contentDescription = contentDescription, backdrop = backdrop)
      return
    }
  }
  ViewerMenuPopover(entries = entries, state = state, modifier = modifier, contentDescription = contentDescription, backdrop = backdrop)
}

/**
 * L'host del menu a goccia del viewer, da montare per ultimo nel viewer (sopra tutto il resto, cosi' anche il suo Indietro vince su quelli del pannello
 * informazioni). Con la goccia spenta ([ViewerDropletMenu]) non compone niente.
 */
@Composable
fun GalleryViewerMenuHost(state: GalleryViewerMenuState, backdrop: GlassBackdropState) {
  val morph = state.morph ?: return
  FluidMorphMenuHost(state = morph, backdrop = backdrop)
}

@Composable
private fun ViewerMenuPopover(
  entries: List<GalleryMenuEntry>,
  state: GalleryViewerMenuState,
  modifier: Modifier,
  contentDescription: String,
  backdrop: GlassBackdropState,
) {
  var armed by remember { mutableStateOf(false) }
  var bounds by remember { mutableStateOf<Rect?>(null) }
  GalleryGlassIconButton(
    icon = Icons.Rounded.MoreVert,
    contentDescription = contentDescription,
    onClick = {
      armed = true
      state.popoverOpen = true
    },
    modifier = modifier.onGloballyPositioned { bounds = it.boundsInRoot() },
    backdrop = backdrop,
  )
  // Se il tasto lascia la composizione con il menu aperto (la barra che sparisce), il portale se ne va con lui: il menu non resta "aperto" per il viewer.
  DisposableEffect(state) { onDispose { state.popoverOpen = false } }
  if (!armed) return
  val inks = viewerMenuInks()
  FluidGlassModalPortal(
    visible = state.popoverOpen,
    onDismissRequest = { state.popoverOpen = false },
    origin = { bounds },
    presentation = FluidGlassModalPresentation.Popover,
    paneTitle = contentDescription,
  ) {
    Box(modifier = Modifier.background(inks.veil).verticalScroll(rememberScrollState())) {
      GalleryMenuPanel(entries = entries, onItemSelected = { state.popoverOpen = false }, destructiveColor = inks.destructive)
    }
  }
}

/** Il velo e il rosso del menu del viewer per il tema, la pellicola dei modali e le superfici piene di adesso. */
@Composable
private fun viewerMenuInks(): GalleryMenuInks {
  val scheme = MaterialTheme.colorScheme
  val film = GlassDefaults.modalTint().overlay
  val dark = GlassDefaults.isDarkSurface()
  val solid = LocalGalleryAccessibility.current.solidSurfaces
  return remember(scheme, film, dark, solid) { GalleryMenuLegibility.onPhoto(scheme, film, dark, solid) }
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

// --- Il ramo della goccia (spento: [ViewerDropletMenu]) ---------------------------------------------------------------------------------------------------
// Tutto cio' che segue decide qualcosa solo con l'interruttore acceso. Le misure del pannello sono ricopiate dall'engine (`fluidMenuPanelLayout`,
// `FluidGlassMenuPanel` della 3.0.4): **vanno riviste a ogni aggiornamento dell'engine** prima di riaccendere la goccia.

/**
 * Il ⋮ che diventa il menu dalla goccia del tasto (`FluidMorphMenuButton(opensOnTap)`): al congedo torna tasto; tenendo premuto il dito passa sulle voci e
 * la scelta si fa rilasciando. Le voci sono quelle di [entries] (un'interruzione di gruppo diventa l'inizio di una sezione). Il menu vive nell'host del
 * viewer ([GalleryViewerMenuHost]).
 */
@Composable
private fun DropletMenuButton(
  entries: List<GalleryMenuEntry>,
  morph: FluidMorphMenuState,
  modifier: Modifier,
  contentDescription: String,
  backdrop: GlassBackdropState,
) {
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
 * Se la goccia va bene per [entries]; se no il ⋮ apre il pop-up, con le stesse voci:
 * - con le **superfici piene** chieste dal sistema ("meno trasparenza", "piu' contrasto"): il pannello a goccia e' vetro dell'engine e non si puo' rendere
 *   pieno da qui, il pop-up si';
 * - quando le voci sono **troppe per lo schermo**: il pannello a goccia non scorre e un "Elimina" in fondo resterebbe sotto il bordo, irraggiungibile.
 */
@Composable
private fun dropletMenuFits(entries: List<GalleryMenuEntry>): Boolean {
  if (LocalGalleryAccessibility.current.solidSurfaces) return false
  val screenHeight = LocalConfiguration.current.screenHeightDp.dp
  return galleryMenuFits(entries, menuRowHeight(), screenHeight - MenuReservedHeight)
}

/**
 * Se un menu a goccia con le voci [entries] sta nello spazio [available]: le righe dell'engine alte [rowHeight] piu' il respiro del pannello e una banda per ogni sezione
 * (`fluidMenuPanelLayout`: 6 dp sopra e sotto, 8 dp a sezione). Pura, per provarla. Decide qualcosa solo con la goccia accesa ([ViewerDropletMenu]).
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

// Misure del pannello a goccia dell'engine (3.0.4), ricopiate: da rivedere a ogni aggiornamento dell'engine.
private val MenuMinRow = 48.dp
private val MenuPanelPadding = 6.dp
private val MenuSectionGap = 8.dp

/** Sopra il pannello ci sono la barra di stato e la riga dei tasti, e attorno un margine: quanto lo schermo non da' al menu. */
private val MenuReservedHeight = 120.dp
