package dev.pampa.mycloudgallery.core.models

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.models.contract.BundledAssets
import dev.pampa.mycloudgallery.core.models.contract.EnsureResult
import dev.pampa.mycloudgallery.core.models.contract.FetchRefusal
import dev.pampa.mycloudgallery.core.models.contract.FetchResult
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelFetcher
import dev.pampa.mycloudgallery.core.models.contract.ModelLimits
import dev.pampa.mycloudgallery.core.models.contract.ModelNetworkGate
import dev.pampa.mycloudgallery.core.models.contract.ModelNetworkVerdict
import dev.pampa.mycloudgallery.core.models.contract.ModelShelfDevices
import dev.pampa.mycloudgallery.core.models.contract.ModelShelfGate
import dev.pampa.mycloudgallery.core.models.contract.ModelSourceKind
import dev.pampa.mycloudgallery.core.models.contract.ShelfResult
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.models.noop.ClosedModelNetworkGate
import dev.pampa.mycloudgallery.core.models.noop.NoBundledAssets
import dev.pampa.mycloudgallery.core.models.noop.NoModelFiles
import dev.pampa.mycloudgallery.core.models.noop.NoModelManager
import dev.pampa.mycloudgallery.core.models.noop.NoModelShelf
import dev.pampa.mycloudgallery.core.models.noop.NoModelTraffic
import dev.pampa.mycloudgallery.core.models.noop.NoShelfWriter
import dev.pampa.mycloudgallery.core.models.noop.NoModelNasArea
import dev.pampa.mycloudgallery.core.models.noop.ClosedModelShelfGate
import dev.pampa.mycloudgallery.core.models.shelf.NasModelShelf
import dev.pampa.mycloudgallery.core.models.support.TestModels
import dev.pampa.mycloudgallery.core.models.testing.InMemoryShelfWriter
import dev.pampa.mycloudgallery.core.models.traffic.LedgerModelTraffic
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.rules.run.testing.InMemoryTrafficLedger
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficNetwork
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficSource
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Il montaggio pubblico (`ModelFetchers`, `LocalModels`), il registro del traffico e i null-object (falliscono chiusi). Con il
 * fetcher vero montato come lo monta `:app`: NAS (copia e cartella dal PC) e asset dell'APK arrivano fino a `models/<sha256>`.
 */
class ModelWiringTest {

  /** Il cancello di una LAN senza Internet (F48): HTTPS no, il NAS e la cartella dal PC si' (R11: il cancello vale per sorgente). */
  private val lanOnly = ModelNetworkGate { _, _, source ->
    if (source == ModelSourceKind.HTTPS) ModelNetworkVerdict.Refused("offline") else ModelNetworkVerdict.Allowed
  }

  @Test
  fun ilTrafficoVaNelRegistroComeCalcoloAiSullaReteEffettiva() = runBlocking<Unit> {
    val ledger = InMemoryTrafficLedger()
    var network = TrafficNetwork.HOME_WIFI
    val traffic = LedgerModelTraffic(ledger) { network }
    traffic.record(1_000)
    network = TrafficNetwork.MOBILE
    traffic.record(250)
    traffic.record(0)
    traffic.record(-5)
    assertThat(ledger.entries.map { Triple(it.source, it.network, it.bytes) }).containsExactly(
      Triple(TrafficSource.AI_COMPUTE, TrafficNetwork.HOME_WIFI, 1_000L),
      Triple(TrafficSource.AI_COMPUTE, TrafficNetwork.MOBILE, 250L),
    ).inOrder()
  }

  @Test
  fun unaCopiaDiUnAltroDispositivoArrivaFinoAModelsPerLaStradaVera() = runBlocking<Unit> {
    val share = "test-comune"
    val me = TrustIds.random()
    val other = TrustIds.random()
    val bytes = TestModels.bytes()
    val spec = TestModels.spec(bytes)
    val nas = InMemoryNasMedia().apply { addShare(share) }
    nas.putFile(share, NasLayout.modelCopy(other, spec.sha256.hex).value, NasContent.of(bytes))
    val ledger = InMemoryTrafficLedger()
    val dir = TestModels.tempDir()
    val models = LocalModels(
      root = dir,
      fetcher = ModelFetchers.create(nas.reader, NoBundledAssets, LedgerModelTraffic(ledger) { TrafficNetwork.HOME_WIFI }),
      networkGate = lanOnly,
      shelfFor = { files -> NasModelShelf(me, files, nas.reader, InMemoryShelfWriter(nas, me), ModelShelfGate { null }, ModelShelfDevices { setOf(other) }) },
      area = { share },
      catalog = listOf(spec),
      freeSpace = { Long.MAX_VALUE },
      progressPoll = kotlin.time.Duration.ZERO,
    )
    // Lo scaffale trova la copia dell'altro dispositivo, il fetcher la legge, il gestore la verifica e la promuove.
    assertThat(models.manager.ensure(spec.id, ModelTrigger.USER_NOW, 1.minutes)).isEqualTo(EnsureResult.Ready)
    assertThat(File(dir, spec.sha256.hex).readBytes()).isEqualTo(bytes)
    assertThat(ledger.totalBytes).isEqualTo(bytes.size.toLong())
    assertThat((models.manager.states.value[spec.id] as ModelState.Ready).source).isEqualTo(ModelSourceKind.NAS)
    // Ora il dispositivo ha il modello verificato: lo mette anche nella propria cartella sul NAS, e solo li'.
    assertThat(models.shelf.publish(spec, share)).isEqualTo(ShelfResult.Published)
    assertThat(nas.bytes(share, NasLayout.modelCopy(me, spec.sha256.hex).value)).isEqualTo(bytes)
    // Lo scaffale non propone mai la copia propria (il dispositivo ha gia' il file): solo quella dell'altro, che e' nel registro firmato.
    assertThat(models.shelf.copies(share, spec.sha256).map { it.device }).containsExactly(other)
    // Un modello che l'app non elenca piu' si toglie dalla propria cartella, non da quella degli altri.
    assertThat(models.shelf.pruneOwn(share, setOf(Sha256.of(byteArrayOf(1))))).isEqualTo(1)
    assertThat(nas.exists(share, NasLayout.modelCopy(other, spec.sha256.hex).value)).isTrue()
  }

  @Test
  fun ilFetcherMontatoLeggeLaCartellaDalPcELAsset() = runBlocking<Unit> {
    val share = "test-comune"
    val bytes = TestModels.bytes(30_000, seed = 6)
    val pcSpec = TestModels.spec(bytes)
    val nas = InMemoryNasMedia().apply { addShare(share) }
    nas.putFile(share, "MyCloud Gallery/Modelli/${pcSpec.fileName}", NasContent.of(bytes))
    val ledger = InMemoryTrafficLedger()
    val traffic = LedgerModelTraffic(ledger) { TrafficNetwork.HOME_WIFI }
    val asset = TestModels.bytes(20_000, seed = 7)
    val cutout = ModelCatalog.CUTOUT_MAGICTOUCH.copy(sizeBytes = asset.size.toLong(), sha256 = Sha256.of(asset))
    val dir = TestModels.tempDir()
    val models = LocalModels(
      root = dir,
      fetcher = ModelFetchers.create(nas.reader, BundledAssets { name -> if (name == cutout.bundledAsset) ByteArrayInputStream(asset) else null }, traffic),
      networkGate = lanOnly,
      area = { share },
      catalog = listOf(pcSpec, cutout),
      freeSpace = { Long.MAX_VALUE },
      progressPoll = kotlin.time.Duration.ZERO,
    )
    // Il gestore fa tutto da solo: copia dal PC (HTTPS non raggiungibile: il cancello e' chiuso), asset dell'APK, verifica.
    assertThat(models.manager.ensure(pcSpec.id, ModelTrigger.USER_NOW, 1.minutes)).isEqualTo(EnsureResult.Ready)
    assertThat(models.manager.ensure(cutout.id, ModelTrigger.USER_NOW, 1.minutes)).isEqualTo(EnsureResult.Ready)
    assertThat(File(dir, pcSpec.sha256.hex).readBytes()).isEqualTo(bytes)
    assertThat(File(dir, cutout.sha256.hex).readBytes()).isEqualTo(asset)
    assertThat(models.files.open(pcSpec.id)).isNotNull()
    // Il traffico dei byte del NAS e' nel registro (l'asset non e' traffico).
    assertThat(ledger.totalBytes).isEqualTo(bytes.size.toLong())
    assertThat(ledger.entries.all { it.source == TrafficSource.AI_COMPUTE }).isTrue()
  }

  @Test
  fun unaSorgenteHttpsConUnCancelloChiusoNonApreMaiUnaConnessione() = runBlocking<Unit> {
    // Il fetcher vero con il cancello di rete chiuso: l'HTTPS non parte, nessuna connessione (nessun traffico).
    val bytes = TestModels.bytes(1_000)
    val spec = TestModels.spec(bytes)
    val ledger = InMemoryTrafficLedger()
    val models = LocalModels(
      root = TestModels.tempDir(),
      fetcher = ModelFetchers.create(InMemoryNasMedia().reader, NoBundledAssets, LedgerModelTraffic(ledger) { TrafficNetwork.HOME_WIFI }),
      networkGate = ClosedModelNetworkGate,
      catalog = listOf(spec),
      freeSpace = { Long.MAX_VALUE },
      progressPoll = kotlin.time.Duration.ZERO,
    )
    val result = models.manager.ensure(spec.id, ModelTrigger.USER_NOW, 30.seconds)
    assertThat(result).isEqualTo(EnsureResult.Deferred(ModelState.Queued(ModelTrigger.USER_NOW, dev.pampa.mycloudgallery.core.models.contract.ModelWait.NETWORK)))
    assertThat(ledger.entries).isEmpty()
  }

  // --- I limiti e i null-object ---------------------------------------------------------------------------------------------------

  @Test
  fun iLimitiSonoQuelliDelProgetto() {
    assertThat(ModelLimits.BLOCK_BYTES).isEqualTo(1_048_576)
    assertThat(ModelLimits.HTTPS_SLICE).isEqualTo(30.seconds)
    assertThat(ModelLimits.STRIKES_BEFORE_SKIP).isEqualTo(2)
    assertThat(ModelLimits.MAX_URL_CHARS).isAtLeast(2048)
  }

  @Test
  fun iMotiviOstiliSonoEsattamenteQuelliDiSicurezza() {
    assertThat(FetchRefusal.HOSTILE).containsExactly(
      FetchRefusal.REDIRECT_HOST,
      FetchRefusal.REDIRECT_SCHEME,
      FetchRefusal.REDIRECT_FORMAT,
      FetchRefusal.REDIRECT_LIMIT,
      FetchRefusal.ENCODING,
      FetchRefusal.CONTENT_RANGE,
      FetchRefusal.REPARSE_POINT,
    )
    for (neutral in listOf(FetchRefusal.SOURCE_KIND, FetchRefusal.COOKIE_HANDLER, FetchRefusal.HTTP_STATUS, FetchRefusal.DEVICE_ID, FetchRefusal.PERMISSION)) {
      assertThat(FetchRefusal.isHostile(neutral)).isFalse()
    }
  }

  @Test
  fun inullObjectFallisconoChiusi() = runBlocking<Unit> {
    assertThat(NoModelFiles.open(ModelCatalog.QUERY_270M.id)).isNull()
    assertThat(NoModelManager.ensure(ModelCatalog.QUERY_270M.id, ModelTrigger.USER_NOW, 1.seconds)).isInstanceOf(EnsureResult.Failed::class.java)
    assertThat(ClosedModelNetworkGate.verdict(ModelTrigger.USER_NOW, 10, ModelSourceKind.HTTPS)).isInstanceOf(ModelNetworkVerdict.Refused::class.java)
    assertThat(ClosedModelShelfGate.blockFor("x")).isNotNull()
    assertThat(NoModelNasArea.commonShare()).isNull()
    assertThat(NoBundledAssets.open("x")).isNull()
    NoModelTraffic.record(10)
    val writer = NoShelfWriter(TrustIds.random())
    assertThat(NoModelShelf.copies("x", ModelCatalog.QUERY_270M.sha256)).isEmpty()
    assertThat(writer.ownerDeviceId).hasLength(26)
  }

  @Test
  fun lAssembleaSenzaNasNeFetcherNonRompeNiente() = runBlocking<Unit> {
    val dir = TestModels.tempDir()
    val models = LocalModels(root = dir, fetcher = ModelFetcher { _, _, _, _ -> FetchResult.NotFound }, networkGate = ClosedModelNetworkGate)
    assertThat(models.manager.states.value.keys).containsExactlyElementsIn(ModelCatalog.ALL.map { it.id })
    assertThat(models.manager.states.value.values.toSet()).containsExactly(ModelState.Absent)
    models.refresh()
    assertThat(models.manager.usage().totalBytes).isEqualTo(0)
    assertThat(models.counters.mismatch).isEqualTo(0)
  }
}
