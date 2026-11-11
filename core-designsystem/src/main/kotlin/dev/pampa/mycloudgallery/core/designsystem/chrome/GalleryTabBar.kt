package dev.pampa.mycloudgallery.core.designsystem.chrome

import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesomeMosaic
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBar
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarScrollConnection
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.rememberFluidFloatingTabBarScrollConnection
import dev.antigravity.fluidengine.ui.haptics.FluidHapticEvent
import dev.antigravity.fluidengine.ui.haptics.LocalFluidHaptics
import dev.pampa.mycloudgallery.core.designsystem.R
import kotlin.math.abs

/**
 * Le tre destinazioni della navigazione principale (G2, come in Foto di Apple): **Libreria · Album · Raccolte**. Cerca non e' una
 * scheda: e' il cerchio di vetro accanto alla pillola ([GalleryTabBar] con `onOpenSearch`), che diventa la barra di ricerca ([GallerySearchBar]).
 *
 * [route] e' la chiave della scheda nella pillola: non e' una rotta di navigazione e non si salva fuori dal processo.
 */
enum class GalleryTab(
  val route: String,
  @StringRes val label: Int,
  val icon: ImageVector,
) {
  Library("library", R.string.tab_library, Icons.Rounded.PhotoLibrary),
  Albums("albums", R.string.tab_albums, Icons.Rounded.PhotoAlbum),
  // Un mosaico e non una pila di foto: con tre schede l'icona di prima (`Collections`) era quasi uguale a quella della Libreria.
  Collections("collections", R.string.tab_collections, Icons.Rounded.AutoAwesomeMosaic),
}

object GalleryTabBarDefaults {

  /** Lo spazio che una pagina deve lasciare in fondo perche' il contenuto non finisca sotto la barra. */
  val ContentInset = FluidFloatingTabBarDefaults.ContentInset

  /** La chiave del cerchio di Cerca nella pillola: non e' una delle [GalleryTab]. */
  const val SearchKey: String = "search"
}

/**
 * La barra delle schede **Libreria · Album · Raccolte** e, accanto, il **cerchio di Cerca** (G2, come in Foto di Apple): la pillola di
 * vetro dell'engine (`FluidFloatingTabBar`) nella forma a DSL, perche' solo quella ha il cerchio separato (`standaloneTab`). Una
 * navigazione sola su ogni formato, telefono e tablet.
 *
 * Il cerchio e' della barra, non un pezzo posato accanto: la barra lo mette alla destra del gruppo centrato, gli lascia il suo spazio
 * quando stringe le schede, e ripiegata dallo scorrimento della Libreria mostra la scheda scelta e il cerchio (stesso vetro, stessa
 * aptica, stessa trasformazione). Non c'e' vetro nuovo qui: questo componente fornisce le voci e basta. Per la piega, la pagina passa
 * [scrollConnection] a `Modifier.nestedScroll`. Il cerchio c'e' sempre, come in produzione (K3a: le prove che disegnavano la pillola da
 * sole la mostravano senza).
 *
 * Il tocco sul cerchio ([onOpenSearch]) apre la ricerca dentro la Libreria (H4): il cerchio si allarga nella barra di ricerca e la pillola esce nello
 * stesso movimento; per questo il cerchio dice dove sta ([searchCircle]). Con la ricerca aperta la pillola non c'e'.
 *
 * La scheda scelta ha sopra di se' la lente della pillola, che prende il tocco e cambia scheda solo dopo un trascinamento: il tocco fermo su di lei
 * (la riselezione, "torna in cima") lo riconosce la barra stessa ([lensTap], GS-2): prima finiva nella lente e non riselezionava (ci riusciva solo un
 * tocco che si muoveva di un pixel).
 *
 * Con [backdrop] nullo la barra disegna i colori piatti (un'anteprima, un dispositivo senza `RenderEffect`).
 *
 * @param onOpenSearch il tocco sul cerchio.
 * @param searchCircle dove si posa il cerchio (la barra di ricerca ci nasce sopra); null = nessuno lo chiede.
 */
@Composable
fun GalleryTabBar(
  selected: GalleryTab,
  onSelect: (GalleryTab) -> Unit,
  modifier: Modifier = Modifier,
  backdrop: GlassBackdropState? = null,
  onReselect: (GalleryTab) -> Unit = {},
  scrollConnection: FluidFloatingTabBarScrollConnection = rememberFluidFloatingTabBarScrollConnection(),
  onOpenSearch: () -> Unit = {},
  searchCircle: GallerySearchCircle? = null,
) {
  val labels = GalleryTab.entries.associateWith { stringResource(it.label) }
  val searchLabel = stringResource(R.string.tab_search)
  val returnTarget = remember { SearchReturnTarget() }
  val haptics = LocalFluidHaptics.current
  val currentSelected by rememberUpdatedState(selected)
  val currentOnReselect by rememberUpdatedState(onReselect)
  val returnTap = Modifier
    .onPlaced { returnTarget.bar = it }
    .lensTap(
      target = returnTarget,
      // Solo con la pillola distesa: ripiegata, la scheda e' un tasto solo, senza lente, e il tocco lo riceve gia'.
      tabUnderLens = { currentSelected.takeIf { !scrollConnection.isInline } },
      onTap = { tab ->
        haptics.play(FluidHapticEvent.Tap)
        // La riselezione ("torna in cima", in fondo per la Libreria).
        currentOnReselect(tab)
      },
    )
  // Le etichette delle schede sono `Text` senza colore: prendono `LocalContentColor`, che fuori da un `Surface` Material e' nero anche in
  // tema scuro. La barra se lo da' da sola, cosi' non dipende da dove la si mette.
  CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    FluidFloatingTabBar(
      selectedTabKey = selected.route,
      scrollConnection = scrollConnection,
      modifier = modifier.then(returnTap),
      backdrop = backdrop,
    ) {
      GalleryTab.entries.forEach { item ->
        val chosen = item == selected
        tab(
          key = item.route,
          title = { Text(labels.getValue(item)) },
          // "Selezionata" sul glifo: la scheda e' un nodo che la barra costruisce da se' e che unisce la semantica dei figli alla propria
          // (come fa la forma a elenco dell'engine).
          icon = {
            Icon(
              item.icon,
              contentDescription = null,
              modifier = Modifier.semantics { this.selected = chosen }.tabIconCopy(returnTarget, item),
            )
          },
          onClick = {
            // La lente rilascia la scheda sotto di se' alla fine di un tocco che si e' mosso anche di un pixel: e' lo stesso tocco che la barra
            // ha gia' servito (l'eco), e la riselezione non parte due volte.
            if (!returnTarget.isEcho(item)) {
              if (chosen) onReselect(item) else onSelect(item)
            }
          },
        )
      }
      standaloneTab(
        key = GalleryTabBarDefaults.SearchKey,
        icon = {
          Icon(
            Icons.Rounded.Search,
            contentDescription = searchLabel,
            // L'icona sta al centro del cerchio: il suo centro e' quello del cerchio (H4). Le copie della barra stanno tutte li', a riposo.
            modifier = if (searchCircle != null) Modifier.onPlaced { searchCircle.coordinates = it } else Modifier,
          )
        },
        onClick = onOpenSearch,
      )
    }
  }
}

/**
 * GS-2: dove sta la scheda sotto la lente, per riconoscere il tocco che la lente della pillola si prende.
 *
 * La lente di `FluidFloatingTabBar` (il "puck") sta sopra la scheda scelta e prende ogni tocco che le cade sopra; cambia scheda solo alla fine
 * di un trascinamento, e un tocco fermo non fa niente. Un tocco fermo sulla scheda scelta e' la riselezione. La barra non permette di togliere la lente (una chiave che non corrisponde a nessuna scheda la lascia dov'era), quindi il tocco lo riconosce chi
 * contiene la barra, prima dei figli ([PointerEventPass.Initial]), senza consumarlo: la lente continua a premersi e a seguire il dito come
 * sempre.
 *
 * Le posizioni delle schede si leggono dalle loro icone (le disegniamo noi, [tabIconCopy]) al momento del tocco: niente stato e niente lavoro
 * per fotogramma. Le copie di un'icona sono piu' d'una (la riga, la copia nitida sopra la lente, la riga tinta mentre la lente e' premuta),
 * ma a riposo stanno tutte sulla loro scheda.
 */
private class SearchReturnTarget {
  /** Il nodo piu' esterno della barra: le celle si misurano qui, e il tocco arriva qui nelle stesse coordinate. */
  var bar: LayoutCoordinates? = null

  /** Le copie disegnate delle icone delle schede. Non e' uno stato: si legge solo al tocco. */
  val icons: MutableSet<TabIconCopy> = LinkedHashSet()

  /** La scheda appena servita dal tocco sulla lente, fino alla fine dello stesso evento: la lente che la rilascia e' un'eco. */
  private var echo: GalleryTab? = null

  fun arm(tab: GalleryTab?) {
    echo = tab
  }

  fun isEcho(tab: GalleryTab): Boolean = echo == tab

  /**
   * La cella di [tab] nelle coordinate della barra: centrata sulla sua icona, larga quanto la distanza fra due schede vicine (le schede della
   * pillola sono uguali e attaccate), alta quanto la barra. Null se la pillola non e' disegnata (ripiegata, fuori schermo).
   */
  fun cellOf(tab: GalleryTab): Rect? {
    val bar = bar?.takeIf { it.isAttached } ?: return null
    val centers = HashMap<GalleryTab, Float>()
    for (copy in icons) {
      val placed = copy.coordinates?.takeIf { it.isAttached } ?: continue
      val which = copy.tab ?: continue
      if (which !in centers) centers[which] = bar.localBoundingBoxOf(placed, clipBounds = false).center.x
    }
    if (centers.size != GalleryTab.entries.size) return null
    val first = centers.getValue(GalleryTab.entries.first())
    val last = centers.getValue(GalleryTab.entries.last())
    val width = abs(last - first) / (GalleryTab.entries.size - 1)
    if (width <= 0f) return null
    val center = centers.getValue(tab)
    return Rect(center - width / 2f, 0f, center + width / 2f, bar.size.height.toFloat())
  }
}

/** Una copia disegnata dell'icona di una scheda e dove sta. */
private class TabIconCopy {
  var tab: GalleryTab? = null
  var coordinates: LayoutCoordinates? = null
}

/** Registra questa copia dell'icona di [tab] presso [target] finche' e' composta. */
@Composable
private fun Modifier.tabIconCopy(target: SearchReturnTarget, tab: GalleryTab): Modifier {
  val copy = remember { TabIconCopy() }
  // La copia nitida sopra la lente puo' passare da una scheda all'altra restando la stessa: la scheda si aggiorna a ogni composizione.
  SideEffect { copy.tab = tab }
  DisposableEffect(target, copy) {
    target.icons += copy
    onDispose { target.icons -= copy }
  }
  return onPlaced { copy.coordinates = it }
}

/**
 * Il tocco fermo sulla cella della scheda sotto la lente ([tabUnderLens]; null = nessuna) chiama [onTap]. Un dito che si muove oltre la soglia
 * del tocco e' un trascinamento della lente: lo serve lei, e qui non succede niente. Non consuma nulla.
 */
private fun Modifier.lensTap(
  target: SearchReturnTarget,
  tabUnderLens: () -> GalleryTab?,
  onTap: (GalleryTab) -> Unit,
): Modifier = pointerInput(target) {
  awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    target.arm(null)
    val tab = tabUnderLens() ?: return@awaitEachGesture
    val cell = target.cellOf(tab) ?: return@awaitEachGesture
    if (!cell.contains(down.position)) return@awaitEachGesture
    val slop = viewConfiguration.touchSlop
    while (true) {
      val event = awaitPointerEvent(PointerEventPass.Initial)
      val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
      if (change.isConsumed || (change.position - down.position).getDistance() > slop) return@awaitEachGesture
      if (!change.pressed) {
        target.arm(tab)
        onTap(tab)
        // La lente risponde allo stesso evento nel passaggio principale: finito quello, l'eco non c'e' piu'.
        awaitPointerEvent(PointerEventPass.Final)
        target.arm(null)
        return@awaitEachGesture
      }
    }
  }
}
