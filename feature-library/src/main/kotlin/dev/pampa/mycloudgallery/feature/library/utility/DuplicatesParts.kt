package dev.pampa.mycloudgallery.feature.library.utility

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterNote
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterPill
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterTone
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.library.duplicates.BlockReason
import dev.pampa.mycloudgallery.core.library.duplicates.CopyArea
import dev.pampa.mycloudgallery.core.library.duplicates.IdentCopy
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.collections.CollectionsDimens

// Fase 7, P8 (D25): i pezzi comuni delle schede dei Doppioni: la miniatura, le etichette dei posti, le righe di avviso. Solo disegno: nessuna logica.

/** La miniatura di [media] a [side], dalla finestra di richieste che la pagina fa ([DuplicatesWindow]). Senza riferimento leggibile, un segnaposto. */
@Composable
internal fun MediaThumb(media: MediaId, loader: ThumbnailLoader, refOf: (MediaId) -> MediaRef?, side: Dp, modifier: Modifier = Modifier) {
  val ref = refOf(media)
  val shape = ContinuousCornerShape(FluidRadius.Small)
  val image = if (ref != null) loader.image(duplicatesThumbKey(media), ref).value else null
  Box(
    modifier = modifier.size(side).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
    contentAlignment = Alignment.Center,
  ) {
    if (image != null) Image(bitmap = image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
  }
}

/** La chiave della miniatura di un media nei Doppioni: il media, nessuna versione (la miniatura segue il media), la taglia M. */
internal fun duplicatesThumbKey(media: MediaId): ThumbKey = ThumbKey(mediaId = media, versionKey = 0L, bucket = ThumbBucket.M)

/** La richiesta di una miniatura, se il media ha un riferimento leggibile. */
internal fun thumbRequest(media: MediaId, refOf: (MediaId) -> MediaRef?): ThumbRequest? = refOf(media)?.let { ThumbRequest(duplicatesThumbKey(media), it) }

/** Il nome di un posto: "Telefono", "NAS · la tua area", "NAS · area comune", "NAS · area di Marco". */
@Composable
internal fun areaLabel(copy: IdentCopy, familyName: (String) -> String?): String = when (copy.area) {
  CopyArea.PHONE -> stringResource(R.string.duplicates_area_phone)
  CopyArea.MY_PRIVATE -> stringResource(R.string.duplicates_area_private)
  CopyArea.COMMON -> stringResource(R.string.duplicates_area_common)
  CopyArea.OTHERS -> stringResource(R.string.duplicates_area_others, ownerName(copy.share, familyName))
}

/** Il nome del familiare di una share, o "un altro familiare" se la famiglia non lo sa. */
@Composable
internal fun ownerName(share: String?, familyName: (String) -> String?): String =
  share?.let(familyName) ?: stringResource(R.string.duplicates_other_family)

/** Perche' una copia resta dov'e'. */
@Composable
internal fun blockedText(reason: BlockReason): String = stringResource(
  when (reason) {
    BlockReason.OTHER_AREA -> R.string.duplicates_blocked_other_area
    BlockReason.LAST_PRIVATE -> R.string.duplicates_blocked_last_private
    BlockReason.SHARED_ALBUM -> R.string.duplicates_blocked_shared_album
  },
)

/** Una pillola di stato (la parola c'e' sempre: il colore non e' mai l'unico segnale). */
@Composable
internal fun RolePill(text: String, tone: GalleryFooterTone) {
  GalleryFooterPill(note = GalleryFooterNote(tone, text), onClick = null)
}

/** L'intestazione di una sezione della pagina, con una frase sotto se serve. */
@Composable
internal fun SectionTitle(title: String, detail: String? = null, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(start = GalleryDimens.Gutter, end = GalleryDimens.Gutter, top = UtilityDimens.SectionTop, bottom = UtilityDimens.SectionBottom),
    verticalArrangement = Arrangement.spacedBy(UtilityDimens.SectionBottom),
  ) {
    Text(
      text = title,
      modifier = Modifier.semantics { heading() },
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (detail != null) {
      Text(text = detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

/** Un avviso a tutta larghezza, con un'azione se serve. */
@Composable
internal fun PageMessage(
  title: String,
  detail: String,
  tone: FluidTone,
  modifier: Modifier = Modifier,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
  onDismiss: (() -> Unit)? = null,
) {
  Column(modifier = modifier.fillMaxWidth()) {
    FluidInlineMessage(
      message = detail,
      title = title,
      modifier = Modifier.fillMaxWidth().padding(horizontal = GalleryDimens.Gutter, vertical = CollectionsDimens.ExplainerGap),
      tone = tone,
      onDismiss = onDismiss,
    )
    if (actionLabel != null && onAction != null) {
      FluidButton(
        text = actionLabel,
        onClick = onAction,
        style = FluidButtonStyle.Tinted,
        size = FluidButtonSize.Large,
        modifier = Modifier.padding(horizontal = GalleryDimens.Gutter),
      )
    }
  }
}

/** "3 copie" / "1 copia" nello stile del modulo (il numero da `GalleryNumbers`). */
@Composable
internal fun copiesText(count: Int): String = pluralStringResource(R.plurals.duplicates_group_copies, count, GalleryNumbers.format(count))
