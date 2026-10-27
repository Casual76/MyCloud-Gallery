package dev.pampa.mycloudgallery.feature.library.utility.testing

import dev.pampa.mycloudgallery.core.library.duplicates.CleanupPlan
import dev.pampa.mycloudgallery.core.library.duplicates.CopyArea
import dev.pampa.mycloudgallery.core.library.duplicates.CopyPart
import dev.pampa.mycloudgallery.core.library.duplicates.GroupProposal
import dev.pampa.mycloudgallery.core.library.duplicates.IdenticalCleanup
import dev.pampa.mycloudgallery.core.library.duplicates.IdenticalDuplicates
import dev.pampa.mycloudgallery.core.library.duplicates.IdenticalGroup
import dev.pampa.mycloudgallery.core.library.duplicates.IdenticalScan
import dev.pampa.mycloudgallery.core.library.duplicates.IdenticalStatus
import dev.pampa.mycloudgallery.core.library.duplicates.IdentCopy
import dev.pampa.mycloudgallery.core.library.duplicates.NasTrashRef
import dev.pampa.mycloudgallery.core.library.duplicates.PhoneTrashRef
import dev.pampa.mycloudgallery.core.library.duplicates.RemovalPlaces
import dev.pampa.mycloudgallery.core.library.duplicates.Shot
import dev.pampa.mycloudgallery.core.library.duplicates.ShotDetails
import dev.pampa.mycloudgallery.core.library.duplicates.SigningReport
import dev.pampa.mycloudgallery.core.library.duplicates.SimilarBatch
import dev.pampa.mycloudgallery.core.library.duplicates.SimilarCluster
import dev.pampa.mycloudgallery.core.library.duplicates.SimilarShots
import dev.pampa.mycloudgallery.core.library.duplicates.TwinReport
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.feature.library.trash.NasTrashOutcome
import dev.pampa.mycloudgallery.feature.library.trash.UserTap
import dev.pampa.mycloudgallery.feature.library.utility.CleanupNasPort
import dev.pampa.mycloudgallery.feature.library.utility.FullHashSwitch
import dev.pampa.mycloudgallery.feature.library.utility.PhoneCopyCheck
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// I fake dei Doppioni (Fase 7, P8, D25, D27): in src/main, cosi' i test di questo modulo, gli screenshot e i test d'integrazione di `:app` (P13) li
// riusano. Mai nella release: tools/check.ps1 fallisce se una classe di un package `testing` compare nel dex dell'APK di release.

/** Un media di prova: l'identificativo numero [n]. */
fun sampleMediaId(n: Int): MediaId = MediaId("00000000-0000-4000-8000-%012d".format(n))

/** Il riferimento al telefono di un media di prova (la miniatura sintetica nasce dal numero), o null per un media che non e' di prova. */
fun sampleRefOf(media: MediaId): MediaRef? =
  media.value.takeLast(12).toLongOrNull()?.let { MediaRef.Phone("external_primary", it, "image/jpeg") }

private val SAMPLE_HASH = Sha256("b".repeat(64))

/** La versione del MediaStore delle copie del telefono di prova. */
const val SAMPLE_STORE: String = "store-v1"

private fun copy(
  id: Long,
  area: CopyArea,
  path: String,
  share: String? = if (area == CopyArea.PHONE) null else "alessio",
  size: Long = 3_400_000,
  modifiedAt: Long = 1_800_000_000_000L + id,
  verifiedAt: Long? = null,
  album: Boolean = false,
): IdentCopy = IdentCopy(
  LocationId(id), area, share, path, size, modifiedAt, verifiedAt,
  backupFolder = if (area == CopyArea.MY_PRIVATE) IdenticalCleanup.backupFolderOf(path) else null,
  inSharedAlbum = album,
)

private fun group(media: Int, part: CopyPart, fullHash: Sha256?, copies: List<IdentCopy>, safe: Boolean = true, video: Boolean = false) =
  IdenticalGroup(sampleMediaId(media), VersionId("00000000-0000-4000-8000-0000000000a$media"), part, fullHash, copies, safe, video)

/**
 * Qualche gruppo di doppioni identici, scritto a mano e passato dalle regole vere (`IdenticalCleanup.propose`):
 *  1. due copie sul telefono (DCIM e Download): una da togliere;
 *  2. tre copie sul NAS piu' una nell'area di un altro familiare: la privata verificata resta, due da togliere, l'altra area non si tocca (E-U1);
 *  3. un video del NAS senza hash completo: da verificare (D27);
 *  4. una copia privata e una in un album condiviso: niente da togliere (mai la copia di un album, mai l'ultima privata).
 */
fun sampleIdenticalProposals(): List<GroupProposal> = listOf(
  group(
    1, CopyPart.PHONE, SAMPLE_HASH,
    listOf(
      copy(10, CopyArea.PHONE, "DCIM/Camera/IMG_0001.jpg", modifiedAt = 1_000),
      copy(11, CopyArea.PHONE, "Download/IMG_0001 (1).jpg", modifiedAt = 2_000),
    ),
    safe = false,
  ),
  group(
    2, CopyPart.NAS, SAMPLE_HASH,
    listOf(
      copy(20, CopyArea.MY_PRIVATE, "Foto/Oslo/IMG_0002.jpg", verifiedAt = 5_000),
      copy(21, CopyArea.MY_PRIVATE, "Backup/Pixel 9/2026/10/IMG_0002.jpg"),
      copy(22, CopyArea.COMMON, "Famiglia/Oslo/IMG_0002.jpg", share = "famiglia"),
      copy(23, CopyArea.OTHERS, "Marco/Oslo/IMG_0002.jpg", share = "marco"),
    ),
  ),
  group(
    3, CopyPart.NAS, null,
    listOf(
      copy(30, CopyArea.MY_PRIVATE, "Video/Estate/VID_0003.mp4", size = 840_000_000),
      copy(31, CopyArea.MY_PRIVATE, "Backup/Pixel 9/2026/08/VID_0003.mp4", size = 840_000_000),
    ),
    video = true,
  ),
  group(
    4, CopyPart.NAS, SAMPLE_HASH,
    listOf(
      copy(40, CopyArea.MY_PRIVATE, "Foto/Cena/IMG_0004.jpg", verifiedAt = 7_000),
      copy(41, CopyArea.COMMON, "Album condivisi/Cena/IMG_0004.jpg", share = "famiglia", album = true),
    ),
  ),
).map { IdenticalCleanup.propose(it) }

/** Il piano che il catalogo vero farebbe per [proposals]: le copie da togliere, i file del telefono con la riga di prova, quelli del NAS con il timbro. */
fun samplePlanOf(proposals: Collection<GroupProposal>): CleanupPlan {
  val phone = ArrayList<PhoneTrashRef>()
  val nas = ArrayList<NasTrashRef>()
  var unsafe = 0
  var groups = 0
  for (p in proposals.filter { it.status == IdenticalStatus.CLEANABLE }) {
    groups++
    for (c in p.remove) {
      if (c.area == CopyArea.PHONE) {
        phone += PhoneTrashRef(MediaRef.Phone("external_primary", c.location.value, "image/jpeg"), FileStamp(c.size, c.modifiedAt), SAMPLE_STORE, c.location)
        if (!p.group.safe) unsafe++
      } else {
        nas += NasTrashRef(c.share ?: "alessio", c.path, c.size, c.modifiedAt, fileId = c.location.value, area = c.area, location = c.location)
      }
    }
  }
  return CleanupPlan(groups, phone, nas, unsafe, skipped = 0)
}

/** I doppioni identici finti: restituiscono quello che il test dice, contano le letture e ricordano cosa e' stato chiesto di portare nel piano. */
class FakeIdenticalDuplicates(
  var proposals: List<GroupProposal> = sampleIdenticalProposals(),
  var failed: Boolean = false,
) : IdenticalDuplicates {

  var scans: Int = 0
    private set

  /** Se vero la prossima lettura lancia (un errore del catalogo). */
  var throwOnScan: Boolean = false

  /** I gruppi con cui e' stato chiesto un piano, nell'ordine. */
  val planned: MutableList<List<GroupProposal>> = mutableListOf()

  /** Se non nullo, il piano risponde questo invece di quello calcolato dalle proposte. */
  var planOverride: CleanupPlan? = null

  /** Quante volte e' stato chiesto il giro dei gemelli del NAS (DP-1), e con che tetto di tempo. */
  val settles: MutableList<Duration> = mutableListOf()

  /** I cambiamenti del catalogo (DP-3): un test li fa arrivare con `changed.tryEmit(Unit)`. */
  val changed: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 8)

  /** Insiemi di file del NAS in verifica (DP-1) e Libreria non leggibile (UT-1) che la prossima lettura riporta. */
  var verifying: Int = 0
  var restricted: Boolean = false

  override suspend fun scan(): IdenticalScan {
    scans++
    check(!throwOnScan) { "lettura fallita" }
    return IdenticalScan(proposals, failed, verifying, restricted)
  }

  override suspend fun settle(budget: Duration): TwinReport {
    settles += budget
    return TwinReport.NONE
  }

  override fun changes(): Flow<Unit> = changed

  override suspend fun plan(proposals: Collection<GroupProposal>): CleanupPlan {
    planned += proposals.toList()
    return planOverride ?: samplePlanOf(proposals)
  }
}

private val SHOT_DAY = 20_261_012
private const val SHOT_T0 = 1_800_000_000_000L

private fun shot(n: Int, minutes: Int) = Shot(sampleMediaId(n), SHOT_T0 + minutes * 60_000L, SHOT_DAY, aspectMilli = 1333)

/**
 * Due gruppi di scatti simili: una raffica a mano di quattro (la migliore e' la seconda; una e' preferita, una e' in un album: mai preselezionate per
 * l'eliminazione, E-U3) e una coppia (la prima e' la migliore).
 */
fun sampleClusters(): List<SimilarCluster> = listOf(
  SimilarCluster(
    shots = listOf(shot(101, 0), shot(102, 1), shot(103, 2), shot(104, 3)),
    details = mapOf(
      sampleMediaId(101) to ShotDetails(pixels = 12_000_000, bytes = 3_000_000),
      sampleMediaId(102) to ShotDetails(pixels = 12_000_000, bytes = 4_800_000),
      sampleMediaId(103) to ShotDetails(favorite = true, pixels = 12_000_000, bytes = 3_100_000),
      sampleMediaId(104) to ShotDetails(inAlbum = true, pixels = 12_000_000, bytes = 2_900_000),
    ),
    best = sampleMediaId(102),
  ),
  SimilarCluster(
    shots = listOf(shot(201, 0), shot(202, 4)),
    details = mapOf(
      sampleMediaId(201) to ShotDetails(pixels = 12_000_000, bytes = 4_100_000),
      sampleMediaId(202) to ShotDetails(pixels = 12_000_000, bytes = 3_300_000),
    ),
    best = sampleMediaId(201),
  ),
)

/** I luoghi di prova di un'eliminazione: ogni media ha una copia sul telefono e una nell'area privata (e uno anche nella comune). */
fun samplePlacesOf(media: Collection<MediaId>, withNas: Boolean = true, unsafePhoneMedia: Int = 0): RemovalPlaces {
  val numbers = media.map { it.value.takeLast(12).toLong() }
  return RemovalPlaces(
    media = media.size,
    phone = numbers.map { PhoneTrashRef(MediaRef.Phone("external_primary", it, "image/jpeg"), FileStamp(3_000_000, 1_800_000_000_000L + it), SAMPLE_STORE, LocationId(it)) },
    nasPrivate = if (withNas) numbers.map { NasTrashRef("alessio", "Foto/IMG_$it.jpg", 3_000_000, 1_800_000_000_000L + it, it, CopyArea.MY_PRIVATE) } else emptyList(),
    nasCommon = if (withNas) numbers.take(1).map { NasTrashRef("famiglia", "Famiglia/IMG_$it.jpg", 3_000_000, 1_800_000_000_000L + it, it, CopyArea.COMMON) } else emptyList(),
    unsafePhoneMedia = unsafePhoneMedia,
  )
}

/** I doppioni simili finti: i lotti dell'elenco, uno per chiamata, poi niente. */
class FakeSimilarShots(
  var batches: List<SimilarBatch> = listOf(SimilarBatch(sampleClusters(), nextBeforeDay = null, undecided = 0)),
  private val places: (Collection<MediaId>) -> RemovalPlaces = { samplePlacesOf(it) },
) : SimilarShots {

  var scans: Int = 0
    private set

  /** Il lotto (da zero, dentro una ricerca) che fallisce; -1 = mai. */
  var failAt: Int = -1

  /** I `beforeDay` con cui e' stata chiamata. */
  val calls: MutableList<Int?> = mutableListOf()

  /** I media di cui e' stato chiesto dove stanno. */
  val placesAsked: MutableList<List<MediaId>> = mutableListOf()

  /** Il lotto numero [n] di una ricerca (dal primo, `beforeDay` nullo, si riparte da zero). */
  private var sequence = 0

  override suspend fun scan(beforeDay: Int?, budget: Duration): SimilarBatch {
    calls += beforeDay
    order += "scan"
    if (beforeDay == null) sequence = 0
    scans++
    val index = sequence++
    if (index == failAt) return SimilarBatch(emptyList(), null, 0, failed = true)
    return batches.getOrNull(index) ?: SimilarBatch.EMPTY
  }

  /** Cosa risponde la firma degli scatti recenti (DP-4), e con che tetto di tempo e' stata chiesta. */
  var signing: SigningReport = SigningReport(0, 0, 0, finished = true)
  val signCalls: MutableList<Duration> = mutableListOf()

  /** L'ordine delle chiamate (`sign` prima di `scan`, DP-4): una parola per chiamata. */
  val order: MutableList<String> = mutableListOf()

  override suspend fun sign(budget: Duration): SigningReport {
    signCalls += budget
    order += "sign"
    return signing
  }

  override suspend fun placesOf(media: Collection<MediaId>): RemovalPlaces {
    placesAsked += media.toList()
    return places(media)
  }
}

/** La porta di pulizia del NAS finta: ricorda cosa le e' stato chiesto e con quanti tocchi, e risponde [outcome]. */
class FakeCleanupNas(var outcome: NasTrashOutcome = NasTrashOutcome.STARTED) : CleanupNasPort {
  val calls: MutableList<Pair<List<NasTrashRef>, Int>> = mutableListOf()

  override suspend fun trash(copies: List<NasTrashRef>, tap: UserTap): NasTrashOutcome {
    calls += copies to tap.taps
    return outcome
  }
}

/** L'interruttore dell'hash completo notturno finto: tiene il valore come farebbe l'impostazione sincronizzata. */
class FakeFullHashSwitch(initial: Boolean = false) : FullHashSwitch {
  private val state = MutableStateFlow(initial)
  override val enabled: StateFlow<Boolean> = state

  /** Le scelte arrivate, nell'ordine. */
  val sets: MutableList<Boolean> = mutableListOf()

  override suspend fun set(on: Boolean) {
    sets += on
    state.value = on
  }
}

/**
 * Il controllo delle copie del telefono finto (R18): di serie il telefono dice che ogni copia e' ancora quella del piano. [changed] sono le copie che
 * la riga del MediaStore non conferma piu' (sovrascritte, spostate): non passano. Ricorda cosa gli e' stato chiesto.
 */
class FakePhoneCopyCheck(var changed: Set<Long> = emptySet(), var throwing: Boolean = false) : PhoneCopyCheck {
  val asked: MutableList<List<PhoneTrashRef>> = mutableListOf()

  override suspend fun unchanged(copies: List<PhoneTrashRef>): List<PhoneTrashRef> {
    asked += copies
    check(!throwing) { "MediaStore non letto" }
    return copies.filter { it.ref.mediaStoreId !in changed }
  }
}
