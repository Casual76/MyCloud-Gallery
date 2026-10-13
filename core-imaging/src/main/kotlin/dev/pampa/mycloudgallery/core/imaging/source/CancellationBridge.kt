package dev.pampa.mycloudgallery.core.imaging.source

import android.os.CancellationSignal
import android.os.OperationCanceledException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Collega l'annullamento delle coroutine al `CancellationSignal` di Android, **subito**.
 *
 * Il lavoro vero (`ContentResolver.loadThumbnail`, una lettura dal MediaProvider) e' una chiamata bloccante che una
 * coroutine annullata non interrompe: la coroutine si accorge dell'annullamento solo quando la chiamata ritorna. Il
 * segnale invece la fa uscire subito. Per questo c'e' un guardiano che dorme accanto al lavoro e, appena la
 * coroutine viene annullata, fa scattare il segnale.
 *
 * Il guardiano parte senza cambiare thread (`UNDISPATCHED`): quando il lavoro comincia, il segnale e' gia' collegato.
 */
internal suspend fun <T> withCancellationSignal(block: suspend (CancellationSignal) -> T): T = coroutineScope {
  val signal = CancellationSignal()
  val finished = AtomicBoolean(false)
  val guard = launch(start = CoroutineStart.UNDISPATCHED) {
    try {
      awaitCancellation()
    } finally {
      if (!finished.get()) signal.cancel()
    }
  }
  try {
    block(signal)
  } catch (e: OperationCanceledException) {
    // Il segnale e' scattato dentro la chiamata bloccante: per le coroutine questo e' un annullamento.
    throw CancellationException("Operazione annullata.").also { it.initCause(e) }
  } finally {
    finished.set(true)
    guard.cancel()
  }
}
