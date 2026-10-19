package dev.pampa.mycloudgallery.gallery

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.android.AndroidPhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.integration.GalleryRig
import java.io.File
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Revisione di sicurezza della Fase 3, I8: condividere o cestinare una selezione molto grande non fallisce in silenzio.
 * Oltre il tetto (le transazioni Binder portano gli URI tre volte; `createTrashRequest` ne accetta al massimo 2.000) i
 * comandi dicono di no **senza provarci** e la Libreria avvisa ("Puoi condividere al massimo 500 elementi alla volta");
 * se Android rifiuta comunque l'avvio (`TransactionTooLargeException`, rilanciata come `RuntimeException`) la risposta e'
 * falso e la Libreria lo dice. L'avviso a schermo e' provato in `LibraryActionNoticeTest` di `:feature-library`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppLibraryCommandsTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val rigs = mutableListOf<GalleryRig>()

  /** Le azioni di sistema registrate: la condivisione e il cestino, con un `IntentSender` vero (Robolectric). */
  private inner class RecordingIntents : PhoneMediaIntents {
    val shared = mutableListOf<List<MediaRef.Phone>>()
    val trashed = mutableListOf<List<MediaRef.Phone>>()

    override val canTrash: Boolean = true

    override fun uriOf(ref: MediaRef.Phone): Uri = Uri.parse("content://media/${ref.volume}/${ref.mediaStoreId}")

    override fun share(refs: List<MediaRef.Phone>): Intent? {
      shared += refs
      return Intent(Intent.ACTION_SEND_MULTIPLE)
    }

    override fun trashRequest(refs: List<MediaRef.Phone>, trash: Boolean): IntentSender? {
      trashed += refs
      return PendingIntent.getActivity(app, 0, Intent(), PendingIntent.FLAG_IMMUTABLE).intentSender
    }

    // Fase 5B (P0-B): le due richieste nuove di sistema non servono ai comandi della Libreria; qui non si fanno mai.
    override fun deleteRequest(refs: List<MediaRef.Phone>): IntentSender? = null

    override fun writeRequest(refs: List<MediaRef.Phone>): IntentSender? = null
  }

  private class RecordingHost(var accepts: Boolean = true) : LibraryCommandHost {
    var choosers = 0
    var trashRequests = 0

    override fun requestMediaAccess() = Unit

    override fun requestMediaLocation() = Unit

    override fun startChooser(intent: Intent): Boolean {
      choosers++
      return accepts
    }

    override fun launchTrashRequest(sender: IntentSender): Boolean {
      trashRequests++
      return accepts
    }
  }

  private val intents = RecordingIntents()
  private val host = RecordingHost()

  private fun ids(count: Int): Set<MediaId> = (1..count).map { MediaId(String.format("00000000-0000-4000-8000-%012d", it)) }.toSet()

  private fun commands(): AppLibraryCommands {
    val rig = GalleryRig(
      app = app,
      replica = File(temp.root, "replica"),
      preferences = File(temp.root, "library.preferences_pb"),
      store = GalleryRig.newStore(),
      access = GalleryRig.accessOf(MediaAccess.FULL),
      intents = intents,
    ).also { rigs += it }
    return AppLibraryCommands(rig.gallery) { id -> MediaRef.Phone(GalleryRig.PRIMARY, id.value.takeLast(12).toLong(), "image/jpeg") }
      .also { it.host = host }
  }

  @After
  fun tearDown() {
    rigs.forEach { it.close() }
  }

  @Test
  fun iTettiSonoQuelliDichiarati() {
    val commands = commands()

    assertThat(commands.shareLimit).isEqualTo(500)
    assertThat(commands.deleteLimit).isEqualTo(AndroidPhoneMediaIntents.MAX_TRASH_URIS)
  }

  @Test
  fun unaCondivisioneDentroIlTettoParte() {
    val commands = commands()

    assertThat(commands.share(ids(commands.shareLimit))).isTrue()

    assertThat(host.choosers).isEqualTo(1)
    assertThat(intents.shared.single()).hasSize(500)
  }

  @Test
  fun unaCondivisioneOltreIlTettoNonProvaNemmeno() {
    val commands = commands()

    assertThat(commands.share(ids(commands.shareLimit + 1))).isFalse()

    assertThat(host.choosers).isEqualTo(0)
    assertThat(intents.shared).isEmpty()
  }

  @Test
  fun seAndroidRifiutaIlSelettoreLaRispostaEFalso() {
    host.accepts = false
    val commands = commands()

    assertThat(commands.share(ids(3))).isFalse()
    assertThat(host.choosers).isEqualTo(1)
  }

  @Test
  fun senzaUnaSchermataCheLoOspitiNonParteEDiceDiNo() {
    val commands = commands()
    commands.host = null

    assertThat(commands.share(ids(3))).isFalse()
    assertThat(commands.deleteFromPhone(ids(3))).isFalse()
  }

  @Test
  fun unaRichiestaDiCestinoDentroIlTettoParte() {
    val commands = commands()

    assertThat(commands.deleteFromPhone(ids(commands.deleteLimit))).isTrue()

    assertThat(host.trashRequests).isEqualTo(1)
    assertThat(intents.trashed.single()).hasSize(2_000)
  }

  @Test
  fun unaRichiestaDiCestinoOltreIlTettoNonProvaNemmeno() {
    val commands = commands()

    assertThat(commands.deleteFromPhone(ids(commands.deleteLimit + 1))).isFalse()

    assertThat(host.trashRequests).isEqualTo(0)
    assertThat(intents.trashed).isEmpty()
  }

  @Test
  fun seIlDialogoDiSistemaNonParteLaRispostaEFalso() {
    host.accepts = false
    val commands = commands()

    assertThat(commands.deleteFromPhone(ids(5))).isFalse()
  }
}
