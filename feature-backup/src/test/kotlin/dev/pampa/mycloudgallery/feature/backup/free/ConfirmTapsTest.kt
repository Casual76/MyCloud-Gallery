package dev.pampa.mycloudgallery.feature.backup.free

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationRules
import java.io.File
import org.junit.Test

/**
 * I tocchi di conferma (D21, R6 della 4B; contratto `PhoneConfirmation` di P0-B): una conferma nasce **solo** da un tocco dell'utente su un
 * pulsante di un foglio. "Libera spazio" ne vuole uno; "Libera subito" **due, distinti**: un tocco arrivato meno di 600 ms dopo il precedente (o
 * dopo la comparsa del foglio) non conta. In piu', un controllo sui sorgenti: nessuna riga di questo modulo chiede la conferma fuori da un `onClick`.
 */
class ConfirmTapsTest {

  private var now = 10_000L

  private fun taps(permanent: Boolean): ConfirmTaps = ConfirmTaps(FreeSpaceSamples.plan(items = 3, permanent = permanent), clock = { now })

  @Test
  fun unPianoNormaleSiConfermaConUnTocco() {
    val taps = taps(permanent = false)
    assertThat(taps.required).isEqualTo(1)
    now += 50
    val confirmation = taps.tap()
    assertThat(confirmation).isNotNull()
    assertThat(confirmation!!.confirmedSteps).isEqualTo(1)
  }

  @Test
  fun liberaSubitoVuoleDueGestiDistinti() {
    val taps = taps(permanent = true)
    assertThat(taps.required).isEqualTo(2)

    // Un tocco troppo vicino alla comparsa del foglio non conta (R6).
    now += 100
    assertThat(taps.tap()).isNull()
    assertThat(taps.taps).isEqualTo(0)

    // Il primo gesto vero: il foglio cambia pannello, ancora nessuna conferma.
    now += PhoneConfirmationRules.MIN_TAP_GAP_MS
    assertThat(taps.tap()).isNull()
    assertThat(taps.taps).isEqualTo(1)

    // Un doppio tocco subito dopo non vale come secondo gesto.
    now += 80
    assertThat(taps.tap()).isNull()
    assertThat(taps.taps).isEqualTo(1)

    // Il secondo gesto, dopo aver letto: la conferma c'e' ed e' a due tocchi.
    now += PhoneConfirmationRules.MIN_TAP_GAP_MS
    val confirmation = taps.tap()
    assertThat(confirmation).isNotNull()
    assertThat(confirmation!!.confirmedSteps).isEqualTo(2)
  }

  @Test
  fun rinunciareRicominciaDaCapo_NessunaConfermaRestaInGiro() {
    val taps = taps(permanent = true)
    now += PhoneConfirmationRules.MIN_TAP_GAP_MS
    taps.tap()
    assertThat(taps.taps).isEqualTo(1)
    taps.reset()
    assertThat(taps.taps).isEqualTo(0)
    // Dopo la rinuncia si ricomincia dal primo gesto: un solo tocco non basta per "Libera subito".
    now += PhoneConfirmationRules.MIN_TAP_GAP_MS
    assertThat(taps.tap()).isNull()
    assertThat(taps.taps).isEqualTo(1)
  }

  @Test
  fun laConfermaVale_PerIlPianoCheHaFirmato() {
    val plan = FreeSpaceSamples.plan(items = 3)
    val taps = ConfirmTaps(plan, clock = { now })
    now += 10
    val confirmation = taps.tap()!!
    assertThat(confirmation.planHash).isEqualTo(plan.hash)
    // Un piano con un elemento in piu' ha un'altra impronta: la stessa conferma non gli appartiene.
    val bigger = FreeSpaceSamples.plan(items = 4)
    assertThat(bigger.hash).isNotEqualTo(confirmation.planHash)
  }

  @Test
  fun nessunaRigaChiedeLaConfermaFuoriDaUnPulsante() {
    val sources = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    assertThat(sources).isNotEmpty()
    val taps = Regex("""\.tap\(\)""")
    val misplaced = sources.filter { it.name != "ConfirmTaps.kt" }.flatMap { file ->
      file.readLines().mapIndexedNotNull { index, line ->
        val code = line.substringBefore("//")
        // Un tocco di conferma sta dentro il gestore di un pulsante, sulla stessa riga: `onClick = { taps?.tap()... }`.
        if (taps.containsMatchIn(code) && "onClick" !in code) "${file.name}:${index + 1}: ${line.trim()}" else null
      }
    }
    assertWithMessage("Tocchi di conferma fuori da un onClick:\n" + misplaced.joinToString("\n")).that(misplaced).isEmpty()
  }

  @Test
  fun ilFoglioDiConfermaEDiAltriNascePerNessunAltroPostoChePerConfirmTaps() {
    val sources = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    // I nomi sono scritti in due pezzi: questo file non li contiene mai interi (come Phase5BArchitectureTest).
    val forbidden = Regex(
      """\bPhone""" + """Confirmation""" + """Issuer\b|\bPhone""" + """Confirmation""" + """Sheet\s*\(|\bPhone""" + """Confirmation\s*\(""",
    )
    val found = sources.filter { it.name != "ConfirmTaps.kt" }.flatMap { file ->
      file.readLines().mapIndexedNotNull { index, line ->
        val code = line.substringBefore("//")
        if (forbidden.containsMatchIn(code)) "${file.name}:${index + 1}: ${line.trim()}" else null
      }
    }
    assertWithMessage("La conferma del telefono nasce solo in ConfirmTaps:\n" + found.joinToString("\n")).that(found).isEmpty()
  }
}
