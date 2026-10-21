package dev.pampa.mycloudgallery.core.models.download.https

import dev.pampa.mycloudgallery.core.models.contract.FetchRefusal
import dev.pampa.mycloudgallery.core.models.contract.ModelHosts
import dev.pampa.mycloudgallery.core.models.contract.ModelLimits
import java.net.URI
import java.net.URISyntaxException

// Le REDIRAZIONI dello scaricamento HTTPS (docs/fase6-progetto.md §2.1, E-S16). Si seguono a mano, una per una, e a ogni salto si
// decide se l'indirizzo nuovo e' ammesso. Nessuna libreria le segue da sola (`instanceFollowRedirects = false`): un host che redirige
// fuori dall'elenco, verso `http` o verso un indirizzo con utente, porta o frammento ferma lo scaricamento prima di aprire la
// connessione.
//
// La query si accetta (e' la firma temporanea che i CDN di Hugging Face richiedono: `cas-bridge.xethub.hf.co/...?X-Amz-Signature=...`),
// ma e' del server: l'app non ne aggiunge mai una alla prima richiesta (PinnedUrl la vieta) ne' ne inventa.

internal sealed interface RedirectDecision {
  /** Si segue: [url] e' assoluto, `https`, su un host ammesso, in forma ASCII. */
  class Follow(val url: String) : RedirectDecision

  /** Si ferma: [reason] e' un codice di [FetchRefusal]. */
  class Refuse(val reason: String) : RedirectDecision
}

internal object RedirectPolicy {
  private const val HTTPS_PORT = 443

  /** Decide dove va una redirezione da [current] con l'intestazione `Location` [location] (anche relativa). */
  fun decide(current: String, location: String?): RedirectDecision {
    if (location.isNullOrBlank() || location.length > ModelLimits.MAX_URL_CHARS) return RedirectDecision.Refuse(FetchRefusal.REDIRECT_FORMAT)
    val target = try {
      URI(current).resolve(URI(location.trim()))
    } catch (e: URISyntaxException) {
      return RedirectDecision.Refuse(FetchRefusal.REDIRECT_FORMAT)
    } catch (e: IllegalArgumentException) {
      return RedirectDecision.Refuse(FetchRefusal.REDIRECT_FORMAT)
    }
    if (target.isOpaque || target.scheme == null) return RedirectDecision.Refuse(FetchRefusal.REDIRECT_FORMAT)
    if (!target.scheme.equals("https", ignoreCase = true)) return RedirectDecision.Refuse(FetchRefusal.REDIRECT_SCHEME)
    if (target.rawUserInfo != null || target.rawFragment != null) return RedirectDecision.Refuse(FetchRefusal.REDIRECT_FORMAT)
    val host = target.host ?: return RedirectDecision.Refuse(FetchRefusal.REDIRECT_FORMAT)
    if (host.endsWith(".")) return RedirectDecision.Refuse(FetchRefusal.REDIRECT_FORMAT)
    if (!ModelHosts.allowed(host)) return RedirectDecision.Refuse(FetchRefusal.REDIRECT_HOST)
    if (target.port != -1 && target.port != HTTPS_PORT) return RedirectDecision.Refuse(FetchRefusal.REDIRECT_FORMAT)
    val text = target.toASCIIString()
    if (text.length > ModelLimits.MAX_URL_CHARS) return RedirectDecision.Refuse(FetchRefusal.REDIRECT_FORMAT)
    return RedirectDecision.Follow(text)
  }
}
