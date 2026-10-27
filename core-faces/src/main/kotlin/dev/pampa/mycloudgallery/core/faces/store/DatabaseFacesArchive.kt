package dev.pampa.mycloudgallery.core.faces.store

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.faces.FacesDatabase
import dev.pampa.mycloudgallery.core.database.faces.dao.FaceBodyDao
import dev.pampa.mycloudgallery.core.database.faces.entity.FaceBodyEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.FaceEntity
import dev.pampa.mycloudgallery.core.faces.contract.FaceBody
import dev.pampa.mycloudgallery.core.faces.contract.FaceEntry
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleKey
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceDetectorCode
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FaceSource
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.enumByCodeOrNull
import dev.pampa.mycloudgallery.core.security.faces.FacesDataKey

/**
 * L'archivio dei volti di UNA generazione sul database cifrato (Fase 8, P6; `docs/fase8-progetto.md` D14-D16, §2.3 "Salvataggio", §2.4
 * "Fusione dei rilevamenti"). Passa la stessa suite di contratto dell'archivio in memoria (`FacesArchiveContract`).
 *
 * - **Salva subito**: [saveBody] e' una transazione (corpo, volti, vincitore): dopo una morte del processo un media gia' salvato non si rifa'.
 * - **Cifrato** (C39): landmark e vettori di un corpo in un solo blocco sigillato (`FaceSecrets`), nomi sigillati (`PeopleTables`); il
 *   resto delle colonne e' in chiaro perche' le query lo chiedono senza decifrare. Nessun vettore o nome in chiaro nel file.
 * - **Un corpo che non si apre** (altra chiave, byte alterati, forma sconosciuta) e' come se non ci fosse: non si restituisce e non fa
 *   crollare niente; il contenuto si rifa' o si riscarica (E-F19). I blocchi sono legati alla loro riga (`aad`).
 * - **Chiuso** (chiave distrutta, generazione cambiata, [retire]): ogni chiamata lancia `IllegalStateException` (chi chiama riconcilia, §2.8).
 *
 * Le liste di chiavi si spezzano in gruppi da [FaceBodyDao.IN_LIMIT] (SQLite di Android 8-10 accetta 999 variabili). Mai valori nei log (D37).
 */
class DatabaseFacesArchive internal constructor(
  override val generation: FaceGeneration,
  private val db: FacesDatabase,
  private val key: FacesDataKey,
) : FacesArchive {

  @Volatile private var closed = false

  private val bodies = db.bodyDao()
  private val grouping = GroupingTables(db.groupingDao())
  private val people = PeopleTables(db.peopleDao(), key)
  private val groupCache = GroupFaceCache(bodies, ::openSecrets)

  /** Da qui ogni chiamata lancia `IllegalStateException`. Lo fa `FacesStore.close` (e quindi la cancellazione locale, **dopo** la chiave). */
  internal fun retire() {
    closed = true
    groupCache.clear()
  }

  private fun open() = check(!closed) { "Archivio dei volti chiuso." }

  override suspend fun saveBody(body: FaceBody) {
    open()
    val contentKey = body.contentKey.hex
    val secret = key.seal(FaceSecrets.encode(body.faces), aadOf(body))
    val header = FaceBodyEntity(
      contentKey = contentKey,
      author = body.author,
      space = body.space.code,
      detector = body.detector.code,
      recipe = body.recipe,
      source = body.source.code,
      digest = body.digest,
      faceCount = body.faces.size,
      secret = secret,
    )
    val faces = body.faces.map { f ->
      FaceEntity(
        contentKey, body.author, f.index, f.frame, f.box.x0, f.box.y0, f.box.x1, f.box.y1, f.score, f.tier.code, f.reasons, f.sidePx, f.yaw, f.sharpness,
      )
    }
    bodies.replaceBody(header, faces)
    // Dopo la scrittura, mai prima: chi legge i volti per il raggruppamento rilegge questo contenuto.
    groupCache.invalidate(contentKey)
  }

  override suspend fun winners(keys: Collection<ContentKey>): Map<ContentKey, FaceBody> {
    open()
    val out = LinkedHashMap<ContentKey, FaceBody>()
    for (chunk in keys.map { it.hex }.distinct().chunked(FaceBodyDao.IN_LIMIT)) {
      val headers = bodies.winners(chunk)
      if (headers.isEmpty()) continue
      val facesOf = bodies.facesOf(headers.map { it.contentKey }).groupBy { it.contentKey to it.author }
      for (header in headers) {
        val body = toBody(header, facesOf[header.contentKey to header.author].orEmpty()) ?: continue
        out[body.contentKey] = body
      }
    }
    return out
  }

  override suspend fun bodiesOf(author: String, keys: Collection<ContentKey>): Map<ContentKey, FaceBody> {
    open()
    val out = LinkedHashMap<ContentKey, FaceBody>()
    for (chunk in keys.map { it.hex }.distinct().chunked(FaceBodyDao.IN_LIMIT)) {
      val headers = bodies.bodiesOfAuthor(author, chunk)
      if (headers.isEmpty()) continue
      val facesOf = bodies.facesOfAuthor(author, headers.map { it.contentKey }).groupBy { it.contentKey to it.author }
      for (header in headers) {
        val body = toBody(header, facesOf[header.contentKey to header.author].orEmpty()) ?: continue
        out[body.contentKey] = body
      }
    }
    return out
  }

  override suspend fun analyzed(keys: Collection<ContentKey>, recipe: Int): Set<ContentKey> {
    open()
    val out = LinkedHashSet<ContentKey>()
    for (chunk in keys.map { it.hex }.distinct().chunked(FaceBodyDao.IN_LIMIT)) {
      bodies.analyzedKeys(chunk, recipe).mapTo(out, ::ContentKey)
      bodies.unsupportedKeys(chunk, recipe).mapTo(out, ::ContentKey)
    }
    return out
  }

  override suspend fun markUnsupported(key: ContentKey, recipe: Int) {
    open()
    bodies.markUnsupported(key.hex, recipe)
  }

  override suspend fun groupFaces(): List<GroupFace> {
    open()
    return groupCache.faces()
  }

  override suspend fun unrecognizable(keys: Collection<ContentKey>): Map<ContentKey, Int> {
    open()
    val out = LinkedHashMap<ContentKey, Int>()
    for (chunk in keys.map { it.hex }.distinct().chunked(FaceBodyDao.IN_LIMIT)) {
      for (row in bodies.countByTier(chunk, FaceTier.NONE.code)) out[ContentKey(row.contentKey)] = row.count
    }
    return out
  }

  override suspend fun grouping(): GroupingState {
    open()
    return grouping.load()
  }

  override suspend fun saveGrouping(state: GroupingState) {
    open()
    grouping.save(state)
  }

  override suspend fun people(): Map<PeopleKey, PeopleRecord> {
    open()
    return people.all()
  }

  override suspend fun addPeople(records: List<PeopleRecord>) {
    open()
    people.add(records)
  }

  override suspend fun datePeople(nasNow: Long): Int {
    open()
    return people.date(nasNow)
  }

  override suspend fun dropAuthors(authors: Set<String>): Int {
    open()
    if (authors.isEmpty()) return 0
    var dropped = 0
    for (chunk in authors.toList().chunked(FaceBodyDao.IN_LIMIT)) dropped += bodies.dropBodiesOf(chunk)
    // Il vincitore di alcune chiavi e' cambiato: i volti per il raggruppamento si rileggono da capo (una revoca e' rara).
    groupCache.clear()
    return dropped + people.dropAuthors(authors)
  }

  /** Quanti corpi e quanti volti ci sono (numeri per la Diagnostica e le prove). */
  internal suspend fun counts(): Pair<Int, Int> {
    open()
    return bodies.bodyCount() to bodies.faceCount()
  }

  // --- pezzi ---------------------------------------------------------------------------------------------------------------------

  private fun aadOf(body: FaceBody): ByteArray = FaceSecrets.bodyAad(body.contentKey.hex, body.author, body.digest, body.recipe, body.faces.size)

  private fun openSecrets(header: FaceBodyEntity): List<FaceSecrets.Secret>? {
    val plain = key.open(header.secret, FaceSecrets.bodyAad(header.contentKey, header.author, header.digest, header.recipe, header.faceCount))
      ?: return null
    return FaceSecrets.decode(plain, header.faceCount)
  }

  /** Il corpo di una riga, o null se non si apre o non e' quello che questa versione capisce. */
  private fun toBody(header: FaceBodyEntity, rows: List<FaceEntity>): FaceBody? = try {
    val space = enumByCodeOrNull<FaceSpaceCode>(header.space)
    val detector = enumByCodeOrNull<FaceDetectorCode>(header.detector)
    val source = enumByCodeOrNull<FaceSource>(header.source)
    if (space == null || detector == null || source == null || rows.size != header.faceCount) {
      null
    } else {
      openSecrets(header)?.let { secrets ->
        val faces = rows.sortedBy { it.index }.mapIndexed { i, row ->
          val tier = enumByCodeOrNull<FaceTier>(row.tier) ?: throw IllegalArgumentException("Livello sconosciuto.")
          val secret = secrets[i]
          require(row.index == i && (secret.vector != null) == tier.hasVector) { "Righe e blocco non tornano." }
          FaceEntry(
            row.index, row.frame, FaceBox(row.x0, row.y0, row.x1, row.y1), secret.landmarks, row.score, tier, row.reasons, row.sidePx, row.yaw,
            row.sharpness, secret.vector,
          )
        }
        FaceBody(ContentKey(header.contentKey), space, detector, header.recipe, source, header.author, faces, header.digest)
      }
    }
  } catch (_: IllegalArgumentException) {
    GalleryLog.w(TAG) { "un corpo con valori non validi e' stato saltato" }
    null
  }

  /** D37: mai chiavi di contenuto, vettori o generazione. */
  override fun toString(): String = "DatabaseFacesArchive(chiuso=$closed)"

  private companion object {
    const val TAG = "FacesArchive"
  }
}
