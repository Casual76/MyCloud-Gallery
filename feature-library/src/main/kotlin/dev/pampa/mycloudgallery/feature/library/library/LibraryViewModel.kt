package dev.pampa.mycloudgallery.feature.library.library

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Il ViewModel della Libreria. **Scheletro di P0**: esiste per provare che Hilt e KSP girano in questo modulo (D7 della
 * Fase 2: `@HiltViewModel`, rotte `@Serializable`) cosi' che P7 non debba toccare nessun file di build. P7 lo riempie
 * con il repository della timeline, le preferenze (filtro, livello, stile), la selezione, lo stato dei permessi e le
 * azioni (preferito, nascondi, elimina dal telefono) e lo collega a [LibraryTab].
 */
@HiltViewModel
class LibraryViewModel @Inject constructor() : ViewModel()
