package dev.pampa.mycloudgallery.core.albums.contract

import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.model.enumByCodeOrNull
import java.security.MessageDigest
import kotlinx.coroutines.flow.StateFlow

// GLI ALBUM CONDIVISI (docs/fase7-progetto.md §1.2 "Condivisi", §2.4, §2.6; D10, D11, D31 adeguata alla risposta Q5 del 22/10; E-SH7,
// E-SH11, E-SH13, E-SH21). Contratto di P0; la replica, la fusione, i permessi letti e il pubblicatore sono di P2, la chiusura e il
// "Riporta" di P4, i fogli di P10. Lo stesso `allowed` decide cosa offre la UI e coincide con cio' che chi legge accetta (C25).
//
// **Q5 (22/10): quando si smette di condividere o si elimina un album, lo decide chi lo fa.** Il foglio chiede al creatore (o all'erede)
// di scegliere fra [OthersAfterClose.REMOVE] (l'album sparisce dalle Raccolte degli altri, con un avviso) e [OthersAfterClose.KEEP_COPY]
// (ognuno si tiene una copia personale con le stesse foto, finche' le foto restano nell'area comune). La scelta **viaggia nella
// definizione firmata** (voce `definition`, campo `afterClose`, P2): ogni dispositivo che legge la chiusura fa la stessa cosa. In
// entrambi i casi i file restano dove sono, salvo "Riporta nelle mie cartelle".

/** Smettere di condividere o eliminare (D31). */
enum class CloseMode(override val code: String) : StableCode {
  /** Stato `closed`: per il creatore l'album diventa personale con gli stessi elementi. */
  STOP("stop"),

  /** Stato `deleted`: per il creatore l'album sparisce. */
  DELETE("delete"),
}

/**
 * Cosa succede agli **altri** quando l'album si chiude (risposta Q5 del 22/10). Il codice viaggia nella definizione firmata: si aggiunge,
 * non si cambia. Un codice sconosciuto letto da un'app piu' nuova vale [REMOVE] (la scelta che non crea niente).
 */
enum class OthersAfterClose(override val code: String) : StableCode {
  /** (a) L'album sparisce dalle Raccolte degli altri, con l'avviso "«…» non e' piu' condiviso". */
  REMOVE("remove"),

  /** (b) Ognuno si tiene una copia personale dell'album con le stesse foto, finche' le foto restano nell'area comune. */
  KEEP_COPY("keep_copy"),
  ;

  companion object {
    fun fromCode(code: String?): OthersAfterClose = enumByCodeOrNull<OthersAfterClose>(code) ?: REMOVE
  }
}

/**
 * La copia personale di Q5 (b). Il suo id e' **deterministico** per (album condiviso, account): i dispositivi dello stesso account che
 * leggono la chiusura scrivono le stesse voci (fusione idempotente, nessun doppione), e l'id e' diverso dall'album condiviso (che
 * resta chiuso per sempre). Anche il creatore, dopo "Smetti di condividere", ha il suo album personale (D31): se l'album era nato
 * personale ha gia' il suo id (D7), altrimenti usa questo. La copia porta la voce `copy_of` (core-personal `ALBUM_COPY_OF`). Una copia
 * che l'utente elimina non si ricrea (P3/P4: lapide della definizione, mai riscritta da una chiusura gia' vista).
 */
object SharedAlbumCopies {
  fun copyIdFor(shared: AlbumId, account: AccountId): AlbumId {
    val md = MessageDigest.getInstance("SHA-256")
    md.update("mcg-album-copy\u0000".toByteArray(Charsets.UTF_8))
    md.update(account.value.toByteArray(Charsets.UTF_8))
    md.update(0)
    md.update(shared.value.toByteArray(Charsets.UTF_8))
    val hex = md.digest().copyOf(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return AlbumId("${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}")
  }
}

/**
 * Il riepilogo del foglio del creatore (§1.5 `CloseSheetState`): cosa succede, quanti miei media posso riportare nelle mie cartelle
 * ([bringBackCandidates]: aggiunti dal mio account, con la cartella d'origine nei miei dati personali), quanti familiari lo vedono oggi.
 * [bringBackHurtsCopies]: se scelgo [OthersAfterClose.KEEP_COPY] **e** riporto i miei media, quei media spariscono anche dalle copie
 * degli altri (escono dall'area comune): il foglio lo dice.
 */
data class ClosePreview(
  val album: AlbumId,
  val title: String,
  val mode: CloseMode,
  val bringBackCandidates: List<MediaId>,
  val otherMembers: Int,
) {
  val bringBackHurtsCopies: Boolean get() = bringBackCandidates.isNotEmpty() && otherMembers > 0

  override fun toString(): String = "ClosePreview(${mode.code}, riportabili=${bringBackCandidates.size}, altri=$otherMembers)"
}

sealed interface ClosePreviewOutcome {
  data class Ready(val preview: ClosePreview) : ClosePreviewOutcome

  data class NotAllowed(val op: AlbumOp) : ClosePreviewOutcome

  data object NotFound : ClosePreviewOutcome
}

sealed interface CloseOutcome {
  /** La definizione (`closed`/`deleted` con [OthersAfterClose]) e' in coda al pubblicatore. Nessun file toccato. */
  data object Closed : CloseOutcome

  data class NotAllowed(val op: AlbumOp) : CloseOutcome

  data class Blocked(val reason: AlbumWriteBlock) : CloseOutcome

  data object NotFound : CloseOutcome
}

sealed interface InheritOutcome {
  /** La voce `heir` e' in coda: da adesso ho i diritti del creatore (D11). */
  data object Inherited : InheritOutcome

  /** Questo dispositivo non e' admin. */
  data object NotAdmin : InheritOutcome

  /** Il creatore ha ancora un dispositivo approvato o dismesso nel registro firmato: nessuna eredita' (D11). */
  data object CreatorStillInFamily : InheritOutcome

  data class Blocked(val reason: AlbumWriteBlock) : InheritOutcome

  data object NotFound : InheritOutcome
}

/** Un avviso una volta sola sugli album condivisi (§2.5 "Ricevere", punto 4; E-SH7, E-SH11, E-SH13, E-SH16; D39; Q5). */
sealed interface SharedAlbumNotice {
  val album: AlbumId
  val title: String

  /**
   * "«…» non e' piu' condiviso" (album chiuso o eliminato da chi l'ha creato). [keptCopy] e' la mia copia personale se chi ha chiuso
   * ha scelto [OthersAfterClose.KEEP_COPY] (Q5 b): "ne hai una copia tua".
   */
  data class Closed(override val album: AlbumId, override val title: String, val mode: CloseMode, val keptCopy: AlbumId?) : SharedAlbumNotice {
    override fun toString(): String = "Notice.Closed(${mode.code}, copia=${keptCopy != null})"
  }

  /** E-SH13: "Non aggiunte: l'album ora e' in sola visione", con "Riporta nelle mie cartelle" per [count] miei media. */
  data class NotAddedLevelChanged(override val album: AlbumId, override val title: String, val count: Int) : SharedAlbumNotice {
    override fun toString(): String = "Notice.NotAdded($count)"
  }

  /** D11: "Sei il nuovo responsabile di «…»". */
  data class Inherited(override val album: AlbumId, override val title: String) : SharedAlbumNotice {
    override fun toString(): String = "Notice.Inherited"
  }

  /**
   * E-SH16: la cartella e' cambiata dal PC; il creatore puo' adottare quella nuova (azione locale, [SharedAlbums.useNewFolder]). [newFolder] (P13) e'
   * la cartella dove stanno adesso gli elementi, relativa all'area comune, da mostrare ("usa «…»"); null = non si sa (nessuna azione). Dato
   * dell'utente: mai nei log.
   */
  data class FolderChanged(override val album: AlbumId, override val title: String, val newFolder: String? = null) : SharedAlbumNotice {
    override fun toString(): String = "Notice.FolderChanged(${newFolder != null})"
  }

  /**
   * E-SH20 (P13): [count] elementi che un mio dispositivo revocato aveva aggiunto e che il mio catalogo vede ancora nell'area comune: "Ripristina gli
   * elementi" ([SharedAlbums.restoreRevoked]) li ripubblica a nome di questo dispositivo. Nessun file si muove.
   */
  data class RevokedItems(override val album: AlbumId, override val title: String, val count: Int) : SharedAlbumNotice {
    override fun toString(): String = "Notice.RevokedItems($count)"
  }

  /** D39: "N file nuovi nella cartella: aggiungili" (aggiunti dal PC; azione locale con conferma). */
  data class FilesAddedFromPc(override val album: AlbumId, override val title: String, val count: Int) : SharedAlbumNotice {
    override fun toString(): String = "Notice.FilesAddedFromPc($count)"
  }
}

/**
 * Gli album condivisi visti da questo dispositivo (D6-D11). [visible]: gli album **attivi** che non ho lasciato; un album chiuso esce da
 * qui e lascia un [SharedAlbumNotice.Closed] (e, con Q5 b, una copia personale fra gli album personali). La creazione e l'aggiunta
 * passano da [ShareRunner] (piano, conferma, diario): qui c'e' la parte che non sposta file.
 */
interface SharedAlbums {
  val visible: StateFlow<List<Album.Shared>>

  val notices: StateFlow<List<SharedAlbumNotice>>

  /** Il piano di un album condiviso nuovo con [media] (D12): `ShareRunner.plan(ShareRequest.NewAlbum(...))`. Niente si scrive. */
  suspend fun create(title: String, level: ShareLevel, media: List<MediaId>, hidden: HiddenChoice = HiddenChoice.DEFAULT): SharePlanOutcome

  /** Rinomina (titolo; la cartella si rinomina a parte, con un'operazione locale confermata, §2.4): voce `definition`. */
  suspend fun rename(id: AlbumId, title: String): EditOutcome

  /** "Rimuovi dall'album" (D39): lapidi `item:` (i file restano); per ogni media la riga del §2.4 secondo chi l'ha aggiunto. */
  suspend fun remove(id: AlbumId, media: List<MediaId>): EditOutcome

  /** Voce `cover` (null = automatica). */
  suspend fun setCover(id: AlbumId, media: MediaId?): EditOutcome

  /** Voce `sort`. */
  suspend fun setSort(id: AlbumId, sort: AlbumSort): EditOutcome

  /** Cambia il livello (solo il creatore o l'erede): voce `level`, al piu' [SharedAlbumLimits.MAX_LEVEL_CHANGES] cambi nella storia. */
  suspend fun setLevel(id: AlbumId, level: ShareLevel): EditOutcome

  /** Il riepilogo del foglio "Smetti di condividere" / "Elimina l'album". */
  suspend fun closePreview(id: AlbumId, mode: CloseMode): ClosePreviewOutcome

  /**
   * Chiude l'album (solo il creatore o l'erede). [forOthers] e' **obbligatorio** (Q5: lo decide chi lo fa; nessun valore di default nel
   * contratto). Nessun file toccato: "Riporta nelle mie cartelle" e' un piano a parte (`ShareRequest.BringBack`).
   */
  suspend fun close(id: AlbumId, mode: CloseMode, forOthers: OthersAfterClose): CloseOutcome

  /** "Non mostrare piu'" (E-SH21): voce personale `shared_album_left:<id>`; si ritrova da "Album nascosti dalle Raccolte". */
  suspend fun leave(id: AlbumId): EditOutcome

  /** Annulla [leave]. */
  suspend fun rejoin(id: AlbumId): EditOutcome

  /** Gli album lasciati (per "Album nascosti dalle Raccolte"). */
  val left: StateFlow<List<Album.Shared>>

  /** E-SH11: un dispositivo admin eredita un album il cui creatore non ha piu' dispositivi nel registro (azione locale confermata). */
  suspend fun inherit(id: AlbumId): InheritOutcome

  /**
   * La tabella del §2.4 applicata a **me** su [id] adesso (livello in vigore, creatore, erede; [item] per le operazioni su un elemento,
   * per sapere chi l'ha aggiunto). Falso per un album sconosciuto o chiuso. Le condizioni della cella le valuta l'implementazione.
   */
  fun allowed(id: AlbumId, op: AlbumOp, item: MediaId? = null): Boolean

  /**
   * Tutte le operazioni della tabella del §2.4 che [allowed] da' per vere a me su [id] adesso (la scorciatoia che P0 prevedeva per P10: le
   * schermate costruiscono `PermissionInfo` senza chiamare `allowed` a mano per ogni operazione). Con [item] valgono per quell'elemento
   * (chi l'ha aggiunto decide "Togli dall'album", "Riporta"...). Ha un corpo: nessuna implementazione deve cambiare.
   */
  fun allowedOps(id: AlbumId, item: MediaId? = null): Set<AlbumOp> = AlbumOp.entries.filterTo(LinkedHashSet()) { allowed(id, it, item) }

  suspend fun dismissNotice(notice: SharedAlbumNotice)

  /**
   * E-SH16 (P13): "Usa la nuova cartella": la definizione dell'album prende la cartella dove gli elementi stanno adesso ([SharedAlbumNotice.FolderChanged]).
   * Solo il creatore o l'erede; nessun file si muove (la cartella l'ha gia' cambiata il PC). Ha un corpo: nessuna implementazione deve cambiare.
   */
  suspend fun useNewFolder(id: AlbumId): EditOutcome = EditOutcome.NotAllowed(null)

  /**
   * E-SH20 (P13): "Ripristina gli elementi" di un mio dispositivo revocato ([SharedAlbumNotice.RevokedItems]): tornano come mie voci, solo quelli i cui
   * file il mio catalogo vede nell'area comune, dietro gli stessi cancelli delle scritture. Ha un corpo: nessuna implementazione deve cambiare.
   */
  suspend fun restoreRevoked(id: AlbumId): EditOutcome = EditOutcome.NotAllowed(null)
}

/** I tetti degli album condivisi (A4 del §0.3 del progetto; formato §7). */
object SharedAlbumLimits {
  const val MAX_LEVEL_CHANGES: Int = 32
  const val MAX_ITEMS: Int = 20_000
  const val MAX_ENTRIES_PER_SEGMENT: Int = 8_192
  const val MAX_ENTRY_BYTES: Int = 4 * 1024
  const val MAX_PATH_BYTES: Int = 1_024

  /** La cartella degli album condivisi nell'area comune (risposta Q1 (a)): "Album condivisi/<nome>". */
  const val FOLDER_ROOT: String = "Album condivisi"
}
