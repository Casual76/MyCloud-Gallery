package dev.pampa.mycloudgallery.feature.integrations.drag

import android.content.ClipData
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.DragFlags
import dev.pampa.mycloudgallery.core.library.contract.DragLimits
import dev.pampa.mycloudgallery.core.library.contract.DragOrigin
import dev.pampa.mycloudgallery.core.library.contract.DragPrepared
import dev.pampa.mycloudgallery.core.library.contract.DragRefusal
import dev.pampa.mycloudgallery.core.library.testing.FakeMediaDragHandler
import dev.pampa.mycloudgallery.core.model.MediaId
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 10 (P6, D20, E-T12, E-T17): `HandlerDragPort`, la porta delle schermate sopra `MediaDragHandler`. Gli id diventano una `DragRequest` (mai con "Rimuovi la posizione" deciso dalla
 * schermata); se la preparazione dura piu' di 300 ms si dice "Preparo..." e quando finisce si toglie; un rifiuto si dice con la frase del suo motivo; un id che non e' un media, un'origine
 * sconosciuta o degli id ripetuti non arrivano alla porta; il dito che si alza (annullamento) non lascia ne' un messaggio di rifiuto ne' un "Preparo..." acceso.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class HandlerDragPortTest {

  private class RecordingNotices : DragNotices {
    val events = mutableListOf<String>()
    val refusals = mutableListOf<DragRefusal>()

    override fun preparing() {
      events += "preparing"
    }

    override fun finished() {
      events += "finished"
    }

    override fun refused(reason: DragRefusal) {
      events += "refused"
      refusals += reason
    }
  }

  private val notices = RecordingNotices()
  private var released = 0
  private val ids = (1L..3L).map { MediaId(UUID(it, it).toString()) }

  private fun ready(count: Int = 1) = DragPrepared.Ready(
    ClipData.newRawUri("", Uri.parse("content://test.pkg.integrations.documents/document/aaaaaaaaaaaaaaaaaaaaaaaa")),
    DragFlags.ALLOWED,
    count,
  ) { released++ }

  @Test
  fun unTrascinamentoProntoDiventaIDatiPerAndroidEUnaRichiestaSenzaLaPosizioneDecisaDallaSchermata() = runTest {
    val handler = FakeMediaDragHandler(answer = { ready(it.ids.size) })
    val port = HandlerDragPort(handler, notices)

    val drag = port.prepare(DragOrigin.ALBUM.code, ids.map { it.value })!!

    assertThat(drag.count).isEqualTo(3)
    assertThat(drag.flags).isEqualTo(DragFlags.ALLOWED)
    val request = handler.requests.single()
    assertThat(request.origin).isEqualTo(DragOrigin.ALBUM)
    assertThat(request.ids).containsExactlyElementsIn(ids).inOrder()
    assertThat(request.removeLocation).isFalse()
    // Se il trascinamento non parte, "scartare" rilascia il prestito (una volta sola e solo qui).
    assertThat(released).isEqualTo(0)
    drag.discard()
    assertThat(released).isEqualTo(1)
    assertThat(notices.events).containsExactly("finished")
  }

  @Test
  fun unaPreparazioneVelocePiuDiTrecentoMsNonDiceNiente() = runTest {
    val port = HandlerDragPort(FakeMediaDragHandler(delayMs = DragLimits.PREPARING_NOTICE_MS - 1, answer = { ready() }), notices)
    val pending = async { port.prepare(DragOrigin.LIBRARY.code, listOf(ids[0].value)) }
    advanceTimeBy(DragLimits.PREPARING_NOTICE_MS)
    runCurrent()
    assertThat(pending.await()).isNotNull()
    assertThat(notices.events).containsExactly("finished")
  }

  @Test
  fun oltreTrecentoMsSiDicePreparoEFinitoSiToglie() = runTest {
    val port = HandlerDragPort(FakeMediaDragHandler(delayMs = 2_000, answer = { ready() }), notices)
    val pending = async { port.prepare(DragOrigin.MEMORY.code, listOf(ids[0].value)) }

    advanceTimeBy(DragLimits.PREPARING_NOTICE_MS - 1)
    runCurrent()
    assertThat(notices.events).isEmpty()
    advanceTimeBy(2)
    runCurrent()
    assertThat(notices.events).containsExactly("preparing")
    advanceTimeBy(5_000)
    assertThat(pending.await()).isNotNull()
    assertThat(notices.events).containsExactly("preparing", "finished").inOrder()
  }

  @Test
  fun unRifiutoSiDiceConLaSuaFraseENienteParte() = runTest {
    for (reason in DragRefusal.entries) {
      val local = RecordingNotices()
      val port = HandlerDragPort(FakeMediaDragHandler(answer = { DragPrepared.Refused(reason) }), local)

      assertThat(port.prepare(DragOrigin.SEARCH.code, listOf(ids[0].value))).isNull()

      assertThat(local.refusals).containsExactly(reason)
      assertThat(local.events).containsExactly("refused", "finished").inOrder()
    }
  }

  @Test
  fun ognuniMotivoHaLaSuaFrase_SenzaDatiDentro() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val texts = DragRefusal.entries.map { context.getString(ToastDragNotices.textOf(it)) }
    assertThat(texts).containsNoDuplicates()
    for (text in texts) {
      assertThat(text).isNotEmpty()
      assertThat(text).doesNotContain("%")
    }
    assertThat(context.getString(dev.pampa.mycloudgallery.feature.integrations.R.string.integrations_drag_preparing)).isEqualTo("Preparo…")
  }

  @Test
  fun unIdCheNonEUnMediaUnaOrigineSconosciutaOIdRipetutiNonArrivanoAllaPorta() = runTest {
    val handler = FakeMediaDragHandler(answer = { ready() })
    val port = HandlerDragPort(handler, notices)

    assertThat(port.prepare(DragOrigin.LIBRARY.code, listOf("non-un-id"))).isNull()
    assertThat(port.prepare(DragOrigin.LIBRARY.code, emptyList())).isNull()
    assertThat(port.prepare(DragOrigin.LIBRARY.code, listOf(ids[0].value, ids[0].value))).isNull()
    assertThat(port.prepare("hidden", listOf(ids[0].value))).isNull()
    assertThat(port.prepare("", listOf(ids[0].value))).isNull()
    assertThat(handler.requests).isEmpty()
    // Nessun messaggio: non e' un rifiuto motivato, e' un gesto che non porta niente.
    assertThat(notices.refusals).isEmpty()
  }

  @Test
  fun alzareIlDitoMentreSiPreparaAnnullaSenzaRifiutoENonLasciaIlPreparo() = runTest {
    val port = HandlerDragPort(FakeMediaDragHandler(delayMs = 60_000, answer = { ready() }), notices)
    val pending = async(start = CoroutineStart.UNDISPATCHED) { port.prepare(DragOrigin.VIEWER.code, listOf(ids[0].value)) }
    advanceTimeBy(1_000)
    runCurrent()
    assertThat(notices.events).containsExactly("preparing")

    pending.cancel()
    runCurrent()

    assertThat(pending.isCancelled).isTrue()
    assertThat(notices.refusals).isEmpty()
    assertThat(notices.events).containsExactly("preparing", "finished").inOrder()
    assertThat(released).isEqualTo(0)
  }
}
