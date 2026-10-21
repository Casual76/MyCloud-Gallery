package dev.pampa.mycloudgallery.core.backup.versions

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupEngine
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupRunResult
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.noop.NoBackupEngine
import dev.pampa.mycloudgallery.core.backup.noop.NoBackupPlanner
import dev.pampa.mycloudgallery.core.backup.upload.StagedBackupEngine
import dev.pampa.mycloudgallery.core.backup.upload.UploadControl
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **O-8** (prove sul campo, `docs/prove-campo-esiti.md`, "Blocco A, resto"): dopo una caduta del Wi-Fi la creazione del temporaneo di una versione
 * rispondeva `Exists`, perche' il NAS teneva ancora occupato quello della connessione caduta; il giro finiva `complete` con "aperti=1" e il lavoro
 * dell'utente `DONE`, e la versione restava "in coda · Al prossimo giro" finche' il lavoro periodico delle foto la chiudeva, **17 minuti dopo**.
 *
 * E' lo stesso schema di D-13 (lavoro rimandato con un'attesa lunga): il motore del caricamento dice `BUSY` per un file occupato e chi guida il
 * lavoro riprova presto; il motore delle versioni invece aspettava in silenzio (`Step.Wait()`, nessun `BUSY`). Adesso un lavoro che esce dal giro
 * ad aspettare un file occupato sul NAS lo rende `BUSY`, con le attese crescenti nello stesso giro come per il caricamento.
 */
class ProveCampoO8VersioniTest {

  @get:Rule val folder = TemporaryFolder()

  private fun world() = VersionWorld(folder.newFolder())

  private val share = VersionWorld.PRIVATE_SHARE

  /**
   * Una versione il cui temporaneo e' rimasto sul NAS dopo una caduta della connessione: la prima scrittura cade (il file e' piccolo, nessun punto di
   * ripresa) o la [dropAt]-esima (con un punto di ripresa salvato).
   */
  private suspend fun VersionWorld.droppedUpload(dropAt: Int): Triple<VersionWorld.Seeded, VersionJob, VersionWorld.Snapshot> {
    val seed = seedSafe()
    val before = snapshot()
    editOnPhone(seed)
    val job = engine.planPhoneVersions(listOf(candidate(seed.mediaId, seed.phoneId!!))).single()
    nas.opsFaults = FaultPlan().at(dropAt, FaultPhase.AFTER, SimFault.DropConnection, op = SimOp.WRITE)
    engine.run(1.hours)
    nas.opsFaults = null
    val stuck = checkNotNull(journal.job(job.id))
    check(stuck.state == UploadState.UPLOADING && stuck.temp != null) { "nessun temporaneo rimasto: ${stuck.state}" }
    return Triple(seed, stuck, before)
  }

  /** (a) Il caso del campo: nessun punto di ripresa, il temporaneo c'e' gia' (`Exists`) e il NAS lo tiene occupato. Il giro non e' "completo". */
  @Test
  fun o8_unTemporaneoOccupatoSenzaPuntoDiRipresaNonChiudeIlGiroComeCompleto() = runTest {
    val w = world()
    val (seed, stuck, before) = w.droppedUpload(dropAt = 1)
    assertThat(stuck.resumeAt).isEqualTo(0)
    val temp = checkNotNull(stuck.temp)
    w.nas.lock(share, temp.value)
    w.restart()
    val started = w.clock.monotonic

    val busy = w.engine.run(1.hours)

    assertThat(busy.stoppedBy).isEqualTo(RunStop.BUSY)
    assertThat(busy.waiting).isEqualTo(1)
    assertThat(busy.done).isEqualTo(0)
    assertThat(busy.failed).isEqualTo(0)
    // Attese crescenti nello stesso giro (10, 20 e 40 ms nel banco), poi il prossimo giro: il lavoro resta com'era, niente toccato sul NAS.
    assertThat(w.clock.monotonic - started).isEqualTo(10L + 20L + 40L)
    assertThat(w.journal.job(stuck.id)!!.state).isEqualTo(UploadState.UPLOADING)
    assertThat(w.nas.exists(share, temp.value)).isTrue()
    assertThat(w.sha(share, seed.usualPath.value)).isEqualTo(Sha256.of(seed.content))

    // Il NAS si accorge della connessione caduta e lascia il temporaneo: il giro dopo porta la versione in fondo, con gli invarianti intatti.
    w.nas.unlock(share, temp.value)
    val done = w.engine.run(1.hours)
    assertThat(done.stoppedBy).isEqualTo(RunStop.COMPLETE)
    assertThat(done.done).isEqualTo(1)
    assertThat(w.journal.job(stuck.id)!!.state).isEqualTo(UploadState.DONE)
    // La versione nuova (i byte che il telefono ha adesso) e' al solito percorso.
    assertThat(w.sha(share, seed.usualPath.value)).isEqualTo(w.phone.sha256(seed.phoneId!!))
    assertThat(w.violations(before, final = true)).isEmpty()
  }

  /** (b) Lo stesso con un punto di ripresa salvato: la riapertura risponde "occupato" ed e' lo stesso `BUSY` (prima: attese e poi `complete`). */
  @Test
  fun o8_unTemporaneoOccupatoConUnPuntoDiRipresaNonChiudeIlGiroComeCompleto() = runTest {
    val w = world()
    val (_, stuck, before) = w.droppedUpload(dropAt = 5)
    assertThat(stuck.resumeAt).isGreaterThan(0)
    val temp = checkNotNull(stuck.temp)
    w.nas.lock(share, temp.value)
    w.restart()

    val busy = w.engine.run(1.hours)

    assertThat(busy.stoppedBy).isEqualTo(RunStop.BUSY)
    assertThat(busy.waiting).isEqualTo(1)
    assertThat(w.journal.job(stuck.id)!!.state).isEqualTo(UploadState.UPLOADING)
    assertThat(w.journal.job(stuck.id)!!.resumeAt).isEqualTo(stuck.resumeAt)

    w.nas.unlock(share, temp.value)
    assertThat(w.engine.run(1.hours).stoppedBy).isEqualTo(RunStop.COMPLETE)
    assertThat(w.journal.job(stuck.id)!!.state).isEqualTo(UploadState.DONE)
    assertThat(w.violations(before, final = true)).isEmpty()
  }

  /** (c) Un giro senza file occupati resta `COMPLETE`: il segnale non scatta a vuoto. */
  @Test
  fun o8_senzaFileOccupatiIlGiroResteCompleto() = runTest {
    val w = world()
    val seed = w.seedSafe()
    w.editOnPhone(seed)
    w.engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, seed.phoneId!!)))
    val result = w.engine.run(1.hours)
    assertThat(result.stoppedBy).isEqualTo(RunStop.COMPLETE)
    assertThat(result.done).isEqualTo(1)
    assertThat(w.engine.run(1.hours).stoppedBy).isEqualTo(RunStop.COMPLETE)
  }

  /**
   * (d) Nel giro del backup (versioni e poi caricamento) una versione occupata non ferma il caricamento e rende il giro `BUSY`: chi guida il lavoro
   * (`BackupFollowUp.Busy`) lo riprova presto invece di aspettare il lavoro periodico.
   */
  @Test
  fun o8_nelGiroDelBackupUnaVersioneOccupataNonFermaIlCaricamentoEIlGiroENonCompleto() = runTest {
    val w = world()
    val (_, stuck, _) = w.droppedUpload(dropAt = 1)
    w.nas.lock(share, checkNotNull(stuck.temp).value)
    w.restart()
    val upload = CountingUpload()
    val engine = VersionedBackupEngine(upload, w.engine, NoBackupPlanner, w.clock)

    val busy = engine.run(BackupKind.PHOTOS, 1.hours, BackupTrigger.SCHEDULED)

    assertThat(upload.runs).isEqualTo(1)
    assertThat(busy.stoppedBy).isEqualTo(RunStop.BUSY)
    assertThat(busy.waiting).isEqualTo(1)

    w.nas.unlock(share, checkNotNull(stuck.temp).value)
    val done = engine.run(BackupKind.PHOTOS, 1.hours, BackupTrigger.SCHEDULED)
    assertThat(done.stoppedBy).isEqualTo(RunStop.COMPLETE)
    assertThat(done.done).isEqualTo(1)
    assertThat(upload.runs).isEqualTo(2)
  }

  /** Un caricamento che non fa niente e finisce `COMPLETE`: serve a vedere se il giro delle versioni lo lascia andare avanti. */
  private class CountingUpload : StagedBackupEngine, BackupEngine by NoBackupEngine {
    var runs = 0

    override suspend fun run(kind: BackupKind, budget: Duration, trigger: BackupTrigger, control: UploadControl): BackupRunResult {
      runs++
      return BackupRunResult.nothing(RunStop.COMPLETE)
    }

    override suspend fun resumeAll(budget: Duration, control: UploadControl): BackupRunResult = BackupRunResult.nothing(RunStop.COMPLETE)
  }
}
