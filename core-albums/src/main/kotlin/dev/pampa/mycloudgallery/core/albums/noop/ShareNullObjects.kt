package dev.pampa.mycloudgallery.core.albums.noop

import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidates
import dev.pampa.mycloudgallery.core.albums.contract.AlbumFamily
import dev.pampa.mycloudgallery.core.albums.contract.AlbumMover
import dev.pampa.mycloudgallery.core.albums.contract.AlbumPublisher
import dev.pampa.mycloudgallery.core.albums.contract.AlbumSyncReport
import dev.pampa.mycloudgallery.core.albums.contract.AlbumUploader
import dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteBlock
import dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteGate
import dev.pampa.mycloudgallery.core.albums.contract.AttestRequest
import dev.pampa.mycloudgallery.core.albums.contract.CandidateCursor
import dev.pampa.mycloudgallery.core.albums.contract.CandidateFilter
import dev.pampa.mycloudgallery.core.albums.contract.CandidatePage
import dev.pampa.mycloudgallery.core.albums.contract.CatalogFacts
import dev.pampa.mycloudgallery.core.albums.contract.FlushReason
import dev.pampa.mycloudgallery.core.albums.contract.MediaFacts
import dev.pampa.mycloudgallery.core.albums.contract.NasItemRef
import dev.pampa.mycloudgallery.core.albums.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SafeCopyRequester
import dev.pampa.mycloudgallery.core.albums.contract.ShareBlock
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmation
import dev.pampa.mycloudgallery.core.albums.contract.ShareFolder
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpId
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpState
import dev.pampa.mycloudgallery.core.albums.contract.SharePlan
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.ShareRunResult
import dev.pampa.mycloudgallery.core.albums.contract.ShareRunStop
import dev.pampa.mycloudgallery.core.albums.contract.ShareRunner
import dev.pampa.mycloudgallery.core.albums.contract.ShareStartOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumFacts
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumSync
import dev.pampa.mycloudgallery.core.albums.contract.SharedOrigins
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.contract.StageTicket
import dev.pampa.mycloudgallery.core.albums.contract.TicketState
import dev.pampa.mycloudgallery.core.albums.contract.TicketStatus
import dev.pampa.mycloudgallery.core.albums.contract.TransferKey
import dev.pampa.mycloudgallery.core.albums.contract.TransferRefusal
import dev.pampa.mycloudgallery.core.albums.contract.TransferStart
import dev.pampa.mycloudgallery.core.albums.contract.TransferTicket
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// I null-object della condivisione e delle porte (Fase 7, P0). Falliscono CHIUSI: nessun piano, nessun trasferimento avviato, nessuna
// pubblicazione, nessun fatto del catalogo (quindi niente da attestare, niente da mostrare), nessuna richiesta registrata. Una porta che
// non puo' registrare cio' che le si chiede lancia (chi chiama non deve credere il contrario).

/** Nessuna condivisione: ogni piano e' fermo ("nessun NAS"). */
object NoShareRunner : ShareRunner {
  override val operations: StateFlow<List<ShareOpState>> = MutableStateFlow(emptyList())

  override suspend fun plan(request: ShareRequest): SharePlanOutcome = SharePlanOutcome.Blocked(ShareBlock.NO_NAS)

  override suspend fun start(plan: SharePlan, confirmation: ShareConfirmation): ShareStartOutcome = ShareStartOutcome.Blocked(ShareBlock.NO_NAS)

  override suspend fun resumeAll(budget: Duration): ShareRunResult = ShareRunResult(0, 0, 0, 0, ShareRunStop.FINISHED)

  override suspend fun cancel(op: ShareOpId) = Unit
}

/** Nessun pubblicatore: le modifiche non escono mai (nessuna testa, niente risulta pubblicato). */
object NoAlbumPublisher : AlbumPublisher {
  override suspend fun stage(album: AlbumId, changes: List<SharedAlbumChange>): StageTicket = StageTicket(1)

  override suspend fun flush(album: AlbumId, reason: FlushReason): PublishOutcome = PublishOutcome.Blocked(AlbumWriteBlock.NO_NAS)

  override suspend fun publishedUpTo(album: AlbumId): StageTicket = StageTicket(0)

  override val pending: StateFlow<Int> = MutableStateFlow(0)
}

object NoSharedAlbumSync : SharedAlbumSync {
  override suspend fun sync(budget: Duration): AlbumSyncReport = AlbumSyncReport(0, 0, 0, 0, 0, incomplete = false)
}

/** Il cancello chiuso: nessun NAS, nessuna scrittura. */
object ClosedAlbumWriteGate : AlbumWriteGate {
  override suspend fun blockFor(share: String): AlbumWriteBlock = AlbumWriteBlock.NO_NAS
}

object NoAlbumMover : AlbumMover {
  override suspend fun move(key: TransferKey, items: List<NasItemRef>, dest: ShareFolder, leave: Set<NasItemRef>, confirmation: ShareConfirmation): TransferStart =
    TransferStart.Refused(TransferRefusal.NAS_UNREACHABLE)
}

object NoAlbumUploader : AlbumUploader {
  override suspend fun upload(key: TransferKey, media: List<MediaId>, dest: ShareFolder, confirmation: ShareConfirmation): TransferStart =
    TransferStart.Refused(TransferRefusal.NAS_UNREACHABLE)
}

object NoTicketStatus : TicketStatus {
  override suspend fun status(ticket: TransferTicket): TicketState = TicketState.Unknown

  override suspend fun find(key: TransferKey): TransferTicket? = null

  override suspend fun cancel(ticket: TransferTicket) = Unit
}

/** Nessun guardiano: la richiesta non e' registrata (lancia, D28). */
object NoSafeCopyRequester : SafeCopyRequester {
  override suspend fun request(media: List<MediaId>) {
    if (media.isNotEmpty()) throw IllegalStateException("Nessun guardiano: copia privata non richiesta.")
  }
}

/** Nessun posto dove scrivere la cartella d'origine: senza, nessuno spostamento deve partire (lancia). */
object NoSharedOrigins : SharedOrigins {
  override suspend fun record(media: MediaId, origin: ShareFolder) {
    throw IllegalStateException("Cartella d'origine non registrabile.")
  }

  override suspend fun originOf(media: MediaId): ShareFolder? = null
}

/** Nessun catalogo: nessun fatto, niente attestato. */
object NoCatalogFacts : CatalogFacts {
  override suspend fun facts(media: Collection<MediaId>): Map<MediaId, MediaFacts> = emptyMap()

  override suspend fun attest(items: Collection<AttestRequest>): Map<ContentKey, MediaId> = emptyMap()
}

object NoAlbumCandidates : AlbumCandidates {
  override suspend fun page(filter: CandidateFilter, after: CandidateCursor?, limit: Int): CandidatePage = CandidatePage(emptyList(), null)

  override suspend fun visibleTotal(): Int = 0
}

object NoSharedAlbumFacts : SharedAlbumFacts {
  override suspend fun folder(album: AlbumId): ShareFolder? = null

  override suspend fun members(album: AlbumId): Map<MediaId, AccountId> = emptyMap()

  override suspend fun state(album: AlbumId): SharedState? = null
}

/** Nessuna famiglia pronta. */
object NoAlbumFamily : AlbumFamily {
  override fun me(): AccountId? = null

  override fun commonShare(): String? = null

  override fun privateShare(): String? = null

  override fun amAdmin(): Boolean = false

  override fun leftFamily(account: AccountId): Boolean = false

  override fun backupFolderName(): String = "Backup"
}
