package dev.pampa.mycloudgallery.core.backup.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupItem
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.testing.BackupTestKit
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Il falso positivo dei verificatori degli invarianti segnalato da P2: un media puo' avere piu' elementi nel diario (un elemento fallito
 * e, dopo "Riprova", uno nuovo che riesce). L'invariante 7 ("nessun 'al sicuro' per un elemento FAILED") e il 9 ("nessun elemento fermo")
 * guardano l'ULTIMO elemento del media, non uno qualsiasi.
 */
class BackupInvariantsRetryTest {

  private val kit = BackupTestKit()

  private suspend fun failedItemFor(media: dev.pampa.mycloudgallery.core.model.MediaId): BackupItem {
    val candidate = kit.planner.next(BackupKind.PHOTOS, 10).single { it.mediaId == media }
    val created = kit.store.create(
      listOf(
        NewBackupItem(
          kind = UploadKind.NEW_COPY, mediaId = candidate.mediaId, versionId = candidate.versionId, size = candidate.size,
          phone = candidate.phone, expected = candidate.stamp, share = kit.share, planned = kit.planner.destination(candidate, kit.folder!!),
          quickHash = candidate.quickHash,
        ),
      ),
    ).single()
    val failed = created.copy(state = UploadState.FAILED, problem = BackupProblemCode.VERIFY_FAILED)
    kit.store.update(failed)
    return failed
  }

  @Test
  fun unFalloSuperatoDaUnElementoRiuscitoNonViolaIlSettimoNeIlNono() = runTest {
    val media = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(Random(1).nextBytes(30_000)))
    val before = kit.snapshot()
    // Il primo tentativo e' fallito; l'utente tocca "Riprova" (il segno si toglie) e il backup rifa' l'elemento.
    failedItemFor(media)
    kit.catalog.markProblem(media, null)
    kit.runAll()
    assertThat(kit.catalog.isSafe(media)).isTrue()
    assertThat(kit.store.all().map { it.state }).containsExactly(UploadState.FAILED, UploadState.DONE).inOrder()
    assertWithMessage("invarianti dopo il riprova").that(kit.violations(before, final = true)).isEmpty()
  }

  @Test
  fun unFalloUltimoElementoDiUnMediaAlSicuroResta7E9() = runTest {
    val media = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(Random(2).nextBytes(30_000)))
    val before = kit.snapshot()
    kit.runAll()
    assertThat(kit.catalog.isSafe(media)).isTrue()
    // Un elemento nuovo e fallito DOPO l'elemento riuscito, con il media al sicuro: questo si' che e' un "al sicuro per un FAILED".
    val done = kit.store.all().single()
    val created = kit.store.create(
      listOf(
        NewBackupItem(
          kind = UploadKind.NEW_COPY, mediaId = done.mediaId, versionId = done.versionId, size = done.size, phone = done.phone,
          expected = done.expected, share = kit.share, planned = done.planned,
        ),
      ),
    ).single()
    kit.store.update(created.copy(state = UploadState.FAILED, problem = BackupProblemCode.VERIFY_FAILED))
    val violations = kit.violations(before, final = true)
    assertThat(violations.filter { it.startsWith("7:") }).hasSize(1)
    assertThat(violations.filter { it.startsWith("9:") }).hasSize(1)
  }
}
