package dev.pampa.mycloudgallery.core.ai.chaos

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Il banco ha i denti (docs/fase6-progetto.md §5.5, riga P10): se si rompe di proposito l'ordine "salvare, poi pubblicare" del calcolo, in
 * poche centinaia di giri il banco deve diventare rosso, e con l'invariante giusto (il 5: "ogni voce pubblicata ha la sua riga locale").
 * Senza questa prova un banco verde non vale niente. I due difetti dello scaricamento (caricare prima di verificare, accettare un parziale)
 * sono nel banco di core-models. Con gli stessi semi il calcolo sano deve restare verde: cosi' il rosso viene dal difetto e non dal banco.
 */
class AiChaosMutationTest {
  private fun run(factory: AiStackFactory, rounds: Int = 400, name: String = "sano", stop: Boolean = false): AiBenchResult =
    AiChaosBench.run(rounds, baseSeed = 4242L, factory = factory, stackName = name, stopAtFirstFailure = stop)

  private fun assertDetects(result: AiBenchResult, expected: AiInvariant) {
    assertWithMessage("il banco non ha visto il difetto in ${result.rounds} giri\n${result.summary()}").that(result.failures).isNotEmpty()
    println("difetto visto al giro ${result.rounds} di 400: ${result.stackName}")
    val seen = result.failures.flatMap { it.failures }.map { it.invariant }.toSet()
    assertWithMessage("invarianti violati: $seen, atteso $expected\n${result.failureText(2)}").that(seen).contains(expected)
  }

  @Test
  fun ilCalcoloSanoConGliStessiSemiRestaVerde() {
    val clean = run(AiStackFactory.REAL, rounds = 200)
    assertWithMessage(clean.summary() + clean.failureText()).that(clean.ok).isTrue()
  }

  @Test
  fun pubblicarePrimaDiSalvareViolaLInvariante5() {
    val mutant = AiMutants.publishBeforeSave()
    assertDetects(run(mutant, name = mutant.toString(), stop = true), AiInvariant.COHERENT_DATA)
  }

  @Test
  fun salvareDopoAverPubblicatoViolaLInvariante5() {
    val mutant = AiMutants.saveAfterPublish()
    assertDetects(run(mutant, name = mutant.toString(), stop = true), AiInvariant.COHERENT_DATA)
  }
}
