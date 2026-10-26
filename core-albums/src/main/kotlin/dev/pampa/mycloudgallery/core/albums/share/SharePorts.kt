package dev.pampa.mycloudgallery.core.albums.share

import dev.pampa.mycloudgallery.core.albums.contract.AlbumClocks
import dev.pampa.mycloudgallery.core.albums.contract.AlbumFamily
import dev.pampa.mycloudgallery.core.albums.contract.AlbumMover
import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.AlbumPublisher
import dev.pampa.mycloudgallery.core.albums.contract.AlbumTitles
import dev.pampa.mycloudgallery.core.albums.contract.AlbumUploader
import dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteGate
import dev.pampa.mycloudgallery.core.albums.contract.CatalogFacts
import dev.pampa.mycloudgallery.core.albums.contract.SafeCopyRequester
import dev.pampa.mycloudgallery.core.albums.contract.ShareDiary
import dev.pampa.mycloudgallery.core.albums.contract.ShareFolder
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemAction
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpId
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumFacts
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumLimits
import dev.pampa.mycloudgallery.core.albums.contract.SharedOrigins
import dev.pampa.mycloudgallery.core.albums.contract.TicketStatus
import dev.pampa.mycloudgallery.core.albums.contract.TransferKey
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.text.Normalizer
import java.util.Locale

// L'ORCHESTRATORE DELLA CONDIVISIONE (docs/fase7-progetto.md §2.5, §2.6; D12, D28, D29, D31, D38, D39; P4): le porte, i nomi delle cartelle
// degli album e le chiavi dei trasferimenti. core-albums non vede core-nasops ne' core-backup: tutto cio' che sposta, carica o chiede il
// guardiano passa dalle porte del contratto (`AlbumMover`, `AlbumUploader`, `SafeCopyRequester`), legate da :app (P13), e solo con la
// `ShareConfirmation` del foglio.

/** Il titolo e gli elementi mostrabili di un album personale, per "Condividi" su un album che esiste gia' (P3 lo lega ai dati personali). */
data class SharedFromPersonal(val title: String, val items: List<MediaId>) {
  override fun toString(): String = "SharedFromPersonal(${items.size})"
}

/**
 * Le porte dell'orchestratore vero ([ShareOrchestrator]). Le stesse del riferimento (`ShareRunnerPorts` di testing/, per la suite di contratto),
 * piu' alcune che servono solo qui, tutte con un valore che non fa danni:
 * - [personal]: titolo ed elementi di un album personale da condividere (null = non c'e');
 * - [publishedId]: l'id con cui e' pubblicato l'elemento che il mio catalogo vede come `media` (per la lapide di "Riporta"); di serie il media
 *   stesso se e' fra gli elementi;
 * - [knownFolders]: le cartelle degli album che la replica conosce, per dare a un album nuovo una cartella libera (E-SH22);
 * - [folderContents]: i media che il mio catalogo vede nella cartella dell'album (adozione dei file aggiunti dal PC, D39); null = non so;
 * - [lastPosition] e [linkPersonal]: l'ordine degli elementi nuovi (in fondo) e il collegamento di un album personale condiviso.
 * Nessuna di queste nasce da un dato del NAS che faccia partire qualcosa: servono a scrivere il piano, che l'utente conferma.
 */
class ShareOrchestratorPorts(
  val diary: ShareDiary,
  val facts: CatalogFacts,
  val shared: SharedAlbumFacts,
  /** La tabella del §2.4 applicata a me (`SharedAlbums.allowed`). */
  val allowed: (AlbumId, AlbumOp, MediaId?) -> Boolean,
  val mover: AlbumMover,
  val uploader: AlbumUploader,
  val tickets: TicketStatus,
  val publisher: AlbumPublisher,
  val safeCopies: SafeCopyRequester,
  val origins: SharedOrigins,
  val family: AlbumFamily,
  val gate: AlbumWriteGate,
  val clocks: AlbumClocks,
  val personal: suspend (AlbumId) -> SharedFromPersonal? = { null },
  val publishedId: suspend (AlbumId, MediaId) -> MediaId? = { album, media -> media.takeIf { it in shared.members(album) } },
  val knownFolders: suspend () -> Set<SharePath> = { emptySet() },
  val folderContents: suspend (ShareFolder) -> Set<MediaId>? = { null },
  /** La posizione piu' alta fra gli elementi dell'album (le mie voci in coda comprese): un elemento nuovo va in fondo (D21). Null = nessuno. */
  val lastPosition: suspend (AlbumId) -> Long? = { null },
  /** "Condividi" su un album personale: la voce personale `shared` che lo collega all'album condiviso nuovo (D4; P3 la scrive). */
  val linkPersonal: suspend (personal: AlbumId, shared: AlbumId) -> Unit = { _, _ -> },
  /** Dopo ogni modifica accodata al pubblicatore: la vista locale si ricalcola (P13 la lega a `SharedAlbumReplica.recompute`). */
  val afterStage: suspend (AlbumId) -> Unit = { },
)

/**
 * La cartella di un album condiviso nell'area comune (risposta Q1 (a): "Album condivisi/<nome>"; E-SH22): il titolo resta quello scelto,
 * la cartella ha un nome che il NAS e Windows accettano. Caratteri vietati da Windows e di controllo diventano `_`, punti e spazi finali si
 * tolgono, al piu' [AlbumTitles.MAX_CHARS] caratteri e [MAX_NAME_BYTES] byte, un nome riservato (`CON`, `LPT1.foto`...) prende un `_` prima del
 * primo punto; un nome che resta non valido diventa "Album". Una cartella gia' usata da un altro album che conosco prende " (2)", " (3)"...
 */
object AlbumFolderNames {
  private val FORBIDDEN = Regex("""[\\/:*?"<>|\p{Cntrl}]""")
  private const val MAX_NAME_BYTES = 200
  private const val MAX_ALTERNATIVE = 99
  private const val FALLBACK = "Album"

  /** Il nome della cartella per [title] (senza la radice). Deterministico: lo stesso titolo da' sempre lo stesso nome. */
  fun nameFor(title: String): String {
    for (candidate in listOf(title, Normalizer.normalize(title, Normalizer.Form.NFKC))) {
      val cleaned = clean(candidate)
      if (cleaned.isNotEmpty() && SharePath.isValidComponent(cleaned)) return cleaned
    }
    return FALLBACK
  }

  private fun clean(raw: String): String {
    var s = raw.replace(FORBIDDEN, "_").trim().trimEnd('.', ' ')
    s = takeCodePoints(s, AlbumTitles.MAX_CHARS)
    while (s.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) s = s.substring(0, s.offsetByCodePoints(s.length, -1))
    s = s.trimEnd('.', ' ')
    // Windows guarda il nome prima del primo punto ("LPT1.foto" e' riservato come "LPT1"): il `_` va li'.
    val base = s.substringBefore('.').trimEnd(' ').uppercase(Locale.ROOT)
    if (base in RESERVED) {
      val dot = s.indexOf('.')
      s = if (dot < 0) s + "_" else s.substring(0, dot) + "_" + s.substring(dot)
    }
    return s
  }

  private fun takeCodePoints(s: String, max: Int): String =
    if (s.codePointCount(0, s.length) <= max) s else s.substring(0, s.offsetByCodePoints(0, max))

  /**
   * La cartella dell'album ("Album condivisi/<nome>") libera fra quelle [inUse] (confronto senza maiuscole): il primo fra "<nome>", "<nome> (2)"...
   * Deterministica dati [title] e [inUse].
   */
  fun folderFor(title: String, inUse: Set<SharePath>): SharePath {
    val used = inUse.map { it.value.lowercase(Locale.ROOT) }.toSet()
    val name = nameFor(title)
    for (n in 1..MAX_ALTERNATIVE) {
      val candidate = if (n == 1) name else alternative(name, n)
      val path = SharePath.of(SharedAlbumLimits.FOLDER_ROOT, candidate)
      if (path.value.lowercase(Locale.ROOT) !in used) return path
    }
    return SharePath.of(SharedAlbumLimits.FOLDER_ROOT, alternative(name, MAX_ALTERNATIVE + 1))
  }

  private fun alternative(name: String, n: Int): String {
    val suffix = " ($n)"
    val room = (TrustLimits.MAX_COMPONENT_UTF8_BYTES - suffix.length).coerceAtLeast(1)
    var base = name
    while (base.toByteArray(Charsets.UTF_8).size > room) base = base.substring(0, base.offsetByCodePoints(base.length, -1))
    return base + suffix
  }

  private val RESERVED: Set<String> = buildSet {
    addAll(listOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$", "CLOCK$"))
    for (suffix in listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "¹", "²", "³")) {
      add("COM$suffix")
      add("LPT$suffix")
    }
  }
}

/**
 * Le chiavi dei trasferimenti di un'operazione (scritte nel diario **prima** di chiamare la porta: la porta e' idempotente per chiave e
 * `TicketStatus.find` ritrova il trasferimento avviato da un processo morto). Una per tipo di trasferimento; per "Riporta" una per cartella
 * d'origine ([group]). Null per cio' che non si trasferisce.
 */
object ShareTransferKeys {
  fun keyFor(op: ShareOpId, action: ShareItemAction, group: Int = 0): TransferKey? = when (action) {
    ShareItemAction.MOVE_IN -> TransferKey("${op.value}:in")
    ShareItemAction.UPLOAD -> TransferKey("${op.value}:up")
    ShareItemAction.MOVE_OUT -> TransferKey("${op.value}:out:$group")
    ShareItemAction.REFERENCE, ShareItemAction.ADOPT -> null
  }
}

/** Le misure dell'orchestratore. */
object ShareRules {
  /** Le posizioni degli elementi a intervalli (D21): si inserisce senza riscrivere gli altri. */
  const val POSITION_STEP: Long = 1_000

  /** La cartella d'origine di un media spostato: la cartella del file (la radice della share se il file sta li'). */
  fun originOf(source: SharePath): SharePath? = source.parent()
}
