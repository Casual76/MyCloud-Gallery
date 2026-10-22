package dev.pampa.mycloudgallery.core.ai.chaos

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * I difetti del codice di produzione che il banco ha trovato e che P10 non corregge (il codice di produzione non e' nel suo perimetro:
 * docs/prova-500.md, sezione della Fase 6, e il REPORT del pacchetto). Ognuno ha la sua riproduzione minima e deterministica. Il banco
 * grande li conta a parte ([KnownDefect]) e non diventa rosso; **questo test diventa rosso quando il difetto e' corretto**: a quel punto
 * si toglie l'etichetta `known` dal verificatore e il banco lo tratta come un errore vero.
 */
class AiChaosKnownDefectsTest {

  /**
   * AI-D1: il buffer tiene le anteprime di un media finito se il processo muore fra il suo salvataggio e lo svuotamento di fine tappa.
   * Due media del solo telefono; il processo muore subito dopo il primo salvataggio (riga scritta); il processo nuovo calcola il secondo
   * e finisce, ma il primo non e' piu' un candidato e nessuno svuota la sua anteprima: resta nel buffer (preparata dai byte del telefono).
   */
  @Test
  fun aiD1_laMorteLasciaLeAnteprimeNelBuffer() {
    val plan = AiRoundPlan.quiet(phoneMedia = 2, deaths = mapOf(1 to SavePoint.AFTER_ROW))
    val report = AiChaosRound.run(plan)
    assertWithMessage("nessun errore vero atteso: ${report.describe()}").that(report.failures).isEmpty()
    assertWithMessage("AI-D1 non si riproduce piu': e' stato corretto? Togli l'etichetta dal verificatore.\n${report.describe()}")
      .that(report.knownDefects).contains(KnownDefect.BUFFER_ORPHAN_BLOBS)
  }

  /** Senza la morte il buffer si svuota: il difetto e' della morte, non del calcolo. */
  @Test
  fun senzaMorteIlBufferSiSvuota() {
    val report = AiChaosRound.run(AiRoundPlan.quiet(phoneMedia = 2, commonMedia = 4))
    assertWithMessage(report.describe()).that(report.violations).isEmpty()
  }
}
