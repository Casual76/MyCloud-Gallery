package dev.pampa.mycloudgallery.core.release.contract

import dev.pampa.mycloudgallery.core.model.StableCode
import java.time.Instant

// LE PORTE DEL MANIFEST (docs/fase10-progetto.md §1.2 "Porte", §1.4 "ReleaseGraph"). Le lega :app (P13): la sorgente sul NAS con la sessione
// esistente, il deposito in `noBackupFilesDir/release/`, l'ora del NAS, la versione dell'app.

/** Da dove arriva il manifest. **Solo il NAS** (Q8 (a), 31/10): nessuna origine Internet, nessun host nuovo. */
enum class ManifestOrigin(override val code: String) : StableCode {
  NAS("nas"),
}

/** Una lettura della sorgente. */
sealed interface ManifestRead {
  /** Il file non c'e' (prima installazione, tolto da un familiare: E-REL1, L552): nessun effetto. */
  data object Absent : ManifestRead

  /** Oltre il tetto chiesto: la sorgente non legge oltre (si conta come `RejectReason.TOO_LARGE`). */
  data object TooLarge : ManifestRead

  /** NAS non raggiungibile o errore di lettura: si riprova al giro dopo, niente cambia. */
  data object Unavailable : ManifestRead

  class Found(bytes: ByteArray) : ManifestRead {
    private val content: ByteArray = bytes.copyOf()

    val bytes: ByteArray get() = content.copyOf()

    override fun toString(): String = "ManifestRead.Found(${content.size} B)"
  }
}

/** La sorgente del manifest (D29): un file solo, al piu' [maxBytes], con la sessione gia' aperta (nessuna connessione in piu'). */
interface ManifestSource {
  val origin: ManifestOrigin

  suspend fun read(maxBytes: Int = ManifestLimits.MAX_BYTES): ManifestRead
}

/** Lo stato salvato (D30): il `seq` piu' alto accettato, l'ultimo manifest valido, la `versionCode` per cui vale [status]. */
data class StoredRelease(
  val seq: Long,
  val manifest: ReleaseManifest?,
  val keyId: Int?,
  val versionCode: Int,
  val status: ReleaseStatus,
) {
  init {
    require(seq >= 0 && versionCode >= 1) { "Stato non valido." }
    require((manifest == null) == (seq == 0L)) { "Un seq senza manifest (o il contrario)." }
  }

  override fun toString(): String = "StoredRelease(seq=$seq, versione=$versionCode, $status)"
}

/** Il deposito dello stato (D30, file atomico). [read] su un file rovinato risponde `null` (come assente): mai un crollo. */
interface ReleaseStateStore {
  suspend fun read(): StoredRelease?

  /** Scrittura atomica (temporaneo, `fsync`, rinomina): se muore a meta' resta lo stato di prima. */
  suspend fun write(state: StoredRelease)

  suspend fun clear()
}

/**
 * L'ora per la scadenza (E-REL9): l'ora del NAS quando c'e', altrimenti la piu' tarda fra l'ultima ora del NAS vista e l'orologio del
 * telefono (:app). Mai l'ora del telefono letta da core-release.
 */
fun interface ReleaseClock {
  fun bestNow(): Instant
}

/** Il canale della build (D33): `DEBUG` le build di sviluppo, `BETA` i dispositivi dell'admin, `STABLE` la famiglia (Q7 (a)). */
enum class ReleaseChannel(override val code: String) : StableCode {
  DEBUG("debug"),
  BETA("beta"),
  STABLE("stable"),
}

/** La versione di questa app: `versionCode`, `versionName` (semantico, D33) e canale. */
data class AppVersion(val code: Int, val name: String, val channel: ReleaseChannel) {
  init {
    require(code >= 1) { "versionCode non valida." }
  }
}
