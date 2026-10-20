package dev.pampa.mycloudgallery.core.backup.versions

import dev.pampa.mycloudgallery.core.backup.contract.BackupCandidate
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.PendingTask
import dev.pampa.mycloudgallery.core.backup.contract.PendingTaskKind
import dev.pampa.mycloudgallery.core.backup.contract.PendingTaskState
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRewrite
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRewriter
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.UploadStateMachine
import dev.pampa.mycloudgallery.core.backup.testing.BackupDeath
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupGate
import dev.pampa.mycloudgallery.core.backup.testing.FakePhoneBytes
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryPendingTasks
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.okOrNull
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOps
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupPaths
import dev.pampa.mycloudgallery.core.nas.ops.backup.VersionPaths
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.File
import java.time.ZoneId
import java.util.Locale
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Il banco delle versioni (P8; lo estende P13 per il banco delle interruzioni della 5B): NAS finto con l'area privata e quella comune,
 * telefono finto, diario e catalogo delle versioni in memoria, file preparati in [root], cancello, orologi, attivita' in sospeso. [restart]
 * simula la morte del processo: il motore si ricrea, il resto resta (NAS, telefono, disco). Scritture piccole per provare i punti di ripresa
 * e i guasti a ogni chiamata con file da decine di KiB.
 */
class VersionWorld(
  val root: File,
  val deviceId: String = DEVICE,
  val share: String = PRIVATE_SHARE,
  val commonShare: String = COMMON_SHARE,
  val config: VersionConfig = VersionConfig(chunkBytes = 16 * 1024, checkpointBytes = 32L * 1024, lockedFirstWaitMs = 10, lockedRetries = 3),
) {
  val clock = FakeBackupClock()
  val nas = InMemoryNasMedia(nasNow = { clock.nasNow ?: FakeBackupClock.DEFAULT_NAS_NOW }).apply {
    addShare(share)
    addShare(commonShare)
  }
  val ops: NasBackupOps = nas.backupOps(deviceId)
  val phone = FakePhoneBytes()
  val journal = InMemoryVersionJournal(nasNow = { clock.nasNow })
  val catalog = InMemoryVersionCatalog(mapOf(share to NasArea.PRIVATE, commonShare to NasArea.COMMON))
  val staged = DirectoryStagedFiles(File(root, "versions"))
  val gate = FakeBackupGate()
  val pending = InMemoryPendingTasks()
  val zone: ZoneId = ZoneId.of("Europe/Rome")

  /** D20 sopra le attivita' in sospeso finte, idempotente per versione come `RoomPendingTasks`. */
  val tasks = VideoOriginalTasks { mediaId, versionId, bytes ->
    if (pending.everything.none { it.versionId == versionId && it.kind == PendingTaskKind.EDITED_VIDEO_ORIGINAL }) {
      pending.add { id -> PendingTask(id, PendingTaskKind.EDITED_VIDEO_ORIGINAL, PendingTaskState.OPEN, mediaId, versionId, bytes, clock.nasNow ?: 0) }
    }
  }

  /** Tutti i byte che il telefono ha mai avuto e i file preparati: un file visibile ha uno di questi SHA-256 (invariante 1). */
  val knownContents: MutableSet<Sha256> = HashSet()

  private val random = Random(77)
  private var nextPhoneId = 100L
  private var nextModified = 1_000L

  /** Il motore del "processo" attuale. */
  var engine: NasVersionEngine = newEngine()
    private set

  fun newEngine(
    journal: VersionJournal = this.journal,
    catalog: VersionCatalog = this.catalog,
    ops: NasBackupOps = this.ops,
  ): NasVersionEngine = NasVersionEngine(
    deviceId = deviceId,
    share = share,
    ops = ops,
    reader = nas.reader,
    journal = journal,
    catalog = catalog,
    phone = phone.port,
    staged = staged,
    gate = gate,
    clock = clock,
    tasks = tasks,
    config = config,
    zone = zone,
    sleep = { ms -> clock.advance(ms) },
  )

  /** La morte del processo: si ricrea solo cio' che sta in memoria. */
  fun restart() {
    engine = newEngine()
  }

  /** Un motore che muore alle scritture del diario e del catalogo. */
  fun dyingEngine(death: BackupDeath, ops: NasBackupOps = this.ops): NasVersionEngine =
    newEngine(DyingVersionJournal(journal, death), DyingVersionCatalog(catalog, death), ops)

  /** Byte pseudo-casuali ripetibili. */
  fun bytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

  // --- Media ----------------------------------------------------------------------------------------------------------------------

  /** Un media al sicuro: l'originale sul telefono (se [onPhone]) e al solito percorso dell'area privata, riletto e verificato. */
  class Seeded(val mediaId: MediaId, val original: VersionId, val phoneId: Long?, val ref: PhoneRef?, val usualPath: SharePath, val content: ByteArray) {
    override fun toString(): String = "Seeded"
  }

  fun seedSafe(
    name: String = "IMG_1234.jpg",
    size: Int = 70_000,
    kind: MediaKind = MediaKind.PHOTO,
    mime: String = "image/jpeg",
    folder: String = "Backup/S25 di prova/2026/10",
    onPhone: Boolean = true,
    targetShare: String = share,
    content: ByteArray = bytes(size),
  ): Seeded {
    val sha = Sha256.of(content)
    knownContents += sha
    val (mediaId, original) = catalog.addMedia(kind = kind, mime = mime, size = content.size.toLong(), fullHash = sha)
    var phoneId: Long? = null
    var ref: PhoneRef? = null
    if (onPhone) {
      phoneId = nextPhoneId++
      ref = phone.add(phoneId, NasContent.of(content), nextModified++)
      catalog.putPhoneCopy(mediaId, original, ref, phone.stamp(phoneId)!!)
    }
    val path = SharePath.parse("$folder/$name")
    nas.putFile(targetShare, path.value, NasContent.of(content), modifiedAt = (clock.nasNow ?: 0) - 86_400_000L)
    catalog.putNasCopy(mediaId, original, targetShare, path, statOf(targetShare, path), verifiedAt = clock.nasNow)
    return Seeded(mediaId, original, phoneId, ref, path, content)
  }

  /** Samsung "Salva" sul telefono (E-E1): byte nuovi sulla riga, la lettura del telefono crea la versione `EDITED` attuale. */
  fun editOnPhone(seed: Seeded, content: ByteArray = bytes(seed.content.size + 1_000)): VersionId {
    val id = requireNotNull(seed.phoneId)
    knownContents += Sha256.of(content)
    phone.rewrite(id, NasContent.of(content), nextModified++)
    val ref = phone.ref(id)!!
    return catalog.phoneChanged(seed.mediaId, ref, phone.stamp(id)!!, content.size.toLong(), fullHash = null)
  }

  /** Samsung "Ripristina originale" sul telefono (E-E2): la riga torna ai byte dell'originale; la lettura del telefono lo riconosce. */
  fun revertOnPhone(seed: Seeded) {
    val id = requireNotNull(seed.phoneId)
    phone.rewrite(id, NasContent.of(seed.content), nextModified++)
    catalog.phoneReverted(seed.mediaId, phone.ref(id)!!, phone.stamp(id)!!, seed.original)
  }

  /** Il candidato del backup per la versione attuale di [mediaId] sulla riga [phoneId] (come lo darebbe il piano della 5A). */
  suspend fun candidate(mediaId: MediaId, phoneId: Long, versionId: VersionId = catalog.currentOf(mediaId)): BackupCandidate {
    val media = catalog.media(mediaId)!!
    val v = media.version(versionId)!!
    return BackupCandidate(
      mediaId = mediaId,
      versionId = versionId,
      phone = phone.ref(phoneId)!!,
      stamp = phone.stamp(phoneId)!!,
      size = v.size,
      kind = media.kind,
      source = BackupSource.Camera,
      localDay = 20261012,
      dateEstimated = false,
      fileName = "IMG.jpg",
      sortTime = 1,
      quickHash = null,
      original = v.kind == dev.pampa.mycloudgallery.core.model.VersionKind.ORIGINAL,
    )
  }

  /** Il timbro di un file del NAS adesso. */
  fun statOf(share: String, path: SharePath): NasStamp = kotlinx.coroutines.runBlocking {
    nas.reader.stat(share, path, Purpose.BACKGROUND).okOrNull()!!.stamp
  }

  fun sha(share: String, path: String): Sha256? = nas.bytes(share, path)?.let { Sha256.of(it) }

  // --- Giri ------------------------------------------------------------------------------------------------------------------------

  /** Giri finche' il diario non ha piu' lavori aperti che possono andare avanti (al massimo [maxRounds]). */
  suspend fun runUntilQuiet(maxRounds: Int = 12, budget: Duration = 1.hours): VersionRunResult {
    var last = VersionRunResult.nothing(RunStop.COMPLETE)
    repeat(maxRounds) {
      last = engine.run(budget)
      val open = journal.all().count { !it.state.terminal }
      if (open == 0 && last.stoppedBy == RunStop.COMPLETE) return last
      if (last.stoppedBy == RunStop.COMPLETE && last.held == open) return last
    }
    return last
  }

  // --- Invarianti (§5.4: 1, 2, 3, 6, 8, 10, 12, 13) -----------------------------------------------------------------------------

  /** I file di una share: percorso → (SHA-256, `fileId`). */
  fun files(of: String = share): Map<String, Pair<Sha256, Long?>> {
    val out = LinkedHashMap<String, Pair<Sha256, Long?>>()
    for (path in nas.filePaths(of)) {
      val bytes = nas.bytes(of, path) ?: continue
      out[path] = Sha256.of(bytes) to statOf(of, SharePath.parse(path)).fileId
    }
    return out
  }

  class Snapshot(val files: Map<String, Map<String, Pair<Sha256, Long?>>>)

  fun snapshot(): Snapshot = Snapshot(mapOf(share to files(share), commonShare to files(commonShare)))

  /**
   * Le violazioni degli invarianti rispetto a [before] (vuoto = tutto bene). [final] = a lavoro finito (niente piu' temporanei, l'attuale al
   * solito percorso).
   */
  suspend fun violations(before: Snapshot, final: Boolean): List<String> {
    val v = ArrayList<String>()
    knownContents += staged.pending().map { it.sha256 }
    // Le versioni del catalogo (una versione corretta, il file di un editor) sono byte di media dell'utente.
    for (m in catalog.allMedia()) catalog.media(m)?.versions?.mapNotNullTo(knownContents) { it.fullHash }
    for ((s, beforeFiles) in before.files) {
      val now = files(s)
      // 3: nessuna sostituzione. Un file di prima c'e' ancora con gli stessi byte e lo stesso fileId: allo stesso nome, o spostato da o verso
      // Versioni/ (le versioni: stessa identita', stessi byte).
      for ((path, old) in beforeFiles) {
        if (path.startsWith(".mycloudgallery/")) continue
        val same = now[path]
        if (same != null && same == old) continue
        val moved = now.entries.firstOrNull { it.value.second != null && it.value.second == old.second }
        when {
          moved == null -> v += "3: sparito $path ($s)"
          moved.value.first != old.first -> v += "3: byte cambiati in $path ($s)"
          !(VersionPaths.isInVersions(SharePath.parse(moved.key)) xor VersionPaths.isInVersions(SharePath.parse(path))) ->
            v += "3: spostato fuori dalle versioni $path ($s)"
        }
      }
      val beforeShas = beforeFiles.filterKeys { !it.startsWith(".mycloudgallery/") }.values.map { it.first }.toSet()
      val cited = journal.citedTemps().map { it.value.lowercase(Locale.ROOT) }.toSet()
      val created = now.filterKeys { it !in beforeFiles }
      for ((path, fact) in created) {
        if (path.startsWith(".mycloudgallery/")) {
          // 6: solo i propri temporanei del backup, citati da un lavoro aperto; a lavoro finito nessuno.
          val temp = SharePath.parse(path)
          when {
            !NasBackupPaths.isBackupTempOf(temp, deviceId) -> v += "6: file dell'app che non e' un temporaneo del backup"
            final -> v += "6: temporaneo rimasto"
            path.lowercase(Locale.ROOT) !in cited -> v += "6: temporaneo non citato"
          }
          continue
        }
        // 1: nessun file a meta' visibile.
        if (fact.first !in knownContents && fact.first !in beforeShas) v += "1: file visibile con byte sconosciuti ($path)"
        // 10: niente fuori posto: solo in Versioni/ o al solito percorso di un lavoro.
        val usual = journal.all().any { it.usualPath.value.equals(path, ignoreCase = true) && it.share.equals(s, ignoreCase = true) }
        if (!VersionPaths.isInVersions(SharePath.parse(path)) && !usual && fact.first !in beforeShas) v += "10: file fuori posto ($path)"
      }
      // 2: nessun doppione creato: i byte di un file nuovo compaiono una volta sola fra i file visibili.
      val visible = now.filterKeys { !it.startsWith(".mycloudgallery/") }
      for ((sha, group) in visible.entries.groupBy { it.value.first }) {
        if (group.size > 1 && group.any { it.key !in beforeFiles }) v += "2: doppione (${group.size} copie)"
      }
      // 13: niente perso: i byte di ogni file visibile di prima ci sono ancora.
      for (sha in beforeShas) if (visible.values.none { it.first == sha }) v += "13: versione persa"
    }
    // 8: ogni transizione del diario e' ammessa.
    for (t in journal.transitions) {
      if (!UploadStateMachine.canTransition(t.flow, t.from, t.to)) v += "8: transizione ${t.from.code} -> ${t.to.code}"
    }
    // Il catalogo dice il vero: ogni copia presente nel catalogo c'e' sul NAS con la sua dimensione.
    for (m in catalog.allMedia()) {
      val media = catalog.media(m) ?: continue
      for (ver in media.versions) for (copy in ver.nas.filter { it.present }) {
        val bytes = nas.bytes(copy.share, copy.path.value)
        if (bytes == null) v += "catalogo: copia assente" else if (bytes.size.toLong() != ver.size) v += "catalogo: dimensione"
        else if (copy.verifiedAtMillis != null && ver.fullHash != null && Sha256.of(bytes) != ver.fullHash) v += "catalogo: verificata ma diversa"
      }
      if (final) {
        // 12: a lavoro finito l'attuale (se ha una copia privata) ha al solito percorso i suoi byte.
        val current = media.current
        val usual = current.nas.filter { VersionPlanning.isOwnPrivate(it, share) && !VersionPaths.isInVersions(it.path) }
        for (copy in usual) {
          if (current.fullHash != null && sha(copy.share, copy.path.value) != current.fullHash) v += "12: l'attuale non e' al solito percorso"
        }
      }
    }
    return v
  }

  companion object {
    val DEVICE: String = Base32.encode(ByteArray(16) { 0x5A })
    const val PRIVATE_SHARE: String = "test"
    const val COMMON_SHARE: String = "test-comune"
  }
}

/**
 * La riscrittura del telefono sopra il telefono finto (come `MediaStorePhoneMediaRewriter` di core-mediastore vista dalla porta): solo con il
 * permesso del dialogo per quella riga ([granted]), solo un file con lo SHA-256 atteso, solo su una riga che ha adesso i byte della versione
 * al sicuro (R3: altrimenti `RowChanged`; con i byte nuovi gia' sopra e' fatta); scrive i byte sulla riga (timbro nuovo).
 */
class PhoneRowRewriter(private val phone: FakePhoneBytes) : PhoneRewriter {
  val granted: MutableSet<Long> = HashSet()
  var failNext: PhoneRewrite.Failed? = null
  val replaced: MutableList<Long> = ArrayList()
  private var modified = 900_000L

  override suspend fun replace(ref: PhoneRef, mime: String?, file: File, expectedSha256: Sha256, currentSha256: Sha256): PhoneRewrite {
    val bytes = file.readBytes()
    if (Sha256.of(bytes) != expectedSha256) return PhoneRewrite.SourceMismatch
    if (phone.stamp(ref.mediaStoreId) == null) return PhoneRewrite.Gone
    // R3, nell'ordine del riscrittore vero (la copia di sicurezza si legge prima di aprire in scrittura).
    val now = phone.sha256(ref.mediaStoreId)
    if (now == expectedSha256) return PhoneRewrite.Replaced(expectedSha256, bytes.size.toLong())
    if (now != currentSha256) return PhoneRewrite.RowChanged
    if (ref.mediaStoreId !in granted) return PhoneRewrite.NotGranted
    failNext?.let {
      failNext = null
      return it
    }
    phone.rewrite(ref.mediaStoreId, NasContent.of(bytes), modified++)
    replaced += ref.mediaStoreId
    return PhoneRewrite.Replaced(expectedSha256, bytes.size.toLong())
  }
}

/** Il timbro di un file del telefono finto (per i controlli dei test). */
fun FakePhoneBytes.stampOf(id: Long): FileStamp = stamp(id)!!
