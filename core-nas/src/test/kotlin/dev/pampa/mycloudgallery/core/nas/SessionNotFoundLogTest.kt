package dev.pampa.mycloudgallery.core.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.errorOrNull
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * O-B11 delle prove sul campo (gruppo B): nel log di uno spostamento da 941 elementi finito bene comparivano due righe
 * `operazione fallita: Failure(NotFound, FINAL)`. "Non c'e'" e' una risposta normale (chi chiama la aspetta e la tratta da solo: un media
 * spostato dall'app mentre la Libreria o la preparazione lo stavano aprendo, un temporaneo o una cartella gia' tolti alla ripresa), non un
 * fallimento: il log non la chiama cosi'. Un guasto vero invece continua a chiamarsi "operazione fallita".
 */
class SessionNotFoundLogTest {

  private val lines = CopyOnWriteArrayList<String>()

  @After
  fun uninstall() = GalleryLog.uninstall()

  private fun TestScope.harness() = Harness(backgroundScope, virtualClock())

  private fun capture() {
    GalleryLog.install { _, tag, message, _ -> if (tag == "NasSession") lines += message }
  }

  @Test
  fun unaVoceAssenteNonSiRegistraComeFallimento() = runTest {
    val h = harness()
    capture()

    val error = h.manager.mediaReader().open(SHARE, SharePath.of("manca.jpg"), Purpose.BACKGROUND).errorOrNull()

    // Chi chiama riceve `NotFound`, com'e' sempre stato: cambia solo come lo racconta il log.
    assertThat(error).isEqualTo(NasError.NotFound)
    assertThat(lines.filter { it.contains("NotFound") }).isNotEmpty()
    assertThat(lines.filter { it.contains("operazione fallita") && it.contains("NotFound") }).isEmpty()
    assertThat(lines.any { it.contains("voce assente") }).isTrue()
  }

  @Test
  fun unGuastoVeroSiRegistraAncoraComeFallimento() = runTest {
    val h = harness()
    h.backend.putContent(SHARE, "cartella/uno.jpg", NasContent.of(byteArrayOf(1)))
    h.backend.faults = FaultPlan().at(1, FaultPhase.BEFORE, SimFault.DropConnection, SimOp.OPEN_DIRECTORY)
    capture()

    h.manager.mediaReader().list(SHARE, SharePath.of("cartella"), Purpose.BACKGROUND) { }

    assertThat(lines.any { it.contains("operazione fallita") }).isTrue()
    assertThat(lines.none { it.contains("voce assente") }).isTrue()
  }
}
