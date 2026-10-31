package dev.pampa.mycloudgallery.feature.onboarding.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.KeyOff
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockClock
import androidx.compose.material.icons.rounded.NoEncryption
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustration
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustrationTone
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.feature.onboarding.R

/** Cosa l'utente puo' fare davanti a un problema (i bottoni della scheda). */
internal enum class ProblemAction(@param:StringRes val label: Int) {
  RETRY(R.string.action_retry),
  CONTINUE_LATER(R.string.action_continue_later),
  OPEN_WIFI_SETTINGS(R.string.action_open_wifi_settings),
  OPEN_WIREGUARD(R.string.action_open_wireguard),
  OPEN_APP_SETTINGS(R.string.action_open_app_settings),
  CHANGE_ADDRESS(R.string.action_change_address),
  RECOVER_ADMIN(R.string.action_recover_admin),
  JOIN_AS_MEMBER(R.string.action_join_as_member),
  SCAN_AGAIN(R.string.action_scan_again),
  ENTER_MANUALLY(R.string.action_enter_manually),
  BACK_TO_CODE(R.string.action_back_to_code),

  /** Punto 10: in quest'area non c'e' una famiglia, si passa a "Crea la famiglia". */
  FOUND_FAMILY(R.string.action_found_family),

  /** Punto 10: un'altra area comune (al passo 4, o un altro invito nell'ingresso). */
  CHANGE_AREA(R.string.action_change_area),
}

/**
 * Come si presenta un problema: l'icona, il tono (avviso o errore), il testo e i bottoni. [big] = il problema
 * occupa la pagina (con l'illustrazione dei "stati vuoti" del design system) invece di una scheda sopra il modulo.
 */
@Immutable
internal data class ProblemSpec(
  val icon: ImageVector,
  val tone: NoticeTone,
  @param:StringRes val title: Int,
  @param:StringRes val detail: Int,
  val actions: List<ProblemAction> = emptyList(),
  val big: Boolean = false,
)

/**
 * Un testo italiano chiaro per **ogni** problema che il coordinatore, l'ingresso o il recupero possono dare
 * (docs/fase2-progetto.md §3.4): la funzione e' una `when` esaustiva, quindi un problema nuovo non compila
 * finche' non ha il suo messaggio. `ProblemSpecTest` verifica che ogni testo esista e non sia vuoto.
 */
internal fun OnboardingProblem.spec(): ProblemSpec = when (this) {
  OnboardingProblem.NOT_ON_WIFI -> ProblemSpec(
    Icons.Rounded.WifiOff, NoticeTone.Caution, R.string.problem_not_on_wifi_title, R.string.problem_not_on_wifi_detail,
    listOf(ProblemAction.OPEN_WIFI_SETTINGS, ProblemAction.RETRY),
  )
  OnboardingProblem.VPN_ACTIVE -> ProblemSpec(
    Icons.Rounded.VpnKey, NoticeTone.Caution, R.string.problem_vpn_active_title, R.string.problem_vpn_active_detail,
    listOf(ProblemAction.OPEN_WIREGUARD, ProblemAction.RETRY),
  )
  OnboardingProblem.NO_ULA -> ProblemSpec(
    Icons.Rounded.Lan, NoticeTone.Caution, R.string.problem_no_ula_title, R.string.problem_no_ula_detail,
    listOf(ProblemAction.RETRY),
  )
  OnboardingProblem.LOCAL_NETWORK_DENIED -> ProblemSpec(
    Icons.Rounded.Block, NoticeTone.Caution, R.string.problem_local_network_title, R.string.problem_local_network_detail,
    listOf(ProblemAction.OPEN_APP_SETTINGS, ProblemAction.RETRY),
  )
  OnboardingProblem.NAS_NOT_FOUND -> ProblemSpec(
    Icons.Rounded.CloudOff, NoticeTone.Caution, R.string.problem_nas_not_found_title, R.string.problem_nas_not_found_detail,
    listOf(ProblemAction.RETRY, ProblemAction.CONTINUE_LATER),
  )
  OnboardingProblem.NAS_SERVICE_DOWN -> ProblemSpec(
    Icons.Rounded.Dns, NoticeTone.Caution, R.string.problem_service_down_title, R.string.problem_service_down_detail,
    listOf(ProblemAction.RETRY),
  )
  OnboardingProblem.NAS_INSECURE -> ProblemSpec(
    Icons.Rounded.NoEncryption, NoticeTone.Danger, R.string.problem_insecure_title, R.string.problem_insecure_detail,
    listOf(ProblemAction.RETRY),
  )
  OnboardingProblem.IDENTITY_MISMATCH -> ProblemSpec(
    Icons.Rounded.GppBad, NoticeTone.Danger, R.string.problem_identity_title, R.string.problem_identity_detail,
    listOf(ProblemAction.CHANGE_ADDRESS),
  )
  OnboardingProblem.PASSWORD_SENT_TO_OTHER_NAS -> ProblemSpec(
    Icons.Rounded.GppBad, NoticeTone.Danger, R.string.problem_password_other_nas_title, R.string.problem_password_other_nas_detail,
  )
  OnboardingProblem.ADMIN_ACCOUNT_REFUSED -> ProblemSpec(
    Icons.Rounded.AdminPanelSettings, NoticeTone.Caution, R.string.problem_admin_refused_title, R.string.problem_admin_refused_detail,
  )
  OnboardingProblem.PASSWORD_TOO_WEAK -> ProblemSpec(
    Icons.Rounded.Password, NoticeTone.Caution, R.string.problem_password_weak_title, R.string.problem_password_weak_detail,
  )
  OnboardingProblem.AUTH_FAILED -> ProblemSpec(
    Icons.Rounded.KeyOff, NoticeTone.Danger, R.string.problem_auth_failed_title, R.string.problem_auth_failed_detail,
  )
  OnboardingProblem.ACCOUNT_LOCKED -> ProblemSpec(
    Icons.Rounded.Lock, NoticeTone.Danger, R.string.problem_account_locked_title, R.string.problem_account_locked_detail,
  )
  OnboardingProblem.ACCOUNT_DISABLED -> ProblemSpec(
    Icons.Rounded.PersonOff, NoticeTone.Danger, R.string.problem_account_disabled_title, R.string.problem_account_disabled_detail,
  )
  OnboardingProblem.PASSWORD_EXPIRED -> ProblemSpec(
    Icons.Rounded.LockClock, NoticeTone.Danger, R.string.problem_password_expired_title, R.string.problem_password_expired_detail,
  )
  OnboardingProblem.ACCOUNT_RESTRICTED -> ProblemSpec(
    Icons.Rounded.Block, NoticeTone.Danger, R.string.problem_account_restricted_title, R.string.problem_account_restricted_detail,
  )
  OnboardingProblem.CREDENTIALS_NOT_SAVED -> ProblemSpec(
    Icons.Rounded.Key, NoticeTone.Danger, R.string.problem_credentials_title, R.string.problem_credentials_detail,
  )
  // Fase 10, M3: la chiave di famiglia vuole l'impronta o il PIN a ogni uso. Il tasto della pagina (crea la chiave, verifica) riprova.
  OnboardingProblem.FAMILY_KEY_NOT_CONFIRMED -> ProblemSpec(
    Icons.Rounded.Key, NoticeTone.Caution, R.string.problem_family_key_not_confirmed_title, R.string.problem_family_key_not_confirmed_detail,
  )
  OnboardingProblem.FAMILY_KEY_INVALIDATED -> ProblemSpec(
    Icons.Rounded.KeyOff, NoticeTone.Danger, R.string.problem_family_key_invalidated_title, R.string.problem_family_key_invalidated_detail,
  )
  OnboardingProblem.NO_ACCESSIBLE_SHARE -> ProblemSpec(
    Icons.Rounded.FolderOff, NoticeTone.Caution, R.string.problem_no_share_title, R.string.problem_no_share_detail,
    listOf(ProblemAction.RETRY),
    big = true,
  )
  OnboardingProblem.COMMON_AREA_NOT_WRITABLE -> ProblemSpec(
    Icons.Rounded.FolderOff, NoticeTone.Caution, R.string.problem_common_area_title, R.string.problem_common_area_detail,
    listOf(ProblemAction.RETRY),
  )
  OnboardingProblem.FAMILY_ALREADY_EXISTS -> ProblemSpec(
    Icons.Rounded.Groups, NoticeTone.Info, R.string.problem_family_exists_title, R.string.problem_family_exists_detail,
    listOf(ProblemAction.JOIN_AS_MEMBER, ProblemAction.RECOVER_ADMIN),
    big = true,
  )
  OnboardingProblem.NO_FAMILY_IN_AREA -> ProblemSpec(
    Icons.Rounded.Groups, NoticeTone.Info, R.string.problem_no_family_title, R.string.problem_no_family_detail,
    listOf(ProblemAction.FOUND_FAMILY, ProblemAction.CHANGE_AREA),
    big = true,
  )
  OnboardingProblem.RECOVERY_CODE_MISMATCH -> ProblemSpec(
    Icons.Rounded.ErrorOutline, NoticeTone.Danger, R.string.problem_code_mismatch_title, R.string.problem_code_mismatch_detail,
    listOf(ProblemAction.BACK_TO_CODE),
  )
  OnboardingProblem.RECOVERY_KIT_UNVERIFIED -> ProblemSpec(
    Icons.Rounded.VerifiedUser, NoticeTone.Danger, R.string.problem_kit_unverified_title, R.string.problem_kit_unverified_detail,
    listOf(ProblemAction.RETRY),
  )
  OnboardingProblem.NAS_WRITE_FAILED -> ProblemSpec(
    Icons.Rounded.Storage, NoticeTone.Caution, R.string.problem_write_failed_title, R.string.problem_write_failed_detail,
    listOf(ProblemAction.RETRY),
  )
  OnboardingProblem.NAS_FULL -> ProblemSpec(
    Icons.Rounded.Storage, NoticeTone.Caution, R.string.problem_nas_full_title, R.string.problem_nas_full_detail,
    listOf(ProblemAction.RETRY),
  )
  OnboardingProblem.INVITE_INVALID -> ProblemSpec(
    Icons.Rounded.QrCodeScanner, NoticeTone.Caution, R.string.problem_invite_invalid_title, R.string.problem_invite_invalid_detail,
    listOf(ProblemAction.SCAN_AGAIN, ProblemAction.ENTER_MANUALLY),
    big = true,
  )
  OnboardingProblem.INVITE_OTHER_FAMILY -> ProblemSpec(
    Icons.Rounded.GppBad, NoticeTone.Danger, R.string.problem_invite_other_title, R.string.problem_invite_other_detail,
    listOf(ProblemAction.SCAN_AGAIN),
    big = true,
  )
  OnboardingProblem.INVITE_EXPIRED -> ProblemSpec(
    Icons.Rounded.Timer, NoticeTone.Caution, R.string.problem_invite_expired_title, R.string.problem_invite_expired_detail,
    listOf(ProblemAction.SCAN_AGAIN),
    big = true,
  )
  OnboardingProblem.NOT_AT_HOME -> ProblemSpec(
    Icons.Rounded.WifiOff, NoticeTone.Caution, R.string.problem_not_at_home_title, R.string.problem_not_at_home_detail,
    listOf(ProblemAction.OPEN_WIFI_SETTINGS, ProblemAction.OPEN_WIREGUARD, ProblemAction.RETRY),
    big = true,
  )
  OnboardingProblem.REGISTRY_UNTRUSTED -> ProblemSpec(
    Icons.Rounded.GppBad, NoticeTone.Danger, R.string.problem_registry_title, R.string.problem_registry_detail,
    listOf(ProblemAction.RETRY),
    big = true,
  )
  OnboardingProblem.NEEDS_APP_UPDATE -> ProblemSpec(
    Icons.Rounded.SystemUpdate, NoticeTone.Info, R.string.problem_update_title, R.string.problem_update_detail,
    big = true,
  )
  OnboardingProblem.STEP_NOT_AVAILABLE -> ProblemSpec(
    Icons.Rounded.ErrorOutline, NoticeTone.Caution, R.string.problem_step_unavailable_title, R.string.problem_step_unavailable_detail,
    listOf(ProblemAction.RETRY),
  )
  OnboardingProblem.KEYS_NOT_REMOVED -> ProblemSpec(
    Icons.Rounded.KeyOff, NoticeTone.Caution, R.string.problem_keys_title, R.string.problem_keys_detail,
    listOf(ProblemAction.RETRY),
  )
  OnboardingProblem.LOCAL_DATA_NOT_REMOVED -> ProblemSpec(
    Icons.Rounded.FolderOff, NoticeTone.Caution, R.string.problem_old_data_title, R.string.problem_old_data_detail,
    listOf(ProblemAction.RETRY),
  )
}

/**
 * Un problema, scritto bene: una scheda sopra il modulo (o, per i problemi che fermano la pagina, l'illustrazione
 * del design system con il titolo, la frase e i bottoni). Il primo bottone e' quello che di solito si vuole.
 *
 * Il testo e' una regione viva: quando il problema compare TalkBack lo legge da solo.
 */
@Composable
internal fun ProblemPanel(
  problem: OnboardingProblem,
  onAction: (ProblemAction) -> Unit,
  modifier: Modifier = Modifier,
  /** Titolo e testo con i dati della pagina (il nome dell'area, punto 10), al posto di quelli generici del problema. */
  title: String? = null,
  detail: String? = null,
) {
  val spec = problem.spec()
  if (spec.big) {
    BigProblem(spec = spec, onAction = onAction, modifier = modifier, title = title, detail = detail)
  } else {
    NoticeCard(
      tone = spec.tone,
      icon = spec.icon,
      title = title ?: stringResource(spec.title),
      body = detail ?: stringResource(spec.detail),
      modifier = modifier,
    ) {
      spec.actions.forEachIndexed { index, action ->
        CardButton(text = stringResource(action.label), onClick = { onAction(action) }, primary = index == 0)
      }
    }
  }
}

@Composable
private fun BigProblem(spec: ProblemSpec, onAction: (ProblemAction) -> Unit, modifier: Modifier = Modifier, title: String? = null, detail: String? = null) {
  val tone = when (spec.tone) {
    NoticeTone.Danger -> GalleryIllustrationTone.Danger
    NoticeTone.Caution -> GalleryIllustrationTone.Caution
    NoticeTone.Info, NoticeTone.Success -> GalleryIllustrationTone.Brand
  }
  Column(
    modifier = modifier.fillMaxWidth().padding(vertical = OnboardingDimens.CardGap),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(OnboardingDimens.CardGap),
  ) {
    GalleryIllustration(icon = spec.icon, tone = tone)
    Column(
      modifier = Modifier
        .widthIn(max = OnboardingDimens.ReadingWidth)
        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(OnboardingDimens.TextGap),
    ) {
      Text(
        text = title ?: stringResource(spec.title),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
      )
      Text(
        text = detail ?: stringResource(spec.detail),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(OnboardingDimens.DockGap),
    ) {
      spec.actions.forEachIndexed { index, action ->
        CardButton(text = stringResource(action.label), onClick = { onAction(action) }, primary = index == 0)
      }
    }
  }
}
