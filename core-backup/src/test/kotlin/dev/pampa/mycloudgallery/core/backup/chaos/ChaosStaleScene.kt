package dev.pampa.mycloudgallery.core.backup.chaos

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.free.BootCounter
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.RenameOutcome
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOps
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.File
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours

// LA SCENA DEL CATALOGO CHE RITARDA (giro 122 del banco JVM, `docs/prova-500.md` Parte C §C6): "Ripristina originale" (uno scambio) ha riportato
// l'originale da `Versioni/` al solito percorso, il processo muore DOPO la rinomina e prima di `RESTORED`, il PC modifica sul posto il file appena
// riportato e il lavoro riprende. Serve a `StaleCatalogAfterPcEditTest` (modifica di un'altra dimensione: il lavoro non riconosce il file, il catalogo
// ritarda fino alla scansione) e a `ChaosKnownDefectsTest` (modifica della STESSA dimensione: F3).

/** Le primitive vere del NAS, ma il processo muore subito dopo la prima `restoreVersion` riuscita (la risposta e' persa). */
internal class DieAfterRestore(private val inner: NasBackupOps) : NasBackupOps by inner {
  var armed = true

  override suspend fun restoreVersion(share: String, from: SharePath, to: SharePath, expect: NasStamp, purpose: Purpose): NasResult<RenameOutcome> {
    val result = inner.restoreVersion(share, from, to, expect, purpose)
    if (armed && result is NasResult.Ok && result.value is RenameOutcome.Renamed) {
      armed = false
      throw ChaosDeath(0, Primitive.RESTORE_VERSION, Moment.AFTER)
    }
    return result
  }
}

internal class StaleScene(root: File) {
  val clock = FakeBackupClock()
  val world = BackupChaosWorld(clock, FakeNasSide(clock, BackupChaosWorld.DEVICE))
  val fb = FiveBWorld(world, Backup5BConfig(), Random(3), root)
  lateinit var rec: MediaRec
  lateinit var usual: SharePath
  val mediaId: MediaId get() = rec.mediaId

  fun services(ops: NasBackupOps = world.nas.ops): FiveBServices = RealFiveBStack.create(
    FiveBEnv(
      world = world, fb = fb, ops = ops, reader = world.nas.reader, journal = fb.journal, versionCatalog = fb.catalog, freeCatalog = fb.catalog,
      backupCatalog = fb.catalog, freeDiary = fb.freeDiary, guardStore = fb.guardStore, phone = world.phone.port, rewriter = fb.rewriter,
      clock = world.clock, boot = BootCounter { fb.bootCount }, random = Random(11),
    ),
  )

  suspend fun scan() = BackupNasScanner.ofShares(world.nas.reader, world.nas.shares, useCache = false).scan(allowCache = false)

  /** I byte che stanno sul NAS adesso (fuori dall'area dell'app), contati per impronta. */
  suspend fun bytes(): Map<Sha256, Int> = scan().files.filter { !it.appArea }.groupingBy { it.sha }.eachCount()

  /** Un giro di backup delle versioni come lo fa il banco: i candidati dal catalogo, gli scambi dei ritorni, poi i lavori aperti. */
  suspend fun runVersions(s: FiveBServices) {
    s.versions.planPhoneVersions(fb.candidates())
    for (r in fb.registry.values.toList()) s.versions.planRealign(r.mediaId)
    s.versions.run(1.hours)
  }

  /** Lo stato del banco com'e' adesso, giudicato dal verificatore come a meta' o a lavoro finito. */
  suspend fun verdict(phase: Phase): List<Violation> {
    val scan = scan()
    val cited = (fb.journal.citedTemps() + fb.guardStore.citedTemps()).mapTo(LinkedHashSet()) { it.value }
    return FiveBVerifier.check(fb.capture(scan, emptyList(), cited), phase)
  }

  suspend fun copies() = fb.catalog.media(mediaId)!!.versions.flatMap { v -> v.nas.map { v to it } }
}

/**
 * Lo scenario fino alla finestra: lo scambio fermo dopo la rinomina, il PC che modifica il file riportato al solito percorso (con [keepSize] della
 * stessa dimensione, altrimenti di un'altra), il lavoro che riprende in un processo nuovo.
 */
internal suspend fun staleWindow(root: File, keepSize: Boolean): StaleScene {
  val s = StaleScene(root)
  val rec = s.fb.seed(FiveBCategory.EDITED_SAFE)
  s.rec = rec
  s.usual = rec.usualPath!!
  val first = s.scan()
  first.files.forEach { s.fb.before[it.key] = it }
  s.fb.knownShas += first.files.filter { !it.appArea }.map { it.sha }
  // Samsung "Ripristina originale": la riga del telefono torna all'originale, la lettura registra il ritorno, parte lo scambio.
  assertThat(s.fb.samsungRevert(rec)).isTrue()
  s.fb.rescan()
  val dying = DieAfterRestore(s.world.nas.ops)
  try {
    s.runVersions(s.services(dying))
    throw AssertionError("il processo doveva morire dopo la rinomina del ripristino")
  } catch (_: ChaosDeath) {
    s.world.nas.processDied()
  }
  assertThat(s.fb.journal.all().single().state).isEqualTo(UploadState.RESTORING)
  // Il PC modifica il file appena riportato al solito percorso: byte nuovi, stesso `fileId`, data nuova.
  val size = s.fb.stampOf(s.world.share, s.usual)!!.size.toInt()
  assertThat(s.fb.pcEdit(s.world.share, s.usual, exactSize = if (keepSize) size else size / 2)).isTrue()
  // Un processo nuovo riprende il lavoro.
  s.runVersions(s.services())
  return s
}
