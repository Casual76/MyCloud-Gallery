package dev.pampa.mycloudgallery.core.albums.noop

import dev.pampa.mycloudgallery.core.albums.contract.AddOutcome
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.AlbumContent
import dev.pampa.mycloudgallery.core.albums.contract.AlbumList
import dev.pampa.mycloudgallery.core.albums.contract.AlbumListSort
import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.albums.contract.AlbumsStatus
import dev.pampa.mycloudgallery.core.albums.contract.ClosePreviewOutcome
import dev.pampa.mycloudgallery.core.albums.contract.CloseMode
import dev.pampa.mycloudgallery.core.albums.contract.CloseOutcome
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.EditOutcome
import dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice
import dev.pampa.mycloudgallery.core.albums.contract.InheritOutcome
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.albums.contract.ProposalNote
import dev.pampa.mycloudgallery.core.albums.contract.RuleProposal
import dev.pampa.mycloudgallery.core.albums.contract.ShareBlock
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumNotice
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbums
import dev.pampa.mycloudgallery.core.albums.contract.SmartAlbumEngine
import dev.pampa.mycloudgallery.core.albums.contract.SmartChoices
import dev.pampa.mycloudgallery.core.albums.contract.SmartNameInterpreter
import dev.pampa.mycloudgallery.core.albums.contract.SmartOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SmartPreview
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.contract.TripAcceptOutcome
import dev.pampa.mycloudgallery.core.albums.contract.TripId
import dev.pampa.mycloudgallery.core.albums.contract.TripProposals
import dev.pampa.mycloudgallery.core.albums.contract.TripReport
import dev.pampa.mycloudgallery.core.albums.contract.Trips
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import java.time.LocalDate
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

// I null-object degli album (Fase 7, P0), fuori da `testing` e quindi buoni anche per la release: :app li lega finche' P13 non integra e
// quando la famiglia non e' pronta. Falliscono CHIUSI: nessun album, nessuna proposta, nessuna modifica, nessun permesso, e lo dicono
// con un motivo invece di fingere. Mai un media mostrato, mai un'azione sui file.

/** Nessun album. */
object NoAlbums : Albums {
  override val all: StateFlow<AlbumList> = MutableStateFlow(AlbumList.EMPTY)

  override val status: StateFlow<AlbumsStatus> = MutableStateFlow(AlbumsStatus.Ready)

  override fun content(id: AlbumId): Flow<AlbumContent?> = flowOf(null)

  override suspend fun create(title: String, kind: AlbumKind, rules: SmartRules?): CreateOutcome = CreateOutcome.Unavailable

  override suspend fun rename(id: AlbumId, title: String): EditOutcome = EditOutcome.NotFound

  override suspend fun delete(id: AlbumId): EditOutcome = EditOutcome.NotFound

  override suspend fun add(id: AlbumId, media: List<MediaId>): AddOutcome = AddOutcome.NotFound

  override suspend fun remove(id: AlbumId, media: List<MediaId>): EditOutcome = EditOutcome.NotFound

  override suspend fun setCover(id: AlbumId, media: MediaId?): EditOutcome = EditOutcome.NotFound

  override suspend fun setSort(id: AlbumId, sort: AlbumSort): EditOutcome = EditOutcome.NotFound

  override suspend fun reorder(id: AlbumId, moved: MediaId, after: MediaId?): EditOutcome = EditOutcome.NotFound

  override suspend fun setRules(id: AlbumId, rules: SmartRules): EditOutcome = EditOutcome.NotFound

  override suspend fun setListSort(sort: AlbumListSort) = Unit

  override suspend fun moveInList(id: AlbumId, after: AlbumId?): EditOutcome = EditOutcome.NotFound
}

/** Senza analizzatore: nessuna regola proposta (l'editor parte vuoto e lo dice). */
object NoSmartNameInterpreter : SmartNameInterpreter {
  override suspend fun propose(title: String, today: LocalDate): RuleProposal = RuleProposal(
    rules = SmartRules(period = null, place = null, text = null),
    alternatives = emptyList(),
    periodSeries = emptyList(),
    notes = setOf(ProposalNote.NO_DATE, ProposalNote.NO_PLACE, ProposalNote.NO_SEARCH),
  )
}

/** Nessun motore: nessun album smart si ricalcola, l'anteprima e' vuota. */
object NoSmartAlbumEngine : SmartAlbumEngine {
  override suspend fun evaluate(id: AlbumId, budget: Duration): SmartOutcome = SmartOutcome.NotFound

  override suspend fun preview(rules: SmartRules): SmartPreview = SmartPreview(0, 0, tooBroad = false, sample = emptyList(), searchUsed = false)
}

object NoSmartChoices : SmartChoices {
  override suspend fun exclude(id: AlbumId, media: List<MediaId>): EditOutcome = EditOutcome.NotFound

  override suspend fun accept(id: AlbumId, media: List<MediaId>): EditOutcome = EditOutcome.NotFound

  override suspend fun freeze(id: AlbumId): EditOutcome = EditOutcome.NotFound

  override suspend fun unfreeze(id: AlbumId): EditOutcome = EditOutcome.NotFound
}

/** Nessun album condiviso: niente si vede, niente si crea, nessun permesso. */
object NoSharedAlbums : SharedAlbums {
  override val visible: StateFlow<List<Album.Shared>> = MutableStateFlow(emptyList())

  override val notices: StateFlow<List<SharedAlbumNotice>> = MutableStateFlow(emptyList())

  override val left: StateFlow<List<Album.Shared>> = MutableStateFlow(emptyList())

  override suspend fun create(title: String, level: ShareLevel, media: List<MediaId>, hidden: HiddenChoice): SharePlanOutcome =
    SharePlanOutcome.Blocked(ShareBlock.NO_NAS)

  override suspend fun rename(id: AlbumId, title: String): EditOutcome = EditOutcome.NotFound

  override suspend fun remove(id: AlbumId, media: List<MediaId>): EditOutcome = EditOutcome.NotFound

  override suspend fun setCover(id: AlbumId, media: MediaId?): EditOutcome = EditOutcome.NotFound

  override suspend fun setSort(id: AlbumId, sort: AlbumSort): EditOutcome = EditOutcome.NotFound

  override suspend fun setLevel(id: AlbumId, level: ShareLevel): EditOutcome = EditOutcome.NotFound

  override suspend fun closePreview(id: AlbumId, mode: CloseMode): ClosePreviewOutcome = ClosePreviewOutcome.NotFound

  override suspend fun close(id: AlbumId, mode: CloseMode, forOthers: OthersAfterClose): CloseOutcome = CloseOutcome.NotFound

  override suspend fun leave(id: AlbumId): EditOutcome = EditOutcome.NotFound

  override suspend fun rejoin(id: AlbumId): EditOutcome = EditOutcome.NotFound

  override suspend fun inherit(id: AlbumId): InheritOutcome = InheritOutcome.NotFound

  override fun allowed(id: AlbumId, op: AlbumOp, item: MediaId?): Boolean = false

  override suspend fun dismissNotice(notice: SharedAlbumNotice) = Unit
}

/** Nessun viaggio: nessuna casa, nessuna proposta. */
object NoTrips : Trips {
  override val proposals: StateFlow<TripProposals> = MutableStateFlow(TripProposals.EMPTY)

  override suspend fun detect(budget: Duration): TripReport = TripReport(0, 0, homeKnown = false, incomplete = false)

  override suspend fun accept(id: TripId): TripAcceptOutcome = TripAcceptOutcome.NotFound

  override suspend fun dismiss(id: TripId) = Unit
}
