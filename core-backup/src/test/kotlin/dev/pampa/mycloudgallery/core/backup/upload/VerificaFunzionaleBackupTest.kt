package dev.pampa.mycloudgallery.core.backup.upload

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.contract.VerifiedCopy
import dev.pampa.mycloudgallery.core.backup.testing.BackupTestKit
import dev.pampa.mycloudgallery.core.backup.testing.ProcessDeath
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.okOrNull
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.rules.run.StageOutcome
import dev.pampa.mycloudgallery.core.rules.run.StagedRun
import dev.pampa.mycloudgallery.core.rules.run.testing.InMemoryCheckpointStore
import dev.pampa.mycloudgallery.core.rules.run.testing.InMemoryTrafficLedger
import dev.pampa.mycloudgallery.core.rules.run.testing.ScriptedStageRules
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Le correzioni della verifica funzionale (`docs/review/funzionale-fasi-1-5a.md`, sezione 2.5) che toccano il **motore** del backup:
 * BK-1 ("Proteggi subito le foto nuove" porta avanti solo la foto nuova, non l'arretrato), BK-6 (la tappa cede il passo a un tocco
 * dell'utente) e la finestra di F2 nel motore della 5A (la copia appena pubblicata e poi spostata da un'altra via non si rifa').
 * Ogni scenario finisce con gli invarianti del §5.4 veri.
 */
class VerificaFunzionaleBackupTest {

  private fun bytes(n: Int, seed: Int) = ByteArray(n) { (it * 131 + seed * 7 + (it shr 8)).toByte() }

  // --- BK-1 -----------------------------------------------------------------------------------------------------------------------

  /**
   * Un primo backup a meta': venti foto vecchie sono gia' nel diario, ancora da caricare. Arriva una foto nuova e parte la corsa "Proteggi
   * subito": con un budget per un file solo, e' **la foto nuova** che deve arrivare al sicuro. Prima il motore guidava tutti gli elementi aperti
   * in ordine di `item_id`: caricava per otto minuti l'arretrato vecchio e la foto nuova aspettava la notte.
   */
  @Test
  fun bk1_laCorsaRapidaPortaAlSicuroLaFotoNuovaENonLArretrato() = runTest {
    val h = RealEngineKit(parallel = 1)
    val kit = h.kit
    val old = (1..20).map { kit.addPhoneMedia("OLD_$it.jpg", NasContent.of(bytes(8_000, it))) }
    // Il giro normale ha pianificato le venti e non ha fatto in tempo a caricarne nessuna (budget zero).
    h.engine.run(BackupKind.PHOTOS, Duration.ZERO, BackupTrigger.SCHEDULED)
    assertThat(kit.store.all().map { it.state }.toSet()).containsExactly(UploadState.PLANNED)
    assertThat(kit.store.all()).hasSize(20)
    val before = kit.snapshot()

    val fresh = kit.addPhoneMedia("IMG_NUOVA.jpg", NasContent.of(bytes(8_000, 99)))
    // Le regole lasciano caricare un file solo: poi la tappa si ferma (come il tempo, o una regola).
    val oneFile = object : UploadControl {
      override suspend fun proceed(): Boolean = kit.store.all().none { it.state == UploadState.DONE }
    }
    val result = h.engine.run(BackupKind.PHOTOS, 1.hours, BackupTrigger.QUICK_NEW_PHOTOS, oneFile)

    assertWithMessage("giro: $result").that(result.done).isEqualTo(1)
    assertThat(kit.catalog.isSafe(fresh)).isTrue()
    assertWithMessage("l'arretrato non si tocca").that(old.count { kit.catalog.isSafe(it) }).isEqualTo(0)
    val items = kit.store.all()
    assertThat(items.first { it.mediaId == fresh }.state).isEqualTo(UploadState.DONE)
    assertThat(items.filter { it.mediaId in old }.map { it.state }.toSet()).containsExactly(UploadState.PLANNED)
    assertWithMessage("invarianti del §5.4").that(kit.violations(before, final = false)).isEmpty()

    // L'arretrato lo riprende il giro normale, senza perdere niente.
    h.resumeUntilQuiet()
    assertThat(old.all { kit.catalog.isSafe(it) }).isTrue()
    assertWithMessage("invarianti del §5.4, alla fine").that(kit.violations(before, final = true)).isEmpty()
  }

  /** Lo stesso difetto sull'esecutore di riferimento (il banco gira su tutti e due): ora si comporta come il vero. */
  @Test
  fun bk1_ancheIlMotoreDiRiferimentoGuidaSoloLeFotoNuove() = runTest {
    val kit = BackupTestKit()
    val old = (1..5).map { kit.addPhoneMedia("OLD_$it.jpg", NasContent.of(bytes(6_000, it))) }
    kit.engine.run(BackupKind.PHOTOS, Duration.ZERO, BackupTrigger.SCHEDULED)
    assertThat(kit.store.all().map { it.state }.toSet()).containsExactly(UploadState.PLANNED)

    val fresh = kit.addPhoneMedia("IMG_NUOVA.jpg", NasContent.of(bytes(6_000, 98)))
    val result = kit.engine.run(BackupKind.PHOTOS, 1.hours, BackupTrigger.QUICK_NEW_PHOTOS)

    assertThat(result.done).isEqualTo(1)
    assertThat(kit.catalog.isSafe(fresh)).isTrue()
    assertThat(old.count { kit.catalog.isSafe(it) }).isEqualTo(0)
    assertThat(kit.store.all().filter { it.mediaId in old }.map { it.state }.toSet()).containsExactly(UploadState.PLANNED)
  }

  /** La corsa rapida non salta niente a chi la usa davvero: nessun arretrato, la foto nuova va, e il giro dice che e' finito. */
  @Test
  fun bk1_senzaArretratoLaCorsaRapidaPortaAlSicuroLaFotoNuovaEFinisce() = runTest {
    val h = RealEngineKit()
    val fresh = h.kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(8_000, 1)))
    val result = h.engine.run(BackupKind.PHOTOS, 1.hours, BackupTrigger.QUICK_NEW_PHOTOS)
    assertThat(result.done).isEqualTo(1)
    assertThat(result.stoppedBy).isEqualTo(RunStop.COMPLETE)
    assertThat(h.kit.catalog.isSafe(fresh)).isTrue()
  }

  // --- BK-6 -----------------------------------------------------------------------------------------------------------------------

  /**
   * L'utente tocca "Inizia ora" mentre gira una tappa di sfondo: la tappa **cede il passo** (al passo dopo, come a tempo scaduto) invece di
   * tenere il turno per i suoi otto minuti. Nessun file resta a meta': il punto di ripresa e' nel diario.
   */
  @Test
  fun bk6_unaTappaDiSfondoCedeIlPassoAChiAspetta() = runTest {
    val h = RealEngineKit(parallel = 1)
    val kit = h.kit
    (1..6).forEach { kit.addPhoneMedia("IMG_$it.jpg", NasContent.of(bytes(8_000, it))) }
    val before = kit.snapshot()
    var waiting = false
    val rules = ScriptedStageRules { RuleDecision.Run(network = NetworkClass.HOME_WIFI) }
    val staged = StagedRun(WorkRequest.Background(BackgroundActivity.PHOTO_BACKUP, writesToNas = true), rules, InMemoryTrafficLedger(), InMemoryCheckpointStore<Unit>())
    // L'utente comincia ad aspettare dopo il primo file finito.
    val outcome = UploadStages.stage(
      staged, h.engine, BackupKind.PHOTOS, BackupTrigger.SCHEDULED,
      yieldTo = {
        if (kit.store.all().any { it.state == UploadState.DONE }) waiting = true
        waiting
      },
    )

    assertThat(outcome.stage).isInstanceOf(StageOutcome.Finished::class.java)
    assertThat(outcome.busy).isFalse()
    val states = kit.store.all().map { it.state }
    assertWithMessage("alcuni file fatti, altri lasciati: $states").that(states.count { it == UploadState.DONE }).isAtLeast(1)
    assertThat(states.count { it == UploadState.DONE }).isLessThan(6)
    assertThat(states.none { it == UploadState.FAILED }).isTrue()
    assertWithMessage("invarianti del §5.4").that(kit.violations(before, final = false)).isEmpty()
    // Chi ha aspettato riprende il resto dal diario, senza perdere niente e senza doppioni.
    h.resumeUntilQuiet()
    assertWithMessage("invarianti del §5.4, alla fine").that(kit.violations(before, final = true)).isEmpty()
  }

  /** Senza nessuno che aspetta la tappa lavora per tutto il suo tempo, come prima. */
  @Test
  fun bk6_senzaNessunoCheAspettaLaTappaNonCedeIlPasso() = runTest {
    val h = RealEngineKit(parallel = 1)
    (1..4).forEach { h.kit.addPhoneMedia("IMG_$it.jpg", NasContent.of(bytes(8_000, it))) }
    val rules = ScriptedStageRules { RuleDecision.Run(network = NetworkClass.HOME_WIFI) }
    val staged = StagedRun(WorkRequest.Background(BackgroundActivity.PHOTO_BACKUP, writesToNas = true), rules, InMemoryTrafficLedger(), InMemoryCheckpointStore<Unit>())
    val outcome = UploadStages.stage(staged, h.engine, BackupKind.PHOTOS, BackupTrigger.SCHEDULED, yieldTo = { false })
    assertThat(outcome.last!!.done).isEqualTo(4)
    assertThat(outcome.last!!.stoppedBy).isEqualTo(RunStop.COMPLETE)
  }

  // --- La finestra di F2 nel motore della 5A ------------------------------------------------------------------------------------

  /**
   * Il processo muore fra la pubblicazione e il collegamento (il file e' sul NAS, il catalogo non lo sa); prima che ritorni, un'altra strada
   * sposta la copia appena pubblicata (una versione che la porta in `Versioni/`, "Cartelle NAS", il PC) e la scansione la fa conoscere al
   * catalogo. Il motore riprende: non la rifa' (due copie degli stessi byte nell'area privata), la cerca per byte (la deduplica, riletta qui) e
   * la collega dov'e'. Lo stesso schema del guardiano (F2).
   */
  @Test
  fun f2_laCopiaPubblicataESpostataDaUnAltraViaNonSiRifa() = runTest {
    val h = RealEngineKit(parallel = 1)
    val kit = h.kit
    val data = bytes(40_000, 3)
    val media = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(data))
    val before = kit.snapshot()

    val dying = object : BackupCatalog by kit.catalog {
      override suspend fun attachVerifiedCopies(copies: List<VerifiedCopy>): Int = throw ProcessDeath()
    }
    val death = runCatching { h.newEngine(catalog = dying).run(BackupKind.PHOTOS, 1.hours, BackupTrigger.SCHEDULED) }.exceptionOrNull()
    assertThat(death).isInstanceOf(ProcessDeath::class.java)
    val published = kit.store.all().single()
    assertThat(published.state).isEqualTo(UploadState.PUBLISHED)
    val final = published.finalPath!!
    assertThat(kit.nas.exists(kit.share, final.value)).isTrue()

    // Un'altra strada la sposta; la scansione la fa conoscere al catalogo (non verificata: l'ha vista, non riletta).
    val movedPath = "Backup/Altrove/IMG_1 (spostata).jpg"
    kit.nas.rename(kit.share, final.value, movedPath)
    val seen = checkNotNull(kit.nas.reader.stat(kit.share, SharePath.parse(movedPath), Purpose.BACKGROUND).okOrNull())
    kit.catalog.putCopy(media, published.versionId, kit.share, SharePath.parse(movedPath), NasStamp(seen.size, seen.modifiedAt, checkNotNull(seen.fileId)))

    h.restart()
    val results = h.runAll()

    val after = kit.store.all().single()
    assertThat(after.state).isEqualTo(UploadState.DONE)
    assertThat(after.finalPath).isEqualTo(SharePath.parse(movedPath))
    assertWithMessage("nessun doppione: i byte del telefono stanno in un posto solo")
      .that(kit.nas.filePaths(kit.share).filter { it.startsWith("Backup/") }).containsExactly(movedPath)
    assertThat(results.sumOf { it.bytesUploaded }).isEqualTo(0L)
    assertThat(kit.catalog.isSafe(media)).isTrue()
    // "Al sicuro" da una rilettura fatta qui: la copia verificata e' quella spostata, con i byte del telefono.
    assertThat(kit.catalog.copiesOf(media).single { it.verifiedAtMillis != null }.path.value).isEqualTo(movedPath)
    assertThat(kit.nas.bytes(kit.share, movedPath)!!.toList()).isEqualTo(data.toList())
    assertThat(kit.nas.filePaths(kit.share).filter { "-bk-" in it }).isEmpty()
    assertWithMessage("invarianti del §5.4").that(kit.violations(before, final = true)).isEmpty()
  }

  /** Se la copia spostata non c'e' (o il catalogo non la conosce ancora), il motore ricarica come sempre: nessun "al sicuro" senza una rilettura. */
  @Test
  fun f2_senzaUnaCopiaIdenticaSiRicaricaComeSempre() = runTest {
    val h = RealEngineKit(parallel = 1)
    val kit = h.kit
    val media = kit.addPhoneMedia("IMG_1.jpg", NasContent.of(bytes(40_000, 4)))
    val dying = object : BackupCatalog by kit.catalog {
      override suspend fun attachVerifiedCopies(copies: List<VerifiedCopy>): Int = throw ProcessDeath()
    }
    runCatching { h.newEngine(catalog = dying).run(BackupKind.PHOTOS, 1.hours, BackupTrigger.SCHEDULED) }
    val final = kit.store.all().single().finalPath!!
    // Il PC la sposta, ma la scansione non l'ha ancora vista: il catalogo non sa dov'e'.
    kit.nas.rename(kit.share, final.value, "Backup/Altrove/IMG_1.jpg")

    h.restart()
    h.resumeUntilQuiet()

    assertThat(kit.store.all().single().state).isEqualTo(UploadState.DONE)
    assertThat(kit.catalog.isSafe(media)).isTrue()
  }
}
