package dev.pampa.mycloudgallery.feature.viewer.download

import dev.pampa.mycloudgallery.core.model.MediaRef
import kotlinx.coroutines.flow.StateFlow

// "Scarica sul telefono" visto dal viewer (Fase 4, P8; docs/fase4-progetto.md §2.6, D20). Il viewer non scarica: chiede a
// un [PhoneDownload] di partire e ne mostra lo stato. Cosi' il download sopravvive al viewer (l'utente scarica e chiude) e
// la stessa cosa serve alla selezione della Libreria (P11): `start` per ogni media scelto, uno dopo l'altro.

/** Perche' un download non e' riuscito. Codici stabili: finiscono nel messaggio, mai un nome di file. */
enum class DownloadFailure {
  /** Il NAS non risponde, la lettura si e' fermata o la sessione e' caduta. */
  NAS_UNREACHABLE,

  /** Questa rete non e' tra quelle ammesse. */
  NOT_ALLOWED_HERE,

  /** D-15: fuori casa con la VPN di casa spenta: il NAS non si raggiunge, nemmeno con i dati mobili. */
  VPN_OFF_AWAY,

  /** Il media non c'e' piu' (catalogo o NAS). */
  NOT_FOUND,

  /** Il telefono non ha piu' posto. Nessun file a meta' e' rimasto. */
  PHONE_FULL,

  /** Dimensione o hash non sono quelli attesi: il file e' cambiato mentre lo si leggeva o l'indice non e' aggiornato. */
  INTEGRITY,

  /** Tutto il resto (MediaStore, collegamento al catalogo, tipo non supportato). */
  OTHER,
}

/** A che punto e' il download di un media. */
sealed interface DownloadState {

  /** In coda: se ne scarica uno alla volta. */
  data object Queued : DownloadState

  /** In corso; [progress] da 0 a 1 se la dimensione e' nota. */
  data class Running(val progress: Float?) : DownloadState

  /** Servono i dati mobili e l'utente non ha ancora confermato per quel media (E-V2). */
  data class NeedsMobileData(val sizeBytes: Long?) : DownloadState

  /** Salvato in `Pictures/MyCloud Gallery` e collegato al catalogo. [displayName] e' un dato dell'utente: mai nei log. */
  data class Done(val displayName: String) : DownloadState {
    override fun toString(): String = "Done"
  }

  data class Failed(val reason: DownloadFailure) : DownloadState

  /** Annullato dall'utente: il temporaneo e' stato tolto. */
  data object Cancelled : DownloadState

  /** Concluso (bene, male o annullato): il viewer lo mostra una volta e poi lo scarta con [PhoneDownload.acknowledge]. */
  val isTerminal: Boolean get() = this is Done || this is Failed || this is Cancelled
}

/**
 * "Scarica sul telefono". Un'istanza per app (la costruisce :app, P11), con un proprio scope: un download non muore con
 * il viewer.
 */
interface PhoneDownload {

  /** Questo media si puo' scaricare qui e adesso? Compare la voce nel menu a capacita' solo se si'. */
  fun canDownload(ref: MediaRef): Boolean

  /** Lo stato di ogni download conosciuto, per id della posizione (`MediaRef.Nas.locationId`). */
  val states: StateFlow<Map<Long, DownloadState>>

  /** Mette in coda [ref] (se non c'e' gia' un download in corso o in coda per lui). Non sospende. */
  fun start(ref: MediaRef.Nas)

  /** Annulla il download di [ref], in coda o in corso: il temporaneo si toglie. */
  fun cancel(ref: MediaRef.Nas)

  /** L'utente ha visto l'esito di [locationId]: lo stato terminale esce dalla mappa. D-15: anche "servono i dati mobili", che con la rete cambiata non e' piu' vero. */
  fun acknowledge(locationId: Long)
}
