package dev.pampa.mycloudgallery.feature.integrations.edit.testing

import dev.pampa.mycloudgallery.core.backup.contract.EditSession
import dev.pampa.mycloudgallery.core.backup.contract.EditSessionId
import dev.pampa.mycloudgallery.core.backup.contract.EditSessionState
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.feature.integrations.edit.EditCatalog
import dev.pampa.mycloudgallery.feature.integrations.edit.EditCurrent
import dev.pampa.mycloudgallery.feature.integrations.edit.EditMedia
import dev.pampa.mycloudgallery.feature.integrations.edit.EditNasCopies
import dev.pampa.mycloudgallery.feature.integrations.edit.EditSessionDraft
import dev.pampa.mycloudgallery.feature.integrations.edit.EditSessionStore
import dev.pampa.mycloudgallery.feature.integrations.edit.GalleryDownload
import dev.pampa.mycloudgallery.feature.integrations.edit.GalleryDownloads
import dev.pampa.mycloudgallery.feature.integrations.edit.NasCopy
import dev.pampa.mycloudgallery.feature.integrations.edit.StagedVersionPlanner
import dev.pampa.mycloudgallery.feature.integrations.edit.StoredEditSession
import java.io.File
import java.security.MessageDigest

// Fase 5B (P11): i fake per provare "Modifica con…" senza catalogo, NAS ne' database. Mai nella release: `tools/check.ps1` vieta i package `testing` nel dex.

/** Le sessioni in memoria: sopravvivono a ogni "morte del processo" del test perche' le tiene il test. */
class InMemoryEditSessionStore : EditSessionStore {
  private val all = LinkedHashMap<Long, StoredEditSession>()
  private var counter = 1L

  /** Tutte le sessioni, dalla piu' vecchia. */
  val sessions: List<StoredEditSession> get() = all.values.toList()

  override suspend fun create(draft: EditSessionDraft): EditSession {
    val id = counter++
    val session = EditSession(
      id = EditSessionId(id),
      mediaId = draft.mediaId,
      versionId = draft.versionId,
      target = draft.target,
      state = EditSessionState.OPEN,
      phone = draft.phone,
      cacheName = draft.cacheName,
      startedAtNasMillis = draft.startedAtNasMillis,
      expiresAtNasMillis = draft.expiresAtNasMillis,
    )
    all[id] = StoredEditSession(session, draft.snapshot)
    return session
  }

  override suspend fun find(id: EditSessionId): StoredEditSession? = all[id.value]

  override suspend fun setState(id: EditSessionId, state: EditSessionState) {
    val old = all[id.value] ?: return
    all[id.value] = StoredEditSession(old.session.copy(state = state), old.snapshot)
  }

  override suspend fun open(): List<StoredEditSession> = all.values.filter { it.session.state == EditSessionState.OPEN }
}

/**
 * Un catalogo di prova che il test pilota: i media, la versione attuale (che il test cambia come farebbe la lettura del telefono), le righe note e il
 * risultato dei ricollegamenti. [onRefresh] gira a ogni `refreshPhone`: li' il test fa succedere cio' che la scansione scoprirebbe.
 */
class FakeEditCatalog : EditCatalog {
  val media: MutableMap<MediaId, EditMedia> = HashMap()
  val current: MutableMap<MediaId, EditCurrent> = HashMap()
  val mediaOfRow: MutableMap<PhoneRef, MediaId> = HashMap()

  /** Il risultato di `linkCopyAsVersion`: la versione nuova, o null (il catalogo rifiuta). */
  var linkResult: VersionId? = null
  var addEditedResult: VersionId? = null

  var refreshes: Int = 0
    private set
  var onRefresh: () -> Unit = {}

  /** Cio' che e' stato chiesto: (media, copia, genitore). */
  val links: MutableList<Triple<MediaId, MediaId, VersionId>> = ArrayList()
  val edited: MutableList<Triple<MediaId, VersionId, Sha256>> = ArrayList()

  override suspend fun media(id: MediaId): EditMedia? = media[id]

  override suspend fun refreshPhone() {
    refreshes++
    onRefresh()
  }

  override suspend fun current(id: MediaId): EditCurrent? = current[id]

  override suspend fun mediaOfPhoneRow(ref: PhoneRef): MediaId? = mediaOfRow[ref]

  override suspend fun linkCopyAsVersion(into: MediaId, copy: MediaId, parent: VersionId): VersionId? {
    links += Triple(into, copy, parent)
    return linkResult
  }

  override suspend fun addEditedVersion(into: MediaId, parent: VersionId, sha256: Sha256, quickHash: QuickHash, size: Long): VersionId? {
    edited += Triple(into, parent, sha256)
    return addEditedResult
  }
}

/** Il NAS di prova: scrive nella copia in cache i byte che il test ha messo, o risponde come il test ha deciso. */
class FakeNasCopies : EditNasCopies {
  val bytes: MutableMap<MediaId, ByteArray> = HashMap()
  var unreachable: Boolean = false
  var full: Boolean = false
  var copies: Int = 0
    private set

  override suspend fun copyCurrent(mediaId: MediaId, target: File): NasCopy {
    copies++
    if (unreachable) return NasCopy.Unreachable
    if (full) return NasCopy.Full
    val data = bytes[mediaId] ?: return NasCopy.Unavailable
    target.writeBytes(data)
    return NasCopy.Done(Sha256(MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 0xff) }), data.size.toLong())
  }
}

/** "Scarica e modifica in Galleria" di prova. */
class FakeGalleryDownloads : GalleryDownloads {
  var result: GalleryDownload = GalleryDownload.Unavailable
  var requests: Int = 0
    private set

  override suspend fun downloadForEdit(mediaId: MediaId): GalleryDownload {
    requests++
    return result
  }
}

/** Il pianificatore dei lavori `VERSION`: registra cio' che gli si chiede. */
class RecordingStagedVersionPlanner : StagedVersionPlanner {
  val planned: MutableList<Triple<MediaId, VersionId, Sha256>> = ArrayList()
  var accepts: Boolean = true

  override suspend fun plan(mediaId: MediaId, versionId: VersionId, sha256: Sha256, size: Long): Boolean {
    planned += Triple(mediaId, versionId, sha256)
    return accepts
  }
}
