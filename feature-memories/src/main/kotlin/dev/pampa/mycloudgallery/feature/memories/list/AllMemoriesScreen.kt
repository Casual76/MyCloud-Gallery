package dev.pampa.mycloudgallery.feature.memories.list

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.rememberFluidScreenMetrics
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryCoverCard
import dev.pampa.mycloudgallery.core.designsystem.instant.InstantBlock
import dev.pampa.mycloudgallery.core.designsystem.instant.InstantShape
import dev.pampa.mycloudgallery.core.designsystem.instant.instantSkeleton
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryOnPhoto
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.library.text.GalleryDates
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCard
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.feature.memories.R
import dev.pampa.mycloudgallery.feature.memories.contract.AllMemoriesUi
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesListState
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesThumbnails
import dev.pampa.mycloudgallery.feature.memories.ui.MemoriesDimens
import dev.pampa.mycloudgallery.feature.memories.ui.MemoriesText
import dev.pampa.mycloudgallery.feature.memories.ui.MemoryCover
import dev.pampa.mycloudgallery.feature.memories.ui.MemoryNames
import dev.pampa.mycloudgallery.feature.memories.ui.coverRequest
import kotlinx.coroutines.flow.distinctUntilChanged
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryFigureState
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIcons

// "TUTTI I RICORDI" (Fase 9, P9; docs/fase9-progetto.md §1.5 `MemoriesListState`, §2.5, D14). I1 (fase I, 11/11: "l'app parla troppo"): non piu' righe
// con la copertina piccola e due righe di testo, ma **una griglia di schede** per mese, come la galleria delle Raccolte: la copertina a tutta scheda, il
// titolo in basso sopra la sfumatura (la coppia di `GalleryOnPhoto`) e al piu' il mese; un punto dell'accento sui ricordi nuovi. Il conto delle foto e la
// frase intera li dice TalkBack. Il primo fotogramma ha il segnaposto sfocato dell'indice, poi la miniatura; lo scheletro se non c'e' ancora niente.
// Le miniature (alla taglia della scheda) si chiedono per le schede sullo schermo e per le prossime, non per tutto lo scaffale.

/** Cosa la pagina chiede fuori da se'. */
internal interface AllMemoriesHandlers {
  fun onBack()

  fun onOpen(id: MemoryId)
}

/** Quante colonne al piu' (il tablet largo). */
private const val MAX_COLUMNS = 6

/** Quante righe di schede oltre l'ultima visibile chiedono gia' la copertina. */
private const val AHEAD_ROWS = 2

/** Una riga della griglia: le schede e la chiave stabile (il mese e la posizione). */
private class CardRow(val key: String, val cards: List<MemoryCard>)

@Composable
internal fun AllMemoriesScreen(
  ui: AllMemoriesUi,
  thumbnails: MemoriesThumbnails,
  names: MemoryNames,
  handlers: AllMemoriesHandlers,
  modifier: Modifier = Modifier,
  listState: LazyListState = rememberLazyListState(),
) {
  val resources = LocalContext.current.resources
  val snapshot = ui.snapshot
  val metrics = rememberFluidScreenMetrics()
  val columns = metrics.columns(minColumn = MemoriesDimens.CardMin, spacing = MemoriesDimens.BlockGap, maxColumns = MAX_COLUMNS)
  val months = (ui.state as? MemoriesListState.Ready)?.byMonth.orEmpty()
  val rows = remember(months, columns) {
    months.flatMap { month -> month.cards.chunked(columns).mapIndexed { i, cards -> CardRow("row-${month.month}-$i", cards) } }
  }
  // Le copertine delle righe sullo schermo, e delle prossime: alla taglia della scheda (la piu' grande della cache), mai tutto lo scaffale insieme.
  LaunchedEffect(snapshot, rows, listState) {
    if (snapshot == null) return@LaunchedEffect
    val byKey = rows.withIndex().associate { (i, row) -> row.key to i }
    snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { byKey[it.key] } }
      .distinctUntilChanged()
      .collect { shown ->
        val first = shown.minOrNull() ?: 0
        val last = shown.maxOrNull() ?: (AHEAD_ROWS - 1)
        val visible = rows.subList(first.coerceAtMost(rows.size), (last + 1).coerceAtMost(rows.size)).flatMap { it.cards }
        val ahead = rows.drop(last + 1).take(AHEAD_ROWS).flatMap { it.cards }
        thumbnails.loader.window(
          visible.mapNotNull { coverRequest(thumbnails, snapshot, it.cover, ThumbBucket.L) },
          ahead.mapNotNull { coverRequest(thumbnails, snapshot, it.cover, ThumbBucket.L) },
        )
      }
  }
  FluidScreen(
    title = stringResource(R.string.memorie_all_title),
    modifier = modifier,
    onBack = handlers::onBack,
    listState = listState,
    metrics = metrics,
    contentMaxWidth = MemoriesDimens.PageWidth,
  ) {
    when (val state = ui.state) {
      MemoriesListState.Empty -> item(key = "empty") {
        // Giro 1 (CU-23): la forma unica degli stati vuoti, la figura e il titolo.
        GalleryFigureState(icon = GalleryIcons.Memories.icon, title = stringResource(R.string.memorie_all_empty_title), fill = false)
      }
      is MemoriesListState.Ready -> {
        for (month in state.byMonth) {
          item(key = "header-${month.month}") { GallerySectionHeader(title = GalleryDates.monthYear(month.month)) }
          month.cards.chunked(columns).forEachIndexed { i, cards ->
            item(key = "row-${month.month}-$i", contentType = "row") {
              Row(horizontalArrangement = Arrangement.spacedBy(MemoriesDimens.BlockGap), modifier = Modifier.fillMaxWidth()) {
                cards.forEach { card -> MemoryCardTile(card, ui, thumbnails, names, handlers, resources, Modifier.weight(1f)) }
                repeat(columns - cards.size) { Spacer(Modifier.weight(1f)) }
              }
            }
          }
        }
        if (state.byMonth.isEmpty()) instantSkeleton(InstantShape.of(InstantBlock.Header, InstantBlock.Rows(4)), keyPrefix = "all")
      }
    }
  }
}

/** La scheda di un ricordo: la copertina a tutta scheda, il titolo in basso sopra la sfumatura, il mese, il punto se e' nuovo. */
@Composable
private fun MemoryCardTile(
  card: MemoryCard,
  ui: AllMemoriesUi,
  thumbnails: MemoriesThumbnails,
  names: MemoryNames,
  handlers: AllMemoriesHandlers,
  resources: Resources,
  modifier: Modifier,
) {
  val places = names.places(card.title.placeIds)
  val title = MemoriesText.title(resources, card.kind, card.title, card.title.person?.let { ui.named[it] }, places)
  val caption = MemoriesText.caption(card.kind, card.title, title)
  val spoken = MemoriesText.spoken(resources, card, title)
  val snapshot = ui.snapshot
  val shape = ContinuousCornerShape(FluidRadius.Card)
  // La forma comune delle schede di copertina (core-designsystem): il titolo, il mese e il punto "nuovo" li disegna lei.
  GalleryCoverCard(
    modifier = modifier
      .aspectRatio(MemoriesDimens.CardAspect)
      .fluidPressable(onClick = { handlers.onOpen(card.id) }, role = Role.Button)
      .semantics(mergeDescendants = true) { contentDescription = spoken }
      .clip(shape)
      .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    title = title,
    caption = caption,
    isNew = card.isNew,
    // Giro 1 (CS-6): il titolo e il mese scritti non si rileggono (il valore di serie della scheda comune); la descrizione dice gia' tutto.
  ) {
    if (snapshot != null) MemoryCover(thumbnails, snapshot, card.cover, Modifier.matchParentSize(), ThumbBucket.L)
  }
}

/** Nessuna azione: per le anteprime e gli screenshot. */
internal object NoAllMemoriesHandlers : AllMemoriesHandlers {
  override fun onBack() = Unit

  override fun onOpen(id: MemoryId) = Unit
}
