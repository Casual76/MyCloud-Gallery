package dev.pampa.mycloudgallery.feature.viewer

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.similar.ChoiceOutcome
import dev.pampa.mycloudgallery.core.library.similar.CopyPlace
import dev.pampa.mycloudgallery.core.library.similar.SimilarCopies
import dev.pampa.mycloudgallery.core.library.similar.SimilarCopiesGroup
import dev.pampa.mycloudgallery.core.library.similar.SimilarCopy
import dev.pampa.mycloudgallery.core.library.testing.FakeSimilarCopies
import dev.pampa.mycloudgallery.core.model.MediaGroupId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.feature.viewer.info.SimilarCopiesViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Lo stato della sezione "N copie" (Fase 5A, P15): carica il gruppo, applica "Mostra questa come principale" e "Separa" con la conferma. */
@OptIn(ExperimentalCoroutinesApi::class)
class SimilarCopiesViewModelTest {

  private val phone = MediaId.random()
  private val cloud = MediaId.random()
  private val old = MediaId.random()

  private fun copy(id: MediaId, width: Int) =
    SimilarCopy(id, MediaKind.PHOTO, setOf(CopyPlace.PHONE), width, width * 3 / 4, 1_000_000L, "image/jpeg", null, cameraOriginal = false)

  private fun group(members: List<MediaId>, cover: MediaId = members.first()) = SimilarCopiesGroup(
    MediaGroupId.random(), cover, members.mapIndexed { i, id -> copy(id, 4000 - i * 1000) }, coverChosenByUser = false, hidden = false,
  )

  private fun TestScope.model(source: SimilarCopies) = SimilarCopiesViewModel(source, scope = this)

  @Test
  fun mostraIlGruppoDelMediaEUnoSenzaCopieNonHaSezione() = runTest(StandardTestDispatcher()) {
    val fake = FakeSimilarCopies()
    fake.put(group(listOf(phone, cloud, old)))
    val model = model(fake)
    assertThat(model.state.value).isNull()
    model.show(cloud)
    advanceUntilIdle()
    assertThat(model.state.value!!.viewing).isEqualTo(cloud)
    assertThat(model.state.value!!.group.copies).hasSize(3)
    // Un altro media, senza copie.
    model.show(MediaId.random())
    advanceUntilIdle()
    assertThat(model.state.value).isNull()
  }

  @Test
  fun unaRichiestaNuovaAnnullaQuellaInCorso() = runTest(StandardTestDispatcher()) {
    val fake = FakeSimilarCopies()
    fake.put(group(listOf(phone, cloud)))
    fake.put(group(listOf(old, MediaId.random())))
    val model = model(fake)
    model.show(phone)
    model.show(old) // prima che la prima sia partita
    advanceUntilIdle()
    assertThat(model.state.value!!.viewing).isEqualTo(old)
  }

  @Test
  fun mostraQuestaComePrincipaleApplicaLaSceltaERileggeIlGruppo() = runTest(StandardTestDispatcher()) {
    val fake = FakeSimilarCopies()
    fake.put(group(listOf(phone, cloud, old), cover = phone))
    val model = model(fake)
    model.show(cloud)
    advanceUntilIdle()
    assertThat(model.state.value!!.group.cover).isEqualTo(phone)

    model.makeMain()
    assertThat(model.state.value!!.busy).isTrue() // un tocco alla volta
    model.makeMain() // un secondo tocco mentre lavora non fa niente
    advanceUntilIdle()
    assertThat(fake.chosenCovers).containsExactly(cloud)
    assertThat(model.state.value!!.busy).isFalse()
    assertThat(model.state.value!!.group.cover).isEqualTo(cloud)
    assertThat(model.state.value!!.group.coverChosenByUser).isTrue()
  }

  @Test
  fun separaChiedeConfermaESoloDopoSepara() = runTest(StandardTestDispatcher()) {
    val fake = FakeSimilarCopies()
    fake.put(group(listOf(phone, cloud, old)))
    val model = model(fake)
    model.show(old)
    advanceUntilIdle()

    // Chiedere non separa; annullare nemmeno.
    model.askSeparate()
    assertThat(model.state.value!!.confirmSeparate).isTrue()
    assertThat(fake.separated).isEmpty()
    model.cancelSeparate()
    assertThat(model.state.value!!.confirmSeparate).isFalse()
    // Confermare senza aver chiesto non fa niente (un tocco in ritardo).
    model.confirmSeparate()
    advanceUntilIdle()
    assertThat(fake.separated).isEmpty()

    model.askSeparate()
    model.confirmSeparate()
    advanceUntilIdle()
    assertThat(fake.separated).containsExactly(old)
    // La copia separata non ha piu' compagne: la sezione sparisce. Le altre due restano insieme.
    assertThat(model.state.value).isNull()
    model.show(phone)
    advanceUntilIdle()
    assertThat(model.state.value!!.group.copies.map { it.mediaId }).containsExactly(phone, cloud)
  }

  @Test
  fun unaRilettaNonChiudeIlFoglioDiConfermaMaUnAltroMediaSi() = runTest(StandardTestDispatcher()) {
    val fake = FakeSimilarCopies()
    fake.put(group(listOf(phone, cloud, old)))
    val model = model(fake)
    model.show(old)
    advanceUntilIdle()
    model.askSeparate()
    model.show(old) // il catalogo e' cambiato: si rilegge
    advanceUntilIdle()
    assertThat(model.state.value!!.confirmSeparate).isTrue()
    model.show(cloud) // un'altra pagina
    advanceUntilIdle()
    assertThat(model.state.value!!.viewing).isEqualTo(cloud)
    assertThat(model.state.value!!.confirmSeparate).isFalse()
  }

  @Test
  fun separareUnaCopiaDaDueScioglieIlGruppoPerTutte() = runTest(StandardTestDispatcher()) {
    val fake = FakeSimilarCopies()
    fake.put(group(listOf(phone, cloud)))
    val model = model(fake)
    model.show(cloud)
    advanceUntilIdle()
    model.askSeparate()
    model.confirmSeparate()
    advanceUntilIdle()
    assertThat(model.state.value).isNull()
    model.show(phone)
    advanceUntilIdle()
    assertThat(model.state.value).isNull()
  }

  @Test
  fun unRilevatoreCheSbagliaNonFermaIlViewer() = runTest(StandardTestDispatcher()) {
    val broken = object : SimilarCopies by FakeSimilarCopies() {
      override suspend fun groupOf(mediaId: MediaId): SimilarCopiesGroup? = error("guasto")

      override suspend fun chooseCover(mediaId: MediaId): ChoiceOutcome = error("guasto")
    }
    val model = model(broken)
    model.show(phone)
    advanceUntilIdle()
    assertThat(model.state.value).isNull()

    // Con un gruppo che c'e' ma una scelta che non va: si rilegge e resta com'era.
    var fail = false
    val fake = FakeSimilarCopies()
    fake.put(group(listOf(phone, cloud)))
    val flaky = object : SimilarCopies by fake {
      override suspend fun chooseCover(mediaId: MediaId): ChoiceOutcome = if (fail) error("guasto") else fake.chooseCover(mediaId)
    }
    val second = model(flaky)
    second.show(cloud)
    advanceUntilIdle()
    fail = true
    second.makeMain()
    advanceUntilIdle()
    assertThat(second.state.value!!.busy).isFalse()
    assertThat(second.state.value!!.group.cover).isEqualTo(phone)
  }
}
