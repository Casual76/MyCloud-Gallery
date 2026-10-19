package dev.pampa.mycloudgallery.core.mediastore.testing

import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import androidx.core.net.toUri
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
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
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// I fake di core-mediastore (D23): in src/main, cosi' i moduli a valle (core-library, feature-*) li riusano nei
// loro test senza cicli. Mai nella release: tools/check.ps1 fallisce se una classe di un package `testing`
// compare nel dex dell'APK di release.
//
// Il simulatore del MediaProvider (`FakeMediaStore`) sta nel suo file.

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

  override fun uriOf(ref: MediaRef.Phone): Uri = "content://media/${ref.volume}/${ref.mediaStoreId}".toUri()

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

  /** Fase 5B (P0-B): le richieste di eliminazione e di scrittura valide, nell'ordine (distinte, al massimo 2.000, solo con il cestino di sistema). */
  val deleteRequests: MutableList<List<MediaRef.Phone>> = mutableListOf()
  val writeRequests: MutableList<List<MediaRef.Phone>> = mutableListOf()

  override fun deleteRequest(refs: List<MediaRef.Phone>): IntentSender? {
    validRequest(refs)?.let { deleteRequests += it }
    return null
  }

  override fun writeRequest(refs: List<MediaRef.Phone>): IntentSender? {
    validRequest(refs)?.let { writeRequests += it }
    return null
  }

  /** Le righe distinte di una richiesta valida come la vuole Android (11+, non vuota, al massimo 2.000), o null: nessuna richiesta parziale. */
  private fun validRequest(refs: List<MediaRef.Phone>): List<MediaRef.Phone>? {
    val distinct = refs.distinct()
    return if (!canTrash || distinct.isEmpty() || distinct.size > PhoneMediaIntents.MAX_REQUEST_URIS) null else distinct
  }
}

/** Un cestino di sistema con gli elementi scelti dal test. */
class FakeRecentlyDeletedSource(var items: List<TrashedItem> = emptyList()) : RecentlyDeletedSource {
  override suspend fun list(): List<TrashedItem> = items
}
