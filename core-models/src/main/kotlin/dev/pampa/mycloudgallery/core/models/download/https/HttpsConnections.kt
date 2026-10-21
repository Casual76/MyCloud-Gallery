package dev.pampa.mycloudgallery.core.models.download.https

import java.io.File
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

// Da dove vengono le connessioni HTTPS: la piattaforma, con la fiducia di sistema (nessun pinning, niente certificati nostri). La
// separazione serve ai test (un server HTTPS di prova con un certificato fidato SOLO li', che risponde al posto degli host
// ammessi); `HttpsModelFetcher` e il suo costruttore sono `internal`, quindi nessun altro modulo puo' sostituirla.

internal fun interface HttpsConnections {
  /** Apre (senza ancora connettersi) la connessione verso [url], un indirizzo `https` gia' controllato. */
  @Throws(IOException::class)
  fun open(url: String): HttpsURLConnection
}

internal object PlatformConnections : HttpsConnections {
  override fun open(url: String): HttpsURLConnection =
    URL(url).openConnection() as? HttpsURLConnection ?: throw IOException("Non e' una connessione HTTPS.")
}

/**
 * Il validatore di una ripresa (`If-Range`, §2.1): l'ETag **forte** della risposta che ha dato i byte, salvato accanto al parziale
 * (`<parziale>.etag`). Un ETag debole o che non ha la forma attesa non si usa mai (una ripresa senza validatore si fida dell'impronta
 * finale: l'indirizzo e' fissato a un commit, il contenuto non cambia).
 */
internal object ETags {
  private val STRONG = Regex("^\"[!#-~]{0,255}\"$")

  fun sidecar(partial: File): File = File(partial.path + ".etag")

  fun read(partial: File): String? =
    try {
      sidecar(partial).takeIf { it.isFile && it.length() < 512 }?.readText(Charsets.US_ASCII)?.trim()?.takeIf { STRONG.matches(it) }
    } catch (e: IOException) {
      null
    }

  fun save(partial: File, header: String?) {
    try {
      val file = sidecar(partial)
      val value = header?.trim()
      if (value != null && STRONG.matches(value)) {
        file.parentFile?.mkdirs()
        file.writeText(value, Charsets.US_ASCII)
      } else {
        file.delete()
      }
    } catch (e: IOException) {
      // Senza validatore la ripresa funziona lo stesso.
    }
  }
}
