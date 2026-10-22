package dev.pampa.mycloudgallery.feature.viewer.motion

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pampa.mycloudgallery.core.model.MediaId

/** Che cosa il badge "Movimento" ha da dire a chi lo tocca, quando il video non parte (D51). */
internal sealed interface MotionNotice {

  /** Media del NAS sui dati mobili: "Il file pesa X. Guardarlo con i dati mobili?" ([sizeBytes] e' il peso del file secondo il catalogo). */
  data class NeedsMobileData(val id: MediaId, val sizeBytes: Long) : MotionNotice

  /** Il video non si puo' avere adesso (NAS non raggiungibile da questa rete, spento, o parte troppo grande): "non disponibile ora". */
  data class Unavailable(val id: MediaId) : MotionNotice
}

/**
 * Cio' che il viewer sa delle foto in movimento, per la sola UI (D50, D51): quali non si possono avere adesso e l'avviso da mostrare.
 * Che cosa si sta riproducendo lo dice `ViewerPlayback` (`motionFor`, `motionMode`): qui non si duplica.
 */
@Stable
internal class MotionState {

  private val unavailable = mutableStateMapOf<MediaId, Boolean>()
  private val loading = mutableStateMapOf<MediaId, Boolean>()

  /** L'avviso aperto dal tocco sul badge, se c'e'. Si chiude da solo, cambiando pagina o con un altro tocco. */
  var notice: MotionNotice? by mutableStateOf(null)

  /** Il badge di [id] e' "non disponibile ora": la rete non lo permette o la lettura non e' riuscita. Passa quando la rete cambia. */
  fun isUnavailable(id: MediaId): Boolean = unavailable[id] == true

  /** Il video di [id] si sta leggendo dal NAS (VM-3): il badge mostra "In caricamento" e ignora altri tocchi. */
  fun isLoading(id: MediaId): Boolean = loading[id] == true

  fun markLoading(id: MediaId) {
    loading[id] = true
  }

  fun clearLoading(id: MediaId) {
    loading.remove(id)
  }

  fun markUnavailable(id: MediaId) {
    unavailable[id] = true
  }

  fun clearUnavailable(id: MediaId) {
    unavailable.remove(id)
  }

  /** La rete e' cambiata (D-15): cio' che si diceva per colpa della rete non e' piu' vero com'era. */
  fun networkChanged() {
    unavailable.clear()
    notice = null
  }
}
