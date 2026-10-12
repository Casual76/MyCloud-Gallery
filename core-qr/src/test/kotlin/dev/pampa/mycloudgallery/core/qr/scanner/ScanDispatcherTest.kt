package dev.pampa.mycloudgallery.core.qr.scanner

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.qr.QrLimits
import dev.pampa.mycloudgallery.core.qr.codec.QrRead
import dev.pampa.mycloudgallery.core.qr.contract.ScanRejection
import dev.pampa.mycloudgallery.core.qr.contract.ScanVerdict
import org.junit.Test

/** Il vigile fra i fotogrammi e chi decide: tetto, ripetizioni, verdetti. Tempo finto, nessuna fotocamera. */
class ScanDispatcherTest {

  private var now = 10_000L
  private val seen = mutableListOf<String>()
  private var verdict: (String) -> ScanVerdict = { ScanVerdict.Accept }

  private val dispatcher = ScanDispatcher(
    accept = { text ->
      seen += text
      verdict(text)
    },
    nowMs = { now },
  )

  private fun found(text: String) = QrRead.Found(text)

  @Test
  fun nessunQr_nonFaNiente() {
    assertThat(dispatcher.onRead(QrRead.None)).isEqualTo(ScanOutcome.Nothing)
    assertThat(seen).isEmpty()
  }

  @Test
  fun accettato_losScannerSiFerma_eNonConsegnaPiuNiente() {
    assertThat(dispatcher.onRead(found("MCG1:A"))).isEqualTo(ScanOutcome.Accepted)
    assertThat(seen).containsExactly("MCG1:A")

    now += 10_000
    assertThat(dispatcher.onRead(found("MCG1:B"))).isEqualTo(ScanOutcome.Nothing)
    assertThat(dispatcher.onManual("MCG1:C")).isEqualTo(ScanOutcome.Nothing)
    assertThat(seen).containsExactly("MCG1:A")
  }

  @Test
  fun rifiutato_siMostraIlMotivo_esiContinua() {
    verdict = { ScanVerdict.Reject(ScanRejection.OTHER_FAMILY) }
    assertThat(dispatcher.onRead(found("MCG1:ALTRA"))).isEqualTo(ScanOutcome.Rejected(ScanRejection.OTHER_FAMILY))

    verdict = { ScanVerdict.Accept }
    now += 100
    assertThat(dispatcher.onRead(found("MCG1:GIUSTA"))).isEqualTo(ScanOutcome.Accepted)
  }

  @Test
  fun ognunDeiMotiviArrivaComE() {
    for (reason in ScanRejection.entries) {
      val d = ScanDispatcher(accept = { ScanVerdict.Reject(reason) }, nowMs = { now })
      assertThat(d.onRead(found("x$reason"))).isEqualTo(ScanOutcome.Rejected(reason))
    }
  }

  @Test
  fun ignora_nonMostraNienteEContinua() {
    verdict = { ScanVerdict.Ignore }
    assertThat(dispatcher.onRead(found("MCG1:A"))).isEqualTo(ScanOutcome.Nothing)
    verdict = { ScanVerdict.Accept }
    now += 5_000
    assertThat(dispatcher.onRead(found("MCG1:B"))).isEqualTo(ScanOutcome.Accepted)
  }

  @Test
  fun lostessoQrRestatoInquadrato_produceUnSoloMessaggio() {
    verdict = { ScanVerdict.Reject(ScanRejection.NOT_OURS) }
    assertThat(dispatcher.onRead(found("https://esempio.it"))).isEqualTo(ScanOutcome.Rejected(ScanRejection.NOT_OURS))
    // dieci fotogrammi a 300 ms l'uno: il QR resta nel mirino per tre secondi, la finestra di 2 s riparte a ogni lettura
    repeat(10) {
      now += 300
      assertThat(dispatcher.onRead(found("https://esempio.it"))).isEqualTo(ScanOutcome.Nothing)
    }
    assertThat(seen).hasSize(1)
  }

  @Test
  fun dopoUnaPausa_lostessoQrSiRiconsegna() {
    verdict = { ScanVerdict.Reject(ScanRejection.EXPIRED) }
    dispatcher.onRead(found("MCG1:VECCHIO"))
    now += ScanDispatcher.DEFAULT_REPEAT_WINDOW_MS + 1
    assertThat(dispatcher.onRead(found("MCG1:VECCHIO"))).isEqualTo(ScanOutcome.Rejected(ScanRejection.EXPIRED))
    assertThat(seen).hasSize(2)
  }

  @Test
  fun unTestoDiversoPassaSubito_ancheDentroLaFinestra() {
    verdict = { ScanVerdict.Reject(ScanRejection.NOT_OURS) }
    dispatcher.onRead(found("uno"))
    now += 50
    dispatcher.onRead(found("due"))
    now += 50
    dispatcher.onRead(found("uno"))
    assertThat(seen).containsExactly("uno", "due", "uno").inOrder()
  }

  @Test
  fun unQrTroppoLungo_nonArrivaAChiDecide_eEUnQrNonNostro() {
    assertThat(dispatcher.onRead(QrRead.TooLong)).isEqualTo(ScanOutcome.Rejected(ScanRejection.NOT_OURS))
    assertThat(seen).isEmpty()
    // e visto di continuo non rifa' il messaggio
    now += 200
    assertThat(dispatcher.onRead(QrRead.TooLong)).isEqualTo(ScanOutcome.Nothing)
  }

  // --- a mano ---------------------------------------------------------------------------------------------

  @Test
  fun aMano_siTolgonoSpaziEACapo() {
    dispatcher.onManual("  MCG1:ABC\r\n")
    assertThat(seen).containsExactly("MCG1:ABC")
  }

  @Test
  fun aMano_unCodiceSpezzatoInPiuRighe_siRiunisce() {
    dispatcher.onManual("MCG1:AAAA\nBBBB\r\nCCCC")
    assertThat(seen).containsExactly("MCG1:AAAABBBBCCCC")
  }

  @Test
  fun aMano_vuotoOSoloSpazi_nonConsegnaNiente() {
    assertThat(dispatcher.onManual("")).isEqualTo(ScanOutcome.Nothing)
    assertThat(dispatcher.onManual("   \n  ")).isEqualTo(ScanOutcome.Nothing)
    assertThat(seen).isEmpty()
  }

  @Test
  fun aMano_oltreIlTetto_eNotOurs_senzaConsegnare() {
    val atCap = "a".repeat(QrLimits.MAX_CHARS)
    assertThat(dispatcher.onManual(atCap + "b")).isEqualTo(ScanOutcome.Rejected(ScanRejection.NOT_OURS))
    assertThat(seen).isEmpty()
    assertThat(dispatcher.onManual(atCap)).isEqualTo(ScanOutcome.Accepted) // al tetto passa
    assertThat(seen).containsExactly(atCap)
  }

  @Test
  fun aMano_nonHaFinestraDiRipetizione_ognunTentativoViene_valutato() {
    verdict = { ScanVerdict.Reject(ScanRejection.MALFORMED) }
    assertThat(dispatcher.onManual("MCG1:X")).isEqualTo(ScanOutcome.Rejected(ScanRejection.MALFORMED))
    assertThat(dispatcher.onManual("MCG1:X")).isEqualTo(ScanOutcome.Rejected(ScanRejection.MALFORMED))
    assertThat(seen).hasSize(2)
  }

  @Test
  fun ilFoundNonStampaIlTesto() {
    assertThat(found("MCG1:SEGRETO").toString()).doesNotContain("SEGRETO")
  }
}
