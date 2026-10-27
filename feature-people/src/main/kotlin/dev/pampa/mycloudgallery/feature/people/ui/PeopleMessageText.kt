package dev.pampa.mycloudgallery.feature.people.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.feature.people.R
import dev.pampa.mycloudgallery.feature.people.contract.PeopleMessage

/** Il testo di un messaggio lasciato da un'azione (il contratto dice solo il codice, D38). */
@Composable
internal fun peopleMessageText(message: PeopleMessage): String = stringResource(
  when (message) {
    PeopleMessage.NOT_ACTIVE -> R.string.persone_message_not_active
    PeopleMessage.NOT_FOUND -> R.string.persone_message_not_found
    PeopleMessage.INVALID_NAME -> R.string.persone_message_invalid_name
    PeopleMessage.TOO_MANY_NAMES -> R.string.persone_message_too_many_names
    PeopleMessage.INVALID -> R.string.persone_message_invalid
  },
)

/** Il messaggio in testa a una schermata: un riquadro dell'engine che si chiude con un tocco, con lo spazio sotto. */
@Composable
internal fun PeopleMessageBlock(message: PeopleMessage, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
  FluidInlineMessage(
    message = peopleMessageText(message),
    title = stringResource(R.string.persone_message_title),
    tone = FluidTone.Warning,
    onDismiss = onDismiss,
    modifier = modifier.fillMaxWidth().padding(vertical = PeopleDimens.TightGap / 2),
  )
}
