package dev.pampa.mycloudgallery.core.albums.shared

import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.trust.data.AlbumEntry
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.data.SegmentRef

// LA REPLICA LOCALE DEGLI ALBUM CONDIVISI (§2.5 "Ricevere", punto 1; D34: tabelle `shared_album_entry` e `shared_album_head_mark` di P1).
// Per ogni (album, dispositivo) l'ultimo contributo accettato: il segno della sua testa (anti-rollback ed equivocazione), l'ora di
// creazione, i segmenti elencati e le voci con il loro segmento (una testa nuova riscarica solo i segmenti nuovi) e il segno "retrodatata"
// (§2.4: una voce scartata perche' retrodatata resta scartata nelle teste dopo). In piu' la testa rifiutata (per non riscaricarla a ogni giro),
// e dalla revisione di sicurezza della Fase 7: l'ultima ora del NAS in cui **chi legge** ha visto che la serie di un dispositivo non aveva niente
// di piu' nuovo di cio' che ha (R7: le ore dichiarate da chi scrive si confrontano con questa), e l'attesa di un contributo che non si legge (R2).

/** Una voce della replica: il segmento che la porta e se e' stata scartata come retrodatata (§2.4, L59). */
data class StoredEntry(val entry: AlbumEntry, val segment: String, val retrodated: Boolean) {
  override fun toString(): String = "StoredEntry(retrodatata=$retrodated)"
}

/**
 * Il contributo di [device] (account [account]) ad [album], com'e' nella replica. [barred]: il registro non lo ammette piu' (revocato, perso,
 * dismesso con una testa non congelata: A7, E-SH20). Non entra mai nella fusione ne' in una schermata; resta solo come **memoria** locale per
 * le azioni di ripristino che lo chiedono (D11: l'erede ricostituisce l'album di chi e' uscito; E-SH20: un altro dispositivo dello stesso
 * account ripubblica i suoi elementi). Non si scarica piu' niente di suo.
 */
data class StoredContribution(
  val album: AlbumId,
  val device: String,
  val account: AccountId,
  val mark: HeadMark,
  val createdAt: Long,
  val segments: List<SegmentRef>,
  val entries: List<StoredEntry>,
  val barred: Boolean = false,
) {
  /** Le voci ammesse alla fusione (senza le retrodatate). */
  fun verified(): VerifiedContribution =
    VerifiedContribution(album, device, account, mark.version, mark.contentHash, createdAt, entries.filter { !it.retrodated }.map { it.entry })

  /** I byte dei segmenti che la testa elenca (R13: il tetto per dispositivo). */
  val bytes: Long get() = segments.sumOf { it.size }

  override fun toString(): String = "StoredContribution(v${mark.version}, ${entries.size} voci, escluso=$barred)"
}

/** Quanto un dispositivo occupa nella replica, in tutti gli album insieme (tetti per dispositivo, D8 e R13). */
data class DeviceFootprint(val entries: Int, val albums: Int, val bytes: Long) {
  companion object {
    val NONE = DeviceFootprint(0, 0, 0)
  }
}

/**
 * R2: un contributo che adesso non si legge (un segmento elencato e sparito, un file che il NAS non da', voci "dal futuro") aspetta fino a
 * [retryAt] (ora del NAS) e non si riscarica a ogni giro. [mark] e' la testa che aspetta (null: nemmeno la testa si e' letta); una testa
 * diversa si legge subito. [attempts] fa crescere l'attesa (0 per le voci "dal futuro": si aspetta solo che l'ora arrivi).
 */
data class ReplicaHold(val mark: HeadMark?, val retryAt: Long, val attempts: Int) {
  override fun toString(): String = "ReplicaHold(tentativi=$attempts)"
}

/**
 * DG-3 (§2.8, revisione di fine Fase 7): il **motivo** per cui un contributo si rifiuta, in nomi fissi. Sono costanti del codice, mai un dato letto dal NAS:
 * quelli del formato firmato ([dev.pampa.mycloudgallery.core.trust.RejectReason], per nome) piu' i tre che nascono nel giro della replica. Vanno in
 * Diagnostica come `reason.<NOME>` (la famiglia di chiavi di `ExportKeys` per i motivi di scarto), solo come numeri.
 */
object AlbumRejections {
  /** R7, L59: la testa (o le sue voci) dichiara un'ora prima di cio' che questo dispositivo ha gia' visto. */
  const val RETRODATED: String = "RETRODATED"

  /** R13: i tetti per dispositivo (album, byte, voci) sono superati. */
  const val OVER_LIMITS: String = "OVER_LIMITS"

  /** La testa o le sue voci portano un'ora nel futuro del NAS: si aspetta che l'ora arrivi (conta come rifiuto del giro, non lascia un segno). */
  const val FUTURE_DATED: String = "FUTURE_DATED"

  /** Un segno senza motivo (scritto da una versione di prima): si conta, non si inventa. */
  const val UNKNOWN: String = "UNKNOWN"

  /** Il nome e' una costante ammessa per l'esportazione (`reason.` + maiuscole, cifre e trattino basso, 3-32 caratteri). */
  fun isValid(name: String): Boolean = name.length in 3..32 && name[0] in 'A'..'Z' && name.all { it in 'A'..'Z' || it in '0'..'9' || it == '_' }
}

/** La replica: una scrittura sostituisce il contributo intero di (album, dispositivo) in una sola transazione. */
interface AlbumReplicaStore {
  suspend fun albums(): Set<AlbumId>

  suspend fun contributions(album: AlbumId): List<StoredContribution>

  suspend fun contribution(album: AlbumId, device: String): StoredContribution?

  suspend fun put(contribution: StoredContribution)

  /** Toglie il contributo (la memoria di un escluso oltre i tetti, o l'azzeramento). */
  suspend fun forget(album: AlbumId, device: String)

  /** Le voci, gli album e i byte di [device] nella replica (tetti per dispositivo). */
  suspend fun footprint(device: String): DeviceFootprint

  /** L'ultima testa rifiutata di (album, dispositivo): non si riscarica finche' non ne compare una diversa. */
  suspend fun rejectedHead(album: AlbumId, device: String): HeadMark?

  /** [reason]: il motivo ([AlbumRejections], DG-3) che resta col segno e si conta in [rejectedByReason]; null per un segno senza motivo. */
  suspend fun setRejectedHead(album: AlbumId, device: String, mark: HeadMark?, reason: String? = null)

  /** DG-3: le teste rifiutate **adesso** (i segni durevoli) per motivo; un segno senza motivo vale [AlbumRejections.UNKNOWN]. */
  suspend fun rejectedByReason(): Map<String, Int>

  /** R2: l'attesa di (album, dispositivo), o null. */
  suspend fun hold(album: AlbumId, device: String): ReplicaHold?

  suspend fun setHold(album: AlbumId, device: String, hold: ReplicaHold?)

  /**
   * R7: l'ultima ora del NAS in cui questo dispositivo ha visto che la serie di [device] in [album] non aveva niente di piu' nuovo di cio'
   * che la replica tiene (nessuna testa, o la testa accettata). null: mai visto. Una testa nuova non puo' essere stata creata prima, e le sue
   * voci nuove non prima di quell'ora meno l'eta' massima di una modifica (`PublishRules.MAX_ENTRY_AGE_MS`).
   */
  suspend fun seenAt(album: AlbumId, device: String): Long?

  suspend fun setSeenAt(album: AlbumId, device: String, at: Long)
}

/** La replica in memoria: l'implementazione del pacchetto finche' P1 non consegna le tabelle v6 (l'adattatore Room e' di P1/P13). */
class MemoryAlbumReplicaStore : AlbumReplicaStore {
  private val lock = Any()
  private val byAlbum = LinkedHashMap<AlbumId, LinkedHashMap<String, StoredContribution>>()
  private val rejected = HashMap<Pair<AlbumId, String>, HeadMark>()
  private val rejectedReasons = HashMap<Pair<AlbumId, String>, String>()
  private val holds = HashMap<Pair<AlbumId, String>, ReplicaHold>()
  private val seen = HashMap<Pair<AlbumId, String>, Long>()

  /** Quante scritture (per provare che un giro senza novita' non scrive). */
  var writes: Int = 0
    private set

  override suspend fun albums(): Set<AlbumId> = synchronized(lock) { byAlbum.filterValues { it.isNotEmpty() }.keys.toSet() }

  override suspend fun contributions(album: AlbumId): List<StoredContribution> = synchronized(lock) { byAlbum[album]?.values?.toList().orEmpty() }

  override suspend fun contribution(album: AlbumId, device: String): StoredContribution? = synchronized(lock) { byAlbum[album]?.get(device) }

  override suspend fun put(contribution: StoredContribution) {
    synchronized(lock) {
      writes++
      byAlbum.getOrPut(contribution.album) { LinkedHashMap() }[contribution.device] = contribution
    }
  }

  override suspend fun forget(album: AlbumId, device: String) {
    synchronized(lock) {
      writes++
      byAlbum[album]?.remove(device)
      if (byAlbum[album]?.isEmpty() == true) byAlbum.remove(album)
    }
  }

  override suspend fun footprint(device: String): DeviceFootprint = synchronized(lock) {
    val mine = byAlbum.values.mapNotNull { it[device] }
    DeviceFootprint(mine.sumOf { it.entries.size }, mine.size, mine.sumOf { it.bytes })
  }

  override suspend fun rejectedHead(album: AlbumId, device: String): HeadMark? = synchronized(lock) { rejected[album to device] }

  override suspend fun setRejectedHead(album: AlbumId, device: String, mark: HeadMark?, reason: String?) {
    synchronized(lock) {
      if (mark == null) {
        rejected.remove(album to device)
        rejectedReasons.remove(album to device)
      } else {
        rejected[album to device] = mark
        if (reason != null) rejectedReasons[album to device] = reason else rejectedReasons.remove(album to device)
      }
    }
  }

  override suspend fun rejectedByReason(): Map<String, Int> = synchronized(lock) {
    rejected.keys.groupingBy { key -> rejectedReasons[key]?.takeIf(AlbumRejections::isValid) ?: AlbumRejections.UNKNOWN }.eachCount()
  }

  override suspend fun hold(album: AlbumId, device: String): ReplicaHold? = synchronized(lock) { holds[album to device] }

  override suspend fun setHold(album: AlbumId, device: String, hold: ReplicaHold?) {
    synchronized(lock) {
      writes++
      if (hold == null) holds.remove(album to device) else holds[album to device] = hold
    }
  }

  override suspend fun seenAt(album: AlbumId, device: String): Long? = synchronized(lock) { seen[album to device] }

  override suspend fun setSeenAt(album: AlbumId, device: String, at: Long) {
    synchronized(lock) {
      writes++
      seen[album to device] = at
    }
  }
}

/**
 * R6 della revisione di sicurezza della Fase 7 (A7, D8): le teste d'album di [device] da congelare quando l'admin lo dismette, cioe' quelle che
 * la replica di questo dispositivo ha accettato. Al piu' [max] (il registro ne tiene un numero limitato): prima i contributi che governano
 * l'album (la definizione, il livello, l'eredita': senza di loro l'album sparisce o cambia mano), poi quelli con piu' elementi vivi; a pari
 * merito l'ordine degli id. Mai una testa di un contributo escluso.
 */
object RetireAlbumHeads {
  suspend fun of(store: AlbumReplicaStore, device: String, max: Int): List<String> {
    val mine = store.albums().mapNotNull { store.contribution(it, device) }.filter { !it.barred }
    fun governs(c: StoredContribution) = c.entries.any { e ->
      !e.retrodated && (e.entry.field == dev.pampa.mycloudgallery.core.trust.data.AlbumField.Definition ||
        e.entry.field == dev.pampa.mycloudgallery.core.trust.data.AlbumField.Level || e.entry.field == dev.pampa.mycloudgallery.core.trust.data.AlbumField.Heir)
    }
    fun items(c: StoredContribution) = c.entries.count { !it.retrodated && it.entry.value is dev.pampa.mycloudgallery.core.trust.data.AlbumValue.Item }
    return mine.sortedWith(
      compareByDescending<StoredContribution> { governs(it) }.thenByDescending { items(it) }.thenBy { dev.pampa.mycloudgallery.core.trust.data.AlbumIds.text(it.album) },
    ).map { it.mark.contentHash }.distinct().take(max.coerceAtLeast(0))
  }
}
