package dev.pampa.mycloudgallery.core.nasops.space

import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOps
import dev.pampa.mycloudgallery.core.nasops.contract.LargestFile
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpace
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpaceDetails
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpaceState
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrash
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrashState
import dev.pampa.mycloudgallery.core.nasops.contract.OpClocks
import dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode
import dev.pampa.mycloudgallery.core.nasops.contract.OpsStore
import dev.pampa.mycloudgallery.core.nasops.contract.TrashEntryState
import java.util.Locale

/**
 * "Spazio NAS" (§1.5, §2.7; E-N13 quota, E-R15): si calcola **solo quando l'utente apre la pagina** (`space()` tocca i dischi).
 *  - libero e totale dalla share; lo spazio "del chiamante" minore di quello del disco e' una **quota**;
 *  - i file piu' grandi dal catalogo ([largest], nessuna chiamata al NAS);
 *  - il cestino dal diario locale (le proprie voci) e dai record degli altri gia' letti da "Eliminati di recente" ([trash]);
 *  - l'indice dalle teste accettate ([indexBytes]); le versioni conservate "—" fino alla Fase 5 (0).
 * Sulla share `NAS` il cestino del WD e' acceso: lo spazio dei file eliminati per sempre si libera solo quando lo svuota il WD.
 */
class NasSpaceService internal constructor(
  private val fileOps: NasFileOps,
  private val clocks: OpClocks,
  private val store: OpsStore,
  private val largest: suspend (String) -> List<LargestFile> = { emptyList() },
  private val indexBytes: suspend (String) -> Long = { 0L },
  private val trash: NasTrash? = null,
  private val wdTrashShares: Set<String> = setOf("NAS"),
) : NasSpace {

  override suspend fun load(share: String): NasSpaceState {
    val biggest = largest(share).sortedByDescending { it.size }.take(NasSpaceDetails.MAX_LARGEST)
    val space = when (val r = fileOps.space(share, Purpose.INTERACTIVE)) {
      is NasResult.Err -> return NasSpaceState.Unavailable(
        if (r.error == NasError.PermissionDenied) OpErrorCode.PERMISSION_DENIED else OpErrorCode.UNREACHABLE,
        biggest,
      )
      is NasResult.Ok -> r.value
    }
    val key = share.lowercase(Locale.ROOT)
    val own = store.expiredTrash(Long.MAX_VALUE, Int.MAX_VALUE).filter { it.state == TrashEntryState.TRASHED && it.shareKey == key }
    val ownIds = own.mapTo(HashSet()) { it.trashId }
    val others = (trash?.items?.value as? NasTrashState.Ready)?.items.orEmpty()
      .filter { it.share.equals(share, ignoreCase = true) && it.trashId !in ownIds }
    return NasSpaceState.Ready(
      NasSpaceDetails(
        share = share,
        freeBytes = space.freeBytes,
        totalBytes = space.totalBytes,
        quotaBytes = if (space.quotaLimited) space.callerFreeBytes else null,
        largest = biggest,
        trashBytes = own.sumOf { it.size } + others.sumOf { it.size },
        indexBytes = indexBytes(share),
        versionsBytes = 0,
        wdTrashMayHoldSpace = wdTrashShares.any { it.equals(share, ignoreCase = true) },
        measuredAtNas = clocks.nasNowMs(),
      ),
    )
  }
}
