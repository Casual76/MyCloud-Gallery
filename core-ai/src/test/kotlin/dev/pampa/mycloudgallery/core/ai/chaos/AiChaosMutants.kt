package dev.pampa.mycloudgallery.core.ai.chaos

import dev.pampa.mycloudgallery.core.ai.compute.AiComputeParts
import dev.pampa.mycloudgallery.core.ai.contract.AiResults
import dev.pampa.mycloudgallery.core.index.ai.AiEntry
import dev.pampa.mycloudgallery.core.index.ai.AiIndexPublisher
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.OcrBody
import dev.pampa.mycloudgallery.core.trust.data.VecBody

// Gli ERRORI INTRODOTTI APPOSTA del calcolo (AiChaosMutationTest, riga P10 del §4.4): i due difetti dell'ordine "salvare, poi pubblicare"
// che il calcolo potrebbe avere, scritti come pezzi rotti che avvolgono le porte del calcolo sano (`AiResults`, `AiIndexPublisher`) e ne
// riproducono l'EFFETTO. Il codice di produzione non si tocca: come per le fasi 4 e 5. I primi due difetti della riga P10 (caricare prima di
// verificare, accettare un parziale) riguardano lo scaricamento e stanno nel banco di core-models (`ModelMutants`).
//
//  3. PUBBLICARE PRIMA DI SALVARE: prima di scrivere il risultato nell'indice locale, il calcolo mette la voce in fila e la pubblica; se il
//     processo muore nel salvataggio, sul NAS c'e' una voce che l'indice locale non ha (e un'altra copia del lavoro non si rifara' mai:
//     "gia' fatto" da chi l'ha pubblicata);
//  4. SALVARE DOPO AVER PUBBLICATO: i risultati si tengono in memoria e si scrivono nell'indice locale solo alla fine dell'unita', dopo la
//     pubblicazione; un media calcolato e pubblicato non ha la sua riga finche' l'unita' non finisce, e una morte nel mezzo la perde.

object AiMutants {

  /** 3. Pubblicare prima di salvare. */
  fun publishBeforeSave(): AiStackFactory = object : AiMutantFactory("pubblica la voce prima di salvarla nell'indice locale") {
    override fun wrap(world: AiChaosWorld, parts: AiComputeParts): AiComputeParts =
      parts.copy(results = PublishFirstResults(parts.results, parts.publisher, world))
  }

  private class PublishFirstResults(private val inner: AiResults, private val publisher: AiIndexPublisher, private val world: AiChaosWorld) : AiResults by inner {
    override suspend fun saveVectors(key: ContentKey, body: VecBody) {
      early(key, AiEntry.Vector(key, body))
      inner.saveVectors(key, body)
    }

    override suspend fun saveText(key: ContentKey, body: OcrBody) {
      early(key, AiEntry.Text(key, body))
      inner.saveText(key, body)
    }

    private suspend fun early(key: ContentKey, entry: AiEntry) {
      for (share in world.sharesOf(key)) {
        publisher.stage(share, listOf(entry))
        publisher.flush(share, FlushReason.UNIT_FINISHED)
      }
    }
  }

  /** 4. Salvare dopo aver pubblicato. */
  fun saveAfterPublish(): AiStackFactory = object : AiMutantFactory("scrive nell'indice locale solo dopo aver pubblicato") {
    override fun wrap(world: AiChaosWorld, parts: AiComputeParts): AiComputeParts {
      val later = DeferredSaves(parts.results)
      return parts.copy(results = later, publisher = FlushThenSave(parts.publisher, later))
    }
  }

  private class DeferredSaves(private val inner: AiResults) : AiResults {
    private val pending = ArrayList<suspend () -> Unit>()

    override suspend fun saveVectors(key: ContentKey, body: VecBody) {
      pending += { inner.saveVectors(key, body) }
    }

    override suspend fun saveText(key: ContentKey, body: OcrBody) {
      pending += { inner.saveText(key, body) }
    }

    override suspend fun saved(key: ContentKey, recipe: Int): Set<AiClass> = inner.saved(key, recipe)

    suspend fun writeAll() {
      val list = pending.toList()
      pending.clear()
      list.forEach { it() }
    }
  }

  private class FlushThenSave(private val inner: AiIndexPublisher, private val later: DeferredSaves) : AiIndexPublisher by inner {
    override suspend fun flush(share: String, reason: FlushReason): PublishOutcome {
      val outcome = inner.flush(share, reason)
      later.writeAll()
      return outcome
    }
  }
}

/** Una fabbrica di calcolo rotto, con l'etichetta del difetto per i rapporti. */
abstract class AiMutantFactory(val label: String) : AiStackFactory {
  override fun toString(): String = "difetto iniettato: $label"
}
