package dev.pampa.mycloudgallery.core.ai.chaos

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * I difetti del codice di produzione che il banco ha trovato (docs/prova-500.md, sezione della Fase 6, e il REPORT di P10), con la loro
 * riproduzione minima e deterministica. Finche' un difetto non e' corretto il banco grande lo conta a parte ([KnownDefect]); corretto, si
 * toglie l'etichetta dal verificatore e la riproduzione resta qui come prova della correzione.
 *
 * AI-D1 e' corretto da P11 (`ComputeSession.pruneFinished`: a inizio tappa si tolgono dal buffer i blob dei media che `AiResults.saved`
 * dice finiti): il verificatore lo tratta come un errore vero.
 */
class AiChaosKnownDefectsTest {

  /**
   * AI-D1, corretto: due media del solo telefono; il processo muore subito dopo il primo salvataggio (riga scritta); il processo nuovo
   * calcola il secondo e a inizio tappa toglie l'anteprima del primo, gia' salvato. A lavoro finito il buffer e' vuoto.
   */
  @Test
  fun aiD1_laMorteNonLasciaPiuLeAnteprimeNelBuffer() {
    val plan = AiRoundPlan.quiet(phoneMedia = 2, deaths = mapOf(1 to SavePoint.AFTER_ROW))
    val report = AiChaosRound.run(plan)
    assertWithMessage("nessun errore atteso: ${report.describe()}").that(report.violations).isEmpty()
    assertWithMessage(report.describe()).that(report.knownDefects).isEmpty()
  }

  /** La stessa morte con anche i media di una share: le unita' riprese non lasciano anteprime gia' calcolate. */
  @Test
  fun aiD1_anchePerLeShare() {
    val plan = AiRoundPlan.quiet(phoneMedia = 2, commonMedia = 4, deaths = mapOf(1 to SavePoint.AFTER_ROW, 3 to SavePoint.AFTER_ROW))
    val report = AiChaosRound.run(plan)
    assertWithMessage(report.describe()).that(report.violations).isEmpty()
  }

  /** Senza la morte il buffer si svuota. */
  @Test
  fun senzaMorteIlBufferSiSvuota() {
    val report = AiChaosRound.run(AiRoundPlan.quiet(phoneMedia = 2, commonMedia = 4))
    assertWithMessage(report.describe()).that(report.violations).isEmpty()
  }
}
