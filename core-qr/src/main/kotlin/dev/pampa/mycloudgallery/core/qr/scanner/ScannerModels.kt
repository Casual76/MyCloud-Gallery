package dev.pampa.mycloudgallery.core.qr.scanner

import androidx.compose.runtime.Immutable
import dev.pampa.mycloudgallery.core.qr.contract.ScanRejection

/** Lo stato del permesso della fotocamera, come lo vede la schermata. */
internal enum class CameraAccess {
  /** Concesso: si apre il mirino. */
  Granted,

  /** Mai chiesto (in questa sessione): si spiega perche' serve e si chiede. */
  NotAsked,

  /** Negato una volta: il sistema chiedera' ancora. Si spiega, si offre di riprovare e il ripiego a mano. */
  Denied,

  /** Negato per sempre ("non chiedere piu'", o negato due volte): il sistema non mostra piu' la domanda. */
  DeniedPermanently,

  /** Nessuna fotocamera utilizzabile (o non parte): solo il ripiego a mano. */
  NoCamera,
}

/** La torcia, se il dispositivo ne ha una. */
@Immutable
internal data class TorchUi(val available: Boolean = false, val on: Boolean = false)

/**
 * Tutto quel che la schermata dello scanner mostra, in un valore: da qui si disegna ogni pagina, dal vero e
 * negli screenshot.
 */
@Immutable
internal data class ScannerUi(
  val access: CameraAccess,
  /** L'utente ha scelto di inserire il codice a mano anche se la fotocamera sarebbe disponibile. */
  val manualOpen: Boolean = false,
  val manualText: String = "",
  /** L'ultimo motivo di rifiuto, finche' non scade o arriva un altro esito. */
  val rejection: ScanRejection? = null,
  /** Un codice e' stato accettato: lo scanner ha finito. */
  val read: Boolean = false,
  val torch: TorchUi = TorchUi(),
) {

  /** La pagina da mostrare. */
  val page: ScannerPage
    get() = when {
      // Dopo la lettura resta la pagina in cui ci si trovava, cosi' l'annuncio per il lettore di schermo
      // e' il cambio di testo di un nodo che esiste gia', non un nodo nuovo.
      access == CameraAccess.Granted && !manualOpen -> ScannerPage.Viewfinder
      access == CameraAccess.NotAsked && !manualOpen -> ScannerPage.Ask
      else -> ScannerPage.Manual
    }
}

/** Le tre pagine dello scanner. */
internal enum class ScannerPage {
  /** Spiega perche' serve la fotocamera e chiede il permesso. */
  Ask,

  /** La fotocamera con il mirino. */
  Viewfinder,

  /** Il campo di testo: ripiego dopo un permesso negato, senza fotocamera, o scelta dell'utente. */
  Manual,
}

/** Le azioni che la schermata puo' chiedere (la stessa cosa dal vero e negli screenshot). */
internal class ScannerActions(
  val onRequestPermission: () -> Unit = {},
  val onOpenSettings: () -> Unit = {},
  val onOpenManual: () -> Unit = {},
  val onCloseManual: () -> Unit = {},
  val onManualTextChange: (String) -> Unit = {},
  val onManualSubmit: () -> Unit = {},
  val onToggleTorch: () -> Unit = {},
  val onCancel: () -> Unit = {},
)
