package dev.pampa.mycloudgallery.feature.people.activation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.feature.people.R
import dev.pampa.mycloudgallery.feature.people.contract.FacesNoticeState

// L'AVVISO UNA TANTUM (docs/fase8-progetto.md §2.1, D4, E-F7): "Hai attivato Persone da un altro tuo dispositivo. Qui vedrai le persone e i loro nomi."
// con "Va bene" e "Non su questo dispositivo" **con lo stesso peso**. Finche' non si sceglie, questo dispositivo non scarica, non calcola e non mostra
// niente: la pagina lo dice. Un testo cambiato torna qui. Nessun consenso si costruisce da questa pagina (e' dell'attivazione): "Va bene" conferma l'avviso
// visto e basta; per rileggere cosa fa Persone c'e' "Leggi come funziona".

@Composable
fun FacesNoticeScreen(
  state: FacesNoticeState,
  onOk: () -> Unit,
  onExclude: () -> Unit,
  onRead: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  FluidScreen(title = stringResource(R.string.faces_notice_title), modifier = modifier, onBack = onBack) {
    when (state) {
      FacesNoticeState.Hidden -> Unit
      is FacesNoticeState.Show -> {
        item(key = "notice") {
          Column(verticalArrangement = Arrangement.spacedBy(ActivationDimens.BlockGap)) {
            FluidInlineMessage(
              message = stringResource(R.string.faces_notice_message),
              title = stringResource(R.string.faces_notice_headline),
              tone = FluidTone.Info,
            )
            state.by?.let { label ->
              Text(
                text = stringResource(R.string.faces_notice_by, label),
                modifier = Modifier.widthIn(max = ActivationDimens.ReadingWidth).fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Text(
              text = stringResource(R.string.faces_notice_waiting),
              modifier = Modifier.widthIn(max = ActivationDimens.ReadingWidth).fillMaxWidth(),
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
        }
        item(key = "buttons") {
          val ok = stringResource(R.string.faces_notice_ok)
          val exclude = stringResource(R.string.faces_notice_exclude)
          val okDescription = stringResource(R.string.faces_notice_ok_description)
          val excludeDescription = stringResource(R.string.faces_notice_exclude_description)
          // Lo stesso peso, la stessa forma, la stessa larghezza: l'avviso non spinge.
          Row(
            modifier = Modifier.fillMaxWidth().padding(top = ActivationDimens.BlockGap),
            horizontalArrangement = Arrangement.spacedBy(ActivationDimens.TightGap),
          ) {
            FluidButton(
              text = exclude,
              onClick = onExclude,
              style = FluidButtonStyle.Tinted,
              size = FluidButtonSize.Large,
              modifier = Modifier.weight(1f).semantics { contentDescription = excludeDescription },
            )
            FluidButton(
              text = ok,
              onClick = onOk,
              style = FluidButtonStyle.Tinted,
              size = FluidButtonSize.Large,
              modifier = Modifier.weight(1f).semantics { contentDescription = okDescription },
            )
          }
        }
        item(key = "read") {
          val description = stringResource(R.string.faces_notice_read_description)
          FluidButton(
            text = stringResource(R.string.faces_notice_read),
            onClick = onRead,
            style = FluidButtonStyle.Plain,
            size = FluidButtonSize.Large,
            modifier = Modifier.padding(bottom = GalleryDimens.Gutter).semantics { contentDescription = description },
          )
        }
      }
    }
  }
}
