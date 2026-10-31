package dev.pampa.mycloudgallery.core.release.contract

import dev.pampa.mycloudgallery.core.model.StableCode
import java.time.Duration
import java.time.Instant

// IL MANIFEST DI RILASCIO (docs/fase10-progetto.md §1.2 "core-release", §2.8, D29; C440, C441). Contratto di P0. Un file canonico firmato che
// puo' solo **restringere**: versione minima, blocchi di versioni, indirizzi in piu' per modelli gia' nel catalogo dell'app. Mai testo libero
// (i messaggi sono un catalogo chiuso nell'app), mai un host nuovo, mai un modello nuovo, mai codice, mai cancellare dati. Nessun `toString()`
// mostra URL o il contenuto oltre ai numeri.

/** Il livello di un blocco (D29). */
enum class BlockLevel(override val code: String) : StableCode {
  /** Sola lettura, come "Ferma le scritture sul NAS": nessuna scrittura sul NAS, nessun lavoro che scrive; Libreria e viewer funzionano. */
  WRITES("writes"),

  /** "Questa versione e' stata fermata": nessuna sessione del NAS, nessun lavoro, nessun traffico; restano Diagnostica ed esportazione. */
  ALL("all"),
  ;

  companion object {
    /** Un livello che l'app non conosce vale [ALL]: un blocco non si perde e non si ammorbidisce per un campo nuovo (regola per il codec di P1). */
    fun ofCode(code: String): BlockLevel = entries.firstOrNull { it.code == code } ?: ALL
  }
}

/**
 * Il **catalogo chiuso** dei messaggi (D29, §3 "Testo dei messaggi"): il manifest porta solo un codice, il testo e' nell'app
 * (`strings_release_*.xml`, P13). Un testo libero sarebbe un canale per ingannare la famiglia ("inserisci qui la password").
 */
enum class ReleaseMessage(override val code: String) : StableCode {
  /** "Aggiorna MyCloud Gallery dal Pampa Store". */
  UPDATE("update"),

  /** "Questa versione ha un problema di sicurezza: aggiorna dal Pampa Store". */
  SECURITY("security"),

  /** "Questa versione puo' rovinare dei dati sul NAS: aggiorna dal Pampa Store". */
  DATA_RISK("data"),
  ;

  companion object {
    /** Un codice che l'app non conosce vale [UPDATE]: un blocco non si perde per un testo (regola per il codec di P1). */
    fun ofCode(code: String): ReleaseMessage = entries.firstOrNull { it.code == code } ?: UPDATE
  }
}

/** Un blocco: le `versionCode` da [fromCode] a [toCode] comprese, con il livello e il messaggio. */
data class VersionBlock(val fromCode: Int, val toCode: Int, val level: BlockLevel, val message: ReleaseMessage) {
  init {
    require(fromCode in 1..toCode) { "Intervallo di versioni non valido." }
  }

  operator fun contains(versionCode: Int): Boolean = versionCode in fromCode..toCode
}

/**
 * Indirizzi `https` **in piu'** per un modello gia' nel catalogo dell'app (D29): [id] e [sha256] devono coincidere con una voce di `ModelCatalog`
 * (lo controlla :app con core-models), ogni URL passa da `PinnedUrl.of` (host di `ModelHosts`, niente porta, utente, query). L'impronta fissata
 * decide sempre: un indirizzo verso un file diverso produce un file rifiutato, mai accettato (E-REL7). Qui solo i tetti di forma.
 */
data class ModelHint(val id: String, val sha256: String, val urls: List<String>) {
  init {
    require(ID.matches(id)) { "Id di modello non valido." }
    require(SHA256.matches(sha256)) { "Impronta non valida." }
    require(urls.size in 1..ManifestLimits.MAX_URLS_PER_MODEL) { "Troppi o nessun indirizzo." }
    require(urls.all { it.length <= ManifestLimits.MAX_URL_CHARS && it.startsWith("https://") }) { "Indirizzo non valido." }
  }

  override fun toString(): String = "ModelHint(${urls.size} indirizzi)"

  private companion object {
    val ID = Regex("^[a-z0-9][a-z0-9.-]{2,63}$")
    val SHA256 = Regex("^[0-9a-f]{64}$")
  }
}

/**
 * Il manifest decodificato e **gia' verificato** (lo costruisce solo il codec di core-release, P1). [seq] cresce soltanto (anti-rigioco,
 * D30); [expires] al piu' [ManifestLimits.MAX_VALIDITY] dopo [issued]; [minVersionCode] sotto = sola lettura con "Aggiorna".
 */
data class ReleaseManifest(
  val schema: Int,
  val app: String,
  val seq: Long,
  val issued: Instant,
  val expires: Instant,
  val minVersionCode: Int?,
  val blocks: List<VersionBlock>,
  val models: List<ModelHint>,
) {
  init {
    require(schema >= 1) { "Schema non valido." }
    require(seq >= 1) { "Sequenza non valida." }
    require(expires.isAfter(issued) && !expires.isAfter(issued.plus(ManifestLimits.MAX_VALIDITY))) { "Scadenza non valida." }
    require(minVersionCode == null || minVersionCode >= 1) { "Versione minima non valida." }
    require(blocks.size <= ManifestLimits.MAX_BLOCKS) { "Troppi blocchi." }
    require(models.size <= ManifestLimits.MAX_MODELS) { "Troppi modelli." }
  }

  override fun toString(): String = "ReleaseManifest(schema=$schema, seq=$seq, blocchi=${blocks.size}, modelli=${models.size})"
}

/**
 * La busta e i tetti (§1.2 "Busta", D29): file `MCGR1` = intestazione fissa ‖ lunghezza ‖ JSON canonico ‖ `keyId` (1 byte) ‖ firma DER
 * ECDSA P-256 su SHA-256. Il codec di P1 li applica **prima** di leggere il JSON; i campi sconosciuti facoltativi si ignorano, un campo
 * sconosciuto marcato `critical` rifiuta la busta intera (`RejectReason.CRITICAL_FIELD`).
 */
object ManifestLimits {
  const val MAGIC: String = "MCGR1"
  const val SCHEMA: Int = 1
  const val MAX_BYTES: Int = 64 * 1024
  const val MAX_BLOCKS: Int = 32
  const val MAX_MODELS: Int = 16
  const val MAX_URLS_PER_MODEL: Int = 4
  const val MAX_URL_CHARS: Int = 2 * 1024
  val MAX_VALIDITY: Duration = Duration.ofDays(180)

  /** Il manifest si rilegge al piu' una volta ogni 24 ore, nel giro di sincronizzazione esistente (e all'avvio se e' passato di piu'). */
  val READ_EVERY: Duration = Duration.ofHours(24)

  /** Il nome fisso del file nell'area comune: `<area comune>/.mycloudgallery/release/manifest.mcgr` (`docs/formato-nas.md`). */
  const val NAS_PATH: String = ".mycloudgallery/release/manifest.mcgr"

  /** L'id di questa app: un manifest per un'altra app e' `RejectReason.WRONG_APP`. */
  const val APP_ID: String = "dev.pampa.mycloudgallery"
}
