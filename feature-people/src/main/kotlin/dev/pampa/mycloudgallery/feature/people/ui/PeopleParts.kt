package dev.pampa.mycloudgallery.feature.people.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.mycloudgallery.core.faces.contract.PersonCard
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.feature.people.R
import dev.pampa.mycloudgallery.feature.people.contract.PeopleCrops

// Le PARTI COMUNI delle schermate delle persone (Fase 8, P9): l'avatar rotondo (il volto ritagliato al momento, mai salvato), la tessera di una
// persona, la nota. Solo componenti dell'engine e del design system (G2); nessun colore, raggio o dimensione di testo scritti a mano
// (NoHandWrittenStyleTest). **Mai testo sopra la foto**: il nome e il conto stanno sotto l'avatar, su una superficie piena, e i controlli non
// si sovrappongono (la scelta e' un anello e una spunta accanto al nome, mai un pallino sul volto).

/** Come una tessera sta nella selezione: non si sta scegliendo, si sta scegliendo ed e' fuori, si sta scegliendo ed e' dentro. */
internal enum class TileSelection { NONE, OFF, ON }

/**
 * L'avatar rotondo di un volto: il ritaglio di [face] (chiesto a [crops] alla taglia di [cropSize], mai salvato), o un segnaposto con
 * la figura se il volto non c'e' o non si puo' mostrare. [ring] = scelto. Il lato lo decide chi chiama con [modifier].
 */
@Composable
internal fun PersonAvatar(
  face: FaceLocator?,
  crops: PeopleCrops,
  cropSize: Dp,
  modifier: Modifier = Modifier,
  ring: Boolean = false,
) {
  val density = LocalDensity.current
  val px = with(density) { cropSize.roundToPx() }.coerceIn(PeopleDimens.CropMinPx, PeopleDimens.CropMaxPx)
  val image by produceState<ImageBitmap?>(initialValue = null, face, px, crops) {
    value = if (face == null) null else crops.crop(face, px)?.asImageBitmap()
  }
  val scheme = MaterialTheme.colorScheme
  Box(
    modifier = modifier
      .clip(FluidCapsuleShape)
      .background(scheme.surfaceContainerHigh)
      .then(if (ring) Modifier.border(PeopleDimens.Ring, scheme.primary, FluidCapsuleShape) else Modifier),
    contentAlignment = Alignment.Center,
  ) {
    val bitmap = image
    if (bitmap != null) {
      Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
    } else {
      Icon(imageVector = Icons.Rounded.Person, contentDescription = null, modifier = Modifier.size(PeopleDimens.AvatarGlyph), tint = scheme.onSurfaceVariant)
    }
  }
}

/**
 * La tessera di una persona nelle griglie: l'avatar, sotto il nome (o l'invito "Aggiungi un nome") e il conto delle foto. Tutta la tessera si
 * tocca (apre la pagina, o in selezione sceglie); la pressione lunga la sceglie. Per TalkBack e' una cosa sola: "Marco, 120 foto" (e in
 * selezione scelta/non scelta), con l'azione "Seleziona" anche senza pressione lunga.
 */
@Composable
internal fun PersonTile(
  card: PersonCard,
  crops: PeopleCrops,
  selection: TileSelection,
  onClick: () -> Unit,
  onLongClick: (() -> Unit)?,
  modifier: Modifier = Modifier,
) {
  val resources = LocalContext.current.resources
  val scheme = MaterialTheme.colorScheme
  val spoken = PeopleText.spoken(resources, card)
  val select = resources.getString(R.string.persone_action_select)
  val actions = if (onLongClick != null && selection == TileSelection.NONE) {
    listOf(
      CustomAccessibilityAction(select) {
        onLongClick()
        true
      },
    )
  } else {
    emptyList()
  }
  Column(
    modifier = modifier
      .heightIn(min = PeopleDimens.Touch)
      .fluidPressable(onClick = onClick, onLongClick = onLongClick, role = if (selection == TileSelection.NONE) Role.Button else Role.Checkbox)
      .semantics(mergeDescendants = true) {
        contentDescription = spoken
        customActions = actions
        if (selection != TileSelection.NONE) {
          selected = selection == TileSelection.ON
          toggleableState = if (selection == TileSelection.ON) ToggleableState.On else ToggleableState.Off
        }
      },
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(PeopleDimens.TightGap),
  ) {
    PersonAvatar(
      face = card.cover,
      crops = crops,
      cropSize = PeopleDimens.AvatarHeader,
      modifier = Modifier.fillMaxWidth().aspectRatio(1f),
      ring = selection == TileSelection.ON,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PeopleDimens.TightGap / 2)) {
      if (selection == TileSelection.ON) {
        Icon(imageVector = Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(PeopleDimens.CheckGlyph), tint = scheme.primary)
      }
      Text(
        text = PeopleText.nameOrInvite(resources, card),
        style = MaterialTheme.typography.titleSmall,
        color = if (card.name == null) scheme.primary else scheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
      )
    }
    Text(
      text = PeopleText.photos(resources, card.photos),
      style = MaterialTheme.typography.bodySmall,
      color = scheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/** Una nota piccola in fondo a un gruppo o a una pagina: nel secondario, con l'aria di una riga. */
@Composable
internal fun SectionNote(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    modifier = modifier.fillMaxWidth().padding(horizontal = PeopleDimens.Inset, vertical = PeopleDimens.TightGap),
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

/** "Annulla" in fondo a un foglio: un bottone semplice da 50 dp (sopra il bersaglio da 48), senza riempimento. */
@Composable
internal fun CancelButton(onClick: () -> Unit, label: String = stringResource(R.string.persone_cancel)) {
  FluidButton(text = label, onClick = onClick, style = FluidButtonStyle.Plain, size = FluidButtonSize.Large, fillWidth = true)
}

/** Il segno di un'opzione scelta: un pallino dell'accento con la spunta, o un anello vuoto. Lo stesso dei fogli di conferma. */
@Composable
internal fun RadioMark(selected: Boolean) {
  val scheme = MaterialTheme.colorScheme
  Box(
    modifier = Modifier
      .size(PeopleDimens.Radio)
      .clip(FluidCapsuleShape)
      .then(
        if (selected) {
          Modifier.background(scheme.primary)
        } else {
          Modifier.border(width = PeopleDimens.RadioRing, color = scheme.outline, shape = FluidCapsuleShape)
        },
      ),
    contentAlignment = Alignment.Center,
  ) {
    if (selected) {
      Icon(imageVector = Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(PeopleDimens.RadioCheck), tint = scheme.onPrimary)
    }
  }
}
