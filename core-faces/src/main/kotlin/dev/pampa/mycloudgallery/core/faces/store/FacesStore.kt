package dev.pampa.mycloudgallery.core.faces.store

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.faces.FacesDatabase
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.FacesDiag
import dev.pampa.mycloudgallery.core.faces.sync.FacesPublishStore
import dev.pampa.mycloudgallery.core.faces.sync.FacesReplicaStore
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.security.faces.FacesDataKey

// IL NEGOZIO DEI VOLTI DI UNA GENERAZIONE (docs/fase8-progetto.md D14, D15, §2.8 "Disattiva e cancella", E-F19). Un database aperto con la sua
// chiave dati e quattro viste: l'archivio dei volti (contratto di P0), i segni anti-rollback, l'outbox e le unita' fatte. Si apre da
// [FacesStores.open], che sa cosa fare se la chiave e' sparita (E-F19) o il file e' danneggiato. Mai valori nei log (D37).

/**
 * Il database dei volti di [generation], aperto, con la chiave dati. [close] chiude prima l'archivio (da quel momento ogni chiamata lancia
 * `IllegalStateException`), poi il database, poi azzera la chiave dati in memoria. Idempotente.
 */
class FacesStore internal constructor(
  val generation: FaceGeneration,
  private val db: FacesDatabase,
  private val key: FacesDataKey,
) {
  private val databaseArchive = DatabaseFacesArchive(generation, db, key)

  /** L'archivio cifrato (`FacesArchive`, la suite di contratto di P0). */
  val archive: FacesArchive get() = databaseArchive

  val headMarks: FacesHeadMarks = DatabaseHeadMarks(db.headMarkDao())

  val outbox: FacesOutbox = DatabaseOutbox(db.outboxDao())

  val units: FacesUnitsDone = DatabaseUnitsDone(db.unitDao())

  private val replica = RoomFacesReplicaStore(generation, db.syncDao(), key)

  private val publication = RoomFacesPublishStore(generation, db.syncDao(), key)

  /**
   * Lo stato della replica dei dispositivi dell'account (P3, `NasFacesSync`) nel database: sopravvive alla morte del processo, le voci
   * `cluster` sigillate. Vale per la generazione di questo negozio.
   */
  val replicaStore: FacesReplicaStore get() = replica

  /** Lo stato del pubblicatore (P3, `NasFacesPublisher`): l'outbox che sopravvive alla morte del processo. Vale per la generazione di questo negozio. */
  val publishStore: FacesPublishStore get() = publication

  @Volatile private var closed = false

  val isOpen: Boolean get() = !closed

  /** Quanti corpi e quanti volti ci sono: solo numeri (Diagnostica, prove). */
  suspend fun counts(): FacesStoreCounts = databaseArchive.counts().let { (bodies, faces) -> FacesStoreCounts(bodies, faces) }

  /** Fa lanciare l'archivio senza chiudere il database: il primo passo della cancellazione, prima ancora di distruggere la chiave. */
  internal fun retire() {
    databaseArchive.retire()
    replica.retire()
    publication.retire()
  }

  @Synchronized
  fun close() {
    if (closed) return
    closed = true
    retire()
    try {
      db.close()
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "database non chiuso bene (${e.javaClass.simpleName})" }
    } finally {
      key.close()
    }
  }

  /** Il database sotto, per le prove di questo pacchetto. */
  internal val database: FacesDatabase get() = db

  override fun toString(): String = "FacesStore(aperto=$isOpen)"

  private companion object {
    const val TAG = "FacesStore"
  }
}

/** I numeri di un negozio (D37: niente chiavi, riquadri o id). */
data class FacesStoreCounts(val bodies: Int, val faces: Int)

/** Perche' un negozio e' stato rifatto da capo: i dati locali erano illeggibili (e si riscaricano dal NAS o si ricalcolano). */
enum class FacesRecovery(override val code: String, val diag: String?) : StableCode {
  /** La chiave del Keystore e' sparita o inutilizzabile (ripristino, aggiornamento del sistema: E-F19): `FACES_KEY_LOST`. */
  KEY_LOST("key_lost", FacesDiag.FACES_KEY_LOST),

  /** Il file del database era danneggiato (la chiave c'era). */
  DATABASE_DAMAGED("database_damaged", null),
}

/** Perche' il negozio non si apre adesso (niente e' stato cancellato): si riprova piu' tardi. */
enum class FacesUnavailable(override val code: String) : StableCode {
  /** Il Keystore non risponde o non c'e' (guasto passeggero, o nessun Keystore hardware in release). */
  KEYSTORE("keystore"),

  /** Il file non si apre o non si toglie (spazio finito, file bloccato). */
  STORAGE("storage"),

  /** Il database e' di una versione piu' nuova di questa app (app tornata indietro): non si apre e **non si cancella** mai. */
  NEWER_DATABASE("newer_database"),
}

/** L'esito di [FacesStores.open]: mai un'eccezione per un caso previsto (E-F19: nessun crollo). */
sealed interface FacesStoreOpen {
  /** Il negozio c'era (o e' nuovo) e funziona. */
  class Ready(val store: FacesStore) : FacesStoreOpen {
    override fun toString(): String = "FacesStoreOpen.Ready"
  }

  /**
   * I dati locali erano illeggibili e sono stati cancellati: [store] e' nuovo e **vuoto**. Chi riceve questo esito riscarica i dati dal NAS
   * o li ricalcola (E-F19) e segnala [FacesRecovery.diag] nella Diagnostica.
   */
  class Recovered(val store: FacesStore, val reason: FacesRecovery) : FacesStoreOpen {
    override fun toString(): String = "FacesStoreOpen.Recovered(${reason.code})"
  }

  data class Unavailable(val reason: FacesUnavailable) : FacesStoreOpen
}

/**
 * L'esito della cancellazione locale di una generazione ([FacesStores.eraseLocal]): la chiave distrutta **per prima**, poi i file. Finita
 * solo se tutte e due le cose sono vere; altrimenti si riprova (ogni passo e' idempotente, D29 passo 6).
 */
data class LocalErase(val keyDestroyed: Boolean, val filesGone: Boolean) {
  val done: Boolean get() = keyDestroyed && filesGone
}
