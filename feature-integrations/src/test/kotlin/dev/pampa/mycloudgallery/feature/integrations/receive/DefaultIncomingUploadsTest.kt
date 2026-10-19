package dev.pampa.mycloudgallery.feature.integrations.receive

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.DestinationArea
import dev.pampa.mycloudgallery.core.backup.contract.IncomingItem
import dev.pampa.mycloudgallery.core.backup.contract.IncomingOrigin
import dev.pampa.mycloudgallery.core.backup.contract.IncomingSaveOutcome
import dev.pampa.mycloudgallery.core.backup.contract.IncomingSavePlan
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStage
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStageId
import dev.pampa.mycloudgallery.core.backup.contract.NasDestination
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmation
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationCheck
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationRules
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationSheet
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.integrations.receive.testing.FakeIncomingQueue
import dev.pampa.mycloudgallery.feature.integrations.receive.testing.OpenCounter
import dev.pampa.mycloudgallery.feature.integrations.receive.testing.incomingStream
import dev.pampa.mycloudgallery.feature.integrations.share.FlowCounters
import dev.pampa.mycloudgallery.feature.integrations.share.ReceiveCounter
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Fase 5B, P11 (D24, E-S11, E-X8; C17): ricevere non carica niente, `save` vuole il tocco. La conferma e' legata al piano (la condivisione copiata, la
 * cartella, l'area), vale 10 minuti di orologio monotono e non sopravvive a un riavvio; i file devono essere ancora quelli copiati; la destinazione
 * non scrivibile e un diario che non risponde non mettono niente in coda.
 */
class DefaultIncomingUploadsTest {

  @get:Rule val folder = TemporaryFolder()

  private val clock = FakeBackupClock()
  private val queue = FakeIncomingQueue()
  private lateinit var stager: IncomingStager
  private lateinit var uploads: DefaultIncomingUploads

  private val dest = NasDestination("alessio", SharePath.parse("Ricevute"), DestinationArea.PRIVATE)

  @Before
  fun setUp() {
    FlowCounters.reset()
    stager = IncomingStager(File(folder.root, "incoming"), freeBytes = { 100L shl 30 }, bounds = ImageBoundsReader { null })
    uploads = DefaultIncomingUploads(stager, queue, clock)
  }

  private fun stage(vararg sizes: Int): IncomingStage =
    runBlocking { uploads.stage(sizes.mapIndexed { i, size -> incomingStream(ReceiveSamples.jpeg(size, i + 1)) }) }

  private fun confirmationFor(stage: IncomingStage, to: NasDestination = dest): PhoneConfirmation =
    PhoneConfirmationSheet(IncomingSavePlan(stage, to)).tap(clock.monotonicMs())!!

  @Test
  fun riceverePerSeNonCaricaNienteE_E_S11() {
    val stage = stage(3_000, 4_000)

    assertThat(stage.accepted).hasSize(2)
    // Dopo la copia: nessun elemento in coda, nessuna chiamata al diario, i file sono solo sul telefono.
    assertThat(queue.enqueued).isEmpty()
    assertThat(FlowCounters.receive.get(ReceiveCounter.SAVED)).isEqualTo(0L)
    assertThat(FlowCounters.receive.get(ReceiveCounter.STAGED_ITEMS)).isEqualTo(2L)
  }

  @Test
  fun conIlTocco_LaCondivisioneVaInCodaEIFileLocaliRestano() {
    val stage = stage(3_000, 4_000)

    val outcome = runBlocking { uploads.save(stage, dest, confirmationFor(stage)) }

    assertThat(outcome).isEqualTo(IncomingSaveOutcome.Queued(2, userInitiated = false))
    assertThat(queue.enqueued).hasSize(1)
    assertThat(queue.enqueued.single().first).isEqualTo(dest)
    assertThat(queue.enqueued.single().second).containsExactlyElementsIn(stage.accepted.map { it.name })
    // I file restano finche' il diario li cita: li toglie il caricamento a lavoro finito, non il salvataggio.
    assertThat(stager.isIntact(stage)).isTrue()
    assertThat(FlowCounters.receive.get(ReceiveCounter.SAVED)).isEqualTo(2L)
  }

  @Test
  fun unaConfermaDiUnAltroPianoOVecchiaOdalFuturoNonVale() {
    val stage = stage(3_000)
    val other = NasDestination("alessio", SharePath.parse("Altrove"), DestinationArea.PRIVATE)

    // La cartella e' cambiata dopo il foglio.
    val wrongPlan = runBlocking { uploads.save(stage, other, confirmationFor(stage, dest)) }
    assertThat(wrongPlan).isEqualTo(IncomingSaveOutcome.Rejected(PhoneConfirmationCheck.WrongPlan))

    // L'area e' cambiata (stessa share e cartella): e' un altro piano.
    val common = NasDestination("alessio", SharePath.parse("Ricevute"), DestinationArea.COMMON)
    assertThat(runBlocking { uploads.save(stage, common, confirmationFor(stage, dest)) })
      .isEqualTo(IncomingSaveOutcome.Rejected(PhoneConfirmationCheck.WrongPlan))

    // Un'altra condivisione.
    val other2 = stage(5_000)
    assertThat(runBlocking { uploads.save(stage, dest, confirmationFor(other2)) })
      .isEqualTo(IncomingSaveOutcome.Rejected(PhoneConfirmationCheck.WrongPlan))

    // Il foglio e' rimasto aperto oltre 10 minuti.
    val confirmation = confirmationFor(stage)
    clock.advance(PhoneConfirmationRules.MAX_AGE_MS + 1)
    assertThat(runBlocking { uploads.save(stage, dest, confirmation) }).isEqualTo(IncomingSaveOutcome.Rejected(PhoneConfirmationCheck.Expired))

    // Una conferma che viene da un altro avvio del telefono (istante monotono nel futuro).
    clock.monotonic = 1_000
    val future = PhoneConfirmationSheet(IncomingSavePlan(stage, dest)).tap(clock.monotonicMs() + 5_000)!!
    assertThat(runBlocking { uploads.save(stage, dest, future) }).isEqualTo(IncomingSaveOutcome.Rejected(PhoneConfirmationCheck.FromTheFuture))

    assertThat(queue.enqueued).isEmpty() // nessuna di queste ha messo niente in coda
  }

  @Test
  fun seIFileNonSonoPiuQuelliCopiatiNonSiSalvaNiente() {
    val stage = stage(3_000, 4_000)
    val confirmation = confirmationFor(stage)
    File(File(folder.root, "incoming/${stage.id.value}"), stage.accepted[1].name).delete()

    assertThat(runBlocking { uploads.save(stage, dest, confirmation) }).isEqualTo(IncomingSaveOutcome.StageGone)
    assertThat(queue.enqueued).isEmpty()
  }

  @Test
  fun unaDestinazioneNonScrivibileEUnDiarioCheNonRispondeNonMettonoNienteInCoda() {
    val stage = stage(3_000)

    queue.allowed = false
    assertThat(runBlocking { uploads.save(stage, dest, confirmationFor(stage)) }).isEqualTo(IncomingSaveOutcome.DestinationNotAllowed)

    queue.allowed = true
    queue.failWith = IOException("diario chiuso")
    assertThat(runBlocking { uploads.save(stage, dest, confirmationFor(stage)) }).isEqualTo(IncomingSaveOutcome.Unavailable)

    assertThat(queue.enqueued).isEmpty()
  }

  @Test
  fun unaCondivisioneGrandeEUnLavoroAvviatoDallUtente() {
    val big = IncomingStage(
      IncomingStageId("grande"),
      listOf(IncomingItem("a".repeat(64), Sha256("a".repeat(64)), 300L * 1024 * 1024, "video/mp4", null)),
      emptyMap(),
    )
    assertThat(big.needsUserInitiatedJob).isTrue()
    assertThat(stage(3_000).needsUserInitiatedJob).isFalse()
  }

  @Test
  fun scartareTogliLeCopieEconta() {
    val stage = stage(3_000)

    runBlocking { uploads.discard(stage.id) }

    assertThat(File(folder.root, "incoming/${stage.id.value}").exists()).isFalse()
    assertThat(FlowCounters.receive.get(ReceiveCounter.DISCARDED)).isEqualTo(1L)
    assertThat(runBlocking { uploads.save(stage, dest, confirmationFor(stage)) }).isEqualTo(IncomingSaveOutcome.StageGone)
  }

  @Test
  fun iRifiutiSiContanoPerMotivoESoloComeNumeri() {
    val counter = OpenCounter()
    runBlocking {
      uploads.stage(
        listOf(
          counter.stream(IncomingOrigin.FILE_SCHEME),
          counter.stream(IncomingOrigin.OWN_PROVIDER),
          incomingStream(ReceiveSamples.fakeJpegThatIsHtml()),
        ),
      )
    }

    assertThat(FlowCounters.receive.get(ReceiveCounter.REJECTED_FILE_SCHEME)).isEqualTo(1L)
    assertThat(FlowCounters.receive.get(ReceiveCounter.REJECTED_OWN_PROVIDER)).isEqualTo(1L)
    assertThat(FlowCounters.receive.get(ReceiveCounter.REJECTED_NOT_MEDIA)).isEqualTo(1L)
    // Solo codici fissi: nessun nome di file, percorso o impronta.
    assertThat(FlowCounters.snapshot().keys).containsNoneOf("jpg", "IMG_1.jpg")
    assertThat(FlowCounters.snapshot().keys.all { Regex("[a-z]+(_[a-z]+)*").matches(it) }).isTrue()
  }
}
