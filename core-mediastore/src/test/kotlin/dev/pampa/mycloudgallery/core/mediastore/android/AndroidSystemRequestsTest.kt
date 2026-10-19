package dev.pampa.mycloudgallery.core.mediastore.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.core.model.MediaRef
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 5B (P0-B, D19, D21): le richieste di sistema nuove di [AndroidPhoneMediaIntents] sul MediaProvider finto di Robolectric:
 * `createDeleteRequest` ("Libera subito") e `createWriteRequest` (correzioni, "Ripristina originale"). Stessi URI e stesso limite del
 * cestino; oltre il limite o senza righe nessuna richiesta, mai una richiesta parziale in silenzio. Nessun `MANAGE_MEDIA`.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSystemRequestsTest {

  private lateinit var provider: FakeMediaProvider
  private val context: Context get() = ApplicationProvider.getApplicationContext()

  @Before
  fun register() {
    provider = AndroidMediaStoreSourceTest.registerMediaProvider()
  }

  private val refs = listOf(MediaRef.Phone("external_primary", 5, "image/jpeg"), MediaRef.Phone("external_primary", 6, "video/mp4"))

  @Test
  fun lEliminazioneEILPermessoDiScritturaPassanoDalDialogoDiSistema() {
    val intents = AndroidPhoneMediaIntents(context)
    assertThat(intents.deleteRequest(emptyList())).isNull()
    assertThat(intents.writeRequest(emptyList())).isNull()

    assertThat(intents.deleteRequest(refs)).isNotNull()
    assertThat(provider.deleteRequests.single().map { it.toString() }).containsExactly(
      "content://media/external_primary/images/media/5",
      "content://media/external_primary/video/media/6",
    )
    assertThat(intents.writeRequest(refs.take(1))).isNotNull()
    assertThat(provider.writeRequests.single().map { it.toString() }).containsExactly("content://media/external_primary/images/media/5")
    // Il cestino non c'entra: le tre richieste sono separate.
    assertThat(provider.trashRequests).isEmpty()
  }

  @Test
  fun leRigheSiContanoDistinteEIlLimiteEQuelloDelCestino() {
    val intents = AndroidPhoneMediaIntents(context)
    assertThat(PhoneMediaIntents.MAX_REQUEST_URIS).isEqualTo(2_000)
    assertThat(AndroidPhoneMediaIntents.MAX_TRASH_URIS).isEqualTo(PhoneMediaIntents.MAX_REQUEST_URIS)

    // La stessa riga due volte conta una volta: una richiesta valida.
    assertThat(intents.deleteRequest(listOf(refs[0], refs[0]))).isNotNull()
    assertThat(provider.deleteRequests.single()).hasSize(1)

    val exactly = (1L..PhoneMediaIntents.MAX_REQUEST_URIS.toLong()).map { MediaRef.Phone("external_primary", it, "image/jpeg") }
    assertThat(intents.writeRequest(exactly)).isNotNull()
    val tooMany = exactly + MediaRef.Phone("external_primary", 5_000, "image/jpeg")
    assertThat(intents.deleteRequest(tooMany)).isNull()
    assertThat(intents.writeRequest(tooMany)).isNull()
    assertThat(provider.deleteRequests).hasSize(1)
    assertThat(provider.writeRequests).hasSize(1)
  }
}
