package dev.pampa.mycloudgallery.core.mediastore.android

import android.content.ClipData
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.BaseColumns
import android.provider.MediaStore
import androidx.annotation.ChecksSdkIntAtLeast
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
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
 * - [deleteRequest] e [writeRequest] (Fase 5B, P0-B): `createDeleteRequest` e `createWriteRequest`, stessi limiti e stesso dialogo di
 *   sistema. Sono le due sole altre vie con cui l'app tocca i file del telefono (insieme alle proprie righe `IS_PENDING`): "Libera
 *   subito" e le riscritture di `PhoneMediaRewriter`.
 *
 * **VE-2 della verifica funzionale 5B: le richieste di sistema vogliono l'URI della collezione del tipo.** Il MediaProvider accetta in
 * `create*Request` solo URI di immagini, video o audio "per id": un `files/<id>` (un riferimento senza MIME, come quello che costruiscono i
 * dialoghi della 5B, o una riga senza metadati) fa lanciare `IllegalArgumentException`. Per questo le richieste usano [requestUriOf]: la
 * collezione la decide il **tipo della riga** letto adesso dal MediaStore (`MEDIA_TYPE`), e solo se la riga non si legge il MIME del
 * riferimento; senza nessuno dei due niente richiesta. Lo stesso URI lo apre la riscrittura (`MediaStorePhoneMediaRewriter`), cosi' il
 * permesso del dialogo vale proprio per l'URI che si scrive. Un errore del sistema nel creare la richiesta non esce mai: niente richiesta.
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
    val uris = requestUris(refs) ?: return null
    return safely { MediaStore.createTrashRequest(resolver, uris, trash).intentSender }
  }

  /** Fase 5B (D21): `createDeleteRequest`, il dialogo di sistema che elimina per sempre ("Libera subito"). Mai da un tocco solo (lo decide `core-backup`). */
  override fun deleteRequest(refs: List<MediaRef.Phone>): IntentSender? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    val uris = requestUris(refs) ?: return null
    return safely { MediaStore.createDeleteRequest(resolver, uris).intentSender }
  }

  /** Fase 5B (D19, E-E10): `createWriteRequest`, il permesso di sistema per riscrivere questi file (correzioni, "Ripristina originale"). */
  override fun writeRequest(refs: List<MediaRef.Phone>): IntentSender? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    val uris = requestUris(refs) ?: return null
    return safely { MediaStore.createWriteRequest(resolver, uris).intentSender }
  }

  /**
   * VE-2: l'URI di [ref] per una richiesta di sistema e per la riscrittura, nella collezione del **tipo della riga** (immagini o video), letto
   * adesso; se la riga non si legge, quella del MIME di [ref]. Null se non si sa (la riga non c'e' e il riferimento non ha un tipo): mai `files/`.
   */
  fun requestUriOf(ref: MediaRef.Phone): Uri? = typedUris(listOf(ref))?.singleOrNull()

  /**
   * Gli URI distinti di [refs] nella collezione del loro tipo; null se sono nessuno, oltre il limite di una richiesta o se una riga non ha un
   * tipo noto (meglio niente che una richiesta parziale, o una che il sistema rifiuta).
   */
  private fun requestUris(refs: List<MediaRef.Phone>): List<Uri>? {
    val distinct = refs.distinct()
    if (distinct.isEmpty() || distinct.size > MAX_TRASH_URIS) return null
    return typedUris(distinct)
  }

  private fun typedUris(refs: List<MediaRef.Phone>): List<Uri>? {
    val types = HashMap<Pair<String, Long>, Int>()
    for ((volume, rows) in refs.groupBy { it.volume }) {
      for ((id, type) in mediaTypes(volume, rows.map { it.mediaStoreId })) types[volume to id] = type
    }
    return refs.map { ref ->
      val collection = collectionOf(ref.volume, types[ref.volume to ref.mediaStoreId], ref.mime) ?: return null
      ContentUris.withAppendedId(collection, ref.mediaStoreId)
    }
  }

  /** `MEDIA_TYPE` delle righe [ids] di [volume], letto adesso (anche in attesa o nel cestino): le righe che non si leggono mancano. */
  private fun mediaTypes(volume: String, ids: List<Long>): Map<Long, Int> {
    val out = HashMap<Long, Int>()
    for (chunk in ids.distinct().chunked(TYPE_QUERY_CHUNK)) {
      val args = Bundle().apply {
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${BaseColumns._ID} IN (${chunk.joinToString(",") { "?" }})")
        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, chunk.map { it.toString() }.toTypedArray())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
          putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
      }
      try {
        resolver.query(MediaStore.Files.getContentUri(volume), TYPE_COLUMNS, args, null)?.use { c ->
          while (c.moveToNext()) if (!c.isNull(1)) out[c.getLong(0)] = c.getInt(1)
        }
      } catch (e: RuntimeException) {
        // Un volume tolto, un provider che non risponde: resta il MIME del riferimento.
        GalleryLog.d(TAG) { "tipo delle righe non letto: ${e.javaClass.simpleName}" }
      }
    }
    return out
  }

  /** Il sistema rifiuta la richiesta (URI non ammesso, volume sparito): niente richiesta, mai un'eccezione che esce fino al tocco. */
  private inline fun safely(block: () -> IntentSender): IntentSender? = try {
    block()
  } catch (e: IllegalArgumentException) {
    GalleryLog.w(TAG) { "richiesta di sistema rifiutata: ${e.javaClass.simpleName}" }
    null
  } catch (e: SecurityException) {
    GalleryLog.w(TAG) { "richiesta di sistema rifiutata: ${e.javaClass.simpleName}" }
    null
  } catch (e: IllegalStateException) {
    GalleryLog.w(TAG) { "richiesta di sistema rifiutata: ${e.javaClass.simpleName}" }
    null
  }

  companion object {
    private const val TAG = "PhoneIntents"

    /** Quante righe per query del tipo (gli argomenti di SQLite hanno un tetto). */
    private const val TYPE_QUERY_CHUNK = 500

    private val TYPE_COLUMNS = arrayOf(BaseColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE)

    /** Il massimo di URI per richiesta di sistema (Android 11+: cestino, eliminazione e scrittura). */
    const val MAX_TRASH_URIS: Int = PhoneMediaIntents.MAX_REQUEST_URIS

    /**
     * VE-2: la collezione di una riga per le richieste di sistema e la riscrittura: dal `MEDIA_TYPE` della riga (1 immagine, 3 video), se no dal
     * MIME. Null se nessuno dei due la dice: mai `Files`, che il sistema rifiuta nelle richieste.
     */
    internal fun collectionOf(volume: String, mediaType: Int?, mime: String?): Uri? {
      val type = mime?.lowercase(Locale.ROOT)
      return when {
        mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.getContentUri(volume)
        mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.getContentUri(volume)
        type == null -> null
        type.startsWith("video/") -> MediaStore.Video.Media.getContentUri(volume)
        type.startsWith("image/") -> MediaStore.Images.Media.getContentUri(volume)
        else -> null
      }
    }

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
