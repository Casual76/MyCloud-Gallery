package dev.pampa.mycloudgallery.feature.nas.folders

import androidx.compose.runtime.Immutable
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderEntry
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderState
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// Fase 7 (P9, D23; chiude I8 della revisione 4B): in Cartelle NAS i file che l'utente ha nascosto nella Libreria non si elencano per nome.
// L'elenco arriva dal NAS e il nome di un file nascosto e' gia' una parte del segreto ("Compleanno_Luca.jpg"): al suo posto una riga che dice
// quanti sono. Fallisce chiuso: finche' non si sa quali sono, o se non si riesce a saperlo, nessun file si elenca (le cartelle si').

/** Cosa si sa dei file nascosti della cartella che si sta guardando. */
@Immutable
internal sealed interface HiddenFiles {

  /** Non ancora: l'elenco e' appena arrivato e `:app` sta rispondendo. I file non si mostrano. */
  data object Checking : HiddenFiles

  /** Non si e' riusciti a saperlo (il catalogo non ha risposto). I file non si mostrano, e la pagina lo dice. */
  data object Unknown : HiddenFiles

  /** Questi file sono nascosti nella Libreria; tutti gli altri si mostrano. */
  data class Known(val paths: Set<SharePath>) : HiddenFiles {
    override fun toString(): String = "Known(${paths.size})"
  }
}

/** Le voci di [listing] che la pagina puo' mostrare con quel che si sa di [hidden]: le cartelle sempre, i file solo se sicuramente non nascosti. */
internal fun shownEntries(listing: List<NasFolderEntry>, hidden: HiddenFiles): List<NasFolderEntry> =
  listing.filter { entry ->
    entry.isFolder || (hidden is HiddenFiles.Known && entry.path !in hidden.paths)
  }

/** Quanti file di [listing] sono nascosti (la riga "N elementi nascosti"); 0 se non si sa. */
internal fun hiddenCountOf(listing: List<NasFolderEntry>, hidden: HiddenFiles): Int =
  if (hidden is HiddenFiles.Known) listing.count { !it.isFolder && it.path in hidden.paths } else 0

/** Le voci di un elenco com'e' arrivato (o l'ultimo salvato se il NAS non risponde), con **tutti** i nomi: serve a chiedere e a contare, non a mostrare. */
internal fun entriesOf(listing: NasFolderState): List<NasFolderEntry> = when (listing) {
  is NasFolderState.Listing -> listing.entries
  is NasFolderState.Offline -> listing.cached.orEmpty()
  else -> emptyList()
}
