package dev.pampa.mycloudgallery.feature.library.utility

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.library.duplicates.IdenticalDuplicates
import dev.pampa.mycloudgallery.core.library.duplicates.NoIdenticalDuplicates
import dev.pampa.mycloudgallery.core.library.duplicates.NoSimilarShots
import dev.pampa.mycloudgallery.core.library.duplicates.SimilarShots
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.feature.library.collections.CollectionItemsSource
import dev.pampa.mycloudgallery.feature.library.collections.GridEnvironment
import java.util.Optional
import javax.inject.Inject

/**
 * Il ViewModel dei **Doppioni** (D25, D27): tiene il [DuplicatesController] legato alla destinazione. Le porte sono tutte **opzionali**
 * (`OptionalUtilityBindingsModule`): finche' `:app` non le lega la pagina e' vuota ("nessun doppione") e non fa niente. I riferimenti per le
 * miniature vengono dalla vista della Libreria ([CollectionItemsSource]): che i nascosti non li ha (E-H1), e i doppioni sono comunque filtrati da
 * `VisibleMedia` nel rilevatore.
 */
@HiltViewModel
class DuplicatesViewModel @Inject constructor(
  identical: Optional<IdenticalDuplicates>,
  similar: Optional<SimilarShots>,
  cleanupNas: Optional<CleanupNasPort>,
  fullHash: Optional<FullHashSwitch>,
  names: Optional<DuplicatesNames>,
  phoneCheck: Optional<PhoneCopyCheck>,
  intents: PhoneMediaIntents,
  source: CollectionItemsSource,
  val environment: GridEnvironment,
) : ViewModel() {

  val controller: DuplicatesController = DuplicatesController(
    identical = identical.orElse(NoIdenticalDuplicates),
    similar = similar.orElse(NoSimilarShots),
    cleanupNas = cleanupNas.orElse(NoCleanupNas),
    intents = intents,
    fullHash = fullHash.orElse(NoFullHashSwitch),
    scope = viewModelScope,
    phoneCheck = phoneCheck.orElse(NoPhoneCopyCheck),
  )

  /** Gli elementi della Libreria intera: da qui il riferimento ai byte di un media per la sua miniatura. */
  val items: ViewerItems = source.items(LibraryFilter.ALL)

  /** Il nome del familiare di un'area (E-U1), se la famiglia lo sa. */
  val familyName: (String) -> String? = { share -> names.orElse(null)?.familyName(share) }

  override fun onCleared() {
    controller.close()
  }
}
