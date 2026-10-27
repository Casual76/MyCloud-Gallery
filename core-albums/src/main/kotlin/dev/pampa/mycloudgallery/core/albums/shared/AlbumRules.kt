package dev.pampa.mycloudgallery.core.albums.shared

import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.AlbumPermissions
import dev.pampa.mycloudgallery.core.albums.contract.AlbumRole
import dev.pampa.mycloudgallery.core.albums.contract.PermissionCondition
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.trust.data.AlbumDefinitionState
import dev.pampa.mycloudgallery.core.trust.data.AlbumEntry
import dev.pampa.mycloudgallery.core.trust.data.AlbumField
import dev.pampa.mycloudgallery.core.trust.data.AlbumIds
import dev.pampa.mycloudgallery.core.trust.data.AlbumValue
import dev.pampa.mycloudgallery.core.trust.data.LevelChange

// LA LOGICA DEI PERMESSI DEGLI ALBUM CONDIVISI (docs/fase7-progetto.md §2.4, D10, D11; C25). La tabella e' quella di P0
// (`AlbumPermissions` in contract/, come dati); qui c'e' cio' che P0 lasciava a P2: l'esame di una voce letta ([check], chi legge) e la
// stessa regola per chi scrive ([SharedAlbumRules.allows]). Stesso codice, stessa tabella: un'azione che chi scrive non offre e' una voce
// che chi legge scarta, e viceversa (test generato dalla tabella per ogni riga e livello, `AlbumPermissionsLogicTest`).

/**
 * La storia dei livelli di un album (voce `level`, D10): il livello in vigore a un'ora del NAS e' quello dell'ultimo cambio non successivo.
 * **Prima del primo cambio vale "Gli altri guardano"** (solo il creatore): un album senza una voce `level` valida non da' a nessun altro il
 * permesso di cambiarlo, e una voce retrodatata a prima della creazione non passa.
 */
class LevelHistory(val changes: List<LevelChange>) {
  init {
    require(changes.zipWithNext().all { (a, b) -> a.at < b.at }) { "Storia dei livelli fuori ordine." }
  }

  fun at(time: Long): ShareLevel = changes.lastOrNull { it.at <= time }?.level ?: BEFORE_FIRST

  /** Il livello dopo l'ultimo cambio (quello che il foglio mostra). */
  val current: ShareLevel get() = changes.lastOrNull()?.level ?: BEFORE_FIRST

  override fun equals(other: Any?): Boolean = other is LevelHistory && other.changes == changes

  override fun hashCode(): Int = changes.hashCode()

  override fun toString(): String = "LevelHistory(${changes.size} cambi, ${current.code})"

  companion object {
    val BEFORE_FIRST: ShareLevel = ShareLevel.VIEW
    val NONE: LevelHistory = LevelHistory(emptyList())
  }
}

/** Perche' una voce si scarta (conteggi per `ALBUM_ENTRY_DENIED`, E-SH13: [NOT_PERMITTED] e' il livello o il ruolo). */
enum class DenyReason {
  /** Il livello all'ora della voce non lo permette a quel ruolo (o manca la condizione della cella). */
  NOT_PERMITTED,

  /** Dopo la chiusura (D31: `closed`/`deleted` sono definitivi). */
  CLOSED,

  /** Una definizione prima della creazione, o una creazione che non e' del creatore, o non attiva. */
  NOT_CREATED,

  /** La definizione nomina un altro creatore o un altro nonce (l'album e' un altro). */
  IDENTITY,

  /** Un elemento che cambia proprietario (`addedBy`) o un'eredita' scritta a nome di un altro account. */
  OWNERSHIP,

  /** L'eredita' cita un registro che chi legge non conosce ancora. */
  REGISTRY_AHEAD,
}

/** L'esito dell'esame di una voce: ammessa (con l'operazione che rappresenta, null = nessun effetto) o scartata. */
sealed interface PermissionVerdict {
  val op: AlbumOp?

  data class Allowed(override val op: AlbumOp?) : PermissionVerdict

  data class Denied(override val op: AlbumOp?, val reason: DenyReason) : PermissionVerdict
}

/**
 * Cio' che serve all'esame oltre alla tabella: dal registro firmato ([authorIsAdmin]: il dispositivo che firma e' admin;
 * [creatorLeftFamily]: l'account del creatore non ha piu' dispositivi approvati ne' dismessi; [registryVersion]: la versione nota a chi
 * legge), l'ora della chiusura ([closedAt]: dopo, nessuna voce vale) e l'ora in cui fu aggiunto l'elemento precedente ([previousAddedAt],
 * condizione "aggiunto quando il livello lo permetteva" di "Riporta").
 */
data class CheckContext(
  val authorIsAdmin: Boolean = false,
  val creatorLeftFamily: Boolean = false,
  val registryVersion: Long = Long.MAX_VALUE,
  val closedAt: Long? = null,
  val previousAddedAt: Long? = null,
)

/**
 * **Chi legge** (§2.4, D10): la voce [entry] e' permessa? Con il livello in vigore alla **sua** ora ([levels]), il creatore [creator] (un
 * account, D11), l'erede [heir] in vigore a quell'ora (null se nessuno) e la voce gia' ammessa per la stessa chiave subito prima
 * ([previous]: per una lapide `item:` chi aveva aggiunto l'elemento, per una definizione lo stato e il titolo di prima).
 *
 * Le regole per campo:
 * - `definition`: la prima (in ordine di tempo) e' la base dell'album e la scrive solo il creatore, o l'erede in vigore quando il creatore e'
 *   uscito dalla famiglia e i suoi contributi non valgono piu' (D11). Ogni dispositivo tiene solo la sua ultima voce per chiave: la
 *   creazione del creatore puo' essere gia' stata sostituita dalla sua chiusura, quindi la base puo' essere anche `closed`/`deleted`.
 *   Poi stesso creatore e nonce, mai dopo una chiusura, titolo o
 *   cartella cambiati = "Rinomina", stato `closed`/`deleted` = "Smetti di condividere"/"Elimina l'album"; nessun cambio = nessun effetto;
 * - `level` = "Cambia il livello"; `cover` = "Copertina"; `sort` = "Ordine";
 * - `item:` nuovo (o dopo una lapide) = "Aggiungi" (e `addedBy` = chi scrive); sopra un elemento vivo `addedBy` non cambia mai: il proprio
 *   = come aggiungere, quello di un altro = come toglierlo (nessuno si appropria dell'elemento di un altro per poi toglierlo), e di quello di
 *   un altro cambia al piu' la posizione: un contenuto diverso si scarta (R14, `OWNERSHIP`);
 * - lapide `item:` = "Togli" confrontato con `addedBy` di prima, oppure "Riporta" per il proprio (anche a "Gli altri guardano", se
 *   l'elemento fu aggiunto quando il livello lo permetteva); una lapide su niente non ha effetto;
 * - `heir` = "Eredita": un dispositivo admin, per il proprio account, con il creatore uscito dalla famiglia nel registro firmato.
 * Dopo la chiusura ([CheckContext.closedAt]) nessuna voce vale, salvo che la definizione la legge da se'.
 */
fun AlbumPermissions.check(
  entry: AlbumEntry,
  levels: LevelHistory,
  creator: AccountId,
  heir: AccountId?,
  previous: AlbumEntry?,
  context: CheckContext = CheckContext(),
): PermissionVerdict {
  val actor = entry.account
  val level = levels.at(entry.at)

  fun roles(addedBy: AccountId?): Set<AlbumRole> = rolesOf(actor, creator, heir, addedBy, context.authorIsAdmin)

  fun admit(op: AlbumOp, addedBy: AccountId? = null): PermissionVerdict {
    if (!admits(op, level, roles(addedBy))) return PermissionVerdict.Denied(op, DenyReason.NOT_PERMITTED)
    val ok = when (cell(op, level).condition) {
      PermissionCondition.NONE -> true
      PermissionCondition.ADDED_WHILE_PERMITTED -> context.previousAddedAt?.let { added ->
        admits(AlbumOp.ADD_MEDIA, levels.at(added), rolesOf(actor, creator, null, null, false))
      } == true
      PermissionCondition.CREATOR_LEFT_FAMILY -> context.creatorLeftFamily
    }
    return if (ok) PermissionVerdict.Allowed(op) else PermissionVerdict.Denied(op, DenyReason.NOT_PERMITTED)
  }

  val field = entry.field
  if (field != AlbumField.Definition && context.closedAt != null && entry.at > context.closedAt) {
    return PermissionVerdict.Denied(null, DenyReason.CLOSED)
  }
  return when (field) {
    AlbumField.Definition -> checkDefinition(entry, creator, heir, previous, ::roles, level)
    AlbumField.Level -> admit(AlbumOp.CHANGE_LEVEL)
    AlbumField.Cover -> admit(AlbumOp.SET_COVER)
    AlbumField.Sort -> admit(AlbumOp.SET_SORT)
    AlbumField.Heir -> {
      val value = entry.value as? AlbumValue.Heir ?: return PermissionVerdict.Denied(AlbumOp.INHERIT, DenyReason.NOT_PERMITTED)
      when {
        value.account != actor -> PermissionVerdict.Denied(AlbumOp.INHERIT, DenyReason.OWNERSHIP)
        value.registryVersion > context.registryVersion -> PermissionVerdict.Denied(AlbumOp.INHERIT, DenyReason.REGISTRY_AHEAD)
        else -> admit(AlbumOp.INHERIT)
      }
    }
    is AlbumField.Item -> {
      val value = entry.value as? AlbumValue.Item
      val before = previous?.value as? AlbumValue.Item
      when {
        value != null && before == null ->
          if (value.addedBy != actor) PermissionVerdict.Denied(AlbumOp.ADD_MEDIA, DenyReason.OWNERSHIP) else admit(AlbumOp.ADD_MEDIA)
        value != null && before != null -> when {
          value.addedBy != before.addedBy -> PermissionVerdict.Denied(AlbumOp.REMOVE_ITEM, DenyReason.OWNERSHIP)
          actor == before.addedBy -> admit(AlbumOp.ADD_MEDIA)
          // R14: sull'elemento di un altro si puo' cambiare al piu' il posto nell'ordine (con il permesso di togliere quell'elemento). Un
          // contenuto diverso (chiave, percorso, versione, hash) "aggiunto da" lui sarebbe una foto che lui non ha mai aggiunto.
          value.copy(position = before.position) != before -> PermissionVerdict.Denied(AlbumOp.REMOVE_ITEM, DenyReason.OWNERSHIP)
          else -> admit(AlbumOp.REMOVE_ITEM, before.addedBy)
        }
        before == null -> PermissionVerdict.Allowed(null)
        else -> {
          val removal = admit(AlbumOp.REMOVE_ITEM, before.addedBy)
          if (removal is PermissionVerdict.Denied && actor == before.addedBy) {
            admit(AlbumOp.BRING_BACK, actor).takeIf { it is PermissionVerdict.Allowed } ?: removal
          } else {
            removal
          }
        }
      }
    }
  }
}

private fun AlbumPermissions.checkDefinition(
  entry: AlbumEntry,
  creator: AccountId,
  heir: AccountId?,
  previous: AlbumEntry?,
  roles: (AccountId?) -> Set<AlbumRole>,
  level: ShareLevel,
): PermissionVerdict {
  val definition = entry.value as? AlbumValue.Definition ?: return PermissionVerdict.Denied(null, DenyReason.IDENTITY)
  if (definition.creator != creator || !AlbumIds.matches(entry.album, definition.creator, definition.nonce)) {
    return PermissionVerdict.Denied(null, DenyReason.IDENTITY)
  }
  val before = previous?.value as? AlbumValue.Definition
    ?: return if (entry.account == creator || entry.account == heir) {
      PermissionVerdict.Allowed(closeOp(definition.state))
    } else {
      PermissionVerdict.Denied(null, DenyReason.NOT_CREATED)
    }
  if (before.creator != definition.creator || before.nonceHex != definition.nonceHex) return PermissionVerdict.Denied(null, DenyReason.IDENTITY)
  if (before.state != AlbumDefinitionState.ACTIVE) return PermissionVerdict.Denied(null, DenyReason.CLOSED)
  var op: AlbumOp? = null
  if (definition.title != before.title || definition.folder != before.folder) {
    if (!admits(AlbumOp.RENAME, level, roles(null))) return PermissionVerdict.Denied(AlbumOp.RENAME, DenyReason.NOT_PERMITTED)
    op = AlbumOp.RENAME
  }
  val close = closeOp(definition.state)
  if (close != null) {
    if (!admits(close, level, roles(null))) return PermissionVerdict.Denied(close, DenyReason.NOT_PERMITTED)
    op = close
  }
  return PermissionVerdict.Allowed(op)
}

private fun closeOp(state: AlbumDefinitionState): AlbumOp? = when (state) {
  AlbumDefinitionState.ACTIVE -> null
  AlbumDefinitionState.CLOSED -> AlbumOp.STOP_SHARING
  AlbumDefinitionState.DELETED -> AlbumOp.DELETE_ALBUM
}

/**
 * **Chi scrive** (§2.4, D10): la stessa tabella applicata a **me** su un album fuso, adesso. Cio' che qui e' falso la UI non lo offre e il
 * pubblicatore non lo riceve; cio' che e' vero, chi legge lo accetta ([check] con la voce che l'operazione produce).
 */
object SharedAlbumRules {

  /**
   * [op] e' permessa a [me] (il mio account; [meIsAdminDevice]: questo dispositivo e' admin nel registro) su [album] all'ora del NAS
   * [now]? [item] per le operazioni su un elemento (chi l'ha aggiunto). Falso per un album chiuso o eliminato.
   */
  fun allows(album: MergedAlbum, me: AccountId, meIsAdminDevice: Boolean, op: AlbumOp, item: MediaId?, now: Long, creatorLeftFamily: Boolean): Boolean {
    if (album.state != SharedState.ACTIVE) return false
    val resolved = item?.let { album.items[it] }
    val level = album.levels.at(now)
    val roles = AlbumPermissions.rolesOf(me, album.creator, album.heirAt(now), resolved?.addedBy, meIsAdminDevice)
    if (!AlbumPermissions.admits(op, level, roles)) return false
    return when (AlbumPermissions.cell(op, level).condition) {
      PermissionCondition.NONE -> true
      PermissionCondition.ADDED_WHILE_PERMITTED -> resolved != null &&
        AlbumPermissions.admits(AlbumOp.ADD_MEDIA, album.levels.at(resolved.addedAt), AlbumPermissions.rolesOf(me, album.creator, null, null, false))
      PermissionCondition.CREATOR_LEFT_FAMILY -> creatorLeftFamily
    }
  }
}
