package dev.pampa.mycloudgallery.feature.integrations.picker

import android.app.Activity
import android.os.Bundle

/**
 * Il selettore per le altre app (Fase 5B, D23, C17; docs/fase5-progetto.md §2.7): risponde a `GET_CONTENT` e `PICK` di immagini e video con la
 * griglia della Libreria, i nascosti esclusi, il blocco dell'app prima di mostrare. **Scheletro di P0-B**: non legge l'intento, non apre niente,
 * non mostra niente e chiude subito con `RESULT_CANCELED`. Una porta d'ingresso esportata non compie mai un'azione senza un tocco dell'utente
 * (C17): finche' P10 non la riempie, l'altra app riceve "annullato".
 *
 * P10 la riempie qui (griglia, `EXTRA_ALLOW_MULTIPLE`, risultato con `ClipData` e `FLAG_GRANT_READ_URI_PERMISSION`, E-X1, E-X4, E-X6, E-X7) senza
 * toccare il manifest; P14 estende `AppLockGate` a questa activity.
 */
class PickerActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setResult(RESULT_CANCELED)
    finish()
  }
}
