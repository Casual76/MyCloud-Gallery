package dev.pampa.mycloudgallery.feature.settings.family

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue

/** La pagina del kit di recupero con lo stato della famiglia (per sapere se si e' admin). */
@Composable
internal fun KitRoute(onBack: () -> Unit, onSecondAdmin: () -> Unit, onReplaceAdmin: () -> Unit) {
  val viewModel: FamilyViewModel = hiltViewModel()
  val state by viewModel.state.collectAsStateWithLifecycle()
  RecoveryKitScreen(
    isAdmin = state.isAdmin,
    onBack = onBack,
    onSecondAdmin = onSecondAdmin,
    onReplaceAdmin = onReplaceAdmin,
  )
}
