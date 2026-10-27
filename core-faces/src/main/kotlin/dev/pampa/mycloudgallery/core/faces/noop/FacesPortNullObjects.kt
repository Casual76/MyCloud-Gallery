package dev.pampa.mycloudgallery.core.faces.noop

import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import dev.pampa.mycloudgallery.core.faces.contract.FaceBody
import dev.pampa.mycloudgallery.core.faces.contract.FaceInput
import dev.pampa.mycloudgallery.core.faces.contract.FaceInputs
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.FacesArea
import dev.pampa.mycloudgallery.core.faces.contract.FacesCatalog
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraseOps
import dev.pampa.mycloudgallery.core.faces.contract.FacesItem
import dev.pampa.mycloudgallery.core.faces.contract.FacesLease
import dev.pampa.mycloudgallery.core.faces.contract.FacesLeases
import dev.pampa.mycloudgallery.core.faces.contract.FacesListing
import dev.pampa.mycloudgallery.core.faces.contract.FacesPermit
import dev.pampa.mycloudgallery.core.faces.contract.FacesPut
import dev.pampa.mycloudgallery.core.faces.contract.FacesRead
import dev.pampa.mycloudgallery.core.faces.contract.FacesRemote
import dev.pampa.mycloudgallery.core.faces.contract.FacesRequirements
import dev.pampa.mycloudgallery.core.faces.contract.FacesResource
import dev.pampa.mycloudgallery.core.faces.contract.FacesRunControl
import dev.pampa.mycloudgallery.core.faces.contract.FacesStop
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteBlock
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.IndexerState
import dev.pampa.mycloudgallery.core.faces.contract.InputWait
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseCheck
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseReason
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseRound
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import dev.pampa.mycloudgallery.core.faces.contract.PeopleKey
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.contract.PrivateAreaState
import dev.pampa.mycloudgallery.core.faces.contract.SearchHistoryPurge
import dev.pampa.mycloudgallery.core.faces.contract.SmartAlbumsPeople
import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlin.time.Duration

// I null-object delle porte di core-faces (Fase 8, P0): falliscono CHIUSI. Nessun ingresso, nessun media, nessuna lettura o scrittura sul
// NAS, nessuna prenotazione, nessuna cancellazione, nessun permesso di calcolare, nessuna area privata.

object NoFaceInputs : FaceInputs {
  override suspend fun preview(item: FacesItem): FaceInput = FaceInput.Waiting(InputWait.NO_PREVIEW)

  override suspend fun hq(item: FacesItem): FaceInput = FaceInput.Waiting(InputWait.NO_PREVIEW)

  override suspend fun frames(item: FacesItem): List<FaceInput> = emptyList()

  override suspend fun sharpest(item: FacesItem): FaceInput = FaceInput.Waiting(InputWait.NO_PREVIEW)
}

object NoFacesCatalog : FacesCatalog {
  override suspend fun months(area: FacesArea): List<String> = emptyList()

  override suspend fun items(area: FacesArea, month: String): List<FacesItem> = emptyList()

  override suspend fun cellKeys(media: Collection<MediaId>): Map<MediaId, Set<ContentKey>> = emptyMap()

  override suspend fun mediaWith(keys: Collection<ContentKey>): Map<ContentKey, Set<MediaId>> = emptyMap()
}

/** Nessun NAS: ogni chiamata `NOT_ALLOWED`, niente si legge ne' si scrive. */
class NoFacesRemote(override val share: String = "", override val deviceId: String = "") : FacesRemote {
  private val denied = RemoteResult.Failed(RemoteFailure.NOT_ALLOWED)

  override suspend fun generations(): RemoteResult<FacesListing<FaceGeneration>> = denied

  override suspend fun devices(generation: FaceGeneration): RemoteResult<FacesListing<String>> = denied

  override suspend fun headNumbers(generation: FaceGeneration, deviceId: String): RemoteResult<FacesListing<Long>> = denied

  override suspend fun readHead(generation: FaceGeneration, deviceId: String, version: Long, maxBytes: Int): RemoteResult<FacesRead> = denied

  override suspend fun readSegment(generation: FaceGeneration, deviceId: String, hash: String, maxBytes: Int): RemoteResult<FacesRead> = denied

  override suspend fun publishSegment(generation: FaceGeneration, hash: String, bytes: ByteArray): RemoteResult<FacesPut> = denied

  override suspend fun publishHead(generation: FaceGeneration, version: Long, bytes: ByteArray): RemoteResult<FacesPut> = denied
}

object NoFacesLeases : FacesLeases {
  override suspend fun acquire(generation: FaceGeneration, resource: FacesResource): FacesLease = FacesLease.Blocked(FacesWriteBlock.NO_NAS)

  override suspend fun renew(generation: FaceGeneration, resource: FacesResource, held: FacesLease.Held): FacesLease = FacesLease.Lost

  override suspend fun release(generation: FaceGeneration, resource: FacesResource, held: FacesLease.Held) = Unit
}

/** Nessun NAS: la cancellazione sul NAS aspetta (mai dichiarata finita senza averla fatta). */
object NoFacesEraseOps : FacesEraseOps {
  override suspend fun generations(): List<FaceGeneration>? = null

  override suspend fun eraseGeneration(generation: FaceGeneration, reason: NasEraseReason, budget: Duration): NasEraseRound =
    NasEraseRound.Waiting(EraseWait.NAS)

  override suspend fun eraseDevice(generation: FaceGeneration, deviceId: String, reason: NasEraseReason, budget: Duration): NasEraseRound =
    NasEraseRound.Waiting(EraseWait.NAS)

  override suspend fun verify(generation: FaceGeneration): NasEraseCheck? = null
}

object NoSmartAlbumsPeople : SmartAlbumsPeople {
  override suspend fun albumsWithPeople(): Int = 0

  override suspend fun release(choice: PeopleAlbumsChoice): Int = 0
}

object NoSearchHistoryPurge : SearchHistoryPurge {
  override suspend fun purge(names: Collection<String>): Int = 0
}

object NoFacesRunControl : FacesRunControl {
  override suspend fun permit(): FacesPermit = FacesPermit.Stop(FacesStop.NOT_ACTIVE)
}

/** Nessuna area privata, nessun indicizzatore, recinzione accesa: Persone non parte. */
object NoFacesRequirements : FacesRequirements {
  override suspend fun privateArea(): PrivateAreaState = PrivateAreaState.Missing

  override suspend fun indexers(): IndexerState = IndexerState(0, thisDevice = false, canBecomeIndexer = false)

  override suspend fun localOnly(share: String): Boolean = true
}

/**
 * L'archivio chiuso (nessuna generazione aperta, chiave non disponibile): si legge vuoto, **non si scrive** (`IllegalStateException`: il
 * calcolo si ferma invece di perdere o salvare in chiaro).
 */
class ClosedFacesArchive(override val generation: FaceGeneration) : FacesArchive {
  private fun closed(): Nothing = throw IllegalStateException("Archivio dei volti chiuso.")

  override suspend fun saveBody(body: FaceBody) = closed()

  override suspend fun winners(keys: Collection<ContentKey>): Map<ContentKey, FaceBody> = emptyMap()

  override suspend fun bodiesOf(author: String, keys: Collection<ContentKey>): Map<ContentKey, FaceBody> = emptyMap()

  override suspend fun analyzed(keys: Collection<ContentKey>, recipe: Int): Set<ContentKey> = emptySet()

  override suspend fun markUnsupported(key: ContentKey, recipe: Int) = closed()

  override suspend fun groupFaces(): List<GroupFace> = emptyList()

  override suspend fun unrecognizable(keys: Collection<ContentKey>): Map<ContentKey, Int> = emptyMap()

  override suspend fun grouping(): GroupingState = GroupingState.EMPTY

  override suspend fun saveGrouping(state: GroupingState) = closed()

  override suspend fun people(): Map<PeopleKey, PeopleRecord> = emptyMap()

  override suspend fun addPeople(records: List<PeopleRecord>) = closed()

  override suspend fun datePeople(nasNow: Long): Int = closed()

  override suspend fun dropAuthors(authors: Set<String>): Int = closed()
}
