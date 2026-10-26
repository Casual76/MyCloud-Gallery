package dev.pampa.mycloudgallery.core.albums.shared

import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.AlbumPermissions
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.data.AlbumDefinitionState
import dev.pampa.mycloudgallery.core.trust.data.AlbumEntry
import dev.pampa.mycloudgallery.core.trust.data.AlbumField
import dev.pampa.mycloudgallery.core.trust.data.AlbumIds
import dev.pampa.mycloudgallery.core.trust.data.AlbumKey
import dev.pampa.mycloudgallery.core.trust.data.AlbumValue
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState

// LA FUSIONE DEGLI ALBUM CONDIVISI (docs/fase7-progetto.md D7, D8, D10, D11, D31; §2.5 "Ricevere", punto 2). Lo stato di un album e' un
// insieme di contributi, uno per dispositivo (l'ultima testa accettata): [AlbumFusion.join] tiene per ogni dispositivo la versione piu' alta,
// quindi e' commutativa, associativa e idempotente; [AlbumFusion.resolve] e' una funzione deterministica di quell'insieme (ordine totale
// delle voci, nessuna dipendenza dall'ordine di arrivo). Le voci non permesse si scartano con la stessa tabella di chi scrive (C25).

/**
 * Il contributo verificato di un dispositivo a un album: le voci **ammesse** (gia' passate dal codec, dalle regole del contributo e da quella
 * delle voci retrodatate, §2.4) della testa [version] con impronta [contentHash], firmata alle [createdAt] dal dispositivo [device]
 * dell'account [account].
 */
data class VerifiedContribution(
  val album: AlbumId,
  val device: String,
  val account: AccountId,
  val version: Long,
  val contentHash: String,
  val createdAt: Long,
  val entries: List<AlbumEntry>,
) {
  init {
    require(entries.all { it.album == album && it.author == device && it.account == account }) { "Voci di un altro contributo." }
    require(entries.map { it.key }.toSet().size == entries.size) { "Una voce per chiave." }
  }

  override fun toString(): String = "VerifiedContribution(v$version, ${entries.size} voci)"
}

/** Cio' che la fusione chiede al registro firmato (D11). */
interface AlbumRegistryView {
  /** La versione del registro accettato (una voce `heir` che ne cita una piu' alta aspetta). */
  val version: Long

  /** Il dispositivo e' admin e approvato. */
  fun isAdminDevice(device: String): Boolean

  /** L'account non ha piu' nessun dispositivo approvato ne' dismesso (D11: solo allora l'admin eredita). */
  fun accountLeft(account: AccountId): Boolean
}

/** Il registro accettato visto dalla fusione. */
class RegistryAlbumView(private val state: RegistryState) : AlbumRegistryView {
  override val version: Long get() = state.registry.version

  override fun isAdminDevice(device: String): Boolean = state.registry.device(device)?.let { it.admin && it.status == DeviceStatus.APPROVED } == true

  override fun accountLeft(account: AccountId): Boolean = state.registry.devices.none { d ->
    AccountId.of(d.nasUser) == account && (d.status == DeviceStatus.APPROVED || d.status == DeviceStatus.RETIRED)
  }
}

/** L'erede (D11) da [since] (ora del NAS della sua voce `heir`). */
data class HeirGrant(val account: AccountId, val since: Long)

/** La copertina scelta. */
data class CoverChoice(val media: MediaId, val contentKey: ContentKey) {
  override fun toString(): String = "CoverChoice"
}

/** Un elemento vivo dell'album dopo la fusione: [media] e' l'id di chi l'ha pubblicato (chi legge lo ritrova per chiave di contenuto, D9). */
data class ResolvedItem(
  val media: MediaId,
  val contentKey: ContentKey,
  val sha256: Sha256?,
  val path: SharePath,
  val position: Long,
  val addedBy: AccountId,
  val version: VersionId?,
  /** L'ora della voce che l'ha aggiunto (prima di eventuali spostamenti nell'ordine): la condizione di "Riporta". */
  val addedAt: Long,
  val at: Long,
) {
  override fun toString(): String = "ResolvedItem"
}

/** Una voce scartata (per i conteggi della Diagnostica e per l'avviso E-SH13). */
data class Denial(val entry: AlbumEntry, val reason: DenyReason, val op: AlbumOp?)

/**
 * Lo stato fuso di un album condiviso per chi legge: definizione (titolo, cartella, stato, scelta per gli altri di Q5), creatore (un
 * account), storia dei livelli, eredi, copertina, ordine, elementi vivi, le voci vincitrici per chiave e quelle scartate.
 */
data class MergedAlbum(
  val album: AlbumId,
  val creator: AccountId,
  val nonceHex: String,
  val title: String,
  val folder: SharePath,
  val state: SharedState,
  /** Il codice firmato di `afterClose` (Q5), cosi' com'e': uno sconosciuto vale [OthersAfterClose.REMOVE]. */
  val afterCloseCode: String?,
  val createdAt: Long,
  val closedAt: Long?,
  val levels: LevelHistory,
  val heirs: List<HeirGrant>,
  val cover: CoverChoice?,
  val sort: AlbumSort?,
  val items: Map<MediaId, ResolvedItem>,
  val winners: Map<AlbumKey, AlbumEntry>,
  val denied: List<Denial>,
  val updatedAt: Long,
) {
  val afterClose: OthersAfterClose? get() = afterCloseCode?.let { OthersAfterClose.fromCode(it) }

  /** L'erede in vigore all'ora [time] (l'ultima voce `heir` ammessa non successiva), o null. */
  fun heirAt(time: Long): AccountId? = heirs.lastOrNull { it.since <= time }?.account

  /** L'erede di adesso (l'ultimo). */
  val heir: AccountId? get() = heirs.lastOrNull()?.account

  override fun toString(): String = "MergedAlbum(${state.code}, ${items.size} elementi, ${denied.size} scartate)"
}

object AlbumFusion {

  /** L'ordine totale delle voci di una chiave: per ora del NAS, a pari ora l'autore maggiore prima (vince il minore, come i dati personali). */
  val ORDER: Comparator<AlbumEntry> = compareBy<AlbumEntry> { it.at }.thenByDescending { it.author }

  /**
   * Unisce i contributi [b] allo stato [a] (per dispositivo): vince la versione piu' alta; a pari versione con un contenuto diverso
   * (un'equivocazione che chi legge rifiuta prima di arrivare qui) vince l'impronta minore, cosi' l'unione resta deterministica.
   */
  fun join(a: Map<String, VerifiedContribution>, b: Collection<VerifiedContribution>): Map<String, VerifiedContribution> {
    val out = HashMap(a)
    for (c in b) {
      val old = out[c.device]
      out[c.device] = when {
        old == null -> c
        c.version > old.version -> c
        c.version < old.version -> old
        c.contentHash < old.contentHash -> c
        else -> old
      }
    }
    return out
  }

  /** La fusione di [contributions] (§1.2: `AlbumFusion.merge`): null se l'album non ha una creazione valida. */
  fun merge(contributions: Collection<VerifiedContribution>, registry: AlbumRegistryView): MergedAlbum? =
    resolve(join(emptyMap(), contributions), registry)

  /** Lo stato fuso dei contributi [state] (uno per dispositivo). */
  fun resolve(state: Map<String, VerifiedContribution>, registry: AlbumRegistryView): MergedAlbum? {
    val all = state.values.flatMap { it.entries }
    val album = all.firstOrNull()?.album ?: return null
    require(all.all { it.album == album }) { "Contributi di album diversi." }
    val byKey: Map<AlbumKey, List<AlbumEntry>> = all.groupBy { it.key }.mapValues { (_, v) -> v.sortedWith(ORDER) }
    val defKey = AlbumKey.of(album, AlbumField.Definition)
    // Il creatore lo dice l'id (D7): tutte le definizioni dei contributi validi hanno lo stesso account e lo stesso nonce.
    val definitions = byKey[defKey].orEmpty().filter { e ->
      val d = e.value as? AlbumValue.Definition
      d != null && AlbumIds.matches(album, d.creator, d.nonce)
    }
    val creator = (definitions.firstOrNull()?.value as? AlbumValue.Definition)?.creator ?: return null
    val creatorLeft = registry.accountLeft(creator)
    val denied = ArrayList<Denial>()

    fun context(entry: AlbumEntry, closedAt: Long?, previousAddedAt: Long? = null) = CheckContext(
      authorIsAdmin = registry.isAdminDevice(entry.author),
      creatorLeftFamily = creatorLeft,
      registryVersion = registry.version,
      closedAt = closedAt,
      previousAddedAt = previousAddedAt,
    )

    // 1. Gli eredi (D11): una linea del tempo, non l'ultima scrittura (un'eredita' successiva non cancella quella di prima).
    val heirs = ArrayList<HeirGrant>()
    var heirEntry: AlbumEntry? = null
    fun heirAt(time: Long): AccountId? = heirs.lastOrNull { it.since <= time }?.account
    for (entry in byKey[AlbumKey.of(album, AlbumField.Heir)].orEmpty()) {
      val verdict = AlbumPermissions.check(entry, LevelHistory.NONE, creator, heirAt(entry.at), null, context(entry, null))
      if (verdict is PermissionVerdict.Allowed) {
        heirs += HeirGrant(entry.account, entry.at)
        heirEntry = entry
      } else {
        denied += Denial(entry, (verdict as PermissionVerdict.Denied).reason, verdict.op)
      }
    }

    // 2. La chiusura (D31): solo il creatore o l'erede la scrivono; la prima loro definizione e' la base dell'album (la creazione, o la sua
    //    ultima voce che l'ha sostituita: una chiusura); non dipende dai livelli.
    var closedAt: Long? = null
    for (entry in definitions) {
      val d = entry.value as AlbumValue.Definition
      if ((entry.account == creator || entry.account == heirAt(entry.at)) && d.state != AlbumDefinitionState.ACTIVE) {
        closedAt = entry.at
        break
      }
    }

    // 3. I livelli: l'ultima voce `level` ammessa (solo il creatore o l'erede), mai dopo la chiusura.
    var levelEntry: AlbumEntry? = null
    for (entry in byKey[AlbumKey.of(album, AlbumField.Level)].orEmpty()) {
      val verdict = AlbumPermissions.check(entry, LevelHistory.NONE, creator, heirAt(entry.at), levelEntry, context(entry, closedAt))
      if (verdict is PermissionVerdict.Allowed) levelEntry = entry else denied += Denial(entry, (verdict as PermissionVerdict.Denied).reason, verdict.op)
    }
    val levels = (levelEntry?.value as? AlbumValue.Levels)?.let { LevelHistory(it.history) } ?: LevelHistory.NONE

    // 4. Le definizioni, con i livelli (la "Rinomina" degli altri dipende dal livello).
    var definition: AlbumEntry? = null
    var createdAt: Long? = null
    for (entry in byKey[defKey].orEmpty()) {
      val verdict = AlbumPermissions.check(entry, levels, creator, heirAt(entry.at), definition, context(entry, null))
      if (verdict is PermissionVerdict.Allowed) {
        if (definition == null) createdAt = entry.at
        definition = entry
      } else {
        denied += Denial(entry, (verdict as PermissionVerdict.Denied).reason, verdict.op)
      }
    }
    val finalDefinition = definition?.value as? AlbumValue.Definition ?: return null
    val winners = HashMap<AlbumKey, AlbumEntry>()
    winners[defKey] = definition
    levelEntry?.let { winners[it.key] = it }
    heirEntry?.let { winners[it.key] = it }

    // 5. Copertina, ordine ed elementi: ognuno con il livello alla sua ora, mai dopo la chiusura.
    var cover: CoverChoice? = null
    var sort: AlbumSort? = null
    val items = HashMap<MediaId, ResolvedItem>()
    for ((key, entries) in byKey) {
      val field = entries.first().field
      if (field == AlbumField.Definition || field == AlbumField.Level || field == AlbumField.Heir) continue
      var current: AlbumEntry? = null
      var addedAt: Long? = null
      for (entry in entries) {
        val verdict = AlbumPermissions.check(entry, levels, creator, heirAt(entry.at), current, context(entry, closedAt, addedAt))
        if (verdict is PermissionVerdict.Allowed) {
          val wasLive = (current?.value as? AlbumValue.Item) != null
          addedAt = when {
            entry.value == null -> null
            wasLive -> addedAt
            else -> entry.at
          }
          current = entry
        } else {
          denied += Denial(entry, (verdict as PermissionVerdict.Denied).reason, verdict.op)
        }
      }
      val winner = current ?: continue
      winners[key] = winner
      when (val value = winner.value) {
        is AlbumValue.Cover -> cover = CoverChoice(value.media, value.contentKey)
        is AlbumValue.Sort -> sort = value.sort
        is AlbumValue.Item -> {
          val media = (field as AlbumField.Item).media
          items[media] = ResolvedItem(media, value.contentKey, value.sha256, value.path, value.position, value.addedBy, value.version, addedAt ?: winner.at, winner.at)
        }
        else -> Unit
      }
    }
    val state = when (finalDefinition.state) {
      AlbumDefinitionState.ACTIVE -> SharedState.ACTIVE
      AlbumDefinitionState.CLOSED -> SharedState.CLOSED
      AlbumDefinitionState.DELETED -> SharedState.DELETED
    }
    return MergedAlbum(
      album = album,
      creator = creator,
      nonceHex = finalDefinition.nonceHex,
      title = finalDefinition.title,
      folder = finalDefinition.folder,
      state = state,
      afterCloseCode = finalDefinition.afterClose,
      createdAt = createdAt ?: definition.at,
      closedAt = if (state == SharedState.ACTIVE) null else definition.at,
      levels = levels,
      heirs = heirs.toList(),
      cover = cover,
      sort = sort,
      items = items,
      winners = winners,
      denied = denied.sortedWith(compareBy<Denial>({ it.entry.key }, { it.entry.at }, { it.entry.author })),
      updatedAt = winners.values.maxOf { it.at },
    )
  }
}
