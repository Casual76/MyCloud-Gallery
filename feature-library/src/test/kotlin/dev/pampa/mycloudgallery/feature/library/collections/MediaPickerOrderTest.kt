package dev.pampa.mycloudgallery.feature.library.collections

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import java.util.UUID
import org.junit.Test

/**
 * Fase 7 (P13): il selettore dei media degli album consegna gli id scelti **nell'ordine della Libreria** (dal piu' recente), qualunque sia l'ordine
 * dei tocchi, e salta quelli che la vista non ha piu' (un media nascosto un attimo fa non esce dal selettore, E-H1).
 */
class MediaPickerOrderTest {

  @Test
  fun gliIdSceltiEsconoNellOrdineDellaLibreria() {
    val library = SyntheticLibrary(count = 30)
    val view = library.view()
    val ids = List(view.size) { view.snapshot.idAt(view.snapshotIndex(it)) }
    val chosen = linkedSetOf(ids[12], ids[3], ids[27], ids[0])
    val gone = MediaId(UUID(99L, 99L).toString())

    assertThat(pickedInOrder(view, chosen + gone)).containsExactly(ids[0], ids[3], ids[12], ids[27]).inOrder()
    assertThat(pickedInOrder(view, emptySet())).isEmpty()
  }
}
