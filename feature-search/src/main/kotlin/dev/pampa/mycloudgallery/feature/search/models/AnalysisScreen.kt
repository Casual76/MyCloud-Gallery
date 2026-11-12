package dev.pampa.mycloudgallery.feature.search.models

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.designsystem.a11y.GalleryLiveAnnouncement
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTile
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryStatusMark
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryProgressRing
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryProgressRingDefaults
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryRingPercent
import dev.pampa.mycloudgallery.core.designsystem.media.galleryRingColor
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryFigureState
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisStatus
import dev.pampa.mycloudgallery.core.library.contract.progressOrNull
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.feature.search.R
import dev.pampa.mycloudgallery.feature.search.contract.AnalysisFlow
import dev.pampa.mycloudgallery.feature.search.contract.AnalysisThumb
import dev.pampa.mycloudgallery.feature.search.contract.SearchThumbnails
import dev.pampa.mycloudgallery.feature.search.ui.SearchDimens
import dev.pampa.mycloudgallery.feature.search.windowOf

// LA PAGINA "ANALISI DELLE FOTO" (Pacchetto X, 12/11/2026): si **vede** il lavoro scorrere. In alto lo stato ridotto (lo stesso della
// testata e della nuvola: anello con le foto pronte, due parole, il motivo vero di una pausa); sotto le anteprime mentre vengono
// analizzate — quella di adesso in evidenza, le ultime finite che si allontanano con il loro segno, le prossime che arrivano. Una
// pagina, non un foglio: resta aperta mentre si guarda e sul tablet usa la larghezza (anteprime piu' grandi, non una colonna stretta).
//
// Costo tenuto basso, di proposito: superfici piene (nessun vetro ne' sfocatura sotto un contenuto che si muove), miniature solo locali
// nella stessa cache di Cerca, movimenti **solo** quando arriva un media nuovo (l'entrata di quello di adesso, lo scorrimento della fila
// delle finite; niente cicli continui), tutto fermo quando la pagina non e' visibile (i flussi si raccolgono con il ciclo di vita) e con
// il movimento ridotto le anteprime cambiano senza scorrere. La pagina non interroga mai il database: legge due flussi gia' pronti.

/** Che cosa la pagina puo' chiedere: far partire l'analisi, o il rimedio di un problema. */
internal interface AnalysisHandlers {
  fun onAnalyzeNow()

  fun onCodeAction(action: CodeAction)
}

internal object NoAnalysisHandlers : AnalysisHandlers {
  override fun onAnalyzeNow() = Unit

  override fun onCodeAction(action: CodeAction) = Unit
}

@Composable
internal fun AnalysisScreen(
  status: LibraryAnalysisStatus,
  flow: AnalysisFlow,
  thumbnails: SearchThumbnails?,
  handlers: AnalysisHandlers,
  onBack: () -> Unit,
) {
  val resources = LocalResources.current
  val flowing = flow.current != null || flow.recent.isNotEmpty() || flow.next.isNotEmpty()
  // Le miniature della pagina nella finestra del caricatore: solo quelle mostrate, quelle del NAS "in arrivo" (mai l'originale, CE-5).
  LaunchedEffect(flow, thumbnails) {
    if (thumbnails == null) return@LaunchedEffect
    val all = listOfNotNull(flow.current) + flow.recent + flow.next
    val requests = all.filterIsInstance<AnalysisThumb.Shown>().mapNotNull { it.request }
    val (local, replicaOnly) = windowOf(requests)
    thumbnails.loader.window(local, replicaOnly)
  }
  FluidScreen(title = stringResource(R.string.analysis_page_title), onBack = onBack) {
    item(key = "analysis-status") { AnalysisStatusBlock(status) }
    val codeActions = analysisCodeActions(status)
    if (codeActions.isNotEmpty()) {
      item(key = "analysis-code-actions") {
        Column(modifier = Modifier.fillMaxWidth().padding(top = SearchDimens.TightGap), verticalArrangement = Arrangement.spacedBy(SearchDimens.TightGap)) {
          codeActions.forEach { action ->
            FluidButton(
              text = stringResource(action.label),
              onClick = { handlers.onCodeAction(action) },
              style = FluidButtonStyle.Filled,
              size = FluidButtonSize.Large,
              fillWidth = true,
              leading = { Icon(imageVector = codeActionIcon(action), contentDescription = null) },
            )
          }
        }
      }
    } else if (status is LibraryAnalysisStatus.Queued || status is LibraryAnalysisStatus.Paused) {
      item(key = "analysis-now") {
        FluidButton(
          text = stringResource(R.string.models_index_now),
          onClick = handlers::onAnalyzeNow,
          style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Large,
          fillWidth = true,
          modifier = Modifier.padding(top = SearchDimens.TightGap),
          leading = { Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null) },
        )
      }
    }
    if (flowing) {
      item(key = "analysis-flow") { AnalysisFlowView(flow, thumbnails) }
    } else {
      item(key = "analysis-empty") { AnalysisEmpty(status) }
    }
  }
  // Uno stato che cambia da solo si annuncia una volta (come la testata del backup).
  analysisSentence(resources, status).takeIf { it.isNotEmpty() }?.let { GalleryLiveAnnouncement(it) }
}

/** I rimedi di un problema (e del modello che manca), dal catalogo dei codici: gli stessi della pagina Ricerca e modelli. */
private fun analysisCodeActions(status: LibraryAnalysisStatus): List<CodeAction> = when (status) {
  is LibraryAnalysisStatus.Problem -> SearchCodes.actions(DiagCode(status.code))
  LibraryAnalysisStatus.ModelMissing -> SearchCodes.actions(DiagCode("AI_MODEL_MISSING"))
  else -> emptyList()
}

/** Lo stato in cima: l'anello delle foto pronte (o il segno) e due righe — lo stato e quante ne restano. TalkBack dice la frase intera. */
@Composable
internal fun AnalysisStatusBlock(status: LibraryAnalysisStatus) {
  val resources = LocalResources.current
  val sentence = analysisSentence(resources, status)
  val (glyph, family, mark) = analysisSign(status)
  val progress = status.progressOrNull()
  val short = analysisShortText(status) ?: return
  val remaining = remainingOf(status)
  val (ready, total) = when (status) {
    is LibraryAnalysisStatus.Running -> status.ready to status.total
    is LibraryAnalysisStatus.Queued -> status.ready to status.total
    is LibraryAnalysisStatus.Paused -> status.ready to status.total
    is LibraryAnalysisStatus.NotHere -> status.ready to status.total
    else -> null to null
  }
  val second = when {
    remaining != null && remaining > 0 -> stringResource(R.string.analysis_remaining, GalleryNumbers.format(remaining))
    ready != null && total != null -> stringResource(R.string.analysis_ready_of, GalleryNumbers.format(ready), GalleryNumbers.format(total))
    else -> null
  }
  FluidListGroup(modifier = Modifier.testTag(AnalysisStatusTag)) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(SearchDimens.HeroPadding).clearAndSetSemantics { contentDescription = sentence },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(SearchDimens.BlockGap),
    ) {
      if (progress != null) {
        GalleryProgressRing(
          progress = progress,
          color = galleryRingColor(GalleryIconFamily.Search),
          strokeWidth = GalleryProgressRingDefaults.stroke(GalleryProgressRingDefaults.Row),
          animate = true,
          modifier = Modifier.size(GalleryProgressRingDefaults.Row),
        ) { GalleryRingPercent(progress) }
      } else {
        GalleryIconTile(icon = glyph, family = family, status = mark, statusDescription = null)
      }
      Column(verticalArrangement = Arrangement.spacedBy(SearchDimens.TightGap)) {
        Text(
          text = short,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.semantics { heading() },
        )
        if (second != null) {
          Text(text = second, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
      }
    }
  }
}

/** Il nastro: adesso in evidenza, appena fatte e prossime in fila. Le misure crescono sul tablet. */
@Composable
private fun AnalysisFlowView(flow: AnalysisFlow, thumbnails: SearchThumbnails?) {
  BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    val wide = maxWidth >= SearchDimens.AnalysisWide
    val currentSize = if (wide) SearchDimens.AnalysisCurrentWide else SearchDimens.AnalysisCurrent
    val thumbSize = if (wide) SearchDimens.AnalysisThumbWide else SearchDimens.AnalysisThumb
    Column(verticalArrangement = Arrangement.spacedBy(SearchDimens.TightGap)) {
      flow.current?.let { current ->
        GallerySectionHeader(title = stringResource(R.string.analysis_now_label))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
          AnalysisThumbView(current, thumbnails, currentSize, done = false, current = true)
        }
      }
      if (flow.recent.isNotEmpty()) {
        GallerySectionHeader(title = stringResource(R.string.analysis_recent_label))
        val rowState = rememberLazyListState()
        val reduced = LocalFluidMotionPolicy.current.reducedMotion
        val firstRecent = flow.recent.firstOrNull()
        // Una finita nuova: la fila torna alla prima (si muove solo a un arrivo, mai in ciclo).
        LaunchedEffect(firstRecent) {
          if (firstRecent == null) return@LaunchedEffect
          if (reduced) rowState.scrollToItem(0) else rowState.animateScrollToItem(0)
        }
        LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(SearchDimens.TightGap), modifier = Modifier.testTag(AnalysisRecentTag)) {
          itemsIndexed(flow.recent, key = { index, thumb -> thumbKey(thumb, index) }) { _, thumb ->
            AnalysisThumbView(thumb, thumbnails, thumbSize, done = true, current = false)
          }
        }
      }
      if (flow.next.isNotEmpty()) {
        GallerySectionHeader(title = stringResource(R.string.analysis_next_label))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(SearchDimens.TightGap), modifier = Modifier.testTag(AnalysisNextTag)) {
          itemsIndexed(flow.next, key = { index, thumb -> thumbKey(thumb, index) }) { _, thumb ->
            AnalysisThumbView(thumb, thumbnails, thumbSize, done = false, current = false)
          }
        }
      }
    }
  }
}

private fun thumbKey(thumb: AnalysisThumb, index: Int): Any = when (thumb) {
  is AnalysisThumb.Shown -> thumb.id.value
  AnalysisThumb.Quiet -> "quiet-$index"
}

/**
 * Un'anteprima del nastro: la miniatura locale (o il segnaposto sfocato) in una tessera piena; un media non mostrabile e' una tessera
 * neutra. Quella di adesso entra con una molla a ogni cambio (letta nel disegno, nessuna ricomposizione a ogni passo; scatta con il
 * movimento ridotto).
 */
@Composable
private fun AnalysisThumbView(thumb: AnalysisThumb, thumbnails: SearchThumbnails?, size: androidx.compose.ui.unit.Dp, done: Boolean, current: Boolean) {
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  val key = (thumb as? AnalysisThumb.Shown)?.id
  val enter = remember(key, current) { Animatable(if (current && !reduced) EnterFrom else 1f) }
  LaunchedEffect(key, current, reduced) {
    if (current && !reduced) enter.animateTo(1f, FluidMotion.snappy()) else enter.snapTo(1f)
  }
  val background = MaterialTheme.colorScheme.surfaceContainerLow
  Box(
    modifier = Modifier
      .size(size)
      .graphicsLayer { scaleX = enter.value; scaleY = enter.value }
      .clip(ContinuousCornerShape(FluidRadius.Card))
      .background(MaterialTheme.colorScheme.surfaceContainer)
      .testTag(if (thumb is AnalysisThumb.Quiet) AnalysisQuietTag else AnalysisShownTag),
    contentAlignment = Alignment.Center,
  ) {
    when (thumb) {
      is AnalysisThumb.Shown -> {
        val image = thumb.request?.let { thumbnails?.loader?.image(it.key, it.ref) }
        val placeholder = remember(thumb.thumbHash, thumbnails) { thumb.thumbHash?.let { thumbnails?.placeholders?.image(it) } }
        val bitmap = image?.value ?: placeholder
        val description = stringResource(if (thumb.video) R.string.analysis_video_description else R.string.analysis_photo_description)
        if (bitmap != null) {
          Image(bitmap = bitmap, contentDescription = description, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
          Box(modifier = Modifier.fillMaxSize().clearAndSetSemantics { contentDescription = description })
        }
        if (thumb.video) {
          Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.BottomStart).size(SearchDimens.AnalysisGlyph),
          )
        }
      }
      AnalysisThumb.Quiet -> Icon(
        imageVector = Icons.Rounded.VisibilityOff,
        contentDescription = stringResource(R.string.analysis_quiet_description),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(SearchDimens.AnalysisGlyph),
      )
    }
    if (done) {
      GalleryStatusMark(
        status = GalleryIconStatus.Done,
        size = SearchDimens.AnalysisMark,
        ring = background,
        contentDescription = stringResource(R.string.analysis_done_description),
        modifier = Modifier.align(Alignment.BottomEnd),
      )
    } else if (current) {
      GalleryStatusMark(
        status = GalleryIconStatus.Running,
        size = SearchDimens.AnalysisMark,
        ring = background,
        contentDescription = null,
        modifier = Modifier.align(Alignment.BottomEnd),
      )
    }
  }
}

/** Quando non scorre niente, lo stesso spazio dice perche': una figura, un titolo, al piu' il perche' dietro la «i». */
@Composable
private fun AnalysisEmpty(status: LibraryAnalysisStatus) {
  val (glyph, _, _) = analysisSign(status)
  val short = analysisShortText(status)
  when (status) {
    LibraryAnalysisStatus.AllReady -> GalleryFigureState(icon = glyph, title = stringResource(R.string.analysis_empty_ready), fill = false)
    is LibraryAnalysisStatus.NotHere -> GalleryFigureState(
      icon = glyph,
      title = stringResource(R.string.analysis_empty_not_here),
      fill = false,
      why = stringResource(R.string.analysis_empty_not_here_why),
    )
    LibraryAnalysisStatus.ModelMissing -> GalleryFigureState(icon = glyph, title = stringResource(R.string.analysis_empty_model), fill = false)
    LibraryAnalysisStatus.Disabled -> GalleryFigureState(icon = glyph, title = stringResource(R.string.analysis_empty_disabled), fill = false)
    else -> if (short != null) GalleryFigureState(icon = glyph, title = short, fill = false)
  }
}

internal const val AnalysisStatusTag: String = "analisi-stato"
internal const val AnalysisRecentTag: String = "analisi-appena-fatte"
internal const val AnalysisNextTag: String = "analisi-prossime"
internal const val AnalysisShownTag: String = "analisi-anteprima"
internal const val AnalysisQuietTag: String = "analisi-nascosta"

private const val EnterFrom = 0.92f
