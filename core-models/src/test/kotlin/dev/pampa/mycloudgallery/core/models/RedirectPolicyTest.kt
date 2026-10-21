package dev.pampa.mycloudgallery.core.models

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.models.contract.FetchRefusal
import dev.pampa.mycloudgallery.core.models.contract.ModelLimits
import dev.pampa.mycloudgallery.core.models.download.https.RedirectDecision
import dev.pampa.mycloudgallery.core.models.download.https.RedirectPolicy
import org.junit.Test

/** E-S16, §2.1 "HTTPS": a ogni salto l'indirizzo nuovo e' https, senza utente, porta o frammento, e su un host ammesso. */
class RedirectPolicyTest {
  private val current = "https://huggingface.co/litert-community/x/resolve/abc/model.litertlm"

  private fun reason(location: String?): String? = (RedirectPolicy.decide(current, location) as? RedirectDecision.Refuse)?.reason

  private fun target(location: String?): String? = (RedirectPolicy.decide(current, location) as? RedirectDecision.Follow)?.url

  @Test
  fun siSeguonoSoloGliHostAmmessi() {
    assertThat(target("https://cas-bridge.xethub.hf.co/obj/abc?X-Amz-Signature=ff&X-Amz-Expires=3600"))
      .isEqualTo("https://cas-bridge.xethub.hf.co/obj/abc?X-Amz-Signature=ff&X-Amz-Expires=3600")
    assertThat(target("https://cdn-lfs-us-1.hf.co/repos/a/b")).isNotNull()
    assertThat(target("https://HuggingFace.co/other")).isEqualTo("https://HuggingFace.co/other")
    assertThat(target("https://cdn.huggingface.co:443/x")).isNotNull()
    for (bad in listOf(
      "https://evil.example.org/x",
      "https://huggingface.co.evil.example.org/x",
      "https://evilhf.co/x",
      "https://nothuggingface.co/x",
      "https://hf.co/x",
      "https://.hf.co/x",
      "https://127.0.0.1/x",
      "https://10.0.0.5/model",
      "https://[::1]/x",
      "https://localhost/x",
      "//evil.example.org/x",
    )) {
      assertThat(reason(bad)).isIn(listOf(FetchRefusal.REDIRECT_HOST, FetchRefusal.REDIRECT_FORMAT, FetchRefusal.REDIRECT_SCHEME))
    }
    assertThat(reason("https://evil.example.org/x")).isEqualTo(FetchRefusal.REDIRECT_HOST)
    assertThat(reason("https://huggingface.co.evil.example.org/x")).isEqualTo(FetchRefusal.REDIRECT_HOST)
  }

  @Test
  fun soloHttps() {
    for (bad in listOf("http://huggingface.co/x", "HTTP://huggingface.co/x", "ftp://huggingface.co/x", "file:///etc/passwd", "javascript:alert(1)", "data:text/plain,x")) {
      assertThat(reason(bad)).isIn(listOf(FetchRefusal.REDIRECT_SCHEME, FetchRefusal.REDIRECT_FORMAT))
    }
    assertThat(reason("http://huggingface.co/x")).isEqualTo(FetchRefusal.REDIRECT_SCHEME)
  }

  @Test
  fun nienteUtentePortaOFrammento() {
    assertThat(reason("https://user:pw@huggingface.co/x")).isEqualTo(FetchRefusal.REDIRECT_FORMAT)
    assertThat(reason("https://huggingface.co@evil.example.org/x")).isEqualTo(FetchRefusal.REDIRECT_FORMAT)
    assertThat(reason("https://huggingface.co:8443/x")).isEqualTo(FetchRefusal.REDIRECT_FORMAT)
    assertThat(reason("https://huggingface.co:80/x")).isEqualTo(FetchRefusal.REDIRECT_FORMAT)
    assertThat(reason("https://huggingface.co/x#frammento")).isEqualTo(FetchRefusal.REDIRECT_FORMAT)
    assertThat(reason("https://huggingface.co./x")).isEqualTo(FetchRefusal.REDIRECT_FORMAT)
  }

  @Test
  fun lePosizioniRelativeRestanoSuLHostCorrente() {
    assertThat(target("/altro/percorso?x=1")).isEqualTo("https://huggingface.co/altro/percorso?x=1")
    assertThat(target("altro")).isEqualTo("https://huggingface.co/litert-community/x/resolve/abc/altro")
    // Una posizione relativa a un host (scheme-relative) cambia host: si giudica l'host nuovo.
    assertThat(target("//cdn-lfs.hf.co/y")).isEqualTo("https://cdn-lfs.hf.co/y")
    assertThat(reason("//evil.example.org/y")).isEqualTo(FetchRefusal.REDIRECT_HOST)
  }

  @Test
  fun formeStraneOVuoteSiRifiutano() {
    assertThat(reason(null)).isEqualTo(FetchRefusal.REDIRECT_FORMAT)
    assertThat(reason("")).isEqualTo(FetchRefusal.REDIRECT_FORMAT)
    assertThat(reason("   ")).isEqualTo(FetchRefusal.REDIRECT_FORMAT)
    assertThat(reason("https://huggingface.co/a b")).isEqualTo(FetchRefusal.REDIRECT_FORMAT)
    assertThat(reason("https://huggingface.co/\u0000")).isEqualTo(FetchRefusal.REDIRECT_FORMAT)
    assertThat(reason("https://huggingface.co/a\\b")).isEqualTo(FetchRefusal.REDIRECT_FORMAT)
    assertThat(reason("https://")).isEqualTo(FetchRefusal.REDIRECT_FORMAT)
    assertThat(reason("mailto:a@huggingface.co")).isIn(listOf(FetchRefusal.REDIRECT_SCHEME, FetchRefusal.REDIRECT_FORMAT))
    assertThat(reason("https://huggingface.co/" + "a".repeat(ModelLimits.MAX_URL_CHARS))).isEqualTo(FetchRefusal.REDIRECT_FORMAT)
  }

  @Test
  fun unNomeNonAsciiSiNormalizzaOSiRifiuta() {
    // Un host con caratteri non ASCII non e' nell'elenco; il percorso con caratteri non ASCII viene codificato.
    assertThat(reason("https://huggïngface.co/x")).isIn(listOf(FetchRefusal.REDIRECT_HOST, FetchRefusal.REDIRECT_FORMAT))
    assertThat(target("https://huggingface.co/caffè")).isEqualTo("https://huggingface.co/caff%C3%A8")
  }
}
