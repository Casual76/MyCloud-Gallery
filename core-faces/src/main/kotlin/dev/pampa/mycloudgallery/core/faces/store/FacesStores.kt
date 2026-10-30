package dev.pampa.mycloudgallery.core.faces.store

import android.database.sqlite.SQLiteDatabaseCorruptException
import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.common.files.Patience
import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.faces.FacesDatabase
import dev.pampa.mycloudgallery.core.database.faces.FacesDatabaseFiles
import dev.pampa.mycloudgallery.core.database.faces.FacesDatabaseOpener
import dev.pampa.mycloudgallery.core.database.faces.entity.FacesMetaEntity
import dev.pampa.mycloudgallery.core.faces.sync.FacesArchives
import dev.pampa.mycloudgallery.core.faces.sync.FacesPublishState
import dev.pampa.mycloudgallery.core.faces.sync.FacesPublishStore
import dev.pampa.mycloudgallery.core.faces.sync.FacesReplicaState
import dev.pampa.mycloudgallery.core.faces.sync.FacesReplicaStore
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.security.faces.FacesDataKey
import dev.pampa.mycloudgallery.core.security.faces.FacesKeyProbe
import dev.pampa.mycloudgallery.core.security.faces.FacesKeyState
import dev.pampa.mycloudgallery.core.security.faces.FacesKeys
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Dove stanno i database dei volti di questo dispositivo, uno per generazione (Fase 8, P6; `docs/fase8-progetto.md` D14, D15, D29, E-F19,
 * E-F35): li apre con la chiave dati giusta, li rifa' da capo se la chiave e' sparita o il file e' danneggiato, e li cancella **come file**
 * con la chiave distrutta per prima. L'unica porta del database dei volti e della sua chiave per il resto dell'app (`Phase8ArchitectureTest`:
 * solo `store/` ed `erase/` nominano la chiave, solo `store/` il database).
 *
 * Il file sta in [directory] (di norma `noBackupFilesDir/faces/`, fuori da qualunque backup e trasferimento) e si chiama `faces-<G>.db`, con
 * i suoi `-wal` e `-shm`. La [keys] sono quelle del Keystore ([dev.pampa.mycloudgallery.core.security.faces.FacesKeyStore]); nelle prove il
 * fake di P0.
 *
 * **Nessuna eccezione per i casi previsti.** [open] risponde con un esito ([FacesStoreOpen]): un negozio pronto, uno **rifatto da capo**
 * perche' la chiave e' sparita (E-F19, `FACES_KEY_LOST`) o il file e' danneggiato, oppure "non adesso". Una chiave che **non risponde**
 * (guasto passeggero) non cancella mai niente; un database di una versione futura non si apre e non si cancella.
 *
 * Mai nomi di file, generazioni o valori nei log (D37): i file si nominano con un'etichetta fissa.
 */
class FacesStores(
  private val directory: File,
  private val databases: FacesDatabaseOpener,
  private val keys: FacesKeys,
  private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
  private val patience: Patience = Patience.STANDARD,
  /** Chiamata quando un negozio e' stato rifatto da capo (E-F19: `FACES_KEY_LOST`; file danneggiato): la Diagnostica lo conta. Solo il motivo. */
  private val onRecovered: (FacesRecovery) -> Unit = {},
) {
  private val lock = Mutex()
  private val opened = HashMap<FaceGeneration, FacesStore>()

  /** Le generazioni di cui questo dispositivo ha un file del database (anche solo un `-wal`), in ordine. */
  fun generationsOnDisk(): List<FaceGeneration> = FacesDatabaseFiles.generationsIn(directory)

  /** C'e' ancora qualcosa del database di [generation] sul disco? Falso dopo una cancellazione riuscita (D29 invariante 1). */
  fun hasFiles(generation: FaceGeneration): Boolean = FacesDatabaseFiles.allOf(fileOf(generation)).any { it.exists() }

  /** Il negozio di [generation]: lo apre, lo crea (generazione nuova, con la sua chiave) o lo rifa' (E-F19). Mai un'eccezione prevista. */
  suspend fun open(generation: FaceGeneration): FacesStoreOpen = withContext(dispatchers.io) {
    lock.withLock {
      opened[generation]?.let { if (it.isOpen) return@withLock FacesStoreOpen.Ready(it) }
      opened.remove(generation)
      openLocked(generation)
    }
  }

  /**
   * I6 (correzioni A): il negozio di [generation] **solo se c'e' gia' e si legge**, per i nomi che la cancellazione toglie dalla cronologia. A differenza di
   * [open] non fa mai nascere una chiave o un database e non toglie mai un file: con la chiave gia' distrutta (la cancellazione e' morta fra la chiave e i
   * file) risponde [ExistingStore.Unreadable], senza rifare niente e senza il falso `FACES_KEY_LOST`.
   */
  suspend fun openExisting(generation: FaceGeneration): ExistingStore = withContext(dispatchers.io) {
    lock.withLock {
      opened[generation]?.let { if (it.isOpen) return@withLock ExistingStore.Ready(it) }
      opened.remove(generation)
      val main = fileOf(generation)
      if (!main.exists() || main.length() == 0L) return@withLock ExistingStore.Unreadable
      val onDisk = FacesDatabaseFiles.versionOf(main)
      if (onDisk != null && onDisk > FacesDatabase.VERSION) return@withLock ExistingStore.Unreadable
      val key = when (val found = openKey(generation)) {
        is KeyOpen.Key -> found.key
        KeyOpen.Lost -> return@withLock ExistingStore.Unreadable
        KeyOpen.Unknown -> return@withLock ExistingStore.Unknown
      }
      when (val result = tryOpen(main, key)) {
        is DbOpen.Ok -> FacesStore(generation, result.db, key).also { opened[generation] = it }.let { ExistingStore.Ready(it) }
        DbOpen.WrongKey, DbOpen.Damaged -> {
          key.close()
          ExistingStore.Unreadable
        }
        DbOpen.Failed -> {
          key.close()
          ExistingStore.Unknown
        }
      }
    }
  }

  /**
   * Le tre porte che la sincronizzazione (P3) vuole dal database, con il negozio che si apre da solo: i legami per `:app` (P13).
   * - [archives]: l'archivio della generazione, o null se il negozio non si apre adesso (Keystore, disco, versione);
   * - [replicaStore] e [publishStore]: lo stato della replica e del pubblicatore, durevole. Un negozio che non si apre da' una replica vuota in
   *   lettura e `IllegalStateException` in scrittura (chi sincronizza se n'e' gia' accorto da [archives]).
   * Un negozio rifatto da capo per una chiave sparita passa da [onRecovered].
   */
  fun archives(): FacesArchives = FacesArchives { generation -> storeOf(generation)?.archive }

  fun replicaStore(): FacesReplicaStore = object : FacesReplicaStore {
    override suspend fun load(generation: FaceGeneration): FacesReplicaState =
      storeOf(generation)?.replicaStore?.load(generation) ?: FacesReplicaState.EMPTY

    override suspend fun save(generation: FaceGeneration, state: FacesReplicaState) =
      (storeOf(generation) ?: throw IllegalStateException("Il database dei volti non si apre adesso.")).replicaStore.save(generation, state)
  }

  fun publishStore(): FacesPublishStore = object : FacesPublishStore {
    override suspend fun load(generation: FaceGeneration): FacesPublishState =
      storeOf(generation)?.publishStore?.load(generation) ?: FacesPublishState.EMPTY

    override suspend fun save(generation: FaceGeneration, state: FacesPublishState) =
      (storeOf(generation) ?: throw IllegalStateException("Il database dei volti non si apre adesso.")).publishStore.save(generation, state)
  }

  private suspend fun storeOf(generation: FaceGeneration): FacesStore? = when (val opened = open(generation)) {
    is FacesStoreOpen.Ready -> opened.store
    is FacesStoreOpen.Recovered -> opened.store
    is FacesStoreOpen.Unavailable -> null
  }

  /**
   * La cancellazione locale di [generation] (D29 passo 6, E-F8, azzeramento): **prima** la chiave (e con lei il file che la avvolge),
   * poi il database con `-wal`, `-shm` e `-journal`, uno per uno con la cancellazione verificata di `StateFiles`. Un negozio aperto si
   * ritira subito (da quel momento lancia) e si chiude dopo la chiave. Idempotente: si puo' richiamare finche' non e' [LocalErase.done].
   * Non si interrompe a meta' (`NonCancellable`).
   */
  suspend fun eraseLocal(generation: FaceGeneration): LocalErase = withContext(NonCancellable + dispatchers.io) {
    lock.withLock { eraseLocked(generation) }
  }

  /**
   * Tutto cio' che questo dispositivo ha dei volti **tranne** [keep] (di norma la generazione corrente): le generazioni non correnti si
   * cancellano (E-F33, `FacesSwitch.reconcile`). Falso in [LocalErase.done] se anche una sola non e' finita.
   */
  suspend fun eraseAllExcept(keep: FaceGeneration?): LocalErase = withContext(NonCancellable + dispatchers.io) {
    lock.withLock {
      val victims = (generationsOnDisk() + opened.keys).distinctBy { it.base32 }.filter { it != keep }
      val results = victims.map { eraseLocked(it) }
      LocalErase(keyDestroyed = results.all { it.keyDestroyed }, filesGone = results.all { it.filesGone })
    }
  }

  /**
   * L'azzeramento del dispositivo (E-F35): **tutte** le chiavi dei volti per prime, poi tutti i database. Anche generazioni che questo
   * oggetto non ha mai aperto.
   */
  suspend fun eraseEverything(): LocalErase = withContext(NonCancellable + dispatchers.io) {
    lock.withLock {
      val victims = (generationsOnDisk() + opened.keys).distinctBy { it.base32 }
      opened.values.forEach { it.retire() }
      val keyDestroyed = keys.destroyAll()
      opened.values.forEach { it.close() }
      opened.clear()
      val filesGone = victims.map { deleteFiles(it) }.all { it }
      LocalErase(keyDestroyed, filesGone)
    }
  }

  // --- implementazione -----------------------------------------------------------------------------------------------------------

  private fun fileOf(generation: FaceGeneration): File = FacesDatabaseFiles.fileOf(directory, generation)

  private sealed interface KeyOpen {
    class Key(val key: FacesDataKey) : KeyOpen

    /** La chiave non c'e' piu' (o non si apre piu'): i dati cifrati con lei sono perduti. */
    data object Lost : KeyOpen

    /** Non si sa: il Keystore non risponde adesso. */
    data object Unknown : KeyOpen
  }

  private sealed interface DbOpen {
    class Ok(val db: FacesDatabase) : DbOpen

    /** Il database non e' di questa chiave. */
    data object WrongKey : DbOpen

    data object Damaged : DbOpen

    data object Failed : DbOpen
  }

  /** Apre la chiave: piu' tentativi, perche' un guasto passeggero del Keystore non e' una chiave sparita. */
  private suspend fun openKey(generation: FaceGeneration): KeyOpen {
    val probe = keys as? FacesKeyProbe
    repeat(KEY_ATTEMPTS) {
      keys.open(generation)?.let { return KeyOpen.Key(it) }
      if (probe != null) {
        when (probe.state(generation)) {
          FacesKeyState.MISSING, FacesKeyState.UNUSABLE -> return KeyOpen.Lost
          FacesKeyState.USABLE, FacesKeyState.UNAVAILABLE -> Unit
        }
      }
    }
    // Senza modo di distinguere, piu' risposte "nessuna chiave" di fila sono una chiave sparita; col modo, non si sa.
    return if (probe != null) KeyOpen.Unknown else KeyOpen.Lost
  }

  private suspend fun openLocked(generation: FaceGeneration): FacesStoreOpen {
    val main = fileOf(generation)
    // Un database di una versione futura (app tornata indietro) non si tocca, qualunque sia la chiave.
    val onDisk = FacesDatabaseFiles.versionOf(main)
    if (onDisk != null && onDisk > FacesDatabase.VERSION) return FacesStoreOpen.Unavailable(FacesUnavailable.NEWER_DATABASE)

    var recovery: FacesRecovery? = null
    val key: FacesDataKey
    when (val found = openKey(generation)) {
      is KeyOpen.Key -> key = found.key
      KeyOpen.Unknown -> return FacesStoreOpen.Unavailable(FacesUnavailable.KEYSTORE)
      KeyOpen.Lost -> {
        if (hasFiles(generation)) {
          // E-F19: dati locali senza la loro chiave. Illeggibili: si cancellano (si riscaricano o si ricalcolano).
          if (!deleteFiles(generation)) return FacesStoreOpen.Unavailable(FacesUnavailable.STORAGE)
          recovery = FacesRecovery.KEY_LOST
        }
        if (!keys.create(generation)) return FacesStoreOpen.Unavailable(FacesUnavailable.KEYSTORE)
        key = keys.open(generation) ?: return FacesStoreOpen.Unavailable(FacesUnavailable.KEYSTORE)
      }
    }

    var result = tryOpen(main, key)
    if (result is DbOpen.WrongKey || result is DbOpen.Damaged) {
      // Il file c'e' ma non e' leggibile con questa chiave (chiave ricreata da altri) o e' danneggiato: da capo.
      recovery = if (result is DbOpen.WrongKey) FacesRecovery.KEY_LOST else FacesRecovery.DATABASE_DAMAGED
      if (!deleteFiles(generation)) {
        key.close()
        return FacesStoreOpen.Unavailable(FacesUnavailable.STORAGE)
      }
      result = tryOpen(main, key)
    }
    if (result !is DbOpen.Ok) {
      key.close()
      return FacesStoreOpen.Unavailable(FacesUnavailable.STORAGE)
    }
    val store = FacesStore(generation, result.db, key)
    opened[generation] = store
    if (recovery == null) return FacesStoreOpen.Ready(store)
    try {
      onRecovered(recovery)
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "chi ascolta i negozi rifatti ha lanciato (${e.javaClass.simpleName})" }
    }
    return FacesStoreOpen.Recovered(store, recovery)
  }

  /** Apre il database e controlla che sia di [key]: un testo noto sigillato alla creazione (`faces_meta`). */
  private suspend fun tryOpen(main: File, key: FacesDataKey): DbOpen {
    var db: FacesDatabase? = null
    try {
      directory.mkdirs()
      val existed = main.length() > 0
      val database = databases.open(main).also { db = it }
      database.openNow()
      val meta = database.metaDao()
      val stored = meta.get(KEY_CHECK)
      if (stored == null) {
        // Un file che c'era e che adesso non ha il controllo della chiave non e' quello che avevamo scritto: Android, trovandolo
        // danneggiato, lo ha gia' tolto e ne ha fatto uno vuoto (DefaultDatabaseErrorHandler). Si dice "danneggiato" e si riparte da capo.
        if (existed) {
          db?.closeQuietly()
          return DbOpen.Damaged
        }
        meta.put(FacesMetaEntity(KEY_CHECK, key.seal(FaceSecrets.KEY_CHECK_PLAIN, FaceSecrets.keyCheckAad())))
      } else if (key.open(stored, FaceSecrets.keyCheckAad())?.contentEquals(FaceSecrets.KEY_CHECK_PLAIN) != true) {
        db?.closeQuietly()
        return DbOpen.WrongKey
      }
      return DbOpen.Ok(database)
    } catch (e: CancellationException) {
      db?.closeQuietly()
      throw e
    } catch (e: SQLiteDatabaseCorruptException) {
      GalleryLog.w(TAG) { "database dei volti danneggiato (${e.javaClass.simpleName})" }
      db?.closeQuietly()
      return DbOpen.Damaged
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "database dei volti non aperto (${e.javaClass.simpleName})" }
      db?.closeQuietly()
      return DbOpen.Failed
    }
  }

  private fun FacesDatabase.closeQuietly() {
    try {
      close()
    } catch (_: Exception) {
      // Gia' chiuso o rotto: il file si toglie comunque.
    }
  }

  private suspend fun eraseLocked(generation: FaceGeneration): LocalErase {
    val handle = opened.remove(generation)
    // 0. Da qui il negozio aperto lancia: nessuna scrittura nuova mentre si cancella (non e' ancora un passo sul Keystore).
    handle?.retire()
    // 1. LA CHIAVE, per prima: ogni resto (pagine libere di SQLite, WAL, blocchi della flash) diventa illeggibile.
    val keyDestroyed = keys.destroy(generation)
    // 2. Il database: chiuso (e la chiave dati in memoria azzerata), poi i file, uno per uno, verificati.
    handle?.close()
    val filesGone = deleteFiles(generation)
    return LocalErase(keyDestroyed, filesGone)
  }

  /** Toglie `faces-<G>.db`, `-wal`, `-shm` e `-journal`; vero solo se non ne resta nessuno. Non si ferma al primo che non si toglie. */
  private fun deleteFiles(generation: FaceGeneration): Boolean {
    var allGone = true
    for (file in FacesDatabaseFiles.allOf(fileOf(generation))) {
      if (!StateFiles.delete(file, label = FILE_LABEL, patience = patience)) allGone = false
    }
    return allGone
  }

  override fun toString(): String = "FacesStores"

  companion object {
    /** La cartella (sotto `noBackupFilesDir`) dei database dei volti. */
    const val DIRECTORY: String = "faces"

    private const val TAG = "FacesStores"
    private const val FILE_LABEL = "faces-db"
    private const val KEY_CHECK = "key_check"
    private const val KEY_ATTEMPTS = 3
  }
}
