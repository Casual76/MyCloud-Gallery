package dev.pampa.mycloudgallery.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingConsent
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingState
import dev.pampa.mycloudgallery.core.personal.contract.TextReadingChoice
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Revisione A, LT-3 e I7 (D41): la versione del testo accettata ha effetto. Con un testo della pagina piu' nuovo di quello accettato la
 * lettura resta "attiva per l'account" (la pagina dice che il testo e' cambiato) ma **non qui**: motore, lettura al volo e ML Kit fermi,
 * anche all'avvio del processo. Si riaccende solo riaccettando il testo nuovo.
 */
class TextReadingVersionTest {

  @get:Rule val tmp = TemporaryFolder()

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

  @After
  fun tearDown() {
    scope.cancel()
  }

  @Test
  fun lt3_unaVersioneAccettataPiuVecchiaNonAccendeLaLetturaQui() {
    assertThat(TextReadingMemory.stateOf(TextReadingChoice.OnForAccount(1), excluded = false, current = 2).activeHere).isFalse()
    assertThat(TextReadingMemory.stateOf(TextReadingChoice.OnForAccount(1), excluded = false, current = 2))
      .isEqualTo(TextReadingState.OnForAccount(thisDevice = false))
    assertThat(TextReadingMemory.stateOf(TextReadingChoice.OnForAccount(2), excluded = false, current = 2).activeHere).isTrue()
    assertThat(TextReadingMemory.stateOf(TextReadingChoice.OnForAccount(3), excluded = false, current = 2).activeHere).isTrue()
    assertThat(TextReadingMemory.stateOf(TextReadingChoice.OnForAccount(2), excluded = true, current = 2).activeHere).isFalse()
    assertThat(TextReadingMemory.stateOf(TextReadingChoice.Off, excluded = false, current = 2)).isEqualTo(TextReadingState.Off)
  }

  @Test
  fun i7_ancheAllAvvioDelProcessoLaMemoriaConUnaVersioneVecchiaDiceSpentaQui() {
    val file = File(tmp.root, TextReadingMemory.FILE)
    TextReadingMemory(file, current = 1).save(TextReadingChoice.OnForAccount(1), excluded = false)
    assertThat(TextReadingMemory(file, current = 1).state().activeHere).isTrue()
    // Un aggiornamento dell'app porta il testo 2: chi ha accettato l'1 non legge piu' (TextReadingStartup usa questo stato).
    assertThat(TextReadingMemory(file, current = 2).state().activeHere).isFalse()
  }

  @Test
  fun lt3_ilCommutatoreSiAccendeSoloRiaccettandoIlTestoNuovo() = runBlocking {
    val choice = MutableStateFlow<TextReadingChoice?>(TextReadingChoice.OnForAccount(1))
    val memory = TextReadingMemory(File(tmp.root, TextReadingMemory.FILE), current = 2)
    val switch = AppTextReadingSwitch(
      scope = scope,
      choice = choice,
      setChoice = { choice.value = it; true },
      memory = memory,
      shutdown = { error("nessuno spegnimento atteso: la lettura non era attiva qui") },
    )
    assertThat(switch.state.value.activeHere).isFalse()
    switch.includeThisDevice()
    assertThat(switch.state.value.activeHere).isFalse()

    switch.enableForAccount(TextReadingConsent(textVersion = 2, shownAtMonotonicNanos = 0L))
    withTimeout(5_000) { switch.state.first { it.activeHere } }
    assertThat(choice.value).isEqualTo(TextReadingChoice.OnForAccount(2))
  }
}
