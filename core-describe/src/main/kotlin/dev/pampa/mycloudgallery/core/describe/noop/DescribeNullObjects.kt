package dev.pampa.mycloudgallery.core.describe.noop

import dev.pampa.mycloudgallery.core.describe.contract.A11yActivity
import dev.pampa.mycloudgallery.core.describe.contract.DescribePeople
import dev.pampa.mycloudgallery.core.describe.contract.DescribeText
import dev.pampa.mycloudgallery.core.model.MediaId

// Fase 10 (P0, D22): i null-object delle porte delle descrizioni. Falliscono **chiusi**: nessun nome, nessun testo letto, nessuno strumento di
// accessibilita' (quindi niente `warm`). Una descrizione legata a loro dice solo i fatti del media.

/** Persone spenta o non legata: nessun nome (E-AC2). */
object NoDescribePeople : DescribePeople {
  override suspend fun names(ids: Collection<MediaId>): Map<MediaId, List<String>> = emptyMap()
}

/** Lettura del testo spenta o non legata: nessuna parola (E-AC3). */
object NoDescribeText : DescribeText {
  override suspend fun firstWords(ids: Collection<MediaId>, max: Int): Map<MediaId, String> = emptyMap()
}

/** Nessuno strumento di accessibilita' attivo: `warm` non carica niente (E-AC1, nessun costo). */
object NoA11yTool : A11yActivity {
  override fun toolEnabled(): Boolean = false
}
