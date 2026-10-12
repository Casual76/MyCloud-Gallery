package dev.pampa.mycloudgallery.core.qr.scanner

import dev.pampa.mycloudgallery.core.qr.QrLimits
import dev.pampa.mycloudgallery.core.qr.codec.QrRead
import dev.pampa.mycloudgallery.core.qr.contract.ScanRejection
import dev.pampa.mycloudgallery.core.qr.contract.ScanVerdict

/** Cosa lo scanner fa dopo aver consegnato un testo a chi lo usa. */
internal sealed interface ScanOutcome {

  /** Niente da fare: stesso testo di poco fa, verdetto "ignora" o scanner gia' concluso. */
  data object Nothing : ScanOutcome

  /** Chi usa lo scanner ha accettato: lo scanner si ferma. */
  data object Accepted : ScanOutcome

  /** Rifiutato: si mostra il motivo e si continua a cercare. */
  data class Rejected(val reason: ScanRejection) : ScanOutcome
}

/**
 * Il vigile fra i fotogrammi (o la tastiera) e chi decide: applica il tetto di lunghezza, non consegna lo
 * stesso testo cento volte e traduce i verdetti. Pura (nessuna fotocamera, nessun Android): si prova su JVM.
 *
 * **Non consegnare due volte.** Un QR resta davanti alla fotocamera per secondi, e ogni fotogramma lo
 * rilegge. Lo stesso testo visto di nuovo entro [repeatWindowMs] dall'ultima volta che lo si e' visto non
 * viene riconsegnato: l'attesa riparte a ogni nuova lettura, quindi un QR rifiutato che resta inquadrato
 * produce un solo messaggio, e ne produce un altro solo dopo essere uscito dal mirino per un po'. E' in
 * aggiunta (non al posto) del verdetto [ScanVerdict.Ignore], con cui chi usa lo scanner puo' fare lo stesso.
 *
 * **Tetto.** Un QR oltre [QrLimits.MAX_CHARS] non arriva a [accept]: e' `NOT_OURS`, come ogni QR che non e' nostro.
 * Il testo digitato a mano passa dalla stessa regola.
 */
internal class ScanDispatcher(
  private val accept: (String) -> ScanVerdict,
  private val nowMs: () -> Long,
  private val repeatWindowMs: Long = DEFAULT_REPEAT_WINDOW_MS,
) {

  private var finished = false
  private var lastKey: String? = null
  private var lastSeenAt = 0L

  /** Un fotogramma ha dato [read]. */
  fun onRead(read: QrRead): ScanOutcome = when (read) {
    QrRead.None -> ScanOutcome.Nothing
    QrRead.TooLong -> handle(key = TOO_LONG_KEY) { ScanOutcome.Rejected(ScanRejection.NOT_OURS) }
    is QrRead.Found -> handle(key = read.text) { verdictOf(accept(read.text)) }
  }

  /**
   * Il testo digitato (o incollato) a mano. Spazi ai lati e a-capo si tolgono: un codice copiato da uno schermo
   * porta spesso l'a-capo in fondo, o spezzato in piu' righe.
   */
  fun onManual(raw: String): ScanOutcome {
    if (finished) return ScanOutcome.Nothing
    val text = normalizeManual(raw)
    if (text.isEmpty()) return ScanOutcome.Nothing
    if (text.length > QrLimits.MAX_CHARS) return ScanOutcome.Rejected(ScanRejection.NOT_OURS)
    return verdictOf(accept(text))
  }

  private inline fun handle(key: String, decide: () -> ScanOutcome): ScanOutcome {
    if (finished) return ScanOutcome.Nothing
    val now = nowMs()
    val repeated = key == lastKey && now - lastSeenAt in 0 until repeatWindowMs
    lastKey = key
    lastSeenAt = now
    if (repeated) return ScanOutcome.Nothing
    return decide()
  }

  private fun verdictOf(verdict: ScanVerdict): ScanOutcome = when (verdict) {
    ScanVerdict.Accept -> {
      finished = true
      ScanOutcome.Accepted
    }
    is ScanVerdict.Reject -> ScanOutcome.Rejected(verdict.reason)
    ScanVerdict.Ignore -> ScanOutcome.Nothing
  }

  companion object {
    /** Per quanto lo stesso testo, rivisto di continuo, non viene riconsegnato. */
    const val DEFAULT_REPEAT_WINDOW_MS: Long = 2_000L

    private const val TOO_LONG_KEY = "\u0000troppo-lungo"

    /** Toglie gli spazi ai lati e ogni a-capo. */
    fun normalizeManual(raw: String): String = raw.trim().filter { it != '\n' && it != '\r' }.trim()
  }
}
