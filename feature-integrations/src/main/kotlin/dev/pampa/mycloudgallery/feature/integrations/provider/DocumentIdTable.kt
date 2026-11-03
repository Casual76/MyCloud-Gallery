package dev.pampa.mycloudgallery.feature.integrations.provider

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentId
import java.security.SecureRandom
import java.util.Base64
import java.util.Random
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// Fase 5B (P10, docs/fase5-progetto.md §2.7, D23, C17; E-X1): gli id opachi dei documenti. Nessun id contiene o permette di ricavare un percorso, una
// share, un account, un id del MediaStore o del NAS, un `MediaId`, un nome.
//
// Due famiglie, con regole diverse (IN-2 della verifica funzionale 5B):
//  - gli id dei **media** sono token casuali (18 byte di `SecureRandom`, 24 caratteri URL-safe), in memoria, validi per questo avvio del processo,
//    a scadenza e revocabili (E-X1: un URI usato dopo che il permesso e' scaduto non apre piu' niente);
//  - gli id della **radice** e delle **cartelle** ("Recenti", "Cartelle NAS", le cartelle del NAS) sono **stabili**: derivati con una chiave locale
//    (HMAC-SHA256 di un'etichetta e, per una cartella, della sua chiave opaca; 18 byte, 24 caratteri), uguali a ogni avvio del processo finche' la
//    chiave non ruota (azzeramento dell'app, famiglia che cambia). DocumentsUI tiene le radici e gli ultimi percorsi nella cache di sistema: con id
//    nuovi a ogni processo, dopo un riavvio "Allega file" di Gmail non trovava piu' niente. Non dicono mai cio' che e' dietro (senza la chiave non
//    si ricavano ne' si indovinano) e non danno nessun accesso ai media, che passano sempre dalla vista di adesso e dal loro id a scadenza.

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
 * La tabella degli id dei documenti. Quelli della radice, di "Recenti", di "Cartelle NAS" e delle cartelle sono **stabili** (derivati con una chiave
 * locale, IN-2; non scadono); quelli dei media nascono quando un elenco li chiede ([idFor]) o quando il selettore consegna un media ([idFor] con
 * `delivered`), e **scadono** ([DocumentPolicy.BROWSE_ID_TTL_MS], [DocumentPolicy.DELIVERED_ID_TTL_MS], a scorrimento) o escono per capienza (i meno
 * usati). Un id consegnato non esce per colpa dello sfoglio.
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
  /** La chiave locale degli id stabili (IN-2). Di default una chiave in memoria: gli id valgono per questo oggetto; `:app` ne passa una su file. */
  private val idKey: DocumentIdKey = InMemoryDocumentIdKey(random),
) {

  private class Entry(val token: String, val target: DocumentTarget, var label: String?, var expiresAt: Long, var delivered: Boolean)

  // Gli id stabili: i tre fissi si derivano alla prima richiesta (la chiave si legge dal file solo allora, mai all'avvio del processo).
  private var key: ByteArray? = null
  private val pinnedById = HashMap<String, DocumentTarget>()
  private val pinnedByTarget = HashMap<DocumentTarget, String>()

  // Le cartelle viste (id derivato -> chiave opaca e nome), in ordine d'accesso: servono a risolvere un id che arriva da DocumentsUI. Dopo un riavvio
  // sono vuote e si ricostruiscono dalla Libreria ([rememberFolder]).
  private class FolderEntry(val key: NasFolderKey, var label: String?)

  private val folders = object : LinkedHashMap<String, FolderEntry>(256, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FolderEntry>?): Boolean = size > FOLDER_CAPACITY
  }

  // Ordine d'accesso: il primo e' il meno usato.
  private val browse = LinkedHashMap<String, Entry>(256, 0.75f, true)
  private val delivered = LinkedHashMap<String, Entry>(256, 0.75f, true)
  private val byTarget = HashMap<DocumentTarget, Entry>()

  /** Quanti id non fissi ci sono (scaduti compresi, finche' nessuno li cerca). */
  val size: Int @Synchronized get() = byTarget.size

  /**
   * L'id di [target]: lo stesso finche' vale, uno nuovo se era scaduto. Con [delivered] l'id passa fra i consegnati (non torna piu' indietro) e
   * vale piu' a lungo. [label] e' il nome da mostrare di una cartella; non entra mai nell'id.
   */
  @Synchronized
  fun idFor(target: DocumentTarget, label: String? = null, delivered: Boolean = false): DocumentId {
    stableIdOf(target)?.let { id ->
      // Una cartella ricorda il suo nome da mostrare; l'id non cambia mai con il nome.
      if (target is DocumentTarget.Folder) rememberFolder(id, target.key, label)
      return id
    }
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
    ensureKey()
    pinnedById[id.value]?.let { return DocumentResolution.Found(it, null, delivered = false) }
    folders[id.value]?.let { return DocumentResolution.Found(DocumentTarget.Folder(it.key), it.label, delivered = false) }
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

  /** Revoca ogni id (consegnato o da sfoglio) dei media [ids]: un "Nascondi" non aspetta la scadenza (I4 della revisione di sicurezza della Fase 10). */
  @Synchronized
  fun revokeMedia(ids: Collection<MediaId>) {
    for (id in ids) byTarget[DocumentTarget.Media(id)]?.let { remove(it) }
  }

  /**
   * Revoca ogni id: quelli dei media escono dalla tabella, e la chiave degli id stabili **ruota** (azzeramento dell'app, famiglia che cambia): la
   * radice e le cartelle di prima non valgono piu'. Chi lo chiama notifica le radici al sistema (`IntegrationsGraph`).
   */
  @Synchronized
  fun revokeAll() {
    browse.clear()
    delivered.clear()
    byTarget.clear()
    folders.clear()
    pinnedById.clear()
    pinnedByTarget.clear()
    key = null
    idKey.rotate()
  }

  /** L'id stabile di una cartella del NAS ([key] opaca): lo stesso a ogni avvio del processo, finche' la chiave degli id non ruota. */
  @Synchronized
  fun folderIdOf(key: NasFolderKey): DocumentId {
    ensureKey()
    return DocumentId(derive(FOLDER_TAG, key.value))
  }

  /**
   * Ricorda che [id] e' la cartella [key] (con il nome da mostrare, se si sa): dopo un riavvio del processo una cartella che DocumentsUI ricorda
   * si ritrova dalla Libreria e si rimette qui. Un id che non e' quello derivato da [key] non si ricorda (nessuno lo scrive da fuori).
   */
  @Synchronized
  fun rememberFolder(id: DocumentId, key: NasFolderKey, label: String? = null) {
    if (id != folderIdOf(key)) return
    val entry = folders[id.value]
    if (entry == null) folders[id.value] = FolderEntry(key, label) else if (label != null) entry.label = label
  }

  /** L'id stabile di [target] se e' una delle quattro forme stabili (radice, "Recenti", "Cartelle NAS", una cartella); null per un media. */
  private fun stableIdOf(target: DocumentTarget): DocumentId? {
    ensureKey()
    return when (target) {
      DocumentTarget.Root, DocumentTarget.Recent, DocumentTarget.NasFolders -> DocumentId(checkNotNull(pinnedByTarget[target]))
      is DocumentTarget.Folder -> DocumentId(derive(FOLDER_TAG, target.key.value))
      is DocumentTarget.Media -> null
    }
  }

  /** La chiave si legge alla prima richiesta (dal file, se c'e'): i tre id fissi si derivano da lei. */
  private fun ensureKey() {
    if (key != null) return
    key = idKey.bytes()
    for ((tag, fixed) in listOf(ROOT_TAG to DocumentTarget.Root, RECENT_TAG to DocumentTarget.Recent, NAS_FOLDERS_TAG to DocumentTarget.NasFolders)) {
      val id = derive(tag, "")
      pinnedById[id] = fixed
      pinnedByTarget[fixed] = id
    }
  }

  /** HMAC-SHA256 con la chiave locale di [tag] e [payload], i primi 18 byte, in base64 URL-safe senza riempimento: 24 caratteri. */
  private fun derive(tag: String, payload: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(checkNotNull(key), "HmacSHA256"))
    mac.update(tag.toByteArray(Charsets.UTF_8))
    mac.update(0)
    mac.update(payload.toByteArray(Charsets.UTF_8))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal().copyOf(TOKEN_BYTES))
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
      if (token !in browse && token !in delivered && token !in pinnedById && token !in folders) return token
    }
  }

  private companion object {
    /** 18 byte = 144 bit: non si indovina. In base64 URL-safe sono 24 caratteri, tutti ammessi da `DocumentIds.isOpaque`. */
    const val TOKEN_BYTES: Int = 18

    /** Quante cartelle la tabella ricorda al massimo (le meno usate escono per prime: si ritrovano dalla Libreria). */
    const val FOLDER_CAPACITY: Int = 4_096

    const val ROOT_TAG = "root"
    const val RECENT_TAG = "recent"
    const val NAS_FOLDERS_TAG = "nas-folders"
    const val FOLDER_TAG = "folder"
  }
}
