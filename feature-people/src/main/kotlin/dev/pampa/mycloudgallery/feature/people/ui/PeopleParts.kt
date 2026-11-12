package dev.pampa.mycloudgallery.feature.people.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryCoverCard
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryOnPhoto
import dev.pampa.mycloudgallery.core.faces.contract.PersonCard
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.feature.people.R
import dev.pampa.mycloudgallery.feature.people.contract.PeopleCrops
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryInfoButton
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryInfoDetail
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryFigureState
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryCoverCardDefaults

// Le PARTI COMUNI delle schermate delle persone (Fase 8, P9): l'avatar rotondo (il volto ritagliato al momento, mai salvato), la tessera di una
// persona, la nota. Solo componenti dell'engine e del design system (G2); nessun colore, raggio o dimensione di testo scritti a mano
// (NoHandWrittenStyleTest).
// I1 (fase I, 11/11): l'utente ha scelto le persone "come Samsung": la tessera e' una **scheda alta con il volto a tutta scheda** e il nome in basso
// sopra una sfumatura (la coppia di `GalleryOnPhoto`, che si legge anche su una foto chiara); niente conto delle foto (lo dice TalkBack); una persona
// senza nome mostra solo il volto. La scelta e' un filo dell'accento attorno alla scheda e un segno nell'angolo in alto.

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
  val scheme = MaterialTheme.colorScheme
  Box(
    modifier = modifier
      .clip(FluidCapsuleShape)
      .background(scheme.surfaceContainerHigh)
      .then(if (ring) Modifier.border(PeopleDimens.Ring, scheme.primary, FluidCapsuleShape) else Modifier),
    contentAlignment = Alignment.Center,
  ) {
    FaceImage(face, crops, cropSize)
  }
}

/**
 * Il volto ritagliato di [face] (chiesto a [crops] alla taglia di [cropSize], tra [PeopleDimens.CropMinPx] e [PeopleDimens.CropMaxPx]) a tutta la scatola,
 * o la figura di una persona finche' non c'e' (o se il cancello lo rifiuta).
 */
@Composable
private fun BoxScope.FaceImage(face: FaceLocator?, crops: PeopleCrops, cropSize: Dp) {
  val density = LocalDensity.current
  val px = with(density) { cropSize.roundToPx() }.coerceIn(PeopleDimens.CropMinPx, PeopleDimens.CropMaxPx)
  val image by produceState<ImageBitmap?>(initialValue = null, face, px, crops) {
    value = if (face == null) null else crops.crop(face, px)?.asImageBitmap()
  }
  val bitmap = image
  if (bitmap != null) {
    Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
  } else {
    Icon(
      imageVector = Icons.Rounded.Person,
      contentDescription = null,
      modifier = Modifier.align(Alignment.Center).size(PeopleDimens.AvatarGlyph),
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/**
 * La tessera di una persona nelle griglie (I1: "come Samsung"): una scheda alta con il volto a tutta scheda e il nome in basso sopra la sfumatura; una
 * persona senza nome, solo il volto. Tutta la scheda si tocca (apre la pagina, o in selezione sceglie); la pressione lunga la sceglie. Per TalkBack e'
 * una cosa sola: "Marco, 120 foto" (e in selezione scelta/non scelta), con l'azione "Seleziona" anche senza pressione lunga.
 */
@Composable
internal fun PersonTile(
  card: PersonCard,
  crops: PeopleCrops,
  selection: TileSelection,
  onClick: () -> Unit,
  onLongClick: (() -> Unit)?,
  modifier: Modifier = Modifier,
  fadeHeight: Dp = GalleryCoverCardDefaults.FadeHeight,
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
  val shape = ContinuousCornerShape(FluidRadius.Card)
  // Giro 1 (CU-2, CU-21, CS-5): la scheda comune delle copertine (core-designsystem), cosi' il nome sopra il volto ha la stessa sfumatura, provata
  // su una copertina bianca, di Ricordi, Viaggi e Luoghi. La descrizione della scheda dice il nome e le foto: il nome scritto non si rilegge (il
  // valore di serie della scheda comune).
  GalleryCoverCard(
    modifier = modifier
      .aspectRatio(PeopleDimens.CardAspect)
      .fluidPressable(onClick = onClick, onLongClick = onLongClick, role = if (selection == TileSelection.NONE) Role.Button else Role.Checkbox)
      .semantics(mergeDescendants = true) {
        contentDescription = spoken
        customActions = actions
        if (selection != TileSelection.NONE) {
          selected = selection == TileSelection.ON
          toggleableState = if (selection == TileSelection.ON) ToggleableState.On else ToggleableState.Off
        }
      }
      .then(if (selection == TileSelection.ON) Modifier.border(PeopleDimens.Ring, scheme.primary, shape) else Modifier)
      .clip(shape)
      .background(scheme.surfaceContainerHigh),
    title = card.name,
    titleMaxLines = 1,
    fadeHeight = fadeHeight,
  ) {
    FaceImage(card.cover, crops, PeopleDimens.CardCrop)
    if (selection != TileSelection.NONE) {
      Box(Modifier.align(Alignment.TopEnd).padding(PeopleDimens.CardInset)) { RadioMark(selected = selection == TileSelection.ON, onPhoto = true) }
    }
  }
}

/** Una nota piccola in fondo a un gruppo o a una pagina: nel secondario, con l'aria di una riga. */
/**
 * Giro 1 (CU-2): l'altezza di una scheda di persona in una griglia di [columns] colonne larga [contentWidth] con [gap] fra le schede (la scheda e'
 * 3:4); [Dp.Unspecified] se la larghezza non e' ancora misurata.
 */
internal fun personCardHeight(contentWidth: Dp, columns: Int, gap: Dp): Dp {
  if (contentWidth == Dp.Unspecified || columns < 1) return Dp.Unspecified
  val width = (contentWidth - gap * (columns - 1)) / columns
  return width / PeopleDimens.CardAspect
}

/**
 * Giro 1 (CU-2): la sfumatura sopra il nome di una scheda alta [height]. Dalla misura, non dalla pagina: sotto [PeopleDimens.CompactCardBelow] quella
 * corta ([GalleryCoverCardDefaults.FadeHeightCompact]), perche' la bocca del volto resti fuori; altrimenti quella di serie. Lo scrim dietro il nome e'
 * pieno in entrambi i casi (il contrasto non cambia).
 */
internal fun personFadeHeight(height: Dp): Dp =
  if (height != Dp.Unspecified && height < PeopleDimens.CompactCardBelow) GalleryCoverCardDefaults.FadeHeightCompact else GalleryCoverCardDefaults.FadeHeight

/**
 * Giro 1 (CU-23): uno stato vuoto (o di fine) delle pagine delle persone nella forma unica della galleria ([GalleryFigureState]): la figura, il titolo,
 * al piu' [action]. Il perche' ([why]), dove spiega una regola (le foto nascoste, i tre volti, che cosa fa nascondere), sta dietro la «i»
 * ([GalleryInfoButton]): un tocco lo apre sotto, TalkBack lo dice nella descrizione della «i». Senza [why], niente «i».
 */
@Composable
internal fun PeopleFigureState(
  icon: ImageVector,
  title: String,
  why: String? = null,
  action: (@Composable () -> Unit)? = null,
) {
  GalleryFigureState(
    icon = icon,
    title = title,
    fill = false,
    modifier = Modifier.fillMaxWidth().padding(vertical = PeopleDimens.StatePadding),
    action = if (why == null && action == null) {
      null
    } else {
      {
        if (why != null) FigureWhy(why)
        action?.invoke()
      }
    },
  )
}

/** La «i» di uno stato e, aperta, la frase sotto. */
@Composable
private fun FigureWhy(why: String) {
  var open by rememberSaveable(why) { mutableStateOf(false) }
  GalleryInfoButton(label = why, open = open, onToggle = { open = !open })
  GalleryInfoDetail(text = why, visible = open)
}

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

/**
 * Il segno di un'opzione scelta: un pallino dell'accento con la spunta, o un anello vuoto. Lo stesso dei fogli di conferma. [onPhoto]: sopra un volto
 * l'anello vuoto e' chiaro su un velo scuro, cosi' si vede su qualunque foto.
 */
@Composable
internal fun RadioMark(selected: Boolean, onPhoto: Boolean = false) {
  val scheme = MaterialTheme.colorScheme
  Box(
    modifier = Modifier
      .size(PeopleDimens.Radio)
      .clip(FluidCapsuleShape)
      .then(
        when {
          selected -> Modifier.background(scheme.primary)
          onPhoto -> Modifier.background(GalleryOnPhoto.scrim()).border(width = PeopleDimens.RadioRing, color = GalleryOnPhoto.Content, shape = FluidCapsuleShape)
          else -> Modifier.border(width = PeopleDimens.RadioRing, color = scheme.outline, shape = FluidCapsuleShape)
        },
      ),
    contentAlignment = Alignment.Center,
  ) {
    if (selected) {
      Icon(imageVector = Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(PeopleDimens.RadioCheck), tint = scheme.onPrimary)
    }
  }
}
