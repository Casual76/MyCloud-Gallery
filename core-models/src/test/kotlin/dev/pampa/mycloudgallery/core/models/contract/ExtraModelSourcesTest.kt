package dev.pampa.mycloudgallery.core.models.contract

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.models.noop.NoExtraModelSources
import dev.pampa.mycloudgallery.core.models.source.CatalogExtraModelSources
import dev.pampa.mycloudgallery.core.models.source.RoutingModelFetcher
import dev.pampa.mycloudgallery.core.models.support.ModelRig
import dev.pampa.mycloudgallery.core.models.support.TestModels
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Fase 10 (P0 la firma, P1 l'uso; D29, E-REL7, E-S16; C441): gli indirizzi aggiuntivi del manifest firmato. Passano **solo** per un `ModelId`
 * e uno SHA-256 del catalogo, solo verso gli host di `ModelHosts` (host simili, IP, porte, query, utenti, `http` rifiutati), dopo gli indirizzi
 * fissati; `RoutingModelFetcher` rifiuta senza rete un indirizzo ne' fissato ne' aggiuntivo; l'impronta del catalogo decide sempre.
 */
class ExtraModelSourcesTest {

  private val spec = ModelCatalog.QUERY_270M
  private val good = listOf("https://huggingface.co/mirror/a.litertlm", "https://cdn-lfs-eu-1.hf.co/b", "https://mirror.huggingface.co/c")

  /** Gli indirizzi che un manifest (anche firmato) potrebbe proporre e che l'app non deve mai usare. */
  private val hostile = listOf(
    "http://huggingface.co/x", "https://huggingface.co.evil.example/x", "https://evilhuggingface.co/x", "https://huggingface.com/x",
    "https://hf.co.evil.io/x", "https://hf.co/x", "https://huggingfacé.co/x", "https://1.2.3.4/x", "https://127.0.0.1/x", "https://[::1]/x",
    "https://0x7f000001/x", "https://huggingface.co:443/x", "https://huggingface.co:8443/x", "https://user@huggingface.co/x",
    "https://huggingface.co@evil.example/x", "https://huggingface.co/x?token=1", "https://huggingface.co/x#f", "https://huggingface.co",
    "HTTPS://huggingface.co/x", " https://huggingface.co/x", "https://huggingface.co/x y", "https://huggingface.co/../x",
    "https://huggingface.co/a/..", "file:///sdcard/x", "https://raw.githubusercontent.com/x", "https://huggingface.co\\@evil.example/x",
  )

  @Test
  fun ilNullObjectNonDaIndirizzi() {
    assertThat(NoExtraModelSources.urls(ModelId("model-a"), Sha256("a".repeat(64)))).isEmpty()
  }

  @Test
  fun unIndirizzoDelManifestPassaDaPinnedUrl() {
    assertThat(PinnedUrl.of("https://huggingface.co/x/model.bin")).isNotNull()
    assertThat(PinnedUrl.of("https://cdn-lfs.hf.co/x/model.bin")).isNotNull()
    for (url in hostile) assertWithMessage(url).that(PinnedUrl.of(url)).isNull()
  }

  @Test
  fun soloIdEImprontaDelCatalogoESoloHostAmmessi() {
    val asked = mutableListOf<Pair<String, String>>()
    val sources = CatalogExtraModelSources({ id, sha -> asked += id to sha; hostile + good + spec.https.map { it.value } })
    val urls = sources.urls(spec.id, spec.sha256).map { it.value }
    assertThat(urls).containsExactlyElementsIn(good).inOrder()
    assertThat(asked).containsExactly(spec.id.value to spec.sha256.hex)
    assertWithMessage("un'impronta diversa").that(sources.urls(spec.id, Sha256("b".repeat(64)))).isEmpty()
    assertWithMessage("un modello che il catalogo non ha").that(sources.urls(ModelId("modello-nuovo"), spec.sha256)).isEmpty()
    val bundled = ModelCatalog.CUTOUT_MAGICTOUCH
    assertWithMessage("un modello dentro l'APK").that(sources.urls(bundled.id, bundled.sha256)).isEmpty()
    assertThat(asked).hasSize(1)
    val many = CatalogExtraModelSources({ _, _ -> List(10) { "https://huggingface.co/m/$it" } })
    assertThat(many.urls(spec.id, spec.sha256)).hasSize(CatalogExtraModelSources.MAX_EXTRA)
    val broken = CatalogExtraModelSources({ _, _ -> error("porta rotta") })
    assertWithMessage("fallire chiusi").that(broken.urls(spec.id, spec.sha256)).isEmpty()
  }

  @Test
  fun ilGestoreProvaNasFissatiEAggiuntiviInOrdineEDecideLImpronta() = runBlocking {
    val bytes = TestModels.bytes()
    val extraUrls = listOf("https://mirror.huggingface.co/a", "https://cdn-lfs.hf.co/b")
    val testSpec = TestModels.spec(bytes)
    val rig = ModelRig(bytes = bytes, spec = testSpec, extraSources = CatalogExtraModelSources({ _, _ -> extraUrls }, listOf(testSpec)))
    rig.shelf.add(ShelfCopy(rig.share, rig.device, testSpec.sha256, bytes.size.toLong()))
    val first = PinnedUrl.of(extraUrls[0])!!
    val second = PinnedUrl.of(extraUrls[1])!!
    rig.fake.serve(ModelSource.Https(first), TestModels.flipped(bytes, 10))
    rig.fake.serve(ModelSource.Https(second), bytes)
    val result = rig.manager.ensure(testSpec.id, ModelTrigger.USER_NOW, 10.minutes)
    assertThat(result).isEqualTo(EnsureResult.Ready)
    assertThat(rig.fake.calls).containsExactly(rig.nas, rig.https, ModelSource.Https(first), ModelSource.Https(second)).inOrder()
    assertWithMessage("il file arrivato dall'indirizzo aggiuntivo ha l'impronta del catalogo").that(rig.finalFile.readBytes()).isEqualTo(bytes)
  }

  @Test
  fun ilRoutingRifiutaSenzaReteUnIndirizzoNonFissato() = runBlocking {
    val calls = mutableListOf<ModelSource>()
    var offered = listOf("https://mirror.huggingface.co/a")
    val routing = RoutingModelFetcher(
      nas = ModelFetcher { _, _, _, _ -> FetchResult.NotFound },
      https = ModelFetcher { _, source, _, _ -> calls += source; FetchResult.NotFound },
      bundled = ModelFetcher { _, _, _, _ -> FetchResult.NotFound },
      extra = CatalogExtraModelSources({ _, _ -> offered }),
    )
    val partial = File(TestModels.tempDir(), "p.part")
    val stranger = ModelSource.Https(PinnedUrl.of("https://huggingface.co/altro")!!)
    assertThat(routing.fetch(spec, stranger, partial, 1.minutes)).isInstanceOf(FetchResult.Refused::class.java)
    assertThat(calls).isEmpty()
    val pinned = ModelSource.Https(spec.https.first())
    val extra = ModelSource.Https(PinnedUrl.of(offered.single())!!)
    assertThat(routing.fetch(spec, pinned, partial, 1.minutes)).isEqualTo(FetchResult.NotFound)
    assertThat(routing.fetch(spec, extra, partial, 1.minutes)).isEqualTo(FetchResult.NotFound)
    assertThat(calls).containsExactly(pinned, extra).inOrder()
    offered = emptyList()
    assertWithMessage("manifest scaduto: l'indirizzo aggiuntivo decade").that(routing.fetch(spec, extra, partial, 1.minutes))
      .isInstanceOf(FetchResult.Refused::class.java)
    assertThat(calls).hasSize(2)
  }
}
