package dev.pampa.mycloudgallery.core.models

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.models.contract.EnsureResult
import dev.pampa.mycloudgallery.core.models.contract.FetchResult
import dev.pampa.mycloudgallery.core.models.contract.FileStat
import dev.pampa.mycloudgallery.core.models.contract.FileStatReader
import dev.pampa.mycloudgallery.core.models.contract.ModelCounters
import dev.pampa.mycloudgallery.core.models.contract.ModelFailCode
import dev.pampa.mycloudgallery.core.models.contract.ModelFetcher
import dev.pampa.mycloudgallery.core.models.contract.ModelGateReason
import dev.pampa.mycloudgallery.core.models.contract.ModelNetworkVerdict
import dev.pampa.mycloudgallery.core.models.contract.ModelSource
import dev.pampa.mycloudgallery.core.models.contract.ModelSourceKind
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.models.contract.ModelWait
import dev.pampa.mycloudgallery.core.models.contract.ShelfCopy
import dev.pampa.mycloudgallery.core.models.contract.ShelfResult
import dev.pampa.mycloudgallery.core.models.store.ModelStore
import dev.pampa.mycloudgallery.core.models.store.PartialWriter
import dev.pampa.mycloudgallery.core.models.support.ModelRig
import dev.pampa.mycloudgallery.core.models.support.TestModels
import dev.pampa.mycloudgallery.core.models.testing.FakeModelFetcher
import dev.pampa.mycloudgallery.core.models.testing.FakeModelNetworkGate
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

/**
 * La revisione B della Fase 6 sul gestore dei modelli (docs/review/6-fase6.md, docs/review/funzionale-6.md): R10 (nessuna eccezione
 * dall'orologio del NAS), R11 (il cancello vale per ogni sorgente), R13 (il parziale di un'altra sorgente non si butta, la colpa va a chi ha
 * scritto, una sorgente che non avanza si ferma), I5 (poche copie, HTTPS dopo il primo errore), MO-2/MO-3/MO-8 (conferma, "Rimuovi" dura,
 * attesa con il motivo), MO-4 (lo scaffale), MO-7 (un solo `refresh` per processo), I1 (sola lettura e `stat`).
 */
class ModelReviewBTest {
  private val long = 10.minutes

  private fun ModelRig.ensure(trigger: ModelTrigger = ModelTrigger.USER_NOW, budget: Duration = long): EnsureResult =
    runBlocking { manager.ensure(spec.id, trigger, budget) }

  /** Toglie il modello verificato come lo toglierebbe una cancellazione dall'esterno (non e' "Rimuovi": nessun segno dell'utente). */
  private fun ModelRig.dropFinal() {
    finalFile.setWritable(true)
    finalFile.delete()
  }

  private fun ModelRig.writePartialNote(source: ModelSource) {
    ModelStore(dir, listOf(spec), { 0L }, ModelCounters()).writePartialSource(spec, PartialWriter.of(source))
  }

  private fun ModelRig.strikesText(): String = File(dir, "meta/strikes").takeIf { it.isFile }?.readText().orEmpty()

  // --- R10: nessuna eccezione dall'orologio del NAS ---------------------------------------------------------------------------

  /** R10: senza l'ora del NAS `strike` lanciava fuori da ogni `try`: la sorgente dopo non si provava e il lavoro cadeva. */
  @Test
  fun r10_senzaOraDelNasUnaCopiaFalsaCostaSoloUnRipiego() {
    val rig = ModelRig()
    rig.clockFails = true
    rig.nasCopy(content = TestModels.flipped(rig.bytes, 9))
    rig.fake.serve(rig.https, rig.bytes)

    assertThat(rig.ensure(ModelTrigger.RULES)).isEqualTo(EnsureResult.Ready)

    assertThat(rig.finalFile.readBytes()).isEqualTo(rig.bytes)
    assertThat(rig.counters.mismatch).isEqualTo(1)
    assertThat(rig.fake.calls).containsExactly(rig.nas, rig.https).inOrder()
  }

  @Test
  fun r10_laQuarantenaSenzaOraDelNasSiRicordaNelProcessoENonSiScriveSulDisco() {
    val rig = ModelRig()
    rig.clockFails = true
    rig.nasCopy(content = TestModels.flipped(rig.bytes, 9))
    rig.fake.serve(rig.https, rig.bytes)
    // "Scarica ora" riprova sempre (nessuna quarantena per l'utente) e a ogni errore la sorgente prende un segno.
    repeat(2) {
      assertThat(rig.ensure(ModelTrigger.USER_NOW)).isEqualTo(EnsureResult.Ready)
      rig.dropFinal()
    }
    rig.fake.calls.clear()
    assertThat(rig.ensure(ModelTrigger.RULES)).isEqualTo(EnsureResult.Ready)
    // Il lavoro automatico salta la copia dei due errori e va dritto a HTTPS.
    assertThat(rig.fake.calls).containsExactly(rig.https)
    assertThat(rig.strikesText()).isEmpty()
  }

  @Test
  fun r10_unGuastoImprevistoEUnFallimentoDiQuestaRichiestaMaiUnaEccezione() {
    val rig = ModelRig(fetcher = ModelFetcher { _, _, _, _ -> error("guasto imprevisto") })
    val first = rig.ensure(ModelTrigger.RULES)
    assertThat(first).isEqualTo(EnsureResult.Failed(ModelFailCode.FETCH_FAILED))
    // Il posto e' stato liberato: la richiesta dopo non trova il modello "in lavorazione".
    val second = rig.ensure(ModelTrigger.RULES)
    assertThat(second).isEqualTo(EnsureResult.Failed(ModelFailCode.FETCH_FAILED))
    assertThat(rig.stateOfModel()).isEqualTo(ModelState.Failed(ModelFailCode.FETCH_FAILED))
  }

  // --- R11 / MO-2 / MO-8: il cancello di rete ---------------------------------------------------------------------------------

  /** R11: il cancello valeva solo per HTTPS; con la VPN accesa il NAS e' raggiungibile anche sui dati mobili e 157-370 MB si leggevano senza conferma. */
  @Test
  fun r11_ilCancelloSiChiedeAncheAlleCopieSulNasEAllaCartellaDalPc() {
    val rig = ModelRig()
    rig.nasCopy()
    rig.fake.serve(rig.pc, rig.bytes)
    rig.gate.verdict = ModelNetworkVerdict.NeedsConfirmation(rig.spec.sizeBytes)

    val result = rig.ensure(ModelTrigger.USER_NOW)

    assertThat(result).isEqualTo(EnsureResult.Failed(ModelFailCode.NETWORK_REFUSED, rig.spec.sizeBytes))
    // Nessun byte letto da nessuna sorgente senza la conferma, e il cancello e' stato chiesto per tutte e tre.
    assertThat(rig.fake.calls).isEmpty()
    assertThat(rig.gate.askedFor).containsAtLeast(ModelSourceKind.NAS, ModelSourceKind.HTTPS, ModelSourceKind.PC_FOLDER)
  }

  @Test
  fun r11_inRoamingNemmenoUnaCopiaDelNasSiLegge() {
    val rig = ModelRig()
    rig.nasCopy()
    rig.gate.verdict = ModelNetworkVerdict.Refused(ModelGateReason.ROAMING)
    val result = rig.ensure(ModelTrigger.USER_NOW)
    assertThat(result).isEqualTo(EnsureResult.Deferred(ModelState.Queued(ModelTrigger.USER_NOW, ModelWait.ROAMING)))
    assertThat(rig.fake.calls).isEmpty()
  }

  @Test
  fun mo2_suiDatiMobiliLaConfermaVieneChiestaConLaDimensioneELaRichiestaRipetutaPassa() {
    val rig = ModelRig()
    rig.fake.serve(rig.https, rig.bytes)
    rig.gate.verdict = ModelNetworkVerdict.NeedsConfirmation(rig.spec.sizeBytes)

    assertThat(rig.ensure(ModelTrigger.USER_NOW)).isEqualTo(EnsureResult.Failed(ModelFailCode.NETWORK_REFUSED, rig.spec.sizeBytes))
    assertThat(rig.stateOfModel()).isEqualTo(ModelState.NeedsConfirmation(rig.spec.sizeBytes))

    // La conferma e' parte della richiesta (USER_CONFIRMED): il cancello la vede, nessuna bandiera da ricordarsi di spegnere.
    rig.gate.verdict = ModelNetworkVerdict.Allowed
    assertThat(rig.ensure(ModelTrigger.USER_CONFIRMED)).isEqualTo(EnsureResult.Ready)
    assertThat(rig.gate.asked).containsAtLeast(ModelTrigger.USER_NOW, ModelTrigger.USER_CONFIRMED)
    assertThat(ModelTrigger.USER_CONFIRMED.isUserRequest).isTrue()
  }

  @Test
  fun mo8_ilCancelloCheNonLasciaScaricareMettePoiInCodaConIlMotivo() {
    val cases = listOf(
      ModelGateReason.METERED to ModelWait.WIFI,
      ModelGateReason.ROAMING to ModelWait.ROAMING,
      ModelGateReason.OFFLINE to ModelWait.NETWORK,
      ModelGateReason.NOT_VALIDATED to ModelWait.NETWORK,
    )
    for ((reason, wait) in cases) {
      val rig = ModelRig()
      rig.fake.serve(rig.https, rig.bytes)
      rig.gate.verdict = ModelNetworkVerdict.Refused(reason)
      val expected = ModelState.Queued(ModelTrigger.RULES, wait)
      assertThat(rig.ensure(ModelTrigger.RULES)).isEqualTo(EnsureResult.Deferred(expected))
      assertThat(rig.stateOfModel()).isEqualTo(expected)
      assertThat(rig.fake.calls).isEmpty()
    }
  }

  // --- MO-3: "Rimuovi" dura ----------------------------------------------------------------------------------------------------

  @Test
  fun mo3_ilGiroPeriodicoNonRiscaricaUnModelloTolto_ScaricaOraSi() {
    val rig = ModelRig()
    rig.fake.serve(rig.https, rig.bytes)
    assertThat(rig.ensure(ModelTrigger.USER_NOW)).isEqualTo(EnsureResult.Ready)
    assertThat(runBlocking { rig.manager.remove(rig.spec.id) }).isTrue()
    rig.fake.calls.clear()

    // Il giro periodico (RULES) lo lascia stare: l'utente l'ha tolto apposta.
    assertThat(rig.ensure(ModelTrigger.RULES)).isInstanceOf(EnsureResult.Deferred::class.java)
    assertThat(rig.fake.calls).isEmpty()
    assertThat(rig.finalFile.exists()).isFalse()

    // "Scarica ora" lo riprende e toglie il segno: il giro dopo non lo considera piu' tolto.
    assertThat(rig.ensure(ModelTrigger.USER_NOW)).isEqualTo(EnsureResult.Ready)
    assertThat(File(rig.dir, "meta").listFiles().orEmpty().map { it.name }.filter { it.endsWith(".removed") }).isEmpty()
  }

  @Test
  fun mo3_ancheIlRuoloIndicizzatoreRiprendeIlModelloTolto() {
    val rig = ModelRig()
    rig.fake.serve(rig.https, rig.bytes)
    rig.ensure(ModelTrigger.USER_NOW)
    runBlocking { rig.manager.remove(rig.spec.id) }
    assertThat(rig.ensure(ModelTrigger.ROLE_CHANGED)).isEqualTo(EnsureResult.Ready)
  }

  // --- R13: il parziale, le sorgenti, chi ha la colpa -------------------------------------------------------------------------

  /** R13: una copia del NAS di un'altra dimensione (tolta fra elenco e lettura) buttava il parziale scritto da HTTPS. */
  @Test
  fun r13_unaSorgenteCheDiceUnaDimensioneDiversaNonButtaIlParzialeDiUnAltra() {
    val lengths = mutableListOf<Long>()
    lateinit var rig: ModelRig
    rig = ModelRig(
      chunk = TestModels.BLOCK,
      fetcher = ModelFetcher { spec, source, partial, budget ->
        lengths += if (partial.isFile) partial.length() else 0L
        rig.fake.fetch(spec, source, partial, budget)
      },
    )
    // Un parziale di due blocchi scritto da HTTPS in un giro precedente...
    val two = TestModels.BLOCK * 2
    rig.installPartial(rig.bytes.copyOf(two))
    rig.writePartialNote(rig.https)
    // ...e adesso il NAS annuncia una copia della dimensione giusta che in realta' e' piu' corta (la lunghezza si legge prima dei byte).
    rig.nasCopy(content = rig.bytes.copyOf(100), size = rig.bytes.size.toLong())
    rig.fake.serve(rig.https, rig.bytes)

    assertThat(rig.ensure(ModelTrigger.RULES)).isEqualTo(EnsureResult.Ready)

    // La copia sbagliata non ha toccato il parziale: HTTPS e' ripartito dai due blocchi, non da zero.
    assertThat(lengths.first()).isEqualTo(two.toLong())
    assertThat(lengths[1]).isAtLeast(two.toLong())
    assertThat(rig.finalFile.readBytes()).isEqualTo(rig.bytes)
    // La colpa e' della copia del NAS (ha mentito sulla dimensione), non di HTTPS.
    assertThat(rig.counters.mismatch).isEqualTo(1)
  }

  /** R13: byte sbagliati letti a meta' dalla cartella dal PC e completati da HTTPS non sono colpa di Hugging Face. */
  @Test
  fun r13_byteSbagliatiDiPiuSorgentiRipartonoDaZeroSenzaDareLaColpaAllUltima() {
    val rig = ModelRig(chunk = TestModels.BLOCK)
    val one = TestModels.BLOCK
    rig.installPartial(TestModels.flipped(rig.bytes.copyOf(one), 5))
    rig.writePartialNote(rig.pc)
    rig.fake.serve(rig.https, rig.bytes)

    // Fette da un blocco: serve piu' di un giro, con un budget largo.
    assertThat(rig.ensure(ModelTrigger.RULES)).isEqualTo(EnsureResult.Ready)

    assertThat(rig.finalFile.readBytes()).isEqualTo(rig.bytes)
    assertThat(rig.counters.mismatch).isEqualTo(1)
    // Nessun segno a HTTPS: il file sbagliato non era suo.
    assertThat(rig.strikesText()).doesNotContain("https:")
  }

  @Test
  fun r13_seDaSolaUnaSorgenteSbagliaLaColpaESuaESiRicordaPerOgniGiro() {
    val rig = ModelRig()
    rig.fake.serve(rig.https, TestModels.flipped(rig.bytes, 11))
    assertThat(rig.ensure(ModelTrigger.RULES)).isEqualTo(EnsureResult.Failed(ModelFailCode.MISMATCH))
    assertThat(rig.ensure(ModelTrigger.RULES)).isEqualTo(EnsureResult.Failed(ModelFailCode.MISMATCH))
    assertThat(rig.strikesText()).contains("https:")
    // Dopo due errori nel giorno il lavoro automatico non riprova HTTPS (la cartella dal PC, che non si blocca mai, si guarda ancora).
    rig.fake.calls.clear()
    rig.ensure(ModelTrigger.RULES)
    assertThat(rig.fake.calls.filterIsInstance<ModelSource.Https>()).isEmpty()
    // "Scarica ora" riprova sempre.
    rig.fake.calls.clear()
    rig.ensure(ModelTrigger.USER_NOW)
    assertThat(rig.fake.calls.filterIsInstance<ModelSource.Https>()).hasSize(1)
  }

  /** R13: un CDN che cambia validatore a ogni richiesta faceva riscaricare gli stessi byte per sempre: due fette senza avanzare e la sorgente si ferma. */
  @Test
  fun r13_unaSorgenteCheNonAvanzaSiFermaDopoDueFette() {
    var calls = 0
    val rig = ModelRig(fetcher = ModelFetcher { _, _, _, _ -> FetchResult.Partial(0).also { calls++ } })
    val result = runBlocking { withTimeout(20_000) { rig.manager.ensure(rig.spec.id, ModelTrigger.RULES, long) } }
    assertThat(result).isInstanceOf(EnsureResult.Deferred::class.java)
    // Una fetta in piu' dell'ultima buona, poi basta: non e' un ciclo senza fine.
    assertThat(calls).isAtMost(6)
  }

  // --- I5: poche copie, HTTPS dopo il primo errore ----------------------------------------------------------------------------

  @Test
  fun i5_dopoIlPrimoErroreSulNasNonSiProvanoLeAltreCopieESiVaSuHttps() {
    val rig = ModelRig()
    val bad = TestModels.flipped(rig.bytes, 3)
    repeat(5) { rig.nasCopy(content = bad, deviceId = TrustIds.random()) }
    rig.fake.serve(rig.https, rig.bytes)

    assertThat(rig.ensure(ModelTrigger.RULES)).isEqualTo(EnsureResult.Ready)

    assertThat(rig.fake.calls.count { it is ModelSource.NasCopy }).isEqualTo(1)
    assertThat(rig.fake.calls.last()).isEqualTo(rig.https)
  }

  // --- MO-4: lo scaffale -----------------------------------------------------------------------------------------------------

  @Test
  fun mo4_ilPrimoDispositivoPubblicaIlModelloEGliAltriNonNeMettonoUnaSecondaCopia() {
    val rig = ModelRig()
    rig.installFinal()
    // Nessuno ha la copia: la pubblica questo dispositivo.
    assertThat(runBlocking { rig.models.shareOnNas() }).isEqualTo(1)
    assertThat(runBlocking { rig.shelf.publish(rig.spec, rig.share) }).isEqualTo(ShelfResult.AlreadyThere)

    // Un altro dispositivo ce l'ha gia': questo non ne mette una sua da 370 MiB.
    val other = ModelRig()
    other.installFinal()
    other.shelf.add(ShelfCopy(other.share, TrustIds.random(), other.spec.sha256, other.spec.sizeBytes))
    assertThat(runBlocking { other.models.shareOnNas() }).isEqualTo(0)
    assertThat(runBlocking { other.shelf.publish(other.spec, other.share) }).isEqualTo(ShelfResult.Published)
  }

  @Test
  fun mo4_sulCancelloCheNonLasciaUsareIlNasNienteSiPubblica() {
    for (verdict in listOf(ModelNetworkVerdict.Refused(ModelGateReason.ROAMING), ModelNetworkVerdict.NeedsConfirmation(1))) {
      val rig = ModelRig()
      rig.installFinal()
      rig.gate.verdict = verdict
      assertThat(runBlocking { rig.models.shareOnNas() }).isEqualTo(0)
      assertThat(runBlocking { rig.shelf.publish(rig.spec, rig.share) }).isEqualTo(ShelfResult.Published)
    }
  }

  @Test
  fun mo4_senzaUnaShareComuneNonSiFaNiente() {
    val rig = ModelRig()
    rig.installFinal()
    rig.area = null
    assertThat(runBlocking { rig.models.shareOnNas() }).isEqualTo(0)
  }

  // --- MO-7: un solo refresh per processo -------------------------------------------------------------------------------------

  /** MO-7: il lavoro dei modelli rileggeva per intero circa 1,1 GB a ogni giro (`refresh`). */
  @Test
  fun mo7_refreshOnceVerificaUnaVoltaSola() {
    val rig = ModelRig()
    rig.installFinal()
    runBlocking { rig.models.refreshOnce() }
    assertThat(rig.stateOfModel()).isInstanceOf(ModelState.Ready::class.java)
    // Il file cambia di nascosto: un secondo `refreshOnce` non lo rilegge...
    rig.finalFile.setWritable(true)
    rig.finalFile.writeBytes(TestModels.flipped(rig.bytes, 7))
    runBlocking { rig.models.refreshOnce() }
    assertThat(rig.stateOfModel()).isInstanceOf(ModelState.Ready::class.java)
    // ...mentre un `refresh` vero (e ogni apertura) lo scopre.
    runBlocking { rig.models.refresh() }
    assertThat(rig.stateOfModel()).isNotInstanceOf(ModelState.Ready::class.java)
  }

  // --- I1: lo stat prima e dopo --------------------------------------------------------------------------------------------------

  /** I1: una scrittura sullo stesso inode non cambia ne' inode ne' dimensione: la `ctime` la scopre. */
  @Test
  fun i1_loStatPrendeAccortoUnaScritturaSulloStessoInode() {
    val verified = FileStat(device = 5, inode = 77, size = 1_000, ctimeSeconds = 1_700_000_000)
    assertThat(verified.isSameFileAs(verified.copy())).isTrue()
    assertThat(verified.isSameFileAs(verified.copy(ctimeSeconds = 1_700_000_001))).isFalse()
    assertThat(verified.isSameFileAs(verified.copy(inode = 78))).isFalse()
    assertThat(verified.isSameFileAs(verified.copy(device = 6))).isFalse()
    assertThat(verified.isSameFileAs(verified.copy(size = 999))).isFalse()
  }

  // --- I1: sola lettura e stat del file verificato ----------------------------------------------------------------------------

  @Test
  fun i1_ilModelloVerificatoEInSolaLetturaEPortaLoStatPresoDuranteLaVerifica() = runBlocking<Unit> {
    val bytes = TestModels.bytes(200_000, seed = 4)
    val spec = TestModels.spec(bytes)
    val dir = TestModels.tempDir()
    val fake = FakeModelFetcher()
    fake.serve(ModelSource.Https(spec.https.single()), bytes)
    val expected = FileStat(device = 1, inode = 2, size = bytes.size.toLong(), ctimeSeconds = 4)
    val models = LocalModels(
      root = dir,
      fetcher = fake,
      networkGate = FakeModelNetworkGate(),
      catalog = listOf(spec),
      freeSpace = { Long.MAX_VALUE },
      progressPoll = Duration.ZERO,
      statReader = FileStatReader { expected },
    )
    assertThat(models.manager.ensure(spec.id, ModelTrigger.USER_NOW, 1.minutes)).isEqualTo(EnsureResult.Ready)
    val file = File(dir, spec.sha256.hex)
    assertThat(file.canWrite()).isFalse()
    models.files.open(spec.id)!!.use { assertThat(it.stat).isEqualTo(expected) }

    // Un modello lasciato scrivibile da una versione precedente si rimette in sola lettura alla prima apertura.
    file.setWritable(true)
    assertThat(file.canWrite()).isTrue()
    models.files.open(spec.id)!!.close()
    assertThat(file.canWrite()).isFalse()
  }
}
