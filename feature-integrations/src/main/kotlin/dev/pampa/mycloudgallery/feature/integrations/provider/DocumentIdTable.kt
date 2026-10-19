package dev.pampa.mycloudgallery.feature.integrations.provider

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentId
import java.security.SecureRandom
import java.util.Base64
import java.util.Random

// Fase 5B (P10, docs/fase5-progetto.md §2.7, D23, C17; E-X1): gli id opachi dei documenti. Un id e' un **token casuale** (18 byte di
// `SecureRandom`, 24 caratteri URL-safe) che la tabella lega a cio' che rappresenta; non contiene, non deriva e non permette di ricavare un
// percorso, una share, un account, un id del MediaStore o del NAS, un `MediaId`, un nome. Vive in memoria, vale per questo avvio del processo e
// scade (E-X1: un URI usato dopo che il permesso e' scaduto non apre piu' niente).

/** Che cosa rappresenta un documento. Mai in un URI: sta nella tabella, dietro un token. */
sealed interface DocumentTarget {
  /** La radice "MyCloud Gallery". */
  data object Root : DocumentTarget

  /** La cartella virtuale "Recenti". */
  data object Recent : DocumentTarget

  /** La cartella virtuale "Cartelle NAS". */
  data object NasFolders : DocumentTarget

  /** Una cartella del NAS, per chiave opaca. */
  data class Folder(val key: NasFolderKey) : DocumentTarget

  /** Un media della Libreria. */
  data class Media(val id: MediaId) : DocumentTarget {
    override fun toString(): String = "Media"
  }
}

/** Il risultato di una ricerca di un id. */
sealed interface DocumentResolution {

  /** L'id c'e' ed e' valido. [delivered]: l'ha consegnato il selettore dopo uno sblocco e un tocco (vale anche con l'app richiusa). */
  class Found(val target: DocumentTarget, val label: String?, val delivered: Boolean) : DocumentResolution

  /** L'id c'era ma e' scaduto (E-X1): nessun media si serve. */
  data object Expired : DocumentResolution

  /** L'id non e' nostro (mai emesso in questo avvio, revocato, uscito dalla tabella). */
  data object Unknown : DocumentResolution
}

/**
 * La tabella degli id dei documenti. Tre id sono fissi per tutto l'avvio (radice, "Recenti", "Cartelle NAS"); gli altri nascono quando un
 * elenco li chiede ([idFor]) o quando il selettore consegna un media ([idFor] con `delivered`), e **scadono** ([DocumentPolicy.BROWSE_ID_TTL_MS],
 * [DocumentPolicy.DELIVERED_ID_TTL_MS], a scorrimento) o escono per capienza (i meno usati). Un id consegnato non esce per colpa dello sfoglio.
 *
 * Thread-safe: la chiamano i thread del binder.
 */
class DocumentIdTable(
  private val clock: DocumentClock = DocumentClock.Monotonic,
  private val random: Random = SecureRandom(),
  private val browseTtlMs: Long = DocumentPolicy.BROWSE_ID_TTL_MS,
  private val deliveredTtlMs: Long = DocumentPolicy.DELIVERED_ID_TTL_MS,
  private val browseCapacity: Int = DocumentPolicy.BROWSE_ID_CAPACITY,
  private val deliveredCapacity: Int = DocumentPolicy.DELIVERED_ID_CAPACITY,
) {

  private class Entry(val token: String, val target: DocumentTarget, var label: String?, var expiresAt: Long, var delivered: Boolean)

  private val pinnedById = HashMap<String, DocumentTarget>()
  private val pinnedByTarget = HashMap<DocumentTarget, String>()

  // Ordine d'accesso: il primo e' il meno usato.
  private val browse = LinkedHashMap<String, Entry>(256, 0.75f, true)
  private val delivered = LinkedHashMap<String, Entry>(256, 0.75f, true)
  private val byTarget = HashMap<DocumentTarget, Entry>()

  init {
    for (fixed in listOf(DocumentTarget.Root, DocumentTarget.Recent, DocumentTarget.NasFolders)) {
      val token = newToken()
      pinnedById[token] = fixed
      pinnedByTarget[fixed] = token
    }
  }

  /** Quanti id non fissi ci sono (scaduti compresi, finche' nessuno li cerca). */
  val size: Int @Synchronized get() = byTarget.size

  /**
   * L'id di [target]: lo stesso finche' vale, uno nuovo se era scaduto. Con [delivered] l'id passa fra i consegnati (non torna piu' indietro) e
   * vale piu' a lungo. [label] e' il nome da mostrare di una cartella; non entra mai nell'id.
   */
  @Synchronized
  fun idFor(target: DocumentTarget, label: String? = null, delivered: Boolean = false): DocumentId {
    pinnedByTarget[target]?.let { return DocumentId(it) }
    val now = clock.elapsedMs()
    val existing = byTarget[target]
    if (existing != null && now >= existing.expiresAt) {
      remove(existing)
    } else if (existing != null) {
      if (delivered && !existing.delivered) {
        browse.remove(existing.token)
        existing.delivered = true
        this.delivered[existing.token] = existing
        trim(this.delivered, deliveredCapacity)
      }
      existing.expiresAt = now + ttlOf(existing)
      if (label != null) existing.label = label
      touch(existing)
      return DocumentId(existing.token)
    }
    val entry = Entry(newToken(), target, label, now + (if (delivered) deliveredTtlMs else browseTtlMs), delivered)
    byTarget[target] = entry
    if (delivered) {
      this.delivered[entry.token] = entry
      trim(this.delivered, deliveredCapacity)
    } else {
      browse[entry.token] = entry
      trim(browse, browseCapacity)
    }
    return DocumentId(entry.token)
  }

  /** Cerca [id]. Un id valido si rinnova (scorrimento); uno scaduto si toglie. */
  @Synchronized
  fun resolve(id: DocumentId): DocumentResolution {
    pinnedById[id.value]?.let { return DocumentResolution.Found(it, null, delivered = false) }
    val entry = delivered[id.value] ?: browse[id.value] ?: return DocumentResolution.Unknown
    val now = clock.elapsedMs()
    if (now >= entry.expiresAt) {
      remove(entry)
      return DocumentResolution.Expired
    }
    entry.expiresAt = now + ttlOf(entry)
    return DocumentResolution.Found(entry.target, entry.label, entry.delivered)
  }

  /** Se [id] e' stato consegnato dal selettore (e non e' scaduto). Non rinnova. */
  @Synchronized
  fun isDelivered(id: DocumentId): Boolean {
    val entry = delivered[id.value] ?: return false
    return clock.elapsedMs() < entry.expiresAt
  }

  /** Revoca [ids]: da quel momento sono sconosciuti. I tre id fissi non si revocano. */
  @Synchronized
  fun revoke(ids: Collection<DocumentId>) {
    for (id in ids) (delivered[id.value] ?: browse[id.value])?.let { remove(it) }
  }

  /** Revoca ogni id non fisso (azzeramento dell'app, famiglia che cambia). */
  @Synchronized
  fun revokeAll() {
    browse.clear()
    delivered.clear()
    byTarget.clear()
  }

  private fun ttlOf(entry: Entry): Long = if (entry.delivered) deliveredTtlMs else browseTtlMs

  private fun touch(entry: Entry) {
    // `get` su una LinkedHashMap per ordine d'accesso porta la voce in coda.
    if (entry.delivered) delivered[entry.token] else browse[entry.token]
  }

  private fun remove(entry: Entry) {
    byTarget.remove(entry.target)
    browse.remove(entry.token)
    delivered.remove(entry.token)
  }

  /** Toglie i meno usati oltre [capacity], dando la precedenza agli scaduti. */
  private fun trim(map: LinkedHashMap<String, Entry>, capacity: Int) {
    if (map.size <= capacity) return
    val now = clock.elapsedMs()
    val expired = map.values.filter { now >= it.expiresAt }
    expired.forEach { remove(it) }
    while (map.size > capacity) {
      val oldest = map.values.iterator().next()
      remove(oldest)
    }
  }

  private fun newToken(): String {
    while (true) {
      val bytes = ByteArray(TOKEN_BYTES)
      random.nextBytes(bytes)
      val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
      if (token !in browse && token !in delivered && token !in pinnedById) return token
    }
  }

  private companion object {
    /** 18 byte = 144 bit: non si indovina. In base64 URL-safe sono 24 caratteri, tutti ammessi da `DocumentIds.isOpaque`. */
    const val TOKEN_BYTES: Int = 18
  }
}
