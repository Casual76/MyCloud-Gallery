package dev.pampa.mycloudgallery.gallery

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.LocalMediaActions
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/** Un errore del catalogo su preferiti o nascosti non chiude l'app; l'annullamento passa (P11). */
class GuardedMediaActionsTest {

  private val selected = setOf(MediaId("00000000-0000-4000-8000-000000000001"))

  private class Failing(private val error: Exception) : LocalMediaActions {
    var calls = 0

    override suspend fun setFavorite(ids: Set<MediaId>, on: Boolean) {
      calls++
      throw error
    }

    override suspend fun setHidden(ids: Set<MediaId>, on: Boolean) {
      calls++
      throw error
    }
  }

  @Test
  fun unErroreDelCatalogoNonEsce() = runBlocking {
    val inner = Failing(IllegalArgumentException("Rotazione non valida."))
    val actions = GuardedMediaActions(inner)

    actions.setHidden(selected, true)
    actions.setFavorite(selected, true)

    assertThat(inner.calls).isEqualTo(2)
  }

  @Test
  fun lAnnullamentoPassa() {
    val actions = GuardedMediaActions(Failing(CancellationException("via")))

    assertThrows(CancellationException::class.java) { runBlocking { actions.setHidden(selected, true) } }
  }
}
