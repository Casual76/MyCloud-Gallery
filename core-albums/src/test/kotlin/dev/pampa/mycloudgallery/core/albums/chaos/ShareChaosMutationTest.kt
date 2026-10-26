package dev.pampa.mycloudgallery.core.albums.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Il banco ha i denti (docs/fase7-progetto.md §5.5, riga P5 del §4.4): se si rompe di proposito lo stack della condivisione, in poche centinaia di giri
 * il banco deve diventare rosso, **con l'invariante giusto**. Senza questa prova un banco verde non vale niente.
 *
 * I quattro errori della riga P5, ognuno uno stack rotto che avvolge le porte dello stack sano (come le Fasi 4, 5 e 6) e ne riproduce l'effetto:
 *  - la sorgente nel cestino prima della verifica → **3, nessuna perdita** (il file e' solo nel cestino, senza una copia completa altrove);
 *  - la voce `item:` pubblicata prima del completamento → **6, nessuna voce senza file**;
 *  - il completamento dopo il cestino → **3** (il catalogo conosce la destinazione dopo che la sorgente e' andata nel cestino) e **5** (il catalogo punta a
 *    un file che non c'e');
 *  - la conferma riusata → **7, nessuna azione senza conferma** (di un altro piano, o di un processo morto).
 * Lo stack sano sugli stessi giri resta **verde**: un rosso e' colpa dell'errore, non del banco.
 */
class ShareChaosMutationTest {

  private fun run(hooks: ShareChaosHooks, rounds: Int): ShareChaosResult = runBlocking {
    ShareChaosBench.run(rounds, baseSeed = SEED, factory = FakeChaosTargets, hooks = hooks)
  }

  private fun assertDetects(label: String, result: ShareChaosResult, expected: Set<ShareInvariant>) {
    println("$label: ${result.failures.size} giri rossi su ${result.rounds}; invarianti ${result.violatedInvariants().entries.joinToString { "${it.key.number} x${it.value}" }}")
    assertWithMessage("il banco non ha visto l'errore in ${result.rounds} giri\n${result.summary()}").that(result.failures).isNotEmpty()
    val seen = result.violatedInvariants().keys
    assertWithMessage("invarianti violati: $seen, attesi almeno uno fra $expected").that(seen.intersect(expected)).isNotEmpty()
  }

  @Test
  fun loStackSanoSugliStessiGiriRestaVerde() {
    val result = run(ShareChaosHooks(), ROUNDS)
    println("stack sano: ${result.failures.size} giri rossi su ${result.rounds}")
    assertWithMessage(result.failureText()).that(result.failures).isEmpty()
  }

  @Test
  fun laSorgenteNelCestinoPrimaDellaVerificaEUnaPerdita() {
    assertDetects("sorgente nel cestino prima della verifica", run(ShareChaosMutants.trashBeforeVerify(), ROUNDS), setOf(ShareInvariant.NO_LOSS))
  }

  @Test
  fun laVocePubblicataPrimaDelCompletamentoEUnaVoceSenzaFile() {
    assertDetects("voce pubblicata prima del completamento", run(ShareChaosMutants.entryBeforeCompletion(), ROUNDS), setOf(ShareInvariant.NO_ENTRY_WITHOUT_FILE))
  }

  @Test
  fun ilCompletamentoDopoIlCestinoLasciaIlCatalogoSenzaFile() {
    assertDetects(
      "completamento dopo il cestino",
      run(ShareChaosMutants.completionAfterTrash(), ROUNDS),
      setOf(ShareInvariant.NO_LOSS, ShareInvariant.NOTHING_MISSING),
    )
  }

  @Test
  fun laConfermaRiusataEUnAzioneSenzaConferma() {
    assertDetects("conferma riusata", run(ShareChaosMutants.reusedConfirmation(), ROUNDS), setOf(ShareInvariant.NO_ACTION_WITHOUT_CONFIRMATION))
  }

  private companion object {
    const val ROUNDS = 300
    const val SEED = 4242L
  }
}
