package dev.pampa.mycloudgallery.feature.viewer.menu

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.pampa.mycloudgallery.core.backup.contract.NasVersionPlan
import dev.pampa.mycloudgallery.core.backup.contract.RestoreAvailability
import dev.pampa.mycloudgallery.core.backup.contract.VersionArea
import dev.pampa.mycloudgallery.core.backup.contract.VersionEntry
import dev.pampa.mycloudgallery.core.backup.contract.VersionPlace
import dev.pampa.mycloudgallery.core.backup.contract.VersionRole
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.VersionId
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow

/** I dati e il finto delle voci del backup nel viewer (P12). */
internal object BackupActionsSamples {

  val media = MediaId(UUID(0x5B12, 1).toString())

  fun versionId(n: Int) = VersionId(UUID(0x5B13, n.toLong()).toString())

  fun entry(n: Int, role: VersionRole, current: Boolean, places: Set<VersionPlace>) = VersionEntry(
    versionId = versionId(n),
    role = role,
    isCurrent = current,
    size = 4_000_000L + n,
    savedAtNasMillis = 1_792_067_520_000L + n * 86_400_000L,
    places = places,
    safe = true,
  )

  /** L'originale (in `Versioni/`), una intermedia e quella di adesso (sul telefono e al solito percorso). */
  fun plan(
    restore: RestoreAvailability = RestoreAvailability.AVAILABLE,
    versions: List<VersionEntry> = listOf(
      entry(1, VersionRole.ORIGINAL, current = false, places = setOf(VersionPlace.NAS_VERSIONS_FOLDER)),
      entry(2, VersionRole.EDITED, current = false, places = setOf(VersionPlace.NAS_VERSIONS_FOLDER)),
      entry(3, VersionRole.EDITED, current = true, places = setOf(VersionPlace.PHONE, VersionPlace.NAS_USUAL_PATH)),
    ),
  ) = NasVersionPlan(media, versions, VersionArea.OWN_PRIVATE, restore)

  /** Dopo "Ripristina originale": l'originale e' quella di adesso, la modificata sta in `Versioni/`. */
  fun swapped() = plan(
    RestoreAvailability.ALREADY_ORIGINAL,
    listOf(
      entry(1, VersionRole.ORIGINAL, current = true, places = setOf(VersionPlace.PHONE, VersionPlace.NAS_USUAL_PATH)),
      entry(3, VersionRole.EDITED, current = false, places = setOf(VersionPlace.NAS_VERSIONS_FOLDER)),
    ),
  )

  /** Una sola versione: l'originale e' quella di adesso. */
  fun single(restore: RestoreAvailability = RestoreAvailability.ONLY_ONE_VERSION) =
    plan(restore, listOf(entry(1, VersionRole.ORIGINAL, current = true, places = setOf(VersionPlace.PHONE, VersionPlace.NAS_USUAL_PATH))))

  val everything = BackupMenuCapabilities(correct = true, editWith = true, viewOriginal = true, share = true, phoneOnlyDelete = true)

  val resources get() = ApplicationProvider.getApplicationContext<Application>().resources

  fun labels(entries: List<GalleryMenuEntry>): List<String> = entries.map { if (it is GalleryMenuEntry.Item) it.label else "|" }
}

/** Il backup del viewer finto: cosa risponde lo sceglie il test; ricorda cosa gli e' stato chiesto. */
internal class FakeBackupMediaActions(
  override var capabilities: BackupMenuCapabilities = BackupActionsSamples.everything,
  var plan: NasVersionPlan? = BackupActionsSamples.plan(),
  var everywhereSummary: EverywhereSummary? = null,
  /** VE-4: il formato si scrive (si ruota davvero). */
  var rotatable: Boolean = true,
) : BackupMediaActions {

  val performed = ArrayList<Pair<BackupMenuAction, MediaId>>()
  val shared = ArrayList<List<MediaId>>()
  var shareWithLocationCalls = 0
  var dismissShareCalls = 0
  var hideShareNoticeCalls = 0
  var dismissNoticeCalls = 0
  var planRequests = 0

  override val removeLocation = MutableStateFlow(false)
  override val shareState = MutableStateFlow<BackupShareUi>(BackupShareUi.Idle)
  override val notice = MutableStateFlow<BackupNotice?>(null)

  override suspend fun versionPlan(id: MediaId): NasVersionPlan? {
    planRequests++
    return plan
  }

  override fun perform(action: BackupMenuAction, id: MediaId) {
    performed += action to id
  }

  override fun share(ids: List<MediaId>) {
    shared += ids
  }

  override fun shareWithLocation() {
    shareWithLocationCalls++
  }

  override fun dismissShare() {
    dismissShareCalls++
  }

  override fun hideShareNotice() {
    hideShareNoticeCalls++
  }

  override fun dismissNotice() {
    dismissNoticeCalls++
  }

  override suspend fun everywhere(id: MediaId): EverywhereSummary? = everywhereSummary

  override suspend fun canRotate(id: MediaId): Boolean = rotatable
}
