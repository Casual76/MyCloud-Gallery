package dev.pampa.mycloudgallery.core.backup.chaos

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupEngine
import dev.pampa.mycloudgallery.core.backup.contract.BackupItem
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * F1 (docs/prova-500.md §B6), corretto in entrambi i motori del banco. Un elemento aperto ha l'`_ID` di una versione vecchia del MediaStore
 * (E-P12: la riga e' `Stale`) e il motore chiede la riga al catalogo (`BackupCatalog.phoneRowOf`):
 * - la foto e' stata cancellata e la lettura del telefono ha riletto tutto: `GONE` con "persa prima del backup" (prima restava aperto per
 *   sempre, con il suo temporaneo);
 * - catalogo in ritardo (non ha ancora visto il cambio: risponde la riga vecchia) o lettura in corso (ha gia' la versione nuova ma non e'
 *   ancora arrivata alla riga: il catalogo vero risponde null anche se la foto c'e'): si aspetta, nessun `GONE` in anticipo e nessuna
 *   "foto persa" finta; quando la lettura ritrova la riga, il lavoro arriva in fondo.
 * A lavoro finito il verificatore del banco non dice niente: non c'e' piu' un difetto noto da dichiarare.
 */
class BackupStaleRowTest {

  private val stacks = listOf(ReferenceStack, ProductionStack)

  /** Un mondo con una foto e il suo elemento `PLANNED` nel diario (il processo e' morto prima di leggerla), e il motore di uno stack. */
  private class Scene(
    val stack: BackupChaosStackFactory,
    val world: BackupChaosWorld,
    val tracked: TrackedMedia,
    val scanner: BackupNasScanner,
    val before: List<NasFileFact>,
    val engine: BackupEngine,
  )

  private suspend fun scene(stack: BackupChaosStackFactory, block: suspend Scene.() -> Unit) {
    val world = FakeNasTarget().openWorld(0)
    try {
      val tracked = world.addPhoneMedia("IMG_1.jpg", NasContent.of(Random(7).nextBytes(20_000)), MediaKind.PHOTO, 20261012)
      val scanner = BackupNasScanner.ofShares(world.nas.reader, world.nas.shares)
      val before = scanner.scan().files
      val candidate = world.planner.next(BackupKind.PHOTOS, 10).single()
      world.store.create(
        listOf(
          NewBackupItem(
            kind = UploadKind.NEW_COPY, mediaId = candidate.mediaId, versionId = candidate.versionId, size = candidate.size, phone = candidate.phone,
            expected = candidate.stamp, share = world.share, planned = world.planner.destination(candidate, world.folder!!),
          ),
        ),
      )
      val env = EngineEnv(world, world.nas.ops, world.nas.reader, world.store, world.catalog, world.phone.port, Random(1))
      Scene(stack, world, tracked, scanner, before, stack.create(env)).block()
    } finally {
      world.close()
    }
  }

  private suspend fun Scene.work() = repeat(4) {
    engine.run(BackupKind.PHOTOS, 1.hours, BackupTrigger.SCHEDULED)
    engine.resumeAll(1.hours)
  }

  private fun Scene.item(): BackupItem = world.store.all().single()

  private suspend fun Scene.violations(): List<Violation> =
    BackupVerifier.check(world.capture(before, scanner.scan(allowCache = false), emptyList(), emptyList(), emptyList()), Phase.FINAL)

  /** Il MediaStore si ricostruisce: versione nuova e la riga della foto con un `_ID` nuovo. */
  private fun Scene.renumber() {
    val old = world.currentId(tracked)!!
    val fresh = world.nextFreshId()
    tracked.ids += fresh
    world.phone.rebuild("v2", mapOf(old to fresh))
  }

  private fun Scene.expect(what: String) = assertWithMessage("${stack.name}: $what")

  @Test
  fun f1_unaFotoCancellataMentreIlMediaStoreCambiaVersioneDiventaGoneSoloDopoLaRilettura(): Unit = runBlocking {
    for (stack in stacks) {
      scene(stack) {
        world.phone.delete(world.currentId(tracked)!!)
        world.phone.rebuild("v2")
        // La lettura del telefono ha cominciato a rileggere (il catalogo non conosce piu' la riga vecchia) ma non ha finito.
        world.catalog.phoneRereading = true
        world.catalog.relinkPhone(tracked.mediaId, null)
        work()
        expect("lettura in corso: si aspetta").that(item().state).isEqualTo(UploadState.PLANNED)
        expect("lettura in corso: nessun segno").that(world.catalog.problemOf(tracked.mediaId)).isNull()
        // La lettura finisce: la riga non c'e' piu'.
        world.rescan()
        world.catalog.phoneRereading = false
        work()
        expect("riletto tutto: GONE").that(item().state).isEqualTo(UploadState.GONE)
        expect("persa prima del backup").that(item().problem).isEqualTo(BackupProblemCode.LOST_BEFORE_BACKUP)
        expect("segno sul media").that(world.catalog.problemOf(tracked.mediaId)).isEqualTo(BackupProblemCode.LOST_BEFORE_BACKUP)
        expect("verificatore del banco").that(violations()).isEmpty()
      }
    }
  }

  @Test
  fun catalogoInRitardo_laRigaVecchiaFaAspettareSenzaGonePoiSiFinisce(): Unit = runBlocking {
    for (stack in stacks) {
      scene(stack) {
        // La lettura del telefono non e' ancora passata dopo il cambio di versione: il catalogo da' ancora la riga vecchia.
        renumber()
        work()
        expect("catalogo in ritardo: si aspetta").that(item().state).isEqualTo(UploadState.PLANNED)
        expect("nessun problema").that(item().problem).isNull()
        expect("nessun segno").that(world.catalog.problemOf(tracked.mediaId)).isNull()
        world.rescan()
        work()
        expect("riga ritrovata: fatto").that(item().state).isEqualTo(UploadState.DONE)
        expect("al sicuro").that(world.catalog.isSafe(tracked.mediaId)).isTrue()
        expect("verificatore del banco").that(violations()).isEmpty()
      }
    }
  }

  @Test
  fun letturaDelTelefonoInCorso_unaFotoCheCeAncoraNonDiventaPersa(): Unit = runBlocking {
    for (stack in stacks) {
      scene(stack) {
        // Il catalogo vero ha gia' salvato la versione nuova del cursore e non e' ancora arrivato a questa riga: risponde null, la foto c'e'.
        renumber()
        world.catalog.phoneRereading = true
        world.catalog.relinkPhone(tracked.mediaId, null)
        work()
        expect("lettura in corso: si aspetta").that(item().state).isEqualTo(UploadState.PLANNED)
        expect("nessuna foto persa finta").that(world.catalog.problemOf(tracked.mediaId)).isNull()
        world.rescan()
        world.catalog.phoneRereading = false
        work()
        expect("riga ritrovata: fatto").that(item().state).isEqualTo(UploadState.DONE)
        expect("al sicuro").that(world.catalog.isSafe(tracked.mediaId)).isTrue()
        expect("verificatore del banco").that(violations()).isEmpty()
      }
    }
  }
}
