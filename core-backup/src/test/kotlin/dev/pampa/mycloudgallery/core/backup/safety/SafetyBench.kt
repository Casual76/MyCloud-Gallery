package dev.pampa.mycloudgallery.core.backup.safety

import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupItem
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupStore
import dev.pampa.mycloudgallery.core.backup.contract.GuardReport
import dev.pampa.mycloudgallery.core.backup.contract.GuardScope
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.testing.BackupTestKit
import dev.pampa.mycloudgallery.core.backup.testing.FakePhoneBytes
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryBackupCatalog
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.okOrNull
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOps
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasOp
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Il banco di P3 sopra il banco del backup di P0 ([BackupTestKit]: NAS finto con area privata e comune, telefono finto, diario, catalogo,
 * cancello, orologi). Aggiunge un'altra area privata ([otherPrivate], quella di un familiare), i costruttori di deduplica, verifiche e
 * guardiano sopra le stesse porte, e i media "di partenza" dei casi del §2.3.
 */
internal class SafetyBench(val kit: BackupTestKit = BackupTestKit()) {

  val nas get() = kit.nas
  val catalog get() = kit.catalog
  val store get() = kit.store
  val share get() = kit.share
  val common get() = kit.commonShare

  init {
    nas.addShare(OTHER_PRIVATE)
  }

  private var nextPhoneId = 5_000L
  private var nextSort = 2_000_000L

  /** Le share toccate dalle letture e dalle scritture (per provare che un'altra area privata o l'area comune non si aprono). */
  val touched: MutableList<Pair<FakeNasOp, String>> = java.util.Collections.synchronizedList(ArrayList())

  /** Accende il registro delle share toccate ([touched]). */
  fun recordShares() {
    nas.fault = dev.pampa.mycloudgallery.core.nas.testing.FakeNasFault { op, s, _ ->
      touched += op to s.lowercase(Locale.ROOT)
      null
    }
  }

  fun touchedShares(): Set<String> = synchronized(touched) { touched.map { it.second }.toSet() }

  fun guardian(
    store: BackupStore = kit.store,
    catalog: BackupCatalog = kit.catalog,
    reader: NasMediaReader = nas.reader,
    ops: NasBackupOps = kit.ops,
    capBytes: Long = BackupPrivateCopyGuardian.DEFAULT_CAP_BYTES,
    root: SharePath? = null,
  ): BackupPrivateCopyGuardian = BackupPrivateCopyGuardian(
    deviceId = kit.deviceId,
    ops = ops,
    reader = reader,
    store = store,
    catalog = catalog,
    phone = kit.phone.port,
    gate = kit.gate,
    clock = kit.clock,
    privateShare = { kit.share },
    commonShare = { kit.commonShare },
    destinationRoot = { root },
    capBytes = capBytes,
    chunkBytes = kit.chunkBytes,
  )

  fun verifier(
    catalog: BackupCatalog = kit.catalog,
    reader: NasMediaReader = nas.reader,
    rotation: RotationPolicy = RotationPolicy(),
    protectedBytes: Long? = null,
  ): BackupSafetyVerifier = BackupSafetyVerifier(
    reader = reader,
    catalog = catalog,
    phone = kit.phone.port,
    gate = kit.gate,
    clock = kit.clock,
    privateShare = { kit.share },
    rotation = rotation,
    protectedBytes = { protectedBytes },
  )

  fun finder(catalog: BackupCatalog = kit.catalog, reader: NasMediaReader = nas.reader): PrivateCopyFinder =
    PrivateCopyFinder(reader, catalog, kit.clock)

  /** Il guardiano gira finche' il diario non ha piu' elementi del guardiano aperti (al massimo [maxRounds] giri). */
  suspend fun guardUntilQuiet(scope: GuardScope = GuardScope.All, maxRounds: Int = 12, make: () -> BackupPrivateCopyGuardian = { guardian() }): GuardReport {
    var last: GuardReport? = null
    repeat(maxRounds) {
      last = make().ensure(scope, 1.hours)
      val open = store.all().count { it.kind == UploadKind.GUARD_COPY && !it.state.terminal }
      if (open == 0 && last!!.stoppedBy == RunStop.COMPLETE) return last!!
    }
    return last!!
  }

  fun guardItems(): List<BackupItem> = store.all().filter { it.kind == UploadKind.GUARD_COPY }

  /** Lo stato di partenza di un media. */
  class Start(val mediaId: MediaId, val versionId: VersionId, val data: ByteArray, val phoneId: Long?, val commonPath: SharePath?)

  /**
   * Un media **proprio** la cui sola copia sul NAS e' nell'area comune (lo stato dopo uno spostamento della 4B nell'area comune, o dopo
   * una condivisione): [onPhone] = la versione c'e' ancora sul telefono; [hashKnown] = il catalogo conosce l'hash completo.
   */
  suspend fun commonOnly(
    name: String,
    data: ByteArray,
    onPhone: Boolean = false,
    hashKnown: Boolean = true,
    quickKnown: Boolean = true,
    localDay: Int? = 20261012,
    commonDir: String = "Foto condivise",
    sizeOverride: Long? = null,
    claimedHash: Sha256? = null,
    /** Il file c'e' gia' nell'area comune (messo da uno spostamento): il catalogo lo registra com'e'. */
    fileAlreadyThere: Boolean = false,
    /** Campo, gruppo A (D2): falso per un media mai stato sul telefono (messo sul NAS dal PC). */
    wasOnPhone: Boolean = true,
  ): Start {
    val content = NasContent.of(data)
    var ref: dev.pampa.mycloudgallery.core.backup.contract.PhoneRef? = null
    var phoneId: Long? = null
    if (onPhone) {
      phoneId = nextPhoneId++
      ref = kit.phone.add(phoneId, content)
    }
    val m = InMemoryBackupCatalog.Media(
      mediaId = MediaId.random(), versionId = VersionId.random(), kind = MediaKind.PHOTO, size = sizeOverride ?: data.size.toLong(),
      quickHash = if (quickKnown) QuickHashAlgorithm.ofContent(data) else null, phone = ref,
      phoneStamp = phoneId?.let { kit.phone.stamp(it) }, source = BackupSource.Camera, localDay = localDay, fileName = name,
      sortTime = nextSort--, fullHash = claimedHash ?: if (hashKnown) Sha256.of(data) else null, wasOnPhone = wasOnPhone,
    )
    catalog.put(m)
    val path = SharePath.parse("$commonDir/$name")
    val stamp = if (fileAlreadyThere) requireNotNull(stampOf(common, path.value)) else putFile(common, path.value, data)
    catalog.putCopy(m.mediaId, m.versionId, common, path, stamp)
    return Start(m.mediaId, m.versionId, data, phoneId, path)
  }

  /** Un media del telefono con una copia nell'area privata, verificata o no (come dopo un backup, o un vecchio backup trovato). */
  suspend fun withPrivateCopy(
    name: String,
    data: ByteArray,
    dir: String = "Backup/${BackupTestKit.DEVICE_FOLDER}/2026/10",
    onPhone: Boolean = true,
    verifiedAt: Long? = kit.clock.nasNow,
    hashKnown: Boolean = true,
    claimedHash: Sha256? = null,
  ): Start {
    val content = NasContent.of(data)
    var ref: dev.pampa.mycloudgallery.core.backup.contract.PhoneRef? = null
    var phoneId: Long? = null
    if (onPhone) {
      phoneId = nextPhoneId++
      ref = kit.phone.add(phoneId, content)
    }
    val m = InMemoryBackupCatalog.Media(
      mediaId = MediaId.random(), versionId = VersionId.random(), kind = MediaKind.PHOTO, size = data.size.toLong(),
      quickHash = QuickHashAlgorithm.ofContent(data), phone = ref, phoneStamp = phoneId?.let { kit.phone.stamp(it) },
      source = BackupSource.Camera, localDay = 20261012, fileName = name, sortTime = nextSort--,
      fullHash = claimedHash ?: if (hashKnown) Sha256.of(data) else null,
    )
    catalog.put(m)
    val path = SharePath.parse("$dir/$name")
    val stamp = putFile(share, path.value, data)
    catalog.putCopy(m.mediaId, m.versionId, share, path, stamp, verifiedAt)
    return Start(m.mediaId, m.versionId, data, phoneId, path)
  }

  /** Un file messo "da fuori" (il PC, un altro strumento) con il suo timbro. */
  fun putFile(share: String, path: String, data: ByteArray, modifiedAt: Long = (kit.clock.nasNow ?: 0) - 86_400_000L): NasStamp {
    val fileId = nas.putFile(share, path, NasContent.of(data), modifiedAt = modifiedAt)
    return NasStamp(data.size.toLong(), modifiedAt, fileId)
  }

  suspend fun stampOf(share: String, path: String): NasStamp? =
    nas.reader.stat(share, SharePath.parse(path), Purpose.BACKGROUND).okOrNull()?.stamp

  /** Il telefono cancella la riga e la lettura del telefono lo registra nel catalogo (E-B13, E-P11). */
  fun phoneLoses(start: Start) {
    kit.phone.delete(requireNotNull(start.phoneId))
    catalog.relinkPhone(start.mediaId, null)
  }

  fun guardDir(day: String = "2026/10"): String = "Backup/Area comune/$day"

  /** I file visibili (fuori da `.mycloudgallery/`) di una share. */
  fun visible(share: String): List<String> = nas.filePaths(share).filter { !it.startsWith(".mycloudgallery/") }

  companion object {
    /** L'area privata di un familiare: il backup non la apre mai. */
    const val OTHER_PRIVATE: String = "test2"

    fun bytes(n: Int, seed: Int) = ByteArray(n) { (it * 131 + seed * 7 + (it shr 8)).toByte() }

    /** Gli stessi byte di [data] con un byte cambiato nel mezzo (stessa dimensione, stesso `qh1` se il file supera 128 KiB). */
    fun oneByteChanged(data: ByteArray, at: Int = data.size / 2): ByteArray = data.copyOf().also { it[at] = (it[at] + 1).toByte() }

    val NOTHING: Duration = Duration.ZERO
  }
}

/** Un `NasContent` per i test di [FakePhoneBytes] e del NAS finto. */
internal fun ByteArray.content(): NasContent = NasContent.of(this)
