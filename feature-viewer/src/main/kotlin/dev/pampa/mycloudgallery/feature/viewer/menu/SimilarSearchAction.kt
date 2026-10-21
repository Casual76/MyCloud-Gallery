package dev.pampa.mycloudgallery.feature.viewer.menu

import android.content.res.Resources
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ImageSearch
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.viewer.R

// La voce "Trova simili" del menu ⋮ del viewer (Fase 6, P8, docs/fase6-progetto.md D30, §1.5 "Azione Trova simili", E-Q14).
// Il viewer non vede core-search ne' core-ai (Phase6ArchitectureTest): chiede a questa porta se la voce c'e', e quando la si sceglie
// la porta apre Cerca con il chip "Simili a" (la apre `:app`, P11). Il viewer non sa niente di vettori.

/** Come si presenta la voce per un media (§1.5): presente, assente (nascosta) o spiegata (senza vettore). */
enum class SimilarAvailability {
  /** Il media ha un vettore: la voce apre la ricerca dei simili. */
  AVAILABLE,

  /** Il media non ha (ancora) un vettore: la voce c'e', e al tocco dice perche' non si puo' (E-Q14). */
  EXPLAINED,

  /** Nessuna voce: un media nascosto (D29, D30) o un viewer senza ricerca (D13: niente voci morte). */
  HIDDEN,
}

/**
 * "Trova simili" per il viewer. `:app` la lega al motore di ricerca (`SearchEngine.similar`) e all'indice locale: [availability] risponde
 * da dati gia' pronti (nessun lavoro lungo: la chiama il menu a ogni composizione), [findSimilar] porta alla schermata Cerca.
 */
interface ViewerSimilarSearch {
  fun availability(id: MediaId): SimilarAvailability

  fun findSimilar(id: MediaId)
}

/** Nessuna ricerca: la voce non compare mai. */
object NoViewerSimilarSearch : ViewerSimilarSearch {
  override fun availability(id: MediaId): SimilarAvailability = SimilarAvailability.HIDDEN

  override fun findSimilar(id: MediaId) = Unit
}

/**
 * La voce del menu per [availability]. Nascosta: nessuna voce. Spiegata: la voce c'e' e [onExplain] mostra la frase (il viewer non
 * resta mai muto). [onFind] la apre solo se disponibile: una voce spiegata non lancia mai la ricerca.
 */
internal fun similarMenuEntries(
  resources: Resources,
  availability: SimilarAvailability,
  onFind: () -> Unit,
  onExplain: () -> Unit,
): List<GalleryMenuEntry> = when (availability) {
  SimilarAvailability.HIDDEN -> emptyList()
  SimilarAvailability.AVAILABLE -> listOf(GalleryMenuEntry.Item(resources.getString(R.string.similar_find), onFind, Icons.Rounded.ImageSearch))
  SimilarAvailability.EXPLAINED -> listOf(GalleryMenuEntry.Item(resources.getString(R.string.similar_find), onExplain, Icons.Rounded.ImageSearch))
}
