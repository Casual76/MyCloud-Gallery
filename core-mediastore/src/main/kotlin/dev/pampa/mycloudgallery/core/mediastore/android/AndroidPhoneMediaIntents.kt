package dev.pampa.mycloudgallery.core.mediastore.android

import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.ChecksSdkIntAtLeast
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.core.model.MediaRef
import java.util.Locale

/**
 * Le azioni di sistema sui media del telefono (D13, D14).
 *
 * - [uriOf]: l'URI della collezione del tipo (immagini o video); senza MIME (prima di META) quello di `Files`, che vale
 *   per entrambi.
 * - [share]: `ACTION_SEND`/`ACTION_SEND_MULTIPLE` con `FLAG_GRANT_READ_URI_PERMISSION` **e** `ClipData` con tutti gli URI:
 *   da Android 18 il permesso implicito sugli extra non c'e' piu' (docs/android.md, Android 17). La posizione dentro le
 *   foto la decide chi riceve; "rimuovi posizione" arriva con la 5.7.
 * - [trashRequest]: `MediaStore.createTrashRequest`, cioe' il dialogo di sistema (una seconda conferma gratuita): l'app
 *   non cancella mai da sola e non chiede `MANAGE_MEDIA`. Al massimo [MAX_TRASH_URIS] elementi per richiesta, il limite di
 *   Android 11+: oltre, nessuna richiesta (meglio niente che una richiesta parziale silenziosa).
 */
class AndroidPhoneMediaIntents(context: Context) : PhoneMediaIntents {

  private val resolver = context.applicationContext.contentResolver

  override val canTrash: Boolean
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R) get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

  override fun uriOf(ref: MediaRef.Phone): Uri {
    val mime = ref.mime?.lowercase(Locale.ROOT)
    val collection = when {
      mime == null -> MediaStore.Files.getContentUri(ref.volume)
      mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(ref.volume)
      else -> MediaStore.Images.Media.getContentUri(ref.volume)
    }
    return ContentUris.withAppendedId(collection, ref.mediaStoreId)
  }

  override fun share(refs: List<MediaRef.Phone>): Intent? {
    val distinct = refs.distinct()
    if (distinct.isEmpty()) return null
    val uris = distinct.map(::uriOf)
    val intent = if (uris.size == 1) {
      Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
    } else {
      Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    val clip = ClipData.newRawUri(null, uris.first())
    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    intent.clipData = clip
    intent.type = shareType(distinct)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return intent
  }

  override fun trashRequest(refs: List<MediaRef.Phone>, trash: Boolean): IntentSender? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    val uris = refs.distinct().map(::uriOf)
    if (uris.isEmpty() || uris.size > MAX_TRASH_URIS) return null
    return MediaStore.createTrashRequest(resolver, uris, trash).intentSender
  }

  companion object {
    /** Il massimo di URI per richiesta di sistema (Android 11+, `MediaStore.createTrashRequest`). */
    const val MAX_TRASH_URIS: Int = 2_000

    /** Il tipo comune: tutte immagini, tutti video, oppure qualunque tipo ([ANY]). */
    internal fun shareType(refs: List<MediaRef.Phone>): String {
      val mimes = refs.map { it.mime?.lowercase(Locale.ROOT) }
      if (mimes.size == 1) return mimes.single() ?: ANY
      return when {
        mimes.all { it != null && it.startsWith("image/") } -> "image/*"
        mimes.all { it != null && it.startsWith("video/") } -> "video/*"
        else -> ANY
      }
    }

    private const val ANY = "*/*"
  }
}
