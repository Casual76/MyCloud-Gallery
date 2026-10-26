package dev.pampa.mycloudgallery.feature.albums.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumThumbnails

// Le PARTI COMUNI delle schermate degli album (Fase 7, P10): copertina, opzione scelta, striscia, nota. Solo componenti dell'engine e del
// design system (G2); nessun colore, raggio o dimensione di testo scritti a mano (NoHandWrittenStyleTest).

private val NoImage: State<ImageBitmap?> = mutableStateOf(null)

/**
 * La copertina (o la miniatura di un media dato per id): quadrata, con l'angolo continuo, il segnaposto se la miniatura non c'e' ancora o il
 * media non ha una sorgente locale. **La finestra del caricatore la apre chi mostra piu' copertine insieme** (la schermata): qui si legge e basta,
 * perche' `ThumbnailLoader.window` sostituisce tutta la finestra e due copertine che la chiedessero a turno si annullerebbero.
 */
@Composable
internal fun AlbumCoverThumb(
  media: MediaId?,
  thumbnails: AlbumThumbnails,
  modifier: Modifier = Modifier,
  shape: Shape = ContinuousCornerShape(FluidRadius.Control),
  glyph: ImageVector = Icons.Rounded.PhotoAlbum,
) {
  val request = remember(media) { media?.let { thumbnails.refs.request(it) } }
  val image = if (request != null) thumbnails.loader.image(request.key, request.ref) else NoImage
  val bitmap = image.value
  Box(
    modifier = modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
    contentAlignment = Alignment.Center,
  ) {
    if (bitmap != null) {
      Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
    } else {
      Icon(imageVector = glyph, contentDescription = null, modifier = Modifier.size(AlbumDimens.CoverGlyph), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

/** Il segno di un'opzione scelta: un pallino dell'accento con la spunta, o un anello vuoto. Lo stesso dei fogli di conferma. */
@Composable
internal fun RadioMark(selected: Boolean) {
  val scheme = MaterialTheme.colorScheme
  Box(
    modifier = Modifier
      .size(AlbumDimens.Radio)
      .clip(FluidCapsuleShape)
      .then(
        if (selected) {
          Modifier.background(scheme.primary)
        } else {
          Modifier.border(width = AlbumDimens.RadioRing, color = scheme.outline, shape = FluidCapsuleShape)
        },
      ),
    contentAlignment = Alignment.Center,
  ) {
    if (selected) {
      Icon(imageVector = Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(AlbumDimens.RadioCheck), tint = scheme.onPrimary)
    }
  }
}

/**
 * Una riga di una scelta (un livello, la scelta per gli altri, i nascosti): titolo, frase sotto, il pallino a destra. Si legge come un
 * bottone radio (ruolo e stato), e [selected] null vuol dire "nessuna scelta ancora" (Q5): nessuna riga e' segnata.
 */
@Composable
internal fun OptionRow(
  title: String,
  detail: String,
  selected: Boolean,
  onSelect: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  FluidListRow(
    title = title,
    subtitle = detail,
    disclosure = false,
    selected = selected,
    onClick = if (enabled) onSelect else null,
    badge = { RadioMark(selected = selected) },
    modifier = modifier.semantics(mergeDescendants = true) {
      role = Role.RadioButton
      this.selected = selected
    },
  )
}

/** Una nota piccola in fondo a un gruppo o a un foglio: nel secondario, con l'aria di una riga. */
@Composable
internal fun SectionNote(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    modifier = modifier.fillMaxWidth().padding(horizontal = AlbumDimens.Inset, vertical = AlbumDimens.TightGap),
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

/**
 * Una striscia in testa a una pagina: un'icona nella sua piastrella, un titolo e una frase; se [onClick] c'e', anche la freccia. Dentro un gruppo,
 * cosi' piu' strisce stanno incolonnate e non si sovrappongono mai. Il colore e' sulla piastrella, mai sulla riga.
 */
@Composable
internal fun StripRow(
  icon: ImageVector,
  tone: FluidTone,
  title: String,
  detail: String,
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
) {
  FluidListRow(
    title = title,
    subtitle = detail,
    tone = tone,
    leading = { Icon(imageVector = icon, contentDescription = null) },
    onClick = onClick,
    modifier = modifier.heightIn(min = AlbumDimens.Touch),
  )
}

/** Un blocco di testo fermo (una spiegazione, una dichiarazione) con lo spazio di una sezione. */
@Composable
internal fun ExplainerText(text: String, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AlbumDimens.TightGap)) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}
