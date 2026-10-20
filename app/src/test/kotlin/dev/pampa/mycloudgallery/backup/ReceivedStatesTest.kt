package dev.pampa.mycloudgallery.backup

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupSetupStep
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.StopBy
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.contract.WaitReason
import dev.pampa.mycloudgallery.core.database.entity.IncomingItemEntity
import dev.pampa.mycloudgallery.core.database.entity.IncomingQueueRow
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedStatus
import org.junit.Test

/**
 * IN-7 ("Ricevuti in coda"): la frase di ogni file ricevuto, dal diario degli arrivi, dall'elemento del diario del backup, dal cancello della sua share
 * e dallo stato del motore. Pura: nessun Android. Il cancello della share vince su tutto (e' cio' che ferma davvero), poi il motore, poi il problema
 * ricordato dal giro o l'attesa delle regole.
 */
class ReceivedStatesTest {

  private fun row(state: String, itemState: String? = null, problem: String? = null) = IncomingQueueRow(
    item = IncomingItemEntity(
      incomingId = 1,
      stageId = "stage",
      state = state,
      fileName = "file",
      displayName = null,
      sha256 = "a".repeat(64),
      size = 1_000,
      mime = "image/jpeg",
      destShare = "alessio",
      destFolder = "Ricevute",
      backupItemId = if (itemState != null) 7 else null,
      createdAt = 1,
      updatedAt = 1,
    ),
    itemState = itemState,
    itemProblem = problem,
  )

  private val running = BackupStatus.Running(BackupKind.PHOTOS, 1, 4, 10, 40, null, BackupTrigger.INCOMING)
  private val upToDate = BackupStatus.UpToDate(10, null)

  private fun status(
    row: IncomingQueueRow,
    backup: BackupStatus = upToDate,
    gate: BackupGateState? = BackupGateState.OPEN,
    copy: Boolean = true,
  ) = ReceivedStates.of(row, backup, gate, copy)

  @Test
  fun ilMotoreCheHaRinunciatoDiceIlSuoProblema() {
    assertThat(status(row("failed", "failed", "unreadable"))).isEqualTo(ReceivedStatus.COPY_CHANGED)
    assertThat(status(row("failed", "failed", "unreadable"), copy = false)).isEqualTo(ReceivedStatus.COPY_MISSING)
    assertThat(status(row("failed", "failed", "verify_failed"))).isEqualTo(ReceivedStatus.NOT_INTACT)
    assertThat(status(row("failed", "failed", "unexpected"))).isEqualTo(ReceivedStatus.FAILED)
    // Senza un problema ricordato l'unica via a `FAILED` e' una destinazione fuori dalle radici ammesse.
    assertThat(status(row("failed", "failed", null))).isEqualTo(ReceivedStatus.FOLDER_REFUSED)
    assertThat(status(row("failed", "failed", "qualcosa-di-nuovo"))).isEqualTo(ReceivedStatus.FAILED)
    assertThat(status(row("gone", "gone"))).isEqualTo(ReceivedStatus.COPY_MISSING)
    // Un problema e' un problema: il cancello di adesso non lo cambia.
    assertThat(status(row("failed", "failed", "verify_failed"), gate = BackupGateState.TEST_ONLY)).isEqualTo(ReceivedStatus.NOT_INTACT)
  }

  @Test
  fun ilCancelloDellaShareVinceSuTutto() {
    val queued = row("queued", "hashed")
    assertThat(status(queued, running, BackupGateState.TEST_ONLY)).isEqualTo(ReceivedStatus.TRIAL)
    assertThat(status(queued, running, BackupGateState.READ_ONLY_AREA)).isEqualTo(ReceivedStatus.FOLDER_LOCKED)
    assertThat(status(queued, running, BackupGateState.FROZEN)).isEqualTo(ReceivedStatus.FROZEN)
    assertThat(status(queued, running, BackupGateState.WRITES_STOPPED)).isEqualTo(ReceivedStatus.FROZEN)
    assertThat(status(queued, running, BackupGateState.PAUSED)).isEqualTo(ReceivedStatus.PAUSED)
    assertThat(status(queued, running, BackupGateState.NOT_READY)).isEqualTo(ReceivedStatus.UNAVAILABLE)
    assertThat(status(queued, running, BackupGateState.NO_PRIVATE_AREA)).isEqualTo(ReceivedStatus.UNAVAILABLE)
  }

  @Test
  fun unFileCheSaleDiceCheSale_SoloSeIlMotoreGira() {
    assertThat(status(row("queued", "uploading"), running)).isEqualTo(ReceivedStatus.UPLOADING)
    for (state in listOf(UploadState.UPLOADED, UploadState.VERIFIED, UploadState.PUBLISHING, UploadState.PUBLISHED, UploadState.LINKED)) {
      assertThat(status(row("queued", state.code), running)).isEqualTo(ReceivedStatus.VERIFYING)
    }
    // Il motore fermo: un caricamento a meta' aspetta, non sale.
    assertThat(status(row("queued", "uploading"), upToDate)).isEqualTo(ReceivedStatus.QUEUED)
    assertThat(status(row("queued", "verified"), BackupStatus.Waiting(1, 0, WaitReason.NAS))).isEqualTo(ReceivedStatus.NETWORK)
    // Un file che il motore non ha ancora toccato aspetta il suo turno anche con il motore in corso.
    assertThat(status(row("queued", "planned"), running)).isEqualTo(ReceivedStatus.QUEUED)
    assertThat(status(row("queued", "hashed"), running)).isEqualTo(ReceivedStatus.QUEUED)
  }

  @Test
  fun ilProblemaDelGiroOLAttesaDelleRegoleDannoLaFrase() {
    val queued = row("queued", "planned")
    assertThat(status(queued, BackupStatus.Problem(BackupProblemCode.NAS_FULL, 0))).isEqualTo(ReceivedStatus.NAS_FULL)
    assertThat(status(queued, BackupStatus.Problem(BackupProblemCode.QUOTA, 0))).isEqualTo(ReceivedStatus.QUOTA)
    assertThat(status(queued, BackupStatus.Problem(BackupProblemCode.AUTH, 0))).isEqualTo(ReceivedStatus.PASSWORD)
    assertThat(status(queued, BackupStatus.Problem(BackupProblemCode.NAS_UNREACHABLE, 0))).isEqualTo(ReceivedStatus.NETWORK)
    assertThat(status(queued, BackupStatus.Problem(BackupProblemCode.READ_ONLY_AREA, 0))).isEqualTo(ReceivedStatus.FOLDER_LOCKED)
    assertThat(status(queued, BackupStatus.Problem(BackupProblemCode.FROZEN, 0))).isEqualTo(ReceivedStatus.FROZEN)
    assertThat(status(queued, BackupStatus.Problem(BackupProblemCode.TEST_ONLY, 0))).isEqualTo(ReceivedStatus.TRIAL)
    // Un problema che e' delle foto del telefono non ferma un file ricevuto.
    assertThat(status(queued, BackupStatus.Problem(BackupProblemCode.MEDIA_PERMISSION, 0))).isEqualTo(ReceivedStatus.QUEUED)
    for (reason in listOf(WaitReason.NAS, WaitReason.WIFI, WaitReason.HOME_WIFI, WaitReason.DATA_CAP)) {
      assertThat(status(queued, BackupStatus.Waiting(1, 0, reason))).isEqualTo(ReceivedStatus.NETWORK)
    }
    // Fascia, carica e preset Notte non riguardano un file che l'utente ha chiesto di salvare (IN-5): niente "attesa di rete" per loro.
    for (reason in listOf(WaitReason.CHARGING, WaitReason.NIGHT_WINDOW, WaitReason.BATTERY, WaitReason.NEXT_RUN)) {
      assertThat(status(queued, BackupStatus.Waiting(1, 0, reason))).isEqualTo(ReceivedStatus.QUEUED)
    }
    assertThat(status(queued, BackupStatus.Stopped(StopBy.USER))).isEqualTo(ReceivedStatus.PAUSED)
    assertThat(status(queued, BackupStatus.Stopped(StopBy.SYSTEM))).isEqualTo(ReceivedStatus.QUEUED)
    assertThat(status(queued, BackupStatus.TestOnly)).isEqualTo(ReceivedStatus.TRIAL)
    assertThat(status(queued, BackupStatus.NotConfigured(setOf(BackupSetupStep.NAS)))).isEqualTo(ReceivedStatus.UNAVAILABLE)
    assertThat(status(queued, upToDate)).isEqualTo(ReceivedStatus.QUEUED)
    // Senza il cancello della share (riga senza destinazione) si va dal resto.
    assertThat(status(queued, gate = null)).isEqualTo(ReceivedStatus.QUEUED)
  }

  @Test
  fun siScartaSoloPrimaCheIByteSianoCompletiSulNas() {
    // Prima di scrivere sul NAS: si scarta. L'elemento sparito dal diario o gia' terminale, anche.
    for (itemState in listOf(null, "planned", "hashed", "failed", "gone")) {
      assertThat(ReceivedStates.canDiscard(row("queued", itemState), running = false)).isTrue()
    }
    // Il motore in giro: uno ancora da leggere aspetta il suo turno e si scarta; uno gia' letto (i byte possono essere in memoria del motore) o a meta'
    // caricamento sta salendo adesso e si scarta dopo. Con il motore fermo si scartano tutti e due.
    assertThat(ReceivedStates.canDiscard(row("queued", "planned"), running = true)).isTrue()
    assertThat(ReceivedStates.canDiscard(row("queued", "hashed"), running = true)).isFalse()
    assertThat(ReceivedStates.canDiscard(row("queued", "uploading"), running = true)).isFalse()
    assertThat(ReceivedStates.canDiscard(row("queued", "hashed"), running = false)).isTrue()
    assertThat(ReceivedStates.canDiscard(row("queued", "uploading"), running = false)).isTrue()
    // Da `UPLOADED` in poi i byte sono sul NAS e il motore finisce da solo: e' "caricato", non "scartabile".
    for (state in listOf("uploaded", "verified", "publishing", "published", "linked", "done", "linked_existing", "copying", "copied")) {
      assertThat(ReceivedStates.canDiscard(row("queued", state), running = false)).isFalse()
    }
    // I file che il motore ha lasciato si scartano sempre.
    assertThat(ReceivedStates.canDiscard(row("failed", "failed", "unreadable"), running = true)).isTrue()
    assertThat(ReceivedStates.canDiscard(row("gone", "gone"), running = true)).isTrue()
  }

  @Test
  fun ogniStatoDelContrattoHaUnPunto() {
    // Ogni stato ha una fase: i conti della pagina Backup non lasciano fuori nessuno.
    assertThat(ReceivedStatus.entries.map { it.phase }.toSet()).hasSize(3)
    assertThat(ReceivedStatus.entries.map { it.code }.toSet()).hasSize(ReceivedStatus.entries.size)
  }
}
