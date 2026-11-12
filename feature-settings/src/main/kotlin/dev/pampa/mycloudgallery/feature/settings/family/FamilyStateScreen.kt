package dev.pampa.mycloudgallery.feature.settings.family

import dev.pampa.mycloudgallery.core.designsystem.state.GalleryFigureState
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustrationTone
import androidx.compose.material.icons.rounded.CloudOff
import dev.pampa.mycloudgallery.feature.settings.ui.SettingsInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.fluid.FluidHero
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidLoadingBlock
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.family.contract.FamilyStateQr
import dev.pampa.mycloudgallery.core.family.contract.QrPayloadCodec
import dev.pampa.mycloudgallery.core.family.contract.QrPayloads
import dev.pampa.mycloudgallery.core.family.contract.TrustStateStore
import dev.pampa.mycloudgallery.core.qr.QrCode
import dev.pampa.mycloudgallery.core.security.screen.OverlayShield
import dev.pampa.mycloudgallery.core.security.screen.SecureScreen
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.registry.RegistryVerifier
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.Note
import dev.pampa.mycloudgallery.feature.settings.ui.SettingsDimens
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue

/** Cosa mostra la pagina "Stato della famiglia". */
internal sealed interface FamilyStateUi {
  data object Loading : FamilyStateUi

  /** Il registro accettato non si legge (o non c'e'): non si mostra nessun QR. */
  data object Unavailable : FamilyStateUi

  /** Il QR (`MCGS1:`) e la versione del registro che porta. **Il testo non e' nei log.** */
  data class Ready(val qrText: String, val registryVersion: Long) : FamilyStateUi {
    override fun toString(): String = "Ready(v$registryVersion)"
  }
}

/**
 * Lo "stato della famiglia" (M7): un QR che un dispositivo gia' approvato mostra a chi entra senza QR o
 * recupera l'admin, per alzare la versione minima del registro che accetta (anti-rollback). Si ricava dal
 * registro accettato e conservato in locale (`TrustStateStore`): versione, impronta del contenuto, id e
 * impronta della chiave di famiglia. **Niente password ne' chiavi private.**
 */
@HiltViewModel
internal class FamilyStateViewModel @Inject constructor(
  private val store: TrustStateStore,
  private val codec: QrPayloadCodec<FamilyStateQr>,
) : ViewModel() {

  private val _state = MutableStateFlow<FamilyStateUi>(FamilyStateUi.Loading)
  val state: StateFlow<FamilyStateUi> = _state.asStateFlow()

  init {
    load()
  }

  fun load() {
    _state.value = FamilyStateUi.Loading
    viewModelScope.launch {
      _state.value = try {
        build()
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        FamilyStateUi.Unavailable
      }
    }
  }

  private suspend fun build(): FamilyStateUi {
    val snapshot = store.load() ?: return FamilyStateUi.Unavailable
    val registry = RegistryVerifier.restore(snapshot.registryBytes, snapshot.signerFingerprint).acceptedOrNull()
      ?: return FamilyStateUi.Unavailable
    val payload = FamilyStateQr(
      familyId = snapshot.familyId,
      familyKeyFingerprint = snapshot.familyKeyFingerprint,
      registryVersion = registry.registry.version,
      registryHash = registry.contentHash,
    )
    val text = codec.encode(payload)
    if (text.length > QrPayloads.MAX_CHARS) return FamilyStateUi.Unavailable
    return FamilyStateUi.Ready(text, registry.registry.version)
  }

  override fun onCleared() {
    _state.value = FamilyStateUi.Loading
  }
}

@Composable
internal fun FamilyStateRoute(onBack: () -> Unit) {
  val viewModel: FamilyStateViewModel = hiltViewModel()
  val state by viewModel.state.collectAsStateWithLifecycle()
  FamilyStateScreen(state = state, onBack = onBack)
}

/** La pagina del QR di stato: sotto `FLAG_SECURE` finche' il QR e' in composizione. */
@Composable
internal fun FamilyStateScreen(state: FamilyStateUi, onBack: () -> Unit) {
  val ready = state as? FamilyStateUi.Ready
  SecureScreen(enabled = ready != null)
  OverlayShield(enabled = ready != null)
  FluidScreen(
    title = stringResource(R.string.state_title),
    onBack = onBack,
    ambient = FluidAmbient(tone = FluidHeroTone.Secondary, motif = FluidHeroMotif.Glow),
  ) {
    when (state) {
      FamilyStateUi.Loading -> item { FluidLoadingBlock() }
      FamilyStateUi.Unavailable -> item {
        // Giro 2 della fase I (CS-17): la forma unica dello stato vuoto (figura e titolo); la frase dietro la «i».
        GalleryFigureState(
          icon = Icons.Rounded.CloudOff,
          title = stringResource(R.string.state_unavailable_title),
          tone = GalleryIllustrationTone.Caution,
          fill = false,
          action = { SettingsInfo(label = stringResource(R.string.settings_state_why), text = stringResource(R.string.state_unavailable_detail)) },
        )
      }
      is FamilyStateUi.Ready -> {
        item {
          FluidHero(
            tone = FluidHeroTone.Secondary,
            motif = FluidHeroMotif.Cards,
            eyebrow = stringResource(R.string.state_eyebrow),
            value = stringResource(R.string.state_version, state.registryVersion),
            title = stringResource(R.string.state_hero_title),
            description = stringResource(R.string.state_hero_detail),
            icon = Icons.Rounded.QrCode2,
          )
        }
        item {
          FluidCard {
            Column(
              modifier = Modifier.fillMaxWidth(),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(SettingsDimens.CardGap),
            ) {
              QrCode(
                content = state.qrText,
                contentDescription = stringResource(R.string.state_qr_description),
                secure = true,
                modifier = Modifier.widthIn(max = SettingsDimens.QrMaxWidth),
              )
            }
          }
        }
        item {
          Note(
            title = stringResource(R.string.state_when_title),
            message = stringResource(R.string.state_when_message),
            tone = FluidTone.Info,
          )
        }
        item { FluidSectionFootnote(text = stringResource(R.string.state_footnote)) }
      }
    }
  }
}
