package dev.pampa.mycloudgallery.nas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.hidden.HiddenWorldBuilder
import dev.pampa.mycloudgallery.hidden.WorldSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CS-11 e CG-2 (fase I): la porta vera delle miniature di Cartelle NAS ([nasLibraryThumbnails]) sul catalogo vero, con nascosti, gruppi con un
 * nascosto e dati a meta' (`HiddenWorldBuilder`). Un media che non deve mai uscire dalle superfici dell'istantanea non ha richiesta; le celle della
 * Libreria la hanno; e la porta segue la Libreria visibile **di adesso**: quando l'istantanea cambia (qui: non c'e' piu') le richieste si rifiutano
 * e chi osserva [dev.pampa.mycloudgallery.feature.nas.contract.NasThumbnails.visible] vede il cambio.
 */
@RunWith(AndroidJUnit4::class)
class NasLibraryThumbnailsTest {

  @Test
  fun unMediaCheNonSiDeveVedereNonHaRichiestaELeCelleDellaLibreriaSi() = runBlocking<Unit> {
    val world = HiddenWorldBuilder.build(ApplicationProvider.getApplicationContext<Context>(), WorldSpec.random(31, 40))
    try {
      val snapshots = MutableStateFlow<TimelineSnapshot?>(world.snapshot)
      val thumbnails = nasLibraryThumbnails(loader = null, snapshots = snapshots)

      assertThat(world.mustNeverShow).isNotEmpty()
      for (id in world.mustNeverShow) assertWithMessage("un media che non si deve vedere ha una richiesta").that(thumbnails.request(id)).isNull()
      val withRequest = world.libraryCells.count { thumbnails.request(it) != null }
      assertThat(withRequest).isGreaterThan(0)

      // La Libreria visibile cambia: la porta lo dice a chi osserva e non da' piu' richieste dell'istantanea di prima.
      snapshots.value = null
      assertThat(thumbnails.visible.value).isNull()
      for (id in world.libraryCells) assertThat(thumbnails.request(id)).isNull()
    } finally {
      world.close()
    }
  }
}
