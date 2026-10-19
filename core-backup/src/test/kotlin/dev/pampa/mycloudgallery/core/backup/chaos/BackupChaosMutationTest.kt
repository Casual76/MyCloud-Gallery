package dev.pampa.mycloudgallery.core.backup.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Il banco ha i denti (docs/fase5-progetto.md riga P6 del §4.4): se si rompe di proposito il motore, in poche centinaia di giri il banco
 * deve diventare rosso, e con l'invariante giusto. Senza questa prova un banco verde non vale niente.
 *
 * Quattro errori, ognuno scritto come uno stack rotto (BackupChaosMutants.kt) e provato sia sull'esecutore di riferimento sia sul motore vero:
 *  - pubblicare prima della verifica → un file visibile con byte che non sono di nessun media (1);
 *  - saltare la rilettura → un file corrotto "al sicuro", senza la copia con i byte della versione (1, 4);
 *  - scrivere il diario dopo la chiamata → un temporaneo che il diario non cita, dopo una morte nel mezzo (6);
 *  - il temporaneo tolto dalla pulizia → un temporaneo citato dal diario che sparisce (6).
 * E il controllo: gli stessi giri sullo stack sano restano verdi (un rosso e' colpa del difetto, non del banco).
 */
class BackupChaosMutationTest {

  private val stacks = listOf(ReferenceStack, ProductionStack)

  private fun run(factory: BackupChaosStackFactory, rounds: Int): BenchResult = runBlocking {
    BackupChaosBench.run(rounds, baseSeed = SEED, target = FakeNasTarget(), factory = factory)
  }

  private fun seen(result: BenchResult): Set<Invariant> = result.failures.flatMap { it.violations }.map { it.invariant }.toSet()

  private fun assertDetects(label: String, result: BenchResult, expected: Set<Invariant>) {
    assertWithMessage("$label: il banco non ha visto il difetto in ${result.rounds} giri\n${result.summary()}").that(result.failures).isNotEmpty()
    val invariants = seen(result)
    assertWithMessage("$label: invarianti violati $invariants, attesi almeno uno fra $expected\n${result.failureText(2)}")
      .that(invariants.intersect(expected)).isNotEmpty()
    println("$label: ${result.failures.size} giri rossi su ${result.rounds}, invarianti ${invariants.map { it.number }.sorted()}")
  }

  @Test
  fun lo_stackSanoNonFaScattareNiente() {
    for (stack in stacks) {
      val result = run(stack, ROUNDS)
      assertWithMessage("${stack.name}: controllo\n${result.failureText()}").that(result.failures).isEmpty()
    }
  }

  @Test
  fun pubblicarePrimaDellaVerificaLasciaUnFileVisibileConByteSbagliati() {
    for (stack in stacks) {
      assertDetects(stack.name + " / pubblica prima", run(Mutants.publishBeforeVerify(stack), ROUNDS), setOf(Invariant.HALF_FILE, Invariant.DUPLICATE))
    }
  }

  @Test
  fun saltareLaRilletturaPubblicaEColegaUnaCopiaCorrotta() {
    for (stack in stacks) {
      assertDetects(stack.name + " / salta la rilettura", run(Mutants.skipReread(stack), ROUNDS), setOf(Invariant.HALF_FILE, Invariant.LOSS))
    }
  }

  @Test
  fun scrivereIlDiarioDopoLaChiamataLasciaUnTemporaneoNonCitato() {
    for (stack in stacks) {
      val result = run(Mutants.diaryAfterCall(stack), ROUNDS)
      assertDetects(stack.name + " / diario dopo la chiamata", result, setOf(Invariant.TEMP))
      assertThat(seen(result)).contains(Invariant.TEMP)
    }
  }

  @Test
  fun laPuliziaCheTogliIlTemporaneoCitatoDalDiarioEUnaViolazione() {
    for (stack in stacks) {
      val result = run(Mutants.cleanupRemovesTemp(stack), ROUNDS)
      assertDetects(stack.name + " / la pulizia toglie il temporaneo", result, setOf(Invariant.TEMP))
      assertThat(result.failures.flatMap { it.violations }.any { "citato dal diario" in it.detail }).isTrue()
    }
  }

  // --- La 5B (P13): versioni, scambi, "Libera spazio" -------------------------------------------------------------------------------

  private fun runFiveB(stack: FiveBStackFactory, rounds: Int = ROUNDS_5B, target: () -> BackupChaosTarget = { FakeNasTarget() }): BenchResult = runBlocking {
    // Il caricamento (la 5A) e' quello sano: il difetto e' solo nei servizi della 5B.
    BackupChaosBench.run(
      rounds, baseSeed = SEED, target = target(), factory = ProductionStack,
      config = BackupChaosConfig(fiveB = Backup5BConfig(stack = stack)),
    )
  }

  @Test
  fun lo_stackSanoDellaCinqueBNonFaScattareNiente() {
    val result = runFiveB(RealFiveBStack)
    assertWithMessage("controllo 5B: " + result.failureText())
      .that(result.failures).isEmpty()
  }

  @Test
  fun archiviareSenzaTimbroSpostaUnFileCheNonEraQuelloDelCatalogo() {
    val result = runFiveB(FiveBMutants.archiveWithoutStamp(RealFiveBStack))
    // Il PC cambia il file attuale proprio prima della rinomina: senza il timbro l'archivio prende i byte del PC e il catalogo crede che siano
    // quelli della versione (7, e la versione di prima non e' piu' in Versioni/ con i suoi byte: 13).
    assertDetects("5B archivia senza timbro", result, setOf(Invariant.CATALOG, Invariant.VERSIONS_KEPT))
  }

  @Test
  fun sostituireAlPostoDiRinominareCancellaUnFileCheCEra() {
    val result = runFiveB(FiveBMutants.replaceInsteadOfRename(RealFiveBStack))
    assertDetects("5B sostituisce", result, setOf(Invariant.REPLACED))
  }

  @Test
  fun liberareConUnaVerificaScadutaConsegnaUnLottoAlDialogo() {
    val result = runFiveB(FiveBMutants.releaseWithExpiredVerification(RealFiveBStack))
    assertDetects("5B verifica scaduta", result, setOf(Invariant.FREE_SPACE))
    assertThat(result.failures.flatMap { it.violations }.any { "verifica scaduta" in it.detail }).isTrue()
  }

  @Test
  fun unoScambioCheLasciaVuotoIlSolitoPercorsoEUnaViolazione() {
    val result = runFiveB(FiveBMutants.swapLeavesUsualPathEmpty(RealFiveBStack))
    assertDetects("5B scambio che lascia vuoto il solito percorso", result, setOf(Invariant.USUAL_PATH))
  }

  private companion object {
    const val SEED = 4242L
    const val ROUNDS = 500
    const val ROUNDS_5B = 500
  }
}
