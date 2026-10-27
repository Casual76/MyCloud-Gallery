package dev.pampa.mycloudgallery.feature.people.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Face
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.feature.people.R
import dev.pampa.mycloudgallery.feature.people.contract.CoverChoicesUi
import dev.pampa.mycloudgallery.feature.people.contract.PeopleCrops
import dev.pampa.mycloudgallery.feature.people.ui.CancelButton
import dev.pampa.mycloudgallery.feature.people.ui.PeopleDimens
import dev.pampa.mycloudgallery.feature.people.ui.PersonAvatar

// "SCEGLI COPERTINA" (Fase 8, P9; docs/fase8-progetto.md §2.6 "Copertina", D25, D28): i volti della persona (solo di media mostrabili) in una
// griglia di avatar rotondi, dal piu' sicuro; un tocco sceglie. Le miniature si ritagliano al momento e non si salvano. In fondo "Usa
// quella automatica" (la lapide della voce `cover`). Gli avatar sono bersagli quadrati di almeno 48 dp: quattro per riga, mai sovrapposti.

/** I volti per riga di un foglio stretto: con il testo grande ne entrano meno, la riga si allarga sola (`weight`). */
private const val PER_ROW = 4

@Composable
internal fun CoverSheet(choices: CoverChoicesUi?, crops: PeopleCrops, onPick: (FaceLocator?) -> Unit, onDismiss: () -> Unit) {
  val resources = LocalContext.current.resources
  val title = stringResource(R.string.persone_cover_title)
  GalleryConfirmSheet(visible = choices != null, onDismissRequest = onDismiss, paneTitle = title) {
    GallerySheetScaffold(
      icon = Icons.Rounded.Face,
      tone = GallerySheetTone.Brand,
      title = title,
      message = stringResource(R.string.persone_cover_message),
      body = {
        val faces = choices?.faces.orEmpty()
        Column(verticalArrangement = Arrangement.spacedBy(PeopleDimens.TightGap)) {
          faces.chunked(PER_ROW).forEachIndexed { row, group ->
            Row(horizontalArrangement = Arrangement.spacedBy(PeopleDimens.TightGap), modifier = Modifier.fillMaxWidth()) {
              group.forEachIndexed { column, item ->
                val spoken = resources.getString(R.string.persone_cover_face, row * PER_ROW + column + 1, faces.size)
                PersonAvatar(
                  face = item.face,
                  crops = crops,
                  cropSize = PeopleDimens.AvatarHeader,
                  modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .fluidPressable(onClick = { onPick(item.face) }, role = Role.Button)
                    .semantics { contentDescription = spoken },
                )
              }
              repeat(PER_ROW - group.size) { Spacer(Modifier.weight(1f)) }
            }
          }
        }
      },
      actions = {
        FluidButton(
          text = stringResource(R.string.persone_cover_automatic), onClick = { onPick(null) }, style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Large, fillWidth = true,
        )
        CancelButton(onDismiss)
      },
    )
  }
}
