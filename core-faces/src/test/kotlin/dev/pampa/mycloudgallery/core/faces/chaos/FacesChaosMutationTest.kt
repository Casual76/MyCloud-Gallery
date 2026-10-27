package dev.pampa.mycloudgallery.core.faces.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * IL BANCO HA I DENTI (docs/prova-500.md Parte F, §F7; riga P12 del §4.4): quattro errori introdotti apposta, ognuno una promessa di sicurezza della Fase 8,
 * devono far diventare rosso il banco **con l'invariante giusto**; lo stack sano sugli stessi giri resta verde (un rosso e' colpa dell'errore, non del banco).
 *
 * | errore | dove | invariante |
 * |---|---|---|
 * | pubblicare senza lo stato fresco | pubblicatore, replica e prenotazioni non guardano la freschezza (D30) | 2, nessuna pubblicazione senza lo stato fresco |
 * | dichiarare la cancellazione prima di farla | il gancio prima di pubblicare i dati personali non cancella (D30) | 4, nessuna testa "non piu' G" con i dati di G ancora li' |
 * | togliere i file prima della chiave | la cancellazione locale (§2.8 passo 6) | 4, la chiave per prima |
 * | cancellare fuori da `faces/` | la cancellazione sul NAS (D31) | 5, mai fuori da `faces/`, nessun media toccato |
 */
class FacesChaosMutationTest {

  private fun run(mutant: Mutant?, rounds: Int = ROUNDS): ChaosResult = runBlocking {
    ChaosBench.run(rounds, SEED, MemoryTarget, ChaosConfig(MemoryTarget.capabilities, mutant = mutant))
  }

  private fun expectRed(mutant: Mutant) {
    val result = run(mutant)
    val seen = result.violatedInvariants()
    println("${mutant.label}: ${result.failures.size} giri rossi su ${result.rounds}, invarianti $seen")
    assertWithMessage("${mutant.label}: il banco non e' diventato rosso").that(result.failures).isNotEmpty()
    assertWithMessage("${mutant.label}: l'invariante giusto (${mutant.expected.number}) non e' fra quelli violati: $seen").that(seen.keys).contains(mutant.expected)
  }

  @Test
  fun lo_stackSanoSuGliStessiGiriResta_verde() {
    val result = run(null)
    assertWithMessage(result.failureText()).that(result.failures).isEmpty()
    assertThat(result.stats.totalFired).isGreaterThan(0)
  }

  @Test
  fun pubblicareSenzaLoStatoFresco_ROSSO() = expectRed(Mutant.PUBLISH_WITHOUT_FRESH_STATE)

  @Test
  fun dichiararLaCancellazionePrimaDiFarla_ROSSO() = expectRed(Mutant.ANNOUNCE_BEFORE_ERASING)

  @Test
  fun togliereIFilePrimaDellaChiave_ROSSO() = expectRed(Mutant.FILES_BEFORE_KEY)

  @Test
  fun cancellareFuoriDaFaces_ROSSO() = expectRed(Mutant.DELETE_OUTSIDE_FACES)

  private companion object {
    const val ROUNDS = 200
    const val SEED = 4242L
  }
}
