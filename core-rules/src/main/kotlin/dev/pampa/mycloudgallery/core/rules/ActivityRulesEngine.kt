package dev.pampa.mycloudgallery.core.rules

/** Il verdetto del motore per una attivita' in questo momento. */
sealed interface RuleDecision {

  /** Puo' girare adesso. */
  data object Run : RuleDecision

  /** Deve aspettare, e sa dire perche' in modo leggibile ("serve la carica", "tetto dati raggiunto"). */
  data class Wait(val reason: WaitReason) : RuleDecision
}

/** Perche' un'attivita' aspetta. La 1.6 lo estende con i motivi veri (carica, rete, tetto, NAS spento...). */
enum class WaitReason {
  /** Le regole non sono ancora configurate: nessuna attivita' parte. */
  NOT_CONFIGURED,
}

/**
 * Segnaposto della Fase 1.1 per il motore delle regole (piano 1.6). Finche' le regole non esistono
 * il verdetto e' sempre "aspetta": una attivita' in background non parte mai per un motore vuoto.
 */
class ActivityRulesEngine {

  fun evaluate(): RuleDecision = RuleDecision.Wait(WaitReason.NOT_CONFIGURED)
}
