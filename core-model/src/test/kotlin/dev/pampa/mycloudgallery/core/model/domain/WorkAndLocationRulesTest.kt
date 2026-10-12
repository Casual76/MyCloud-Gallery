package dev.pampa.mycloudgallery.core.model.domain

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.Fixtures.DAY
import dev.pampa.mycloudgallery.core.model.Fixtures.T0
import dev.pampa.mycloudgallery.core.model.Fixtures.full
import dev.pampa.mycloudgallery.core.model.Fixtures.located
import dev.pampa.mycloudgallery.core.model.Fixtures.nas
import dev.pampa.mycloudgallery.core.model.Fixtures.original
import dev.pampa.mycloudgallery.core.model.Fixtures.phone
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.WorkErrorCode
import dev.pampa.mycloudgallery.core.model.WorkStage
import dev.pampa.mycloudgallery.core.model.WorkStatus
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkAndLocationRulesTest {

  private val v1 = VersionId.random()
  private val v2 = VersionId.random()

  @Test
  fun unMediaNuovoNonCalcolaIVoltiSenzaAttivazione() {
    val states = WorkRules.initialStates(MediaKind.PHOTO, v1, T0)
    assertThat(states.map { it.stage }).doesNotContain(WorkStage.FACES)
    assertThat(states.all { it.status == WorkStatus.PENDING && it.versionId == v1 }).isTrue()
  }

  /** E-M10: un file corrotto si arrende dopo troppi tentativi, senza bloccare gli altri. */
  @Test
  fun dopoTroppiFallimentiLaFaseSiArrende() {
    var state = WorkRules.pending(WorkStage.THUMB, v1, T0)
    repeat(WorkRules.MAX_ATTEMPTS - 1) {
      state = WorkRules.failed(state, WorkErrorCode("DECODE_FAILED"), T0)
      assertThat(state.status).isEqualTo(WorkStatus.FAILED)
    }
    state = WorkRules.failed(state, WorkErrorCode("DECODE_FAILED"), T0)
    assertThat(state.status).isEqualTo(WorkStatus.GAVE_UP)
    assertThat(state.attempts).isEqualTo(WorkRules.MAX_ATTEMPTS)
    assertThat(state.lastError).isEqualTo(WorkErrorCode("DECODE_FAILED"))
  }

  @Test
  fun iCodiciDErroreNonPossonoContenereTestoLibero() {
    assertThrows(IllegalArgumentException::class.java) { WorkErrorCode("file /storage/emulated/0/DCIM/a.jpg corrotto") }
    assertThrows(IllegalArgumentException::class.java) { WorkErrorCode("decode_failed") }
  }

  @Test
  fun unaNuovaVersioneRiapreSoloLeFasiIndicate() {
    val done = WorkStage.entries.map { WorkRules.succeeded(WorkRules.pending(it, v1, T0), v1, T0) }
    val gaveUp = done.map { if (it.stage == WorkStage.OCR) it.copy(status = WorkStatus.GAVE_UP, attempts = 5) else it }

    val after = WorkRules.invalidate(gaveUp, setOf(WorkStage.META, WorkStage.OCR), v2, T0 + 1)

    after.forEach {
      if (it.stage == WorkStage.META || it.stage == WorkStage.OCR) {
        assertThat(it.status).isEqualTo(WorkStatus.PENDING)
        assertThat(it.versionId).isEqualTo(v2)
        assertThat(it.attempts).isEqualTo(0)
      } else {
        assertThat(it.status).isEqualTo(WorkStatus.DONE)
        assertThat(it.versionId).isEqualTo(v1)
      }
    }
  }

  @Test
  fun nellUnioneVinceIlLavoroGiaFattoSullaVersioneCorrente() {
    val survivor = listOf(WorkRules.pending(WorkStage.EMBED, v2, T0), WorkRules.succeeded(WorkRules.pending(WorkStage.META, v1, T0), v1, T0))
    val loser = listOf(WorkRules.succeeded(WorkRules.pending(WorkStage.EMBED, v2, T0), v2, T0), WorkRules.pending(WorkStage.OCR, v2, T0))

    val merged = WorkRules.merge(survivor, loser, currentVersionId = v2).associateBy { it.stage }

    assertThat(merged.getValue(WorkStage.EMBED).status).isEqualTo(WorkStatus.DONE)
    assertThat(merged.getValue(WorkStage.META).versionId).isEqualTo(v1)
    assertThat(merged.getValue(WorkStage.OCR).status).isEqualTo(WorkStatus.PENDING)
  }

  // --- Posizioni ---------------------------------------------------------------------------------

  @Test
  fun unaPosizioneSparitaRicordaLaPrimaAssenzaEScadeDopoLaTolleranza() {
    val v = original(full(1))
    val onPhone = located(1, v, phone("a.jpg", 10))

    val gone = LocationRules.vanished(onPhone, T0 + DAY)
    assertThat(gone.state).isEqualTo(LocationState.ABSENT)
    assertThat(LocationRules.vanished(gone, T0 + 5 * DAY).absentSinceMillis).isEqualTo(T0 + DAY)

    assertThat(LocationRules.isExpired(gone, T0 + 60 * DAY)).isFalse()
    assertThat(LocationRules.isExpired(gone, T0 + 61 * DAY)).isTrue()

    val goneFromNas = LocationRules.vanished(located(2, v, nas("a.jpg")), T0)
    assertThat(LocationRules.isExpired(goneFromNas, T0 + 29 * DAY)).isFalse()
    assertThat(LocationRules.isExpired(goneFromNas, T0 + 30 * DAY)).isTrue()

    assertThat(LocationRules.isExpired(onPhone, T0 + 365 * DAY)).isFalse()
  }

  @Test
  fun unContenitoreIrraggiungibileNonRendeAssenteUnFile() {
    val v = original(full(1))
    val onSd = located(1, v, phone("a.jpg", 10, volume = "1a2b-3c4d"))
    assertThat(LocationRules.unavailable(onSd).state).isEqualTo(LocationState.UNAVAILABLE)
    val gone = LocationRules.vanished(onSd, T0)
    assertThat(LocationRules.unavailable(gone)).isEqualTo(gone)
  }
}
