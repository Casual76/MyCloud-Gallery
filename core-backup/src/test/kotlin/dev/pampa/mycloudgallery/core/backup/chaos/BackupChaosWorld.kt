package dev.pampa.mycloudgallery.core.backup.chaos

import dev.pampa.mycloudgallery.core.backup.contract.BackupItem
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupPlanner
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupGate
import dev.pampa.mycloudgallery.core.backup.testing.FakePhoneBytes
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryBackupCatalog
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryBackupStore
import dev.pampa.mycloudgallery.core.backup.testing.ReferenceBackupPlanner
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupPaths
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Locale
import kotlin.random.Random

/** Dove si apre un mondo. */
interface BackupChaosTarget {
  val name: String

  /** Un mondo nuovo e vuoto per il giro [round]. */
  suspend fun openWorld(round: Int): BackupChaosWorld
}

/** Il NAS finto in memoria: il banco JVM. */
class FakeNasTarget : BackupChaosTarget {
  override val name: String = "NAS finto in memoria (InMemoryNasMedia)"

  override suspend fun openWorld(round: Int): BackupChaosWorld {
    val clock = FakeBackupClock()
    return BackupChaosWorld(clock, FakeNasSide(clock, BackupChaosWorld.DEVICE))
  }
}

/** Il simulatore SMB di core-nas con la sessione e le primitive del backup vere. */
class SimSmbTarget : BackupChaosTarget {
  override val name: String = "simulatore SMB di core-nas con la sessione e le primitive del backup vere"

  override suspend fun openWorld(round: Int): BackupChaosWorld {
    val clock = FakeBackupClock()
    return BackupChaosWorld(clock, SimSmbNasSide(clock, BackupChaosWorld.DEVICE))
  }
}

/** Un media del telefono che il banco segue: nome, tipo, gli `_ID` che la riga ha avuto e l'ultimo contenuto. */
class TrackedMedia(
  val label: Int,
  val mediaId: MediaId,
  val versionId: VersionId,
  val name: String,
  val kind: MediaKind,
  val localDay: Int?,
  /** Gli `_ID` della riga (si rinumerano, E-P12): l'ultimo che esiste sul telefono e' quello attuale. */
  val ids: MutableList<Long>,
  val size: Long,
  val sha: Sha256,
) {
  /** La riga e' stata riscritta (Samsung "Salva"): il catalogo ha una versione nuova e questa non si segue piu'. */
  var retired: Boolean = false

  override fun toString(): String = "TrackedMedia(#$label, ${kind.code}, $size B)"
}

/**
 * Il mondo di un giro: il NAS ([NasSide]), il telefono finto, il diario, il catalogo, il cancello e gli orologi del backup, piu' le azioni
 * "da fuori" che il banco fa per conto suo (il PC che crea un file, il tempo che passa, la pulizia dei propri file, la lettura del telefono).
 * Diario, catalogo e telefono sopravvivono alla morte del processo (sono il disco e il telefono): il motore si ricrea sopra di loro.
 */
class BackupChaosWorld(
  val clock: FakeBackupClock,
  val nas: NasSide,
  val deviceId: String = DEVICE,
  /** Scritture piccole di proposito: punti di ripresa e guasti si provano con file da decine di KiB (i video da GB passano dallo stesso codice). */
  val chunkBytes: Int = 16 * 1024,
  val checkpointBytes: Long = 32L * 1024,
) : AutoCloseable {
  val share: String = nas.shares[0]
  val commonShare: String = nas.shares[1]
  val phone = FakePhoneBytes()
  val store = InMemoryBackupStore()
  val catalog = InMemoryBackupCatalog(share, commonShare)
  val gate = FakeBackupGate()
  var folder: DeviceFolder? = DeviceFolder(FOLDER_NAME)
  val planner: BackupPlanner = ReferenceBackupPlanner(catalog, { BackupSources.DEFAULT })

  val tracked = ArrayList<TrackedMedia>()

  /** Le impronte di tutti i contenuti che il telefono ha avuto o avra' (prima e dopo una riscrittura): quelle di un file sul NAS legittimo. */
  val phoneShasEver = LinkedHashSet<Sha256>()

  /** Il contenuto di ogni impronta del telefono (solo quelli piccoli), per le azioni del PC che ne mette una copia identica. */
  private val smallContents = HashMap<Sha256, NasContent>()

  private var nextPhoneId = 100L
  private var nextSort = 1_000_000L
  private var nextLabel = 1

  /** Un media del telefono, gia' letto dal catalogo (proprio, nella sorgente "fotocamera"); i contenuti oltre 1 MiB non si tengono in memoria. */
  fun addPhoneMedia(name: String, content: NasContent, kind: MediaKind, localDay: Int?, modifiedMs: Long = 1_000): TrackedMedia {
    val id = nextPhoneId++
    val ref = phone.add(id, content, modifiedMs)
    if (content.size > BIG_CONTENT) nas.registerKnown(content)
    val sha = shaOf(content)
    val mediaId = MediaId.random()
    val versionId = VersionId.random()
    catalog.put(
      InMemoryBackupCatalog.Media(
        mediaId = mediaId, versionId = versionId, kind = kind, size = content.size, quickHash = null, phone = ref, phoneStamp = phone.stamp(id),
        source = BackupSource.Camera, localDay = localDay, fileName = name, sortTime = nextSort--, fullHash = null,
      ),
    )
    return TrackedMedia(nextLabel++, mediaId, versionId, name, kind, localDay, mutableListOf(id), content.size, sha).also { tracked += it }
  }

  /** L'impronta di un contenuto del telefono, ricordata tra quelle legittime. */
  fun shaOf(content: NasContent): Sha256 {
    val sha = FakePhoneBytes.sha256Of(content)
    phoneShasEver += sha
    if (content.size <= SMALL_CONTENT) smallContents[sha] = content
    return sha
  }

  fun bytesOf(sha: Sha256): ByteArray? = smallContents[sha]?.let { c -> ByteArray(c.size.toInt()).also { c.copy(0, it, 0, it.size) } }

  fun nextFreshId(): Long = nextPhoneId++

  /** L'`_ID` attuale della riga di [t] sul telefono, o null se la riga non c'e'. */
  fun currentId(t: TrackedMedia): Long? = t.ids.lastOrNull { phone.stamp(it) != null }

  fun kindOf(item: BackupItem): BackupKind? = catalog.media(item.mediaId)?.kind?.let { BackupKind.of(it) }

  /**
   * Un file che c'era **prima** del backup (vecchi backup, il PC, un altro dispositivo). Con [known] la scansione l'ha gia' visto: la
   * deduplica lo trova (§2.11 punto 1); senza, il backup lo scopre solo pubblicando (nome occupato, E-B3).
   */
  suspend fun putNasFile(path: String, bytes: ByteArray, known: Boolean, inShare: String = share) {
    nas.pcPut(inShare, path, bytes, (clock.nasNow ?: FakeBackupClock.DEFAULT_NAS_NOW) - DAY_MS)
    if (!known) return
    val entry = (nas.reader.stat(inShare, SharePath.parse(path), Purpose.BACKGROUND) as? NasResult.Ok)?.value ?: error("File appena messo assente.")
    val owner = InMemoryBackupCatalog.Media(
      mediaId = MediaId.random(), versionId = VersionId.random(), kind = MediaKind.PHOTO, size = bytes.size.toLong(), quickHash = null, phone = null,
      phoneStamp = null, source = BackupSource.Camera, localDay = null, fileName = SharePath.parse(path).name, sortTime = 0, fullHash = null,
      mine = false,
    )
    catalog.put(owner)
    catalog.putCopy(owner.mediaId, owner.versionId, inShare, SharePath.parse(path), NasStamp(entry.size, entry.modifiedAt, entry.fileId))
  }

  /** Il "PC" mette un file nel punto previsto per [t]; restituisce il fatto per l'elenco dei file che c'erano. Nessun colpo. */
  suspend fun pcCreateAtPlanned(random: Random, maxBytes: Long = PC_MAX_BYTES): NasFileFact? {
    val open = store.itemsToResume(Int.MAX_VALUE)
    val unsafe = tracked.filter { !it.retired && catalog.media(it.mediaId) != null && !catalog.isSafe(it.mediaId) && it.size <= maxBytes }
    val t = unsafe.randomOrNull(random) ?: return null
    val destination = open.firstOrNull { it.mediaId == t.mediaId }?.let { it.finalPath ?: it.planned } ?: plannedFor(t) ?: return null
    val existing = nas.reader.stat(share, destination, Purpose.BACKGROUND)
    if (existing !is NasResult.Ok || existing.value != null) return null
    val identical = random.nextBoolean()
    val bytes = (if (identical) bytesOf(t.sha) else null) ?: random.nextBytes(t.size.toInt())
    nas.pcPut(share, destination.value, bytes, clock.nasNow ?: FakeBackupClock.DEFAULT_NAS_NOW)
    val entry = (nas.reader.stat(share, destination, Purpose.BACKGROUND) as? NasResult.Ok)?.value
    return NasFileFact(share, destination.value, bytes.size.toLong(), entry?.fileId, Sha256.of(bytes))
  }

  /** La destinazione che il piano da' a [t] (se e' ancora un candidato). */
  suspend fun plannedFor(t: TrackedMedia): SharePath? {
    val device = folder ?: return null
    val candidate = planner.next(BackupKind.of(t.kind), 1_000).firstOrNull { it.mediaId == t.mediaId } ?: return null
    return planner.destination(candidate, device)
  }

  /**
   * La lettura del telefono (la scansione del MediaStore): ritrova le righe dopo una rinumerazione degli `_ID` (E-P12), segna come
   * sparite quelle cancellate e registra come versione nuova (un media nuovo) quella riscritta sul posto (E-B15: Samsung "Salva").
   */
  fun rescan() {
    for (t in tracked.toList()) {
      if (t.retired) continue
      val media = catalog.media(t.mediaId) ?: continue
      val id = currentId(t)
      if (id == null) {
        catalog.relinkPhone(t.mediaId, null)
        continue
      }
      val stamp = phone.stamp(id)!!
      if (media.phoneStamp != null && stamp != media.phoneStamp) {
        catalog.relinkPhone(t.mediaId, null)
        t.retired = true
        val sha = phone.sha256(id)!!
        phoneShasEver += sha
        // La riga e' la stessa: porta con se' tutta la storia dei suoi _ID (un evento programmato puo' ancora rinumerarla).
        val fresh = TrackedMedia(nextLabel++, MediaId.random(), VersionId.random(), t.name, t.kind, t.localDay, t.ids.toMutableList(), stamp.size, sha)
        catalog.put(
          InMemoryBackupCatalog.Media(
            mediaId = fresh.mediaId, versionId = fresh.versionId, kind = t.kind, size = stamp.size, quickHash = null, phone = phone.ref(id),
            phoneStamp = stamp, source = BackupSource.Camera, localDay = t.localDay, fileName = t.name, sortTime = nextSort--, fullHash = null,
          ),
        )
        tracked += fresh
      } else {
        catalog.relinkPhone(t.mediaId, phone.ref(id))
      }
    }
  }

  /** La riga di [media] c'e' ancora sul telefono con il timbro che il catalogo conosce. */
  private fun onPhone(media: InMemoryBackupCatalog.Media): Boolean {
    val ref = media.phone ?: return false
    return phone.stamp(ref.mediaStoreId).let { it != null && it == media.phoneStamp }
  }

  fun advanceTime(ms: Long) {
    clock.advance(ms)
    nas.advanceTime(ms)
  }

  /**
   * La pulizia dei propri file (D33, E-B21): toglie i temporanei `-bk-` di questo dispositivo fermi da piu' di un giorno. Quella onesta lascia
   * quelli che il diario cita ([honorCitations]); quella rotta (la mutazione del banco) no. Restituisce i percorsi tolti.
   */
  suspend fun housekeeping(honorCitations: Boolean, cited: Set<String>): List<String> {
    if (!nas.caps.housekeeping) return emptyList()
    val dir = NasLayout.ROOT.child("tmp")
    val entries = ArrayList<NasEntry>()
    nas.reader.list(share, dir, Purpose.BACKGROUND) { entries += it }
    val now = clock.nasNow ?: FakeBackupClock.DEFAULT_NAS_NOW
    val removed = ArrayList<String>()
    for (entry in entries) {
      if (entry.isDirectory) continue
      val path = dir.child(entry.name)
      if (!NasBackupPaths.isBackupTempOf(path, deviceId)) continue
      if (now - entry.modifiedAt < DAY_MS) continue
      if (honorCitations && path.value.lowercase(Locale.ROOT) in cited) continue
      nas.remove(share, path.value)
      removed += path.value
    }
    return removed
  }

  /** Lo stato per il verificatore: tutto cio' che serve a giudicare gli invarianti, senza oggetti vivi. */
  suspend fun capture(
    before: Collection<NasFileFact>,
    scan: ScanResult,
    transitions: List<DiaryTransition>,
    removedCited: List<String>,
    notes: List<Note>,
  ): BackupState = BackupState(
    deviceId = deviceId,
    privateShare = share,
    commonShare = commonShare,
    before = before.toList(),
    files = scan.files,
    phoneShas = phoneShasEver.toSet(),
    items = store.all().map {
      ItemFact(
        id = it.id.value, kind = it.kind.code, state = it.state.code, media = it.mediaId.toString(), version = it.versionId.toString(), size = it.size,
        share = it.share, planned = it.planned.value, finalPath = it.finalPath?.value, temp = it.temp?.value, resumeAt = it.resumeAt,
        sha256 = it.sha256, verifyAttempts = it.verifyAttempts,
      )
    },
    transitions = transitions.map { TransitionFact(it.item.value, it.from.code, it.to.code) },
    media = catalog.allMedia().filter { it.mine }.map {
      MediaFact(
        media = it.mediaId.toString(), version = it.versionId.toString(), size = it.size, fullHash = it.fullHash, safe = catalog.isSafe(it.mediaId),
        problem = catalog.problemOf(it.mediaId)?.code, onPhone = onPhone(it),
      )
    },
    copies = catalog.allCopies().map {
      CopyFact(it.mediaId.toString(), it.versionId.toString(), it.share, it.path.value, it.stamp.size, it.verifiedAtMillis != null)
    },
    cited = store.citedTemps().mapTo(LinkedHashSet()) { it.value },
    cleanerRemovedCited = removedCited,
    notes = notes,
    cacheHits = scan.cacheHits,
    filesHashed = scan.filesHashed,
  )

  override fun close() = nas.close()

  companion object {
    /** Un id di dispositivo valido (`TrustIds`). */
    val DEVICE: String = Base32.encode(ByteArray(16) { 0x5A })
    const val FOLDER_NAME = "S25 di prova"
    const val DAY_MS = 86_400_000L
    const val BIG_CONTENT = 1L shl 20
    const val SMALL_CONTENT = 4L shl 20
    const val PC_MAX_BYTES = 2L shl 20
  }
}
