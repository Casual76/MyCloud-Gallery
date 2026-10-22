package dev.pampa.mycloudgallery.debugtools.eval

import androidx.compose.ui.graphics.ImageBitmap
import dev.pampa.mycloudgallery.core.ai.contract.TextEmbedder
import dev.pampa.mycloudgallery.core.ai.contract.TextReader
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.MediaText
import dev.pampa.mycloudgallery.core.search.contract.PlaceDictionary
import dev.pampa.mycloudgallery.core.search.contract.QueryParser
import dev.pampa.mycloudgallery.core.search.contract.SearchData
import dev.pampa.mycloudgallery.core.search.contract.SearchEngine
import dev.pampa.mycloudgallery.core.search.contract.Today
import dev.pampa.mycloudgallery.core.search.engine.QueryEmbedding

/**
 * SOLO debug (Fase 6, P9): tutto cio' che lo strumento "Valutazione della ricerca" chiede all'app. La **lega P11** quando collega `SearchGraph`
 * (l'indice locale, `CellKeys`, il motore delle query, il lettore del testo): P9 non tocca `:app` fuori da `src/debug/.../eval/`, quindi lo
 * strumento parla con questa porta e non con i grafi dell'app. Senza legatura la schermata dice "ricerca non collegata" e non fa niente.
 *
 * La porta mette insieme le stesse cose che usa il motore di ricerca vero: cosi' le tabelle dei punteggi vedono quello che vede il motore
 * (`SyntheticEvalTest` lo prova sulla JVM). Mai rete, mai NAS: solo l'indice locale e le miniature locali.
 */
interface EvalEnvironment {
  /** Il catalogo della ricerca sopra l'indice locale (di norma `IndexSearchData`): istantanea, vettori, testo, nascosti. */
  val data: SearchData

  /** Il motore vero (`LocalSearchEngine`): una delle due configurazioni del pozzo. */
  val engine: SearchEngine

  /** Il vettore della query (270M): senza, le tabelle hanno solo il testo e lo strumento lo dice. */
  val embedding: QueryEmbedding

  /** Il motore del testo per i vettori delle frasi prototipo di "Documenti" (D34); null se il modello non c'e'. */
  val embedder: TextEmbedder?

  val parser: QueryParser

  val today: Today

  /** I luoghi delle celle **visibili** (mai dai nascosti), per le query con un luogo. */
  fun places(): PlaceDictionary

  /** Le righe e i caratteri letti di un media (porta `MediaText`), per "Documenti". */
  val mediaText: MediaText

  /** La chiave di contenuto della cella di un media, o null se non ce l'ha: una cella senza chiave non si etichetta. */
  fun keyOf(id: MediaId): String?

  /** Prima di leggere le chiavi (di norma `CellKeys.sync`). */
  suspend fun prepare()

  /** La miniatura **locale** del media (mai dal NAS), o null se non c'e': la schermata mostra un riquadro grigio. */
  suspend fun thumbnail(id: MediaId): ImageBitmap?

  /** Il lettore del testo vero (ML Kit dietro il cancello); null con la lettura del testo spenta: la lettura di prova non parte. */
  val textReader: TextReader?
}

/** Il punto in cui P11 mette la porta quando lo strumento si apre. Fino ad allora e' vuoto. */
object EvalEnvironmentHolder {
  @Volatile var current: EvalEnvironment? = null
}
