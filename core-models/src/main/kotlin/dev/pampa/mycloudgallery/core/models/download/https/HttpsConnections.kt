package dev.pampa.mycloudgallery.core.models.download.https

import dev.pampa.mycloudgallery.core.models.contract.ModelHttpsRoute
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
 * R12: le connessioni escono dalla rete **approvata dal cancello** ([route]), non dalla rete predefinita del sistema. La rete si rilegge a ogni
 * connessione (ogni fetta). Senza una rete approvata la connessione non si apre (`IOException`, che il fetcher traduce in "non adesso"): mai un
 * ripiego sulla rete predefinita, che potrebbe essere quella dei dati mobili.
 */
internal class NetworkBoundConnections(private val route: ModelHttpsRoute) : HttpsConnections {
  override fun open(url: String): HttpsURLConnection {
    val network = route.network() ?: throw IOException("Nessuna rete approvata per lo scaricamento.")
    return network.openConnection(URL(url)) as? HttpsURLConnection ?: throw IOException("Non e' una connessione HTTPS.")
  }
}

/**
 * Il validatore di una ripresa (`If-Range`, §2.1): l'ETag **forte** della risposta che ha dato i byte, salvato accanto al parziale
 * (`<parziale>.etag`). Un ETag debole o che non ha la forma attesa non si usa mai (una ripresa senza validatore si fida dell'impronta
 * finale: l'indirizzo e' fissato a un commit, il contenuto non cambia).
 */
internal object ETags {
  private val STRONG = Regex("^\"[!#-~]{0,255}\"$")

  /** Il segno "questo parziale riprende senza `If-Range`" (R13): dopo un `200` in ripresa. Non e' un ETag, [read] non lo restituisce mai. */
  private const val NO_VALIDATOR = "-"

  fun sidecar(partial: File): File = File(partial.path + ".etag")

  fun read(partial: File): String? =
    try {
      sidecar(partial).takeIf { it.isFile && it.length() < 512 }?.readText(Charsets.US_ASCII)?.trim()?.takeIf { STRONG.matches(it) }
    } catch (e: IOException) {
      null
    }

  /** Da qui in poi il parziale riprende col solo `Range` (un CDN che cambia ETag a ogni richiesta farebbe ripartire da zero a ogni fetta). */
  fun markNoValidator(partial: File) {
    try {
      val file = sidecar(partial)
      file.parentFile?.mkdirs()
      file.writeText(NO_VALIDATOR, Charsets.US_ASCII)
    } catch (e: IOException) {
      // Senza il segno la prossima ripresa riprova con `If-Range`: al peggio un'altra fetta da zero.
    }
  }

  fun save(partial: File, header: String?) {
    try {
      val file = sidecar(partial)
      // Il segno "senza validatore" e' definitivo per questo parziale: un ETag nuovo non lo toglie.
      if (file.isFile && file.length() < 512 && file.readText(Charsets.US_ASCII).trim() == NO_VALIDATOR) return
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
