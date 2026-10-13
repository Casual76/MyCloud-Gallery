package dev.pampa.mycloudgallery.core.mediastore.android

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.model.MediaRef
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Le azioni di sistema (D13, D14): URI, condivisione con `ClipData` e permesso di lettura (Android 17/18), richiesta di
 * cestino con il dialogo di sistema, "Eliminati di recente" dal cestino di sistema, accesso ai media letto dai permessi.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSystemActionsTest {

  private lateinit var provider: FakeMediaProvider
  private val context: Context get() = ApplicationProvider.getApplicationContext()

  @Before
  fun register() {
    provider = AndroidMediaStoreSourceTest.registerMediaProvider()
  }

  @Test
  fun gliUriSonoQuelliDellaCollezioneDelTipo() {
    val intents = AndroidPhoneMediaIntents(context)
    assertThat(intents.uriOf(MediaRef.Phone("external_primary", 5, "image/heic")).toString())
      .isEqualTo("content://media/external_primary/images/media/5")
    assertThat(intents.uriOf(MediaRef.Phone("1a2b-3c4d", 6, "video/mp4")).toString())
      .isEqualTo("content://media/1a2b-3c4d/video/media/6")
    assertThat(intents.uriOf(MediaRef.Phone("external_primary", 7, null)).toString())
      .isEqualTo("content://media/external_primary/file/7")
  }

  @Test
  fun laCondivisioneHaClipDataEPermessoDiLettura() {
    val intents = AndroidPhoneMediaIntents(context)
    assertThat(intents.share(emptyList())).isNull()

    val one = intents.share(listOf(MediaRef.Phone("external_primary", 5, "image/jpeg")))!!
    assertThat(one.action).isEqualTo(Intent.ACTION_SEND)
    assertThat(one.type).isEqualTo("image/jpeg")
    assertThat(one.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
    assertThat(one.clipData!!.itemCount).isEqualTo(1)
    @Suppress("DEPRECATION")
    assertThat(one.getParcelableExtra<Uri>(Intent.EXTRA_STREAM).toString()).isEqualTo("content://media/external_primary/images/media/5")

    val refs = listOf(
      MediaRef.Phone("external_primary", 5, "image/jpeg"),
      MediaRef.Phone("external_primary", 6, "image/heic"),
      MediaRef.Phone("external_primary", 6, "image/heic"),
    )
    val many = intents.share(refs)!!
    assertThat(many.action).isEqualTo(Intent.ACTION_SEND_MULTIPLE)
    assertThat(many.type).isEqualTo("image/*")
    assertThat(many.clipData!!.itemCount).isEqualTo(2)
    @Suppress("DEPRECATION")
    assertThat(many.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).hasSize(2)
    assertThat(many.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)

    val mixed = intents.share(listOf(MediaRef.Phone("external_primary", 5, "image/jpeg"), MediaRef.Phone("external_primary", 8, "video/mp4")))!!
    assertThat(mixed.type).isEqualTo("*/*")
  }

  @Test
  fun ilCestinoPassaDalDialogoDiSistema() {
    val intents = AndroidPhoneMediaIntents(context)
    assertThat(intents.canTrash).isTrue()
    assertThat(intents.trashRequest(emptyList(), trash = true)).isNull()

    val refs = listOf(MediaRef.Phone("external_primary", 5, "image/jpeg"), MediaRef.Phone("external_primary", 6, "video/mp4"))
    assertThat(intents.trashRequest(refs, trash = true)).isNotNull()
    val (uris, value) = provider.trashRequests.single()
    assertThat(uris.map { it.toString() }).containsExactly(
      "content://media/external_primary/images/media/5",
      "content://media/external_primary/video/media/6",
    )
    assertThat(value).isEqualTo(1)

    // Ripristino ("Eliminati di recente").
    assertThat(intents.trashRequest(refs.take(1), trash = false)).isNotNull()
    assertThat(provider.trashRequests.last().second).isEqualTo(0)

    // Oltre il limite di sistema: nessuna richiesta (meglio niente che una parte in silenzio).
    val tooMany = (1L..(AndroidPhoneMediaIntents.MAX_TRASH_URIS + 1L)).map { MediaRef.Phone("external_primary", it, "image/jpeg") }
    assertThat(intents.trashRequest(tooMany, trash = true)).isNull()
    assertThat(provider.trashRequests).hasSize(2)
  }

  @Test
  fun eliminatiDiRecenteDalCestinoDiSistema() = runBlocking<Unit> {
    val expires = 1_762_000_000L
    provider.insertRow(1)
    provider.insertRow(2, trashed = true, expiresSec = expires)
    provider.insertRow(3, mediaType = 3, trashed = true, expiresSec = expires + 100, volume = "1a2b-3c4d")
    provider.insertRow(4, pending = true, trashed = true, expiresSec = expires + 200)

    val items = AndroidRecentlyDeletedSource(context).list()

    assertThat(items.map { it.ref.mediaStoreId }).containsExactly(3L, 2L).inOrder()
    val video = items.first()
    assertThat(video.isVideo).isTrue()
    assertThat(video.ref.volume).isEqualTo("1a2b-3c4d")
    assertThat(video.ref.mime).isEqualTo("video/mp4")
    assertThat(video.expiresAtMs).isEqualTo(TimeUnit.SECONDS.toMillis(expires + 100))
    assertThat(video.expiresAtMs - video.trashedAtMs).isEqualTo(TimeUnit.DAYS.toMillis(AndroidRecentlyDeletedSource.SYSTEM_TRASH_DAYS))
  }

  @Test
  fun lAccessoSiRilegge() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val shadow = shadowOf(app)
    shadow.denyPermissions(
      "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
      "android.permission.READ_MEDIA_VISUAL_USER_SELECTED", "android.permission.ACCESS_MEDIA_LOCATION",
    )
    val reader = AndroidPhoneAccessReader(app, volumeNames = { setOf("external_primary", "1a2b-3c4d", "VOLUME?") })
    assertThat(reader.state.value.media).isEqualTo(MediaAccess.NONE)
    assertThat(reader.state.value.volumes).containsExactly("external_primary", "1a2b-3c4d")

    // "Foto selezionate" (Android 14+).
    shadow.grantPermissions("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
    reader.refresh()
    assertThat(reader.state.value.media).isEqualTo(MediaAccess.PARTIAL)

    // Solo le immagini: con meta' libreria non si deducono assenze.
    shadow.grantPermissions("android.permission.READ_MEDIA_IMAGES")
    reader.refresh()
    assertThat(reader.state.value.media).isEqualTo(MediaAccess.PARTIAL)

    shadow.grantPermissions("android.permission.READ_MEDIA_VIDEO", "android.permission.ACCESS_MEDIA_LOCATION")
    reader.refresh()
    assertThat(reader.state.value.media).isEqualTo(MediaAccess.FULL)
    assertThat(reader.state.value.mediaLocation).isTrue()

    // Ritirati dalle impostazioni: si vede al prossimo refresh, mai memorizzato.
    shadow.denyPermissions("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
    reader.refresh()
    assertThat(reader.state.value.media).isEqualTo(MediaAccess.NONE)
  }
}
