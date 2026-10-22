package dev.pampa.mycloudgallery.core.albums.contract

import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.model.StableCode

// I PERMESSI DEGLI ALBUM CONDIVISI COME TABELLA DATI (docs/fase7-progetto.md §2.4, proposta 7, D10, D11; C25). Scritta da P0 riga per
// riga dal §2.4; **la logica e' di P2**: l'esame di una voce letta (`AlbumPermissions.check(entry, levels, creator, heir, previous)`, con
// il livello in vigore all'ora `at` della voce, la storia dei livelli e la voce precedente) si aggiunge in shared/ come estensione di
// [AlbumPermissions] sopra questa tabella. La **stessa** tabella la usano chi scrive (che non offre l'azione: SharedAlbums, P10) e chi
// legge (che scarta la voce: P2): stesso codice, stesso esito, un test generato dalla tabella per ogni riga e ogni livello.
//
// Ruoli (un account puo' averne piu' d'uno): CREATOR = l'account del creatore o l'erede dopo la voce `heir` (D11); ADDER = l'account che
// ha aggiunto l'elemento di cui si parla (`addedBy`); OTHER = ogni account della famiglia che non e' CREATOR; ADMIN = un account con un
// dispositivo admin nel registro.

/** Le operazioni su un album condiviso (§2.4). Le prime sei sono "sempre permesse a tutti" (personali o di sola lettura). */
enum class AlbumOp(override val code: String) : StableCode {
  VIEW("view"),
  DOWNLOAD("download"),
  SHARE_OUT("share_out"),
  FAVORITE_OR_HIDE("favorite_or_hide"),
  ADD_TO_PERSONAL_ALBUM("add_to_personal_album"),

  /** "Non mostrare piu'" (E-SH21): chiunque non sia il creatore. */
  LEAVE("leave"),

  /** Aggiungere media (spostamento confermato, D12). */
  ADD_MEDIA("add_media"),

  /** Togliere dall'album un elemento (aggiunto da se' o da altri: la riga la decide il ruolo ADDER). */
  REMOVE_ITEM("remove_item"),

  /** Eliminare il file (cestino del NAS, E-D8): solo chi scrive puo' controllarlo (L57). */
  DELETE_FILE("delete_file"),

  /** Nuova versione nell'area comune (modifica, correzione, F5-D35); altrimenti la modifica nasce nell'area privata (E-E9). */
  NEW_VERSION("new_version"),

  /** Rinominare l'album (titolo; la cartella con un'operazione locale confermata). */
  RENAME("rename"),
  SET_COVER("set_cover"),
  SET_SORT("set_sort"),

  /** "Riporta nelle mie cartelle" un **proprio** media (D31). */
  BRING_BACK("bring_back"),

  /** Adottare i file aggiunti dal PC nella cartella dell'album (D39): come aggiungere, senza spostamenti. */
  ADOPT_FOLDER_FILES("adopt_folder_files"),
  CHANGE_LEVEL("change_level"),
  STOP_SHARING("stop_sharing"),
  DELETE_ALBUM("delete_album"),

  /** Ereditare l'album (E-SH11): un admin, solo con il creatore uscito dalla famiglia. */
  INHERIT("inherit"),
}

enum class AlbumRole { CREATOR, ADDER, OTHER, ADMIN }

/** Una condizione oltre al ruolo, che valuta chi applica la tabella (P2 per chi legge, P4 e P10 per chi scrive). */
enum class PermissionCondition {
  NONE,

  /** "Riporta" a "Gli altri guardano": solo per un elemento aggiunto quando il livello lo permetteva (E-SH13). */
  ADDED_WHILE_PERMITTED,

  /** Eredita': il registro firmato non ha piu' nessun dispositivo approvato ne' dismesso dell'account del creatore (D11). */
  CREATOR_LEFT_FAMILY,
}

/** Chi controlla (§2.4, ultima colonna). */
enum class Enforcement {
  /** Azione personale o di sola lettura: niente da controllare nell'album. */
  PERSONAL,

  /** Solo chi scrive non la offre: sul NAS non c'e' una voce da scartare (L57). */
  WRITER_ONLY,

  /** Chi scrive non la offre **e** chi legge scarta la voce ([PermissionRow.readerField]). */
  WRITER_AND_READER,
}

/** Una cella della tabella: i ruoli ammessi a un livello, con la condizione. */
data class PermissionCell(val roles: Set<AlbumRole>, val condition: PermissionCondition = PermissionCondition.NONE)

/** Una riga del §2.4: un'operazione, la cella per ogni livello, chi la controlla e la voce che chi legge esamina. */
data class PermissionRow(val op: AlbumOp, val cells: Map<ShareLevel, PermissionCell>, val enforcement: Enforcement, val readerField: String?) {
  init {
    require(cells.keys == ShareLevel.entries.toSet()) { "Una riga ha una cella per ogni livello." }
  }
}

/** La tabella del §2.4, come dati. */
object AlbumPermissions {

  private val creator = setOf(AlbumRole.CREATOR)
  private val everyone = setOf(AlbumRole.CREATOR, AlbumRole.OTHER)
  private val creatorAndAdder = setOf(AlbumRole.CREATOR, AlbumRole.ADDER)

  private fun row(op: AlbumOp, all: Set<AlbumRole>, add: Set<AlbumRole>, view: Set<AlbumRole>, enforcement: Enforcement, field: String?) =
    PermissionRow(op, mapOf(ShareLevel.ALL to PermissionCell(all), ShareLevel.ADD to PermissionCell(add), ShareLevel.VIEW to PermissionCell(view)), enforcement, field)

  private fun same(op: AlbumOp, roles: Set<AlbumRole>, enforcement: Enforcement, field: String?, condition: PermissionCondition = PermissionCondition.NONE) =
    PermissionRow(op, ShareLevel.entries.associateWith { PermissionCell(roles, condition) }, enforcement, field)

  /** Le righe, nell'ordine del §2.4 (prima le azioni sempre permesse). */
  val rows: List<PermissionRow> = listOf(
    same(AlbumOp.VIEW, everyone, Enforcement.PERSONAL, null),
    same(AlbumOp.DOWNLOAD, everyone, Enforcement.PERSONAL, null),
    same(AlbumOp.SHARE_OUT, everyone, Enforcement.PERSONAL, null),
    same(AlbumOp.FAVORITE_OR_HIDE, everyone, Enforcement.PERSONAL, null),
    same(AlbumOp.ADD_TO_PERSONAL_ALBUM, everyone, Enforcement.PERSONAL, null),
    same(AlbumOp.LEAVE, setOf(AlbumRole.OTHER), Enforcement.PERSONAL, null),
    // Aggiungere media: tutti / tutti / solo il creatore. Chi legge scarta la voce `item:` non permessa all'ora `at`.
    row(AlbumOp.ADD_MEDIA, everyone, everyone, creator, Enforcement.WRITER_AND_READER, "item"),
    // Togliere un elemento: aggiunto da se' tutti / tutti / solo il creatore; aggiunto da altri tutti / solo il creatore / solo il creatore.
    // Lapide `item:` confrontata con `addedBy` della voce precedente.
    row(AlbumOp.REMOVE_ITEM, everyone, creatorAndAdder, creator, Enforcement.WRITER_AND_READER, "item"),
    // Eliminare il file e nuova versione: tutti / il creatore e chi l'ha aggiunto / solo il creatore. Solo chi scrive (L57).
    row(AlbumOp.DELETE_FILE, everyone, creatorAndAdder, creator, Enforcement.WRITER_ONLY, null),
    row(AlbumOp.NEW_VERSION, everyone, creatorAndAdder, creator, Enforcement.WRITER_ONLY, null),
    // Rinominare, copertina, ordine: tutti / solo il creatore / solo il creatore.
    row(AlbumOp.RENAME, everyone, creator, creator, Enforcement.WRITER_AND_READER, "definition"),
    row(AlbumOp.SET_COVER, everyone, creator, creator, Enforcement.WRITER_AND_READER, "cover"),
    row(AlbumOp.SET_SORT, everyone, creator, creator, Enforcement.WRITER_AND_READER, "sort"),
    // "Riporta" un proprio media: si' / si' / si' se aggiunto quando il livello lo permetteva.
    PermissionRow(
      AlbumOp.BRING_BACK,
      mapOf(
        ShareLevel.ALL to PermissionCell(setOf(AlbumRole.ADDER)),
        ShareLevel.ADD to PermissionCell(setOf(AlbumRole.ADDER)),
        ShareLevel.VIEW to PermissionCell(setOf(AlbumRole.ADDER), PermissionCondition.ADDED_WHILE_PERMITTED),
      ),
      Enforcement.WRITER_AND_READER,
      "item",
    ),
    // Adottare i file del PC: come aggiungere.
    row(AlbumOp.ADOPT_FOLDER_FILES, everyone, everyone, creator, Enforcement.WRITER_AND_READER, "item"),
    same(AlbumOp.CHANGE_LEVEL, creator, Enforcement.WRITER_AND_READER, "level"),
    same(AlbumOp.STOP_SHARING, creator, Enforcement.WRITER_AND_READER, "definition"),
    same(AlbumOp.DELETE_ALBUM, creator, Enforcement.WRITER_AND_READER, "definition"),
    same(AlbumOp.INHERIT, setOf(AlbumRole.ADMIN), Enforcement.WRITER_AND_READER, "heir", PermissionCondition.CREATOR_LEFT_FAMILY),
  )

  private val byOp: Map<AlbumOp, PermissionRow> = rows.associateBy { it.op }

  init {
    require(byOp.size == rows.size && byOp.keys == AlbumOp.entries.toSet()) { "Una riga per ogni operazione." }
  }

  fun row(op: AlbumOp): PermissionRow = byOp.getValue(op)

  fun cell(op: AlbumOp, level: ShareLevel): PermissionCell = row(op).cells.getValue(level)

  /**
   * I ruoli di [actor] rispetto a un album con creatore [creator] ed erede [heir] (null finche' nessuna voce `heir` valida), e rispetto
   * all'elemento aggiunto da [addedBy] (null se l'operazione non riguarda un elemento). [actorIsAdmin] dal registro firmato.
   */
  fun rolesOf(actor: AccountId, creator: AccountId, heir: AccountId?, addedBy: AccountId?, actorIsAdmin: Boolean): Set<AlbumRole> {
    val roles = LinkedHashSet<AlbumRole>()
    val isCreator = actor == creator || (heir != null && actor == heir)
    roles += if (isCreator) AlbumRole.CREATOR else AlbumRole.OTHER
    if (addedBy != null && actor == addedBy) roles += AlbumRole.ADDER
    if (actorIsAdmin) roles += AlbumRole.ADMIN
    return roles
  }

  /** Vero se uno dei [roles] e' nella cella di [op] a [level]. **La condizione della cella la valuta chi chiama.** */
  fun admits(op: AlbumOp, level: ShareLevel, roles: Set<AlbumRole>): Boolean = cell(op, level).roles.any { it in roles }
}
