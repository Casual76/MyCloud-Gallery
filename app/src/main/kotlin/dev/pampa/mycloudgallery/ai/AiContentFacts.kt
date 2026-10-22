package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.ai.compute.AiMediaSources
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.VersionId
import java.util.Collections

/**
 * Cio' che il catalogo ha detto di un contenuto appena elencato per il calcolo AI (Fase 6, P11b): a quale versione di quale media
 * appartiene la chiave di contenuto, quanto e' grande e da dove viene. La chiave e' un'impronta a senso unico (`ContentKey.of`): le porte
 * del calcolo che ricevono **solo la chiave** (i byte del telefono, la sorgente per gli screenshot) la ritrovano da qui.
 */
class ContentFact(
  val mediaId: MediaId,
  val versionId: VersionId,
  val size: Long,
  val source: MediaSource,
  /** L'impronta rapida della versione: se nota, chi prepara i byte del telefono controlla che siano quelli (D8). */
  val quickHash: QuickHash? = null,
) {
  override fun toString(): String = "ContentFact($source)"
}

/**
 * Le ultime chiavi elencate, con un tetto: l'elenco di un mese del NAS o la pagina dei media del solo telefono mettono qui i loro contenuti
 * prima che il calcolo li chieda; i piu' vecchi escono per primi. Niente si salva su disco (sono dati del catalogo, si rileggono).
 */
class ContentFacts(private val capacity: Int = DEFAULT_CAPACITY) : AiMediaSources {
  private val facts: MutableMap<ContentKey, ContentFact> = Collections.synchronizedMap(
    object : LinkedHashMap<ContentKey, ContentFact>(256, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ContentKey, ContentFact>?): Boolean = size > capacity
    },
  )

  fun put(key: ContentKey, fact: ContentFact) {
    facts[key] = fact
  }

  operator fun get(key: ContentKey): ContentFact? = facts[key]

  /** La sorgente dei contenuti noti (D12: per gli screenshot l'OCR del calcolo va subito sull'HQ). Le chiavi ignote non ci sono. */
  override suspend fun sources(keys: Collection<ContentKey>): Map<ContentKey, MediaSource> =
    keys.mapNotNull { key -> get(key)?.let { key to it.source } }.toMap()

  val size: Int get() = facts.size

  companion object {
    /** Un mese del NAS puo' avere qualche migliaio di file; le pagine del telefono 2.000 (`AiComputeConfig.localBatchLimit`). */
    const val DEFAULT_CAPACITY: Int = 16_384
  }
}
