package dev.pampa.mycloudgallery.core.nasmedia.testing

import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.LocationKey
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.domain.LinkDecision
import dev.pampa.mycloudgallery.core.model.domain.Observation
import dev.pampa.mycloudgallery.core.nasmedia.contract.Freeze
import dev.pampa.mycloudgallery.core.nasmedia.contract.FreezeState
import dev.pampa.mycloudgallery.core.nasmedia.contract.KnownNasFile
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasScanCursor
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasScanStatus
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasScanStore
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasScanner
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasSeen
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanReport
import dev.pampa.mycloudgallery.core.nasmedia.contract.UserGesture
import java.util.Locale
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow

// I fake di core-nasmedia (P0): in src/main per i test di P5, P7, P9 e :app. Mai nella release (tools/check.ps1).

/**
 * La porta del catalogo per la scansione, sopra un [MediaCatalog] **vero** (di solito in memoria, con Robolectric:
 * `GalleryDatabase.build(context, name = null)`): le decisioni di collegamento sono quelle vere (`LinkingRules`, E-N1,
 * E-N2, E-N7), mentre cursore, timbri noti e "visto a" stanno qui in memoria finche' P2 non aggiunge lo schema v3. Le
 * assenze passano dal catalogo (`markVanished`), cosi' la Libreria le vede.
 */
class CatalogNasScanStore(private val catalog: MediaCatalog) : NasScanStore {

  private class Entry(val location: Location.Nas, var locationId: LocationId, var stamp: NasStamp, var state: LocationState, var seenAt: Long)

  private val cursors = HashMap<Pair<AccountId, String>, NasScanCursor>()
  private val entries = LinkedHashMap<LocationKey, Entry>()

  /** Quante volte e' stato chiesto di segnare assenze (per provare "congelata → nessuna assenza", D10). */
  @Volatile var vanishCalls: Int = 0
    private set

  private fun shareKey(account: AccountId, share: String) = account to share.lowercase(Locale.ROOT)

  private fun inShare(entry: Entry, account: AccountId, share: String) =
    entry.location.account == account && entry.location.shareKey == share.lowercase(Locale.ROOT)

  override suspend fun cursor(account: AccountId, share: String): NasScanCursor? = synchronized(this) { cursors[shareKey(account, share)] }

  override suspend fun saveCursor(cursor: NasScanCursor) {
    synchronized(this) { cursors[shareKey(cursor.account, cursor.share)] = cursor }
  }

  override suspend fun knownIn(account: AccountId, share: String, folder: NasPath?): Map<LocationKey, KnownNasFile> = synchronized(this) {
    val folderKey = Location.Nas(account, share, NasPath.of(((folder?.value?.let { "$it/" }) ?: "") + "x")).folderKey
    entries.filterValues { inShare(it, account, share) && it.state != LocationState.ABSENT && it.location.folderKey == folderKey }
      .mapValues { (_, e) -> KnownNasFile(e.locationId, e.stamp, e.state) }
  }

  override suspend fun countPresent(account: AccountId, share: String): Int = synchronized(this) {
    entries.values.count { inShare(it, account, share) && it.state == LocationState.PRESENT }
  }

  override suspend fun recordAll(
    observations: List<Observation>,
    whenUndecided: MediaCatalog.WhenUndecided,
    cursor: NasScanCursor?,
    seenAt: Long,
  ): List<MediaCatalog.RecordOutcome> {
    val outcomes = catalog.recordAll(observations, whenUndecided, cursor = null, seenAt = seenAt)
    synchronized(this) {
      observations.zip(outcomes).forEach { (observation, outcome) ->
        val location = observation.location as? Location.Nas ?: return@forEach
        val id = outcome.locationId ?: return@forEach
        // Uno spostamento (E-N1) porta la posizione in un altro posto: la chiave vecchia non vale piu'.
        if (outcome.decision is LinkDecision.Moved) entries.values.removeAll { it.locationId == id }
        entries[location.key] = Entry(location, id, NasStamp(observation.stamp.size, observation.stamp.modifiedAtMillis, location.fileId),
          LocationState.PRESENT, seenAt)
      }
      cursor?.let { cursors[shareKey(it.account, it.share)] = it }
    }
    return outcomes
  }

  override suspend fun confirmAllPresent(seen: List<NasSeen>, seenAt: Long, cursor: NasScanCursor?): List<Boolean> = synchronized(this) {
    val result = seen.map { s ->
      val entry = entries[s.location.key]
      val ok = entry != null && entry.state != LocationState.ABSENT && entry.stamp.sameAs(s.stamp)
      if (ok) {
        entry!!.seenAt = seenAt
        entry.state = LocationState.PRESENT
      }
      ok
    }
    cursor?.let { cursors[shareKey(it.account, it.share)] = it }
    result
  }

  override suspend fun markVanishedInShare(account: AccountId, share: String, epoch: Long, cap: Int): Int {
    require(cap > 0) { "Il tetto e' positivo." }
    val chosen = synchronized(this) {
      vanishCalls++
      entries.values.filter { inShare(it, account, share) && it.state != LocationState.ABSENT && it.seenAt < epoch }
        .sortedBy { it.seenAt }.take(cap)
    }
    for (entry in chosen) {
      catalog.markVanished(entry.location.key)
      synchronized(this) { entry.state = LocationState.ABSENT }
    }
    return chosen.size
  }

  override suspend fun markShareUnavailable(account: AccountId, share: String) {
    synchronized(this) {
      entries.values.filter { inShare(it, account, share) && it.state == LocationState.PRESENT }.forEach { it.state = LocationState.UNAVAILABLE }
    }
  }
}

/** Il congelamento finto: [freeze] lo mette, [release] lo toglie solo con il conteggio giusto (doppia conferma). */
class FakeFreezeState(initial: Freeze? = null) : FreezeState {
  override val frozen: MutableStateFlow<Freeze?> = MutableStateFlow(initial)

  fun freeze(freeze: Freeze?) {
    frozen.value = freeze
  }

  override suspend fun release(gesture: UserGesture): Boolean {
    val current = frozen.value ?: return false
    val count = maxOf(current.diff.vanished, current.diff.changed, current.diff.notMedia)
    if (gesture.confirmedCount != count) return false
    frozen.value = null
    return true
  }
}

/** Uno scanner finto per chi sta a valle (P7, P9, :app): restituisce [nextReport] e aggiorna lo stato. */
class FakeNasScanner(var nextReport: ScanReport? = null) : NasScanner {
  override val status: MutableStateFlow<NasScanStatus> = MutableStateFlow(NasScanStatus.Idle)

  val runs: MutableList<ScanMode> = mutableListOf()

  override suspend fun scan(mode: ScanMode, budget: Duration): ScanReport {
    runs += mode
    val report = nextReport ?: ScanReport(
      mode = mode, folders = 0, files = 0, unchanged = 0, adopted = 0, detected = 0, arriving = 0, skippedNames = 0,
      skippedSystem = 0, albumCovers = 0, notMedia = 0, busy = 0, vanished = 0, bytesRead = 0, complete = true, resumed = false,
    )
    status.value = report.freeze?.let { NasScanStatus.Frozen(it) } ?: NasScanStatus.Finished(report)
    return report
  }
}
