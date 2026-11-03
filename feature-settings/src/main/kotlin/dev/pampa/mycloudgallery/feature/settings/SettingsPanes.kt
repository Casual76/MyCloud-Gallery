package dev.pampa.mycloudgallery.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import dev.antigravity.fluidengine.ui.navigation.FluidNavHost
import dev.antigravity.fluidengine.ui.navigation.composable
import androidx.navigation.compose.rememberNavController
import dev.antigravity.fluidengine.ui.page.FluidBackSwipe
import dev.antigravity.fluidengine.ui.fluid.FluidDetailContent
import dev.antigravity.fluidengine.ui.fluid.FluidDetailPlaceholder
import dev.antigravity.fluidengine.ui.fluid.FluidListDetailScaffold
import dev.pampa.mycloudgallery.core.designsystem.adaptive.FluidPaneSizes
import dev.pampa.mycloudgallery.core.designsystem.adaptive.rememberGalleryWindow
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import kotlinx.serialization.Serializable

// LE IMPOSTAZIONI A DUE PANNELLI (Fase 10, P5, D13 (4); docs/fase10-progetto.md §2.3): da finestra estesa (840 dp) l'elenco delle Impostazioni sta a sinistra e la pagina scelta a destra; sotto, l'elenco e poi
// la pagina, come prima. La pagina scelta si ricorda (`rememberSaveable`) e passando sotto la soglia e tornando non si perde. Le pagine sono quelle di sempre, ognuna con il suo ViewModel: nel pannello vivono
// in una navigazione propria (un `NavHost` per pagina scelta), cosi' le loro rotte, i loro argomenti e i loro "Indietro" funzionano senza una riga di codice diversa; l'"Indietro" della prima pagina
// del pannello la chiude (torna "Scegli una voce"). Le voci che portano fuori dalle Impostazioni (Diagnostica, Backup, Ricerca, Nascosti, Persone, Ricordi, Mappa) restano pagine a finestra intera.

/** Una pagina delle Impostazioni che puo' stare nel pannello di destra: un codice stabile (salvato) e la rotta. */
internal enum class SettingsPage(val code: String, val route: Any) {
  DEVICES("devices", SettingsDevicesRoute),
  FAMILY("family", SettingsFamilyRoute),
  ACCOUNT("account", SettingsAccountRoute),
  NETWORK("network", SettingsNetworkRoute),
  SECURITY("security", SettingsSecurityRoute),
  BACKUP_NOTICE("backup_notice", SettingsBackupRoute),
  QUALITY("quality", SettingsQualityRoute),
  RULES("rules", SettingsRulesRoute),
  SHARING("sharing", SettingsSharingRoute),
  WIDGET("widget", SettingsWidgetRoute),
  ABOUT("about", SettingsAboutRoute),
  ;

  companion object {
    /** La pagina di un codice salvato; null per un codice che questa versione non conosce. */
    fun fromCode(code: String?): SettingsPage? = entries.firstOrNull { it.code == code }
  }
}

/** Il pannello di destra quando non c'e' niente di scelto. */
@Serializable
internal data object SettingsPaneEmptyRoute

/**
 * Il guscio a due pannelli delle Impostazioni, senza Hilt (lo provano i test): [list] riceve se c'e' il pannello accanto e come **scegliere** una pagina; [detail] disegna la pagina scelta (o niente) e come
 * chiuderla. La scelta sta qui, salvata; sotto la soglia [list] non la usa (le pagine si aprono come prima) e la scelta resta per quando la finestra torna estesa.
 */
@Composable
internal fun SettingsPanes(
  list: @Composable (twoPane: Boolean, choose: (SettingsPage) -> Unit) -> Unit,
  detail: @Composable (page: SettingsPage?, close: () -> Unit) -> Unit,
  modifier: Modifier = Modifier,
) {
  val window = rememberGalleryWindow()
  var code by rememberSaveable { mutableStateOf<String?>(null) }
  val page = SettingsPage.fromCode(code)
  FluidListDetailScaffold(
    ambient = null,
    modifier = modifier,
    sizes = FluidPaneSizes.listDetail(window),
    list = { twoPane -> list(twoPane) { chosen -> code = chosen.code } },
    detail = {
      FluidDetailContent(item = page, key = { it?.code }, order = { it?.ordinal }) { shown -> detail(shown) { code = null } }
    },
  )
}

/**
 * L'"Indietro" di una pagina nel pannello (FI-1 della verifica funzionale della Fase 10): dalla **prima** pagina (l'unica con cui il pannello nasce) non si toglie mai niente, si chiude il
 * pannello ([close]: chi sceglie torna a "Scegli una voce" e la stessa voce si puo' riscegliere); da una seconda si torna alla prima. Prima contava sul fatto che `popBackStack()` rispondesse
 * falso quando non c'e' altro, e sul resto lasciava alla libreria la scelta di togliere o no l'ultima destinazione.
 */
internal fun paneBack(nav: NavController, close: () -> Unit) {
  if (nav.previousBackStackEntry == null) close() else nav.popBackStack()
}

/**
 * La pagina scelta nel pannello di destra: una navigazione propria che parte dalla rotta della pagina (le pagine delle Impostazioni vivono tutte in [settingsPages]); [resumeStep] riapre un passo
 * dell'onboarding (e' una pagina a finestra intera, come la Diagnostica). Cambiare pagina ricomincia da capo: un `NavHost` per pagina scelta.
 */
@Composable
internal fun SettingsPaneHost(
  page: SettingsPage?,
  close: () -> Unit,
  resumeStep: (OnboardingStep) -> Unit,
  modifier: Modifier = Modifier,
  pages: NavGraphBuilder.(nav: NavController, back: () -> Unit) -> Unit = { nav, back -> settingsPages(nav = nav, back = back, resumeStep = resumeStep, backSwipe = FluidBackSwipe.EdgeOnly) },
) {
  key(page) {
    val paneNav = rememberNavController()
    // Fluid Engine 3.0: le pagine di `settingsPages` sono pagine Fluid anche qui (un `FluidNavHost` del pannello, come quello dell'app). Il cambio fra due
    // pagine scelte lo fa ancora `FluidDetailContent`; dentro il pannello una pagina aperta da un'altra (Famiglia -> Invito) arriva da destra, come nella
    // 3.0 (l'engine non ha un passaggio "senza movimento"). Il fondo resta quello del pannello (`pageColor` non specificato: l'host non dipinge) e il back col dito parte solo
    // dal bordo del pannello (`EdgeOnly`): in mezzo al pannello un trascinamento di lato non chiude la pagina interna, resta il back della pagina delle
    // Impostazioni intera (l'host dell'app, `Anywhere`), come a pagina singola.
    FluidNavHost(
      navController = paneNav,
      startDestination = page?.route ?: SettingsPaneEmptyRoute,
      modifier = modifier,
      pageColor = Color.Unspecified,
    ) {
      composable<SettingsPaneEmptyRoute>(backSwipe = FluidBackSwipe.Off) {
        FluidDetailPlaceholder(
          title = stringResource(R.string.settings_panes_empty_title),
          message = stringResource(R.string.settings_panes_empty_message),
          icon = Icons.Rounded.Settings,
        )
      }
      pages(paneNav) { paneBack(paneNav, close) }
    }
  }
}
