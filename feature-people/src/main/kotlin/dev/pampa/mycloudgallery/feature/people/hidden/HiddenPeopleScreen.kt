package dev.pampa.mycloudgallery.feature.people.hidden

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.ui.fluid.FluidLoadingBlock
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.people.R
import dev.pampa.mycloudgallery.feature.people.contract.HiddenPeopleState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleCrops
import dev.pampa.mycloudgallery.feature.people.contract.PeopleMessage
import dev.pampa.mycloudgallery.feature.people.nav.PeopleDependencies
import dev.pampa.mycloudgallery.feature.people.ui.PeopleDimens
import dev.pampa.mycloudgallery.feature.people.ui.PeopleHost
import dev.pampa.mycloudgallery.feature.people.ui.PeopleMessageBlock
import dev.pampa.mycloudgallery.feature.people.ui.PeopleText
import dev.pampa.mycloudgallery.feature.people.ui.PersonAvatar
import javax.inject.Inject
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIcons
import dev.pampa.mycloudgallery.feature.people.ui.PeopleFigureState

// "PERSONE NASCOSTE" (Fase 8, P9; docs/fase8-progetto.md E-F28): Impostazioni -> Persone -> "Persone nascoste". Le persone che hai nascosto, come righe
// di un gruppo con il volto, il nome (o "Persona senza nome") e il conto; un tocco le mostra di nuovo. Le loro foto non sono mai state tolte dalla
// Libreria. Una persona con soli media nascosti non e' qui (D28). Solo componenti G2.

internal interface HiddenPeopleHandlers {
  fun onBack()

  fun onShow(id: PersonId)

  fun onDismissNotice()
}

@Composable
internal fun HiddenPeopleScreen(
  state: HiddenPeopleState,
  notice: PeopleMessage?,
  crops: PeopleCrops,
  handlers: HiddenPeopleHandlers,
  modifier: Modifier = Modifier,
  listState: LazyListState = rememberLazyListState(),
) {
  val resources = LocalContext.current.resources
  FluidScreen(
    title = stringResource(R.string.persone_hidden_page_title),
    modifier = modifier,
    onBack = handlers::onBack,
    listState = listState,
    contentMaxWidth = PeopleDimens.SheetWidth,
  ) {
    notice?.let { message -> item(key = "message") { PeopleMessageBlock(message, handlers::onDismissNotice) } }
    when (state) {
      HiddenPeopleState.Loading -> item(key = "loading") { FluidLoadingBlock() }
      HiddenPeopleState.Empty -> item(key = "empty") {
        PeopleFigureState(
          icon = GalleryIcons.Hidden.icon,
          title = stringResource(R.string.persone_hidden_empty_title),
          why = stringResource(R.string.persone_hidden_empty_detail),
        )
      }
      is HiddenPeopleState.Ready -> item(key = "people") {
        FluidListGroup {
          state.people.forEachIndexed { index, card ->
            if (index > 0) FluidListDivider()
            val show = resources.getString(R.string.persone_show_again)
            FluidListRow(
              title = PeopleText.title(resources, card),
              // I1 (fase I): il volto e il nome; quante foto ha lo dice TalkBack (la descrizione della riga).
              subtitle = "",
              disclosure = false,
              onClick = { handlers.onShow(card.id) },
              leading = { PersonAvatar(card.cover, crops, PeopleDimens.AvatarRow, Modifier.size(PeopleDimens.AvatarRow)) },
              // Giro 1 (CU-8): il volto tondo da solo, senza la piastrella grigia dietro.
              leadingTile = false,
              badge = { Text(text = show, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
              modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = resources.getString(R.string.persone_hidden_spoken, PeopleText.spoken(resources, card))
                customActions = listOf(CustomAccessibilityAction(show) { handlers.onShow(card.id); true })
              },
            )
          }
        }
      }
    }
  }
}

@HiltViewModel
class HiddenPeopleViewModel @Inject constructor(ports: PeopleDependencies) : ViewModel() {
  val controller: HiddenPeopleController = HiddenPeopleController(ports.hidden, ports.people, viewModelScope)
}

@Composable
fun HiddenPeopleRoute(
  crops: PeopleCrops,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: HiddenPeopleViewModel = hiltViewModel(),
) {
  val controller = viewModel.controller
  val state by controller.ui.collectAsState()
  val notice by controller.notice.collectAsState()
  val handlers = remember(controller, onBack) {
    val goBack = onBack
    object : HiddenPeopleHandlers {
      override fun onBack() = goBack()

      override fun onShow(id: PersonId) = controller.show(id)

      override fun onDismissNotice() = controller.dismissNotice()
    }
  }
  PeopleHost { HiddenPeopleScreen(state = state, notice = notice, crops = crops, handlers = handlers, modifier = modifier) }
}
