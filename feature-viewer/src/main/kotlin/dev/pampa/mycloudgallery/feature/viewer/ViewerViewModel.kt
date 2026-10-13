package dev.pampa.mycloudgallery.feature.viewer

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Il ViewModel del viewer. **Scheletro di P0**: esiste per provare che Hilt e KSP girano in questo modulo (D7 della Fase
 * 2) cosi' che P8 non debba toccare nessun file di build. P8 lo riempie con la pagina corrente (salvata nello stato: E-V8),
 * i `MediaDetails` dal catalogo, l'EXIF e il luogo su richiesta, le azioni (preferito, nascondi, condividi, copia,
 * elimina dal telefono) e lo collega a [ViewerOverlay].
 */
@HiltViewModel
class ViewerViewModel @Inject constructor() : ViewModel()
