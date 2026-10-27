package dev.pampa.mycloudgallery.core.albums.contract

import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow

// PUBBLICAZIONE E REPLICA DEGLI ALBUM CONDIVISI (docs/fase7-progetto.md §1.2 "Pubblicazione", "Replica"; §2.5 passi 3 e 6, "Ricevere";
// D6-D9, D30, D38). Contratti di P0; il pubblicatore vero (teste di tipo 6 versione 3, segmenti classe `album`, `publishExclusive` solo
// sotto `albums/<id>/<proprio dispositivo>/`) e la replica (codec ostile, fusione, attestazione) sono di P2. Il formato (`AlbumEntry`,
// `AlbumValue`, `AlbumDocs`) e' di P2 in core-trust: qui ci sono le **modifiche** come le pensa chi le fa; ora del NAS, autore e account
// le aggiunge il pubblicatore. Non per la UI: feature-albums non vede core-trust (percorsi).

/**
 * Una modifica del **mio** contributo a un album condiviso (D7). Il pubblicatore la trasforma in una voce (`at` = ora del NAS, `author` =
 * questo dispositivo, `account` = il mio). Una modifica non permessa dalla tabella non si accoda (chi scrive non la offre, D10).
 */
sealed interface SharedAlbumChange {
  /**
   * Voce `definition`: titolo, cartella dell'album (relativa all'area comune), stato, account e nonce del creatore (l'id si ricalcola,
   * D7), e con [SharedState.CLOSED]/[SharedState.DELETED] la scelta per gli altri ([afterClose], Q5). [nonceHex] = 16 byte in esadecimale.
   */
  data class Definition(
    val title: String,
    val folder: SharePath,
    val state: SharedState,
    val creator: AccountId,
    val nonceHex: String,
    val afterClose: OthersAfterClose?,
  ) : SharedAlbumChange {
    init {
      require(NONCE_HEX.matches(nonceHex)) { "Il nonce sono 16 byte esadecimali." }
      require((state == SharedState.ACTIVE) == (afterClose == null)) { "La scelta per gli altri c'e' solo alla chiusura (Q5)." }
    }

    override fun toString(): String = "Change.Definition(${state.code}, ${afterClose?.code})"

    private companion object {
      val NONCE_HEX = Regex("^[0-9a-f]{32}$")
    }
  }

  /** Voce `level`: il livello nuovo (il pubblicatore lo accoda alla storia, al piu' [SharedAlbumLimits.MAX_LEVEL_CHANGES]). */
  data class Level(val level: ShareLevel) : SharedAlbumChange

  /**
   * Voce `level` con la **storia intera** cosi' com'e' (P2): solo per ricostituire un album il cui creatore e' uscito dalla famiglia (D11:
   * l'erede ripubblica la storia con le ore di allora, cosi' le voci degli altri si giudicano come prima). Da 1 a
   * [SharedAlbumLimits.MAX_LEVEL_CHANGES] passi, con l'ora strettamente crescente.
   */
  data class LevelHistory(val steps: List<LevelStep>) : SharedAlbumChange {
    init {
      require(steps.size in 1..SharedAlbumLimits.MAX_LEVEL_CHANGES) { "Da 1 a ${SharedAlbumLimits.MAX_LEVEL_CHANGES} passi." }
      require(steps.zipWithNext().all { (a, b) -> a.atNas < b.atNas }) { "Passi fuori ordine." }
    }
  }

  /** Voce `cover`: la copertina scelta, null = automatica. */
  data class Cover(val media: MediaId?, val contentKey: ContentKey?) : SharedAlbumChange {
    override fun toString(): String = "Change.Cover(${media != null})"
  }

  data class Sort(val sort: AlbumSort) : SharedAlbumChange

  /**
   * Voce `item:<media>` (§2.5 passo 6): solo per un file **gia' nell'area comune** (invariante 6 del §5.5). [path] relativo all'area
   * comune, mai dentro `.mycloudgallery`; [addedBy] = l'account che lo aggiunge (Q6: si mostra). [sha256] (P2): l'hash completo del file, se
   * chi aggiunge lo conosce (la verifica dello spostamento lo calcola sempre): chi legge lo confronta con il suo, se ce l'ha (D9).
   */
  data class Item(
    val media: MediaId,
    val version: VersionId?,
    val contentKey: ContentKey,
    val path: SharePath,
    val position: Long,
    val addedBy: AccountId,
    val sha256: Sha256? = null,
  ) : SharedAlbumChange {
    override fun toString(): String = "Change.Item"
  }

  /** Lapide di `item:<media>` ("Rimuovi dall'album", "Riporta"): il file resta dov'e' (D39). */
  data class ItemRemoved(val media: MediaId) : SharedAlbumChange {
    override fun toString(): String = "Change.ItemRemoved"
  }

  /** Voce `heir` (D11): l'erede e la versione del registro che lo giustifica. */
  data class Heir(val account: AccountId, val registryVersion: Long) : SharedAlbumChange {
    override fun toString(): String = "Change.Heir"
  }
}

/** Un passo della storia dei livelli: da [atNas] (ora del NAS) vale [level]. */
data class LevelStep(val atNas: Long, val level: ShareLevel)

/** Il numero d'ordine di una modifica accodata: cresce per album. Il diario della condivisione lo salva (ENTRY_STAGED → PUBLISHED). */
@JvmInline
value class StageTicket(val seq: Long)

/** Perche' si prova a pubblicare. */
enum class FlushReason(override val code: String) : StableCode {
  /** 30 s dopo l'ultima modifica (accorpamento, D6). */
  DEBOUNCE("debounce"),

  /** Il giro di sincronizzazione (`IndexSyncWorker`, dopo indice e dati personali). */
  SYNC_ROUND("sync_round"),

  /** Dopo una ripresa: si ripubblica lo stato intero del proprio contributo (§2.5 passo 6). */
  RESUME("resume"),
}

/** Perche' non si scrive adesso nell'area comune (D30, D38). Le scritture restano in coda, nessuna va persa. */
enum class AlbumWriteBlock(override val code: String) : StableCode {
  /** Area congelata (scansione con troppi spariti/cambiati). */
  FROZEN("frozen"),

  /** "Ferma le scritture sul NAS" della Diagnostica. */
  EMERGENCY_STOP("emergency_stop"),

  /** Salto dell'ora del NAS (F4-D32). */
  CLOCK_JUMP("clock_jump"),

  /** Questo dispositivo non e' fidato (registro). */
  NOT_TRUSTED("not_trusted"),

  /** Area in sola lettura per l'account. */
  READ_ONLY("read_only"),

  /** D30: album condivisi solo sulle share di prova ("Arriva dopo le prove"). */
  TEST_SHARES_ONLY("test_shares_only"),

  /** F4-D33, solo debug: `ShareFence` con un account di prova. */
  FENCE("fence"),

  /** Nessun NAS configurato o nessuna area comune nel registro. */
  NO_NAS("no_nas"),
}

/**
 * Il cancello delle scritture degli album (D38, §1.4): `IndexWriteRules` (congelamento, emergenza, salto dell'ora, fiducia, sola lettura)
 * + `FeatureFlags.sharedAlbumsMayUse` (D30) + `ShareFence` in debug. Lo lega :app (P13). null = si puo' scrivere in [share].
 */
fun interface AlbumWriteGate {
  suspend fun blockFor(share: String): AlbumWriteBlock?
}

/** L'esito di [AlbumPublisher.flush]. */
sealed interface PublishOutcome {
  /** Una testa nuova ([version]) con le modifiche fino a [upTo]. */
  data class Published(val version: Long, val upTo: StageTicket, val entries: Int) : PublishOutcome

  data object NothingToPublish : PublishOutcome

  /** Meno di [PublishRules.MIN_HEAD_INTERVAL_MS] dalla testa precedente: riprova fra [retryAfterMs] (monotoni). */
  data class TooSoon(val retryAfterMs: Long) : PublishOutcome

  data class Blocked(val reason: AlbumWriteBlock) : PublishOutcome

  /** Errore del NAS (rete, sessione): niente perso, si riprova. */
  data object Failed : PublishOutcome
}

/** I ritmi del pubblicatore (D6). */
object PublishRules {
  /** Al piu' una testa ogni 5 minuti per album e dispositivo. */
  const val MIN_HEAD_INTERVAL_MS: Long = 5L * 60 * 1000

  /** Modifiche accorpate in 30 s. */
  const val DEBOUNCE_MS: Long = 30L * 1000

  /** Pulizia dei propri file come `OwnFilesPolicy`: le ultime 3 teste, 7 giorni di grazia. */
  const val KEEP_HEADS: Int = 3
  const val GRACE_DAYS: Int = 7

  /**
   * R7 (revisione di sicurezza della Fase 7): l'eta' massima di una modifica alla pubblicazione. Una modifica rimasta in coda piu' a lungo (il
   * NAS irraggiungibile per ore) prende l'ora del NAS della pubblicazione. Cosi' chi legge sa che una voce **nuova** di un dispositivo non e'
   * mai piu' vecchia di questo rispetto all'ultima volta che ha guardato i suoi contributi, e scarta come retrodatata quella che lo e' (L59).
   */
  const val MAX_ENTRY_AGE_MS: Long = 60L * 60 * 1000

  /**
   * R7: di quanto l'ora dichiarata da una testa nuova puo' stare prima dell'ultima volta che chi legge ha visto la serie senza di lei (gli
   * orologi del NAS di due dispositivi non coincidono al millisecondo). Oltre, la testa e' retrodatata e si rifiuta.
   */
  const val HEAD_TIME_TOLERANCE_MS: Long = 10L * 60 * 1000
}

/**
 * Il pubblicatore del **mio** contributo (D6): accoda, accorpa, pubblica dietro [AlbumWriteGate]. **Durevole** (le modifiche accodate
 * sopravvivono alla morte del processo) e **idempotente** (la stessa modifica due volte = una voce; una ripresa ripubblica lo stato
 * intero). Scrive solo sotto `albums/<id>/<proprio dispositivo>/`, solo con `publishExclusive` (P2, test di proprieta').
 */
interface AlbumPublisher {
  /** Accoda [changes] per [album]; restituisce il numero d'ordine dell'ultima. Non scrive sul NAS. */
  suspend fun stage(album: AlbumId, changes: List<SharedAlbumChange>): StageTicket

  /** Prova a pubblicare cio' che e' in coda per [album]. Mai un'eccezione per un guasto previsto: lo dice il risultato. */
  suspend fun flush(album: AlbumId, reason: FlushReason): PublishOutcome

  /** Il numero d'ordine piu' alto gia' dentro una testa pubblicata (0 se nessuna). */
  suspend fun publishedUpTo(album: AlbumId): StageTicket

  /** Quante modifiche aspettano, in tutto. */
  val pending: StateFlow<Int>
}

/** Il resoconto di un giro di replica (D8): solo numeri, per la Diagnostica (§2.8). */
data class AlbumSyncReport(
  val albums: Int,
  val contributionsRead: Int,
  /** Contributi interi rifiutati (firma, id che non torna, tetti, campi): `ALBUM_CONTRIB_REJECTED`. */
  val contributionsRejected: Int,
  /** Voci scartate per permesso: `ALBUM_ENTRY_DENIED`. */
  val entriesDenied: Int,
  /** Elementi senza file nell'area comune: `ALBUM_ITEM_UNATTESTED`. */
  val itemsUnattested: Int,
  /** Il budget e' finito prima della fine: il giro dopo riprende. */
  val incomplete: Boolean,
  /** R2 (revisione di sicurezza della Fase 7): i byte letti dal NAS nel giro (teste e segmenti), per il conto del traffico del lavoro. */
  val bytesRead: Long = 0,
  /** R2: contributi in attesa (un segmento sparito, un file illeggibile, voci "dal futuro"): si riprovano a intervalli crescenti. */
  val contributionsHeld: Int = 0,
)

/**
 * La replica degli album condivisi (§2.5 "Ricevere"): elenca `albums/` dell'area comune, verifica ogni contributo (D8), scarta le voci non
 * permesse (D10), fonde (D7), **attesta** gli elementi sul catalogo di chi legge (D9) e proietta. **Mai un'azione sui file**: nessuna
 * chiamata ad `AlbumMover`, `AlbumUploader`, `SafeCopyRequester` (test con porte che falliscono se chiamate, §4.3).
 */
interface SharedAlbumSync {
  suspend fun sync(budget: Duration): AlbumSyncReport
}
