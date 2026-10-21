package dev.pampa.mycloudgallery.core.models

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.models.contract.FetchRefusal
import dev.pampa.mycloudgallery.core.models.contract.FetchResult
import dev.pampa.mycloudgallery.core.models.contract.ModelSource
import dev.pampa.mycloudgallery.core.models.contract.ModelTraffic
import dev.pampa.mycloudgallery.core.models.source.NasModelFetcher
import dev.pampa.mycloudgallery.core.models.support.TestModels
import dev.pampa.mycloudgallery.core.models.support.TrafficCounter
import dev.pampa.mycloudgallery.core.nas.contract.NasAttributes
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasRandomFile
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasFault
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasOp
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Fase 6, P3 (§2.1, D17, D18, E-A1, E-A2): le sorgenti dal NAS, cioe' la copia di un dispositivo e la cartella dal PC, sul NAS finto con
 * il `FaultPlan` del simulatore SMB (caduta della connessione, sessione scaduta, stallo, prima e dopo che il server abbia applicato
 * l'operazione, a ogni chiamata). Il file giusto si legge a blocchi da 1 MiB e si riprende dal confine; un file di un'altra
 * lunghezza, una cartella, un collegamento o un id di dispositivo senza la forma di un id non si leggono nemmeno.
 */
class NasModelFetcherTest {
  private val share = "test-comune"
  private val nas = InMemoryNasMedia().apply { addShare(share) }
  private val device = TrustIds.random()
  private val bytes = TestModels.bytes()
  private val spec = TestModels.spec(bytes)
  private val traffic = TrafficCounter()
  private val dir = TestModels.tempDir()
  private val partial = File(dir, "partial/${spec.sha256.hex}.part")
  private val copyPath = NasLayout.modelCopy(device, spec.sha256.hex).value
  private val pcPath = "MyCloud Gallery/Modelli/${spec.fileName}"
  private val fromNas = ModelSource.NasCopy(share, device)
  private val fromPc = ModelSource.PcFolder(share)

  private fun fetcher(reader: NasMediaReader = nas.reader, traffic: ModelTraffic = this.traffic, nanos: () -> Long = System::nanoTime) =
    NasModelFetcher(reader, traffic, Purpose.BACKGROUND, nanos = nanos)

  private fun fetch(source: ModelSource, budget: Duration = 60.seconds, fetcher: NasModelFetcher = fetcher()): FetchResult =
    runBlocking { fetcher.fetch(spec, source, partial, budget) }

  private fun putCopy(content: ByteArray = bytes) {
    nas.putFile(share, copyPath, NasContent.of(content))
  }

  // --- La copia di un dispositivo e la cartella dal PC --------------------------------------------------------------------------

  @Test
  fun leggeLaCopiaDiUnDispositivoABlocchi() {
    putCopy()
    assertThat(fetch(fromNas)).isEqualTo(FetchResult.Done)
    assertThat(partial.readBytes()).isEqualTo(bytes)
    assertThat(traffic.total).isEqualTo(bytes.size.toLong())
    assertThat(nas.bytesRead.get()).isEqualTo(bytes.size.toLong())
    assertThat(nas.listCalls.get()).isEqualTo(0)
  }

  @Test
  fun leggeLaCartellaDalPcPerNomeEDimensioneDelCatalogo() {
    nas.putFile(share, pcPath, NasContent.of(bytes))
    assertThat(fetch(fromPc)).isEqualTo(FetchResult.Done)
    assertThat(partial.readBytes()).isEqualTo(bytes)
    // Un file con un altro nome, nella stessa cartella, non si guarda nemmeno.
    nas.putFile(share, "MyCloud Gallery/Modelli/altro.litertlm", NasContent.of(ByteArray(10)))
    assertThat(nas.listCalls.get()).isEqualTo(0)
  }

  @Test
  fun unFileChePassaLaVerificaDelleLunghezzeMaHaUnByteDiversoNonELaffareDelFetcher() {
    // L'impronta la verifica il gestore sul file finito: qui un byte cambiato di pari lunghezza arriva, ed e' il gestore a scartarlo.
    putCopy(TestModels.flipped(bytes, 12_345))
    assertThat(fetch(fromNas)).isEqualTo(FetchResult.Done)
    assertThat(partial.readBytes()).isNotEqualTo(bytes)
  }

  @Test
  fun assenteOCondivisioneSparita() {
    assertThat(fetch(fromNas)).isEqualTo(FetchResult.NotFound)
    assertThat(fetch(fromPc)).isEqualTo(FetchResult.NotFound)
    assertThat(fetch(ModelSource.NasCopy("non-esiste", device))).isEqualTo(FetchResult.NotFound)
    assertThat(partial.exists()).isFalse()
  }

  @Test
  fun unaDimensioneDiversaSiRifiutaPrimaDiLeggereUnByte() {
    for (size in listOf(bytes.size - 1, bytes.size + 1, 0, 10)) {
      putCopy(ByteArray(size) { 5 })
      assertThat(fetch(fromNas)).isEqualTo(FetchResult.Mismatch)
      assertThat(nas.bytesRead.get()).isEqualTo(0)
      assertThat(partial.exists()).isFalse()
    }
    nas.putFile(share, pcPath, NasContent.of(bytes.copyOf(bytes.size - 1)))
    assertThat(fetch(fromPc)).isEqualTo(FetchResult.Mismatch)
    assertThat(nas.bytesRead.get()).isEqualTo(0)
  }

  @Test
  fun unaCartellaOUnCollegamentoNonSonoUnModello() {
    nas.putDir(share, copyPath)
    assertThat(fetch(fromNas)).isEqualTo(FetchResult.NotFound)
    nas.putFile(share, pcPath, NasContent.of(bytes), attributes = NasAttributes.ARCHIVE or NasAttributes.REPARSE_POINT)
    assertThat(fetch(fromPc)).isEqualTo(FetchResult.Refused(FetchRefusal.REPARSE_POINT))
    assertThat(nas.bytesRead.get()).isEqualTo(0)
  }

  @Test
  fun unNomeLettoDalNasCheNonEUnIdNonDiventaUnPercorso() {
    putCopy()
    for (bad in listOf("", "..", "../../x", "ABCDEFGHJKMNPQRSTVWXYZ0123", device.uppercase(), device.dropLast(1), device + "0", "a/b", "a\\b", "con", device.replace(device[0], 'i'))) {
      val before = nas.statCalls.get()
      assertThat(fetch(ModelSource.NasCopy(share, bad))).isEqualTo(FetchResult.Refused(FetchRefusal.DEVICE_ID))
      // Nessuna operazione sul NAS: il nome sbagliato non e' arrivato nemmeno a un percorso.
      assertThat(nas.statCalls.get()).isEqualTo(before)
    }
    assertThat(fetch(ModelSource.NasCopy("  ", device))).isEqualTo(FetchResult.Refused(FetchRefusal.DEVICE_ID))
  }

  @Test
  fun unaSorgenteCheNonEDelNasSiRifiuta() {
    val https = ModelSource.Https(spec.https.single())
    assertThat(fetch(https)).isEqualTo(FetchResult.Refused(FetchRefusal.SOURCE_KIND))
    assertThat(fetch(ModelSource.Bundled("x"))).isEqualTo(FetchResult.Refused(FetchRefusal.SOURCE_KIND))
  }

  // --- Ripresa e tempo ------------------------------------------------------------------------------------------------------------

  @Test
  fun riprendeDalConfineDiBloccoEButtaLaCoda() {
    putCopy()
    partial.parentFile.mkdirs()
    partial.writeBytes(bytes.copyOf(TestModels.BLOCK + 777))
    assertThat(fetch(fromNas)).isEqualTo(FetchResult.Done)
    assertThat(partial.readBytes()).isEqualTo(bytes)
    // Letto solo cio' che manca dal confine, non il file di nuovo.
    assertThat(nas.bytesRead.get()).isEqualTo(bytes.size - TestModels.BLOCK.toLong())
    assertThat(traffic.total).isEqualTo(bytes.size - TestModels.BLOCK.toLong())
  }

  @Test
  fun unParzialeCompletoNonToccaIlNas() {
    putCopy()
    partial.parentFile.mkdirs()
    partial.writeBytes(bytes)
    assertThat(fetch(fromNas)).isEqualTo(FetchResult.Done)
    assertThat(nas.statCalls.get()).isEqualTo(0)
    assertThat(nas.opens.get()).isEqualTo(0)
  }

  @Test
  fun rispettaIlTempoAiConfiniDiBlocco() {
    putCopy()
    val now = AtomicLong()
    val clock = ModelTraffic { now.addAndGet(1_000_000_000L) }
    val result = fetch(fromNas, budget = 1500.milliseconds, fetcher = fetcher(traffic = clock, nanos = { now.get() }))
    assertThat(result).isEqualTo(FetchResult.Partial(2L * TestModels.BLOCK))
    assertThat(fetch(fromNas)).isEqualTo(FetchResult.Done)
    assertThat(partial.readBytes()).isEqualTo(bytes)
  }

  @Test
  fun leLettureCorteSiRipetonoFinoAlBlocco() {
    putCopy()
    val trickle = TrickleReader(nas.reader, 1000)
    assertThat(fetch(fromNas, fetcher = fetcher(reader = trickle))).isEqualTo(FetchResult.Done)
    assertThat(partial.readBytes()).isEqualTo(bytes)
  }

  @Test
  fun unFileCheSiAccorciaFraLoStatELApertura() {
    putCopy()
    var cut = false
    nas.fault = FakeNasFault { op, _, _ ->
      if (op == FakeNasOp.OPEN && !cut) {
        cut = true
        nas.modify(share, copyPath, NasContent.of(bytes.copyOf(bytes.size - 5)))
      }
      null
    }
    assertThat(fetch(fromNas)).isEqualTo(FetchResult.Mismatch)
  }

  @Test
  fun unFileCheSiAccorciaMentreLoSiLeggeEUnaInterruzione() {
    putCopy()
    // Il file e' lungo giusto all'apertura ma le letture finiscono prima (rovinato sul server): niente di completo, si riprende.
    val shrinking = ShrinkingReader(nas.reader, endsAt = 2L * TestModels.BLOCK + 50)
    val result = fetch(fromNas, fetcher = fetcher(reader = shrinking))
    assertThat(result).isEqualTo(FetchResult.Partial(2L * TestModels.BLOCK))
  }

  // --- Errori e FaultPlan ---------------------------------------------------------------------------------------------------------

  @Test
  fun iCodiciDiErroreDelNasHannoUnEsitoStabile() {
    putCopy()
    val expected = mapOf(
      NasError.NotFound to FetchResult.NotFound,
      NasError.PermissionDenied to FetchResult.Refused(FetchRefusal.PERMISSION),
      NasError.Timeout to FetchResult.NetworkError,
      NasError.FileBusy to FetchResult.NetworkError,
      NasError.SessionExpired to FetchResult.NetworkError,
      NasError.ServiceDown to FetchResult.NetworkError,
      NasError.Stalled to FetchResult.NetworkError,
      NasError.LocalNetworkBlocked to FetchResult.NetworkError,
      NasError.CredentialsMissing to FetchResult.NetworkError,
    )
    for ((error, result) in expected) {
      nas.failWith = error
      assertThat(fetch(fromNas)).isEqualTo(result)
      assertThat(partial.exists()).isFalse()
    }
  }

  @Test
  fun unGuastoAOgniChiamataPrimaEDopoSiRipreSenzaPerdereUnByteDiTroppo() {
    putCopy()
    val faults = listOf(SimFault.DropConnection, SimFault.ExpireSession, SimFault.Stall)
    var injected = 0
    for (fault in faults) {
      for (phase in FaultPhase.entries) {
        // Il numero di chiamate del NAS per un modello da 4 blocchi: stato, apertura, quattro letture. Si guasta ognuna.
        for (n in 1..8) {
          val work = File(TestModels.tempDir(), "p/${spec.sha256.hex}.part")
          val plan = FaultPlan().at(n, phase, fault)
          nas.readFaults = plan
          val counter = TrafficCounter()
          val f = fetcher(traffic = counter)
          var attempts = 0
          var last: FetchResult
          do {
            attempts++
            last = runBlocking { f.fetch(spec, fromNas, work, 60.seconds) }
          } while (last != FetchResult.Done && attempts < 6)
          assertThat(last).isEqualTo(FetchResult.Done)
          assertThat(work.readBytes()).isEqualTo(bytes)
          // Una sola interruzione costa al piu' un blocco in piu' del file (e quello letto ma perso nel guasto "dopo").
          assertThat(counter.total).isAtMost(bytes.size + 4L * TestModels.BLOCK)
          assertThat(plan.fired.size).isAtMost(1)
          injected += plan.fired.size
        }
      }
    }
    // I guasti sono davvero scattati (un piano che non scatta non prova niente).
    assertThat(injected).isAtLeast(3 * 2 * 6)
  }

  @Test
  fun guastiCasualiRipetibiliAlFondoArriva() {
    putCopy()
    for (seed in 1L..5L) {
      val work = File(TestModels.tempDir(), "q/${spec.sha256.hex}.part")
      nas.readFaults = FaultPlan().random(seed, 0.3, FaultPhase.AFTER, SimFault.DropConnection)
      val f = fetcher()
      var attempts = 0
      var last: FetchResult
      do {
        attempts++
        last = runBlocking { f.fetch(spec, fromNas, work, 60.seconds) }
      } while (last != FetchResult.Done && attempts < 200)
      assertThat(last).isEqualTo(FetchResult.Done)
      assertThat(work.readBytes()).isEqualTo(bytes)
    }
  }

  /** Un lettore del NAS che consegna al piu' [chunk] byte per lettura (SMB puo' farlo). */
  private class TrickleReader(private val inner: NasMediaReader, private val chunk: Int) : NasMediaReader by inner {
    override suspend fun open(share: String, path: dev.pampa.mycloudgallery.core.trust.nas.SharePath, purpose: Purpose): NasResult<NasRandomFile> =
      when (val r = inner.open(share, path, purpose)) {
        is NasResult.Err -> r
        is NasResult.Ok -> NasResult.Ok(object : NasRandomFile by r.value {
          override suspend fun read(offset: Long, buf: ByteArray, off: Int, len: Int): Int = r.value.read(offset, buf, off, minOf(len, chunk))
        })
      }
  }

  /** Un lettore del NAS le cui letture finiscono (fine file) a [endsAt] byte, anche se il file ne dichiara di piu'. */
  private class ShrinkingReader(private val inner: NasMediaReader, private val endsAt: Long) : NasMediaReader by inner {
    override suspend fun open(share: String, path: dev.pampa.mycloudgallery.core.trust.nas.SharePath, purpose: Purpose): NasResult<NasRandomFile> =
      when (val r = inner.open(share, path, purpose)) {
        is NasResult.Err -> r
        is NasResult.Ok -> NasResult.Ok(object : NasRandomFile by r.value {
          override suspend fun read(offset: Long, buf: ByteArray, off: Int, len: Int): Int =
            if (offset >= endsAt) -1 else r.value.read(offset, buf, off, minOf(len.toLong(), endsAt - offset).toInt())
        })
      }
  }
}
