package dev.pampa.mycloudgallery.core.models

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.models.contract.FetchResult
import dev.pampa.mycloudgallery.core.models.contract.ModelFetcher
import dev.pampa.mycloudgallery.core.models.contract.ModelSource
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import dev.pampa.mycloudgallery.core.models.download.https.HttpsModelFetcher
import dev.pampa.mycloudgallery.core.models.source.NasModelFetcher
import dev.pampa.mycloudgallery.core.models.support.FileBehavior
import dev.pampa.mycloudgallery.core.models.support.HttpsTestServer
import dev.pampa.mycloudgallery.core.models.support.TestConnections
import dev.pampa.mycloudgallery.core.models.support.TestModels
import dev.pampa.mycloudgallery.core.models.support.TrafficCounter
import dev.pampa.mycloudgallery.core.models.support.serve
import dev.pampa.mycloudgallery.core.models.testing.FakeModelFetcher
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * Parita' fra il fetcher finto di P0 e i fetcher veri (§5.2): la stessa suite di contratto gira sul fake, sul lettore del NAS e sul
 * fetcher HTTPS. Il manager dei modelli si prova col fake: qui si prova che il fake dice la verita'.
 */
abstract class FetcherContractBase {
  protected val bytes: ByteArray = TestModels.bytes(2 * TestModels.BLOCK + 7_000, seed = 21)
  protected val spec: ModelSpec = TestModels.spec(bytes)
  protected val partial = File(TestModels.tempDir(), "partial/${spec.sha256.hex}.part")

  abstract val fetcher: ModelFetcher
  abstract val source: ModelSource

  /** Il contenuto che la sorgente serve da adesso; null = la sorgente non ha il file. */
  abstract fun serve(content: ByteArray?)

  @After
  open fun stop() {}

  protected fun fetch(): FetchResult = runBlocking { fetcher.fetch(spec, source, partial, 60.seconds) }

  /** Chiama [fetch] finche' non finisce (un fetcher puo' fermarsi a ogni blocco). */
  private fun fetchToTheEnd(): FetchResult {
    var last: FetchResult
    var guard = 0
    do {
      last = fetch()
    } while (last is FetchResult.Partial && ++guard < 20)
    return last
  }

  @Test
  fun scaricaTuttoIlFile() {
    serve(bytes)
    assertThat(fetchToTheEnd()).isEqualTo(FetchResult.Done)
    assertThat(partial.readBytes()).isEqualTo(bytes)
  }

  @Test
  fun riprendeDaUnParzialeSenzaToccareIlPrefisso() {
    serve(bytes)
    partial.parentFile.mkdirs()
    partial.writeBytes(bytes.copyOf(TestModels.BLOCK))
    assertThat(fetchToTheEnd()).isEqualTo(FetchResult.Done)
    assertThat(partial.readBytes()).isEqualTo(bytes)
  }

  @Test
  fun unParzialeGiaCompletoEFinito() {
    serve(bytes)
    partial.parentFile.mkdirs()
    partial.writeBytes(bytes)
    assertThat(fetch()).isEqualTo(FetchResult.Done)
    assertThat(partial.readBytes()).isEqualTo(bytes)
  }

  @Test
  fun unFileDiUnAltraLunghezzaNonEIlModello() {
    for (content in listOf(bytes + byteArrayOf(0), bytes.copyOf(bytes.size - 1))) {
      serve(content)
      // Un file di un'altra lunghezza e' un altro file: lo si vede prima di leggere (SizeMismatch), il parziale di altri non si butta (R13).
      assertThat(fetchToTheEnd()).isEqualTo(FetchResult.SizeMismatch)
    }
  }

  @Test
  fun unFileAssenteNonEUnErrore() {
    serve(null)
    assertThat(fetchToTheEnd()).isEqualTo(FetchResult.NotFound)
  }

  @Test
  fun nessunByteOltreLaDimensioneFissataNelParziale() {
    serve(bytes + ByteArray(5_000))
    fetchToTheEnd()
    assertThat(partial.length()).isAtMost(bytes.size.toLong())
  }
}

class FakeFetcherContractTest : FetcherContractBase() {
  private val fake = FakeModelFetcher(chunk = TestModels.BLOCK)
  override val fetcher: ModelFetcher = fake
  override val source: ModelSource = ModelSource.NasCopy("test-comune", TrustIds.random())

  override fun serve(content: ByteArray?) {
    if (content != null) fake.serve(source, content)
  }
}

class NasFetcherContractTest : FetcherContractBase() {
  private val share = "test-comune"
  private val nas = InMemoryNasMedia().apply { addShare(share) }
  private val device = TrustIds.random()
  override val fetcher: ModelFetcher = NasModelFetcher(nas.reader, TrafficCounter(), Purpose.BACKGROUND)
  override val source: ModelSource = ModelSource.NasCopy(share, device)

  override fun serve(content: ByteArray?) {
    val path = NasLayout.modelCopy(device, spec.sha256.hex).value
    if (content == null) {
      if (nas.exists(share, path)) nas.delete(share, path)
    } else {
      nas.putFile(share, path, NasContent.of(content))
    }
  }
}

class PcFolderFetcherContractTest : FetcherContractBase() {
  private val share = "test-comune"
  private val nas = InMemoryNasMedia().apply { addShare(share) }
  override val fetcher: ModelFetcher = NasModelFetcher(nas.reader, TrafficCounter(), Purpose.BACKGROUND)
  override val source: ModelSource = ModelSource.PcFolder(share)

  override fun serve(content: ByteArray?) {
    val path = "MyCloud Gallery/Modelli/${spec.fileName}"
    if (content == null) {
      if (nas.exists(share, path)) nas.delete(share, path)
    } else {
      nas.putFile(share, path, NasContent.of(content))
    }
  }
}

class HttpsFetcherContractTest : FetcherContractBase() {
  private val server = HttpsTestServer()
  override val fetcher: ModelFetcher = HttpsModelFetcher(TrafficCounter(), TestConnections(server), Dispatchers.IO, connectTimeoutMs = 5_000, readTimeoutMs = 5_000)
  override val source: ModelSource = ModelSource.Https(spec.https.single())

  override fun serve(content: ByteArray?) {
    if (content == null) {
      server.handler = { exchange, _ -> exchange.sendResponseHeaders(404, -1) }
    } else {
      val behavior = FileBehavior(content)
      server.handler = { exchange, _ -> exchange.serve(behavior) }
    }
  }

  @After
  override fun stop() {
    server.close()
  }
}
