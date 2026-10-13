package dev.pampa.mycloudgallery.feature.onboarding.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidLoading
import dev.pampa.mycloudgallery.core.family.contract.OnboardingWarning
import dev.pampa.mycloudgallery.feature.onboarding.R
import dev.pampa.mycloudgallery.feature.onboarding.model.OnboardingBanner

/** Come si presenta un avviso non bloccante (E-S3, avviso su una share pubblica...). */
internal data class WarningSpec(val icon: ImageVector, @param:StringRes val title: Int, @param:StringRes val detail: Int)

/** Un testo per ogni avviso che un passo puo' restituire: esaustivo, un avviso nuovo non compila senza. */
internal fun OnboardingWarning.spec(): WarningSpec = when (this) {
  OnboardingWarning.SHARE_IS_PUBLIC ->
    WarningSpec(Icons.Rounded.Public, R.string.warning_public_share_title, R.string.warning_public_share_detail)
  OnboardingWarning.ACCOUNT_SEES_OTHER_PRIVATE_SHARES ->
    WarningSpec(Icons.Rounded.Visibility, R.string.warning_other_shares_title, R.string.warning_other_shares_detail)
  OnboardingWarning.NO_SCREEN_LOCK ->
    WarningSpec(Icons.Rounded.Lock, R.string.warning_no_screen_lock_title, R.string.warning_no_screen_lock_detail)
  OnboardingWarning.NO_MIN_REGISTRY_VERSION ->
    WarningSpec(Icons.Rounded.QrCode2, R.string.warning_no_min_version_title, R.string.warning_no_min_version_detail)
}

/** La conferma di cio' che e' appena andato bene. */
internal data class BannerSpec(val icon: ImageVector, @param:StringRes val title: Int, @param:StringRes val detail: Int)

internal fun OnboardingBanner.spec(): BannerSpec = when (this) {
  OnboardingBanner.NAS_VERIFIED ->
    BannerSpec(Icons.Rounded.VerifiedUser, R.string.banner_nas_verified_title, R.string.banner_nas_verified_detail)
  OnboardingBanner.CREDENTIALS_SAVED ->
    BannerSpec(Icons.Rounded.Shield, R.string.banner_credentials_saved_title, R.string.banner_credentials_saved_detail)
  OnboardingBanner.KIT_VERIFIED ->
    BannerSpec(Icons.Rounded.CheckCircle, R.string.banner_kit_verified_title, R.string.banner_kit_verified_detail)
}

/**
 * Le cose che stanno sopra il contenuto di ogni passo: la barra del cammino, la conferma del passo prima (se c'e')
 * e gli avvisi non bloccanti ancora aperti.
 */
internal fun LazyListScope.chromeItems(chrome: StepChrome) {
  chrome.progress?.let { progress ->
    item(key = "progress") {
      StepProgressHeader(label = stringResource(R.string.step_of, progress.step, progress.total), fraction = progress.fraction)
    }
  }
  chrome.banner?.let { banner ->
    item(key = "banner") {
      val spec = banner.spec()
      NoticeCard(
        tone = NoticeTone.Success,
        icon = spec.icon,
        title = stringResource(spec.title),
        body = stringResource(spec.detail),
        compact = true,
        onDismiss = chrome.onDismissBanner,
      )
    }
  }
}

/**
 * Gli avvisi non bloccanti, **dopo** il contenuto: non fermano niente e non devono portare il modulo sotto la piega. Sono
 * schede compatte sotto "Da sapere", chiudibili una a una.
 */
internal fun LazyListScope.warningItems(chrome: StepChrome) {
  if (chrome.warnings.isEmpty()) return
  item(key = "warnings-header") { FluidSectionHeader(title = stringResource(R.string.warnings_section)) }
  chrome.warnings.forEach { warning ->
    item(key = "warning-${warning.name}") {
      val spec = warning.spec()
      NoticeCard(
        tone = NoticeTone.Caution,
        icon = spec.icon,
        title = stringResource(spec.title),
        body = stringResource(spec.detail),
        compact = true,
        onDismiss = { chrome.onDismissWarning(warning) },
      )
    }
  }
}

/** Un'attesa dentro una pagina: il segno e una frase che dice cosa si sta facendo. */
@Composable
internal fun LoadingBlock(label: String, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = OnboardingDimens.CardPadding),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(OnboardingDimens.CardGap),
  ) {
    FluidLoading()
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}
