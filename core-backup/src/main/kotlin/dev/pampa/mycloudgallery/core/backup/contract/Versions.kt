package dev.pampa.mycloudgallery.core.backup.contract

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.model.VersionId

// Fase 5B (docs/fase5-progetto.md §1.2 "Versioni (5B)", §1.5 `VersionsState`, §2.4, D17, D18, D35; riga P0-B del §4.4): le VERSIONI di un
// media sul NAS e "Ripristina originale". Contratto, null-object e fake di P0-B; la logica (i cammini `VERSION` e `SWAP` del diario, le
// rinomine con `archiveVersion` e `restoreVersion`) e' di P8. L'elenco delle versioni lo legge la UI (viewer ⋮ → Versioni) da qui; le
// versioni intermedie si eliminano solo dal foglio di `feature-nas` (cestino del NAS, D20): questo servizio non toglie niente.

/**
 * Che ruolo ha una versione fra quelle di un media (§1.5: originale, intermedie, attuale). L'**attuale** non e' un ruolo ma un attributo
 * ([VersionEntry.isCurrent]): dopo "Ripristina originale" l'originale e' anche l'attuale.
 */
enum class VersionRole(override val code: String) : StableCode {
  /** La prima versione conosciuta (`VersionKind.ORIGINAL`). Una sola per media. */
  ORIGINAL("original"),

  /** Ogni altra versione (modificata fuori dall'app, corretta dall'app, del PC): se non e' l'attuale e' una "intermedia", si toglie solo a mano (D17). */
  EDITED("edited"),
}

/** Dove sta una versione adesso. */
enum class VersionPlace(override val code: String) : StableCode {
  /** Una copia sul telefono (MediaStore). */
  PHONE("phone"),

  /** Sul NAS al solito percorso (la versione attuale). */
  NAS_USUAL_PATH("nas_usual_path"),

  /** Sul NAS in `<share>/Versioni/…` (le precedenti, D17). */
  NAS_VERSIONS_FOLDER("nas_versions_folder"),

  /** Sul NAS, ma solo nell'area comune: la copia privata non c'e' ancora (il guardiano, D9). */
  NAS_COMMON_ONLY("nas_common_only"),
}

/**
 * Una riga dell'elenco delle versioni (viewer ⋮ → Versioni): data, dimensione, dove sta, se e' al sicuro. [savedAtNasMillis] e' l'ora del NAS
 * in cui la versione e' comparsa (F4-D32), null se non nota. Nessun nome di file ne' percorso.
 */
data class VersionEntry(
  val versionId: VersionId,
  val role: VersionRole,
  /** E' la versione che la Libreria mostra e che sta al solito percorso sul NAS. */
  val isCurrent: Boolean,
  val size: Long,
  val savedAtNasMillis: Long?,
  val places: Set<VersionPlace>,
  /** Ha una copia privata riletta e verificata (D14): quella di cui "Libera spazio" e "Ripristina originale" si fidano. */
  val safe: Boolean,
) {
  init {
    require(size >= 0) { "Dimensione negativa." }
  }

  override fun toString(): String = "VersionEntry(${role.code}${if (isCurrent) ", attuale" else ""}, $size B)"
}

/** In quale area sta il media (D35): cambia che cosa succede salvando una versione nuova. */
enum class VersionArea(override val code: String) : StableCode {
  /** L'area privata dell'utente: il caso normale. */
  OWN_PRIVATE("own_private"),

  /** L'area comune: la versione nuova si scrive accanto all'originale solo dopo il foglio "La modifica sara' visibile a tutti i familiari". */
  COMMON_NEEDS_NOTICE("common_needs_notice"),

  /** Un'area senza permesso di scrittura (E-N9): la modifica diventa un media nuovo in `Backup/<dispositivo>/Modificate/` e le info lo dicono. */
  READ_ONLY_NEW_MEDIA("read_only_new_media"),
}

/** Se "Ripristina originale" si puo' fare adesso, o perche' no. */
enum class RestoreAvailability(override val code: String) : StableCode {
  AVAILABLE("available"),

  /** C'e' una sola versione: non c'e' niente da ripristinare. */
  ONLY_ONE_VERSION("only_one_version"),

  /** L'originale e' gia' quello attuale. */
  ALREADY_ORIGINAL("already_original"),

  /** L'originale non ha una copia privata verificata: non si sposta ne' si riscrive un file senza poter tornare indietro. */
  ORIGINAL_NOT_SAFE("original_not_safe"),

  /** Il NAS non risponde (E-B9). */
  NAS_UNREACHABLE("nas_unreachable"),

  /** Area congelata o scritture ferme (E-B19, E-B22, D32). */
  FROZEN("frozen"),

  /** Nell'area in sola lettura (E-N9). */
  READ_ONLY_AREA("read_only_area"),

  /** Non e' un media di questo account. */
  NOT_MINE("not_mine"),

  /** Nessuna implementazione legata (null-object). */
  NOT_AVAILABLE("not_available"),
}

/**
 * Il piano delle versioni di un media (§1.5 `VersionsState`): le versioni dall'originale all'attuale, dove stanno, e se "Ripristina originale"
 * e' possibile. E' anche il piano che la conferma dell'utente firma: [hash] copre il media, le versioni (con ruolo e luoghi) e la
 * disponibilita', quindi una versione nuova arrivata dopo il foglio fa rifiutare la conferma (`WrongPlan`).
 */
class NasVersionPlan(
  val mediaId: MediaId,
  val versions: List<VersionEntry>,
  val area: VersionArea,
  val restore: RestoreAvailability,
) : ConfirmablePlan {

  /** Le versioni intermedie (ne' l'originale ne' l'attuale), per "Elimina le intermedie" (che il foglio di `feature-nas` esegue, con il cestino del NAS). */
  val intermediates: List<VersionEntry> get() = versions.filter { it.role == VersionRole.EDITED && !it.isCurrent }

  val original: VersionEntry? get() = versions.firstOrNull { it.role == VersionRole.ORIGINAL }

  val current: VersionEntry? get() = versions.firstOrNull { it.isCurrent }

  override val requiredConfirmations: Int = 1

  override val hash: PhonePlanHash = PhonePlanHash.builder("versions")
    .string(mediaId.value)
    .string(area.code)
    .string(restore.code)
    .list(versions) { v ->
      string(v.versionId.value)
      string(v.role.code)
      bool(v.isCurrent)
      long(v.size)
      long(v.savedAtNasMillis)
      bool(v.safe)
      list(v.places.sortedBy { it.code }) { place -> string(place.code) }
    }
    .build()

  override fun toString(): String = "NasVersionPlan(${versions.size} versioni, ${restore.code})"
}

/** L'esito di "Ripristina originale" ([VersionKeeper.restoreOriginal]). Solo numeri e codici. */
sealed interface RestoreOutcome {
  /** L'avvio e' fatto: lo scambio (`SWAP`, nessun byte caricato) e' nel diario; il motore lo porta avanti. */
  data object Queued : RestoreOutcome

  /**
   * Il NAS e' a posto ma la copia del telefono e' la versione modificata e va riscritta (E-E10): serve il permesso di sistema per [rows]
   * (`writeRequest`). La UI chiede il dialogo e richiama `restoreOriginal` con il [PhoneWriteGrant]; negato, la copia del telefono resta la
   * versione modificata e l'app lo dice.
   */
  data class NeedsPhoneWrite(val rows: List<PhoneRef>) : RestoreOutcome

  /** La conferma non vale per il piano di adesso. */
  data class Rejected(val check: PhoneConfirmationCheck) : RestoreOutcome

  /** Il piano non permette il ripristino ([RestoreAvailability] diverso da `AVAILABLE`). */
  data class Unavailable(val reason: RestoreAvailability) : RestoreOutcome
}

/**
 * Le versioni di un media e "Ripristina originale" (D17, D18, D35). **Nessun byte di un'altra versione si perde e nessun file si toglie**:
 * l'attuale e l'originale cambiano posto con due rinomine (`SWAP`) senza sostituire; ogni passo e' nel diario del backup prima della chiamata.
 */
interface VersionKeeper {

  /** Il piano delle versioni di [mediaId], com'e' adesso (vuoto se il media non esiste). */
  suspend fun plan(mediaId: MediaId): NasVersionPlan

  /**
   * "Ripristina originale" (E-E2, E-E10): l'originale torna al solito percorso, l'attuale va in `Versioni/`, nessun caricamento. [confirmation]
   * si controlla contro il piano **ricalcolato adesso**. [writeGrant], se serve riscrivere la copia del telefono, e' il permesso del dialogo
   * di sistema per quelle righe: senza, la risposta e' [RestoreOutcome.NeedsPhoneWrite].
   */
  suspend fun restoreOriginal(mediaId: MediaId, confirmation: PhoneConfirmation, writeGrant: PhoneWriteGrant? = null): RestoreOutcome
}
