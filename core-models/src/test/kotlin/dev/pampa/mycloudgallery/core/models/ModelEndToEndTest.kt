package dev.pampa.mycloudgallery.core.models

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.models.contract.EnsureResult
import dev.pampa.mycloudgallery.core.models.contract.ModelFailCode
import dev.pampa.mycloudgallery.core.models.contract.ModelNetworkVerdict
import dev.pampa.mycloudgallery.core.models.contract.ModelShelfGate
import dev.pampa.mycloudgallery.core.models.contract.ModelSourceKind
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.models.contract.ShelfResult
import dev.pampa.mycloudgallery.core.models.download.https.HttpsModelFetcher
import dev.pampa.mycloudgallery.core.models.noop.NoBundledAssets
import dev.pampa.mycloudgallery.core.models.shelf.NasModelShelf
import dev.pampa.mycloudgallery.core.models.source.BundledModelFetcher
import dev.pampa.mycloudgallery.core.models.source.NasModelFetcher
import dev.pampa.mycloudgallery.core.models.source.RoutingModelFetcher
import dev.pampa.mycloudgallery.core.models.support.FileBehavior
import dev.pampa.mycloudgallery.core.models.support.HttpsTestServer
import dev.pampa.mycloudgallery.core.models.support.TestConnections
import dev.pampa.mycloudgallery.core.models.support.TestModels
import dev.pampa.mycloudgallery.core.models.support.TrafficCounter
import dev.pampa.mycloudgallery.core.models.support.assertModelsDirInvariant
import dev.pampa.mycloudgallery.core.models.support.serve
import dev.pampa.mycloudgallery.core.models.testing.FakeModelNetworkGate
import dev.pampa.mycloudgallery.core.models.testing.InMemoryShelfWriter
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * Fase 6, P3, da una parte all'altra: il gestore vero con i fetcher veri (HTTPS contro il server di prova del JDK, NAS e cartella dal PC
 * sul NAS finto) e la verifica vera. Le promesse della riga P3: un byte cambiato rifiutato (E-A2) anche arrivando da HTTPS, una
 * redirezione fuori elenco ferma la sorgente (E-S16) e si passa alla successiva, la ripresa dopo un'interruzione, "una volta sola da
 * Internet per la famiglia, poi dal NAS" (Q1, D18).
 */
class ModelEndToEndTest {
  private val share = "test-comune"
  private val server = HttpsTestServer()
  private val connections = TestConnections(server)
  private val nas = InMemoryNasMedia().apply { addShare(share) }
  private val traffic = TrafficCounter()
  private val bytes = TestModels.bytes()
  private val spec = TestModels.spec(bytes)
  private val behavior = FileBehavior(bytes)
  private val nasMillis = AtomicLong(1_700_000_000_000L)
  private val gate = FakeModelNetworkGate()
  private val long = 10.minutes
  private val pcPath = "MyCloud Gallery/Modelli/${spec.fileName}"

  @After
  fun stop() {
    server.close()
  }

  private fun models(dir: File = TestModels.tempDir(), me: String = TrustIds.random(), nasReader: InMemoryNasMedia = nas): LocalModels =
    LocalModels(
      root = dir,
      fetcher = RoutingModelFetcher(
        nas = NasModelFetcher(nasReader.reader, traffic),
        https = HttpsModelFetcher(traffic, connections, Dispatchers.IO, connectTimeoutMs = 5_000, readTimeoutMs = 5_000),
        bundled = BundledModelFetcher(NoBundledAssets),
      ),
      networkGate = gate,
      shelfFor = { files -> NasModelShelf(me, files, nasReader.reader, InMemoryShelfWriter(nasReader, me), ModelShelfGate { null }) },
      area = { share },
      catalog = listOf(spec),
      freeSpace = { Long.MAX_VALUE },
      clock = { nasMillis.get() },
      progressPoll = Duration.ZERO,
    )

  private fun LocalModels.ensure(trigger: ModelTrigger = ModelTrigger.USER_NOW): EnsureResult =
    runBlocking { manager.ensure(spec.id, trigger, long) }

  private fun serveFile() {
    server.handler = { exchange, _ -> exchange.serve(behavior) }
  }

  @Test
  fun daHttpsConUnaRedirezioneAlCdnFinoAModels() {
    val signed = "X-Amz-Signature=abc123&X-Amz-Expires=3600"
    server.handler = { exchange, request ->
      if (request.logicalHost == "huggingface.co") {
        exchange.responseHeaders.add("Location", "https://cas-bridge.xethub.hf.co/obj/model?$signed")
        exchange.sendResponseHeaders(302, -1)
      } else {
        exchange.serve(behavior)
      }
    }
    val dir = TestModels.tempDir()
    val models = models(dir)
    assertThat(models.ensure()).isEqualTo(EnsureResult.Ready)
    assertThat(File(dir, spec.sha256.hex).readBytes()).isEqualTo(bytes)
    assertThat((models.manager.states.value[spec.id] as ModelState.Ready).source).isEqualTo(ModelSourceKind.HTTPS)
    assertThat(traffic.total).isEqualTo(bytes.size.toLong())
    assertThat(server.requests.map { it.logicalHost }).containsExactly("huggingface.co", "cas-bridge.xethub.hf.co").inOrder()
    assertThat(server.requests.first().rawQuery).isNull()
    assertThat(server.requests.none { it.has("Cookie") }).isTrue()
    assertModelsDirInvariant(dir, listOf(spec))
    runBlocking { models.files.open(spec.id)!!.close() }
  }

  @Test
  fun unByteCambiatoArrivandoDaHttpsEScartatoESiRiprovaSoloQuandoServe() {
    val dir = TestModels.tempDir()
    val models = models(dir)
    val bad = FileBehavior(TestModels.flipped(bytes, 1_234_567))
    server.handler = { exchange, _ -> exchange.serve(bad) }
    // E-A2: stessa lunghezza, un byte diverso. Il server dice 200, la lunghezza torna, solo l'impronta lo tradisce.
    assertThat(models.ensure(ModelTrigger.RULES)).isEqualTo(EnsureResult.Failed(ModelFailCode.MISMATCH))
    assertThat(File(dir, spec.sha256.hex).exists()).isFalse()
    assertThat(File(dir, "partial").listFiles().orEmpty()).isEmpty()
    assertThat(models.counters.mismatch).isEqualTo(1)
    assertThat(models.ensure(ModelTrigger.RULES)).isEqualTo(EnsureResult.Failed(ModelFailCode.MISMATCH))
    // Due errori in un giorno: i lavori automatici non riprovano, e il server non vede piu' richieste.
    val seen = server.requests.size
    assertThat(models.ensure(ModelTrigger.RULES)).isEqualTo(EnsureResult.Failed(ModelFailCode.FETCH_FAILED))
    assertThat(server.requests).hasSize(seen)
    // L'utente preme "Scarica ora" quando il server e' stato sistemato: riprova, e il file giusto passa.
    server.handler = { exchange, _ -> exchange.serve(behavior) }
    assertThat(models.ensure(ModelTrigger.USER_NOW)).isEqualTo(EnsureResult.Ready)
    assertThat(File(dir, spec.sha256.hex).readBytes()).isEqualTo(bytes)
    assertModelsDirInvariant(dir, listOf(spec))
  }

  @Test
  fun unaRedirezioneFuoriElencoFermaHttpsEPassaAllaCartellaDalPc() {
    nas.putFile(share, pcPath, NasContent.of(bytes))
    server.handler = { exchange, _ ->
      exchange.responseHeaders.add("Location", "https://download.example.org/model.litertlm")
      exchange.sendResponseHeaders(302, -1)
    }
    val dir = TestModels.tempDir()
    val models = models(dir)
    assertThat(models.ensure()).isEqualTo(EnsureResult.Ready)
    // E-S16: l'host non ammesso non e' mai stato contattato, HTTPS e' stata contata come sorgente sbagliata, il file viene dal PC.
    assertThat(connections.opened).containsExactly(spec.https.single().value)
    assertThat(models.counters.mismatch).isEqualTo(1)
    assertThat((models.manager.states.value[spec.id] as ModelState.Ready).source).isEqualTo(ModelSourceKind.PC_FOLDER)
    assertThat(File(dir, spec.sha256.hex).readBytes()).isEqualTo(bytes)
  }

  @Test
  fun unaRedirezioneVersoHttpSenzaAltreSorgentiFallisce() {
    server.handler = { exchange, _ ->
      exchange.responseHeaders.add("Location", "http://huggingface.co/model.litertlm")
      exchange.sendResponseHeaders(302, -1)
    }
    val dir = TestModels.tempDir()
    val models = models(dir)
    assertThat(models.ensure()).isEqualTo(EnsureResult.Failed(ModelFailCode.MISMATCH))
    assertThat(File(dir, spec.sha256.hex).exists()).isFalse()
    assertThat(connections.opened).hasSize(1)
  }

  @Test
  fun unInterruzioneSiRiprendeNellaStessaRichiesta() {
    behavior.cutBodyAt = 2L * TestModels.BLOCK + 321
    var requests = 0
    server.handler = { exchange, _ ->
      requests++
      if (requests > 1) behavior.cutBodyAt = null
      exchange.serve(behavior)
    }
    val dir = TestModels.tempDir()
    val models = models(dir)
    assertThat(models.ensure()).isEqualTo(EnsureResult.Ready)
    assertThat(File(dir, spec.sha256.hex).readBytes()).isEqualTo(bytes)
    assertThat(server.requests.map { it.header("Range") }).containsExactly(null, "bytes=${2L * TestModels.BLOCK}-").inOrder()
    // Al piu' un blocco in piu' del file: la coda strappata (321 byte) e' passata ma non e' stata tenuta.
    assertThat(traffic.total - bytes.size).isAtMost(TestModels.BLOCK.toLong())
  }

  @Test
  fun interruzioniCasualiAOgniPuntoConvergonoAlFileGiusto() {
    for (seed in 1L..12L) {
      val random = Random(seed)
      val dir = TestModels.tempDir()
      val models = models(dir)
      var requests = 0
      server.requests.clear()
      val file = FileBehavior(bytes)
      server.handler = { exchange, _ ->
        requests++
        // Le prime richieste cadono a un punto qualunque (anche dentro un blocco), le ultime arrivano in fondo.
        file.cutBodyAt = if (requests <= 4) random.nextLong(1, TestModels.SIZE.toLong()) else null
        exchange.serve(file)
      }
      val before = traffic.total
      // Una connessione che cade prima del primo blocco intero non lascia niente da riprendere: la richiesta finisce "non adesso" e il
      // lavoro dopo riprova. Qui si ripete come fa il lavoro periodico.
      var rounds = 0
      var result: EnsureResult
      do {
        rounds++
        result = models.ensure()
      } while (result != EnsureResult.Ready && rounds < 12)
      assertThat(result).isEqualTo(EnsureResult.Ready)
      assertThat(File(dir, spec.sha256.hex).readBytes()).isEqualTo(bytes)
      assertModelsDirInvariant(dir, listOf(spec))
      // Ogni interruzione costa meno di un blocco intero riscaricato piu' la coda che il client ha letto e buttato.
      assertThat(traffic.total - before - bytes.size).isAtMost(4L * TestModels.BLOCK)
      assertThat(rounds).isAtMost(6)
    }
  }

  @Test
  fun ilCancelloDiReteChiusoLasciaIlServerInPace() {
    nas.putFile(share, pcPath, NasContent.of(bytes))
    serveFile()
    gate.verdict = ModelNetworkVerdict.Refused("roaming")
    val models = models()
    assertThat(models.ensure(ModelTrigger.RULES)).isEqualTo(EnsureResult.Ready)
    assertThat(server.requests).isEmpty()
    assertThat((models.manager.states.value[spec.id] as ModelState.Ready).source).isEqualTo(ModelSourceKind.PC_FOLDER)
  }

  @Test
  fun unaVoltaSolaDaInternetPerLaFamigliaPoiDalNas() {
    serveFile()
    val first = models(me = TrustIds.random())
    assertThat(first.ensure()).isEqualTo(EnsureResult.Ready)
    assertThat(server.requests).hasSize(1)
    // Il primo dispositivo mette il modello verificato nella propria cartella sul NAS (D18)...
    assertThat(runBlocking { first.shelf.publish(spec, share) }).isEqualTo(ShelfResult.Published)
    // ...e il secondo (un altro profilo, un altro telefono) lo prende da li', senza toccare Internet.
    val secondDir = TestModels.tempDir()
    val second = models(secondDir, me = TrustIds.random())
    assertThat(second.ensure()).isEqualTo(EnsureResult.Ready)
    assertThat(server.requests).hasSize(1)
    assertThat((second.manager.states.value[spec.id] as ModelState.Ready).source).isEqualTo(ModelSourceKind.NAS)
    assertThat(File(secondDir, spec.sha256.hex).readBytes()).isEqualTo(bytes)
    runBlocking { second.files.open(spec.id)!!.close() }
  }

  @Test
  fun unaCopiaFalsaSulNasCostaSoloUnRipiego() {
    serveFile()
    val device = TrustIds.random()
    // Un dispositivo compromesso mette sul NAS un file con il nome giusto, la lunghezza giusta e un byte diverso.
    nas.putFile(share, NasLayout.modelCopy(device, spec.sha256.hex).value, NasContent.of(TestModels.flipped(bytes, 777)))
    val dir = TestModels.tempDir()
    val models = models(dir, me = TrustIds.random())
    assertThat(models.ensure(ModelTrigger.RULES)).isEqualTo(EnsureResult.Ready)
    assertThat(File(dir, spec.sha256.hex).readBytes()).isEqualTo(bytes)
    assertThat(models.counters.mismatch).isEqualTo(1)
    assertThat((models.manager.states.value[spec.id] as ModelState.Ready).source).isEqualTo(ModelSourceKind.HTTPS)
    assertModelsDirInvariant(dir, listOf(spec))
  }

  @Test
  fun lAperturaDopoLoScaricamentoVerificaDiNuovo() {
    serveFile()
    val dir = TestModels.tempDir()
    val models = models(dir)
    assertThat(models.ensure()).isEqualTo(EnsureResult.Ready)
    File(dir, spec.sha256.hex).let { file ->
      val altered = file.readBytes()
      altered[altered.size - 1] = (altered.last().toInt() xor 1).toByte()
      file.writeBytes(altered)
    }
    assertThat(runBlocking { models.files.open(spec.id) }).isNull()
    // Rimesso in coda: la richiesta dopo lo riscarica.
    assertThat(models.ensure()).isEqualTo(EnsureResult.Ready)
    assertThat(File(dir, spec.sha256.hex).readBytes()).isEqualTo(bytes)
    assertThat(models.counters.verifyFailed).isEqualTo(1)
  }

  @Test
  fun lAnnullamentoDelChiamanteNonLasciaNienteDiStrano() {
    // Un server lento: la richiesta dell'utente viene annullata a meta'; il file parziale resta valido per la ripresa.
    var first = true
    server.handler = { exchange, _ ->
      if (first) {
        first = false
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.write(bytes, 0, TestModels.BLOCK)
        exchange.responseBody.flush()
        Thread.sleep(8_000)
      } else {
        exchange.serve(behavior)
      }
    }
    val dir = TestModels.tempDir()
    val models = models(dir)
    val started = System.nanoTime()
    runBlocking {
      val job = launch(Dispatchers.Default) { models.manager.ensure(spec.id, ModelTrigger.USER_NOW, long) }
      delay(1_500)
      job.cancel()
      job.join()
    }
    assertThat((System.nanoTime() - started) / 1e9).isLessThan(6.0)
    assertModelsDirInvariant(dir, listOf(spec))
    assertThat(File(dir, spec.sha256.hex).exists()).isFalse()
    assertThat(models.ensure()).isEqualTo(EnsureResult.Ready)
    assertThat(File(dir, spec.sha256.hex).readBytes()).isEqualTo(bytes)
  }

  @Test
  fun unOrologioDelNasCheSaltaNonSbloccaUnaSorgenteInQuarantena() {
    val bad = FileBehavior(TestModels.flipped(bytes, 5))
    server.handler = { exchange, _ -> exchange.serve(bad) }
    val models = models()
    repeat(2) { models.ensure(ModelTrigger.RULES) }
    val seen = server.requests.size
    // 23 ore dopo e' ancora in quarantena; 25 ore dopo si riprova.
    nasMillis.addAndGet(23.hours.inWholeMilliseconds)
    assertThat(models.ensure(ModelTrigger.RULES)).isEqualTo(EnsureResult.Failed(ModelFailCode.FETCH_FAILED))
    assertThat(server.requests).hasSize(seen)
    nasMillis.addAndGet(2.hours.inWholeMilliseconds)
    assertThat(models.ensure(ModelTrigger.RULES)).isEqualTo(EnsureResult.Failed(ModelFailCode.MISMATCH))
    assertThat(server.requests.size).isGreaterThan(seen)
  }

  @Test
  fun unaSorgenteSenzaIlFilePassaAllaSuccessivaSenzaRumore() {
    // HTTPS risponde 404 (il commit non c'e' piu'), la cartella dal PC ha il file.
    nas.putFile(share, pcPath, NasContent.of(bytes))
    server.handler = { exchange, _ -> exchange.sendResponseHeaders(404, -1) }
    val models = models()
    assertThat(models.ensure()).isEqualTo(EnsureResult.Ready)
    assertThat(models.counters.mismatch).isEqualTo(0)
    assertThat(models.counters.fetchFailed).isEqualTo(0)
  }
}
