package dev.pampa.mycloudgallery.feature.integrations.receive

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import dev.pampa.mycloudgallery.core.backup.contract.IncomingOrigin
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStream
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

// Fase 5B (P11, docs/fase5-progetto.md D24, C18, E-X2): l'intento di condivisione, letto in modo difensivo. Dell'intento si guarda **solo**
// l'azione (`SEND`, `SEND_MULTIPLE`), il tipo (foto, video o qualunque) e gli indirizzi dei flussi: `EXTRA_STREAM` e il `ClipData`. Tutto il resto
// (testo, titolo, altri extra, il nome del chiamante) non entra: non c'e' modo che un campo scritto dall'altra app guidi qualcosa. Un intento
// che non si legge (extra malformati) vale come un intento vuoto.

/** Cio' che l'altra app ci ha dato: gli indirizzi dei suoi flussi (al massimo [MAX_URIS]) e quanti altri ce n'erano. */
class ReceiveRequest(val uris: List<Uri>, val overflow: Int) {

  override fun toString(): String = "ReceiveRequest(${uris.size} indirizzi, $overflow in piu')"

  companion object {
    /** Quanti indirizzi si guardano al massimo: gli altri si contano come "troppi" (stesso tetto della copia, E-X2). */
    const val MAX_URIS: Int = IncomingStager.MAX_EXAMINED

    /** La richiesta di [intent], o null se non e' una condivisione di foto o video (azione sbagliata, tipo che non c'entra, niente da leggere). */
    fun from(intent: Intent?): ReceiveRequest? {
      intent ?: return null
      val action = intent.action
      if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
      val type = intent.type?.lowercase(Locale.ROOT)
      // Il manifest accetta solo foto e video; un intento esplicito con un altro tipo (testo, PDF) non e' per noi.
      if (type != null && !(type.startsWith("image/") || type.startsWith("video/") || type == "*/*")) return null
      return try {
        val all = LinkedHashSet<Uri>()
        if (action == Intent.ACTION_SEND) {
          IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { all += it }
        } else {
          IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { all += it }
        }
        intent.clipData?.let { clip ->
          for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { all += it }
        }
        if (all.isEmpty()) return null
        val list = all.toList()
        ReceiveRequest(list.take(MAX_URIS), (list.size - MAX_URIS).coerceAtLeast(0))
      } catch (_: RuntimeException) {
        // Extra malformati (BadParcelableException, ClassCastException...): per noi l'intento e' vuoto.
        null
      }
    }
  }
}

/** Costruisce i flussi da copiare a partire dagli indirizzi. Nessun flusso si apre qui: lo fa la copia, protetta da un limite di tempo. */
object IncomingStreams {

  /** Quanto si aspetta una risposta sul nome e sulla dimensione di un file prima di rinunciare (un provider che non risponde). */
  private const val INFO_STALL_MS = 3_000L

  /** Quanto tempo in tutto si spende a chiedere nomi e dimensioni: oltre, gli altri flussi si copiano senza. */
  private const val INFO_BUDGET_NANOS = 15_000_000_000L

  /** I nomi e le dimensioni si chiedono solo per i primi: gli altri sono quasi certamente oltre il tetto. */
  private const val INFO_COUNT = 600

  suspend fun of(
    resolver: ContentResolver,
    ownPackage: String,
    request: ReceiveRequest,
    io: CoroutineContext = Dispatchers.IO,
  ): List<IncomingStream> {
    val guard = BlockingGuard(io)
    val deadline = System.nanoTime() + INFO_BUDGET_NANOS
    val out = ArrayList<IncomingStream>(request.uris.size)
    for ((index, uri) in request.uris.withIndex()) {
      val origin = IncomingOrigins.classify(uri, ownPackage)
      if (origin != IncomingOrigin.CONTENT_OF_ANOTHER_APP) {
        // Un file:// o un provider nostro non si interroga e non si apre: il rifiuto e' sull'indirizzo.
        out += IncomingStream(origin, null, null, null) { throw IOException("Questo flusso non si apre.") }
        continue
      }
      val info = if (index < INFO_COUNT && System.nanoTime() < deadline) guard.run(INFO_STALL_MS) { infoOf(resolver, uri) } else null
      out += IncomingStream(origin, info?.name, info?.type, info?.size) { resolver.openInputStream(uri) ?: throw IOException("Nessun flusso.") }
    }
    return out
  }

  private class Info(val name: String?, val type: String?, val size: Long?)

  private fun infoOf(resolver: ContentResolver, uri: Uri): Info {
    var name: String? = null
    var size: Long? = null
    try {
      resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
          val nameAt = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          val sizeAt = cursor.getColumnIndex(OpenableColumns.SIZE)
          if (nameAt >= 0 && !cursor.isNull(nameAt)) name = cursor.getString(nameAt)
          if (sizeAt >= 0 && !cursor.isNull(sizeAt)) size = cursor.getLong(sizeAt).takeIf { it >= 0 }
        }
      }
    } catch (_: Exception) {
      // Un provider che non risponde alla query non e' un motivo per rifiutare: si copia comunque, con i tetti.
    }
    val type = try {
      resolver.getType(uri)
    } catch (_: Exception) {
      null
    }
    return Info(name, type, size)
  }
}
