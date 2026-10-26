package dev.pampa.mycloudgallery.gallery

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.LocalMediaActions
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Revisione di sicurezza della Fase 3, R8: un errore del catalogo su preferiti o nascosti **arriva a chi ha chiesto**, che lo
 * dice all'utente; non e' piu' ingoiato (la selezione si chiudeva come se "Nascondi" fosse riuscito). L'annullamento passa
 * come tale, e quando va bene non cambia niente.
 */
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

  private class Recording : LocalMediaActions {
    val favorites = mutableListOf<Pair<Set<MediaId>, Boolean>>()
    val hidden = mutableListOf<Pair<Set<MediaId>, Boolean>>()

    override suspend fun setFavorite(ids: Set<MediaId>, on: Boolean) {
      favorites += ids to on
    }

    override suspend fun setHidden(ids: Set<MediaId>, on: Boolean) {
      hidden += ids to on
    }
  }

  @Test
  fun unErroreDelCatalogoArrivaAChiHaChiesto() {
    val inner = Failing(IllegalArgumentException("Rotazione non valida."))
    val actions = GuardedMediaActions(inner)

    assertThrows(IllegalArgumentException::class.java) { runBlocking { actions.setHidden(selected, true) } }
    assertThrows(IllegalArgumentException::class.java) { runBlocking { actions.setFavorite(selected, true) } }

    assertThat(inner.calls).isEqualTo(2)
  }

  @Test
  fun lAnnullamentoPassa() {
    val actions = GuardedMediaActions(Failing(CancellationException("via")))

    assertThrows(CancellationException::class.java) { runBlocking { actions.setHidden(selected, true) } }
  }

  @Test
  fun quandoVaBeneNonCambiaNiente() = runBlocking<Unit> {
    val inner = Recording()
    val actions = GuardedMediaActions(inner)

    actions.setHidden(selected, true)
    actions.setHidden(selected, false)
    actions.setFavorite(selected, true)

    assertThat(inner.hidden).containsExactly(selected to true, selected to false).inOrder()
    assertThat(inner.favorites).containsExactly(selected to true)
  }

  /** Fase 7 (P9, E-H2): il guardiano inoltra "quanti li vedono ancora i familiari"; il default 0 dell'interfaccia farebbe sparire l'avviso. */
  private class FamilyAware(private val answer: Int) : LocalMediaActions {
    val asked = mutableListOf<Set<MediaId>>()

    override suspend fun setFavorite(ids: Set<MediaId>, on: Boolean) = Unit

    override suspend fun setHidden(ids: Set<MediaId>, on: Boolean) = Unit

    override suspend fun familyStillSees(ids: Set<MediaId>): Int {
      asked += ids
      return answer
    }
  }

  @Test
  fun l_avvisoDeiFamiliariAttraversaIlGuardiano() = runBlocking<Unit> {
    val inner = FamilyAware(answer = 3)
    val actions = GuardedMediaActions(inner)

    assertThat(actions.familyStillSees(selected)).isEqualTo(3)
    assertThat(inner.asked).containsExactly(selected)
  }
}
