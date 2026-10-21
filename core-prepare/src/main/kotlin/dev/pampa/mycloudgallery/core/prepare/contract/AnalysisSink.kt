package dev.pampa.mycloudgallery.core.prepare.contract

import dev.pampa.mycloudgallery.core.model.ContentKey

// Fase 6 (docs/fase6-progetto.md §1.1, §1.3, D10): la porta con cui la preparazione consegna le anteprime di analisi e i fotogrammi
// al BUFFER degli indicizzatori. Firma di P0; l'uso (in `DefaultPreparationRun` e nella preparazione del backup) e' di P5,
// l'implementazione e' il buffer di core-ai, legato da :app solo quando questo dispositivo e' Indicizzatore.
//
// Definita QUI e non in core-ai: chi prepara non vede core-ai ne' core-models ne' core-search (§1.1, nessun ciclo).

/**
 * Riceve `analysis` e `frames` appena preparati per [ContentKey] (oggi, con F4-D31 acceso sulle share vere, si buttano: R2 della
 * 4A). Le altre classi non arrivano mai qui. Non deve mai bloccare ne' far fallire la preparazione: un buffer pieno o sotto 1 GiB
 * libero rifiuta in silenzio (l'anteprima si riscarichera' dall'indice o si ripreparera').
 */
fun interface AnalysisSink {
  suspend fun deliver(contentKey: ContentKey, outputs: List<PrepOutput>)

  companion object {
    /** Nessun buffer: il dispositivo non e' Indicizzatore (o la Fase 6 non e' legata). */
    val NONE: AnalysisSink = AnalysisSink { _, _ -> }
  }
}
