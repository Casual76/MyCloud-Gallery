package dev.pampa.mycloudgallery.core.faces.store

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.faces.dao.FaceBodyDao
import dev.pampa.mycloudgallery.core.database.faces.entity.FaceBodyEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.FaceEntity
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchiveLimits
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.Float16
import dev.pampa.mycloudgallery.core.model.enumByCodeOrNull
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// I VOLTI PER IL RAGGRUPPAMENTO, TENUTI IN MEMORIA (docs/fase8-progetto.md D29 "cache in memoria", §2.5). `FacesArchive.groupFaces` restituisce
// i vettori in chiaro di tutti i volti con un vettore: leggerli e decifrarli ogni volta (5 MB di blocchi per 20.000 volti, un'operazione AES
// per contenuto) costa piu' di cento millisecondi, e il custode lo chiede a ogni fine unita' per aggiungere poche centinaia di volti. Qui si
// legge e si decifra **una volta**; dopo, solo i contenuti salvati nel frattempo (`invalidate`, chiamato da `saveBody` dopo la scrittura).
//
// - I vettori in chiaro restano in memoria finche' il negozio e' aperto (la chiave dati che li decifra e' li' comunque, D14); [clear] li
//   lascia al primo ritiro dell'archivio (chiusura, cancellazione locale). Mai su disco, mai nei log (D37).
// - Un tetto ([MAX_FACES]): oltre, non si tiene niente e ogni chiamata rilegge (meglio piu' lenta che un heap gonfio).
// - R6 (correzioni della Fase 8): un tetto **duro** ([FacesArchiveLimits.MAX_GROUP_FACES]) che si guarda **prima** di caricare (un conteggio):
//   oltre, la lettura fallisce come un archivio chiuso (`IllegalStateException`: il raggruppamento salta il giro senza cadere) invece di
//   riempire la memoria fino a far morire il processo a ogni passo.
// - Un solo processo scrive questo database (un solo `FacesStores`): chi salva segna la chiave **dopo** aver scritto, e chi legge prende
//   l'elenco delle chiavi da rileggere **prima** di leggere: una scrittura che si incrocia con una lettura si vede al giro dopo, mai persa.

internal class GroupFaceCache(
  private val bodies: FaceBodyDao,
  private val secretsOf: (FaceBodyEntity) -> List<FaceSecrets.Secret>?,
  /** R6: il tetto duro dei volti letti per il raggruppamento. */
  private val maxGroupFaces: Int = FacesArchiveLimits.MAX_GROUP_FACES,
) {
  private val lock = Mutex()
  private val dirty = ConcurrentHashMap.newKeySet<String>()

  /** Chiave di contenuto -> i volti con vettore del suo corpo vincente nello spazio corrente (vuota: nessuno). */
  @Volatile private var cache: TreeMap<String, List<GroupFace>>? = null

  @Volatile private var tooBig = false

  /** [contentKey] e' stata scritta: la prossima lettura la rilegge. Dopo la scrittura sul database, mai prima. */
  fun invalidate(contentKey: String) {
    dirty += contentKey
  }

  /** Per le prove (R11): ci sono vettori in chiaro in memoria. */
  internal val holdsVectors: Boolean get() = cache != null

  /** L'archivio e' ritirato (R11): niente torna in memoria, una lettura in corso non rimette i vettori in chiaro nella cache. */
  @Volatile private var closed = false

  /** Lascia andare i vettori in chiaro (dopo una revoca: la prossima lettura rilegge da capo). */
  fun clear() {
    cache = null
    dirty.clear()
  }

  /**
   * L'archivio e' ritirato (la cancellazione locale): come [clear], e da qui la cache non si riempie piu'. Non aspetta il lucchetto (la cancellazione lo chiama
   * sotto il recinto): il segno [closed] impedisce a una lettura gia' in corso di rimettere i vettori in chiaro in memoria.
   */
  fun retire() {
    closed = true
    clear()
  }

  suspend fun faces(): List<GroupFace> = lock.withLock {
    check(!closed) { "Archivio dei volti chiuso." }
    val space = FaceSpaceCode.CURRENT.code
    val current = cache
    if (current == null || tooBig) {
      val loaded = loadAll(space)
      // Ritirato mentre si leggeva: i vettori letti non si tengono (chi chiama prende l'eccezione, come per ogni altra lettura di un archivio chiuso).
      check(!closed) { "Archivio dei volti chiuso." }
      val total = loaded.values.sumOf { it.size }
      if (total > MAX_FACES) {
        tooBig = true
        cache = null
        GalleryLog.i(TAG) { "troppi volti per tenerli in memoria: si rileggono a ogni richiesta" }
      } else {
        tooBig = false
        cache = loaded
      }
      return@withLock flatten(loaded)
    }
    val stale = dirty.toList()
    if (stale.isNotEmpty()) {
      dirty.removeAll(stale.toSet())
      for (chunk in stale.chunked(FaceBodyDao.IN_LIMIT)) refresh(current, chunk, space)
      // R6: cresciuta oltre il tetto della memoria, la cache si lascia e la prossima lettura ripassa dal tetto duro di [loadAll]; oltre il tetto
      // duro, nemmeno questa lettura risponde.
      val total = current.values.sumOf { it.size }
      if (total > MAX_FACES || total > maxGroupFaces) {
        tooBig = true
        cache = null
      }
      check(total <= maxGroupFaces) { TOO_MANY }
    }
    flatten(current)
  }

  private fun flatten(map: Map<String, List<GroupFace>>): List<GroupFace> {
    val out = ArrayList<GroupFace>(map.values.sumOf { it.size })
    for (list in map.values) out += list
    return out
  }

  /** Tutto, con due letture: i corpi vincenti nello spazio e i loro volti con un vettore. */
  private suspend fun loadAll(space: String): TreeMap<String, List<GroupFace>> {
    dirty.clear() // quel che e' stato scritto fin qui e' gia' nel database che si sta per leggere
    // R6: il tetto duro prima di leggere un solo corpo (e di nuovo sulle righe lette: un salvataggio nel frattempo non lo scavalca).
    val expected = bodies.countSearchableFaces(space, FaceTier.NONE.code)
    check(expected <= maxGroupFaces) { TOO_MANY }
    val headers = bodies.winnersInSpace(space)
    val out = TreeMap<String, List<GroupFace>>()
    if (headers.isEmpty()) return out
    val faceRows = bodies.searchableFaces(space, FaceTier.NONE.code)
    check(faceRows.size <= maxGroupFaces) { TOO_MANY }
    val rows = faceRows.groupBy { it.contentKey to it.author }
    for (header in headers) {
      val faces = rows[header.contentKey to header.author] ?: continue
      out[header.contentKey] = facesOf(header, faces)
    }
    return out
  }

  /** Rilegge solo [keys]: un contenuto che non ha piu' un vincitore nello spazio corrente esce dalla memoria. */
  private suspend fun refresh(cache: TreeMap<String, List<GroupFace>>, keys: List<String>, space: String) {
    val headers = bodies.winners(keys).filter { it.space == space }.associateBy { it.contentKey }
    val rows = if (headers.isEmpty()) emptyMap() else bodies.facesOf(headers.keys.toList()).groupBy { it.contentKey to it.author }
    for (key in keys) {
      val header = headers[key]
      val faces = header?.let { rows[it.contentKey to it.author] }
      if (header == null || faces == null) cache.remove(key) else cache[key] = facesOf(header, faces)
    }
  }

  /** I volti con un vettore di un corpo (livello CORE e WEAK), o nessuno se il blocco non si apre o i valori non sono validi. */
  private fun facesOf(header: FaceBodyEntity, rows: List<FaceEntity>): List<GroupFace> {
    val secrets = secretsOf(header) ?: return emptyList()
    return try {
      val contentKey = ContentKey(header.contentKey)
      rows.sortedBy { it.index }.mapNotNull { row ->
        val tier = enumByCodeOrNull<FaceTier>(row.tier)?.takeIf { it.hasVector } ?: return@mapNotNull null
        val vector = secrets.getOrNull(row.index)?.vector ?: return@mapNotNull null
        val ref = FaceRef(contentKey, row.frame, row.index, header.digest)
        GroupFace(ref, FaceLocator(contentKey, row.frame, FaceBox(row.x0, row.y0, row.x1, row.y1)), tier, Float16.decode(vector))
      }
    } catch (_: IllegalArgumentException) {
      GalleryLog.w(TAG) { "un corpo con valori non validi e' stato saltato" }
      emptyList()
    }
  }

  private companion object {
    const val TAG = "FacesArchive"

    /** Volti con vettore che si tengono in memoria (~0,55 KB l'uno): 120.000 sono circa 65 MB. */
    const val MAX_FACES = 120_000

    const val TOO_MANY = "Troppi volti per il raggruppamento."
  }
}
