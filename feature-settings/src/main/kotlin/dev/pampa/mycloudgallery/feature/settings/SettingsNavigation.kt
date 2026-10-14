package dev.pampa.mycloudgallery.feature.settings

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.feature.settings.about.AboutRoute
import dev.pampa.mycloudgallery.feature.settings.account.AddressRoute
import dev.pampa.mycloudgallery.feature.settings.account.NasAccountLinks
import dev.pampa.mycloudgallery.feature.settings.account.NasAccountRoute
import dev.pampa.mycloudgallery.feature.settings.account.PasswordRoute
import dev.pampa.mycloudgallery.feature.settings.devices.ApprovalRoute
import dev.pampa.mycloudgallery.feature.settings.devices.DeviceDetailRoute
import dev.pampa.mycloudgallery.feature.settings.devices.DevicesRoute
import dev.pampa.mycloudgallery.feature.settings.family.AdminRecoveryRoute
import dev.pampa.mycloudgallery.feature.settings.family.FamilyLinks
import dev.pampa.mycloudgallery.feature.settings.family.FamilyResetRoute
import dev.pampa.mycloudgallery.feature.settings.family.FamilyRoute
import dev.pampa.mycloudgallery.feature.settings.family.FamilyStateRoute
import dev.pampa.mycloudgallery.feature.settings.family.InviteRoute
import dev.pampa.mycloudgallery.feature.settings.family.KitRoute
import dev.pampa.mycloudgallery.feature.settings.network.NetworkRoute
import dev.pampa.mycloudgallery.feature.settings.quality.PreviewQualityRoute
import dev.pampa.mycloudgallery.feature.settings.security.BackupNoticeScreen
import dev.pampa.mycloudgallery.feature.settings.security.SecurityRoute
import dev.pampa.mycloudgallery.feature.settings.ui.SettingsHost
import kotlinx.serialization.Serializable

@Serializable
data object SettingsGraphRoute

@Serializable
data object SettingsHomeRoute

// Le pagine delle Impostazioni (P7). Una rotta per pagina; i nomi degli argomenti (`deviceId`, `requestId`,
// `replace`) sono quelli che i ViewModel leggono dal SavedStateHandle.

@Serializable
data object SettingsDevicesRoute

@Serializable
data class SettingsDeviceRoute(val deviceId: String)

@Serializable
data class SettingsApprovalRoute(val requestId: String)

@Serializable
data object SettingsFamilyRoute

@Serializable
data object SettingsInviteRoute

@Serializable
data object SettingsKitRoute

@Serializable
data class SettingsAdminRecoveryRoute(val replace: Boolean)

@Serializable
data object SettingsFamilyStateRoute

@Serializable
data object SettingsResetRoute

@Serializable
data object SettingsAccountRoute

@Serializable
data object SettingsPasswordRoute

@Serializable
data object SettingsAddressRoute

@Serializable
data object SettingsNetworkRoute

@Serializable
data object SettingsSecurityRoute

@Serializable
data object SettingsBackupRoute

@Serializable
data object SettingsAboutRoute

/** La qualita' delle anteprime del NAS e le copertine degli album (Fase 4A, P11). */
@Serializable
data object SettingsQualityRoute

/**
 * Le Impostazioni (P7): Dispositivi (elenco, dettaglio, approvazione, segnala perso), Famiglia (invito, kit di
 * recupero, secondo admin, reset, QR "stato"), Account del NAS (password, indirizzo), Rete e VPN, Sicurezza
 * (blocco app), avviso sul backup esterno e "Da completare".
 *
 * Le uscite verso altre funzioni sono callback di :app, perche' i feature non si conoscono:
 * [openDiagnostics] apre la Diagnostica, [resumeStep] riapre un passo dell'onboarding (i passi saltati,
 * il controllo di WireGuard, la checklist del backup). [developerSection] e' la sezione "Sviluppo" che :app
 * passa **solo in debug** (catalogo dei componenti, stati finti dello scaffold); in release e' null e non
 * esiste.
 *
 * Ogni pagina vive dentro un [SettingsHost]: i fogli di conferma (perso, reimposta, indirizzo) si aprono
 * anche se :app non ha un host dei pop-up alla radice.
 */
fun NavGraphBuilder.settingsGraph(
  nav: NavController,
  openDiagnostics: () -> Unit,
  resumeStep: (OnboardingStep) -> Unit,
  developerSection: (@Composable () -> Unit)? = null,
) {
  val back: () -> Unit = { nav.popBackStack() }
  navigation<SettingsGraphRoute>(startDestination = SettingsHomeRoute) {
    composable<SettingsHomeRoute> {
      SettingsHost {
        SettingsRoute(
          onBack = back,
          links = SettingsLinks(
            openDevices = { nav.navigate(SettingsDevicesRoute) },
            openFamily = { nav.navigate(SettingsFamilyRoute) },
            openNasAccount = { nav.navigate(SettingsAccountRoute) },
            openNetwork = { nav.navigate(SettingsNetworkRoute) },
            openSecurity = { nav.navigate(SettingsSecurityRoute) },
            openBackupNotice = { nav.navigate(SettingsBackupRoute) },
            openAbout = { nav.navigate(SettingsAboutRoute) },
            openQuality = { nav.navigate(SettingsQualityRoute) },
          ),
          openDiagnostics = openDiagnostics,
          resumeStep = resumeStep,
          developerSection = developerSection,
        )
      }
    }

    // --- Dispositivi ---
    composable<SettingsDevicesRoute> {
      SettingsHost {
        DevicesRoute(
          onBack = back,
          openRequest = { nav.navigate(SettingsApprovalRoute(it)) },
          openDevice = { nav.navigate(SettingsDeviceRoute(it)) },
        )
      }
    }
    composable<SettingsDeviceRoute> { SettingsHost { DeviceDetailRoute(onBack = back) } }
    composable<SettingsApprovalRoute> { SettingsHost { ApprovalRoute(onBack = back) } }

    // --- Famiglia ---
    composable<SettingsFamilyRoute> {
      SettingsHost {
        FamilyRoute(
          links = FamilyLinks(
            onBack = back,
            openInvite = { nav.navigate(SettingsInviteRoute) },
            openRecoveryKit = { nav.navigate(SettingsKitRoute) },
            openFamilyState = { nav.navigate(SettingsFamilyStateRoute) },
            openSecondAdmin = { nav.navigate(SettingsAdminRecoveryRoute(replace = false)) },
            openReplaceAdmin = { nav.navigate(SettingsAdminRecoveryRoute(replace = true)) },
            openReset = { nav.navigate(SettingsResetRoute) },
          ),
        )
      }
    }
    composable<SettingsInviteRoute> { SettingsHost { InviteRoute(onBack = back) } }
    composable<SettingsKitRoute> {
      SettingsHost {
        KitRoute(
          onBack = back,
          onSecondAdmin = { nav.navigate(SettingsAdminRecoveryRoute(replace = false)) },
          onReplaceAdmin = { nav.navigate(SettingsAdminRecoveryRoute(replace = true)) },
        )
      }
    }
    composable<SettingsAdminRecoveryRoute> { SettingsHost { AdminRecoveryRoute(onBack = back) } }
    composable<SettingsFamilyStateRoute> { SettingsHost { FamilyStateRoute(onBack = back) } }
    composable<SettingsResetRoute> {
      SettingsHost { FamilyResetRoute(onBack = back, onInvite = { nav.navigate(SettingsInviteRoute) }) }
    }

    // --- Account del NAS ---
    composable<SettingsAccountRoute> {
      SettingsHost {
        NasAccountRoute(
          links = NasAccountLinks(
            onBack = back,
            openPassword = { nav.navigate(SettingsPasswordRoute) },
            openAddress = { nav.navigate(SettingsAddressRoute) },
          ),
        )
      }
    }
    composable<SettingsPasswordRoute> { SettingsHost { PasswordRoute(onBack = back) } }
    composable<SettingsAddressRoute> { SettingsHost { AddressRoute(onBack = back) } }

    // --- Rete, sicurezza, backup ---
    composable<SettingsNetworkRoute> {
      SettingsHost { NetworkRoute(onBack = back, configureVpn = { resumeStep(OnboardingStep.VPN) }) }
    }
    composable<SettingsSecurityRoute> {
      SettingsHost { SecurityRoute(onBack = back, openBackupNotice = { nav.navigate(SettingsBackupRoute) }) }
    }
    composable<SettingsBackupRoute> {
      SettingsHost {
        BackupNoticeScreen(onBack = back, onReviewChecklist = { resumeStep(OnboardingStep.SECURITY_NOTICE) })
      }
    }

    // --- Anteprime del NAS (Fase 4A) ---
    composable<SettingsQualityRoute> { SettingsHost { PreviewQualityRoute(onBack = back) } }

    // --- Informazioni e licenze ---
    composable<SettingsAboutRoute> { SettingsHost { AboutRoute(onBack = back) } }
  }
}
