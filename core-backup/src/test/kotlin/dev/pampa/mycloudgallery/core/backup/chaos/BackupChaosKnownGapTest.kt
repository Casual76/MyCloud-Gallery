package dev.pampa.mycloudgallery.core.backup.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
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
 * F1 (docs/prova-500.md §B6): il difetto noto del motore che il banco ha trovato. Un elemento del diario per una foto che poi sparisce dal
 * telefono, mentre la versione del MediaStore cambia (`_ID` rinumerati, E-P12): la riga vecchia e' `Stale`, il catalogo non conosce piu' la riga
 * (la lettura del telefono l'ha tolta) e `phoneRefOf` risponde null. Il motore (`refreshPhone`: "la riga nuova dal catalogo, o si aspetta che la
 * lettura del telefono la ritrovi") aspetta; se la foto e' stata cancellata la lettura non la ritrovera' mai, e l'elemento resta `PLANNED` per sempre
 * invece di diventare `GONE` ("persa prima del backup", E-B4). Senza il cambio di versione il motore risponde bene (`Gone` → `GONE`).
 *
 * Questo test non asserisce il difetto: riproduce lo scenario e controlla che il banco lo **classifichi** giusto, qualunque cosa faccia il motore.
 * Se il motore porta l'elemento a `GONE` (difetto corretto) il verificatore non dice niente; se lo lascia aperto lo dichiara come difetto noto
 * [Invariant.KNOWN_GAP], mai come un invariante violato ne' in silenzio. Quando il difetto e' corretto in entrambi i motori si tolgono
 * `ItemFact.waitsForRow` e questo test (la pista e' scritta in docs/prova-500.md).
 */
class BackupChaosKnownGapTest {

  private fun scenario(stack: BackupChaosStackFactory): Pair<Boolean, List<Violation>> = runBlocking {
    val world = FakeNasTarget().openWorld(0)
    try {
      val t = world.addPhoneMedia("IMG_1.jpg", NasContent.of(Random(7).nextBytes(20_000)), MediaKind.PHOTO, 20261012)
      val scanner = BackupNasScanner.ofShares(world.nas.reader, world.nas.shares)
      val before = scanner.scan().files
      // Il backup ha pianificato la foto e il processo e' morto prima di leggerla: un elemento PLANNED nel diario.
      val candidate = world.planner.next(BackupKind.PHOTOS, 10).single()
      world.store.create(
        listOf(
          NewBackupItem(
            kind = UploadKind.NEW_COPY, mediaId = candidate.mediaId, versionId = candidate.versionId, size = candidate.size, phone = candidate.phone,
            expected = candidate.stamp, share = world.share, planned = world.planner.destination(candidate, world.folder!!),
          ),
        ),
      )
      // La foto viene cancellata e il MediaStore si ricostruisce; la lettura del telefono vede che la riga non c'e' piu'.
      world.phone.delete(world.currentId(t)!!)
      world.phone.rebuild("v2")
      world.rescan()
      val env = EngineEnv(world, world.nas.ops, world.nas.reader, world.store, world.catalog, world.phone.port, Random(1))
      val engine = stack.create(env)
      repeat(4) {
        engine.run(BackupKind.PHOTOS, 1.hours, BackupTrigger.SCHEDULED)
        engine.resumeAll(1.hours)
      }
      val stuck = world.store.all().any { !it.state.terminal }
      val state = world.capture(before, scanner.scan(allowCache = false), emptyList(), emptyList(), emptyList())
      stuck to BackupVerifier.check(state, Phase.FINAL)
    } finally {
      world.close()
    }
  }

  @Test
  fun unElementoFermoPerUnaRigaCheIlCatalogoNonConoscePiuELoDichiaratoNonUnaViolazioneNeSilenzio() {
    for (stack in listOf(ReferenceStack, ProductionStack)) {
      val (stuck, violations) = scenario(stack)
      if (stuck) {
        assertWithMessage("${stack.name}: elemento fermo, violazioni $violations").that(violations.map { it.invariant }).containsExactly(Invariant.KNOWN_GAP)
        println("${stack.name}: F1 ancora presente (l'elemento resta PLANNED), dichiarato come difetto noto")
      } else {
        assertWithMessage("${stack.name}: elemento portato a GONE, violazioni $violations").that(violations).isEmpty()
        println("${stack.name}: F1 corretto (l'elemento diventa GONE): togliere il difetto noto dal banco")
      }
      assertThat(UploadState.GONE.terminal).isTrue()
    }
  }
}
