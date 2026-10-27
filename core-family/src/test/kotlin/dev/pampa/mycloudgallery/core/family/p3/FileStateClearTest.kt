package dev.pampa.mycloudgallery.core.family.p3

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.TrustStateStore
import dev.pampa.mycloudgallery.core.family.contract.WipePhase
import dev.pampa.mycloudgallery.core.family.contract.WipeReason
import dev.pampa.mycloudgallery.core.family.lifecycle.CredentialWiper
import dev.pampa.mycloudgallery.core.family.lifecycle.DeviceSelfWipe
import dev.pampa.mycloudgallery.core.family.lifecycle.FileWipeJournal
import dev.pampa.mycloudgallery.core.family.lifecycle.InMemoryWipeJournal
import dev.pampa.mycloudgallery.core.family.lifecycle.WipeJournal
import dev.pampa.mycloudgallery.core.family.lifecycle.WipeRecord
import dev.pampa.mycloudgallery.core.family.lifecycle.WorkCanceller
import dev.pampa.mycloudgallery.core.family.registry.FileApprovalSightings
import dev.pampa.mycloudgallery.core.family.testing.OperationLog
import dev.pampa.mycloudgallery.core.family.testing.RecordingLocalDataEraser
import dev.pampa.mycloudgallery.core.family.trust.FileTrustStateStore
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * I file di stato dell'azzeramento non fanno credere tolto cio' che c'e' ancora: `clear()` toglie davvero, oppure lancia `IOException`, e
 * l'azzeramento lo registra come fase non riuscita (si riprende al prossimo avvio) invece di andare avanti. Prima `File.delete()` diceva `false`
 * e nessuno lo guardava: su Windows un altro handle bastava, e lo stato fidato o il diario di un dispositivo azzerato restavano.
 *
 * Il file che "non si toglie" e' un nome occupato da una cartella non vuota: nessuna attesa lo risolve, uguale su Windows e su Linux.
 */
class FileStateClearTest {

  @get:Rule
  val folder = TemporaryFolder()

  private fun stuck(name: String): File = File(folder.root, name).also {
    it.mkdirs()
    File(it, "dentro").writeText("1")
  }

  @Test
  fun loStatoFidatoSiTogliePerDavvero() = runTest {
    val file = File(folder.root, "trust-state").also { it.writeText("x") }

    FileTrustStateStore(file).clear()

    assertThat(file.exists()).isFalse()
  }

  @Test
  fun loStatoFidatoCheNonSiTogliePerDavveroLanciaEIlFileResta() = runTest {
    val file = stuck("trust-state")

    val error = runCatching { FileTrustStateStore(file).clear() }.exceptionOrNull()

    assertThat(error).isInstanceOf(IOException::class.java)
    assertThat(file.exists()).isTrue()
  }

  @Test
  fun leRichiesteVisteSiTolgonoPerDavveroOLanciano() = runTest {
    val ok = File(folder.root, "sightings").also { it.writeText("x") }
    FileApprovalSightings(ok).clear()
    assertThat(ok.exists()).isFalse()

    val bad = stuck("sightings-bloccate")
    val error = runCatching { FileApprovalSightings(bad).clear() }.exceptionOrNull()
    assertThat(error).isInstanceOf(IOException::class.java)
    assertThat(bad.exists()).isTrue()
  }

  @Test
  fun ilDiarioDellAzzeramentoSiTogliePerDavveroOLancia() = runTest {
    val ok = File(folder.root, FileWipeJournal.FILE_NAME)
    val journal = FileWipeJournal(ok)
    journal.write(WipeRecord(WipeReason.LOST_REPORT, WipePhase.DONE, 1L, 2L))
    journal.clear()
    assertThat(ok.exists()).isFalse()

    val bad = stuck("diario-bloccato")
    val error = runCatching { FileWipeJournal(bad).clear() }.exceptionOrNull()
    assertThat(error).isInstanceOf(IOException::class.java)
    assertThat(bad.exists()).isTrue()
  }

  /** Un azzeramento con i fake che registrano, sul diario [journal] e sullo stato fidato [trustState]. */
  private fun wipe(
    log: OperationLog,
    journal: WipeJournal,
    trustState: TrustStateStore,
    localState: List<suspend () -> Unit> = emptyList(),
    afterWork: List<suspend () -> Unit> = emptyList(),
  ): DeviceSelfWipe {
    val keys = SoftwareKeys().apply { deviceSigner() }
    return DeviceSelfWipe(
      journal = RecordingJournal(log, journal),
      keys = keys,
      familyKeys = MemoryFamilyKeyVault(log).apply { key = FamilyKey.generate() },
      credentials = CredentialWiper { true },
      sessions = HostileSessions(HostileNasServer(), log),
      trustState = trustState,
      eraser = RecordingLocalDataEraser(log, true),
      work = WorkCanceller { log.record("work:cancelAll") },
      deviceClock = { 1234L },
      localState = localState,
      afterWork = afterWork,
    )
  }

  @Test
  fun unAzzeramentoChe_nonRiescaAToglierelLoStatoFidato_cancellaComunqueIDatiLocaliERestaDaFare() = runTest {
    val log = OperationLog()
    val journal = InMemoryWipeJournal()
    val sightings = AtomicInteger()
    val process = wipe(log, journal, FileTrustStateStore(stuck("trust-state")), localState = listOf({ sightings.incrementAndGet() }))

    val phase = process.run(WipeReason.MARKED_LOST)

    // R10: lo stato fidato che non si toglie non salta piu' il resto dei dati (catalogo, miniature, dati personali): le chiavi sono gia' distrutte.
    assertThat(log.all()).contains("eraser:eraseAll")
    assertThat(sightings.get()).isEqualTo(1)
    // La fase pero' non e' riuscita: il diario resta all'ultima completata e i lavori non sono stati fermati.
    assertThat(phase).isEqualTo(WipePhase.SESSIONS_CLOSED)
    assertThat(journal.record!!.phase).isEqualTo(WipePhase.SESSIONS_CLOSED)
    assertThat(log.all()).doesNotContain("work:cancelAll")
  }

  @Test
  fun unPassoDeiDatiChePartaPerErrore_nonFermaGliAltriEFaRipetereLaFase() = runTest {
    val log = OperationLog()
    val journal = InMemoryWipeJournal()
    val trust = File(folder.root, "trust-state").also { it.writeText("x") }
    val process = wipe(
      log, journal, FileTrustStateStore(trust),
      localState = listOf({ throw IOException("richieste viste bloccate") }),
    )

    val phase = process.run(WipeReason.MARKED_LOST)

    assertThat(log.all()).contains("eraser:eraseAll")
    assertThat(trust.exists()).isFalse()
    assertThat(phase).isEqualTo(WipePhase.SESSIONS_CLOSED)
  }

  @Test
  fun alRiavvioLaFaseDeiDatiSiRipeteEFinisce() = runTest {
    val log = OperationLog()
    val journal = InMemoryWipeJournal()
    val blocked = stuck("trust-state")
    assertThat(wipe(log, journal, FileTrustStateStore(blocked)).run(WipeReason.MARKED_LOST)).isEqualTo(WipePhase.SESSIONS_CLOSED)

    // Il blocco passa (la cartella che occupava il nome se ne va): il nuovo processo riprende dalla fase dei dati e arriva in fondo.
    blocked.deleteRecursively()
    val second = wipe(log, journal, FileTrustStateStore(blocked))
    second.resumeIfInterrupted()

    assertThat(journal.record!!.phase).isEqualTo(WipePhase.DONE)
    assertThat(log.all().count { it == "eraser:eraseAll" }).isEqualTo(2)
  }

  @Test
  fun iFileCheUnLavoroPoteva_ricreare_sitolgonoDopoCheILavoriSonoFermati() = runTest {
    val log = OperationLog()
    val journal = InMemoryWipeJournal()
    val process = wipe(log, journal, FileTrustStateStore(File(folder.root, "trust-state")), afterWork = listOf({ log.record("battito:clear") }))

    assertThat(process.run(WipeReason.MARKED_LOST)).isEqualTo(WipePhase.DONE)

    val order = log.all()
    assertThat(order.indexOf("battito:clear")).isGreaterThan(order.indexOf("work:cancelAll"))
    assertThat(order.indexOf("battito:clear")).isLessThan(order.indexOf("journal:WORK_CANCELLED"))
  }

  @Test
  fun unFileDopoILavoriCheNonSiTogliePerDavvero_lasciaLaFaseDaRipetere() = runTest {
    val log = OperationLog()
    val journal = InMemoryWipeJournal()
    val process = wipe(
      log, journal, FileTrustStateStore(File(folder.root, "trust-state")),
      afterWork = listOf({ throw IOException("battito non tolto") }),
    )

    val phase = process.run(WipeReason.MARKED_LOST)

    assertThat(phase).isEqualTo(WipePhase.LOCAL_DATA_ERASED)
    assertThat(log.all()).doesNotContain("journal:WORK_CANCELLED")
  }

  @Test
  fun ilDiarioFinitoCheNonSiTogliePerDavvero_nonSiDiceDimenticato() = runTest {
    val log = OperationLog()
    val journal = InMemoryWipeJournal()
    val broken = object : WipeJournal by journal {
      override suspend fun clear() = throw IOException("diario occupato")
    }
    val process = wipe(log, broken, FileTrustStateStore(File(folder.root, "trust-state")))
    assertThat(process.run(WipeReason.MARKED_LOST)).isEqualTo(WipePhase.DONE)

    assertThat(process.forgetCompletedWipe()).isFalse()

    // Il diario e lo stato in memoria sono rimasti quelli di un azzeramento finito: si puo' riprovare.
    assertThat(journal.record!!.phase).isEqualTo(WipePhase.DONE)
    assertThat(process.progress.value).isEqualTo(WipePhase.DONE)
  }
}
