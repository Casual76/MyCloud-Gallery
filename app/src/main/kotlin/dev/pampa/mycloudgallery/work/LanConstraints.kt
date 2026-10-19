package dev.pampa.mycloudgallery.work

import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.Constraints
import androidx.work.NetworkType

/**
 * Il vincolo di rete di tutti i lavori in background (verifica funzionale, RE-11): **una rete qualunque, con o senza internet**.
 *
 * `NetworkType.CONNECTED` di WorkManager (e `NETWORK_TYPE_ANY` di JobScheduler) chiede una rete con `NET_CAPABILITY_INTERNET` e
 * `NET_CAPABILITY_VALIDATED`: con un router senza collegamento a internet (il FRITZ!Box senza WAN, un guasto del provider) il Wi-Fi di
 * casa non e' "validato", il vincolo non e' mai soddisfatto e nessun lavoro parte, mentre il NAS e' in LAN e risponde benissimo
 * (docs/rete-casa-vpn.md §5: «casa vale anche senza internet»). Qui si chiede solo un trasporto (Wi-Fi, Ethernet, cellulare), senza
 * internet ne' validazione: se la rete serve davvero, e se e' casa, lo decide il motore delle regole dentro il lavoro (rete non
 * riconosciuta, offline, NAS irraggiungibile), non il vincolo.
 */
internal object LanConstraints {

  /** La richiesta di rete: ogni trasporto fisico, senza `INTERNET` e senza `VALIDATED` (tolti esplicitamente, per non dipendere dai default). */
  fun request(): NetworkRequest = NetworkRequest.Builder()
    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    .removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    .build()

  /**
   * Il costruttore dei vincoli con la rete gia' impostata. Il tipo di rete di riserva ([NetworkType.CONNECTED]) vale solo prima di
   * Android 9, che l'app non supporta (minSdk 29): sopra, comanda la richiesta.
   */
  fun builder(): Constraints.Builder = Constraints.Builder().setRequiredNetworkRequest(request(), NetworkType.CONNECTED)

  fun anyNetwork(): Constraints = builder().build()
}
