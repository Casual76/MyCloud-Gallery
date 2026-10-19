package dev.pampa.mycloudgallery.core.backup.safety

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.GuardScope
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.contract.VerifiedCopy
import dev.pampa.mycloudgallery.core.backup.contract.VerifyScope
import dev.pampa.mycloudgallery.core.backup.testing.BackupInvariants
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Le correzioni della revisione di sicurezza della 5A (`docs/review/5a-fase5a.md`) che toccano il **guardiano** e le **verifiche**: R1 (il
 * nome "nome (n)" oltre i 255 byte scelto da chi ha messo il file nell'area comune, I2, e la difesa in profondita'), R4 (la pausa ferma chi
 * scrive e non chi legge) e R7 (l'elemento di un'altra share).
 */
class Review5aSafetyTest {

  private val bench = SafetyBench()
  private val kit = bench.kit

  private suspend fun assertClean(before: BackupInvariants.Snapshot) {
    assertWithMessage("invarianti del §5.4").that(kit.violations(before, final = true)).isEmpty()
  }

  private fun utf8(text: String): Int = text.toByteArray(Charsets.UTF_8).size

  private fun nameOf(total: Int, filler: String, extension: String): String {
    val sb = StringBuilder()
    while (utf8(sb.toString()) + utf8(filler) <= total - utf8(extension)) sb.append(filler)
    while (utf8(sb.toString()) < total - utf8(extension)) sb.append('a')
    val name = sb.toString() + extension
    check(utf8(name) == total) { "il nome di prova non e' di $total byte" }
    return name
  }

  private val longNames = listOf(nameOf(252, "あ", ".jpg"), nameOf(253, "😀", ".jpg"), nameOf(254, "é", ".jpg"), nameOf(255, "日本語", ".jpg"))

  // --- R1: il nome occupato con un nome da 252-255 byte (I2: lo sceglie chi ha messo il file nell'area comune) ---------------------

  /**
   * La revisione: un familiare mette nell'area comune un file con un nome di 252-255 byte; nella cartella delle copie del guardiano c'e'
   * gia' un file **diverso** con quel nome. "nome (2)" non stava piu' in 255 byte: `TrustViolation`, 3 giri su 3 morti, il secondo media
   * mai al sicuro e nessuna verifica. Adesso la radice si accorcia a un confine di carattere e gli altri media arrivano al sicuro.
   */
  @Test
  fun r1_unNomeOccupatoDa252A255ByteDiventaNomeDueEGliAltriArrivanoAlSicuro() = runTest {
    for (name in longNames) {
      val b = SafetyBench()
      val long = b.commonOnly(name, SafetyBench.bytes(30_000, 1))
      val other = b.commonOnly("IMG_2.jpg", SafetyBench.bytes(20_000, 2))
      b.putFile(b.share, "${b.guardDir()}/$name", SafetyBench.bytes(31_000, 3))
      val before = b.kit.snapshot()

      // I tre giri della revisione: nessuno deve morire.
      repeat(3) { b.guardian().ensure(GuardScope.All, 1.hours) }

      assertWithMessage(name).that(b.kit.catalog.isSafe(long.mediaId)).isTrue()
      assertThat(b.kit.catalog.isSafe(other.mediaId)).isTrue()
      val item = b.guardItems().single { it.mediaId == long.mediaId }
      assertThat(item.state).isEqualTo(UploadState.DONE)
      val final = item.finalPath!!
      assertThat(utf8(final.name)).isAtMost(255)
      assertThat(final.name).endsWith(" (2).jpg")
      assertThat(final.parent()!!.value).isEqualTo(b.guardDir())
      // Il file diverso che c'era non si tocca; la copia ha i byte della sorgente.
      assertThat(b.kit.nas.bytes(b.share, "${b.guardDir()}/$name")!!.toList()).isEqualTo(SafetyBench.bytes(31_000, 3).toList())
      assertThat(b.kit.nas.bytes(b.share, final.value)!!.toList()).isEqualTo(long.data.toList())
      assertWithMessage("invarianti del §5.4").that(b.kit.violations(before, final = true)).isEmpty()
    }
  }

  /** Un guardiano che ha parcheggiato un media non toglie le verifiche: il verificatore lavora sullo stesso catalogo e sullo stesso diario. */
  @Test
  fun r1_leVerificheGiranoAncheDopoUnGuardianoCheHaParcheggiatoUnMedia() = runTest {
    val copy = bench.withPrivateCopy("IMG_V.jpg", SafetyBench.bytes(20_000, 4), verifiedAt = null)
    val bad = bench.commonOnly("IMG_B.jpg", SafetyBench.bytes(21_000, 5))
    val throwing = catalogFailingAttach(bad.mediaId)
    val report = bench.guardian(catalog = throwing).ensure(GuardScope.All, 1.hours)
    assertThat(report.failed).isEqualTo(1)
    val verified = bench.verifier().verify(VerifyScope.Unverified, 1.hours)
    assertThat(verified.verified).isEqualTo(1)
    assertThat(kit.catalog.isSafe(copy.mediaId)).isTrue()
  }

  // --- R1: la difesa in profondita' ------------------------------------------------------------------------------------------------

  /** Un `attachVerifiedCopies` che lancia un'eccezione inattesa solo per un media (per esempio un diario che dice no a quel solo elemento). */
  private fun catalogFailingAttach(bad: MediaId, base: BackupCatalog = kit.catalog): BackupCatalog = object : BackupCatalog by base {
    override suspend fun attachVerifiedCopies(copies: List<VerifiedCopy>): Int =
      if (copies.any { it.mediaId == bad }) throw IllegalStateException("errore inatteso di prova") else base.attachVerifiedCopies(copies)
  }

  /**
   * Un'eccezione di un solo media: quell'elemento si parcheggia con `UNEXPECTED` (e il media porta il segno), il giro continua con gli
   * altri e il media parcheggiato non torna a ogni giro.
   */
  @Test
  fun r1_unErroreInattesoDiUnMediaLoParcheggiaEGliAltriArrivanoAlSicuro() = runTest {
    val bad = bench.commonOnly("IMG_1.jpg", SafetyBench.bytes(25_000, 6))
    val good1 = bench.commonOnly("IMG_2.jpg", SafetyBench.bytes(26_000, 7))
    val good2 = bench.commonOnly("IMG_3.jpg", SafetyBench.bytes(27_000, 8))
    val before = kit.snapshot()
    val throwing = catalogFailingAttach(bad.mediaId)

    val first = bench.guardian(catalog = throwing).ensure(GuardScope.All, 1.hours)

    assertThat(first.created).isEqualTo(2)
    assertThat(first.failed).isEqualTo(1)
    assertThat(first.stoppedBy).isEqualTo(RunStop.COMPLETE)
    assertThat(kit.catalog.isSafe(good1.mediaId) && kit.catalog.isSafe(good2.mediaId)).isTrue()
    assertThat(kit.catalog.isSafe(bad.mediaId)).isFalse()
    val parked = bench.guardItems().single { it.mediaId == bad.mediaId }
    assertThat(parked.state).isEqualTo(UploadState.FAILED)
    assertThat(parked.problem).isEqualTo(BackupProblemCode.UNEXPECTED)
    assertThat(kit.catalog.problemOf(bad.mediaId)).isEqualTo(BackupProblemCode.UNEXPECTED)
    // Non torna: il giro dopo salta il media gia' segnato (finche' l'utente non tocca "Riprova", che toglie il segno).
    val second = bench.guardian(catalog = throwing).ensure(GuardScope.All, 1.hours)
    assertThat(second.failed).isEqualTo(0)
    assertThat(bench.guardItems().count { it.mediaId == bad.mediaId }).isEqualTo(1)
    // Nessun temporaneo resta sul NAS per l'elemento parcheggiato.
    assertThat(kit.nas.filePaths(kit.share).filter { it.startsWith(".mycloudgallery/tmp") }).isEmpty()
    // L'elemento parcheggiato e' fermo per scelta (FAILED chiede l'utente): gli invarianti di sicurezza valgono lo stesso.
    assertWithMessage("invarianti del §5.4").that(kit.violations(before, final = false)).isEmpty()
  }

  // --- R4: la pausa ferma chi scrive, non chi legge ------------------------------------------------------------------------------

  @Test
  fun r4_inPausaIlGuardianoNonScriveMaLeVerificheContinuano() = runTest {
    val toVerify = bench.withPrivateCopy("IMG_V.jpg", SafetyBench.bytes(20_000, 9), verifiedAt = null)
    val common = bench.commonOnly("IMG_C.jpg", SafetyBench.bytes(21_000, 10))
    kit.gate.default = BackupGateState.PAUSED
    val before = kit.snapshot()

    val guard = bench.guardian().ensure(GuardScope.All, 1.hours)
    assertThat(guard.stoppedBy).isEqualTo(RunStop.STOPPED)
    assertThat(guard.created).isEqualTo(0)
    assertThat(bench.guardItems()).isEmpty()
    assertThat(kit.catalog.isSafe(common.mediaId)).isFalse()

    // Le verifiche leggono soltanto: la pausa non le ferma.
    val verify = bench.verifier().verify(VerifyScope.Unverified, 1.hours)
    assertThat(verify.stoppedBy).isEqualTo(RunStop.COMPLETE)
    assertThat(verify.verified).isEqualTo(1)
    assertThat(kit.catalog.isSafe(toVerify.mediaId)).isTrue()

    // "Riprendi": il guardiano torna a lavorare.
    kit.gate.default = BackupGateState.OPEN
    assertThat(bench.guardian().ensure(GuardScope.All, 1.hours).created).isEqualTo(1)
    assertClean(before)
  }

  // --- R7: l'elemento di un'altra share -----------------------------------------------------------------------------------------

  @Test
  fun r7_unElementoDelGuardianoDiUnAltraShareAspettaEGliAltriVannoAvanti() = runTest {
    val old = bench.commonOnly("IMG_OLD.jpg", SafetyBench.bytes(20_000, 11))
    val fresh = bench.commonOnly("IMG_NEW.jpg", SafetyBench.bytes(21_000, 12))
    val m = checkNotNull(kit.catalog.media(old.mediaId))
    val source = bench.catalog.copiesOf(old.mediaId).single()
    kit.store.create(
      listOf(
        NewBackupItem(
          kind = UploadKind.GUARD_COPY, mediaId = old.mediaId, versionId = old.versionId, size = m.size, phone = null, expected = null,
          share = "altra-area", planned = SharePath.parse("${bench.guardDir()}/IMG_OLD.jpg"), quickHash = m.quickHash, sha256 = m.fullHash,
          guardSource = dev.pampa.mycloudgallery.core.backup.contract.GuardSource(source.share, source.path, source.stamp),
        ),
      ),
    )
    val before = kit.snapshot()

    val report = bench.guardian().ensure(GuardScope.All, 1.hours)

    assertThat(report.stoppedBy).isEqualTo(RunStop.COMPLETE)
    assertThat(kit.catalog.isSafe(fresh.mediaId)).isTrue()
    assertThat(kit.catalog.isSafe(old.mediaId)).isFalse()
    assertThat(bench.guardItems().single { it.mediaId == old.mediaId }.state).isEqualTo(UploadState.PLANNED)
    assertThat(report.skipped).isAtLeast(1)
    assertThat(kit.violations(before, final = false)).isEmpty()
  }
}
