package dev.pampa.mycloudgallery.core.models

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.models.contract.FetchResult
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelSource
import dev.pampa.mycloudgallery.core.models.contract.PinnedUrl
import dev.pampa.mycloudgallery.core.models.contract.ShelfResult
import dev.pampa.mycloudgallery.core.models.testing.FakeModelFetcher
import dev.pampa.mycloudgallery.core.models.testing.FakeModelFiles
import dev.pampa.mycloudgallery.core.models.testing.InMemoryModelShelf
import dev.pampa.mycloudgallery.core.models.contract.ShelfCopy
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Fase 6 (P0, §5.2): i fake dei modelli fanno davvero cio' che promettono (verifica a ogni apertura, tetto, ripresa, esclusivita'). */
class ModelFakesTest {

  private val dir = Files.createTempDirectory("models").toFile().apply { deleteOnExit() }

  /** D16, E-A11: la verifica e' a OGNI apertura; un file alterato dopo si rifiuta e si toglie. */
  @Test
  fun unFileAlteratoDopoLaVerificaSiRifiutaAllaProssimaApertura() = runTest {
    val files = FakeModelFiles(dir)
    val spec = files.install(ModelCatalog.QUERY_270M, ByteArray(4096) { (it % 251).toByte() })
    assertThat(files.open(spec.id)).isNotNull()
    files.fileOf(spec.id)!!.appendBytes(byteArrayOf(1))
    assertThat(files.open(spec.id)).isNull()
    assertThat(files.rejected).isEqualTo(1)
    assertThat(files.fileOf(spec.id)!!.exists()).isFalse()
  }

  /** E-A1: la ripresa riparte dal parziale; il tetto della dimensione fissata non si supera mai. */
  @Test
  fun loScaricamentoRiprendeENonSuperaIlTetto() = runTest {
    val bytes = ByteArray(10_000) { it.toByte() }
    val spec = ModelCatalog.QUERY_270M.copy(sizeBytes = bytes.size.toLong())
    val source = ModelSource.Https(PinnedUrl.of("https://huggingface.co/x/y.litertlm")!!)
    val fetcher = FakeModelFetcher(chunk = 4_000)
    fetcher.serve(source, bytes)
    val partial = dir.resolve("partial/y.part")
    assertThat(fetcher.fetch(spec, source, partial, 1.seconds)).isEqualTo(FetchResult.Partial(4_000))
    assertThat(fetcher.fetch(spec, source, partial, 1.seconds)).isEqualTo(FetchResult.Partial(8_000))
    assertThat(fetcher.fetch(spec, source, partial, 1.seconds)).isEqualTo(FetchResult.Done)
    assertThat(partial.readBytes()).isEqualTo(bytes)

    val tooBig = ModelSource.Https(PinnedUrl.of("https://huggingface.co/x/z.litertlm")!!)
    fetcher.serve(tooBig, ByteArray(10_001))
    assertThat(fetcher.fetch(spec, tooBig, dir.resolve("partial/z.part"), 1.seconds)).isEqualTo(FetchResult.SizeMismatch)
    assertThat(fetcher.fetch(spec, ModelSource.PcFolder("comune"), dir.resolve("partial/w.part"), 1.seconds)).isEqualTo(FetchResult.NotFound)
  }

  /** D18: scrittura esclusiva nella propria cartella; `pruneOwn` non tocca le copie degli altri. */
  @Test
  fun loScaffaleToccaSoloLaPropriaCartella() = runTest {
    val shelf = InMemoryModelShelf(ownDevice = "io")
    val spec = ModelCatalog.QUERY_270M
    assertThat(shelf.publish(spec, "comune")).isEqualTo(ShelfResult.Published)
    assertThat(shelf.publish(spec, "comune")).isEqualTo(ShelfResult.AlreadyThere)
    shelf.add(ShelfCopy("comune", "altro", ModelCatalog.INDEX_440M.sha256, 1))
    shelf.add(ShelfCopy("comune", "io", ModelCatalog.INDEX_440M.sha256, 1))
    assertThat(shelf.pruneOwn("comune", keep = setOf(spec.sha256))).isEqualTo(1)
    assertThat(shelf.copies("comune", ModelCatalog.INDEX_440M.sha256).map { it.device }).containsExactly("altro")
    shelf.blocked = "local_only"
    assertThat(shelf.publish(ModelCatalog.INDEX_440M, "comune")).isEqualTo(ShelfResult.Blocked("local_only"))
  }
}
