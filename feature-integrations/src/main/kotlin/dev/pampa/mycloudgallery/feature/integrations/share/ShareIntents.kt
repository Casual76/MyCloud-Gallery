package dev.pampa.mycloudgallery.feature.integrations.share

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

// Fase 5B (P11, docs/fase5-progetto.md §2.7, D25, D26, C17; Android 17 e 18): gli indirizzi dei file in cache e i PERMESSI su di essi. Il
// `FileProvider` non e' esportato: ogni altra app legge un file solo se glielo diamo noi, uno per uno, con `FLAG_GRANT_READ_URI_PERMISSION` e un
// `ClipData` (mai solo `EXTRA_STREAM`: dalle versioni recenti di Android il permesso passa dal `ClipData`). Il permesso non e' permanente: lo si
// **revoca dopo l'uso** (`ShareLease.release`), e subito dopo si toglie anche il file.

/** L'indirizzo `content://` di un file in `cacheDir/share/` o `cacheDir/edit/`. */
fun interface CacheUris {
  fun uriFor(file: File): Uri
}

/** Il vero: il `FileProvider` delle integrazioni (`IntegrationFileProvider`, autorita' `<pacchetto>.integrations.files`). */
class FileProviderUris(private val context: Context) : CacheUris {
  override fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, authorityOf(context), file)

  companion object {
    fun authorityOf(context: Context): String = "${context.packageName}.integrations.files"
  }
}

/** Revoca i permessi di lettura (e scrittura) che si sono dati sull'indirizzo [Uri]. */
fun interface UriRevoker {
  fun revoke(uri: Uri)
}

class ContextUriRevoker(private val context: Context) : UriRevoker {
  override fun revoke(uri: Uri) {
    try {
      context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    } catch (_: SecurityException) {
      // Un indirizzo che non e' nostro non si revoca: il file, se e' nostro, si toglie comunque.
    }
  }
}

/**
 * Cio' che una condivisione (o una sessione di modifica) ha in giro: indirizzi con permessi e file in cache. [release] revoca i permessi e toglie
 * i file; si puo' chiamare piu' volte (la seconda non fa niente). Va chiamato alla fine dell'uso: quando l'utente torna nell'app dopo la
 * condivisione, o la sessione si chiude. Se il processo muore prima, `ShareCache.sweepAtStart` toglie i file all'avvio dopo.
 */
class ShareLease(
  private val uris: List<Uri>,
  private val roots: List<File>,
  private val revoker: UriRevoker,
  private val cache: ShareCache?,
) {
  private val released = AtomicBoolean(false)

  /** Gli indirizzi coperti da questo lease. */
  val granted: List<Uri> get() = uris

  val isReleased: Boolean get() = released.get()

  fun release() {
    if (!released.compareAndSet(false, true)) return
    FlowCounters.share.add(ShareCounter.RELEASED)
    // Prima si revoca (nessuno puo' piu' aprire il file), poi si toglie.
    uris.forEach { uri -> revoker.revoke(uri) }
    roots.forEach { root -> if (cache != null) cache.deleteTree(root) else root.deleteRecursively() }
  }
}

/** Gli intenti di condivisione. Con `ClipData` e `FLAG_GRANT_READ_URI_PERMISSION` sempre; nessun permesso di scrittura. */
object ShareIntents {

  /**
   * L'intento `ACTION_SEND` (un file) o `ACTION_SEND_MULTIPLE` (piu' file) verso il foglio di condivisione del sistema. Il tipo e' quello vero
   * dei file (tutte foto o tutti video se sono dello stesso genere, altrimenti qualunque tipo).
   */
  fun send(files: List<SharedFile>): Intent {
    require(files.isNotEmpty()) { "Niente da condividere." }
    val type = commonType(files.map { it.mime })
    val clip = clipOf(files.map { it.uri }, files.map { it.mime })
    val intent = if (files.size == 1) {
      Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, files[0].uri)
    } else {
      Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map { it.uri }))
    }
    return intent.apply {
      setType(type)
      clipData = clip
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  }

  /** Il `ClipData` con tutti gli indirizzi: da lui l'app che riceve ottiene il permesso. */
  fun clipOf(uris: List<Uri>, mimes: List<String>): ClipData {
    val description = ClipDescription(CLIP_LABEL, mimes.distinct().toTypedArray())
    val clip = ClipData(description, ClipData.Item(uris.first()))
    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    return clip
  }

  /** Il tipo comune: foto, video o, se misti, qualunque. */
  fun commonType(mimes: List<String>): String {
    val kinds = mimes.map { it.substringBefore('/').lowercase() }.distinct()
    return if (kinds.size == 1 && (kinds[0] == "image" || kinds[0] == "video")) "${kinds[0]}/*" else "*/*"
  }

  private const val CLIP_LABEL = "media"
}
