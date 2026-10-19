package dev.pampa.mycloudgallery.feature.integrations

import android.app.Activity
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
import dev.pampa.mycloudgallery.feature.integrations.receive.ShareReceiverActivity
import java.io.FileNotFoundException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * Fase 5B (P0-B, D29, C17): i componenti approvati sono **scheletri che non agiscono**. La ricezione chiude con `RESULT_CANCELED` senza leggere
 * l'intento ne' aprire un flusso (P11 la riempira'). Il selettore e il provider dei documenti non sono piu' scheletri dal P10: i loro test sono in
 * `picker/` e `provider/` (stesse garanzie: con le porte non installate non mostrano e non aprono niente, nemmeno con un intento ostile).
 */
@RunWith(AndroidJUnit4::class)
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
  fun laRicezioneNonApreNessunFlusso() {
    CountingProvider.opens = 0
    CountingProvider.queries = 0
    Robolectric.buildContentProvider(CountingProvider::class.java).create("test.stream")
    val uri = Uri.parse("content://test.stream/1")
    val one = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
    assertCanceledAndGone(Robolectric.buildActivity(ShareReceiverActivity::class.java, one).create().get())
    // Un file:// e un provider nostro (gli ingressi ostili del D24) non fanno aprire niente nemmeno qui, e non fanno eccezioni.
    val hostile = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
      .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(Uri.parse("file:///data/data/x/databases/gallery.db"), uri))
    assertCanceledAndGone(Robolectric.buildActivity(ShareReceiverActivity::class.java, hostile).create().get())
    assertThat(CountingProvider.opens).isEqualTo(0)
    assertThat(CountingProvider.queries).isEqualTo(0)
  }
}
