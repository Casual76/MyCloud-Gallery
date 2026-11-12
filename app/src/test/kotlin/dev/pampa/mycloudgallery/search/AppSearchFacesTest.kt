package dev.pampa.mycloudgallery.search

import android.app.Application
import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.library.collections.CollectionFaceCrop
import dev.pampa.mycloudgallery.feature.library.collections.CollectionFaceCrops
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CS-11 (giro 1 della fase I): il cancello dei volti della ricerca ha una prova sua. [AppSearchFaces] chiede il ritaglio alla stessa porta delle Raccolte
 * (`CollectionFaceCrops`, il cancello dei media mostrabili) e consegna un volto **solo** per una persona con un nome e non nascosta, solo se il cancello
 * lo permette e solo con le sue foto d'origine; le revoche sono quelle delle Raccolte. Ogni prova cade se si toglie il suo controllo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppSearchFacesTest {

  private val anna = PersonId("00000000-0000-4000-8000-000000000007")
  private val hidden = PersonId("00000000-0000-4000-8000-000000000008")
  private val annaFace = FaceLocator(ContentKey("%064x".format(1)), 0, FaceBox(10, 10, 90, 90))
  private val origin = MediaId.random()

  /** Il cancello finto delle Raccolte: permette o rifiuta, puo' dare un ritaglio senza origini, conta le domande, ha il suo segno. */
  private class Gate(private val origin: MediaId) : CollectionFaceCrops {
    var shown = true
    var noOrigins = false
    var asked = 0
    val token = MutableStateFlow(0)

    override suspend fun crop(face: FaceLocator, sizePx: Int): CollectionFaceCrop? {
      asked++
      if (!shown) return null
      return CollectionFaceCrop(Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888), if (noOrigins) emptySet() else setOf(origin))
    }

    override val revocations: Flow<Any?> get() = token
  }

  /** Solo Anna ha un nome e non e' nascosta: e' la sola con un volto di copertina. */
  private fun faces(gate: Gate) = AppSearchFaces({ person -> if (person == anna) annaFace else null }, gate)

  @Test
  fun conIlPermessoIlVoltoArrivaConLeSueFotoDOrigine_CS11() = runTest {
    val gate = Gate(origin)
    val face = faces(gate).face(anna, 64)
    assertThat(face).isNotNull()
    assertThat(face!!.origins).containsExactly(origin)
    assertThat(face.bitmap.width).isEqualTo(64)
  }

  @Test
  fun nessunaFotoDOrigineMostrabile_NessunVolto_CS11() = runTest {
    val gate = Gate(origin).apply { shown = false }
    assertThat(faces(gate).face(anna, 64)).isNull()
    assertThat(gate.asked).isEqualTo(1)
  }

  @Test
  fun unRitaglioSenzaFotoDOrigineNonSiConsegna_CS11() = runTest {
    val gate = Gate(origin).apply { noOrigins = true }
    assertThat(faces(gate).face(anna, 64)).isNull()
  }

  @Test
  fun unaPersonaSenzaNomeONascostaNonHaVolto_ENonSiChiedeNemmeno_CS11() = runTest {
    val gate = Gate(origin)
    assertThat(faces(gate).face(hidden, 64)).isNull()
    assertThat(gate.asked).isEqualTo(0)
  }

  @Test
  fun leRevocheSonoQuelleDelleRaccolte_CG1() = runTest {
    val gate = Gate(origin)
    val search = faces(gate)
    assertThat(search.revocations.first()).isEqualTo(0)
    gate.token.value = 1
    assertThat(search.revocations.first()).isEqualTo(1)
  }
}
