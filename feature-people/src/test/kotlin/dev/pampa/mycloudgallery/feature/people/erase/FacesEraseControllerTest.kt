package dev.pampa.mycloudgallery.feature.people.erase

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.AckState
import dev.pampa.mycloudgallery.core.faces.contract.DeviceAck
import dev.pampa.mycloudgallery.core.faces.contract.EraseReport
import dev.pampa.mycloudgallery.core.faces.contract.EraseStart
import dev.pampa.mycloudgallery.core.faces.contract.EraseStatus
import dev.pampa.mycloudgallery.core.faces.contract.EraseStep
import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import dev.pampa.mycloudgallery.core.faces.contract.ErasePreview
import dev.pampa.mycloudgallery.core.faces.contract.FacesClock
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraseConfirmation
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraser
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import dev.pampa.mycloudgallery.core.faces.contract.StepState
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.feature.people.contract.EraseSheetState
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Test

/**
 * La logica del foglio "Disattiva e cancella" (Fase 8, P8; §1.5 `EraseSheetState`, §2.8): l'anteprima con "Tieni le foto" preselezionata quando ci sono
 * album, la scelta, la conferma che va alla cancellazione con la scelta, ogni esito (partita, scaduta, anteprima cambiata, gia' in corso, niente da
 * cancellare, diario che non si scrive, un'eccezione), lo stato che segue la cancellazione, e lo stato della pagina dallo stato della cancellazione.
 */
class FacesEraseControllerTest {

  private val g = FaceGeneration.fromBytes(ByteArray(16) { 5 })
  private val phone = DeviceAck("Telefono", "a".repeat(26), AckState.CONFIRMED, null, thisDevice = true)
  private val tablet = DeviceAck("Tablet", "b".repeat(26), AckState.WAITING, 1_800_000_000_000L, thisDevice = false)
  private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

  @After
  fun close() = scope.cancel()

  private class Clock(var now: Long = 7_000L) : FacesClock {
    override fun monotonicNanos(): Long = now

    override fun nasNowMillis(): Long? = null
  }

  /** Una cancellazione a copione: l'anteprima, l'esito di [erase], e lo stato da far cambiare a mano. */
  private class ScriptedEraser(var preview: ErasePreview, var outcome: EraseStart = EraseStart.Started) : FacesEraser {
    override val status = MutableStateFlow<EraseStatus?>(null)
    val calls = mutableListOf<Pair<FacesEraseConfirmation, PeopleAlbumsChoice>>()
    var throws: Exception? = null
    var onErase: (() -> Unit)? = null

    override suspend fun preview(): ErasePreview = preview

    override suspend fun erase(confirmation: FacesEraseConfirmation, albums: PeopleAlbumsChoice): EraseStart {
      throws?.let { throw it }
      calls += confirmation to albums
      onErase?.invoke()
      return outcome
    }

    override suspend fun resume(budget: Duration): EraseReport = EraseReport(EraseStep.DONE, 0, null, true)
  }

  private fun preview(albums: Int = 2, digest: Long = 42) = ErasePreview(3, albums, historyWithNames = true, devices = listOf(phone, tablet), localOnly = false, digest = digest)

  private fun status(step: EraseStep, local: StepState, nas: StepState, waiting: EraseWait?, devices: List<DeviceAck> = listOf(phone, tablet)) =
    EraseStatus(g, step, local, nas, devices, waiting)

  @Test
  fun lAnteprimaConGliAlbumPreselezionaTieniLeFoto_ESiPuoCambiare() {
    val eraser = ScriptedEraser(preview())
    val clock = Clock()
    val controller = FacesEraseController(eraser, clock, scope)
    controller.load()
    val shown = controller.state.value as EraseSheetState.Preview
    assertThat(shown.namedPeople).isEqualTo(3)
    assertThat(shown.choice).isEqualTo(PeopleAlbumsChoice.KEEP_PHOTOS)
    assertThat(shown.canConfirm).isTrue()
    assertThat(controller.token.value).isEqualTo(EraseSheetToken(42, 7_000L))
    controller.choose(PeopleAlbumsChoice.DROP_RULE)
    assertThat((controller.state.value as EraseSheetState.Preview).choice).isEqualTo(PeopleAlbumsChoice.DROP_RULE)
    // Senza album non c'e' niente da scegliere.
    val none = FacesEraseController(ScriptedEraser(preview(albums = 0)), clock, scope).also { it.load() }
    assertThat((none.state.value as EraseSheetState.Preview).choice).isNull()
    none.choose(PeopleAlbumsChoice.DROP_RULE)
    assertThat((none.state.value as EraseSheetState.Preview).choice).isNull()
  }

  @Test
  fun laConfermaVaAllaCancellazioneConLaSceltaEPoiSiSegueLoStato() {
    val eraser = ScriptedEraser(preview())
    eraser.onErase = { eraser.status.value = status(EraseStep.LOCAL_DONE, StepState.DONE, StepState.RUNNING, null) }
    val controller = FacesEraseController(eraser, Clock(), scope).also { it.load() }
    controller.choose(PeopleAlbumsChoice.DROP_RULE)
    val confirmation = FacesEraseConfirmation(42, 7_000L)
    controller.confirm(confirmation)
    assertThat(eraser.calls.single().first).isSameInstanceAs(confirmation)
    assertThat(eraser.calls.single().second).isEqualTo(PeopleAlbumsChoice.DROP_RULE)
    assertThat(controller.state.value).isEqualTo(EraseSheetState.Progress(StepState.DONE, StepState.RUNNING, listOf(phone, tablet), null))
    assertThat(controller.token.value).isNull()
    eraser.status.value = status(EraseStep.VERIFIED, StepState.DONE, StepState.DONE, EraseWait.DEVICES)
    assertThat(controller.state.value).isEqualTo(EraseSheetState.WaitingDevices(listOf(tablet)))
    eraser.status.value = status(EraseStep.DONE, StepState.DONE, StepState.DONE, null, listOf(phone, tablet.copy(state = AckState.CONFIRMED)))
    assertThat(controller.state.value).isEqualTo(EraseSheetState.Done)
    // Una seconda conferma fuori dall'anteprima non fa niente.
    controller.confirm(confirmation)
    assertThat(eraser.calls).hasSize(1)
  }

  @Test
  fun ogniEsitoHaLaSuaPagina() {
    val cases = mapOf(
      EraseStart.Expired to EraseSheetState.Expired,
      EraseStart.PreviewChanged to EraseSheetState.Expired,
      EraseStart.Unavailable to EraseSheetState.Expired,
      EraseStart.NothingToErase to EraseSheetState.Done,
    )
    for ((outcome, page) in cases) {
      val controller = FacesEraseController(ScriptedEraser(preview(), outcome), Clock(), scope).also { it.load() }
      controller.confirm(FacesEraseConfirmation(42, 7_000L))
      assertThat(controller.state.value).isEqualTo(page)
    }
    val throwing = ScriptedEraser(preview()).apply { throws = IllegalStateException("guasto") }
    val controller = FacesEraseController(throwing, Clock(), scope).also { it.load() }
    controller.confirm(FacesEraseConfirmation(42, 7_000L))
    assertThat(controller.state.value).isEqualTo(EraseSheetState.Expired)
    // "Rileggi il riepilogo": l'anteprima di nuovo, con l'istante nuovo.
    val clock = Clock()
    val again = FacesEraseController(ScriptedEraser(preview(), EraseStart.PreviewChanged), clock, scope).also { it.load() }
    again.confirm(FacesEraseConfirmation(42, 7_000L))
    clock.now = 9_000L
    again.reload()
    assertThat(again.state.value).isInstanceOf(EraseSheetState.Preview::class.java)
    assertThat(again.token.value).isEqualTo(EraseSheetToken(42, 9_000L))
  }

  @Test
  fun unaCancellazioneGiaInCorsoSiMostraSubito_eSenzaNienteDaCancellareEFatto() {
    val eraser = ScriptedEraser(preview())
    eraser.status.value = status(EraseStep.LOCAL_DONE, StepState.DONE, StepState.WAITING, EraseWait.FROZEN)
    val controller = FacesEraseController(eraser, Clock(), scope).also { it.load() }
    assertThat(controller.state.value).isEqualTo(EraseSheetState.Progress(StepState.DONE, StepState.WAITING, listOf(phone, tablet), EraseWait.FROZEN))
    val off = FacesEraseController(ScriptedEraser(preview(digest = 0)), Clock(), scope).also { it.load() }
    assertThat(off.state.value).isEqualTo(EraseSheetState.Done)
  }

  @Test
  fun loStatoDellaPaginaDalloStatoDellaCancellazione() {
    assertThat(EraseSheetStates.of(status(EraseStep.DONE, StepState.DONE, StepState.DONE, null))).isEqualTo(EraseSheetState.Done)
    // Finita ma con una copia nel cestino di rete (E-F34): l'avanzamento con il motivo, che la pagina dice.
    assertThat(EraseSheetStates.of(status(EraseStep.DONE, StepState.DONE, StepState.DONE, EraseWait.RECYCLE_BIN)))
      .isEqualTo(EraseSheetState.Progress(StepState.DONE, StepState.DONE, listOf(phone, tablet), EraseWait.RECYCLE_BIN))
    // Con la recinzione il NAS non serve: mancano solo i dispositivi = dispositivi mancanti.
    assertThat(EraseSheetStates.of(status(EraseStep.VERIFIED, StepState.DONE, StepState.NOT_NEEDED, EraseWait.DEVICES))).isEqualTo(EraseSheetState.WaitingDevices(listOf(tablet)))
    assertThat(EraseSheetStates.of(status(EraseStep.LOCAL_DONE, StepState.DONE, StepState.WAITING, EraseWait.NAS)))
      .isEqualTo(EraseSheetState.Progress(StepState.DONE, StepState.WAITING, listOf(phone, tablet), EraseWait.NAS))
    assertThat(EraseSheetStates.progress(status(EraseStep.DONE, StepState.DONE, StepState.DONE, null)).local).isEqualTo(StepState.DONE)
  }
}
