package dev.pampa.mycloudgallery.feature.memories.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.NavigateBefore
import androidx.compose.material.icons.rounded.NavigateNext
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.feature.memories.R
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesThumbnails
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerClipSurface
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerImages
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerIntro
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerState
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerUi
import dev.pampa.mycloudgallery.feature.memories.page.cellDescription
import dev.pampa.mycloudgallery.feature.memories.ui.MemoriesDimens
import dev.pampa.mycloudgallery.feature.memories.ui.MemoriesText

// LA PRESENTAZIONE (`MemoryPlayer`; Fase 9, P9; docs/fase9-progetto.md §1.5 `PlayerState`, §2.4, D13, E-MEM19): schermo intero, scuro. Il palco con
// l'immagine, in basso i comandi (dopo un tocco, in pausa, sulla carta finale, con TalkBack sempre), in alto "Chiudi" e dove si e'. Il titolo sta
// sopra la copertina nei primi 2 secondi, **su un velo** che lo rende leggibile. Mai due controlli sovrapposti, bersagli da 48 dp, testo a 1,3x.
// Con TalkBack: niente parte da solo, ogni foto ha la sua descrizione e le azioni "Foto successiva" e "Foto precedente".

/** Cosa la presentazione chiede fuori da se': ognuna passa dal controllore. */
internal interface PlayerHandlers {
  fun onTap()

  fun onPlay()

  fun onPause()

  fun onNext()

  fun onPrevious()

  fun onRestart()

  fun onExit()
}

@Composable
internal fun PlayerScreen(
  ui: PlayerUi,
  thumbnails: MemoriesThumbnails,
  images: PlayerImages,
  clips: PlayerClipSurface,
  handlers: PlayerHandlers,
  modifier: Modifier = Modifier,
) {
  val state = ui.state
  val snapshot = ui.snapshot
  Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    when {
      ui.gone -> Gone(handlers)
      state == null || snapshot == null -> Loading()
      state is PlayerState.Ended -> EndCard(ui, state, snapshot, thumbnails, handlers)
      else -> Playing(ui, state, snapshot, thumbnails, images, clips, handlers)
    }
  }
}

@Composable
private fun Loading() {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(text = stringResource(R.string.memorie_player_loading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun Gone(handlers: PlayerHandlers) {
  Column(
    modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(MemoriesDimens.PlayerInset),
    verticalArrangement = Arrangement.spacedBy(MemoriesDimens.BlockGap, Alignment.CenterVertically),
  ) {
    FluidEmptyState(title = stringResource(R.string.memorie_gone_title), detail = stringResource(R.string.memorie_gone_detail))
    FluidButton(text = stringResource(R.string.memorie_back_to_memories), onClick = handlers::onExit, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true)
  }
}

@Composable
private fun Playing(
  ui: PlayerUi,
  state: PlayerState,
  snapshot: TimelineSnapshot,
  thumbnails: MemoriesThumbnails,
  images: PlayerImages,
  clips: PlayerClipSurface,
  handlers: PlayerHandlers,
) {
  val resources = LocalContext.current.resources
  val item = checkNotNull(state.itemOrNull())
  val index = checkNotNull(state.indexOrNull())
  val density = LocalDensity.current.density
  var size by remember { mutableStateOf(IntSize.Zero) }
  var drag by remember { mutableFloatStateOf(0f) }
  val at = snapshot.indexOf(item.media)
  val description = describe(resources, ui, snapshot, at)
  val position = stringResource(R.string.memorie_player_position, index + 1, ui.items.size)
  val next = stringResource(R.string.memorie_player_next)
  val previous = stringResource(R.string.memorie_player_previous)
  Box(
    modifier = Modifier
      .fillMaxSize()
      .onSizeChanged { size = it }
      .pointerInput(Unit) { detectTapGestures(onTap = { handlers.onTap() }) }
      .pointerInput(Unit) {
        var dx = 0f
        var dy = 0f
        detectDragGestures(
          onDragStart = {
            dx = 0f
            dy = 0f
            drag = 0f
          },
          onDragEnd = {
            drag = 0f
            when (PlayerGestures.resolve(dx, dy, size.width.toFloat(), size.height.toFloat(), density = density)) {
              PlayerGesture.NEXT -> handlers.onNext()
              PlayerGesture.PREVIOUS -> handlers.onPrevious()
              PlayerGesture.EXIT -> handlers.onExit()
              PlayerGesture.NONE -> Unit
            }
          },
          onDragCancel = { drag = 0f },
          onDrag = { change, amount ->
            change.consume()
            dx += amount.x
            dy += amount.y
            if (abs(dy) > abs(dx) && dy > 0f && !state.reducedMotion) drag = dy
          },
        )
      }
      .semantics {
        customActions = listOf(
          CustomAccessibilityAction(next) {
            handlers.onNext()
            true
          },
          CustomAccessibilityAction(previous) {
            handlers.onPrevious()
            true
          },
        )
      }
      .graphicsLayer { translationY = drag },
  ) {
    PlayerStage(
      snapshot = snapshot,
      item = item,
      index = index,
      moving = state is PlayerState.Playing && ui.intro == null,
      reducedMotion = state.reducedMotion,
      durationMs = durationOf(item),
      thumbnails = thumbnails,
      images = images,
      clips = clips,
      modifier = Modifier
        .fillMaxSize()
        .semantics {
          contentDescription = if (state.talkBack) "$description. $position" else description
          liveRegion = LiveRegionMode.Polite
        }
    )
    ui.intro?.let { intro -> Intro(intro, talkBack = state.talkBack, modifier = Modifier.align(Alignment.BottomStart)) }
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(MemoriesDimens.PlayerInset), verticalArrangement = Arrangement.SpaceBetween) {
      TopBar(position = position, notice = ui.musicMissing, handlers = handlers)
      if (ui.controls) Controls(playing = state is PlayerState.Playing, talkBack = state.talkBack, handlers = handlers)
      else Progress(index, ui.items.size)
    }
  }
}

/** La frase di TalkBack per una foto: "Foto del 12 marzo 2025, Oslo", e "con Anna" solo dentro l'app (§2.4). Mai il nome del file. */
internal fun describe(resources: android.content.res.Resources, ui: PlayerUi, snapshot: TimelineSnapshot, at: Int): String {
  val base = if (at >= 0) cellDescription(resources, snapshot, at) else resources.getString(R.string.memorie_cell_undated)
  val withPlace = ui.header?.placeNames?.firstOrNull()?.let { "$base, $it" } ?: base
  return ui.header?.personName?.let { resources.getString(R.string.memorie_player_photo_with, withPlace, it) } ?: withPlace
}

/** In alto: "Chiudi" a sinistra, dove si e' a destra; sotto, se serve, l'avviso che la canzone non c'e' piu'. */
@Composable
private fun TopBar(position: String, notice: Boolean, handlers: PlayerHandlers) {
  Column(verticalArrangement = Arrangement.spacedBy(MemoriesDimens.TightGap)) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      FluidButton(
        text = stringResource(R.string.memorie_player_close_short), onClick = handlers::onExit, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large,
        leading = { Icon(imageVector = Icons.Rounded.Close, contentDescription = null) },
      )
      Text(text = position, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (notice) {
      FluidInlineMessage(message = stringResource(R.string.memorie_player_music_missing), tone = FluidTone.Warning, title = stringResource(R.string.memorie_message_title))
    }
  }
}

/** In basso: precedente, pausa o riproduci, successiva. Con TalkBack niente "Riproduci": non parte niente da solo. */
@Composable
private fun Controls(playing: Boolean, talkBack: Boolean, handlers: PlayerHandlers) {
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MemoriesDimens.TightGap)) {
    FluidButton(
      text = stringResource(R.string.memorie_player_previous), onClick = handlers::onPrevious, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large,
      modifier = Modifier.weight(1f), leading = { Icon(imageVector = Icons.Rounded.NavigateBefore, contentDescription = null) },
    )
    if (!talkBack) {
      FluidButton(
        text = stringResource(if (playing) R.string.memorie_player_pause else R.string.memorie_player_play),
        onClick = if (playing) handlers::onPause else handlers::onPlay,
        size = FluidButtonSize.Large, modifier = Modifier.weight(1f),
        leading = { Icon(imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null) },
      )
    }
    FluidButton(
      text = stringResource(R.string.memorie_player_next), onClick = handlers::onNext, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large,
      modifier = Modifier.weight(1f), leading = { Icon(imageVector = Icons.Rounded.NavigateNext, contentDescription = null) },
    )
  }
}

/** Una striscia sottile in basso che dice a che punto si e': non si tocca, non porta testo. */
@Composable
private fun Progress(index: Int, count: Int) {
  val fraction = if (count <= 0) 0f else (index + 1).toFloat() / count
  Box(modifier = Modifier.fillMaxWidth().height(MemoriesDimens.ProgressHeight).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
    Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(fraction).background(MaterialTheme.colorScheme.primary))
  }
}

/** Il titolo sopra la copertina per i primi 2 secondi (§2.4), su un velo per la leggibilita'. Con TalkBack resta finche' l'utente non va avanti. */
@Composable
private fun Intro(intro: PlayerIntro, talkBack: Boolean, modifier: Modifier = Modifier) {
  val resources = LocalContext.current.resources
  val header = intro.header
  val title = MemoriesText.title(resources, header.kind, header.title, header.personName, header.placeNames)
  val subtitle = MemoriesText.subtitle(resources, header.title, header.placeNames)
  val scrim = MaterialTheme.colorScheme.scrim
  Column(
    modifier = modifier
      .fillMaxWidth()
      .background(Brush.verticalGradient(listOf(scrim.copy(alpha = 0f), scrim.copy(alpha = VEIL_ALPHA))))
      .navigationBarsPadding()
      .padding(MemoriesDimens.PlayerInset)
      .padding(top = MemoriesDimens.PlayerButton, bottom = MemoriesDimens.PlayerButton)
      .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    verticalArrangement = Arrangement.spacedBy(MemoriesDimens.TightGap),
  ) {
    Text(text = title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
    Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (talkBack) Text(text = stringResource(R.string.memorie_player_intro_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

private const val VEIL_ALPHA: Float = 0.72f

/** Nessuna azione: per le anteprime e gli screenshot. */
internal object NoPlayerHandlers : PlayerHandlers {
  override fun onTap() = Unit

  override fun onPlay() = Unit

  override fun onPause() = Unit

  override fun onNext() = Unit

  override fun onPrevious() = Unit

  override fun onRestart() = Unit

  override fun onExit() = Unit
}
