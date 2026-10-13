package dev.pampa.mycloudgallery.feature.library.testing

import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.feature.library.library.LibraryTabState
import dev.pampa.mycloudgallery.feature.library.library.testing.M1SyntheticSizes
import dev.pampa.mycloudgallery.feature.library.library.testing.syntheticLibraryState

// I fake della Libreria (D23): in src/main, cosi' :app (solo debug) e i test li usano senza cicli. Mai nella release:
// tools/check.ps1 fallisce se una classe di un package `testing` compare nel dex dell'APK di release. Nessun codice di
// produzione di questo modulo li referenzia.

/**
 * La Libreria sintetica pronta per la scheda: [count] elementi deterministici su 15 anni, miniature a gradiente, luoghi
 * veri del mini-dataset di prova, motore di layout di riferimento e un viewer finto (docs/fase3-progetto.md §5.2).
 *
 * E' l'ingresso di P0 (lo chiama `DeveloperTools` in debug e lo usano gli screenshot di `:app`): P7 lo fa passare dalla
 * Libreria vera (`syntheticLibraryState`: filtro e stile ricordati, preferiti, menu di debug di M1). In debug il menu della
 * vista offre anche la Libreria da 100 mila elementi (M1, `docs/bench/M1.md`).
 */
fun syntheticLibraryTabState(
  count: Int = SyntheticLibrary.DEFAULT_COUNT,
  seed: Long = 1L,
  level: GridLevel = GridLevel.DAYS,
  style: GridStyle = GridStyle.SQUARE,
  filter: LibraryFilter = LibraryFilter.ALL,
): LibraryTabState = syntheticLibraryState(
  count = count,
  seed = seed,
  level = level,
  style = style,
  filter = filter,
  debugSizes = M1SyntheticSizes,
)
