package dev.pampa.mycloudgallery.core.mediastore.testing

import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.mediastore.contract.DeletedRow
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreRow
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreSource
import dev.pampa.mycloudgallery.core.mediastore.contract.MetaPriority
import dev.pampa.mycloudgallery.core.mediastore.contract.PauseReason
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccessReader
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneLibrarySync
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.contract.RecentlyDeletedSource
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReport
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncStatus
import dev.pampa.mycloudgallery.core.mediastore.contract.TrashedItem
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.metadata.testing.ByteArrayMediaBytes
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// I fake di core-mediastore (D23): in src/main, cosi' i moduli a valle (core-library, feature-*) li riusano nei
// loro test senza cicli. Mai nella release: tools/check.ps1 fallisce se una classe di un package `testing`
// compare nel dex dell'APK di release.

/**
 * Un MediaStore in memoria: la base del simulatore con cui P2 prova tutta la sincronizzazione senza dispositivo.
 *
 * P0 ne dà il minimo che serve a far girare il resto: volumi (anche non montati, E-P6), righe con la generazione
 * che sale a ogni scrittura, righe tolte (`deleted_files`), versione che cambia con rinumerazione degli `_ID`
 * (E-P12, E-P22), segnale di cambiamento. P2 lo estende (cestino di Samsung, accesso parziale, latenze, righe
 * `IS_PENDING`...): e' nel suo perimetro.
 */
class FakeMediaStore : MediaStoreSource {

  private class FakeVolume(var version: String, var mounted: Boolean = true) {
    var generation: Long = 1
    val rows = LinkedHashMap<Long, MediaStoreRow>()
    val bytes = HashMap<Long, ByteArray>()
    val deleted = ArrayList<DeletedRow>()
  }

  private val volumeMap = LinkedHashMap<String, FakeVolume>()
  private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

  /** Se falso, [generation] e [deletedSince] rispondono come su API 29 (nessuna generazione). */
  var supportsGenerations: Boolean = true

  /** Quante volte e' stata chiamata [open]: la scorciatoia del timbro non deve aprire nessun file. */
  var opens: Int = 0
    private set

  fun addVolume(name: String, version: String = "v1", mounted: Boolean = true) {
    volumeMap[name] = FakeVolume(version, mounted)
  }

  /** Monta o smonta un volume (microSD tolta e rimessa, E-P6). */
  fun setMounted(name: String, mounted: Boolean) {
    volumeMap.getValue(name).mounted = mounted
  }

  /** Aggiunge o aggiorna una riga: la sua `GENERATION_MODIFIED` e' la generazione del volume, che sale. */
  fun put(row: MediaStoreRow, content: ByteArray = ByteArray(row.size.toInt().coerceIn(0, MAX_FAKE_BYTES))): MediaStoreRow {
    val volume = volumeMap.getValue(row.volume)
    volume.generation++
    val stored = MediaStoreRow(
      volume = row.volume, id = row.id, type = row.type, displayName = row.displayName, relativePath = row.relativePath,
      mime = row.mime, size = row.size, dateModifiedMs = row.dateModifiedMs, dateTakenMs = row.dateTakenMs, width = row.width,
      height = row.height, orientation = row.orientation, durationMs = row.durationMs,
      generationModified = if (supportsGenerations) volume.generation else null, ownerPackage = row.ownerPackage,
    )
    volume.rows[row.id] = stored
    volume.bytes[row.id] = content
    changed.tryEmit(Unit)
    return stored
  }

  /** Toglie una riga: finisce in `deleted_files` con la generazione corrente. */
  fun remove(volume: String, id: Long) {
    val v = volumeMap.getValue(volume)
    v.generation++
    if (v.rows.remove(id) != null) v.deleted += DeletedRow(volume, id, if (supportsGenerations) v.generation else null)
    v.bytes.remove(id)
    changed.tryEmit(Unit)
  }

  /**
   * Il MediaStore si e' ricostruito (aggiornamento di One UI o Mainline, E-P22): nuova versione, generazioni che
   * ripartono, e gli `_ID` rinumerati secondo [renumber] (vecchio → nuovo; gli id non elencati restano).
   */
  fun bumpVersion(volume: String, newVersion: String, renumber: Map<Long, Long> = emptyMap()) {
    val v = volumeMap.getValue(volume)
    val oldRows = v.rows.values.toList()
    val oldBytes = HashMap(v.bytes)
    v.version = newVersion
    v.generation = 1
    v.rows.clear()
    v.bytes.clear()
    v.deleted.clear()
    for (row in oldRows) {
      val newId = renumber[row.id] ?: row.id
      v.generation++
      v.rows[newId] = MediaStoreRow(
        volume = row.volume, id = newId, type = row.type, displayName = row.displayName, relativePath = row.relativePath,
        mime = row.mime, size = row.size, dateModifiedMs = row.dateModifiedMs, dateTakenMs = row.dateTakenMs,
        width = row.width, height = row.height, orientation = row.orientation, durationMs = row.durationMs,
        generationModified = if (supportsGenerations) v.generation else null, ownerPackage = row.ownerPackage,
      )
      v.bytes[newId] = oldBytes[row.id] ?: ByteArray(0)
    }
    changed.tryEmit(Unit)
  }

  override suspend fun volumes(): Set<String> = volumeMap.filterValues { it.mounted }.keys.toSet()

  override suspend fun version(volume: String): String = volumeMap.getValue(volume).version

  override suspend fun generation(volume: String): Long? = if (supportsGenerations) volumeMap.getValue(volume).generation else null

  override suspend fun query(volume: String, sinceGeneration: Long?, afterId: Long?, limit: Int): List<MediaStoreRow> {
    val v = volumeMap.getValue(volume)
    // Dalle piu' recenti: DATE_TAKEN DESC, poi _ID DESC (l'ordine di scansione del §2.1).
    var ordered = v.rows.values.sortedWith(compareByDescending<MediaStoreRow> { it.dateTakenMs ?: it.dateModifiedMs }.thenByDescending { it.id })
    if (sinceGeneration != null) ordered = ordered.filter { (it.generationModified ?: Long.MAX_VALUE) > sinceGeneration }
    if (afterId != null) {
      val position = ordered.indexOfFirst { it.id == afterId }
      ordered = if (position >= 0) ordered.drop(position + 1) else ordered
    }
    return ordered.take(limit)
  }

  override suspend fun deletedSince(volume: String, generation: Long): List<DeletedRow>? {
    if (!supportsGenerations) return null
    return volumeMap.getValue(volume).deleted.filter { (it.generationModified ?: 0) > generation }
  }

  override suspend fun allIds(volume: String): LongArray = volumeMap.getValue(volume).rows.keys.toLongArray()

  override suspend fun open(row: MediaStoreRow): MediaBytes {
    opens++
    return ByteArrayMediaBytes(volumeMap.getValue(row.volume).bytes[row.id] ?: ByteArray(0))
  }

  override fun changes(): Flow<Unit> = changed

  private companion object {
    /** Le righe finte non occupano la loro dimensione dichiarata: al massimo 4 KiB di contenuto. */
    const val MAX_FAKE_BYTES = 4096
  }
}

/** Un accesso scelto dal test, osservabile. */
class FakePhoneAccessReader(initial: PhoneAccess = PhoneAccess(MediaAccess.FULL, mediaLocation = true, volumes = setOf("external_primary"))) :
  PhoneAccessReader {

  private val flow = MutableStateFlow(initial)
  override val state: StateFlow<PhoneAccess> = flow

  /** Quante volte e' stato riletto (a ogni `onResume`, E-P1). */
  var refreshes: Int = 0
    private set

  fun set(access: PhoneAccess) {
    flow.value = access
  }

  override fun refresh() {
    refreshes++
  }
}

/** Una sincronizzazione che registra le richieste e risponde con un bilancio scelto dal test. */
class FakePhoneLibrarySync(var report: SyncReport = SyncReport()) : PhoneLibrarySync {

  private val flow = MutableStateFlow<SyncStatus>(SyncStatus.Idle(atMs = 0))
  override val status: StateFlow<SyncStatus> = flow

  /** Le richieste ricevute, nell'ordine. */
  val requests: MutableList<SyncReason> = mutableListOf()

  var attached: Boolean = false
    private set

  fun setStatus(status: SyncStatus) {
    flow.value = status
  }

  fun pause(reason: PauseReason) {
    flow.value = SyncStatus.Paused(reason)
  }

  override suspend fun syncNow(reason: SyncReason): SyncReport {
    requests += reason
    return report
  }

  override fun attach(scope: CoroutineScope) {
    attached = true
  }

  override fun detach() {
    attached = false
  }
}

/** Una priorita' scelta dal test. */
class FakeMetaPriority(initial: List<MediaId> = emptyList()) : MetaPriority {
  private val flow = MutableStateFlow(initial)
  override val visible: StateFlow<List<MediaId>> = flow

  fun set(ids: List<MediaId>) {
    flow.value = ids
  }
}

/**
 * Le azioni di sistema finte: l'URI e' `content://media/<volume>/<id>`, la condivisione e' un intento senza
 * destinatario e il cestino registra le richieste (un `IntentSender` non si puo' costruire su JVM: il chiamante nei
 * test controlla [trashRequests]).
 */
class FakePhoneMediaIntents(override var canTrash: Boolean = true) : PhoneMediaIntents {

  val trashRequests: MutableList<Pair<List<MediaRef.Phone>, Boolean>> = mutableListOf()
  val shares: MutableList<List<MediaRef.Phone>> = mutableListOf()

  override fun uriOf(ref: MediaRef.Phone): Uri = Uri.parse("content://media/${ref.volume}/${ref.mediaStoreId}")

  override fun share(refs: List<MediaRef.Phone>): Intent? {
    if (refs.isEmpty()) return null
    shares += refs
    return Intent(if (refs.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE)
  }

  override fun trashRequest(refs: List<MediaRef.Phone>, trash: Boolean): IntentSender? {
    if (refs.isEmpty() || !canTrash) return null
    trashRequests += refs to trash
    return null
  }
}

/** Un cestino di sistema con gli elementi scelti dal test. */
class FakeRecentlyDeletedSource(var items: List<TrashedItem> = emptyList()) : RecentlyDeletedSource {
  override suspend fun list(): List<TrashedItem> = items
}
