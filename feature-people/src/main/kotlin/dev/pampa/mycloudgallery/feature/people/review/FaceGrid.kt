package dev.pampa.mycloudgallery.feature.people.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.mycloudgallery.feature.people.contract.PeopleCrops
import dev.pampa.mycloudgallery.feature.people.contract.ReviewFace
import dev.pampa.mycloudgallery.feature.people.ui.PeopleDimens
import dev.pampa.mycloudgallery.feature.people.ui.PersonAvatar

// LA GRIGLIA DEI VOLTI (Fase 8, P9; docs/fase8-progetto.md §2.6 "Accessibilita'": la selezione dei volti ha le azioni di TalkBack "Seleziona" e
// "Non e' …", bersagli da 48 dp, testo a 1,3x). Un volto = un avatar rotondo ritagliato al momento (mai salvato) con **sotto** una riga di
// altezza fissa: l'etichetta (il gruppo, in "Altri volti") o, se scelto, la spunta e "Scelto". La riga c'e' sempre, cosi' scegliere un volto
// non sposta la griglia; niente sul volto: la scelta e' un anello e una spunta accanto al testo, mai un pallino sopra l'immagine.

/** Una tessera della griglia dei volti. [label] sotto l'avatar (null = niente); [spoken] cio' che TalkBack legge; [extraActions] le sue azioni in piu'. */
@Composable
internal fun FaceTile(
  face: ReviewFace,
  crops: PeopleCrops,
  selected: Boolean,
  spoken: String,
  label: String?,
  selectedLabel: String,
  selectLabel: String,
  extraActions: List<CustomAccessibilityAction>,
  onClick: () -> Unit,
  onLongClick: (() -> Unit)?,
  modifier: Modifier = Modifier,
) {
  val scheme = MaterialTheme.colorScheme
  val actions = buildList {
    add(
      // "Seleziona" e' la pressione lunga per chi non la fa: dove un tocco apre qualcosa ("Altri volti") entra nella selezione, altrove sceglie il volto.
      CustomAccessibilityAction(selectLabel) {
        (onLongClick ?: onClick)()
        true
      },
    )
    addAll(extraActions)
  }
  Column(
    modifier = modifier
      .heightIn(min = PeopleDimens.Touch)
      .fluidPressable(onClick = onClick, onLongClick = onLongClick, role = Role.Checkbox)
      .semantics(mergeDescendants = true) {
        contentDescription = spoken
        this.selected = selected
        toggleableState = if (selected) ToggleableState.On else ToggleableState.Off
        customActions = actions
      },
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(PeopleDimens.TightGap / 2),
  ) {
    PersonAvatar(face.face, crops, PeopleDimens.AvatarHeader, Modifier.fillMaxWidth().aspectRatio(1f), ring = selected)
    Row(
      modifier = Modifier.fillMaxWidth().heightIn(min = PeopleDimens.CheckGlyph),
      horizontalArrangement = Arrangement.spacedBy(PeopleDimens.TightGap / 2, Alignment.CenterHorizontally),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (selected) {
        Icon(imageVector = Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(PeopleDimens.CheckGlyph), tint = scheme.primary)
        Text(text = selectedLabel, style = MaterialTheme.typography.labelMedium, color = scheme.primary, maxLines = 1)
      } else if (label != null) {
        Text(
          text = label,
          style = MaterialTheme.typography.labelMedium,
          color = scheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          textAlign = TextAlign.Center,
        )
      } else {
        Spacer(Modifier.height(PeopleDimens.CheckGlyph))
      }
    }
  }
}

/** Le righe della griglia dei volti: [columns] tessere per riga; [tile] disegna una tessera. */
internal fun LazyListScope.faceGrid(
  key: String,
  faces: List<ReviewFace>,
  columns: Int,
  tile: @Composable (index: Int, face: ReviewFace, modifier: Modifier) -> Unit,
) {
  val rows = faces.chunked(columns)
  items(rows.size, key = { "$key-row-$it" }) { rowIndex ->
    Row(horizontalArrangement = Arrangement.spacedBy(PeopleDimens.BlockGap), modifier = Modifier.fillMaxWidth()) {
      val row = rows[rowIndex]
      row.forEachIndexed { column, face -> tile(rowIndex * columns + column, face, Modifier.weight(1f)) }
      repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
    }
  }
}
