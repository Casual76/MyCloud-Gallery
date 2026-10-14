package dev.pampa.mycloudgallery.core.nasmedia.contract

import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow

// La SCANSIONE del NAS (docs/fase4-progetto.md §2.3, D9, D11, D22; E-N1–E-N16, E-M12, E-S11). Contratto di P0, logica
// di P5. :app (P11) costruisce un NasScanner per share (`NasGraph`) e lo esegue da `NasScanWorker` (INDEX_SYNC, a tappe).

/** Che giro fare. */
sealed interface ScanMode {
  /** Prima scansione o giro completo: dai piu' recenti, con ripresa dal cursore (E-P4). */
  data object Full : ScanMode

  /**
   * Verifica: si elencano **tutte** le cartelle incluse e si confrontano i timbri; si legge solo cio' che e' nuovo o
   * cambiato (D9). E' anche un giro completo (puo' segnare assenze).
   */
  data object Verify : ScanMode

  /** Solo una cartella (e le sue sottocartelle): Cartelle NAS aperte dall'utente, 4B. Non segna assenze. */
  data class Folder(val path: SharePath) : ScanMode {
    override fun toString(): String = "Folder"
  }
}

/**
 * Le esclusioni di una share (D11, E-N8, E-N14, E-N15, E-N16), gia' risolte da :app: quelle dell'area comune dalla
 * configurazione firmata (`commonExclusions`), quelle dell'area privata dalle scelte locali dell'onboarding. Le cartelle
 * di sistema, i cestini del NAS e le cartelle col punto (E-N16) sono escluse **sempre**, qui non si elencano.
 */
data class ScanSettings(
  val excluded: List<SharePath> = emptyList(),
  /** "Mostra le copertine degli album" (E-N14): spento di default. */
  val showAlbumCovers: Boolean = false,
  /**
   * Fuori dal Wi-Fi di casa il rilevamento legge al massimo questi byte per giro (D22: 50 MB); `null` = nessun tetto
   * (a casa). Lo decide :app dalle regole delle attivita'.
   */
  val detectionByteCap: Long? = null,
) {
  override fun toString(): String = "ScanSettings(${excluded.size} esclusioni, copertine=$showAlbumCovers)"
}

/** Lo stato della scansione di una share, per il piede della Libreria (`LibraryNasStatus.FirstScan`) e la Diagnostica. */
sealed interface NasScanStatus {
  data object Idle : NasScanStatus

  /** In corso: [filesTotal] e' noto solo dopo il primo giro completo (null alla prima scansione). */
  data class Scanning(val mode: ScanMode, val foldersDone: Int, val filesSeen: Int, val detected: Int, val filesTotal: Int?) : NasScanStatus

  /** Ultimo giro finito (o fermato) con questo resoconto. */
  data class Finished(val report: ScanReport) : NasScanStatus

  /** La share e' congelata (D10): nessuna assenza segnata, nessuna scrittura automatica sul NAS. */
  data class Frozen(val freeze: Freeze) : NasScanStatus

  /** Il NAS o la share non rispondono ([code] stabile, es. `NAS_UNREACHABLE`, `ACCESS_DENIED`). */
  data class Unavailable(val code: String) : NasScanStatus
}

/** Perche' un giro si e' fermato prima della fine (si riprende dal cursore). */
enum class ScanStop {
  /** Tempo della tappa finito (D22: 8 minuti). */
  BUDGET,

  /** Tetto del rilevamento fuori casa raggiunto (D22). */
  BYTE_CAP,

  /** Le regole delle attivita' hanno detto di fermarsi. */
  RULES,

  /** NAS irraggiungibile o share negata. */
  UNAVAILABLE,

  /** Congelata a fine giro (D10): le assenze non si segnano. */
  FROZEN,
}

/** Il resoconto di un giro (solo numeri: va in Diagnostica). */
data class ScanReport(
  val mode: ScanMode,
  val folders: Int,
  val files: Int,
  /** Timbro uguale al catalogo: nessuna lettura (D9). */
  val unchanged: Int,
  /** Record dell'indice con lo stesso timbro adottati senza leggere il file (D6). */
  val adopted: Int,
  /** Letti testa e coda (64 KiB + 64 KiB) per l'impronta e i metadati. */
  val detected: Int,
  /** In arrivo (E-N4): nome temporaneo, troppo recenti per l'ora del NAS o in crescita fra due elenchi. */
  val arriving: Int,
  /** Nomi non gestibili da `SharePath` (E-N6): saltati e contati. */
  val skippedNames: Int,
  /** Nascosti, di sistema, punti di rianalisi (E-N8, E-N10, E-N16). */
  val skippedSystem: Int,
  /** Copertine degli album escluse (E-N14). */
  val albumCovers: Int,
  /** Zero byte o falsi media (E-M12). */
  val notMedia: Int,
  /** File bloccati dal PC (E-N13): si riprova al giro dopo. */
  val busy: Int,
  /** Posizioni segnate assenti a fine giro (solo con giro completo e senza congelamento). */
  val vanished: Int,
  val bytesRead: Long,
  /** Il giro e' arrivato in fondo (solo cosi' si segnano le assenze). */
  val complete: Boolean,
  val resumed: Boolean,
  val stoppedBy: ScanStop? = null,
  val freeze: Freeze? = null,
)

/**
 * La scansione di **una** share con l'account di questo dispositivo (§2.3). [scan] rispetta [budget] e il cursore: un
 * giro interrotto riprende dalla cartella dopo l'ultima completata. Nessuna scrittura sul NAS, nessuna operazione sui
 * file: cambia solo lo stato nel catalogo locale (E-S11).
 */
interface NasScanner {
  val status: StateFlow<NasScanStatus>

  suspend fun scan(mode: ScanMode, budget: Duration): ScanReport
}
