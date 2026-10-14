package dev.pampa.mycloudgallery.core.prepare.contract

import dev.pampa.mycloudgallery.core.index.contract.WorkItem
import dev.pampa.mycloudgallery.core.trust.data.MetaFile

// Le PORTE del giro di preparazione (§2.4): cio' che `PreparationRun` chiede a :app (P11) senza conoscerne i moduli. Ogni
// porta ha un'implementazione neutra (qui o in `testing/`) cosi' il giro gira da solo nei test. Aggiunte di P6.

/**
 * Cio' che la scansione conosce di un file e il catalogo ha scritto (P5/P2): data, fuso, luogo, tratti, impronta rapida,
 * identita' condivise. La preparazione lo **completa** (classi preparate, hash completo, dimensioni corrette dalla
 * decodifica, segnaposto) e lo pubblica; non lo inventa. Null = il catalogo non conosce piu' quel file (cancellato o
 * rinominato dopo l'elenco): il media si salta e la prossima scansione sistema le cose.
 *
 * Il dato viene dal catalogo locale, mai da un record remoto (D8).
 */
fun interface PrepRecordSource {
  suspend fun baseFor(share: String, item: WorkItem): MetaFile?
}

/**
 * I tentativi falliti per media (E-M10): un file che non si decodifica si riprova a ogni giro, **cinque** volte; alla quinta
 * il giro pubblica il fallimento e il media diventa "non leggibile", mai bloccante. Lo stato sta nel catalogo (stessa
 * regola di `WorkRules.MAX_ATTEMPTS`); qui solo il contatore.
 */
fun interface PrepAttempts {
  /** Registra un fallimento di [item] con [code] e dice quanti ce ne sono ora (questo compreso). */
  suspend fun failed(share: String, item: WorkItem, code: PrepFailCode): Int

  companion object {
    /** Il numero di tentativi dopo cui ci si arrende. */
    const val MAX_ATTEMPTS: Int = 5

    /** Nessun conteggio: ogni fallimento vale "e' il primo" (il giro non si arrende mai; serve ai test e alla Fase 5 appena nata). */
    val NONE: PrepAttempts = PrepAttempts { _, _, _ -> 1 }
  }
}

/** Il verdetto delle regole delle attivita' (D22) fra un media e l'altro. */
sealed interface PrepVerdict {
  /** Si prosegue al ritmo normale. */
  data object Continue : PrepVerdict

  /** Si prosegue piano (`Pace.REDUCED`, telefono caldo): un media per volta e una pausa fra l'uno e l'altro. */
  data object Slow : PrepVerdict

  /** Ci si ferma dopo il media in corso: l'unita' si salva e si lascia ([by] finisce in [RunResult.stoppedBy]). */
  data class Stop(val by: PrepStop = PrepStop.RULES) : PrepVerdict
}

/** Chi consulta le regole per il giro (:app lo lega a `StagedRun`/`ActivityRulesEngine`, P9/P11). */
fun interface PrepControl {
  suspend fun verdict(): PrepVerdict

  companion object {
    val ALWAYS: PrepControl = PrepControl { PrepVerdict.Continue }
  }
}

/** Un orologio **monotono** per le durate del giro (tappa da 8 minuti, rinnovo ogni 20): mai l'ora del telefono (D32, §4.3). */
fun interface PrepClock {
  fun nanos(): Long

  companion object {
    val SYSTEM: PrepClock = PrepClock { System.nanoTime() }
  }
}
