package dev.pampa.mycloudgallery.feature.integrations.receive

import android.app.Activity
import android.os.Bundle

/**
 * La ricezione delle condivisioni (Fase 5B, D24, C18; docs/fase5-progetto.md §2.7): "Salva sul NAS" per `SEND` e `SEND_MULTIPLE` di immagini e
 * video. **Scheletro di P0-B**: non legge l'intento, non apre nessun flusso, non copia niente e chiude subito con `RESULT_CANCELED`. I file in
 * arrivo sono input non fidato: nessun flusso si apre finche' P11 non porta la copia immediata con i tetti, il tipo dai byte e il rifiuto di
 * `file://` e dei provider dell'app; nessun caricamento parte da solo (serve il tocco su "Salva sul NAS").
 *
 * P11 la riempie qui senza toccare il manifest; P14 estende `AppLockGate` a questa activity.
 */
class ShareReceiverActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setResult(RESULT_CANCELED)
    finish()
  }
}
