package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidScreenDefaults
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryCoverCard
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTile
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTileDefaults
import dev.pampa.mycloudgallery.core.designsystem.icon.rememberGalleryIconColors
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryFigureState
import dev.pampa.mycloudgallery.core.designsystem.state.phoneTrashText
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.feature.library.R
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.distinctUntilChanged

// LE RACCOLTE COME UNA GALLERIA (fase I, I1, 11/11). L'utente ha messo a confronto le nostre Raccolte con la Galleria di Samsung e ha scelto "come
// Samsung": ogni sezione e' un titolo corto (toccabile, con la freccia, se porta a "tutti") e sotto un **carosello orizzontale di schede grandi** con la
// foto o il volto e il nome sopra una sfumatura. Niente righe di testo, niente "185 foto": le frasi restano per TalkBack.
//
//  - Persone: schede alte con il volto a tutta scheda e il nome in basso (una persona senza nome: solo il volto).
//  - Ricordi e Viaggi: schede larghe con la copertina, il titolo e al piu' una riga piccola (il mese, "Viaggio").
//  - Luoghi: la mappa in anteprima a tutta larghezza (CollectionMapPreview.kt) e sotto le schede dei posti con una foto e il nome.
//  - Utility, NAS, Nascosti: niente foto dell'utente da mostrare (i Nascosti **mai**): piastrelle compatte con l'icona espressiva del componente
//    condiviso (I3) e l'etichetta; il numero solo se dice qualcosa (le operazioni in corso).
//  - Lo stato di una sezione (Persone spenta, i ricordi in preparazione): un invito con l'icona; sotto un carosello, un segno piccolo.
//
// **Prestazioni.** I caroselli sono `LazyRow` con chiavi stabili (il codice della voce), le foto vengono dalla stessa cache delle miniature con la
// taglia della scheda e solo per le schede vicine allo schermo ([CoverViewport]); il disegno non legge niente per fotogramma; la pressione e' una scala
// (`fluidPressable`), mai una misura che cambia; nessun vetro qui dentro.

/** Il margine della pagina: i caroselli sporgono fino al bordo dello schermo (lo mette `CollectionsTab` dalla misura del pannello). */
internal val LocalCollectionsGutter = staticCompositionLocalOf { FluidScreenDefaults.HorizontalPadding }

/** La forma delle schede e delle piastrelle. */
private val CardShape: Shape = ContinuousCornerShape(FluidRadius.Card)

/** Quante schede oltre l'ultima visibile chiedono gia' la foto. */
private const val AHEAD = 2

/** Le parti di una sezione, divise per forma ([EntryShape]): pure, provate su JVM (`CollectionsGalleryPartsTest`). */
@Immutable
internal class SectionParts(
  val map: CollectionEntry?,
  val cards: List<CollectionEntry>,
  val more: CollectionEntry?,
  val notices: List<CollectionEntry>,
  val tiles: List<SectionRow>,
)

/**
 * Le parti di questa sezione: la mappa (la prima voce [EntryShape.Map]), le schede del carosello, il "tutti" (la prima voce [EntryShape.More]), gli stati
 * e le piastrelle (le raccolte della scheda, Nascosti ed Eliminati di recente, sono sempre piastrelle: mai una foto).
 */
internal fun CollectionsSection.parts(): SectionParts {
  var map: CollectionEntry? = null
  var more: CollectionEntry? = null
  val cards = ArrayList<CollectionEntry>()
  val notices = ArrayList<CollectionEntry>()
  val tiles = ArrayList<SectionRow>()
  for (row in rows) {
    when (row) {
      is SectionRow.Kind -> tiles += row
      is SectionRow.Entry -> when (row.entry.resolvedShape) {
        EntryShape.Map -> if (map == null) map = row.entry else tiles += row
        EntryShape.More -> if (more == null) more = row.entry else notices += row.entry
        EntryShape.Card -> cards += row.entry
        EntryShape.Notice -> notices += row.entry
        EntryShape.Tile -> tiles += row
      }
    }
  }
  return SectionParts(map, cards, more, notices, tiles)
}

/** Il nome dell'unica cosa della sezione, se e' una piastrella sola (Nascosti); null altrimenti. */
@Composable
private fun SectionParts.soleTileTitle(): String? {
  if (map != null || more != null || cards.isNotEmpty() || notices.isNotEmpty() || tiles.size != 1) return null
  return when (val row = tiles.single()) {
    is SectionRow.Kind -> stringResource(row.row.kind.titleRes)
    is SectionRow.Entry -> row.entry.title
  }
}

/** Le voci di una sezione che hanno una foto o dei volti da preparare (le copertine e i ritagli). */
internal fun CollectionsSection.coverEntries(): List<CollectionEntry> =
  rows.mapNotNull { (it as? SectionRow.Entry)?.entry?.takeIf { e -> e.cover != null || e.faces.isNotEmpty() || e.coverPlace > 0 || e.coverPlaces.isNotEmpty() } }

/**
 * Le sezioni visibili di [state] come galleria: per ognuna il titolo e il corpo (mappa, carosello, stati, piastrelle). Su due pannelli la voce o la
 * raccolta scelta ha il filo dell'accento e il titolo non promette "si apre una pagina" piu' di quanto faccia.
 */
internal fun LazyListScope.collectionsGallery(
  state: CollectionsLayoutState,
  selectedKind: CollectionKind?,
  selectedEntry: String?,
  onSelectKind: (CollectionKind) -> Unit,
  onSelectEntry: (CollectionEntry) -> Unit,
) {
  for (section in state.visible) {
    val parts = section.parts()
    // Il titolo porta al "tutti" della sezione; i Luoghi non ne hanno uno e il titolo apre la Mappa (come il tocco sulla carta). Titolo e corpo sono un
    // elemento solo della colonna: il titolo sta attaccato al suo carosello, e lo spazio fra le sezioni e' quello della pagina.
    val target = parts.more ?: parts.map
    item(key = "section-${section.code}", contentType = "section") {
      val title = section.title.resolve()
      Column {
        // Una sezione che e' una piastrella sola con il suo stesso nome (Nascosti) e' la piastrella e basta: il nome non si scrive due volte.
        if (parts.soleTileTitle() != title) {
          SectionTitle(title, target, selected = target != null && target.code == selectedEntry, onOpen = onSelectEntry)
        }
        SectionBody(section, parts, selectedKind, selectedEntry, onSelectKind, onSelectEntry)
      }
    }
  }
}

/** Il titolo di una sezione: grande, e toccabile con la freccia se la sezione ha un "tutti" (Tutte le persone, Tutti i ricordi). */
@Composable
private fun SectionTitle(title: String, more: CollectionEntry?, selected: Boolean, onOpen: (CollectionEntry) -> Unit) {
  val scheme = MaterialTheme.colorScheme
  val base = Modifier.fillMaxWidth().heightIn(min = CollectionsDimens.SectionTitleMin)
  val modifier = if (more == null) {
    base.semantics { heading() }
  } else {
    base
      .fluidPressable(onClick = { onOpen(more) }, role = Role.Button)
      .semantics(mergeDescendants = true) {
        heading()
        contentDescription = "$title, ${more.title}"
        if (selected) this.selected = true
      }
  }
  Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CollectionsDimens.SectionTitleGap)) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleLarge,
      color = scheme.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f, fill = false),
    )
    if (more != null) {
      Icon(imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = if (selected) scheme.primary else scheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun SectionBody(
  section: CollectionsSection,
  parts: SectionParts,
  selectedKind: CollectionKind?,
  selectedEntry: String?,
  onSelectKind: (CollectionKind) -> Unit,
  onSelectEntry: (CollectionEntry) -> Unit,
) {
  val family = CollectionArtFamily.of(section.code)
  Column(verticalArrangement = Arrangement.spacedBy(CollectionsDimens.CarouselGap)) {
    parts.map?.let { map ->
      CollectionMapCard(map, parts.cards, family, selected = map.code == selectedEntry, onSelect = onSelectEntry)
    }
    if (parts.cards.isNotEmpty()) Carousel(section.code, parts.cards, parts.more, family, selectedEntry, onSelectEntry)
    val alone = parts.cards.isEmpty() && parts.map == null
    if (alone) {
      // Niente da mostrare ancora: un invito con l'icona (lo stato della sezione), o il "tutti" se e' l'unica cosa che c'e'.
      val invites = parts.notices.ifEmpty { listOfNotNull(parts.more) }
      invites.forEach { InviteCard(it, selected = it.code == selectedEntry, onSelect = onSelectEntry) }
    } else if (parts.notices.isNotEmpty()) {
      NoticeChips(parts.notices, family, onSelectEntry)
    }
    if (parts.tiles.isNotEmpty()) TileGrid(parts.tiles, family, selectedKind, selectedEntry, onSelectKind, onSelectEntry)
  }
}

/** Il genere di una scheda del carosello: dai dati della voce. */
private enum class CardKind { Person, Place, Wide }

private fun cardKindOf(entry: CollectionEntry, family: CollectionArtFamily): CardKind = when {
  // Una persona resta una scheda alta anche senza un volto da ritagliare (il cancello lo ha rifiutato): la figura al posto del volto.
  entry.faces.isNotEmpty() || family == CollectionArtFamily.People -> CardKind.Person
  entry.cover == null && entry.coverPlace > 0 -> CardKind.Place
  else -> CardKind.Wide
}

private fun cardWidth(kind: CardKind): Dp = when (kind) {
  CardKind.Person -> CollectionsDimens.PersonCardWidth
  CardKind.Place -> CollectionsDimens.PlaceCardSide
  CardKind.Wide -> CollectionsDimens.WideCardWidth
}

private fun cardHeight(kind: CardKind): Dp = when (kind) {
  CardKind.Person -> CollectionsDimens.PersonCardHeight
  CardKind.Place -> CollectionsDimens.PlaceCardSide
  CardKind.Wide -> CollectionsDimens.WideCardHeight
}

/**
 * Il carosello di una sezione: le schede, di lato, fino al bordo dello schermo (il pezzo che sporge dice "scorri"), e in fondo la scheda del "tutti".
 * Dice a [CoverViewport] quali schede si vedono e quali stanno per entrare: le foto si chiedono solo per quelle.
 */
@Composable
private fun Carousel(
  code: String,
  cards: List<CollectionEntry>,
  more: CollectionEntry?,
  family: CollectionArtFamily,
  selectedEntry: String?,
  onSelect: (CollectionEntry) -> Unit,
) {
  val gutter = LocalCollectionsGutter.current
  val rowState = rememberLazyListState()
  val viewport = LocalCoverViewport.current
  if (viewport != null) {
    LaunchedEffect(rowState, cards, viewport) {
      snapshotFlow { rowState.layoutInfo.visibleItemsInfo.let { (it.firstOrNull()?.index ?: 0) to (it.lastOrNull()?.index ?: 0) } }
        .distinctUntilChanged()
        .collect { (first, last) ->
          val from = first.coerceIn(0, cards.size)
          val to = min(last, cards.lastIndex)
          viewport.report(code, if (to >= from) cards.subList(from, to + 1) else emptyList(), cards.drop(to + 1).take(AHEAD))
        }
    }
    DisposableEffect(code, viewport) { onDispose { viewport.drop(code) } }
  }
  val firstKind = cardKindOf(cards.first(), family)
  LazyRow(
    state = rowState,
    modifier = Modifier.bleed(gutter),
    contentPadding = PaddingValues(horizontal = gutter),
    horizontalArrangement = Arrangement.spacedBy(CollectionsDimens.CarouselGap),
  ) {
    items(cards, key = { it.code }, contentType = { cardKindOf(it, family) }) { entry ->
      CollectionCard(entry, family, selected = entry.code == selectedEntry, onSelect = onSelect)
    }
    if (more != null) {
      item(key = "more:" + more.code, contentType = "more") {
        MoreCard(more, firstKind, family, selected = more.code == selectedEntry, onSelect = onSelect)
      }
    }
  }
}

/** Allarga l'elemento di [gutter] per parte, fino al bordo dello schermo, senza cambiare lo spazio che occupa nella colonna. */
private fun Modifier.bleed(gutter: Dp): Modifier = layout { measurable, constraints ->
  if (!constraints.hasBoundedWidth) {
    val placeable = measurable.measure(constraints)
    return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
  }
  val extra = gutter.roundToPx()
  val wide = constraints.maxWidth + 2 * extra
  val placeable = measurable.measure(constraints.copy(minWidth = wide, maxWidth = wide))
  layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
}

/** Il filo dell'accento attorno alla scheda scelta sul tablet. */
@Composable
private fun Modifier.selectedRing(selected: Boolean, shape: Shape): Modifier =
  if (selected) border(CollectionsDimens.SelectedRing, MaterialTheme.colorScheme.primary, shape) else this

/** Cio' che TalkBack dice di una voce: il titolo, la frase sotto (i numeri e lo stato che lo schermo non scrive) e la parola a destra ("Nuovo"). */
internal fun CollectionEntry.spoken(): String = listOfNotNull(
  title,
  subtitle.takeIf { it.isNotBlank() },
  // Giro 1 (CS-6): il testo scritto sulla scheda non si rilegge, quindi la descrizione dice anche la riga piccola (il mese, "Viaggio") se la frase
  // non la contiene gia'.
  caption?.takeIf { it.isNotBlank() && !subtitle.contains(it, ignoreCase = true) },
  trailing,
  badge.takeIf { it > 0 }?.let(GalleryNumbers::format),
).joinToString(", ")

/**
 * Una scheda del carosello: la foto (o il volto) a tutta scheda sul colore della famiglia (che si vede finche' la foto non c'e', senza salti), il titolo
 * in basso sopra una sfumatura, e un punto se e' nuova. Una persona senza nome ([CollectionEntry.showTitle] falso): solo il volto.
 */
@Composable
private fun CollectionCard(entry: CollectionEntry, family: CollectionArtFamily, selected: Boolean, onSelect: (CollectionEntry) -> Unit) {
  val kind = cardKindOf(entry, family)
  val covers = LocalCollectionCovers.current
  val colors = rememberGalleryIconColors(iconFamilyOf(family, entry.tone, entry.icon))
  val facePx = collectionFacePx()
  val bucket = collectionCardBucket()
  val spoken = entry.spoken()
  val wide = kind == CardKind.Wide
  // La forma comune delle schede di copertina (core-designsystem): il titolo sulla sfumatura e il punto "nuovo" li disegna lei.
  GalleryCoverCard(
    modifier = Modifier
      .size(cardWidth(kind), cardHeight(kind))
      .fluidPressable(onClick = { onSelect(entry) }, role = Role.Button)
      .semantics(mergeDescendants = true) {
        contentDescription = spoken
        if (selected) this.selected = true
      }
      .selectedRing(selected, CardShape)
      .clip(CardShape)
      .background(Brush.linearGradient(listOf(colors.top, colors.bottom))),
    title = entry.title.takeIf { entry.showTitle },
    caption = if (wide) entry.caption else null,
    titleStyle = if (wide) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
    titleMaxLines = if (wide) 2 else 1,
    isNew = entry.trailing != null && entry.badge == 0,
    // Giro 1 (CS-6): la descrizione della scheda dice gia' il titolo, la frase e "Nuovo": il titolo scritto non si rilegge.
    labelsSpoken = false,
  ) {
    Icon(imageVector = entry.icon, contentDescription = null, tint = colors.glyph, modifier = Modifier.align(Alignment.Center).size(CollectionsDimens.CardGlyph))
    if (covers != null) {
      if (kind == CardKind.Person) {
        entry.faces.firstOrNull()?.let { covers.faceImage(it, facePx) }?.let { face ->
          Image(bitmap = face, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
      } else {
        covers.mediaOf(entry)?.let { media -> CoverImage(covers, media, Modifier.matchParentSize(), bucket) }
      }
    }
  }
}

/** La scheda in fondo al carosello che porta a "tutti": la freccia nel colore della famiglia e il nome ("Tutte le persone"), alta come le altre. */
@Composable
private fun MoreCard(more: CollectionEntry, kind: CardKind, family: CollectionArtFamily, selected: Boolean, onSelect: (CollectionEntry) -> Unit) {
  val scheme = MaterialTheme.colorScheme
  val colors = rememberGalleryIconColors(iconFamilyOf(family, more.tone))
  Box(
    modifier = Modifier
      .size(minOf(cardWidth(kind), CollectionsDimens.PlaceCardSide), cardHeight(kind))
      .fluidPressable(onClick = { onSelect(more) }, role = Role.Button)
      .semantics(mergeDescendants = true) {
        contentDescription = more.spoken()
        if (selected) this.selected = true
      }
      .selectedRing(selected, CardShape)
      .clip(CardShape)
      .background(scheme.surfaceContainerLow),
  ) {
    Column(
      modifier = Modifier.align(Alignment.Center).padding(CollectionsDimens.CardTextInset),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(CollectionsDimens.CardTextInset),
    ) {
      Box(
        modifier = Modifier.size(CollectionsDimens.MoreDisc).clip(FluidCapsuleShape).background(Brush.linearGradient(listOf(colors.top, colors.bottom))),
        contentAlignment = Alignment.Center,
      ) {
        Icon(imageVector = Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = colors.glyph)
      }
      Text(
        text = more.title,
        style = MaterialTheme.typography.titleSmall,
        color = scheme.onSurface,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/**
 * L'invito di una sezione che non ha ancora niente da mostrare (Persone spenta, i ricordi in preparazione, nessun viaggio). Giro 1 (CU-23): nella forma
 * unica degli stati vuoti della galleria ([GalleryFigureState]: la figura e il titolo, nessuna frase), come la Libreria vuota, dentro una scheda;
 * tutto l'invito si tocca e TalkBack lo legge una volta ([CollectionEntry.spoken]). Sul tablet la scelta e' il filo intorno.
 */
@Composable
private fun InviteCard(entry: CollectionEntry, selected: Boolean, onSelect: (CollectionEntry) -> Unit) {
  GalleryFigureState(
    icon = entry.icon,
    title = entry.title,
    fill = false,
    modifier = Modifier
      .fillMaxWidth()
      .fluidPressable(onClick = { onSelect(entry) }, role = Role.Button)
      .semantics(mergeDescendants = true) {
        contentDescription = entry.spoken()
        if (selected) this.selected = true
      }
      .selectedRing(selected, CardShape)
      .clip(CardShape)
      // Sopra il fondo di una scheda: si vede che e' una cosa sola, e che si tocca (non un'intestazione della pagina).
      .background(MaterialTheme.colorScheme.surfaceContainer)
      .padding(vertical = CollectionsDimens.CardTextInset),
  )
}

/** Gli stati sotto un carosello ("Altri volti (3)", "1.240 senza posizione"): capsule piccole con l'icona della famiglia, una accanto all'altra. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoticeChips(notices: List<CollectionEntry>, family: CollectionArtFamily, onSelect: (CollectionEntry) -> Unit) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(CollectionsDimens.CarouselGap), verticalArrangement = Arrangement.spacedBy(CollectionsDimens.CarouselGap)) {
    notices.forEach { entry -> NoticeChip(entry, family, onSelect) }
  }
}

@Composable
private fun NoticeChip(entry: CollectionEntry, family: CollectionArtFamily, onSelect: (CollectionEntry) -> Unit) {
  val scheme = MaterialTheme.colorScheme
  val colors = rememberGalleryIconColors(iconFamilyOf(family, entry.tone, entry.icon))
  Box(
    modifier = Modifier
      .heightIn(min = CollectionsDimens.SectionTitleMin)
      .fluidPressable(onClick = { onSelect(entry) }, role = Role.Button)
      .semantics(mergeDescendants = true) { contentDescription = entry.spoken() },
    contentAlignment = Alignment.Center,
  ) {
    Row(
      modifier = Modifier
        .heightIn(min = CollectionsDimens.ChipHeight)
        .clip(FluidCapsuleShape)
        .background(scheme.surfaceContainerLow)
        .padding(horizontal = CollectionsDimens.ChipPadding),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(CollectionsDimens.SectionTitleGap * 2),
    ) {
      Icon(imageVector = entry.icon, contentDescription = null, tint = colors.top, modifier = Modifier.size(CollectionsDimens.ChipGlyph))
      Text(text = entry.title, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

/**
 * Le piastrelle compatte di una sezione senza foto (Utility, NAS, Nascosti): l'icona espressiva del componente condiviso e l'etichetta, due per riga sul
 * telefono (tante quante ne stanno sul tablet). La frase sotto il nome di prima la dice TalkBack; si vede solo un numero che conta qualcosa.
 */
@Composable
private fun TileGrid(
  rows: List<SectionRow>,
  family: CollectionArtFamily,
  selectedKind: CollectionKind?,
  selectedEntry: String?,
  onSelectKind: (CollectionKind) -> Unit,
  onSelectEntry: (CollectionEntry) -> Unit,
) {
  TileFlow {
    rows.forEach { row ->
      when (row) {
        is SectionRow.Kind -> KindTile(row.row, family, selected = row.row.kind == selectedKind, onSelect = onSelectKind)
        is SectionRow.Entry -> {
          val entry = row.entry
          CollectionTile(
            title = entry.title,
            spoken = entry.spoken(),
            iconFamily = iconFamilyOf(family, entry.tone, entry.icon),
            icon = entry.icon,
            badge = entry.badge,
            selected = entry.code == selectedEntry,
            onClick = { onSelectEntry(entry) },
          )
        }
      }
    }
  }
}

/** Una raccolta della scheda (Nascosti, Eliminati di recente): una piastrella come le altre, mai una foto e mai un conto (E-H1). */
@Composable
private fun KindTile(row: CollectionRow, family: CollectionArtFamily, selected: Boolean, onSelect: (CollectionKind) -> Unit) {
  val title = stringResource(row.kind.titleRes)
  val detail = when (row.kind) {
    CollectionKind.HIDDEN -> stringResource(R.string.collection_hidden_subtitle)
    CollectionKind.RECENTLY_DELETED ->
      stringResource(phoneTrashText(R.string.collection_recently_deleted_subtitle, R.string.collection_recently_deleted_subtitle_gallery))
    else -> null
  }
  CollectionTile(
    title = title,
    spoken = listOfNotNull(title, detail).joinToString(", "),
    iconFamily = iconFamilyOf(family, row.kind.tone, row.kind.icon),
    icon = row.kind.icon,
    badge = 0,
    selected = selected,
    onClick = { onSelect(row.kind) },
  )
}

@Composable
private fun CollectionTile(
  title: String,
  spoken: String,
  iconFamily: dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  badge: Int,
  selected: Boolean,
  onClick: () -> Unit,
) {
  val scheme = MaterialTheme.colorScheme
  Row(
    modifier = Modifier
      .heightIn(min = CollectionsDimens.TileMinHeight)
      .fluidPressable(onClick = onClick, role = Role.Button)
      .semantics(mergeDescendants = true) {
        contentDescription = spoken
        if (selected) this.selected = true
      }
      .selectedRing(selected, CardShape)
      .clip(CardShape)
      .background(scheme.surfaceContainerLow)
      .padding(CollectionsDimens.TilePadding),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(CollectionsDimens.TilePadding),
  ) {
    GalleryIconTile(icon = icon, family = iconFamily, size = GalleryIconTileDefaults.Medium)
    Text(
      text = title,
      style = MaterialTheme.typography.titleSmall,
      color = scheme.onSurface,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    if (badge > 0) {
      Text(
        text = GalleryNumbers.format(badge),
        style = MaterialTheme.typography.labelMedium,
        color = scheme.onPrimary,
        modifier = Modifier
          .clip(FluidCapsuleShape)
          .background(scheme.primary)
          .padding(horizontal = CollectionsDimens.TileBadgeHorizontal, vertical = CollectionsDimens.TileBadgeVertical),
      )
    }
  }
}

/**
 * La griglia delle piastrelle: tante colonne quante ne stanno con la larghezza minima ([CollectionsDimens.TileMinWidth]), la stessa altezza per tutte
 * quelle di una riga. Un `Layout` solo, senza sottocomposizione.
 */
@Composable
private fun TileFlow(content: @Composable () -> Unit) {
  Layout(content = content) { measurables, constraints ->
    val gap = CollectionsDimens.CarouselGap.roundToPx()
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else CollectionsDimens.TileMinWidth.roundToPx() * 2 + gap
    val columns = max(1, (width + gap) / (CollectionsDimens.TileMinWidth.roundToPx() + gap))
    val cell = (width - gap * (columns - 1)) / columns
    val rows = measurables.chunked(columns)
    val placed = rows.map { row ->
      // Le piastrelle di una riga sono alte uguali anche se un'etichetta va a capo: l'altezza della riga dalle misure intrinseche, una misura sola.
      val height = row.maxOf { it.maxIntrinsicHeight(cell) }
      row.map { it.measure(Constraints.fixed(cell, height)) } to height
    }
    val total = placed.sumOf { it.second } + gap * max(0, placed.size - 1)
    layout(width, total) {
      var y = 0
      placed.forEach { (row, height) ->
        row.forEachIndexed { i, p -> p.place(i * (cell + gap), y) }
        y += height + gap
      }
    }
  }
}
