package dev.pampa.mycloudgallery.feature.backup.free

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhonelinkErase
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidRowValue
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceExclusion
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceKind
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpacePlan
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTile
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryProgressRing
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryProgressRingDefaults
import dev.pampa.mycloudgallery.core.designsystem.media.galleryRingColor
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.backup.BackupDimens
import dev.pampa.mycloudgallery.feature.backup.R
import dev.pampa.mycloudgallery.feature.backup.ui.BackupCovers
import dev.pampa.mycloudgallery.feature.backup.ui.BackupText
import dev.pampa.mycloudgallery.feature.backup.ui.LocalBackupCovers

// IL RIEPILOGO DI «LIBERA SPAZIO» (giro 1 della fase I, CU-6 = CS-3). Prima: circa 200 parole, due schede di frasi, tre scelte con il sottotitolo,
// quattro «Non li libero» con la spiegazione, due note. L'utente: «se non sapessi leggere, potrei usare l'app?». Ora:
//
//  - **quanto si libera** come numero grande dentro l'anello della galleria (la parte piena: quanti sono pronti su tutti quelli che si potrebbero
//    togliere; il resto sono gli esclusi qui sotto), quanti sono in una riga, e **le anteprime** di cio' che si toglie (foto gia' al sicuro);
//  - **la garanzia in una riga** con lo scudo; il come (gli ultimi giorni, il cestino) dietro la «i»;
//  - **le scelte** come righe con la piastrella e il segno di scelta (il dettaglio lo sente TalkBack);
//  - **i motivi** per cui alcuni restano: la piastrella del motivo e il numero; il perche' dietro la «i».
//
// L'avviso forte (C13, «Il NAS non ha un backup esterno») resta la scheda piena in cima: e' l'unico rischio vero. Le recinzioni e la logica non
// cambiano: si cambia solo come si mostra. Le anteprime passano dalla Libreria visibile come le copertine del backup (mai un nascosto).

/**
 * La testata del riepilogo: l'anello con quanto si libera al centro, quanti sono, le anteprime e la garanzia. Con niente da liberare l'anello e'
 * vuoto e al centro c'e' la spunta: «Per ora non c'e' niente da liberare».
 */
@Composable
internal fun FreeHero(plan: FreeSpacePlan, modifier: Modifier = Modifier) {
  val container = MaterialTheme.colorScheme.surfaceContainerLow
  val excluded = plan.excluded.values.sum()
  val all = plan.itemCount + excluded
  val fraction = if (all <= 0) 0f else plan.itemCount.toFloat() / all
  val empty = plan.itemCount == 0
  val size = BackupText.size(plan.totalBytes)
  val count = pluralStringResource(R.plurals.backup_free_hero_count, plan.itemCount, BackupText.count(plan.itemCount))
  // Per TalkBack l'anello dice quanto si libera; la riga sotto (quanti sono) si legge da se'.
  val spoken = if (empty) null else stringResource(R.string.backup_free_summary_title, size)
  val ring = GalleryProgressRingDefaults.Hero
  val media = remember(plan) { plan.items.map { it.mediaId } }
  Column(
    modifier = modifier
      .fillMaxWidth()
      .clip(ContinuousCornerShape(FluidRadius.Group))
      .background(container)
      .padding(BackupDimens.HeroPadding),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(BackupDimens.CardGap),
  ) {
    GalleryProgressRing(
      progress = fraction,
      modifier = Modifier.size(ring).semantics { if (spoken != null) contentDescription = spoken },
      color = galleryRingColor(GalleryIconFamily.Backup),
      strokeWidth = GalleryProgressRingDefaults.stroke(ring),
      animate = true,
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clearAndSetSemantics {}) {
        if (empty) {
          Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(BackupDimens.HeroCenterGlyph),
          )
        } else {
          Text(
            text = size,
            style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
          )
          Text(
            text = stringResource(R.string.backup_free_hero_unit),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
          )
        }
      }
    }
    Text(
      text = if (empty) stringResource(R.string.backup_free_summary_empty_title) else count,
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
      textAlign = TextAlign.Center,
      color = MaterialTheme.colorScheme.onSurface,
    )
    if (!empty) {
      FreePreviews(media = media, total = plan.itemCount)
      FreeLine(icon = Icons.Rounded.Shield, text = stringResource(R.string.backup_free_guarantee))
    }
  }
}

/** Una riga con il glifo davanti: la garanzia («ricontrollo ogni copia»). */
@Composable
private fun FreeLine(icon: ImageVector, text: String) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BackupDimens.LineGap)) {
    Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(BackupDimens.GuaranteeGlyph))
    Text(
      text = text,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.weight(1f, fill = false),
    )
  }
}

/**
 * Le anteprime di cio' che si toglie: le prime [FreePreviewCount] foto del piano **che sono nella Libreria visibile adesso** (mai un nascosto ne'
 * un eliminato: lo stesso cancello delle copertine, `BackupCovers.request`, riletto a ogni vista), e «+N» per il resto. Le miniature si chiedono
 * accanto alla finestra della griglia ([dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader.keep]), senza prenderne il posto. Senza le
 * porte delle copertine (test, build senza Libreria) niente anteprime. Per TalkBack il numero lo dice gia' la testata.
 */
@Composable
internal fun FreePreviews(media: List<MediaId>, total: Int, modifier: Modifier = Modifier) {
  val covers = LocalBackupCovers.current ?: return
  val view by covers.view.collectAsState()
  // Col testo grande il «+N» e' piu' largo: un'anteprima in meno, cosi' la riga resta dentro la testata.
  val max = if (LocalDensity.current.fontScale >= LargeTextScale) FreePreviewCount - 1 else FreePreviewCount
  val requests = remember(view, media, max) { freePreviewRequests(media, max) { covers.request(view, it) } }
  DisposableEffect(covers, requests.map { it.key }) {
    covers.loader.keep(FreePreviewOwner, requests)
    onDispose { covers.loader.keep(FreePreviewOwner, emptyList()) }
  }
  if (requests.isEmpty()) return
  Row(
    modifier = modifier.clearAndSetSemantics {},
    horizontalArrangement = Arrangement.spacedBy(BackupDimens.FreePreviewGap, Alignment.CenterHorizontally),
  ) {
    requests.forEach { request -> PreviewThumb(covers, request) }
    val more = total - requests.size
    if (more > 0) {
      Box(
        modifier = Modifier
          .height(BackupDimens.FreePreview)
          .widthIn(min = BackupDimens.FreePreview)
          .clip(ContinuousCornerShape(FluidRadius.Card))
          .background(MaterialTheme.colorScheme.surfaceContainerHighest)
          .padding(horizontal = BackupDimens.LineGap),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = stringResource(R.string.backup_free_preview_more, GalleryNumbers.format(more)),
          style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
        )
      }
    }
  }
}

/** Le richieste delle anteprime: nell'ordine del piano, solo quelle che [request] lascia passare (la Libreria visibile), al piu' [FreePreviewCount]. */
internal fun freePreviewRequests(media: List<MediaId>, max: Int = FreePreviewCount, request: (MediaId) -> ThumbRequest?): List<ThumbRequest> =
  media.asSequence().mapNotNull(request).take(max).toList()

@Composable
private fun PreviewThumb(covers: BackupCovers, request: ThumbRequest) {
  val image = covers.loader.image(request.key, request.ref).value
  Box(
    modifier = Modifier
      .size(BackupDimens.FreePreview)
      .clip(ContinuousCornerShape(FluidRadius.Card))
      .background(MaterialTheme.colorScheme.surfaceContainerHighest),
  ) {
    if (image != null) Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
  }
}

/** Quante anteprime al piu': quattro e il «+N» stanno nella testata sul telefono dell'utente (360 dp: 288 dp dentro la scheda). */
internal const val FreePreviewCount: Int = 4

/** Da qui in su il testo e' «grande» (1,3x): un'anteprima in meno. */
private const val LargeTextScale = 1.3f

/** Chi tiene le richieste delle anteprime accanto alla finestra della griglia. */
private const val FreePreviewOwner = "libera-spazio"

// --- Le scelte -------------------------------------------------------------------------------------------------------------------

/**
 * Una scelta di cosa liberare: la piastrella che dice la cosa (tutto, i video, il vecchio), il nome e il segno di scelta a destra (il tondo vuoto, la
 * spunta piena). Il dettaglio lo sente TalkBack; per lui e' un raggio con il suo stato.
 */
@Composable
internal fun FreeKindRow(kind: FreeSpaceKind, selected: Boolean, onSelect: () -> Unit) {
  val (titleRes, detailRes) = filterTexts(kind)
  val title = stringResource(titleRes)
  val detail = stringResource(detailRes)
  val (icon, family) = when (kind) {
    FreeSpaceKind.ALL -> Icons.Rounded.PhotoLibrary to GalleryIconFamily.Photos
    FreeSpaceKind.VIDEOS_ONLY -> Icons.Rounded.Videocam to GalleryIconFamily.Photos
    FreeSpaceKind.OLDER_THAN_A_YEAR -> Icons.Rounded.History to GalleryIconFamily.Memories
  }
  FluidListRow(
    title = title,
    subtitle = "",
    leading = { GalleryIconTile(icon = icon, family = family) },
    leadingTile = false,
    badge = {
      Icon(
        imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
        contentDescription = null,
        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        modifier = Modifier.size(BackupDimens.RowGlyph),
      )
    },
    selected = selected,
    disclosure = false,
    titleMaxLines = 2,
    onClick = onSelect,
    modifier = Modifier.semantics(mergeDescendants = true) {
      role = Role.RadioButton
      this.selected = selected
      contentDescription = "$title. $detail"
    },
  )
}

/** Il titolo e il dettaglio di ogni scelta. */
internal fun filterTexts(kind: FreeSpaceKind): Pair<Int, Int> = when (kind) {
  FreeSpaceKind.ALL -> R.string.backup_free_filter_all to R.string.backup_free_filter_all_detail
  FreeSpaceKind.VIDEOS_ONLY -> R.string.backup_free_filter_videos to R.string.backup_free_filter_videos_detail
  FreeSpaceKind.OLDER_THAN_A_YEAR -> R.string.backup_free_filter_old to R.string.backup_free_filter_old_detail
}

// --- Chi resta e perche' -------------------------------------------------------------------------------------------------------

/** Un motivo per cui alcuni restano sul telefono: la piastrella del motivo, il nome corto e il numero a destra. Il perche' lo sente TalkBack. */
@Composable
internal fun ExclusionRow(reason: FreeSpaceExclusion, count: Int) {
  val (titleRes, detailRes) = exclusionText(reason)
  val title = stringResource(titleRes)
  val detail = stringResource(detailRes)
  val (icon, family) = exclusionLook(reason)
  val value = BackupText.count(count)
  FluidListRow(
    title = title,
    subtitle = "",
    leading = { GalleryIconTile(icon = icon, family = family) },
    leadingTile = false,
    badge = { FluidRowValue(text = value) },
    disclosure = false,
    titleMaxLines = 2,
    modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$title: $value. $detail" },
  )
}

/**
 * La piastrella di ogni motivo: la cosa che lo ferma, nella sua famiglia. L'attesa ha la clessidra (aspettano il backup, o la risposta del
 * telefono), la pausa le due barre (il backup vero non e' ancora acceso). Nessuna piastrella grigia (regola comune del giro 1). Un `when`
 * esaustivo: un motivo nuovo non compila senza la sua.
 */
internal fun exclusionLook(reason: FreeSpaceExclusion): Pair<ImageVector, GalleryIconFamily> = when (reason) {
  FreeSpaceExclusion.NOT_VERIFIED -> Icons.Rounded.HourglassTop to GalleryIconFamily.Backup
  FreeSpaceExclusion.VERSION_NOT_SAFE -> Icons.Rounded.Layers to GalleryIconFamily.Photos
  FreeSpaceExclusion.IN_PROGRESS_OR_ERROR -> Icons.Rounded.CloudUpload to GalleryIconFamily.Backup
  FreeSpaceExclusion.AREA_FROZEN -> Icons.Rounded.AcUnit to GalleryIconFamily.Danger
  FreeSpaceExclusion.NAS_UNREACHABLE -> Icons.Rounded.CloudOff to GalleryIconFamily.Network
  FreeSpaceExclusion.OUTSIDE_TEST_FENCE -> Icons.Rounded.Pause to GalleryIconFamily.Backup
  FreeSpaceExclusion.RECENT -> Icons.Rounded.Today to GalleryIconFamily.Memories
  FreeSpaceExclusion.COPY_CHANGED -> Icons.Rounded.SyncProblem to GalleryIconFamily.Backup
  FreeSpaceExclusion.GONE_FROM_PHONE -> Icons.Rounded.PhonelinkErase to GalleryIconFamily.Photos
  FreeSpaceExclusion.NOT_MINE -> Icons.Rounded.Group to GalleryIconFamily.People
  FreeSpaceExclusion.BACKUP_NOT_ACTIVE -> Icons.Rounded.Backup to GalleryIconFamily.Backup
  FreeSpaceExclusion.AWAITING_PHONE -> Icons.Rounded.HourglassTop to GalleryIconFamily.Backup
}

/** I media propri con la sola copia comune (D9): prima la copia privata. Una riga con la piastrella e il numero, come i motivi. */
@Composable
internal fun GuardianRow(count: Int) {
  val title = stringResource(R.string.backup_free_guardian_title)
  val value = BackupText.count(count)
  val detail = stringResource(R.string.backup_free_guardian_note, value)
  FluidListRow(
    title = title,
    subtitle = "",
    leading = { GalleryIconTile(icon = Icons.Rounded.FolderShared, family = GalleryIconFamily.Security) },
    leadingTile = false,
    badge = { FluidRowValue(text = value) },
    disclosure = false,
    titleMaxLines = 2,
    modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = detail },
  )
}
