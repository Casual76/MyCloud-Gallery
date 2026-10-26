package dev.pampa.mycloudgallery.core.models

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.models.contract.EnsureResult
import dev.pampa.mycloudgallery.core.models.contract.FetchRefusal
import dev.pampa.mycloudgallery.core.models.contract.FetchResult
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelFailCode
import dev.pampa.mycloudgallery.core.models.contract.ModelFetcher
import dev.pampa.mycloudgallery.core.models.contract.ModelGateReason
import dev.pampa.mycloudgallery.core.models.contract.ModelId
import dev.pampa.mycloudgallery.core.models.contract.ModelLimits
import dev.pampa.mycloudgallery.core.models.contract.ModelManager
import dev.pampa.mycloudgallery.core.models.contract.ModelNetworkGate
import dev.pampa.mycloudgallery.core.models.contract.ModelNetworkVerdict
import dev.pampa.mycloudgallery.core.models.contract.ModelSource
import dev.pampa.mycloudgallery.core.models.contract.ModelSourceKind
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.models.contract.ModelWait
import dev.pampa.mycloudgallery.core.models.support.FlakyFetcher
import dev.pampa.mycloudgallery.core.models.support.ModelRig
import dev.pampa.mycloudgallery.core.models.support.TestModels
import dev.pampa.mycloudgallery.core.models.support.TickingFetcher
import dev.pampa.mycloudgallery.core.models.support.assertModelsDirInvariant
import java.io.File
import java.io.RandomAccessFile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Fase 6, P3 (§2.1, D16-D20, E-A1, E-A2, E-A3, E-A11, E-S16): il gestore con un fetcher finto e una cartella `models/` vera su
 * disco. Un byte cambiato = file scartato e sorgente dopo; solo un file verificato entra in `models/<sha256>`; le sorgenti in ordine;
 * spazio controllato prima; cancello di rete rimisurato a ogni fetta; due errori in un giorno e la sorgente si salta; rimozione.
 */
class ModelManagerTest {
  private val long = 10.minutes

  private fun ModelRig.ensure(trigger: ModelTrigger = ModelTrigger.USER_NOW, budget: Duration = long): EnsureResult =
    runBlocking { manager.ensure(spec.id, trigger, budget) }

  /**
   * Il modello sparisce da questo dispositivo senza che l'utente l'abbia tolto: i test che ripartono da zero con i lavori automatici (RULES)
   * lo usano. "Rimuovi" dura (MO-3): qui si toglie il segno dell'utente che `remove` lascia, per provare le altre regole.
   */
  private fun ModelRig.removeIt() = runBlocking<Unit> {
    manager.remove(spec.id)
    File(dir, "meta/${spec.sha256.hex}.removed").delete()
  }

  private fun ModelRig.noStrayFiles() = assertModelsDirInvariant(dir, listOf(spec))

  /** Un fetcher che prima di ogni chiamata aspetta [gate] e poi fa lavorare quello finto del banco di prova. */
  private fun gated(rig: () -> ModelRig, entered: CompletableDeferred<Unit>? = null, gate: CompletableDeferred<Unit>? = null, only: ModelId? = null) =
    ModelFetcher { spec, source, partial, budget ->
      if (only == null || spec.id == only) {
        entered?.complete(Unit)
        gate?.await()
      }
      rig().fake.fetch(spec, source, partial, budget)
    }

  // --- Il percorso felice e l'ordine delle sorgenti ---------------------------------------------------------------------------

  @Test
  fun scaricaVerificaEPromuove() {
    val rig = ModelRig()
    rig.nasCopy()
    assertThat(rig.ensure()).isEqualTo(EnsureResult.Ready)
    assertThat(rig.finalFile.readBytes()).isEqualTo(rig.bytes)
    assertThat(File(rig.dir, "partial").listFiles().orEmpty()).isEmpty()
    assertThat(rig.stateOfModel()).isEqualTo(ModelState.Ready(rig.bytes.size.toLong(), ModelSourceKind.NAS, 1_000L))
    assertThat(runBlocking { rig.files.open(rig.spec.id) }).isNotNull()
    rig.noStrayFiles()
  }

  @Test
  fun gliStatiPassanoPerInCodaScaricamentoVerificaPronto() = runBlocking<Unit> {
    val rig = ModelRig()
    rig.nasCopy()
    rig.manager.states.test {
      assertThat(awaitItem()[rig.spec.id]).isEqualTo(ModelState.Absent)
      val ensured = async(Dispatchers.IO) { rig.manager.ensure(rig.spec.id, ModelTrigger.USER_NOW, long) }
      val seen = mutableListOf<ModelState>()
      while (true) {
        val state = awaitItem()[rig.spec.id] ?: continue
        seen += state
        if (state is ModelState.Ready) break
      }
      assertThat(ensured.await()).isEqualTo(EnsureResult.Ready)
      assertThat(seen.map { it::class.simpleName }.distinct()).containsAtLeast("Queued", "Fetching", "Verifying", "Ready").inOrder()
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun vincePrimaLaSorgentePiuAltaEleAltreNonSiChiamano() {
    val rig = ModelRig()
    rig.nasCopy()
    rig.fake.serve(rig.https, rig.bytes)
    rig.fake.serve(rig.pc, rig.bytes)
    assertThat(rig.ensure()).isEqualTo(EnsureResult.Ready)
    assertThat(rig.fake.calls).containsExactly(rig.nas)
  }

  @Test
  fun leSorgentiSiProvanoNasHttpsCartellaDalPc() {
    val rig = ModelRig()
    // La copia sul NAS e' annunciata ma non da' byte; HTTPS non li ha; la cartella dal PC si'.
    rig.nasCopy(content = ByteArray(0))
    rig.fake.serve(rig.pc, rig.bytes)
    assertThat(rig.ensure()).isEqualTo(EnsureResult.Ready)
    assertThat(rig.fake.calls).containsExactly(rig.nas, rig.https, rig.pc).inOrder()
    assertThat((rig.stateOfModel() as ModelState.Ready).source).isEqualTo(ModelSourceKind.PC_FOLDER)
    rig.noStrayFiles()
  }

  @Test
  fun senzaNasRestanoGliIndirizziHttps() {
    val rig = ModelRig()
    rig.area = null
    rig.fake.serve(rig.https, rig.bytes)
    assertThat(rig.ensure()).isEqualTo(EnsureResult.Ready)
    assertThat(rig.fake.calls).containsExactly(rig.https)
    assertThat((rig.stateOfModel() as ModelState.Ready).source).isEqualTo(ModelSourceKind.HTTPS)
  }

  @Test
  fun piuCopieSulNasSiProvanoInOrdineDiDispositivoEDopoIlPrimoErroreSiVaSuHttps() {
    val rig = ModelRig()
    for (id in listOf("zzz", "aaa", "mmm")) rig.nasCopy(content = ByteArray(0), deviceId = id)
    rig.fake.serve(ModelSource.NasCopy(rig.share, "mmm"), rig.bytes)
    rig.fake.serve(rig.https, rig.bytes)
    assertThat(rig.ensure()).isEqualTo(EnsureResult.Ready)
    // I5: la prima copia (in ordine di dispositivo) sbaglia, e le altre non si provano: HTTPS dopo il primo errore sul NAS.
    assertThat(rig.fake.calls).containsExactly(ModelSource.NasCopy(rig.share, "aaa"), rig.https).inOrder()
  }

  @Test
  fun unaCopiaSulNasConUnaDimensioneDiversaNonSiLeggeNemmeno() {
    val rig = ModelRig()
    rig.nasCopy(size = rig.bytes.size + 1L)
    rig.fake.serve(rig.https, rig.bytes)
    assertThat(rig.ensure()).isEqualTo(EnsureResult.Ready)
    assertThat(rig.fake.calls).containsExactly(rig.https)
    assertThat(rig.counters.mismatch).isEqualTo(1)
  }

  @Test
  fun unModelloGiaProntoRispondeSenzaRete() {
    val rig = ModelRig()
    rig.installFinal()
    assertThat(rig.ensure()).isEqualTo(EnsureResult.Ready)
    assertThat(rig.fake.calls).isEmpty()
    assertThat(rig.gate.asked).isEmpty()
    assertThat(rig.stateOfModel()).isInstanceOf(ModelState.Ready::class.java)
  }

  @Test
  fun unIdSconosciutoNonSiScarica() {
    val rig = ModelRig()
    val result = runBlocking { rig.manager.ensure(ModelCatalog.INDEX_440M.id, ModelTrigger.USER_NOW, long) }
    assertThat(result).isEqualTo(EnsureResult.Failed(ModelFailCode.FETCH_FAILED))
    assertThat(rig.fake.calls).isEmpty()
  }

  // --- E-A2: un byte cambiato -------------------------------------------------------------------------------------------------

  @Test
  fun unByteCambiatoAlloScaricamentoFaScartareIlFileEPassareAllaSorgenteDopo() {
    val rig = ModelRig()
    rig.nasCopy(content = TestModels.flipped(rig.bytes, rig.bytes.size / 2))
    rig.fake.serve(rig.https, rig.bytes)
    assertThat(rig.ensure()).isEqualTo(EnsureResult.Ready)
    // La sorgente buona e' ripartita da zero: il parziale della cattiva e' stato buttato.
    assertThat(rig.finalFile.readBytes()).isEqualTo(rig.bytes)
    assertThat(rig.fake.calls).containsExactly(rig.nas, rig.https).inOrder()
    assertThat(rig.counters.mismatch).isEqualTo(1)
    rig.noStrayFiles()
  }

  @Test
  fun ognunaDelleQuattroPosizioniDelByteCambiatoVieneRifiutata() {
    for (position in listOf(0, TestModels.BLOCK - 1, TestModels.BLOCK, TestModels.SIZE - 1)) {
      val rig = ModelRig()
      val bad = TestModels.flipped(rig.bytes, position)
      rig.nasCopy(content = bad)
      rig.fake.serve(rig.https, bad)
      rig.fake.serve(rig.pc, bad)
      assertThat(rig.ensure()).isEqualTo(EnsureResult.Failed(ModelFailCode.MISMATCH))
      assertThat(rig.finalFile.exists()).isFalse()
      assertThat(rig.partial.exists()).isFalse()
      assertThat(rig.counters.mismatch).isEqualTo(3)
      assertThat(rig.stateOfModel()).isEqualTo(ModelState.Failed(ModelFailCode.MISMATCH))
      rig.noStrayFiles()
    }
  }

  @Test
  fun unaLunghezzaSbagliataDallaSorgenteSiScarta() {
    val rig = ModelRig()
    rig.nasCopy(content = rig.bytes + byteArrayOf(0))
    rig.fake.serve(rig.https, rig.bytes.copyOf(rig.bytes.size - 1))
    rig.fake.serve(rig.pc, rig.bytes)
    assertThat(rig.ensure()).isEqualTo(EnsureResult.Ready)
    assertThat(rig.counters.mismatch).isEqualTo(2)
    rig.noStrayFiles()
  }

  @Test
  fun unaRedirezioneFuoriElencoEUnaSorgenteSbagliata() {
    val rig = ModelRig(fetcher = scripted { _, source -> if (source is ModelSource.Https) FetchResult.Refused(FetchRefusal.REDIRECT_HOST) else null })
    // E-S16: il rifiuto di una redirezione fuori elenco e' contato come `AI_MODEL_MISMATCH`, e si passa alla sorgente dopo.
    assertThat(rig.ensure()).isEqualTo(EnsureResult.Failed(ModelFailCode.MISMATCH))
    assertThat(rig.counters.mismatch).isEqualTo(1)
  }

  @Test
  fun unRifiutoNonOstileNonSiConta() {
    val rig = ModelRig(fetcher = scripted { _, source -> if (source is ModelSource.Https) FetchResult.Refused(FetchRefusal.HTTP_STATUS) else null })
    assertThat(rig.ensure()).isEqualTo(EnsureResult.Failed(ModelFailCode.FETCH_FAILED))
    assertThat(rig.counters.mismatch).isEqualTo(0)
    assertThat(rig.counters.fetchFailed).isEqualTo(1)
  }

  @Test
  fun dueErroriInUnGiornoFannoSaltareLaSorgenteSoloAiLavoriAutomatici() {
    val rig = ModelRig()
    rig.nasCopy(content = TestModels.flipped(rig.bytes, 10))
    rig.fake.serve(rig.https, rig.bytes)
    // Due errori della stessa sorgente (un dispositivo che da' un file sbagliato) in un giorno.
    repeat(2) {
      assertThat(rig.ensure(ModelTrigger.RULES)).isEqualTo(EnsureResult.Ready)
      rig.removeIt()
    }
    assertThat(rig.fake.calls.count { it == rig.nas }).isEqualTo(2)
    // Il terzo giro automatico non prova nemmeno la copia sbagliata...
    rig.fake.calls.clear()
    assertThat(rig.ensure(ModelTrigger.RULES)).isEqualTo(EnsureResult.Ready)
    assertThat(rig.fake.calls).containsExactly(rig.https)
    assertThat(rig.counters.skippedSources).isEqualTo(1)
    rig.removeIt()
    // ...ma chi preme "Scarica ora" riprova sempre.
    rig.fake.calls.clear()
    assertThat(rig.ensure(ModelTrigger.USER_NOW)).isEqualTo(EnsureResult.Ready)
    assertThat(rig.fake.calls).containsExactly(rig.nas, rig.https).inOrder()
    rig.removeIt()
    // Il giorno dopo la sorgente torna in lista (l'orologio e' quello del NAS).
    rig.nasMillis.addAndGet(25.hours.inWholeMilliseconds)
    rig.fake.calls.clear()
    assertThat(rig.ensure(ModelTrigger.ROLE_CHANGED)).isEqualTo(EnsureResult.Ready)
    assertThat(rig.fake.calls).containsExactly(rig.nas, rig.https).inOrder()
  }

  @Test
  fun gliErroriDelleSorgentiSiRicordanoAncheDopoLaMorteDelProcesso() {
    val first = ModelRig()
    first.nasCopy(content = TestModels.flipped(first.bytes, 5))
    first.fake.serve(first.https, first.bytes)
    repeat(2) {
      assertThat(first.ensure(ModelTrigger.RULES)).isEqualTo(EnsureResult.Ready)
      first.removeIt()
    }
    // Un altro processo (una nuova istanza sulla stessa cartella) ricorda i due errori.
    val second = ModelRig(first.bytes, dir = first.dir, spec = first.spec)
    second.nasMillis.set(first.nasMillis.get())
    second.nasCopy(content = TestModels.flipped(second.bytes, 5), deviceId = first.device)
    second.fake.serve(second.https, second.bytes)
    assertThat(second.ensure(ModelTrigger.RULES)).isEqualTo(EnsureResult.Ready)
    assertThat(second.fake.calls).containsExactly(second.https)
  }

  @Test
  fun unOrologioChePartePiuIndietroAzzeraGliErrori() {
    val rig = ModelRig()
    rig.nasCopy(content = TestModels.flipped(rig.bytes, 10))
    rig.fake.serve(rig.https, rig.bytes)
    repeat(2) {
      rig.ensure(ModelTrigger.RULES)
      rig.removeIt()
    }
    rig.nasMillis.addAndGet(-5.hours.inWholeMilliseconds)
    rig.fake.calls.clear()
    rig.ensure(ModelTrigger.RULES)
    assertThat(rig.fake.calls).containsExactly(rig.nas, rig.https).inOrder()
  }

  @Test
  fun unaSorgenteCheDaUnFileBuonoDimenticaISuoiErrori() {
    val rig = ModelRig()
    rig.fake.serve(rig.https, rig.bytes)
    rig.nasCopy(content = TestModels.flipped(rig.bytes, 10))
    assertThat(rig.ensure(ModelTrigger.RULES)).isEqualTo(EnsureResult.Ready) // errore 1 del NAS
    rig.removeIt()
    rig.fake.serve(rig.nas, rig.bytes)
    assertThat(rig.ensure(ModelTrigger.RULES)).isEqualTo(EnsureResult.Ready) // un file buono: errori dimenticati
    rig.removeIt()
    rig.fake.serve(rig.nas, TestModels.flipped(rig.bytes, 10))
    assertThat(rig.ensure(ModelTrigger.RULES)).isEqualTo(EnsureResult.Ready) // di nuovo un errore (il primo)
    rig.removeIt()
    // Senza il "dimentica" sarebbero due e la sorgente si salterebbe.
    rig.fake.calls.clear()
    assertThat(rig.ensure(ModelTrigger.RULES)).isEqualTo(EnsureResult.Ready)
    assertThat(rig.fake.calls.first()).isEqualTo(rig.nas)
  }

  @Test
  fun laCartellaDalPcNonSiBloccaMai() {
    val rig = ModelRig()
    rig.fake.serve(rig.pc, TestModels.flipped(rig.bytes, 3))
    repeat(4) {
      rig.fake.calls.clear()
      assertThat(rig.ensure(ModelTrigger.RULES)).isEqualTo(EnsureResult.Failed(ModelFailCode.MISMATCH))
      assertThat(rig.fake.calls).contains(rig.pc)
    }
    assertThat(rig.counters.skippedSources).isEqualTo(0)
  }

  // --- E-A3: spazio ------------------------------------------------------------------------------------------------------------

  @Test
  fun senzaSpazioNonSiScaricaNiente() {
    val rig = ModelRig()
    rig.nasCopy()
    val needed = rig.bytes.size + ModelLimits.SPACE_MARGIN_BYTES
    rig.freeBytes = needed - 1
    assertThat(rig.ensure()).isEqualTo(EnsureResult.Failed(ModelFailCode.NO_SPACE, needed))
    assertThat(rig.fake.calls).isEmpty()
    assertThat(rig.stateOfModel()).isEqualTo(ModelState.Failed(ModelFailCode.NO_SPACE))
    assertThat(rig.counters.noSpace).isEqualTo(1)
    // Con esattamente lo spazio che serve si scarica.
    rig.freeBytes = needed
    assertThat(rig.ensure()).isEqualTo(EnsureResult.Ready)
  }

  @Test
  fun unParzialeEsistenteAbbassaLoSpazioCheServe() {
    val rig = ModelRig()
    rig.nasCopy()
    rig.installPartial(rig.bytes.copyOf(TestModels.BLOCK))
    val needed = rig.bytes.size - TestModels.BLOCK + ModelLimits.SPACE_MARGIN_BYTES
    rig.freeBytes = needed - 1
    assertThat(rig.ensure()).isEqualTo(EnsureResult.Failed(ModelFailCode.NO_SPACE, needed))
    // Il parziale resta: mancare lo spazio non lo butta.
    assertThat(rig.partial.length()).isEqualTo(TestModels.BLOCK.toLong())
    rig.freeBytes = needed
    assertThat(rig.ensure()).isEqualTo(EnsureResult.Ready)
  }

  @Test
  fun ilDiscoPienoDuranteLoScaricamentoEUnNoSpace() {
    val rig = ModelRig(fetcher = scripted { _, _ -> FetchResult.NoSpace })
    val result = rig.ensure()
    assertThat(result).isInstanceOf(EnsureResult.Failed::class.java)
    assertThat((result as EnsureResult.Failed).code).isEqualTo(ModelFailCode.NO_SPACE)
    assertThat(rig.counters.noSpace).isEqualTo(1)
  }

  // --- Cancello di rete (HTTPS) -------------------------------------------------------------------------------------------------

  @Test
  fun ilCancelloDiReteRifiutaOChiedeConferma() {
    val rig = ModelRig()
    rig.area = null
    rig.fake.serve(rig.https, rig.bytes)
    rig.gate.verdict = ModelNetworkVerdict.Refused(ModelGateReason.ROAMING)
    // MO-8: un rifiuto del cancello e' "in coda" con il motivo, non un errore da riprovare.
    assertThat(rig.ensure(ModelTrigger.RULES)).isEqualTo(EnsureResult.Deferred(ModelState.Queued(ModelTrigger.RULES, ModelWait.ROAMING)))
    assertThat(rig.fake.calls).isEmpty()
    rig.gate.verdict = ModelNetworkVerdict.NeedsConfirmation(rig.bytes.size.toLong())
    assertThat(rig.ensure(ModelTrigger.USER_NOW)).isEqualTo(EnsureResult.Failed(ModelFailCode.NETWORK_REFUSED, rig.bytes.size.toLong()))
    assertThat(rig.gate.asked).containsExactly(ModelTrigger.RULES, ModelTrigger.USER_NOW).inOrder()
    rig.gate.verdict = ModelNetworkVerdict.Allowed
    assertThat(rig.ensure(ModelTrigger.USER_NOW)).isEqualTo(EnsureResult.Ready)
  }

  @Test
  fun ilCancelloDiReteVaChiestoPerOgniSorgenteEUnVerdettoPerSorgentePermetteIlNas() {
    // R11: in roaming nemmeno il NAS (raggiungibile con la VPN) ne' la cartella dal PC si leggono.
    val nasRig = ModelRig()
    nasRig.gate.verdict = ModelNetworkVerdict.Refused(ModelGateReason.ROAMING)
    nasRig.nasCopy()
    assertThat(nasRig.ensure()).isEqualTo(EnsureResult.Deferred(ModelState.Queued(ModelTrigger.USER_NOW, ModelWait.ROAMING)))
    assertThat(nasRig.fake.calls).isEmpty()
    assertThat(nasRig.gate.askedFor).contains(ModelSourceKind.NAS)
    // Un cancello che dice no all'HTTPS (dati mobili) e si' al NAS (LAN) non toglie il NAS ne' la cartella dal PC.
    val lan = ModelNetworkGate { _, _, source -> if (source == ModelSourceKind.HTTPS) ModelNetworkVerdict.Refused(ModelGateReason.METERED) else ModelNetworkVerdict.Allowed }
    val pcRig = ModelRig()
    pcRig.fake.serve(pcRig.pc, pcRig.bytes)
    assertThat(runBlocking { managerOver(pcRig, gate = lan).ensure(pcRig.spec.id, ModelTrigger.USER_NOW, long) }).isEqualTo(EnsureResult.Ready)
    assertThat(pcRig.fake.calls.last()).isEqualTo(pcRig.pc)
    assertThat(pcRig.fake.calls.filterIsInstance<ModelSource.Https>()).isEmpty()
  }

  @Test
  fun ilCancelloSiRileggeAOgniFettaEFermaLoScaricamentoAMezzo() {
    val rig = ModelRig(chunk = TestModels.BLOCK)
    rig.area = null
    rig.fake.serve(rig.https, rig.bytes)
    // Cancello vero: dopo due fette dice no (si e' passati ai dati mobili).
    var asks = 0
    val flipping = ModelNetworkGate { _, _, _ ->
      asks++
      if (asks <= 2) ModelNetworkVerdict.Allowed else ModelNetworkVerdict.Refused(ModelGateReason.METERED)
    }
    val manager = managerOver(rig, gate = flipping)
    assertThat(runBlocking { manager.ensure(rig.spec.id, ModelTrigger.RULES, long) })
      .isEqualTo(EnsureResult.Deferred(ModelState.Queued(ModelTrigger.RULES, ModelWait.WIFI)))
    // Due fette scaricate (2 MiB), il parziale resta e la terza non parte.
    assertThat(rig.partial.length()).isEqualTo(2L * TestModels.BLOCK)
    assertThat(rig.fake.calls).hasSize(2)
    // Tornato il Wi-Fi riprende da dove era: solo cio' che manca.
    val open = managerOver(rig)
    assertThat(runBlocking { open.ensure(rig.spec.id, ModelTrigger.RULES, long) }).isEqualTo(EnsureResult.Ready)
    assertThat(rig.fake.calls).hasSize(4)
    assertThat(rig.finalFile.readBytes()).isEqualTo(rig.bytes)
  }

  /** Un secondo gestore sulla stessa cartella e sugli stessi finti del banco di prova (con un cancello o un fetcher diversi). */
  private fun managerOver(rig: ModelRig, gate: ModelNetworkGate = rig.gate, fetcher: ModelFetcher = rig.fake): ModelManager =
    LocalModels(
      root = rig.dir,
      fetcher = fetcher,
      networkGate = gate,
      shelfFor = { rig.shelf },
      area = { rig.area },
      catalog = listOf(rig.spec),
      freeSpace = { Long.MAX_VALUE },
      nanos = { rig.monotonic.get() },
      progressPoll = Duration.ZERO,
    ).manager

  // --- Ripresa, tempo, una richiesta alla volta ---------------------------------------------------------------------------------

  @Test
  fun alVoltaDopoRiprendeDalParzialeEScaricaSoloCioCheManca() {
    val rig = ModelRig(chunk = TestModels.BLOCK)
    rig.nasCopy()
    val timeboxed = managerOver(rig, fetcher = TickingFetcher(rig.fake, rig.monotonic, 1_000_000_000L))
    // Una fetta al secondo e 2,5 s di tempo: tre fette, poi "non adesso".
    val first = runBlocking { timeboxed.ensure(rig.spec.id, ModelTrigger.RULES, 2500.milliseconds) }
    assertThat(first).isEqualTo(EnsureResult.Deferred(ModelState.Queued(ModelTrigger.RULES)))
    assertThat(rig.partial.length()).isEqualTo(3L * TestModels.BLOCK)
    rig.fake.calls.clear()
    assertThat(runBlocking { timeboxed.ensure(rig.spec.id, ModelTrigger.RULES, long) }).isEqualTo(EnsureResult.Ready)
    assertThat(rig.fake.calls).hasSize(1)
    assertThat(rig.finalFile.readBytes()).isEqualTo(rig.bytes)
  }

  @Test
  fun riprendeDaUnAltraSorgenteSeLaPrimaCade() {
    lateinit var rig: ModelRig
    rig = ModelRig(chunk = TestModels.BLOCK, fetcher = FlakyFetcher(gated({ rig }), mapOf(ModelSource.NasCopy("test-comune", "dev") to 2)))
    rig.nasCopy(deviceId = "dev")
    rig.fake.serve(rig.https, rig.bytes)
    assertThat(rig.ensure()).isEqualTo(EnsureResult.Ready)
    // Il NAS ha dato 2 MiB e poi e' caduto: il resto lo ha dato HTTPS, dallo stesso punto (stessi byte, stessa impronta).
    assertThat(rig.finalFile.readBytes()).isEqualTo(rig.bytes)
    assertThat(rig.fake.calls.map { it.kind }.distinct()).containsExactly(ModelSourceKind.NAS, ModelSourceKind.HTTPS).inOrder()
    assertThat(rig.fake.calls.count { it.kind == ModelSourceKind.NAS }).isEqualTo(2)
  }

  @Test
  fun unaSorgenteCheNonFaProgressiNonFaGirareAVuoto() {
    val rig = ModelRig(fetcher = scripted { _, _ -> FetchResult.Partial(0) })
    assertThat(rig.ensure()).isEqualTo(EnsureResult.Deferred(ModelState.Queued(ModelTrigger.USER_NOW)))
  }

  @Test
  fun unSoloRestartDaUn200NonEUnoStalloMaDueDiFilaFermanoLaSorgente() {
    lateinit var rig: ModelRig
    var calls = 0
    // Una fetta in ripresa che riceve un `200` riparte da zero (accorcia il parziale): una volta e' tollerata, perche' la ripresa dopo (col solo
    // `Range`) cresce; due di fila non avanzano e la sorgente si ferma (R13: un CDN che cambia ETag a ogni richiesta).
    val restarting = ModelFetcher { _, _, partial, _ ->
      partial.parentFile?.mkdirs()
      when (++calls) {
        1 -> partial.writeBytes(rig.bytes.copyOf(3 * TestModels.BLOCK)).let { FetchResult.Partial(3L * TestModels.BLOCK) }
        2 -> partial.writeBytes(rig.bytes.copyOf(TestModels.BLOCK)).let { FetchResult.Partial(TestModels.BLOCK.toLong()) }
        3 -> partial.writeBytes(rig.bytes.copyOf(2 * TestModels.BLOCK)).let { FetchResult.Partial(2L * TestModels.BLOCK) }
        else -> partial.writeBytes(rig.bytes).let { FetchResult.Done }
      }
    }
    rig = ModelRig(fetcher = restarting)
    // Una sola sorgente (HTTPS): se si desse per vinta alla seconda fetta senza progressi non ce ne sarebbe un'altra a finire.
    rig.area = null
    assertThat(rig.ensure()).isEqualTo(EnsureResult.Ready)
    assertThat(calls).isEqualTo(4)
  }

  @Test
  fun dueRestartDiFilaPerDei200FermanoLaSorgenteSenzaRiscaricareSempreGliStessiByte() {
    lateinit var rig: ModelRig
    var calls = 0
    val shrinking = ModelFetcher { _, _, partial, _ ->
      partial.parentFile?.mkdirs()
      val blocks = when (++calls) { 1 -> 3; 2 -> 2; 3 -> 1; else -> 0 }
      partial.writeBytes(rig.bytes.copyOf(blocks * TestModels.BLOCK))
      FetchResult.Partial(blocks.toLong() * TestModels.BLOCK)
    }
    rig = ModelRig(fetcher = shrinking)
    rig.area = null
    assertThat(rig.ensure()).isEqualTo(EnsureResult.Deferred(ModelState.Queued(ModelTrigger.USER_NOW)))
    // Terza chiamata (la seconda senza avanzare) e basta: nessun giro a vuoto fino alla fine del tempo.
    assertThat(calls).isEqualTo(3)
  }

  @Test
  fun unaSolaRichiestaAlloStessoTempoPerModello() = runBlocking<Unit> {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    lateinit var rig: ModelRig
    rig = ModelRig(fetcher = gated({ rig }, entered, release))
    rig.nasCopy()
    val first = async(Dispatchers.IO) { rig.manager.ensure(rig.spec.id, ModelTrigger.USER_NOW, long) }
    entered.await()
    // La seconda richiesta non aspetta: risponde con lo stato.
    val second = rig.manager.ensure(rig.spec.id, ModelTrigger.RULES, long)
    assertThat(second).isInstanceOf(EnsureResult.Deferred::class.java)
    assertThat((second as EnsureResult.Deferred).state).isInstanceOf(ModelState.Fetching::class.java)
    release.complete(Unit)
    assertThat(first.await()).isEqualTo(EnsureResult.Ready)
    // Dopo, un'altra richiesta e' un normale "gia' pronto".
    assertThat(rig.manager.ensure(rig.spec.id, ModelTrigger.RULES, long)).isEqualTo(EnsureResult.Ready)
  }

  @Test
  fun leRichiesteDiModelliDiversiVannoInParallelo() = runBlocking<Unit> {
    val other = TestModels.bytes(5_000, seed = 77)
    val otherSpec = TestModels.spec(other, ModelCatalog.INDEX_440M)
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    lateinit var rig: ModelRig
    rig = ModelRig(fetcher = gated({ rig }, entered, release, only = TestModels.spec(TestModels.bytes()).id), extra = listOf(otherSpec))
    rig.area = null
    rig.fake.serve(rig.https, rig.bytes)
    rig.fake.serve(ModelSource.Https(otherSpec.https.single()), other)
    val slow = async(Dispatchers.IO) { rig.manager.ensure(rig.spec.id, ModelTrigger.USER_NOW, long) }
    entered.await()
    assertThat(rig.manager.ensure(otherSpec.id, ModelTrigger.USER_NOW, long)).isEqualTo(EnsureResult.Ready)
    release.complete(Unit)
    assertThat(slow.await()).isEqualTo(EnsureResult.Ready)
  }

  @Test
  fun siPuoAnnullareLaRichiestaEIlParzialeRestaPerLaProssima() = runBlocking<Unit> {
    val entered = CompletableDeferred<Unit>()
    lateinit var rig: ModelRig
    val hanging = ModelFetcher { spec, source, partial, budget ->
      rig.fake.fetch(spec, source, partial, budget)
      entered.complete(Unit)
      delay(60_000)
      FetchResult.Done
    }
    rig = ModelRig(chunk = TestModels.BLOCK, fetcher = hanging)
    rig.nasCopy()
    val job = launch(Dispatchers.IO) { rig.manager.ensure(rig.spec.id, ModelTrigger.USER_NOW, long) }
    entered.await()
    job.cancel()
    job.join()
    assertThat(job.isCancelled).isTrue()
    assertThat(rig.partial.length()).isEqualTo(TestModels.BLOCK.toLong())
    assertThat(rig.stateOfModel()).isEqualTo(ModelState.Queued(ModelTrigger.USER_NOW))
    // La richiesta dopo non resta bloccata da quella annullata.
    assertThat(rig.manager.ensure(rig.spec.id, ModelTrigger.USER_NOW, Duration.ZERO)).isInstanceOf(EnsureResult.Deferred::class.java)
  }

  @Test
  fun senzaTempoNonSiFaNiente() {
    val rig = ModelRig()
    rig.nasCopy()
    assertThat(rig.ensure(budget = Duration.ZERO)).isEqualTo(EnsureResult.Deferred(ModelState.Queued(ModelTrigger.USER_NOW)))
    assertThat(rig.fake.calls).isEmpty()
  }

  @Test
  fun lAvanzamentoSiLeggeDalFileMentreIlFetcherLavora() = runBlocking<Unit> {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    lateinit var rig: ModelRig
    var calls = 0
    val slow = ModelFetcher { spec, source, partial, budget ->
      val result = rig.fake.fetch(spec, source, partial, budget)
      if (++calls == 1) {
        entered.complete(Unit)
        release.await()
      }
      result
    }
    rig = ModelRig(chunk = 2 * TestModels.BLOCK, fetcher = slow)
    rig.nasCopy()
    val polling = LocalModels(
      root = rig.dir,
      fetcher = slow,
      networkGate = rig.gate,
      shelfFor = { rig.shelf },
      area = { rig.share },
      catalog = listOf(rig.spec),
      freeSpace = { Long.MAX_VALUE },
      progressPoll = 20.milliseconds,
    ).manager
    val ensured = async(Dispatchers.IO) { polling.ensure(rig.spec.id, ModelTrigger.USER_NOW, long) }
    entered.await()
    polling.states.test {
      var seen: ModelState.Fetching? = null
      while (seen == null || seen.done < 2L * TestModels.BLOCK) {
        val state = awaitItem()[rig.spec.id]
        if (state is ModelState.Fetching) seen = state
      }
      assertThat(seen.total).isEqualTo(rig.bytes.size.toLong())
      cancelAndIgnoreRemainingEvents()
    }
    release.complete(Unit)
    assertThat(ensured.await()).isEqualTo(EnsureResult.Ready)
  }

  // --- E-A11: il file si altera dopo la verifica --------------------------------------------------------------------------------

  @Test
  fun unFileAlteratoDopoLaVerificaSiRifiutaSiTogliEsiRiscarica() = runBlocking<Unit> {
    val rig = ModelRig()
    rig.nasCopy()
    assertThat(rig.ensure()).isEqualTo(EnsureResult.Ready)
    RandomAccessFile(rig.finalFile.also { it.setWritable(true) }, "rw").use {
      it.seek(1_000)
      it.write(0x7e)
    }
    assertThat(rig.files.open(rig.spec.id)).isNull()
    assertThat(rig.finalFile.exists()).isFalse()
    assertThat(rig.stateOfModel()).isEqualTo(ModelState.Failed(ModelFailCode.VERIFY_FAILED))
    assertThat(rig.counters.verifyFailed).isEqualTo(1)
    // Rimesso in coda: la richiesta dopo lo riprende dalle sorgenti e torna pronto.
    assertThat(rig.ensure()).isEqualTo(EnsureResult.Ready)
    assertThat(rig.files.open(rig.spec.id)).isNotNull()
    assertThat(rig.finalFile.readBytes()).isEqualTo(rig.bytes)
  }

  @Test
  fun unFileAlteratoLoVedeAncheEnsureEPrimaDelCaricamento() {
    val rig = ModelRig()
    rig.nasCopy()
    rig.installFinal(TestModels.flipped(rig.bytes, 99))
    assertThat(rig.ensure()).isEqualTo(EnsureResult.Ready)
    assertThat(rig.finalFile.readBytes()).isEqualTo(rig.bytes)
    assertThat(rig.counters.verifyFailed).isEqualTo(1)
  }

  // --- Rimozione, uso dello spazio, pulizia (D20) -------------------------------------------------------------------------------

  @Test
  fun rimuoviToglieTuttoEDiceSeCera() = runBlocking<Unit> {
    val rig = ModelRig()
    rig.nasCopy()
    assertThat(rig.ensure()).isEqualTo(EnsureResult.Ready)
    val cache = File(rig.dir, "cache/${rig.spec.sha256.hex}-gpu").apply { mkdirs() }
    File(cache, "p").writeBytes(ByteArray(4))
    val usage = rig.manager.usage()
    assertThat(usage.bytesById).containsExactly(rig.spec.id, rig.bytes.size.toLong())
    assertThat(usage.partialBytes).isEqualTo(0)

    assertThat(rig.manager.remove(rig.spec.id)).isTrue()
    assertThat(rig.finalFile.exists()).isFalse()
    assertThat(File(rig.dir, "meta/${rig.spec.sha256.hex}").exists()).isFalse()
    assertThat(cache.exists()).isFalse()
    assertThat(rig.stateOfModel()).isEqualTo(ModelState.Absent)
    assertThat(rig.manager.usage().totalBytes).isEqualTo(0)
    // Una seconda volta non c'era piu'.
    assertThat(rig.manager.remove(rig.spec.id)).isFalse()
    assertThat(rig.manager.remove(ModelId("non-esiste"))).isFalse()
  }

  @Test
  fun rimuoviFermaUnoScaricamentoInCorso() = runBlocking<Unit> {
    val entered = CompletableDeferred<Unit>()
    lateinit var rig: ModelRig
    val hanging = ModelFetcher { spec, source, partial, budget ->
      rig.fake.fetch(spec, source, partial, budget)
      entered.complete(Unit)
      delay(60_000)
      FetchResult.Done
    }
    rig = ModelRig(chunk = TestModels.BLOCK, fetcher = hanging)
    rig.nasCopy()
    val ensured = async(Dispatchers.IO) { rig.manager.ensure(rig.spec.id, ModelTrigger.USER_NOW, long) }
    entered.await()
    assertThat(rig.manager.usage().partialBytes).isEqualTo(TestModels.BLOCK.toLong())
    // Il modello non c'era ancora (era in scaricamento): niente da dire "c'era", ma il parziale sparisce e l'attesa finisce.
    assertThat(rig.manager.remove(rig.spec.id)).isFalse()
    assertThat(ensured.await()).isInstanceOf(EnsureResult.Deferred::class.java)
    assertThat(rig.partial.exists()).isFalse()
    assertThat(rig.stateOfModel()).isEqualTo(ModelState.Absent)
  }

  @Test
  fun unModelloCheLAppNonElencaPiuSiTogliDaSe() = runBlocking<Unit> {
    val dir = TestModels.tempDir()
    val stranger = "b".repeat(64)
    File(dir, stranger).writeBytes(ByteArray(10))
    File(dir, "partial").mkdirs()
    File(dir, "partial/$stranger.part").writeBytes(ByteArray(10))
    File(dir, "partial/$stranger.part.etag").writeText("\"x\"")
    File(dir, "partial/$stranger.part.src").writeText("NAS")
    File(dir, "meta").mkdirs()
    File(dir, "meta/$stranger").writeText("NAS")
    File(dir, "cache/$stranger-gpu").mkdirs()
    File(dir, "cache/$stranger-gpu/prog").writeBytes(ByteArray(3))
    // Un file con un nome che non e' un'impronta non e' del gestore: non lo si tocca.
    File(dir, "note.txt").writeText("altro")
    val rig = ModelRig(dir = dir)
    rig.installFinal()
    rig.models.refresh()
    for (gone in listOf(stranger, "partial/$stranger.part", "partial/$stranger.part.etag", "partial/$stranger.part.src", "meta/$stranger", "cache/$stranger-gpu")) {
      assertThat(File(dir, gone).exists()).isFalse()
    }
    assertThat(File(dir, "note.txt").exists()).isTrue()
    assertThat(rig.finalFile.exists()).isTrue()
  }

  @Test
  fun unFilePresenteNonEProntoFinoAllaPrimaVerifica() = runBlocking<Unit> {
    val dir = TestModels.tempDir()
    val bytes = TestModels.bytes()
    File(dir, Sha256.of(bytes).hex).writeBytes(bytes)
    val rig = ModelRig(bytes, dir)
    assertThat(rig.stateOfModel()).isEqualTo(ModelState.Verifying)
    rig.models.refresh()
    assertThat((rig.stateOfModel() as ModelState.Ready).sizeBytes).isEqualTo(bytes.size.toLong())
  }

  @Test
  fun refreshTogliUnFileRovinatoEDiceloNelloStato() = runBlocking<Unit> {
    val dir = TestModels.tempDir()
    val bytes = TestModels.bytes()
    File(dir, Sha256.of(bytes).hex).writeBytes(TestModels.flipped(bytes, 5))
    val rig = ModelRig(bytes, dir)
    rig.models.refresh()
    assertThat(rig.finalFile.exists()).isFalse()
    assertThat(rig.stateOfModel()).isEqualTo(ModelState.Failed(ModelFailCode.VERIFY_FAILED))
  }

  @Test
  fun unFileDellaDimensioneSbagliataNonEMaiPronto() = runBlocking<Unit> {
    val dir = TestModels.tempDir()
    val bytes = TestModels.bytes()
    File(dir, Sha256.of(bytes).hex).writeBytes(bytes.copyOf(100))
    val rig = ModelRig(bytes, dir)
    assertThat(rig.stateOfModel()).isEqualTo(ModelState.Absent)
    rig.models.refresh()
    // La dimensione sbagliata si vede senza leggere il file: tolto, e lo stato lo dice.
    assertThat(rig.finalFile.exists()).isFalse()
    assertThat(rig.stateOfModel()).isEqualTo(ModelState.Failed(ModelFailCode.VERIFY_FAILED))
  }

  @Test
  fun lUsoDelloSpazioContaIParziali() = runBlocking<Unit> {
    val rig = ModelRig()
    rig.installPartial(rig.bytes.copyOf(5_000))
    val usage = rig.manager.usage()
    assertThat(usage.bytesById).isEmpty()
    assertThat(usage.partialBytes).isEqualTo(5_000)
    rig.installFinal()
    assertThat(rig.manager.usage().bytesById[rig.spec.id]).isEqualTo(rig.bytes.size.toLong())
  }

  // --- Il ritaglio dentro l'APK -------------------------------------------------------------------------------------------------

  @Test
  fun ilRitaglioSiEstraeSoloDallAssetEDoPoSegueLaStessaStrada() {
    val bytes = TestModels.bytes(40_000, seed = 12)
    val spec = ModelCatalog.CUTOUT_MAGICTOUCH.copy(sizeBytes = bytes.size.toLong(), sha256 = Sha256.of(bytes))
    val rig = ModelRig(bytes, spec = spec)
    val asset = ModelSource.Bundled(spec.bundledAsset!!)
    rig.fake.serve(asset, bytes)
    rig.gate.verdict = ModelNetworkVerdict.Refused("mai chiamato")
    assertThat(rig.ensure()).isEqualTo(EnsureResult.Ready)
    // Solo l'APK: niente copie del NAS, niente rete, niente cartella dal PC.
    assertThat(rig.fake.calls).containsExactly(asset)
    assertThat(rig.gate.asked).isEmpty()
    assertThat((rig.stateOfModel() as ModelState.Ready).source).isEqualTo(ModelSourceKind.BUNDLED)
    // Come gli altri: verificato a ogni apertura.
    assertThat(runBlocking { rig.files.open(spec.id) }).isNotNull()
    RandomAccessFile(rig.finalFile.also { it.setWritable(true) }, "rw").use { it.write(0x01) }
    assertThat(runBlocking { rig.files.open(spec.id) }).isNull()
  }

  /**
   * VR-2: il ritaglio sta nell'APK (6 MB): per metterlo fra i modelli verificati basta un margine piccolo, non i 300 MB di un modello da scaricare. Prima con
   * meno di circa 306 MB liberi il ritaglio non funzionava affatto ("Ritaglio non riuscito").
   */
  @Test
  fun ilRitaglioDellApkNonVuoleIlMargineDiUnModelloDaScaricare_VR2() {
    val bytes = TestModels.bytes(40_000, seed = 13)
    val spec = ModelCatalog.CUTOUT_MAGICTOUCH.copy(sizeBytes = bytes.size.toLong(), sha256 = Sha256.of(bytes))
    val asset = ModelSource.Bundled(spec.bundledAsset!!)
    val needed = bytes.size + ModelLimits.BUNDLED_SPACE_MARGIN_BYTES
    assertThat(ModelLimits.BUNDLED_SPACE_MARGIN_BYTES).isLessThan(ModelLimits.SPACE_MARGIN_BYTES)

    val short = ModelRig(bytes, spec = spec)
    short.fake.serve(asset, bytes)
    short.freeBytes = needed - 1
    assertThat(short.ensure()).isEqualTo(EnsureResult.Failed(ModelFailCode.NO_SPACE, needed))
    assertThat(short.fake.calls).isEmpty()

    // Con 20 MB liberi (molto meno dei 300 del margine dei modelli da scaricare) il ritaglio si mette a posto.
    val rig = ModelRig(bytes, spec = spec)
    rig.fake.serve(asset, bytes)
    rig.freeBytes = needed
    assertThat(rig.ensure()).isEqualTo(EnsureResult.Ready)
    // Un modello da scaricare vuole sempre il margine grande.
    assertThat(ModelLimits.spaceMargin(ModelCatalog.QUERY_270M)).isEqualTo(ModelLimits.SPACE_MARGIN_BYTES)
    assertThat(ModelLimits.spaceMargin(spec)).isEqualTo(ModelLimits.BUNDLED_SPACE_MARGIN_BYTES)
  }

  // --- La cartella come l'ha lasciata un processo morto ---------------------------------------------------------------------------

  @Test
  fun unParzialeCompletoAlRiavvioSiVerificaENonSiRiscarica() {
    val rig = ModelRig()
    rig.nasCopy()
    rig.installPartial(rig.bytes)
    File(rig.partial.path + ".src").writeText("PC_FOLDER")
    assertThat(rig.ensure()).isEqualTo(EnsureResult.Ready)
    assertThat(rig.fake.calls).isEmpty()
    assertThat((rig.stateOfModel() as ModelState.Ready).source).isEqualTo(ModelSourceKind.PC_FOLDER)
    assertThat(File(rig.dir, "partial").listFiles().orEmpty()).isEmpty()
  }

  @Test
  fun unParzialeCompletoMaRovinatoSiButtaSenzaColpaPerNessunaSorgente() {
    val rig = ModelRig()
    rig.nasCopy()
    rig.installPartial(TestModels.flipped(rig.bytes, 1_000))
    assertThat(rig.ensure(ModelTrigger.RULES)).isEqualTo(EnsureResult.Ready)
    assertThat(rig.fake.calls).containsExactly(rig.nas)
    assertThat(rig.counters.mismatch).isEqualTo(1)
    // Nessuna sorgente e' stata segnata.
    rig.removeIt()
    assertThat(rig.ensure(ModelTrigger.RULES)).isEqualTo(EnsureResult.Ready)
    assertThat(rig.counters.skippedSources).isEqualTo(0)
  }

  @Test
  fun unModelloProntoConResiduiDelloScaricamentoLiPulisce() {
    val rig = ModelRig()
    rig.installFinal()
    rig.installPartial(ByteArray(100))
    File(rig.partial.path + ".etag").writeText("\"v1\"")
    assertThat(rig.ensure()).isEqualTo(EnsureResult.Ready)
    assertThat(File(rig.dir, "partial").listFiles().orEmpty()).isEmpty()
  }

  @Test
  fun unParzialePiuLungoDelModelloSiButta() {
    val rig = ModelRig()
    rig.nasCopy()
    rig.installPartial(ByteArray(rig.bytes.size + 5))
    assertThat(rig.ensure()).isEqualTo(EnsureResult.Ready)
    assertThat(rig.finalFile.readBytes()).isEqualTo(rig.bytes)
  }

  /** Un fetcher scritto a mano: per ogni chiamata [script] decide l'esito (null = non trovato). */
  private fun scripted(script: (ModelSpec, ModelSource) -> FetchResult?) =
    ModelFetcher { spec, source, _, _ -> script(spec, source) ?: FetchResult.NotFound }
}
