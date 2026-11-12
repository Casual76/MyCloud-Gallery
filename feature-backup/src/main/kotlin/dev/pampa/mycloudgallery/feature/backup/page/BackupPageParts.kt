package dev.pampa.mycloudgallery.feature.backup.page

import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.runtime.remember
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.SourceStatus
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTile
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIcons
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryStatusMark
import dev.pampa.mycloudgallery.core.designsystem.icon.rememberGalleryIconColors
import dev.pampa.mycloudgallery.core.designsystem.icon.rememberGalleryStatusColors
import dev.pampa.mycloudgallery.core.designsystem.state.LocalWritesHeldByRelease
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.feature.backup.BackupDimens
import dev.pampa.mycloudgallery.feature.backup.R
import dev.pampa.mycloudgallery.feature.backup.contract.BackupScreen
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedCounts
import dev.pampa.mycloudgallery.feature.backup.ui.BackupActionTexts
import dev.pampa.mycloudgallery.feature.backup.ui.BackupProblemTexts
import dev.pampa.mycloudgallery.feature.backup.ui.BackupSourceCover
import dev.pampa.mycloudgallery.feature.backup.ui.BackupText
import dev.pampa.mycloudgallery.feature.backup.ui.sourceGlyph
import dev.pampa.mycloudgallery.feature.backup.ui.sourceName

// I PEZZI DELLA PAGINA BACKUP (I3, fase I): le copertine delle sorgenti, il gruppo «Da controllare» e le righe del NAS. Tutto con la piastrella
// espressiva di core-designsystem (`GalleryIconTile`): il colore dice la famiglia, il glifo la cosa, il segno nell'angolo lo stato.

// --- Le copertine delle sorgenti ---------------------------------------------------------------------------------------------------

/**
 * Le sorgenti in una griglia di copertine: tre per riga sul telefono dell'utente (360 dp), di piu' sullo schermo largo. Non e' una lista che scorre
 * da sola: le sorgenti sono poche (le cartelle scelte) e stanno in un elemento solo della pagina.
 */
@Composable
internal fun SourceGrid(sources: List<SourceStatus>, halt: SourcesHalt?) {
  // Giro 1 della fase I (CS-2): lo stato comune («In pausa», «In corso») lo dicono l'anello e l'etichetta in cima, una volta; il segno sta solo
  // sulla copertina diversa dalle altre (un problema, una cartella spenta). Prima la pausa si vedeva otto volte nella stessa pagina.
  val states = sources.map { sourceState(it, it.problems.values.sum(), halt) }
  // Giro 1 (CS-13): col testo grande la copertina cresce con il testo (a 1,3x due per riga sul telefono), e il nome va su due righe: prima a 1,3x
  // «Fotoca…», «Screens…», «WhatsA…» non si distinguevano.
  val scale = LocalDensity.current.fontScale
  BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    val gap = BackupDimens.CoverGap
    val minColumns = if (scale >= LargeTextScale) MinColumnsLargeText else MinColumns
    val minCell = BackupDimens.CoverMinCell * maxOf(1f, scale)
    val columns = maxOf(minColumns, ((maxWidth + gap) / (minCell + gap)).toInt())
    val cell = (maxWidth - gap * (columns - 1)) / columns
    // I6 (CS-27): una riga riserva due righe al nome solo se almeno un nome della riga ne ha bisogno (a quella larghezza e con quel testo): le barre
    // della stessa riga restano allineate, e una riga di nomi corti non ha il vuoto sotto.
    val names = sources.map { sourceName(it.source) }
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val cellPx = with(LocalDensity.current) { cell.roundToPx() }
    val lines = remember(names, cellPx, style, columns) {
      coverNameLines(names.map { measurer.measure(it, style, maxLines = NameLines, constraints = Constraints(maxWidth = cellPx)).lineCount }, columns)
    }
    Column(verticalArrangement = Arrangement.spacedBy(BackupDimens.CoverRowGap)) {
      sources.indices.chunked(columns).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
          row.forEach { index ->
            SourceCard(source = sources[index], halt = halt, cell = cell, showMark = coverMarkShown(states[index], states), nameLines = lines[index])
          }
        }
      }
    }
  }
}

/**
 * Una sorgente: la copertina (la sua foto piu' recente, o la piastrella con il glifo) con il segno dello stato nell'angolo, il nome, una barra sottile
 * con quanto e' al sicuro e **il numero che conta**: quante mancano (la freccia in su), quanti problemi (il triangolo), la spunta quando e' tutto al
 * sicuro, la nuvola barrata quando la cartella non si salva. Nessuna frase: per TalkBack la copertina dice nome, stato e numeri in una frase.
 */
@Composable
private fun SourceCard(source: SourceStatus, halt: SourcesHalt?, cell: Dp, showMark: Boolean, nameLines: Int = NameLines) {
  val name = sourceName(source.source)
  val problems = source.problems.values.sum()
  val state = sourceState(source, problems, halt)
  val mark = if (state == SourceState.Halted && halt != null) halt.mark else state.mark
  val stateLabel = stringResource(if (state == SourceState.Halted && halt != null) halt.pill else state.label)
  val missing = (source.media - source.safe).coerceAtLeast(0)
  val count = when {
    !source.active -> CountLine(Icons.Rounded.CloudOff, source.media, mark)
    problems > 0 -> CountLine(Icons.Rounded.Warning, problems, mark)
    missing > 0 -> CountLine(Icons.Rounded.ArrowUpward, missing, mark)
    else -> CountLine(Icons.Rounded.Check, source.media, mark)
  }
  val numbers = when {
    !source.active -> pluralStringResource(R.plurals.backup_source_media, source.media, BackupText.count(source.media)) + ", " +
      stringResource(R.string.backup_source_not_saved)
    problems > 0 -> pluralStringResource(R.plurals.backup_source_problems, problems, BackupText.count(problems))
    missing > 0 -> stringResource(R.string.backup_source_to_save, BackupText.count(missing))
    else -> stringResource(R.string.backup_source_safe, BackupText.count(source.safe))
  }
  val fraction = if (source.media > 0) (source.safe.toFloat() / source.media).coerceIn(0f, 1f) else 1f
  Column(
    modifier = Modifier.width(cell).semantics(mergeDescendants = true) { contentDescription = "$name, $stateLabel. $numbers" },
  ) {
    SourceCover(source = source.source, cell = cell, mark = mark.takeIf { showMark }, dim = !source.active)
    Spacer(Modifier.height(BackupDimens.CoverLineGap))
    Text(
      text = name,
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurface,
      // Giro 2 (CS-18) e I6 (CS-27): le righe del nome sono quelle della riga di copertine (due se un nome ne ha bisogno), cosi' barre e numeri
      // della stessa riga stanno alla stessa altezza senza un vuoto dove i nomi sono corti.
      minLines = nameLines,
      maxLines = NameLines,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.clearAndSetSemantics {},
    )
    Spacer(Modifier.height(BackupDimens.CoverLineGap))
    if (source.active) {
      ThinBar(fraction = fraction, color = rememberGalleryStatusColors(mark).top)
      Spacer(Modifier.height(BackupDimens.CoverLineGap))
    }
    CountRow(count)
  }
}

/**
 * CS-2: il segno di stato sulla copertina solo quando la cartella e' diversa dalle altre e chiede uno sguardo: un problema o una cartella che non si
 * salva. Lo stato che hanno tutte (in pausa, in corso, al sicuro) lo dice la testata, e la barra e il numero di ogni copertina lo mostrano senza segno.
 */
internal fun coverMarkShown(state: SourceState, all: List<SourceState>): Boolean =
  (state == SourceState.Problem || state == SourceState.Off) && all.any { it != state }

/**
 * I6 (CS-27): le righe da riservare al nome di ogni copertina, da [lines] (quante ne usa ciascun nome, al piu' [NameLines]) e dalle [columns] della
 * griglia: in ogni riga di copertine il massimo della riga. Pura, per la prova.
 */
internal fun coverNameLines(lines: List<Int>, columns: Int): List<Int> =
  lines.indices.chunked(maxOf(1, columns)).flatMap { row ->
    val most = row.maxOf { lines[it] }.coerceIn(1, NameLines)
    row.map { most }
  }

/** Il numero che conta di una copertina: il glifo (che dice di che numero si tratta) e il numero. */
private class CountLine(val icon: ImageVector, val value: Int, val mark: GalleryIconStatus)

@Composable
private fun CountRow(line: CountLine) {
  val tint = when (line.mark) {
    GalleryIconStatus.Done -> rememberGalleryStatusColors(GalleryIconStatus.Done).top
    GalleryIconStatus.Attention, GalleryIconStatus.Problem -> rememberGalleryStatusColors(line.mark).top
    else -> MaterialTheme.colorScheme.onSurfaceVariant
  }
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BackupDimens.LineGap)) {
    Icon(imageVector = line.icon, contentDescription = null, tint = tint, modifier = Modifier.size(BackupDimens.CountGlyph))
    Text(
      text = GalleryNumbers.format(line.value),
      style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
    )
  }
}

/** La barra sottile: la traccia e la parte al sicuro, disegnate (niente misure che cambiano). */
@Composable
private fun ThinBar(fraction: Float, color: Color) {
  val track = MaterialTheme.colorScheme.onSurface.copy(alpha = BarTrackAlpha)
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(BackupDimens.CoverBar)
      .clip(FluidCapsuleShape)
      .drawBehind {
        drawRect(track)
        if (fraction > 0f) drawRect(color, size = size.copy(width = size.width * fraction))
      },
  )
}

/**
 * La copertina: la piastrella della famiglia delle foto con il glifo della sorgente (la fotocamera, lo screenshot, la cartella), e sopra la foto piu'
 * recente quando c'e' (stessa cache e stessi cancelli della griglia). Il segno dello stato sporge dall'angolo in basso a destra. Una cartella che non
 * si salva e' sbiadita.
 */
@Composable
internal fun SourceCover(source: BackupSource, cell: Dp, mark: GalleryIconStatus?, dim: Boolean) {
  val colors = rememberGalleryIconColors(GalleryIconFamily.Photos)
  val shape = ContinuousCornerShape(FluidRadius.Card)
  Box(modifier = Modifier.size(cell)) {
    Box(
      modifier = Modifier
        .matchParentSize()
        .graphicsLayer { alpha = if (dim) DimAlpha else 1f }
        .clip(shape)
        .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(colors.top, colors.bottom))),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = sourceGlyph(source),
        contentDescription = null,
        tint = colors.glyph,
        modifier = Modifier.size(cell * BackupDimens.CoverGlyphFraction),
      )
      BackupSourceCover(source)
    }
    if (mark != null) {
      GalleryStatusMark(
        status = mark,
        size = BackupDimens.CoverMark,
        ring = MaterialTheme.colorScheme.background,
        contentDescription = null,
        modifier = Modifier.align(Alignment.BottomEnd).offset(x = BackupDimens.CoverMarkOverhang, y = BackupDimens.CoverMarkOverhang),
      )
    }
  }
}

/** Nessuna sorgente scelta: una riga con la piastrella e la strada per sceglierle, nelle impostazioni del backup. */
@Composable
internal fun SourcesEmpty() {
  FluidListGroup {
    FluidListRow(
      title = stringResource(R.string.backup_sources_empty_title),
      subtitle = "",
      leading = { GalleryIconTile(spec = GalleryIcons.Library, status = GalleryIconStatus.Attention) },
      leadingTile = false,
      disclosure = false,
    )
  }
}

// --- Da controllare ------------------------------------------------------------------------------------------------------------------

/** Il gruppo «Da controllare»: le attivita' da decidere, i file ricevuti, gli altri problemi. */
@Composable
internal fun ToCheckGroup(pendingTasks: Int, received: ReceivedCounts, problems: List<Pair<BackupProblemCode, Int>>, actions: BackupPageActions) {
  FluidListGroup {
    var first = true
    fun divider(): Boolean = (!first).also { first = false }
    if (pendingTasks > 0) {
      if (divider()) FluidListDivider()
      FluidListRow(
        title = stringResource(R.string.backup_tasks_entry_title),
        subtitle = "",
        leading = { GalleryIconTile(icon = Icons.Rounded.Checklist, family = GalleryIconFamily.Backup, status = GalleryIconStatus.Attention) },
        leadingTile = false,
        badge = { CountBadge(pendingTasks) },
        onClick = { actions.open(BackupScreen.PENDING_TASKS) },
      )
    }
    if (received.total > 0) {
      if (divider()) FluidListDivider()
      ReceivedRow(received = received, onOpen = { actions.open(BackupScreen.RECEIVED) })
    }
    problems.forEach { (code, count) ->
      if (divider()) FluidListDivider()
      ProblemRow(code = code, count = count, actions = actions)
    }
  }
}

/** Il numero a destra di una riga (le attivita' da decidere): il valore corto, non una frase. */
@Composable
private fun CountBadge(value: Int) {
  Text(
    text = GalleryNumbers.format(value),
    style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

/**
 * IN-7: i file ricevuti da un'altra app che non sono ancora sul NAS. Il sottotitolo e' lo stato che cambia (quanti in attesa, in caricamento, con un
 * problema); un tocco apre l'elenco.
 */
@Composable
private fun ReceivedRow(received: ReceivedCounts, onOpen: () -> Unit) {
  val parts = ArrayList<String>(3)
  if (received.waiting > 0) parts += stringResource(R.string.backup_received_entry_waiting, BackupText.count(received.waiting))
  if (received.uploading > 0) parts += stringResource(R.string.backup_received_entry_uploading, BackupText.count(received.uploading))
  if (received.problems > 0) parts += pluralStringResource(R.plurals.backup_received_entry_problems, received.problems, BackupText.count(received.problems))
  val mark = when {
    received.problems > 0 -> GalleryIconStatus.Attention
    received.uploading > 0 -> GalleryIconStatus.Running
    else -> GalleryIconStatus.Paused
  }
  FluidListRow(
    title = stringResource(R.string.backup_received_entry_title),
    subtitle = parts.joinToString(" · "),
    leading = { GalleryIconTile(icon = Icons.Rounded.MoveToInbox, family = GalleryIconFamily.Backup, status = mark) },
    leadingTile = false,
    onClick = onOpen,
  )
}

/**
 * Un problema: la piastrella che dice la cosa (con il segno del tono) e il messaggio corto. Un tocco apre sotto il perche' e il rimedio (i tasti con
 * l'icona); un altro tocco li richiude. Per TalkBack la riga e' un tasto con il suo stato, aperto o chiuso.
 */
@Composable
private fun ProblemRow(code: BackupProblemCode, count: Int, actions: BackupPageActions) {
  val text = BackupProblemTexts.of(code, LocalWritesHeldByRelease.current)
  val countPlural = text.titleCount
  val title = if (countPlural != null && count > 0) pluralStringResource(countPlural, count, GalleryNumbers.format(count)) else stringResource(text.title)
  val resolved = code.actions.map { it to BackupActionTexts.of(it) }
  val hint = resolved.firstOrNull { !it.second.isButton }?.let { stringResource(it.second.label) }
  val buttons = resolved.filter { it.second.isButton }
  var open by rememberSaveable(code.code) { mutableStateOf(false) }
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  val turn by animateFloatAsState(if (open) HalfTurn else 0f, if (reduced) snap() else FluidMotion.snappy(), label = "problem chevron")
  val openState = stringResource(R.string.backup_hero_detail_open)
  val closedState = stringResource(R.string.backup_hero_detail_closed)
  Column {
    FluidListRow(
      title = title,
      subtitle = "",
      leading = { GalleryIconTile(icon = text.glyph, family = text.family, status = text.mark) },
      leadingTile = false,
      badge = {
        Icon(
          imageVector = Icons.Rounded.ExpandMore,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.graphicsLayer { rotationZ = turn },
        )
      },
      disclosure = false,
      onClick = { open = !open },
      modifier = Modifier.semantics { stateDescription = if (open) openState else closedState },
    )
    AnimatedVisibility(
      visible = open,
      enter = if (reduced) EnterTransition.None else fadeIn(FluidMotion.fadeIn()) + expandVertically(FluidMotion.intSize()),
      exit = if (reduced) ExitTransition.None else fadeOut(FluidMotion.fadeOut()) + shrinkVertically(FluidMotion.intSize()),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(start = BackupDimens.CardPadding, end = BackupDimens.CardPadding, bottom = BackupDimens.CardPadding),
        verticalArrangement = Arrangement.spacedBy(BackupDimens.CardGap),
      ) {
        val detail = listOfNotNull(text.detail?.let { stringResource(it) }, hint).joinToString("\n")
        if (detail.isNotEmpty()) {
          Text(text = detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        buttons.forEachIndexed { index, (action, label) ->
          FluidButton(
            text = stringResource(label.label),
            onClick = { actions.perform(action) },
            style = if (index == 0) FluidButtonStyle.Filled else FluidButtonStyle.Tinted,
            size = FluidButtonSize.Large,
            fillWidth = true,
            leading = { Icon(imageVector = label.icon, contentDescription = null) },
          )
        }
      }
    }
  }
}

// --- Sul NAS -------------------------------------------------------------------------------------------------------------------------

/**
 * Le righe del NAS: la cartella del dispositivo (il nome a destra, come un valore), la copia privata dell'area comune (la spunta: si fa sempre; il
 * perche' dietro un tocco) e le impostazioni del backup.
 */
@Composable
internal fun DeviceGroup(state: BackupPageUi, actions: BackupPageActions) {
  val folder = state.deviceFolder
  // La cartella si sceglie una volta (D7), con il primo backup (D-9): dal suo riepilogo, mai da sola.
  val canChoose = folder == null && state.status is BackupStatus.FirstBackupReady
  var commonOpen by rememberSaveable { mutableStateOf(false) }
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  val openState = stringResource(R.string.backup_hero_detail_open)
  val closedState = stringResource(R.string.backup_hero_detail_closed)
  FluidListGroup {
    FluidListRow(
      title = stringResource(R.string.backup_device_folder_title),
      subtitle = "",
      leading = { GalleryIconTile(spec = GalleryIcons.Nas, status = if (folder == null) GalleryIconStatus.Attention else null) },
      leadingTile = false,
      badge = {
        Text(
          text = folder?.name ?: stringResource(R.string.backup_device_folder_none),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.widthLimit(),
        )
      },
      disclosure = canChoose,
      onClick = if (canChoose) actions.openSheet else null,
    )
    FluidListDivider()
    // Fase 10 (P13, 5A I5, decisione dell'utente del 31/10): la copia privata dei media propri che stanno solo nell'area comune si fa **sempre**,
    // anche senza backup avviato (D9). La riga lo dice con la spunta; il perche' si apre con un tocco.
    FluidListRow(
      title = stringResource(R.string.backup_common_copy_title),
      subtitle = "",
      leading = { GalleryIconTile(icon = Icons.Rounded.FolderShared, family = GalleryIconFamily.Security) },
      leadingTile = false,
      badge = { GalleryStatusMark(status = GalleryIconStatus.Done, size = BackupDimens.CoverMark, ring = MaterialTheme.colorScheme.surfaceContainerLow) },
      disclosure = false,
      onClick = { commonOpen = !commonOpen },
      modifier = Modifier.semantics { stateDescription = if (commonOpen) openState else closedState },
    )
    AnimatedVisibility(
      visible = commonOpen,
      enter = if (reduced) EnterTransition.None else fadeIn(FluidMotion.fadeIn()) + expandVertically(FluidMotion.intSize()),
      exit = if (reduced) ExitTransition.None else fadeOut(FluidMotion.fadeOut()) + shrinkVertically(FluidMotion.intSize()),
    ) {
      Text(
        text = stringResource(R.string.backup_common_copy_detail),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = BackupDimens.CardPadding, end = BackupDimens.CardPadding, bottom = BackupDimens.CardPadding),
      )
    }
    FluidListDivider()
    FluidListRow(
      title = stringResource(R.string.backup_settings_title),
      subtitle = "",
      // Giro 1: nessuna piastrella grigia accanto alle colorate (le impostazioni del backup sono del backup).
      leading = { GalleryIconTile(icon = Icons.Rounded.Tune, family = GalleryIconFamily.Backup) },
      leadingTile = false,
      onClick = { actions.open(BackupScreen.SETTINGS) },
    )
  }
}

/** Il valore a destra di una riga non si prende piu' di meta' riga: il titolo resta leggibile. */
private fun Modifier.widthLimit(): Modifier = this.then(Modifier.widthIn(max = BackupDimens.ValueMaxWidth))

private const val MinColumns = 3
private const val MinColumnsLargeText = 2
private const val LargeTextScale = 1.3f
private const val NameLines = 2
private const val BarTrackAlpha = 0.10f
private const val DimAlpha = 0.45f
private const val HalfTurn = 180f
