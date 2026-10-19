package dev.pampa.mycloudgallery.feature.integrations

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.integrations.contract.IntegrationPorts
import dev.pampa.mycloudgallery.feature.integrations.receive.ReceivePorts
import dev.pampa.mycloudgallery.feature.integrations.receive.ShareReceiverActivity
import java.io.FileNotFoundException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 5B (P0-B, D29, C17): i componenti approvati **non agiscono** da soli. La ricezione, con le porte non installate (l'app non e' pronta), non
 * legge l'intento ne' apre un flusso: mostra solo che l'app non e' pronta. Un intento che non e' una condivisione di media chiude subito con
 * `RESULT_CANCELED`. Dal P11 la ricezione e' piena (copia, tetti, "Salva sul NAS"): le sue prove stanno in `receive/`. Il selettore e il provider dei
 * documenti non sono scheletri dal P10: i loro test sono in `picker/` e `provider/`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class IntegrationSkeletonsTest {

  private val context: Context get() = ApplicationProvider.getApplicationContext()

  /** Un provider di un'"altra app" che conta le aperture: chi riceve una condivisione non deve aprire nessun flusso. */
  class CountingProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
      queries++
      return null
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
      opens++
      throw FileNotFoundException("non si apre")
    }

    companion object {
      var opens = 0
      var queries = 0
    }
  }

  private fun assertCanceledAndGone(activity: Activity) {
    assertThat(activity.isFinishing).isTrue()
    assertThat(shadowOf(activity).resultCode).isEqualTo(Activity.RESULT_CANCELED)
    assertThat(shadowOf(activity).resultIntent).isNull()
  }

  @Test
  fun laRicezioneSenzaLePorteInstallateNonApreNessunFlusso() {
    IntegrationPorts.reset()
    ReceivePorts.reset()
    CountingProvider.opens = 0
    CountingProvider.queries = 0
    Robolectric.buildContentProvider(CountingProvider::class.java).create("test.stream")
    val uri = Uri.parse("content://test.stream/1")
    val one = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
    Robolectric.buildActivity(ShareReceiverActivity::class.java, one).create().start().resume().visible().get()
    // Un file:// e un provider nostro (gli ingressi ostili del D24) non fanno aprire niente nemmeno qui, e non fanno eccezioni.
    val hostile = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
      .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(Uri.parse("file:///data/data/x/databases/gallery.db"), uri))
    Robolectric.buildActivity(ShareReceiverActivity::class.java, hostile).create().start().resume().visible().get()
    assertThat(CountingProvider.opens).isEqualTo(0)
    assertThat(CountingProvider.queries).isEqualTo(0)
  }

  @Test
  fun unIntentoCheNonEUnaCondivisioneChiudeSubitoAnnullato() {
    assertCanceledAndGone(Robolectric.buildActivity(ShareReceiverActivity::class.java, Intent(Intent.ACTION_VIEW)).create().get())
    assertCanceledAndGone(Robolectric.buildActivity(ShareReceiverActivity::class.java, Intent(Intent.ACTION_SEND).setType("text/plain")).create().get())
  }
}
