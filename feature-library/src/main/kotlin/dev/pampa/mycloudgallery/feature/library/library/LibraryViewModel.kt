package dev.pampa.mycloudgallery.feature.library.library

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Il ViewModel della Libreria.
 *
 * **Tutta la logica sta in [LibraryTabState]** (griglia, filtro e stile ricordati, stato del telefono, azioni), provata
 * su JVM senza Hilt; [LibraryTab] la riceve gia' costruita. Le dipendenze vere (`TimelineRepository`, `LibraryPreferences`,
 * `LocalMediaActions`, `ThumbnailLoader`, `PhoneAccessReader`/`PhoneLibrarySync`, i comandi verso l'activity) le lega
 * `:app` (P11): finche' non ci sono nel grafo, un costruttore `@Inject` che le chiedesse romperebbe la compilazione di
 * `:app`. P11 le aggiunge qui e costruisce lo stato nello scope del ViewModel, cosi' sopravvive alla ricreazione
 * dell'activity (la posizione della griglia sopravvive comunque: `rememberSaveable` come elemento ancorato, E-P18).
 */
@HiltViewModel
class LibraryViewModel @Inject constructor() : ViewModel()
