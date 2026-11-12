package dev.pampa.mycloudgallery.feature.update.net

import java.net.URI
import java.net.URISyntaxException

// IL CANCELLO DEGLI HOST DELL'AGGIORNAMENTO (pacchetto U, 12/11/2026; docs/sicurezza.md C482). Ogni indirizzo che l'aggiornamento tocca, il primo e
// quelli a cui un server rimanda, passa da qui: solo `https`, solo un host dell'elenco chiuso (uguale, non un suffisso), porta 443 o nessuna, niente
// credenziali nell'indirizzo, niente frammenti, al piu' [MAX_URL_CHARS]. Un rimando relativo si risolve sull'indirizzo di adesso. Con la stessa
// forma del cancello dei modelli (`core-models/.../download/https/RedirectPolicy`), ma un suo elenco: gli host dei modelli non scaricano APK.

internal object HostGate {

  /** Un indirizzo di GitHub firmato (lo scaricamento di un asset) sta in poco piu' di mille caratteri. */
  const val MAX_URL_CHARS: Int = 8_192

  private const val HTTPS_PORT = 443

  /** [url] se e' ammesso verso [hosts] (nella forma ASCII da usare), o null. */
  fun admit(url: String, hosts: Set<String>): String? {
    if (url.length > MAX_URL_CHARS) return null
    val uri = try {
      URI(url.trim())
    } catch (e: URISyntaxException) {
      return null
    }
    return accept(uri, hosts)
  }

  /** Dove porta il rimando [location] (anche relativo) da [current], se e' ammesso verso [hosts], o null (rimando rifiutato). */
  fun redirect(current: String, location: String?, hosts: Set<String>): String? {
    if (location.isNullOrBlank() || location.length > MAX_URL_CHARS) return null
    val target = try {
      URI(current).resolve(URI(location.trim()))
    } catch (e: URISyntaxException) {
      return null
    } catch (e: IllegalArgumentException) {
      return null
    }
    return accept(target, hosts)
  }

  private fun accept(uri: URI, hosts: Set<String>): String? {
    if (uri.isOpaque || uri.scheme == null) return null
    if (!uri.scheme.equals("https", ignoreCase = true)) return null
    if (uri.rawUserInfo != null || uri.rawFragment != null) return null
    val host = uri.host?.lowercase() ?: return null
    if (host !in hosts) return null
    if (uri.port != -1 && uri.port != HTTPS_PORT) return null
    val text = uri.toASCIIString()
    return text.takeIf { it.length <= MAX_URL_CHARS }
  }
}
