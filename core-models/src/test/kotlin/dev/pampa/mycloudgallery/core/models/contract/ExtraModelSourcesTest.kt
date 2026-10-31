package dev.pampa.mycloudgallery.core.models.contract

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.models.noop.NoExtraModelSources
import org.junit.Test

/**
 * Fase 10 (P0, D29, E-REL7, E-S16): la firma degli indirizzi aggiuntivi. Il null-object non da' niente; un indirizzo che arriva dal manifest
 * diventa un [PinnedUrl] solo con le regole di sempre (host simili, IP, porte, query, utenti e `http` rifiutati). P1 aggiunge le prove con il
 * catalogo e `RoutingModelFetcher`.
 */
class ExtraModelSourcesTest {

  @Test
  fun ilNullObjectNonDaIndirizzi() {
    assertThat(NoExtraModelSources.urls(ModelId("model-a"), Sha256("a".repeat(64)))).isEmpty()
  }

  @Test
  fun unIndirizzoDelManifestPassaDaPinnedUrl() {
    assertThat(PinnedUrl.of("https://huggingface.co/x/model.bin")).isNotNull()
    assertThat(PinnedUrl.of("https://cdn-lfs.hf.co/x/model.bin")).isNotNull()
    val refused = listOf(
      "http://huggingface.co/x", "https://huggingface.co.evil.example/x", "https://evilhuggingface.co/x", "https://1.2.3.4/x",
      "https://huggingface.co:8443/x", "https://user@huggingface.co/x", "https://huggingface.co/x?token=1", "https://huggingface.co/x#f",
      "https://raw.githubusercontent.com/x", "https://huggingface.co/../x",
    )
    for (url in refused) assertThat(PinnedUrl.of(url)).isNull()
  }
}
