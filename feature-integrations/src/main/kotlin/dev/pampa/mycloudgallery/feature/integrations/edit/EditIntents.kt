package dev.pampa.mycloudgallery.feature.integrations.edit

import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import dev.pampa.mycloudgallery.core.backup.contract.EditSession
import dev.pampa.mycloudgallery.core.backup.contract.EditTarget
import dev.pampa.mycloudgallery.feature.integrations.share.CacheUris
import dev.pampa.mycloudgallery.feature.integrations.share.ShareCache

// Fase 5B (P11, docs/fase5-progetto.md §2.7, D25, C17, E-X1; Android 17 e 18): gli INTENTI con cui si apre un editor. Li lancia la UI dopo un tocco su "Modifica
// con…" (mai da soli).
//  - Media del telefono (e "Scarica e modifica in Galleria"): `ACTION_VIEW` verso Samsung Gallery con il **pacchetto esplicito**, sull'indirizzo del
//    MediaStore della riga: Samsung non risponde ad `ACTION_EDIT` (scoperta 11) e l'esito si legge dal MediaStore. Nessun permesso da dare: la
//    Galleria di Samsung legge il MediaStore da sola.
//  - Media solo-NAS: `ACTION_EDIT` sulla copia in `cacheDir/edit/` servita dal `FileProvider` con permesso di **lettura e scrittura** su quel solo
//    indirizzo, con `ClipData` (Android 17 e 18); l'editor lo sceglie l'utente (Google Foto, OneDrive). I permessi si revocano a sessione finita
//    (`DefaultEditSessions`): dopo, l'editor non puo' piu' scrivere nella copia.

object EditIntents {

  /** Il pacchetto di Samsung Gallery. */
  const val SAMSUNG_GALLERY: String = "com.sec.android.gallery3d"

  /** L'indirizzo `content://` di una riga del MediaStore (foto o video) di un volume. */
  fun mediaStoreUri(volume: String, id: Long, isVideo: Boolean): Uri =
    ContentUris.withAppendedId(if (isVideo) MediaStore.Video.Media.getContentUri(volume) else MediaStore.Images.Media.getContentUri(volume), id)

  /** `ACTION_VIEW` verso Samsung Gallery: l'editor del telefono. */
  fun viewInGallery(uri: Uri, mime: String): Intent =
    Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).setPackage(SAMSUNG_GALLERY).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

  /** `ACTION_EDIT` su una copia in cache: lettura **e** scrittura, solo su questo indirizzo, con `ClipData`. L'editor lo sceglie l'utente. */
  fun editCopy(uri: Uri, mime: String): Intent {
    val clip = ClipData(ClipDescription("media", arrayOf(mime)), ClipData.Item(uri))
    return Intent(Intent.ACTION_EDIT).apply {
      setDataAndType(uri, mime)
      clipData = clip
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
  }

  /**
   * L'intento giusto per [session], o null se non si puo' fare (la sessione non ha una riga ne' una copia): Samsung Gallery per il telefono,
   * `ACTION_EDIT` per la copia in cache. [isVideo] e [mime] li conosce la UI che apre la sessione.
   */
  fun forSession(session: EditSession, uris: CacheUris, cache: ShareCache, mime: String, isVideo: Boolean): Intent? =
    when (session.target) {
      EditTarget.PHONE_GALLERY, EditTarget.NAS_DOWNLOAD ->
        session.phone?.let { viewInGallery(mediaStoreUri(it.volume, it.mediaStoreId, isVideo), mime) }
      EditTarget.NAS_CACHE -> session.cacheName?.let(cache::editFile)?.takeIf { it.isFile }?.let { editCopy(uris.uriFor(it), mime) }
    }

  /**
   * Lancia [intent]. Falso se non c'e' un'app che lo gestisce (Samsung Gallery assente o disattivata): la UI lo dice e propone "Scarica e modifica
   * in Galleria" o un altro editor. Non serve nessuna dichiarazione `<queries>`: si prova a lanciare, non si chiede cosa c'e'.
   */
  fun launch(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
  } catch (_: android.content.ActivityNotFoundException) {
    false
  } catch (_: SecurityException) {
    false
  }
}
