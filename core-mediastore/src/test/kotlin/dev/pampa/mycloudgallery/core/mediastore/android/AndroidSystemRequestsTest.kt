package dev.pampa.mycloudgallery.core.mediastore.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreRow
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.contract.RowType
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaStore
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
  /**
   * VE-2 della verifica funzionale 5B: i fogli della 5B chiedono il permesso con un riferimento senza MIME. Il MediaProvider rifiuta `files/<id>`
   * (prima: eccezione fino al tocco, l'app si chiudeva); adesso la richiesta va sulla collezione del tipo della riga, letto dal MediaStore.
   */
  @Test
  fun ve2_senzaMimeLaRichiestaVaSullaCollezioneDelTipoDellaRiga() {
    provider.insertRow(5, mediaType = 1, mime = null)
    provider.insertRow(6, mediaType = 3, mime = null)
    val intents = AndroidPhoneMediaIntents(context)
    val untyped = listOf(MediaRef.Phone("external_primary", 5, null), MediaRef.Phone("external_primary", 6, null))
    assertThat(intents.writeRequest(untyped)).isNotNull()
    assertThat(provider.writeRequests.single().map { it.toString() }).containsExactly(
      "content://media/external_primary/images/media/5",
      "content://media/external_primary/video/media/6",
    )
    // Lo stesso per il cestino e l'eliminazione di "Libera spazio" quando il catalogo non ha il MIME.
    assertThat(intents.deleteRequest(untyped)).isNotNull()
    assertThat(intents.trashRequest(untyped, trash = true)).isNotNull()
    assertThat(provider.trashRequests.single().first.map { it.toString() }).containsExactly(
      "content://media/external_primary/images/media/5",
      "content://media/external_primary/video/media/6",
    )
    // Il tipo della riga vince sul MIME del riferimento (il dialogo e la scrittura devono usare lo stesso URI).
    assertThat(intents.requestUriOf(MediaRef.Phone("external_primary", 6, "image/jpeg")).toString()).isEqualTo("content://media/external_primary/video/media/6")
  }

  /** VE-2: una riga che non si trova e un riferimento senza tipo non danno nessuna richiesta (mai `files/`); un rifiuto del sistema non esce. */
  @Test
  fun ve2_senzaTipoNessunaRichiestaEUnRifiutoDelSistemaNonEsce() {
    val intents = AndroidPhoneMediaIntents(context)
    val unknown = listOf(MediaRef.Phone("external_primary", 404, null))
    assertThat(intents.writeRequest(unknown)).isNull()
    assertThat(intents.deleteRequest(unknown)).isNull()
    assertThat(intents.trashRequest(unknown, trash = true)).isNull()
    assertThat(provider.writeRequests).isEmpty()
    assertThat(intents.requestUriOf(unknown.single())).isNull()

    provider.insertRow(7, mediaType = 1)
    provider.failRequests = true
    assertThat(intents.writeRequest(listOf(MediaRef.Phone("external_primary", 7, null)))).isNull()
  }

  /**
   * VE-2, parita' del finto: il telefono finto (`FakeMediaStore.requestCollectionOf`, usato dai test dei flussi della 5B) sceglie la collezione
   * della richiesta con la stessa regola dell'adattatore vero.
   */
  @Test
  fun ve2_ilTelefonoFintoSceglieLaCollezioneComeQuelloVero() {
    provider.insertRow(8, mediaType = 1, mime = null)
    provider.insertRow(9, mediaType = 3, mime = null)
    val fake = FakeMediaStore().apply {
      addVolume("external_primary")
      put(MediaStoreRow("external_primary", 8, RowType.IMAGE, "a.jpg", "DCIM/", null, size = 1, dateModifiedMs = 1), ByteArray(1))
      put(MediaStoreRow("external_primary", 9, RowType.VIDEO, "b.mp4", "DCIM/", null, size = 1, dateModifiedMs = 1), ByteArray(1))
    }
    val intents = AndroidPhoneMediaIntents(context)
    val cases = listOf(
      MediaRef.Phone("external_primary", 8, null),
      MediaRef.Phone("external_primary", 8, "video/mp4"),
      MediaRef.Phone("external_primary", 9, null),
      MediaRef.Phone("external_primary", 404, "image/jpeg"),
      MediaRef.Phone("external_primary", 405, "video/mp4"),
      MediaRef.Phone("external_primary", 406, null),
    )
    for (ref in cases) {
      val real = intents.requestUriOf(ref)?.pathSegments?.getOrNull(1)
      val expected = when (fake.requestCollectionOf(ref)) {
        FakeMediaStore.RequestCollection.IMAGES -> "images"
        FakeMediaStore.RequestCollection.VIDEO -> "video"
        FakeMediaStore.RequestCollection.FILES -> null
      }
      assertThat(real).isEqualTo(expected)
    }
  }
}
