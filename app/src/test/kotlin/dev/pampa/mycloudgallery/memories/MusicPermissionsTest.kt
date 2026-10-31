package dev.pampa.mycloudgallery.memories

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * I permessi di lettura delle canzoni scelte con il selettore di sistema (I4 della revisione di sicurezza, 31/10): se ne rilascia uno (la canzone che si lascia) e, all'azzeramento,
 * **tutti**. Il sistema ricorda gli URI dei permessi persistiti anche dopo "dispositivo azzerato": senza il rilascio l'app terrebbe il diritto di leggere ogni file audio mai scelto.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MusicPermissionsTest {

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val first = Uri.parse("content://com.example.documenti/document/audio%3A1")
  private val second = Uri.parse("content://com.example.documenti/document/audio%3A2")

  private fun persisted(): List<Uri> = app.contentResolver.persistedUriPermissions.map { it.uri }

  @Test
  fun siRilasciaUnoESiRilasciaTutto() {
    app.contentResolver.takePersistableUriPermission(first, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    app.contentResolver.takePersistableUriPermission(second, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    assertThat(persisted()).containsExactly(first, second)
    // La canzone che si lascia: il suo permesso va via, quello dell'altra resta.
    MusicPermissions.release(app, first.toString())
    assertThat(persisted()).containsExactly(second)
    // Un permesso che non c'e' (piu') non e' un errore.
    MusicPermissions.release(app, first.toString())
    // L'azzeramento: tutti.
    app.contentResolver.takePersistableUriPermission(first, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    assertThat(MusicPermissions.releaseAll(app)).isEqualTo(2)
    assertThat(persisted()).isEmpty()
    assertThat(MusicPermissions.releaseAll(app)).isEqualTo(0)
  }
}
