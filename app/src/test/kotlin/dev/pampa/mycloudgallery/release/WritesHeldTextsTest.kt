package dev.pampa.mycloudgallery.release

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CA-1 della revisione funzionale della Fase 10: le frasi "scritture ferme" che **non** hanno una variante per il manifest firmato (cestino, doppioni, operazioni sui
 * file, viewer, cancellazione dei volti, "Ricevuti in coda") sono neutre: valgono per l'emergenza dell'utente, per il congelamento **e** per una versione da aggiornare.
 * Quindi nominano la versione e non promettono che basta riprovare o aspettare (con il manifest i blocchi non scadono: passano solo aggiornando l'app). Le frasi con la
 * loro variante (Backup, album, piede della Libreria, Diagnostica) le provano i loro moduli e `ReleaseGatesAppTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WritesHeldTextsTest {

  private val app: Application = ApplicationProvider.getApplicationContext()

  private val neutral: Map<String, Int> = mapOf(
    "cestino del NAS" to dev.pampa.mycloudgallery.feature.library.R.string.trash_notice_blocked,
    "doppioni" to dev.pampa.mycloudgallery.feature.library.R.string.duplicates_notice_cleanup_blocked,
    "operazioni in pausa" to dev.pampa.mycloudgallery.feature.nas.R.string.nas_ops_pause_writes_stopped,
    "piano rifiutato" to dev.pampa.mycloudgallery.feature.nas.R.string.nas_reject_writes_blocked,
    "operazioni ferme" to dev.pampa.mycloudgallery.feature.nas.R.string.nas_writes_blocked,
    "viewer" to dev.pampa.mycloudgallery.feature.viewer.R.string.viewer_nas_done_blocked,
    "cancellazione dei volti" to dev.pampa.mycloudgallery.feature.people.R.string.faces_erase_wait_emergency_stop,
    "ricevuti in coda" to dev.pampa.mycloudgallery.feature.backup.R.string.backup_received_status_frozen,
  )

  @Test
  fun ca1_leFrasiNeutreNominanoLaVersioneENonPromettonoDiRiprovare() {
    for ((where, res) in neutral) {
      val text = app.getString(res).lowercase()
      assertWithMessage("$where: nomina la versione da aggiornare").that(text).contains("versione")
      for (promise in listOf("riprova più tardi", "riprendo da sola", "riaccendi", "riattiv")) {
        assertWithMessage("$where: non promette \"$promise\"").that(text).doesNotContain(promise)
      }
    }
  }
}
