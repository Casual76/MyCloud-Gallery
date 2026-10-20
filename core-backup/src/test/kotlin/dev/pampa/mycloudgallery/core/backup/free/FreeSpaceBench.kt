package dev.pampa.mycloudgallery.core.backup.free

import dev.pampa.mycloudgallery.core.backup.contract.BackupFence
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceFilter
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpacePlan
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRun
import dev.pampa.mycloudgallery.core.backup.contract.GuardReport
import dev.pampa.mycloudgallery.core.backup.contract.GuardScope
import dev.pampa.mycloudgallery.core.backup.contract.NasVersionPlan
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.PrivateCopyGuardian
import dev.pampa.mycloudgallery.core.backup.contract.RestoreAvailability
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.TrashBatch
import dev.pampa.mycloudgallery.core.backup.contract.TrashOutcome
import dev.pampa.mycloudgallery.core.backup.contract.VersionArea
import dev.pampa.mycloudgallery.core.backup.contract.VersionEntry
import dev.pampa.mycloudgallery.core.backup.contract.VersionPlace
import dev.pampa.mycloudgallery.core.backup.contract.VersionRole
import dev.pampa.mycloudgallery.core.backup.free.testing.InMemoryFreeSpaceCatalog
import dev.pampa.mycloudgallery.core.backup.free.testing.InMemoryFreeSpaceDiary
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupGate
import dev.pampa.mycloudgallery.core.backup.testing.FakePhoneBytes
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryBackupStore
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryVersionKeeper
import dev.pampa.mycloudgallery.core.backup.testing.TestConfirmations
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.Media
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaLocation
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.MediaVersion
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.NasAreaLookup
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.NasShare
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.time.LocalDate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Il banco di "Libera spazio" (P9): NAS finto con l'area privata, quella comune e quella di un familiare, telefono finto, catalogo con il
 * grafo vero dei media (piu' versioni), diario, diario del backup, custode delle versioni di P8 (fake di P0-B), guardiano finto, cancello,
 * orologi e conteggio dei riavvii. [restart] e' la morte del processo: il motore si ricrea, tutto il resto (NAS, telefono, catalogo, diari)
 * resta. [dialog] e' il dialogo di sistema simulato: toglie dal telefono le righe confermate (come Android), e basta.
 */
internal class FreeSpaceBench {

  val clock = FakeBackupClock()
  val nas = InMemoryNasMedia(nasNow = { clock.nasNow ?: FakeBackupClock.DEFAULT_NAS_NOW }).apply {
    addShare(PRIVATE)
    addShare(COMMON)
    addShare(OTHER_PRIVATE)
  }
  val phone = FakePhoneBytes()
  val catalog = InMemoryFreeSpaceCatalog()
  val diary = InMemoryFreeSpaceDiary()
  val store = InMemoryBackupStore()
  val versions = InMemoryVersionKeeper(clock)
  val guardian = BenchGuardian(this)
  val gate = FakeBackupGate()
  val me = AccountId(PRIVATE)

  var bootCount: Long? = 7
  var fence: BackupFence? = null
  var today: LocalDate? = LocalDate.of(2026, 10, 19)
  var systemDialogs: Boolean = true
  var areas: NasAreaLookup = NasAreaLookup.of(
    listOf(NasShare(me, PRIVATE, NasArea.PRIVATE), NasShare(me, COMMON, NasArea.COMMON)),
  )

  /**
   * LS-11: come `:app` raggiunge il NAS quando l'ora del NAS non c'e' ancora (una sessione la mette). Di serie niente: l'ora resta com'e'.
   */
  var reachNas: suspend () -> Unit = {}

  fun engine(
    diary: FreeSpaceDiary = this.diary,
    catalog: FreeSpaceCatalog = this.catalog,
    reader: NasMediaReader = nas.reader,
    guardian: PrivateCopyGuardian = this.guardian,
  ): SafeFreeSpace =
    SafeFreeSpace(
      catalog = catalog,
      diary = diary,
      backupStore = store,
      versions = versions,
      guardian = guardian,
      reader = reader,
      phone = phone.port,
      gate = gate,
      clock = clock,
      boot = BootCounter { bootCount },
      account = { me },
      privateShare = { PRIVATE },
      areas = { areas },
      fence = fence,
      systemDialogs = { systemDialogs },
      reachNas = { reachNas() },
      today = { today },
      pageSize = 7,
    )

  /** Il motore del "processo" attuale. */
  var space: SafeFreeSpace = engine()
    private set

  /** La morte del processo: si ricrea solo il motore. */
  fun restart() {
    space = engine()
  }

  // --- I media di partenza -------------------------------------------------------------------------------------------------------

  private var nextLocation = 1_000L
  private var nextPhone = 100L
  private var nextSort = 5_000_000L
  private var nextName = 1

  /** Un media costruito dal banco: id, versioni (la prima e' l'originale, l'ultima l'attuale), byte per versione, riga del telefono. */
  class Made(
    val id: MediaId,
    val versions: List<VersionId>,
    val data: Map<VersionId, ByteArray>,
    val phoneId: Long?,
    val phoneRef: PhoneRef?,
    val phoneLocation: LocationId?,
    /** Il percorso della copia privata di ogni versione (se c'e'). */
    val privatePaths: Map<VersionId, String>,
    val commonPaths: Map<VersionId, String>,
    val name: String,
  ) {
    val current: VersionId get() = versions.last()
    val original: VersionId get() = versions.first()
  }

  fun bytes(size: Int, seed: Int): ByteArray = ByteArray(size) { (it * 131 + seed * 7 + (it shr 8)).toByte() }

  /**
   * Un media al sicuro (o quasi, secondo i parametri): una versione, la copia del telefono in [folder], la copia privata in
   * `Backup/S25/AAAA/MM/` verificata ([verified]) e, se chiesta, una copia nell'area comune.
   */
  fun safe(
    size: Int = 4_096,
    localDay: Int? = 20_240_101,
    kind: MediaKind = MediaKind.PHOTO,
    folder: String = "DCIM/Camera/",
    verified: Boolean = true,
    privateCopy: Boolean = true,
    commonCopy: Boolean = false,
    onPhone: Boolean = true,
    owner: AccountId? = me,
    hashKnown: Boolean = true,
  ): Made {
    val n = nextName++
    val name = (if (kind.isVideo) "VID_" else "IMG_") + "%04d".format(n) + (if (kind.isVideo) ".mp4" else ".jpg")
    val data = bytes(size, n)
    val v = VersionId.random()
    val version = MediaVersion(v, VersionKind.ORIGINAL, null, Fingerprint(data.size.toLong(), QuickHashAlgorithm.ofContent(data), if (hashKnown) Sha256.of(data) else null), 0)
    val locations = ArrayList<MediaLocation>()
    var phoneId: Long? = null
    var ref: PhoneRef? = null
    var phoneLocation: LocationId? = null
    if (onPhone) {
      phoneId = nextPhone++
      ref = phone.add(phoneId, NasContent.of(data))
      phoneLocation = LocationId(nextLocation++)
      locations += phoneRow(phoneLocation, v, folder, name, phoneId)
    }
    val privatePaths = HashMap<VersionId, String>()
    val commonPaths = HashMap<VersionId, String>()
    if (privateCopy) {
      val path = "Backup/S25/2024/01/$name"
      locations += nasCopy(v, PRIVATE, path, data, verifiedAt = if (verified) clock.nasNow else null)
      privatePaths[v] = path
    }
    if (commonCopy) {
      val path = "Foto condivise/$name"
      locations += nasCopy(v, COMMON, path, data, verifiedAt = null)
      commonPaths[v] = path
    }
    val media = Media(MediaId.random(), kind, MediaSource.CAMERA, owner, 0, v, listOf(version), locations)
    catalog.put(FreeSpaceMedia(media, localDay, nextSort--, mime = if (kind.isVideo) "video/mp4" else "image/jpeg"))
    return Made(media.id, listOf(v), mapOf(v to data), phoneId, ref, phoneLocation, privatePaths, commonPaths, name)
  }

  /**
   * Un media con due versioni (E-E1): l'originale archiviato in `Versioni/` e l'attuale (modificata, sul telefono) al solito percorso. Le
   * due copie private verificate secondo [originalVerified] e [currentVerified]; il custode delle versioni (P8) dice [p8Safe].
   */
  fun edited(
    localDay: Int? = 20_240_101,
    originalVerified: Boolean = true,
    currentVerified: Boolean = true,
    originalCopy: Boolean = true,
    p8Safe: Boolean = true,
    folder: String = "DCIM/Camera/",
  ): Made {
    val n = nextName++
    val name = "IMG_%04d.jpg".format(n)
    val dataO = bytes(3_000, n)
    val dataE = bytes(3_500, n + 50_000)
    val o = VersionId.random()
    val e = VersionId.random()
    val vo = MediaVersion(o, VersionKind.ORIGINAL, null, Fingerprint(dataO.size.toLong(), QuickHashAlgorithm.ofContent(dataO), Sha256.of(dataO)), 0)
    val ve = MediaVersion(e, VersionKind.EDITED, o, Fingerprint(dataE.size.toLong(), QuickHashAlgorithm.ofContent(dataE), Sha256.of(dataE)), 10)
    val phoneId = nextPhone++
    val ref = phone.add(phoneId, NasContent.of(dataE))
    val phoneLoc = LocationId(nextLocation++)
    val locations = ArrayList<MediaLocation>()
    locations += phoneRow(phoneLoc, e, folder, name, phoneId)
    val privatePaths = HashMap<VersionId, String>()
    val usual = "Backup/S25/2024/01/$name"
    locations += nasCopy(e, PRIVATE, usual, dataE, verifiedAt = if (currentVerified) clock.nasNow else null)
    privatePaths[e] = usual
    if (originalCopy) {
      val archived = "Versioni/Backup/S25/2024/01/IMG_%04d (originale).jpg".format(n)
      locations += nasCopy(o, PRIVATE, archived, dataO, verifiedAt = if (originalVerified) clock.nasNow else null)
      privatePaths[o] = archived
    }
    val media = Media(MediaId.random(), MediaKind.PHOTO, MediaSource.CAMERA, me, 0, e, listOf(vo, ve), locations)
    catalog.put(FreeSpaceMedia(media, localDay, nextSort--, mime = "image/jpeg"))
    versions.setPlan(
      NasVersionPlan(
        media.id,
        listOf(
          VersionEntry(o, VersionRole.ORIGINAL, false, dataO.size.toLong(), null, setOf(VersionPlace.NAS_VERSIONS_FOLDER), safe = p8Safe && originalCopy && originalVerified),
          VersionEntry(e, VersionRole.EDITED, true, dataE.size.toLong(), null, setOf(VersionPlace.PHONE, VersionPlace.NAS_USUAL_PATH), safe = p8Safe && currentVerified),
        ),
        VersionArea.OWN_PRIVATE,
        RestoreAvailability.AVAILABLE,
      ),
    )
    return Made(media.id, listOf(o, e), mapOf(o to dataO, e to dataE), phoneId, ref, phoneLoc, privatePaths, emptyMap(), name)
  }

  private fun phoneRow(id: LocationId, v: VersionId, folder: String, name: String, phoneId: Long): MediaLocation = MediaLocation(
    id = id,
    versionId = v,
    location = Location.Phone(FakePhoneBytes.VOLUME, folder, name, phoneId, phone.version),
    stamp = phone.stamp(phoneId)!!,
    state = LocationState.PRESENT,
    lastSeenAtMillis = 0,
  )

  /** Un file sul NAS messo "da fuori" (il backup di ieri, il PC) e la sua posizione nel catalogo. */
  fun nasCopy(v: VersionId, share: String, path: String, data: ByteArray, verifiedAt: Long?): MediaLocation {
    val modifiedAt = (clock.nasNow ?: FakeBackupClock.DEFAULT_NAS_NOW) - 86_400_000L
    val fileId = nas.putFile(share, path, NasContent.of(data), modifiedAt = modifiedAt)
    return MediaLocation(
      id = LocationId(nextLocation++),
      versionId = v,
      location = Location.Nas(me, share, NasPath.of(path), fileId),
      stamp = FileStamp(data.size.toLong(), modifiedAt),
      state = LocationState.PRESENT,
      verifiedAtMillis = verifiedAt,
      lastSeenAtMillis = 0,
    )
  }

  /** Aggiunge una posizione al grafo di un media (il guardiano, una scansione). */
  fun addLocation(id: MediaId, location: MediaLocation) = catalog.change(id) { it.copy(locations = it.locations + location) }

  fun newLocationId(): LocationId = LocationId(nextLocation++)

  /** Il PC cambia un byte nel mezzo della copia (stesso nome, stessa dimensione, data nuova). */
  fun corruptOnNas(share: String, path: String) {
    val bytes = nas.bytes(share, path)!!
    bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
    clock.advance(1_000)
    nas.modify(share, path, NasContent.of(bytes))
  }

  // --- Il flusso ---------------------------------------------------------------------------------------------------------------

  /** Il piano confermato con tutti i tocchi richiesti, adesso. */
  suspend fun confirmed(filter: FreeSpaceFilter = FreeSpaceFilter(), permanent: Boolean = false): Pair<FreeSpacePlan, dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmation> {
    val plan = space.plan(filter, permanent)
    return plan to TestConfirmations.confirm(plan, clock.monotonicMs())
  }

  /** Piano, conferma e verifica in una tappa sola. */
  suspend fun start(filter: FreeSpaceFilter = FreeSpaceFilter(), permanent: Boolean = false, budget: Duration = 1.hours): FreeSpaceRun {
    val (plan, confirmation) = confirmed(filter, permanent)
    return space.verify(plan, confirmation, budget)
  }

  /** LS-13: le righe di ogni piano confermato, per impronta (un lotto contiene solo righe del piano che l'utente ha visto). */
  val confirmedPhones: MutableMap<dev.pampa.mycloudgallery.core.backup.contract.PhonePlanHash, Set<PhoneRef>> = HashMap()

  /** I lotti presentati al dialogo di sistema, in ordine. */
  val presented: MutableList<TrashBatch> = ArrayList()

  /** Le righe che il dialogo simulato ha tolto dal telefono. */
  val removed: MutableSet<PhoneRef> = LinkedHashSet()

  /** Il dialogo di sistema simulato: Android toglie le righe confermate ([only] = solo una parte) e risponde. */
  fun dialog(batch: TrashBatch, accept: Boolean = true, only: Set<PhoneRef>? = null): TrashOutcome {
    if (!accept) return TrashOutcome.Refused
    val confirmed = batch.items.map { it.phone }.filter { only == null || it in only }.toSet()
    for (ref in confirmed) {
      phone.delete(ref.mediaStoreId)
      removed += ref
    }
    return TrashOutcome.Confirmed(confirmed)
  }

  /** Il giro com'e' adesso (una tappa vuota). */
  suspend fun refresh(run: FreeSpaceRun): FreeSpaceRun = space.resume(run, Duration.ZERO)

  /** Tutti i lotti fino in fondo, ognuno controllato ([batchViolations]) e confermato. */
  suspend fun freeAll(start: FreeSpaceRun): FreeSpaceRun {
    var run = start
    while (true) {
      val batch = space.nextBatch(run) ?: return refresh(run)
      presented += batch
      check(batchViolations(batch).isEmpty()) { batchViolations(batch).joinToString() }
      run = space.recordOutcome(batch, dialog(batch))
    }
  }

  // --- Gli invarianti --------------------------------------------------------------------------------------------------------

  /**
   * Invariante 14 del §5.4 ("Libera spazio" non sbaglia), controllato contro la **verita'** del NAS finto e del telefono finto, non contro
   * cio' che dice il motore: nel momento in cui un lotto esce, per ogni sua riga (1) il telefono ha i byte della versione dell'elemento, (2)
   * **ogni** versione da proteggere ha nell'area privata una copia i cui byte sul NAS hanno lo SHA-256 della versione, (3) il diario sa che
   * la riga e' nel dialogo. Vuoto = tutto bene.
   */
  fun batchViolations(batch: TrashBatch): List<String> {
    val out = ArrayList<String>()
    val items = diary.everything().filter { it.first == batch.runId }.map { it.second }
    for (t in batch.items) {
      val fm = catalog.get(t.mediaId)
      if (fm == null) {
        out += "media sconosciuto nel lotto"
        continue
      }
      val media = fm.media
      val wanted = media.version(t.versionId).fingerprint.fullHash
      if (phone.sha256(t.phone.mediaStoreId) != wanted) out += "il telefono non ha i byte della versione"
      for (v in FreeSpaceEvaluator.requiredVersions(media, t.versionId)) {
        val hash = media.version(v).fingerprint.fullHash
        val good = media.locationsOf(v).any { loc ->
          val nasLoc = loc.location as? Location.Nas ?: return@any false
          nasLoc.share.equals(PRIVATE, ignoreCase = true) && hash != null &&
            nas.bytes(PRIVATE, nasLoc.path.value)?.let { Sha256.of(it) } == hash
        }
        if (!good) out += "una versione da proteggere senza copia privata integra"
      }
      val record = items.firstOrNull { it.phone == t.phone }
      if (record == null || record.state != FreeItemState.IN_DIALOG || record.batchIndex != batch.index) out += "il diario non sa che la riga e' nel dialogo"
    }
    if (batch.items.size > dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRules.MAX_BATCH) out += "lotto oltre i 2.000"
    return out
  }

  /**
   * "Non ne perde la traccia": ogni riga tolta dal dialogo simulato ha nel diario un elemento liberato o in dubbio (mai un elemento che
   * dice "verificato", "uscito" o "rimasto"), e ogni elemento liberato ha la riga davvero tolta. [settled] = dopo [SafeFreeSpace.settle].
   */
  fun trackingViolations(settled: Boolean): List<String> {
    val out = ArrayList<String>()
    val items = diary.everything().map { it.second }
    for (ref in removed) {
      val record = items.filter { it.phone == ref }
      val ok = record.any { it.state == FreeItemState.FREED || it.state == FreeItemState.IN_DOUBT || (!settled && it.state == FreeItemState.IN_DIALOG) }
      if (!ok) out += "riga tolta dal telefono senza traccia nel diario"
    }
    for (item in items) {
      if (item.state == FreeItemState.FREED && item.phone != null && item.phone !in removed) out += "elemento dato per liberato con la riga ancora sul telefono"
      if (settled && item.state == FreeItemState.IN_DIALOG) out += "elemento ancora nel dialogo dopo la risoluzione"
    }
    return out
  }

  companion object {
    const val PRIVATE: String = "test"
    const val COMMON: String = "test-comune"
    const val OTHER_PRIVATE: String = "test2"

    fun pathOf(raw: String): SharePath = SharePath.parse(raw)
  }
}

/**
 * Il guardiano finto del banco (D9): per i media chiesti, ogni versione con la sola copia nell'area comune riceve una copia privata in
 * `Backup/Area comune/` attaccata come verificata (come fa il guardiano vero di P3 dopo la sua verifica). [mode] sceglie i guasti: niente
 * copia, una copia **rovinata** che il catalogo crede buona (la verifica sul momento deve accorgersene), NAS che cade, tappa finita.
 */
internal class BenchGuardian(private val bench: FreeSpaceBench) : PrivateCopyGuardian {

  enum class Mode { COPY, FAIL, CORRUPT, NAS_DOWN, BUDGET_ONCE }

  var mode: Mode = Mode.COPY
  val scopes: MutableList<GuardScope> = ArrayList()

  override suspend fun ensure(scope: GuardScope, budget: Duration): GuardReport {
    scopes += scope
    when (mode) {
      Mode.FAIL -> return GuardReport(0, 0, 0, 1, 0, RunStop.COMPLETE)
      Mode.NAS_DOWN -> return GuardReport(0, 0, 0, 0, 0, RunStop.NAS)
      Mode.BUDGET_ONCE -> {
        mode = Mode.COPY
        return GuardReport(0, 0, 0, 0, 0, RunStop.BUDGET)
      }
      Mode.COPY, Mode.CORRUPT -> Unit
    }
    val ids = (scope as GuardScope.Media).ids
    var created = 0
    var bytes = 0L
    for (id in ids) {
      val fm = bench.catalog.get(id) ?: continue
      for (version in fm.media.versions) {
        val here = fm.media.locationsOf(version.id).filter { it.isPresent && it.isNas }
        if (here.any { (it.location as Location.Nas).share == FreeSpaceBench.PRIVATE }) continue
        val common = here.firstOrNull { (it.location as Location.Nas).share == FreeSpaceBench.COMMON } ?: continue
        val source = bench.nas.bytes(FreeSpaceBench.COMMON, (common.location as Location.Nas).path.value)!!
        val data = if (mode == Mode.CORRUPT) source.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() } else source
        val path = "Backup/Area comune/2024/01/" + (common.location as Location.Nas).path.fileName
        val location = bench.nasCopy(version.id, FreeSpaceBench.PRIVATE, path, data, verifiedAt = bench.clock.nasNow)
        bench.addLocation(id, location)
        created++
        bytes += data.size
      }
    }
    return GuardReport(created, 0, 0, 0, bytes, RunStop.COMPLETE)
  }
}
