package dev.pampa.mycloudgallery.core.albums.shared

import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.trust.data.AlbumEntry
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.data.SegmentRef

// LA REPLICA LOCALE DEGLI ALBUM CONDIVISI (§2.5 "Ricevere", punto 1; D34: tabelle `shared_album_entry` e `shared_album_head_mark` di P1).
// Per ogni (album, dispositivo) l'ultimo contributo accettato: il segno della sua testa (anti-rollback ed equivocazione), l'ora di
// creazione, i segmenti elencati e le voci con il loro segmento (una testa nuova riscarica solo i segmenti nuovi) e il segno "retrodatata"
// (§2.4: una voce scartata perche' retrodatata resta scartata nelle teste dopo). In piu' la testa rifiutata (per non riscaricarla a ogni giro).

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

  override fun toString(): String = "StoredContribution(v${mark.version}, ${entries.size} voci, escluso=$barred)"
}

/** La replica: una scrittura sostituisce il contributo intero di (album, dispositivo) in una sola transazione. */
interface AlbumReplicaStore {
  suspend fun albums(): Set<AlbumId>

  suspend fun contributions(album: AlbumId): List<StoredContribution>

  suspend fun contribution(album: AlbumId, device: String): StoredContribution?

  suspend fun put(contribution: StoredContribution)

  /** Toglie il contributo (la memoria di un escluso oltre i tetti, o l'azzeramento). */
  suspend fun forget(album: AlbumId, device: String)

  /** Le voci di [device] in tutti gli album (tetto per dispositivo) e quanti album. */
  suspend fun footprint(device: String): Pair<Int, Int>

  /** L'ultima testa rifiutata di (album, dispositivo): non si riscarica finche' non ne compare una diversa. */
  suspend fun rejectedHead(album: AlbumId, device: String): HeadMark?

  suspend fun setRejectedHead(album: AlbumId, device: String, mark: HeadMark?)
}

/** La replica in memoria: l'implementazione del pacchetto finche' P1 non consegna le tabelle v6 (l'adattatore Room e' di P1/P13). */
class MemoryAlbumReplicaStore : AlbumReplicaStore {
  private val lock = Any()
  private val byAlbum = LinkedHashMap<AlbumId, LinkedHashMap<String, StoredContribution>>()
  private val rejected = HashMap<Pair<AlbumId, String>, HeadMark>()

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

  override suspend fun footprint(device: String): Pair<Int, Int> = synchronized(lock) {
    val mine = byAlbum.values.mapNotNull { it[device] }
    mine.sumOf { it.entries.size } to mine.size
  }

  override suspend fun rejectedHead(album: AlbumId, device: String): HeadMark? = synchronized(lock) { rejected[album to device] }

  override suspend fun setRejectedHead(album: AlbumId, device: String, mark: HeadMark?) {
    synchronized(lock) { if (mark == null) rejected.remove(album to device) else rejected[album to device] = mark }
  }
}
