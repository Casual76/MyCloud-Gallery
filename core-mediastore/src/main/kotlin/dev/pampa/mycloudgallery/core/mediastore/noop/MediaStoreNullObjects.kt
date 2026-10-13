package dev.pampa.mycloudgallery.core.mediastore.noop

import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.mediastore.contract.DeletedRow
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreRow
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreSource
import dev.pampa.mycloudgallery.core.mediastore.contract.MetaPriority
import dev.pampa.mycloudgallery.core.mediastore.contract.MetadataPass
import dev.pampa.mycloudgallery.core.mediastore.contract.PauseReason
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccessReader
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneLibrarySync
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneSyncRunner
import dev.pampa.mycloudgallery.core.mediastore.contract.RecentlyDeletedSource
import dev.pampa.mycloudgallery.core.mediastore.contract.RunResult
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReport
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncStatus
import dev.pampa.mycloudgallery.core.mediastore.contract.TrashedItem
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

// I null-object di core-mediastore: cio' che :app lega finche' P2 non porta l'adattatore vero e la
// sincronizzazione.
//
// Non stanno nel package `testing` di proposito: vanno nella release, mentre i fake no (tools/check.ps1 lo
// verifica). Falliscono chiusi: nessun volume, nessun permesso, nessuna riga, e soprattutto **nessuna assenza
// dedotta** (E-P15): senza accesso la sincronizzazione e' ferma, non "il telefono e' vuoto".

/** Nessun MediaStore: nessun volume, nessuna riga. Aprire un byte e' un errore (non si dovrebbe arrivare qui). */
object NoMediaStoreSource : MediaStoreSource {
  override suspend fun volumes(): Set<String> = emptySet()

  override suspend fun version(volume: String): String = ""

  override suspend fun generation(volume: String): Long? = null

  override suspend fun query(volume: String, sinceGeneration: Long?, afterId: Long?, limit: Int): List<MediaStoreRow> = emptyList()

  override suspend fun deletedSince(volume: String, generation: Long): List<DeletedRow>? = null

  override suspend fun allIds(volume: String): LongArray = LongArray(0)

  override suspend fun open(row: MediaStoreRow): MediaBytes = throw UnsupportedOperationException("Nessun MediaStore collegato.")

  override fun changes(): Flow<Unit> = emptyFlow()
}

/** Nessun accesso ai media. */
object NoPhoneAccessReader : PhoneAccessReader {
  override val state: StateFlow<PhoneAccess> = MutableStateFlow(PhoneAccess.NONE)

  override fun refresh() = Unit
}

/** Una sincronizzazione ferma per mancanza di permessi: non legge e non segna mai assenze. */
object NoPhoneLibrarySync : PhoneLibrarySync {
  override val status: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus.Paused(PauseReason.NO_PERMISSION))

  override suspend fun syncNow(reason: SyncReason): SyncReport = SyncReport(completed = false)

  override fun attach(scope: CoroutineScope) = Unit

  override fun detach() = Unit
}

/** Il lavoro in background che non fa niente. */
object NoPhoneSyncRunner : PhoneSyncRunner {
  override suspend fun run(budget: Duration): RunResult = RunResult.Paused(PauseReason.NO_PERMISSION)
}

/** La fase dei metadati che non fa niente. */
object NoMetadataPass : MetadataPass {
  override suspend fun run(budget: Duration, priority: MetaPriority): RunResult = RunResult.Done
}

/** Nessuna finestra visibile: la fase dei metadati va dai piu' recenti. */
object NoMetaPriority : MetaPriority {
  override val visible: StateFlow<List<MediaId>> = MutableStateFlow(emptyList())
}

/** Nessuna azione di sistema: niente da condividere, nessun cestino. */
object NoPhoneMediaIntents : PhoneMediaIntents {
  override val canTrash: Boolean = false

  override fun uriOf(ref: MediaRef.Phone): Uri = Uri.EMPTY

  override fun share(refs: List<MediaRef.Phone>): Intent? = null

  override fun trashRequest(refs: List<MediaRef.Phone>, trash: Boolean): IntentSender? = null
}

/** Nessun cestino di sistema (Android 10, o nessun accesso). */
object NoRecentlyDeletedSource : RecentlyDeletedSource {
  override suspend fun list(): List<TrashedItem> = emptyList()
}
