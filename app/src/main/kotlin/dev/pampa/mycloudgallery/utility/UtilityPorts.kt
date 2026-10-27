package dev.pampa.mycloudgallery.utility

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.library.duplicates.AlbumMembership
import dev.pampa.mycloudgallery.core.backup.contract.PhoneBytes
import dev.pampa.mycloudgallery.core.backup.contract.PhoneLookup
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.library.duplicates.NasTrashRef
import dev.pampa.mycloudgallery.core.library.duplicates.PhoneTrashRef
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationIssuer
import dev.pampa.mycloudgallery.core.nasops.contract.NasItem
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperations
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrash
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrashItem
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrashState
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.OwnTrashRef
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.PlanRejection
import dev.pampa.mycloudgallery.core.nasops.contract.SkipReason
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.TrashRestoreRef
import dev.pampa.mycloudgallery.core.personal.contract.FullHashPersonalSettings
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.library.trash.NasTrashFeed
import dev.pampa.mycloudgallery.feature.library.trash.NasTrashOutcome
import dev.pampa.mycloudgallery.feature.library.trash.NasTrashPort
import dev.pampa.mycloudgallery.feature.library.trash.NasTrashRow
import dev.pampa.mycloudgallery.feature.library.trash.UserTap
import dev.pampa.mycloudgallery.feature.library.utility.CleanupNasPort
import dev.pampa.mycloudgallery.feature.library.utility.CleanupNasResult
import dev.pampa.mycloudgallery.feature.library.utility.FullHashSwitch
import dev.pampa.mycloudgallery.feature.library.utility.PhoneCopyCheck
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// LE PORTE DELL'UTILITY (Fase 7, P13; P8: "IdenticalDuplicates, SimilarShots, NasTrashPort, CleanupNasPort, FullHashSwitch"). feature-library e
// core-library non vedono core-nasops (Phase4ArchitectureTest): quello che tocca il NAS esce dalle due porte qui sotto, che costruiscono il piano di
// `NasOperations` e lo avviano con la conferma **emessa adesso**, dopo il tocco di cui la schermata consegna la prova (`UserTap`). Mai una
// cancellazione definitiva senza i due tocchi di "Elimina subito"; tutto il resto finisce nei cestini.

/** `CleanupNasPort` sopra `NasOperations` (l'adattatore di riferimento di `CleanupOverOperationsTest`, P8): piano di `Trash`, conferma, avvio. */
class OperationsCleanupNas(private val operations: NasOperations, private val monotonicMs: () -> Long) : CleanupNasPort {
  override suspend fun trash(copies: List<NasTrashRef>, tap: UserTap): NasTrashOutcome = trashReporting(copies, tap).outcome

  /**
   * Il piano di `Trash` e' **il punto per cui passa ogni scrittura sul NAS** (R3, pacchetto B): lascia fuori i file di un album condiviso che il livello non mi
   * lascia eliminare (`SkipReason.SHARED_ALBUM_ITEM`), anche da "Ovunque" dei Doppioni simili. Correzioni F: qui si **contano** e si restituiscono, cosi' la
   * pagina dice al tocco quante copie non sono state toccate e perche', anche in una selezione mista.
   */
  override suspend fun trashReporting(copies: List<NasTrashRef>, tap: UserTap): CleanupNasResult {
    if (copies.isEmpty()) return CleanupNasResult(NasTrashOutcome.REJECTED)
    val items = copies.map { copy ->
      NasItem(copy.share, SharePath.parse(copy.path), isFolder = false, size = copy.size, stamp = NasStamp(copy.size, copy.modifiedAt, copy.fileId))
    }
    val plan = when (val outcome = operations.plan(OpRequest.Trash(items))) {
      is PlanOutcome.Ready -> outcome.plan
      // Tutte le copie sono file di un album condiviso (o non c'e' altro da fare): niente parte, e la pagina sa se il motivo e' l'album.
      is PlanOutcome.Rejected -> return CleanupNasResult(
        NasTrashOutcome.REJECTED,
        protectedLeftOut = if (outcome.reason == PlanRejection.SHARED_ALBUM_ITEM) copies else emptyList(),
      )
    }
    val kept = plan.skipped.filter { it.reason == SkipReason.SHARED_ALBUM_ITEM }.mapTo(HashSet()) { keyOf(it.share, it.path.value) }
    val left = copies.filter { keyOf(it.share, it.path) in kept }
    // Un piano senza elementi (tutti esclusi: share in sola lettura, file spariti) non avvia nessuna operazione.
    if (plan.items.isEmpty()) return CleanupNasResult(NasTrashOutcome.REJECTED, protectedLeftOut = left)
    // Il tocco c'e' gia' stato (sul foglio): la conferma si emette adesso, con i tocchi che la prova dice, e il piano ne chiede uno.
    if (tap.taps < plan.requiredConfirmations) return CleanupNasResult(NasTrashOutcome.REJECTED, protectedLeftOut = left)
    val outcome = when (operations.start(plan, ConfirmationIssuer.issue(plan, tap.taps, monotonicMs()))) {
      is StartOutcome.Started, is StartOutcome.AlreadyStarted -> NasTrashOutcome.STARTED
      StartOutcome.WritesBlocked -> NasTrashOutcome.BLOCKED
      else -> NasTrashOutcome.REJECTED
    }
    return CleanupNasResult(outcome, started = if (outcome == NasTrashOutcome.STARTED) plan.items.size else 0, protectedLeftOut = left)
  }

  /** La stessa chiave del piano (`OpPlanner.key`): share e percorso, senza badare alle maiuscole. */
  private fun keyOf(share: String, path: String): String = share.lowercase(Locale.ROOT) + "/" + path.lowercase(Locale.ROOT)
}

/**
 * R18 (revisione di Fase 7): `PhoneCopyCheck` sopra la porta del telefono del backup. Per ogni copia del piano rilegge la **riga del MediaStore** con la
 * versione con cui l'`_ID` valeva e ne confronta il timbro (dimensione, data di modifica, generazione) con quello del piano. Una riga sparita, ricostruita
 * (versione del MediaStore cambiata), nascosta dal permesso o di cui non si sa niente non passa: nel dubbio non va nel cestino.
 */
class MediaStorePhoneCopyCheck(private val phone: PhoneBytes) : PhoneCopyCheck {
  override suspend fun unchanged(copies: List<PhoneTrashRef>): List<PhoneTrashRef> = copies.filter { copy ->
    val now = try {
      phone.lookup(PhoneRef(copy.ref.volume, copy.ref.mediaStoreId, copy.mediaStoreVersion))
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      return@filter false
    }
    now is PhoneLookup.Present && now.stamp.looksUnchangedFrom(copy.stamp)
  }
}

/**
 * `NasTrashPort` sopra `NasTrash` (le voci, solo da mostrare) e `NasOperations` (D26): "Ripristina" con un tocco (`OpRequest.Restore`), "Elimina
 * subito" con due e solo per le voci **proprie** (`OpRequest.PurgeNow`, Q8). Le azioni portano le chiavi scelte dall'utente: le voci si cercano
 * nell'elenco di adesso, e quello che si elimina davvero lo ricava core-nasops dal diario locale (`OwnTrashRef` porta solo l'id).
 */
class OperationsNasTrash(
  private val trash: NasTrash,
  private val operations: NasOperations,
  private val monotonicMs: () -> Long,
  scope: CoroutineScope,
) : NasTrashPort {
  override val feed: StateFlow<NasTrashFeed> = trash.items.map(::feedOf).stateIn(scope, SharingStarted.Eagerly, feedOf(trash.items.value))

  override suspend fun refresh() {
    trash.refresh()
  }

  override suspend fun restore(keys: Set<String>, tap: UserTap): NasTrashOutcome {
    val chosen = current().filter { it.key() in keys && it.restorable }
    val refs = chosen.mapNotNull { item ->
      val path = item.originalPath ?: return@mapNotNull null
      runCatching { TrashRestoreRef(item.share, item.ownerDeviceId, item.trashId, path, item.isFolder, item.size.coerceAtLeast(0)) }.getOrNull()
    }
    if (refs.isEmpty()) return NasTrashOutcome.REJECTED
    return run(OpRequest.Restore(refs), tap)
  }

  override suspend fun purgeNow(keys: Set<String>, tap: UserTap): NasTrashOutcome {
    // Q8: due tocchi, e solo le voci proprie (quelle degli altri non sono mai `deletableNow`).
    if (tap.taps < PURGE_TAPS) return NasTrashOutcome.REJECTED
    val own = current().filter { it.key() in keys && it.deletableNow }.mapNotNull { runCatching { OwnTrashRef(it.trashId) }.getOrNull() }
    if (own.isEmpty()) return NasTrashOutcome.REJECTED
    return run(OpRequest.PurgeNow(own), tap)
  }

  private suspend fun run(request: OpRequest, tap: UserTap): NasTrashOutcome {
    val plan = when (val outcome = operations.plan(request)) {
      is PlanOutcome.Ready -> outcome.plan
      is PlanOutcome.Rejected -> return NasTrashOutcome.REJECTED
    }
    if (plan.items.isEmpty() || tap.taps < plan.requiredConfirmations) return NasTrashOutcome.REJECTED
    return when (operations.start(plan, ConfirmationIssuer.issue(plan, tap.taps, monotonicMs()))) {
      is StartOutcome.Started, is StartOutcome.AlreadyStarted -> NasTrashOutcome.STARTED
      StartOutcome.WritesBlocked -> NasTrashOutcome.BLOCKED
      else -> NasTrashOutcome.REJECTED
    }
  }

  private fun current(): List<NasTrashItem> = when (val state = trash.items.value) {
    is NasTrashState.Ready -> state.items
    is NasTrashState.Offline -> state.cachedOwn
    else -> emptyList()
  }

  private fun feedOf(state: NasTrashState): NasTrashFeed = when (state) {
    NasTrashState.Loading -> NasTrashFeed.Loading
    is NasTrashState.Ready -> NasTrashFeed.Ready(state.items.map(::rowOf), state.writable)
    is NasTrashState.Offline -> NasTrashFeed.Offline(state.cachedOwn.map(::rowOf))
    NasTrashState.Denied -> NasTrashFeed.Denied
    is NasTrashState.Error -> NasTrashFeed.Failed
  }

  private fun rowOf(item: NasTrashItem): NasTrashRow = NasTrashRow(
    key = item.key(),
    name = item.name,
    folder = item.originalFolder?.value,
    sizeBytes = item.size.coerceAtLeast(0),
    daysLeft = item.daysLeft,
    isFolder = item.isFolder,
    isVideo = !item.isFolder && isVideoName(item.name),
    fileCount = item.fileCount,
    author = item.author,
    withoutValidRecord = item.withoutValidRecord,
    restorable = item.restorable,
    deletableNow = item.deletableNow,
  )

  private fun NasTrashItem.key(): String = "$ownerDeviceId/$trashId"

  internal companion object {
    /** "Elimina subito" vuole la doppia conferma (Q8, R6 della 4B). */
    const val PURGE_TAPS: Int = 2

    private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "3gp", "3g2", "mkv", "webm", "avi", "mts", "m2ts")

    fun isVideoName(name: String?): Boolean = name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) in VIDEO_EXTENSIONS
  }
}

/**
 * L'interruttore "Controlla di notte i video del NAS" (D27): l'impostazione personale sincronizzata (`full_hash_nightly`, spenta se assente) e, a
 * ogni cambio fatto qui, il giro che la applica subito ([apply]: `FullHashNightly.apply`), senza aspettare la notte. Prima che i dati personali ci
 * siano (motore del NAS non agganciato) resta spento e [set] non salva: l'interruttore mostra sempre cio' che la porta dice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PersonalFullHashSwitch(
  private val settings: StateFlow<FullHashPersonalSettings?>,
  private val scope: CoroutineScope,
  private val apply: suspend (Boolean) -> Unit,
) : FullHashSwitch {
  override val enabled: StateFlow<Boolean> = settings.flatMapLatest { s -> s?.fullHashNightly ?: flowOf(false) }
    .stateIn(scope, SharingStarted.Eagerly, settings.value?.fullHashNightly?.value ?: false)

  override suspend fun set(on: Boolean) {
    val current = settings.value ?: throw IllegalStateException("Dati personali non pronti.")
    current.setFullHashNightly(on)
    scope.launch {
      try {
        apply(on)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.w(UtilityGraph.TAG) { "coda dell'hash completo: ${e.javaClass.simpleName}" }
      }
    }
  }
}

/**
 * I media che stanno in un album (E-U3: uno scatto in un album e' protetto nella pulizia dei simili): le righe `album_item` (album personali e
 * smart, gia' proiettati) e gli elementi attestati degli album condivisi ([shared], dalla replica). Una lettura a blocchi da [CHUNK].
 */
class AppAlbumMembership(
  private val database: GalleryDatabase,
  private val shared: () -> Set<MediaId>,
) : AlbumMembership {
  override suspend fun inAlbums(media: Collection<MediaId>): Set<MediaId> {
    if (media.isEmpty()) return emptySet()
    val out = HashSet<MediaId>()
    val inShared = shared()
    media.filterTo(out) { it in inShared }
    withContext(Dispatchers.IO) {
      for (chunk in media.map { it.value }.distinct().chunked(CHUNK)) {
        val marks = chunk.joinToString(",") { "?" }
        database.openHelper.readableDatabase.query("SELECT DISTINCT media_id FROM album_item WHERE media_id IN ($marks)", chunk.toTypedArray()).use { c ->
          while (c.moveToNext()) out += MediaId(c.getString(0))
        }
      }
    }
    return out
  }

  private companion object {
    const val CHUNK = 500
  }
}
