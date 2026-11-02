package dev.pampa.mycloudgallery.feature.memories.list

import android.content.res.Resources
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.instant.InstantBlock
import dev.pampa.mycloudgallery.core.designsystem.instant.InstantShape
import dev.pampa.mycloudgallery.core.designsystem.instant.instantSkeleton
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

// "TUTTI I RICORDI" (Fase 9, P9; docs/fase9-progetto.md §1.5 `MemoriesListState`, §2.5, D14): lo scaffale intero per mese, in righe raggruppate di
// Fluid. La copertina sta a sinistra, titolo e conto accanto (mai testo sopra la foto); il segno "nuovo" e' una pillola a destra. Il primo
// fotogramma non ha copertine (arrivano dopo il filtro finale dei Nascosti) e ha lo scheletro se non c'e' ancora niente. Solo componenti G2.

/** Cosa la pagina chiede fuori da se'. */
internal interface AllMemoriesHandlers {
  fun onBack()

  fun onOpen(id: MemoryId)
}

private const val FIRST_VISIBLE = 12

@Composable
internal fun AllMemoriesScreen(
  ui: AllMemoriesUi,
  thumbnails: MemoriesThumbnails,
  names: MemoryNames,
  handlers: AllMemoriesHandlers,
  modifier: Modifier = Modifier,
) {
  val resources = LocalContext.current.resources
  val snapshot = ui.snapshot
  val cards = (ui.state as? MemoriesListState.Ready)?.byMonth.orEmpty().flatMap { it.cards }
  // Le miniature delle copertine: le prime in vista, le altre in arrivo (l'elenco ha al piu' 60 voci).
  LaunchedEffect(snapshot, cards) {
    if (snapshot == null) return@LaunchedEffect
    val requests = cards.mapNotNull { coverRequest(thumbnails, snapshot, it.cover, ThumbBucket.S) }
    thumbnails.loader.window(requests.take(FIRST_VISIBLE), requests.drop(FIRST_VISIBLE))
  }
  FluidScreen(
    title = stringResource(R.string.memorie_all_title),
    modifier = modifier,
    onBack = handlers::onBack,
    contentMaxWidth = MemoriesDimens.SheetWidth,
  ) {
    when (val state = ui.state) {
      MemoriesListState.Empty -> item(key = "empty") {
        FluidEmptyState(title = stringResource(R.string.memorie_all_empty_title), detail = stringResource(R.string.memorie_all_empty_detail))
      }
      is MemoriesListState.Ready -> {
        for (month in state.byMonth) {
          item(key = "header-${month.month}") { GallerySectionHeader(title = GalleryDates.monthYear(month.month)) }
          item(key = "month-${month.month}") {
            FluidListGroup {
              month.cards.forEachIndexed { index, card ->
                if (index > 0) FluidListDivider()
                MemoryRow(card, ui, thumbnails, names, handlers, resources)
              }
            }
          }
        }
        if (state.byMonth.isEmpty()) instantSkeleton(InstantShape.of(InstantBlock.Header, InstantBlock.Rows(4)), keyPrefix = "all")
      }
    }
  }
}

@Composable
private fun MemoryRow(
  card: MemoryCard,
  ui: AllMemoriesUi,
  thumbnails: MemoriesThumbnails,
  names: MemoryNames,
  handlers: AllMemoriesHandlers,
  resources: Resources,
) {
  val places = names.places(card.title.placeIds)
  val title = MemoriesText.title(resources, card.kind, card.title, card.title.person?.let { ui.named[it] }, places)
  val spoken = MemoriesText.spoken(resources, card, title)
  val snapshot = ui.snapshot
  FluidListRow(
    title = title,
    subtitle = resources.getString(R.string.memorie_all_row_detail, MemoriesText.photos(resources, card.count), MemoriesText.subtitle(resources, card.title, places)),
    tone = if (card.isNew) FluidTone.Primary else FluidTone.Neutral,
    modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
    leading = {
      if (snapshot != null) {
        MemoryCover(thumbnails, snapshot, card.cover, Modifier.size(MemoriesDimens.RowCover), ThumbBucket.S)
      } else {
        Box(Modifier.size(MemoriesDimens.RowCover))
      }
    },
    badge = if (card.isNew) {
      { FluidStatusBadge(label = stringResource(R.string.memorie_new), tone = FluidTone.Primary) }
    } else {
      null
    },
    onClick = { handlers.onOpen(card.id) },
  )
}

/** Nessuna azione: per le anteprime e gli screenshot. */
internal object NoAllMemoriesHandlers : AllMemoriesHandlers {
  override fun onBack() = Unit

  override fun onOpen(id: MemoryId) = Unit
}
