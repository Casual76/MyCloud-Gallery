package dev.pampa.mycloudgallery.core.models

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.SpaceCode
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelHosts
import dev.pampa.mycloudgallery.core.models.contract.ModelRole
import dev.pampa.mycloudgallery.core.models.contract.PinnedUrl
import dev.pampa.mycloudgallery.core.trust.data.DeviceRole
import org.junit.Test

/** Fase 6 (P0, §2.1, D16, D17, D33): il catalogo fissato nell'app e le regole degli indirizzi. */
class ModelCatalogTest {

  /** I valori del §2.1 (commit, dimensioni, impronte): cambiano solo con un aggiornamento dell'app. */
  @Test
  fun ilCatalogoEQuelloDelProgetto() {
    val q = ModelCatalog.QUERY_270M
    assertThat(q.role).isEqualTo(ModelRole.QUERY_TEXT)
    assertThat(q.sizeBytes).isEqualTo(164_626_432L)
    assertThat(q.sha256.hex).isEqualTo("2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb")
    assertThat(q.https.single().value).contains("/resolve/9be6e8b90982095dc05c2bd162e4b954ee4dbac7/")
    val i = ModelCatalog.INDEX_440M
    assertThat(i.role).isEqualTo(ModelRole.INDEX_MULTIMODAL)
    assertThat(i.sizeBytes).isEqualTo(387_710_976L)
    assertThat(i.sha256.hex).isEqualTo("92dcbea108899e5d6e30d919b0744f90d9967e80c67a4ab5503ac16d54f62eb0")
    assertThat(i.https.single().value).contains("/resolve/e301f74d5551b0c2641bd5cb4652a76239d5c5f8/")
    // D3, E-Q8: query e indicizzatori nello stesso spazio.
    assertThat(q.space).isEqualTo(SpaceCode.CURRENT.space)
    assertThat(i.space).isEqualTo(q.space)
    val c = ModelCatalog.CUTOUT_MAGICTOUCH
    assertThat(c.role).isEqualTo(ModelRole.CUTOUT)
    assertThat(c.https).isEmpty()
    assertThat(c.bundledAsset).isEqualTo("cutout/magic_touch.tflite")
    assertThat(c.sizeBytes).isEqualTo(6_227_884L)
    assertThat(ModelCatalog.ALL.map { it.id }.toSet()).hasSize(3)
    assertThat(ModelCatalog.ALL.map { it.sha256 }.toSet()).hasSize(3)
  }

  /** D3: il 440M solo agli indicizzatori; il 270M e il ritaglio a tutti. */
  @Test
  fun iModelliCheServonoDipendonoDalRuolo() {
    assertThat(ModelCatalog.needed(emptySet())).containsExactly(ModelCatalog.QUERY_270M, ModelCatalog.CUTOUT_MAGICTOUCH)
    assertThat(ModelCatalog.needed(setOf(DeviceRole.PREPARER))).doesNotContain(ModelCatalog.INDEX_440M)
    assertThat(ModelCatalog.needed(setOf(DeviceRole.INDEXER))).contains(ModelCatalog.INDEX_440M)
  }

  /** D17, E-S16: solo https, solo host ammessi (suffissi dei CDN), niente porta, utente, parametri o frammento. */
  @Test
  fun gliIndirizziAmmessiSonoPochiEPuliti() {
    assertThat(ModelHosts.allowed("huggingface.co")).isTrue()
    assertThat(ModelHosts.allowed("cdn-lfs-us-1.hf.co")).isTrue()
    assertThat(ModelHosts.allowed("cas-bridge.xethub.hf.co")).isTrue()
    assertThat(ModelHosts.allowed("HuggingFace.CO.")).isTrue()
    assertThat(ModelHosts.allowed("hf.co")).isFalse()
    assertThat(ModelHosts.allowed("evilhf.co")).isFalse()
    assertThat(ModelHosts.allowed("huggingface.co.example.org")).isFalse()
    assertThat(ModelHosts.allowed("10.0.0.1")).isFalse()
    assertThat(ModelHosts.allowed("")).isFalse()

    assertThat(PinnedUrl.of("https://huggingface.co/a/b/resolve/c/d.litertlm")).isNotNull()
    for (bad in listOf(
      "http://huggingface.co/a",
      "https://huggingface.co:8443/a",
      "https://user@huggingface.co/a",
      "https://huggingface.co/a?download=true",
      "https://huggingface.co/a#x",
      "https://example.org/a",
      "https://huggingface.co/a/../../b",
      "ftp://huggingface.co/a",
    )) {
      assertThat(PinnedUrl.of(bad)).isNull()
    }
  }
}
