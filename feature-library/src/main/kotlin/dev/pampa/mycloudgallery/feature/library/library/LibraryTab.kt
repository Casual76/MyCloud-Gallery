package dev.pampa.mycloudgallery.feature.library.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.fluidGlassModalObscured
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest

/**
 * La scheda **Libreria** (docs/fase3-progetto.md §2.4; grafica G2, docs/grafica-progetto.md §12): la griglia del telefono e
 * del NAS **a tutto schermo** (§1.4: la griglia e' la sua lista, niente `FluidScreen`), con la chrome di vetro adattivo
 * in alto (l'etichetta con la sezione in vista e la capsula dei comandi, con la nuvola di stato), la pillola in basso (o,
 * selezionando, la barra delle azioni al suo posto), e il riepilogo in fondo alla lista, sotto i piu' recenti.
 *
 * - [contentPadding]: lo spazio che la pillola delle schede occupa in basso (il contenuto scorre sotto, rifratto);
 * - [backdropOut]: la Libreria consegna a `:app` il fondale di vetro che disegna (la griglia), cosi' la pillola lo
 *   rifrange senza far parte della scheda;
 * - [onOpenViewer]: il viewer e' una sovrapposizione ospitata da `:app` sopra la pillola (D12): la griglia non lo
 *   conosce, manda la richiesta con la geometria della miniatura (`ViewerAnchor`);
 * - [onOpenSettings]: dal menu della vista;
 * - [tabBarScroll]: la connessione con cui la pillola si ripiega scorrendo verso i piu' vecchi (scelta 5 dell'utente);
 * - [onNasStatusClick]: "Apri la Diagnostica" sotto un problema del NAS nel pop-up della nuvola (i dettagli e "Ho controllato,
 *   continua");
 * - [onBackupStatusClick]: "Apri Backup" sotto un problema del backup, e il tocco su un lavoro del backup, nel pop-up (Fase 5A, D16);
 * - [network]: lo stato della rete per la nuvola (B2), ridotto da `:app` dall'indicatore globale; null = la rete non dice niente;
 * - [onNetworkClick]: il tasto sotto un problema della rete (WireGuard, la password, la Diagnostica: cio' che dice `:app`).
 *
 * Gli stati (§2.4): nessun permesso, accesso parziale (E-P1), foto in attesa (E-P21), prima lettura, Libreria vuota,
 * filtro senza risultati. I pop-up (menu, conferma dell'eliminazione) usano il `FluidGlassModalHost` dello scope; se
 * `:app` non ne ha uno, la Libreria ne mette uno suo.
 */
@Composable
fun LibraryTab(
  contentPadding: PaddingValues,
  backdropOut: (GlassBackdropState) -> Unit,
  onOpenViewer: (ViewerRequest) -> Unit,
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier,
  state: LibraryTabState = rememberEmptyLibraryTabState(),
  tabBarScroll: NestedScrollConnection? = null,
  onNasStatusClick: () -> Unit = {},
  onBackupStatusClick: () -> Unit = {},
  network: LibraryNetworkStatus? = null,
  onNetworkClick: () -> Unit = {},
) {
  LibraryModalHost(modifier) {
    LibraryEffects(state)
    LibraryScreen(
      contentPadding = contentPadding,
      backdropOut = backdropOut,
      onOpenViewer = onOpenViewer,
      onOpenSettings = onOpenSettings,
      state = state,
      tabBarScroll = tabBarScroll,
      network = network,
      cloudActions = remember(onNetworkClick, onNasStatusClick, onBackupStatusClick) {
        LibraryCloudActions(onNetwork = onNetworkClick, onNas = onNasStatusClick, onBackup = onBackupStatusClick)
      },
    )
  }
}

/** Il punto in cui i pop-up della Libreria si aprono: quello di `:app` se c'e', altrimenti uno sopra la scheda. */
@Composable
private fun LibraryModalHost(modifier: Modifier, content: @Composable () -> Unit) {
  if (LocalFluidGlassModalHostState.current != null) {
    Box(modifier = modifier.fillMaxSize()) { content() }
    return
  }
  val host = rememberFluidGlassModalHostState()
  val backdrop = rememberGlassBackdrop()
  CompositionLocalProvider(LocalFluidGlassModalHostState provides host) {
    Box(modifier = modifier.fillMaxSize()) {
      Box(modifier = Modifier.fillMaxSize().glassBackdropSource(backdrop).fluidGlassModalObscured()) { content() }
      FluidGlassModalHost(state = host, backdrop = backdrop)
    }
  }
}
