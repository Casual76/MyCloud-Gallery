package dev.pampa.mycloudgallery.feature.integrations.share.testing

import android.net.Uri
import android.media.MediaDataSource
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.integrations.share.CacheUris
import dev.pampa.mycloudgallery.feature.integrations.share.OpenedForShare
import dev.pampa.mycloudgallery.feature.integrations.share.ShareOpen
import dev.pampa.mycloudgallery.feature.integrations.share.ShareSource
import dev.pampa.mycloudgallery.feature.integrations.share.UriRevoker
import java.io.File

// Fase 5B (P11): i fake per provare "Condividi" senza telefono e senza NAS. Mai nella release: `tools/check.ps1` vieta i package `testing` nel dex.

/** Una sorgente di media di prova: byte in memoria, con il nome, il tipo e (se e' del telefono) l'indirizzo del MediaStore. */
class InMemoryShareSource : ShareSource {

  class Entry(val bytes: ByteArray, val mime: String, val displayName: String?, val phoneUri: Uri?)

  private val entries = HashMap<MediaId, Entry>()

  /** I media per cui il NAS non risponde (E-X1). */
  val nasUnreachable: MutableSet<MediaId> = HashSet()

  /** Quanti media sono stati aperti e chiusi, per provare che nessuno resta aperto. */
  var opened: Int = 0
    private set
  var closed: Int = 0
    private set

  /** Una lettura che lancia dopo [failAfterBytes] byte (un NAS che cade a meta' scaricamento). */
  var failAfterBytes: Long? = null

  fun put(id: MediaId, bytes: ByteArray, mime: String, displayName: String? = null, phoneUri: Uri? = null) {
    entries[id] = Entry(bytes, mime, displayName, phoneUri)
  }

  /** Un media che dichiara [size] byte senza averli (un video da 3 GiB): per provare i tetti senza scrivere niente. */
  fun putVirtual(id: MediaId, size: Long, mime: String, displayName: String? = null) {
    virtual[id] = VirtualEntry(size, mime, displayName)
  }

  private class VirtualEntry(val size: Long, val mime: String, val displayName: String?)

  private val virtual = HashMap<MediaId, VirtualEntry>()

  override suspend fun open(mediaId: MediaId): ShareOpen {
    virtual[mediaId]?.let { v ->
      opened++
      return ShareOpen.Opened(OpenedForShare(VirtualBytes(v.size), v.mime, v.displayName, null) { closed++ })
    }
    if (mediaId in nasUnreachable) return ShareOpen.NasUnreachable
    val entry = entries[mediaId] ?: return ShareOpen.Gone
    opened++
    return ShareOpen.Opened(OpenedForShare(ArrayBytes(entry.bytes, failAfterBytes), entry.mime, entry.displayName, entry.phoneUri) { closed++ })
  }
}

/** I byte di un `ByteArray`, letti a intervalli; con [failAfter] lancia `IOException` appena si supera quella posizione. */
class ArrayBytes(private val data: ByteArray, private val failAfter: Long? = null) : MediaBytes {
  override val size: Long get() = data.size.toLong()

  override suspend fun read(range: LongRange): ByteArray {
    if (range.isEmpty() || range.first >= size) return ByteArray(0)
    val from = maxOf(range.first, 0L).toInt()
    val to = minOf(range.last, size - 1).toInt()
    if (failAfter != null && to >= failAfter) throw java.io.IOException("Lettura finta interrotta.")
    return data.copyOfRange(from, to + 1)
  }

  override fun dataSource(): MediaDataSource = throw UnsupportedOperationException("Non serve ai fake.")
}

/** Byte che non esistono: solo una dimensione. Leggerli da' zeri (i test dei tetti non li leggono). */
class VirtualBytes(override val size: Long) : MediaBytes {
  override suspend fun read(range: LongRange): ByteArray = ByteArray(if (range.isEmpty()) 0 else (minOf(range.last, size - 1) - range.first + 1).toInt().coerceAtLeast(0))

  override fun dataSource(): MediaDataSource = throw UnsupportedOperationException("Non serve ai fake.")
}

/** Gli indirizzi di prova: `content://prova/<nome>`, uno per file (il `FileProvider` vero non si prova su JVM/Windows). */
class FakeCacheUris : CacheUris {
  val issued: MutableList<Pair<Uri, File>> = ArrayList()

  override fun uriFor(file: File): Uri = Uri.parse("content://prova/${file.parentFile?.name}/${file.name}").also { issued += it to file }
}

/** Registra i permessi revocati. */
class RecordingUriRevoker : UriRevoker {
  val revoked: MutableList<Uri> = ArrayList()

  override fun revoke(uri: Uri) {
    revoked += uri
  }
}
