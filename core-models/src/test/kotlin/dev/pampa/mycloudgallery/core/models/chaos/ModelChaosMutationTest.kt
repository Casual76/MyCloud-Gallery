package dev.pampa.mycloudgallery.core.models.chaos

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Il banco ha i denti (docs/fase6-progetto.md §5.5, riga P10): se si rompe di proposito il gestore dei modelli, in poche centinaia di giri
 * il banco deve diventare rosso, e con l'invariante giusto. Senza questa prova un banco verde non vale niente. Qui i due difetti dello
 * scaricamento; gli altri due (pubblicare prima di salvare, salvare dopo aver pubblicato) sono nel banco di core-ai.
 *
 * Con gli stessi semi lo stack sano deve restare verde: cosi' il rosso viene dal difetto e non dal banco.
 */
class ModelChaosMutationTest {
  private fun run(factory: ModelStackFactory, rounds: Int = 400, name: String = "sano", stop: Boolean = false): ModelBenchResult =
    ModelChaosBench.run(rounds, baseSeed = 4242L, backend = ModelBackend.MEMORY, factory = factory, stackName = name, stopAtFirstFailure = stop)

  private fun assertDetects(result: ModelBenchResult, expected: ModelInvariant) {
    assertWithMessage("il banco non ha visto il difetto in ${result.rounds} giri\n${result.summary()}").that(result.failures).isNotEmpty()
    println("difetto visto al giro ${result.rounds} di 400: ${result.stackName}")
    val seen = result.failures.flatMap { it.violations }.map { it.invariant }.toSet()
    assertWithMessage("invarianti violati: $seen, atteso $expected\n${result.failureText(2)}").that(seen).contains(expected)
  }

  @Test
  fun loStackSanoConGliStessiSemiRestaVerde() {
    val clean = run(ModelStackFactory.REAL, rounds = 150)
    assertWithMessage(clean.summary() + clean.failureText()).that(clean.ok).isTrue()
  }

  @Test
  fun caricarePrimaDiVerificareViolaLInvariante1() {
    val mutant = ModelMutants.loadBeforeVerify()
    assertDetects(run(mutant, name = mutant.toString(), stop = true), ModelInvariant.VERIFIED_LOAD)
  }

  @Test
  fun accettareUnParzialeViolaLInvariante2() {
    val mutant = ModelMutants.acceptPartial()
    assertDetects(run(mutant, name = mutant.toString(), stop = true), ModelInvariant.NO_PARTIAL)
  }
}
