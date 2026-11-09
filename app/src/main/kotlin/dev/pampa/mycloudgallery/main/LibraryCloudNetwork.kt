package dev.pampa.mycloudgallery.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.SignalWifiOff
import androidx.compose.material.icons.rounded.VpnKeyOff
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.pampa.mycloudgallery.feature.library.library.LibraryNetworkAction
import dev.pampa.mycloudgallery.feature.library.library.LibraryNetworkSeverity
import dev.pampa.mycloudgallery.feature.library.library.LibraryNetworkStatus
import dev.pampa.mycloudgallery.status.GlobalStatus
import dev.pampa.mycloudgallery.status.StatusAction

// B2 della beta (lista dell'utente del 31/10): nella Libreria la rete non e' piu' una voce in fondo alla griglia ma uno degli stati
// della nuvola in alto a destra. La Libreria non vede `GlobalStatus` (sta in :app): qui l'indicatore globale diventa il suo dato,
// `LibraryNetworkStatus`, con le stesse frasi, gli stessi glifi e le stesse uscite della voce della rete (`NetworkStatusEntry` di
// `MainScaffold`, che resta in cima alle Raccolte e a Cerca).

/**
 * Quanto pesa uno stato della rete sulla nuvola: a posto (non dice niente), il NAS che si sveglia (un lavoro), senza rete (la nuvola
 * barrata), da guardare (rete non riconosciuta, VPN spenta, roaming con le attivita' ferme, NAS non raggiungibile), serve l'utente (la
 * password, la rete locale negata da Android). Esaustiva: uno stato nuovo non compila finche' non ha il suo peso.
 */
fun libraryNetworkSeverity(status: GlobalStatus): LibraryNetworkSeverity = when (status) {
  GlobalStatus.HOME_CONNECTED, GlobalStatus.HOME_IDLE, GlobalStatus.AWAY_VPN_WIFI, GlobalStatus.AWAY_VPN_MOBILE -> LibraryNetworkSeverity.Fine
  GlobalStatus.WAKING_NAS -> LibraryNetworkSeverity.Working
  GlobalStatus.OFFLINE -> LibraryNetworkSeverity.Offline
  GlobalStatus.ROAMING, GlobalStatus.VPN_OFF, GlobalStatus.UNTRUSTED, GlobalStatus.FOREIGN_VPN, GlobalStatus.NAS_UNREACHABLE -> LibraryNetworkSeverity.Attention
  GlobalStatus.LOCAL_NETWORK_BLOCKED, GlobalStatus.NEEDS_PASSWORD -> LibraryNetworkSeverity.Alarm
}

/** L'uscita del tasto sotto un problema della rete: quella che il tocco sull'indicatore apre ([GlobalStatus.action]). */
fun libraryNetworkAction(status: GlobalStatus): LibraryNetworkAction = when (status.action) {
  StatusAction.OPEN_DIAGNOSTICS -> LibraryNetworkAction.DIAGNOSTICS
  StatusAction.OPEN_WIREGUARD -> LibraryNetworkAction.WIREGUARD
  StatusAction.ENTER_PASSWORD -> LibraryNetworkAction.PASSWORD
}

/** Il glifo di uno stato della rete: gli stessi della voce della rete (`NetworkStatusEntry`). Null mentre il NAS si sveglia. */
fun libraryNetworkGlyph(status: GlobalStatus): ImageVector? = when (status) {
  GlobalStatus.HOME_CONNECTED -> Icons.Rounded.CheckCircle
  GlobalStatus.HOME_IDLE -> Icons.Rounded.Home
  GlobalStatus.AWAY_VPN_WIFI, GlobalStatus.AWAY_VPN_MOBILE, GlobalStatus.FOREIGN_VPN -> Icons.Rounded.VpnLock
  GlobalStatus.ROAMING -> Icons.Rounded.Public
  GlobalStatus.VPN_OFF -> Icons.Rounded.VpnKeyOff
  GlobalStatus.UNTRUSTED -> Icons.Rounded.GppMaybe
  GlobalStatus.WAKING_NAS -> null
  GlobalStatus.NAS_UNREACHABLE -> Icons.Rounded.CloudOff
  GlobalStatus.LOCAL_NETWORK_BLOCKED -> Icons.Rounded.Block
  GlobalStatus.NEEDS_PASSWORD -> Icons.Rounded.Key
  GlobalStatus.OFFLINE -> Icons.Rounded.SignalWifiOff
}

/**
 * Lo stato della rete per la nuvola della Libreria: la frase dell'indicatore divisa al punto come nella voce della rete ("Rete non
 * riconosciuta" e, sotto, "Nessuna connessione al NAS"), la frase intera per TalkBack, il peso, il glifo e l'uscita.
 */
@Composable
fun libraryNetworkOf(status: GlobalStatus): LibraryNetworkStatus {
  val sentence = stringResource(status.label)
  return remember(status, sentence) {
    val head = sentence.substringBefore(SENTENCE_SEPARATOR).trim()
    val rest = sentence.substringAfter(SENTENCE_SEPARATOR, missingDelimiterValue = "").trim().ifEmpty { null }
    LibraryNetworkStatus(
      severity = libraryNetworkSeverity(status),
      label = head,
      detail = rest?.replaceFirstChar { it.titlecase() },
      glyph = libraryNetworkGlyph(status),
      action = libraryNetworkAction(status),
      description = sentence,
    )
  }
}

/** Il punto in mezzo delle frasi dell'indicatore globale: prima la cosa, poi la conseguenza (come in `MainScaffold`). */
private const val SENTENCE_SEPARATOR = " · "
