package dev.pampa.mycloudgallery.core.family.p3

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.WipePhase
import dev.pampa.mycloudgallery.core.family.contract.WipeReason
import dev.pampa.mycloudgallery.core.family.lifecycle.CredentialWiper
import dev.pampa.mycloudgallery.core.family.lifecycle.DeviceSelfWipe
import dev.pampa.mycloudgallery.core.family.lifecycle.FileWipeJournal
import dev.pampa.mycloudgallery.core.family.lifecycle.InMemoryWipeJournal
import dev.pampa.mycloudgallery.core.family.lifecycle.WipeRecord
import dev.pampa.mycloudgallery.core.family.lifecycle.WorkCanceller
import dev.pampa.mycloudgallery.core.family.testing.FakeTrustStateStore
import dev.pampa.mycloudgallery.core.family.testing.OperationLog
import dev.pampa.mycloudgallery.core.family.testing.RecordingLocalDataEraser
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * L'ordine dell'azzeramento (§2.3, test obbligatorio di P3): **prima le chiavi, poi i dati**, con fake che
 * registrano la sequenza; e la ripresa a meta' dopo la morte del processo.
 */
class SelfWipeTest {

  private val expectedOrder = listOf(
    "journal:MARKED",
    "keys:destroyDeviceKey",
    "journal:DEVICE_KEY_DESTROYED",
    "vault:destroy",
    "journal:FAMILY_KEY_DESTROYED",
    "credentials:wipeAll",
    "journal:CREDENTIALS_WIPED",
    "sessions:invalidate:WIPE",
    "journal:SESSIONS_CLOSED",
    "eraser:eraseAll",
    "journal:LOCAL_DATA_ERASED",
    "work:cancelAll",
    "journal:WORK_CANCELLED",
    "journal:DONE",
  )

  /** Un "processo": i fake che registrano in [log], sul diario [journal] (che sopravvive al processo). */
  private class Process(val log: OperationLog, journal: InMemoryWipeJournal, eraserOk: Boolean = true, val keys: SoftwareKeys = SoftwareKeys()) {
    val store = FakeTrustStateStore()
    val eraser = RecordingLocalDataEraser(log, eraserOk)
    val vault = MemoryFamilyKeyVault(log).apply { key = FamilyKey.generate() }
    var credentialsThrow = false
    val wipe = DeviceSelfWipe(
      journal = RecordingJournal(log, journal),
      keys = object : dev.pampa.mycloudgallery.core.trust.crypto.KeyProvider by keys {
        override fun destroyDeviceKey() {
          keys.destroyDeviceKey()
          log.record("keys:destroyDeviceKey")
        }
      },
      familyKeys = vault,
      credentials = CredentialWiper {
        if (credentialsThrow) throw IllegalStateException("processo ucciso")
        log.record("credentials:wipeAll")
        true
      },
      sessions = HostileSessions(HostileNasServer(), log),
      trustState = store,
      eraser = eraser,
      work = WorkCanceller { log.record("work:cancelAll") },
      deviceClock = { 1234L },
    )
  }

  @Test
  fun lAzzeramentoDistruggePrimaLeChiaviEPoiIDati() = runTest {
    val log = OperationLog()
    val process = Process(log, InMemoryWipeJournal())
    process.keys.deviceSigner()
    process.store.snapshot = null

    val phase = process.wipe.run(WipeReason.MARKED_LOST)

    assertThat(phase).isEqualTo(WipePhase.DONE)
    assertThat(log.all()).isEqualTo(expectedOrder)
    assertThat(process.keys.hasDeviceKey()).isFalse()
    assertThat(process.vault.key).isNull()
    assertThat(process.wipe.record.value!!.finishedAt).isEqualTo(1234L)
    assertThat(process.wipe.progress.value).isEqualTo(WipePhase.DONE)
    // Una seconda chiamata non rifa' nulla.
    assertThat(process.wipe.run(WipeReason.LOST_REPORT)).isEqualTo(WipePhase.DONE)
    assertThat(log.all()).hasSize(expectedOrder.size)
  }

  @Test
  fun seICancellareIDatiFallisceSiRiprendeDaliAlRiavvioSenzaRifareLeChiavi() = runTest {
    val log = OperationLog()
    val journal = InMemoryWipeJournal()
    val first = Process(log, journal, eraserOk = false)

    assertThat(first.wipe.run(WipeReason.MARKED_LOST)).isEqualTo(WipePhase.SESSIONS_CLOSED)
    assertThat(journal.record!!.phase).isEqualTo(WipePhase.SESSIONS_CLOSED)
    assertThat(log.all()).doesNotContain("work:cancelAll")

    // Il processo muore; al riavvio un'altra istanza, lo stesso diario.
    val second = Process(log, journal, eraserOk = true)
    second.wipe.resumeIfInterrupted()

    val tail = log.all().drop(expectedOrder.indexOf("journal:SESSIONS_CLOSED") + 1)
    assertThat(tail).containsExactly(
      "eraser:eraseAll", // il tentativo fallito del primo processo
      "eraser:eraseAll",
      "journal:LOCAL_DATA_ERASED",
      "work:cancelAll",
      "journal:WORK_CANCELLED",
      "journal:DONE",
    ).inOrder()
    assertThat(log.all().count { it == "keys:destroyDeviceKey" }).isEqualTo(1)
    assertThat(journal.record!!.reason).isEqualTo(WipeReason.MARKED_LOST)
  }

  @Test
  fun unProcessoUccisoDopoLeChiaviRiprendeDalleCredenziali() = runTest {
    val log = OperationLog()
    val journal = InMemoryWipeJournal()
    val first = Process(log, journal).apply { credentialsThrow = true }

    assertThat(first.wipe.run(WipeReason.LOST_REPORT)).isEqualTo(WipePhase.FAMILY_KEY_DESTROYED)

    val second = Process(log, journal)
    second.wipe.resumeIfInterrupted()

    assertThat(journal.record!!.phase).isEqualTo(WipePhase.DONE)
    assertThat(journal.record!!.reason).isEqualTo(WipeReason.LOST_REPORT)
    assertThat(log.all().indexOf("credentials:wipeAll")).isGreaterThan(log.all().indexOf("vault:destroy"))
    assertThat(log.all().count { it == "vault:destroy" }).isEqualTo(1)
  }

  @Test
  fun seLaChiaveNonSiDistruggeNessunDatoVieneToccato() = runTest {
    val log = OperationLog()
    val keys = SoftwareKeys().apply { deviceSigner(); failDestroy = true }
    val process = Process(log, InMemoryWipeJournal(), keys = keys)

    assertThat(process.wipe.run(WipeReason.MARKED_LOST)).isEqualTo(WipePhase.MARKED)

    assertThat(log.all()).containsExactly("journal:MARKED")
    assertThat(process.vault.key).isNotNull()
    assertThat(log.all()).doesNotContain("eraser:eraseAll")
  }

  @Test
  fun ilDiarioSuFileSopravviveESiRilegge() = runTest {
    val dir = Files.createTempDirectory("wipe").toFile()
    val file = File(dir, FileWipeJournal.FILE_NAME)
    val journal = FileWipeJournal(file)
    journal.write(WipeRecord(WipeReason.LOST_REPORT, WipePhase.CREDENTIALS_WIPED, 10L, null))

    assertThat(FileWipeJournal(file).read()).isEqualTo(WipeRecord(WipeReason.LOST_REPORT, WipePhase.CREDENTIALS_WIPED, 10L, null))

    // Un diario rovinato vale "azzeramento iniziato" (si rifa' tutto), mai "nessun azzeramento".
    file.writeText("spazzatura")
    assertThat(FileWipeJournal(file).read()!!.phase).isEqualTo(WipePhase.MARKED)
    journal.clear()
    assertThat(journal.read()).isNull()
    dir.deleteRecursively()
  }

  @Test
  fun dopoLaRiconfigurazioneIlDiarioFinitoSiDimentica() = runTest {
    val log = OperationLog()
    val journal = InMemoryWipeJournal()
    val process = Process(log, journal)
    assertThat(process.wipe.forgetCompletedWipe()).isFalse() // niente da dimenticare
    process.wipe.run(WipeReason.MARKED_LOST)

    assertThat(process.wipe.forgetCompletedWipe()).isTrue()

    assertThat(journal.record).isNull()
    assertThat(process.wipe.progress.value).isNull()
  }
}
