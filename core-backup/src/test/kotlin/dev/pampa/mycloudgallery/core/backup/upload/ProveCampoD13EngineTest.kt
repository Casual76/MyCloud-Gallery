package dev.pampa.mycloudgallery.core.backup.upload

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupItem
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.contract.WaitReason
import dev.pampa.mycloudgallery.core.backup.testing.BackupTestKit
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupPaths
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * **D-13** (prove sul campo, `docs/prove-campo-esiti.md`, "Riprova con `6c19a33`"): dopo 100 interruzioni due video restavano "in caricamento"
 * con il temporaneo occupato sul NAS, e ogni giro dei video finiva in 30 secondi "completo" senza fare niente ("fatti=0 · aperti=3 ·
 * stop=complete"); il lavoro si chiudeva e il backup aspettava sei ore.
 *
 * Misure sul WD (`docs/decisioni.md`, D-13): una connessione che muore con la rete su (RST o FIN) lascia il temporaneo in meno di un secondo;
 * una che muore con la rete gia' giu' (la morte durante una caduta del Wi-Fi) lo tiene occupato finche' il NAS non se ne accorge, cioe' per
 * ore. Qui il NAS finto tiene occupato il temporaneo come il WD ([InMemoryNasMedia.lock], che risponde "occupato" a riapertura, ripresa,
 * pubblicazione e cancellazione), sul motore vero ([RealEngineKit]).
 */
class ProveCampoD13EngineTest {

  private val size = 200 * 1024

  private fun bytes(n: Int, seed: Int) = ByteArray(n) { (it * 131 + seed * 7 + (it shr 8)).toByte() }

  /** Un video a meta' con il suo punto di ripresa (la connessione cade dopo la sesta scrittura), il temporaneo che resta sul NAS. */
  private suspend fun halfUploaded(h: RealEngineKit, data: ByteArray): Pair<MediaId, BackupItem> {
    val media = h.kit.addPhoneMedia("VID_1.mp4", NasContent.of(data), kind = MediaKind.VIDEO)
    h.kit.nas.opsFaults = FaultPlan().at(6, FaultPhase.AFTER, SimFault.DropConnection, op = SimOp.WRITE)
    h.runAll()
    h.kit.nas.opsFaults = null
    val item = h.kit.store.all().single()
    check(item.state == UploadState.UPLOADING && item.resumeAt > 0 && item.temp != null) { "nessun punto di ripresa: $item" }
    return media to item
  }

  private fun RealEngineKit.held(item: BackupItem) = kit.nas.lock(kit.share, checkNotNull(item.temp).value)

  private fun RealEngineKit.released(item: BackupItem) = kit.nas.unlock(kit.share, checkNotNull(item.temp).value)

  /** (a) Il proprio temporaneo occupato non chiude mai il giro come completo: il giro dice "occupato", l'elemento resta com'era. */
  @Test
  fun d13_unTemporaneoOccupatoNonChiudeIlGiroComeCompleto() = runTest {
    val h = RealEngineKit()
    val (_, item) = halfUploaded(h, bytes(size, 1))
    val before = h.kit.nas.bytes(h.kit.share, item.temp!!.value)!!.toList()
    h.held(item)
    h.restart()

    val result = h.engine.run(BackupKind.VIDEOS, 1.hours, BackupTrigger.SCHEDULED)

    assertThat(result.stoppedBy).isEqualTo(RunStop.BUSY)
    assertThat(result.waiting).isEqualTo(1)
    assertThat(h.engine.status.value).isEqualTo(BackupStatus.Waiting(1, 0, WaitReason.NAS_BUSY))
    // Attese crescenti nel giro (10, 20, 40 ms nel banco), poi il prossimo giro: niente toccato, niente pubblicato.
    assertThat(h.sleeps).containsExactly(10L, 20L, 40L).inOrder()
    assertThat(h.kit.store.all().single()).isEqualTo(item)
    assertThat(h.kit.nas.bytes(h.kit.share, item.temp!!.value)!!.toList()).isEqualTo(before)
    assertThat(h.kit.nas.filePaths(h.kit.share).filter { !it.startsWith(".mycloudgallery/") }).isEmpty()
  }

  /** (a) Mentre il motore aspetta un file occupato lo stato lo dice: non "Backup in corso" (la Libreria diceva "in corso: 0 di 3"). */
  @Test
  fun d13_duranteLAttesaLoStatoDiceOccupatoNonInCorso() = runTest {
    val h = RealEngineKit()
    val (_, item) = halfUploaded(h, bytes(size, 2))
    h.held(item)
    h.restart()
    val seen = ArrayList<BackupStatus>()
    h.sleepHook = { seen += h.engine.status.value }

    h.engine.run(BackupKind.VIDEOS, 1.hours, BackupTrigger.USER_NOW)

    assertThat(seen).hasSize(3)
    assertThat(seen.distinct()).containsExactly(BackupStatus.Waiting(1, 0, WaitReason.NAS_BUSY))
  }

  /**
   * (a) e punto 4: il NAS lascia il temporaneo prima del tetto (la connessione morta e' stata chiusa): la ripresa resta una ripresa. Si riparte dal
   * punto di ripresa (al piu' un punto di ripresa ricaricato, un solo accorciamento), un solo file pubblicato e verificato, nessun doppione, nessun
   * file a meta' visibile, nessun temporaneo rimasto.
   */
  @Test
  fun d13_seIlNasLasciaIlTemporaneoLaRipresaRestaUnaRipresa() = runTest {
    val h = RealEngineKit()
    val data = bytes(size, 3)
    val (media, item) = halfUploaded(h, data)
    h.held(item)
    h.restart()
    assertThat(h.engine.run(BackupKind.VIDEOS, 1.hours, BackupTrigger.SCHEDULED).stoppedBy).isEqualTo(RunStop.BUSY)
    // Il NAS si accorge della connessione morta dopo qualche minuto (sotto il tetto di dieci).
    h.kit.clock.advance(5 * 60_000L)
    h.released(item)
    val writtenBefore = h.kit.nas.backupBytesWritten.get()
    val truncationsBefore = h.kit.nas.backupTruncations.get()

    h.resumeUntilQuiet()

    val done = h.kit.store.all().single()
    assertThat(done.state).isEqualTo(UploadState.DONE)
    assertThat(done.temp).isEqualTo(item.temp)
    assertThat(h.kit.catalog.isSafe(media)).isTrue()
    assertThat(h.kit.nas.backupBytesWritten.get() - writtenBefore).isAtMost(size - item.resumeAt + h.kit.checkpointBytes)
    assertThat(h.kit.nas.backupTruncations.get() - truncationsBefore).isEqualTo(1)
    assertThat(h.kit.nas.filePaths(h.kit.share)).containsExactly("Backup/${BackupTestKit.DEVICE_FOLDER}/2026/10/VID_1.mp4")
    assertThat(h.kit.nas.bytes(h.kit.share, "Backup/${BackupTestKit.DEVICE_FOLDER}/2026/10/VID_1.mp4")!!.toList()).isEqualTo(data.toList())
  }

  /**
   * (c) Oltre il tetto (dieci minuti di ora del NAS dall'ultimo passo del diario) il motore riparte da capo su un temporaneo nuovo, il tentativo
   * dopo dello stesso elemento, scritto nel diario prima di crearlo. Il vecchio non si tocca (stessi byte), resta citato dal diario finche'
   * l'elemento e' aperto e lo toglie il motore quando il NAS lo lascia: prima l'elemento aspetta "collegato" (il media e' gia' al sicuro), poi
   * finisce. L'attesa del vecchio, che non serve piu' a niente, non fa dire "occupato" al giro (il WD lo tiene per ore: la pagina direbbe
   * "riprovo tra poco" e i lavori riproverebbero per niente). Mai un file che non sia un nostro temporaneo del backup.
   */
  @Test
  fun d13_oltreIlTettoRipartiSuUnTemporaneoNuovoETogliePoiIlVecchio() = runTest {
    val h = RealEngineKit()
    val data = bytes(size, 4)
    val (media, item) = halfUploaded(h, data)
    val old = item.temp!!
    val oldBytes = h.kit.nas.bytes(h.kit.share, old.value)!!.toList()
    h.held(item)
    h.restart()
    h.kit.clock.advance(11 * 60_000L)

    val result = h.engine.run(BackupKind.VIDEOS, 1.hours, BackupTrigger.SCHEDULED)

    val next = NasBackupPaths.nextAttempt(old)!!
    val linked = h.kit.store.all().single()
    assertThat(linked.temp).isEqualTo(next)
    assertThat(NasBackupPaths.attemptOf(next)).isEqualTo(1)
    // Il media e' al sicuro (ricaricato da capo sul temporaneo nuovo, riletto, pubblicato, collegato) ...
    assertThat(h.kit.catalog.isSafe(media)).isTrue()
    assertThat(h.kit.nas.bytes(h.kit.share, "Backup/${BackupTestKit.DEVICE_FOLDER}/2026/10/VID_1.mp4")!!.toList()).isEqualTo(data.toList())
    // ... e l'elemento resta aperto (al prossimo giro) finche' il vecchio temporaneo, intatto e citato, e' occupato. Non "occupato": niente da
    // riprovare presto, il media e' al sicuro.
    assertThat(linked.state).isEqualTo(UploadState.LINKED)
    assertThat(result.stoppedBy).isEqualTo(RunStop.COMPLETE)
    assertThat(result.waiting).isEqualTo(1)
    assertThat(h.engine.status.value).isEqualTo(BackupStatus.Waiting(1, 0, WaitReason.NEXT_RUN))
    assertThat(h.kit.nas.bytes(h.kit.share, old.value)!!.toList()).isEqualTo(oldBytes)
    assertThat(h.kit.store.citedTemps()).containsExactly(next, old)

    // Il NAS lascia il vecchio: il motore lo toglie e chiude l'elemento. Nessun temporaneo resta.
    h.kit.nas.unlock(h.kit.share, old.value)
    h.resumeUntilQuiet()
    assertThat(h.kit.store.all().single().state).isEqualTo(UploadState.DONE)
    assertThat(h.kit.store.citedTemps()).isEmpty()
    assertThat(h.kit.nas.filePaths(h.kit.share)).containsExactly("Backup/${BackupTestKit.DEVICE_FOLDER}/2026/10/VID_1.mp4")
  }

  /** (c) Il tetto conta dall'ultimo passo del diario: sotto i dieci minuti si aspetta e basta, nessun temporaneo nuovo. */
  @Test
  fun d13_sottoIlTettoNessunTemporaneoNuovo() = runTest {
    val h = RealEngineKit()
    val (_, item) = halfUploaded(h, bytes(size, 5))
    h.held(item)
    h.restart()
    h.kit.clock.advance(9 * 60_000L)

    val result = h.engine.run(BackupKind.VIDEOS, 1.hours, BackupTrigger.SCHEDULED)

    assertThat(result.stoppedBy).isEqualTo(RunStop.BUSY)
    assertThat(h.kit.store.all().single().temp).isEqualTo(item.temp)
  }

  /**
   * (c), L38: mai da capo da un temporaneo **completo**. Un video verificato e in pubblicazione il cui temporaneo e' occupato (la rinomina
   * risponde "occupato") aspetta che il NAS lo lasci, anche oltre il tetto: nessun temporaneo nuovo, nessuna nuova lettura del telefono (che,
   * cambiato nel frattempo, farebbe perdere la versione gia' caricata e verificata). Poi lo stesso temporaneo diventa il file.
   */
  @Test
  fun d13_unTemporaneoCompletoNonRipartiMaiDaCapo() = runTest {
    val h = RealEngineKit()
    val data = bytes(size, 6)
    val media = h.kit.addPhoneMedia("VID_1.mp4", NasContent.of(data), kind = MediaKind.VIDEO)
    h.kit.nas.opsFaults = FaultPlan().at(1, FaultPhase.BEFORE, SimFault.DropConnection, op = SimOp.RENAME)
    h.runAll()
    h.kit.nas.opsFaults = null
    val item = h.kit.store.all().single()
    check(item.state == UploadState.PUBLISHING && item.temp != null) { "non in pubblicazione: $item" }
    h.held(item)
    h.restart()
    h.kit.clock.advance(60 * 60_000L)

    val result = h.engine.run(BackupKind.VIDEOS, 1.hours, BackupTrigger.SCHEDULED)

    assertThat(result.stoppedBy).isEqualTo(RunStop.BUSY)
    assertThat(h.kit.store.all().single()).isEqualTo(item)
    assertThat(h.kit.nas.filePaths(h.kit.share).filter { "-bk-" in it }).containsExactly(item.temp!!.value)

    h.released(item)
    h.resumeUntilQuiet()
    val done = h.kit.store.all().single()
    assertThat(done.state).isEqualTo(UploadState.DONE)
    assertThat(done.temp).isEqualTo(item.temp)
    assertThat(h.kit.catalog.isSafe(media)).isTrue()
    assertThat(h.kit.nas.filePaths(h.kit.share)).containsExactly("Backup/${BackupTestKit.DEVICE_FOLDER}/2026/10/VID_1.mp4")
  }

  /**
   * (a) Se a fermare il giro sono le regole (il Wi-Fi che se ne va, la batteria, la tappa), il giro dice `BUDGET` anche se resta solo da aspettare
   * un file occupato: chi guida il lavoro vede le regole e aspetta come sempre (D-6: il lavoro dell'utente aspetta la rete), non riprova "presto".
   */
  @Test
  fun d13_unaFermataDelleRegoleNonDiventaOccupato() = runTest {
    val h = RealEngineKit()
    val (_, item) = halfUploaded(h, bytes(size, 7))
    h.held(item)
    h.restart()
    var rulesSayStop = false
    h.sleepHook = { rulesSayStop = true }

    val result = h.engine.run(
      BackupKind.VIDEOS, 1.hours, BackupTrigger.SCHEDULED,
      object : UploadControl {
        override suspend fun proceed(): Boolean = !rulesSayStop
      },
    )

    assertThat(result.stoppedBy).isEqualTo(RunStop.BUDGET)
    assertThat(h.kit.store.all().single()).isEqualTo(item)
  }
}
