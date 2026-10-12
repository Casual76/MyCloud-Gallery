package dev.pampa.mycloudgallery.core.common.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * I dispatcher dell'app dietro un'interfaccia, cosi' i test sostituiscono tutti i thread con uno
 * `TestDispatcher` invece di dormire e sperare. Si inietta (Hilt la fornisce da `:app`), non si
 * scrive `Dispatchers.IO` dentro una classe di dominio.
 */
interface DispatcherProvider {
  val main: CoroutineDispatcher
  val io: CoroutineDispatcher
  val default: CoroutineDispatcher
}

/** I dispatcher veri. */
object DefaultDispatcherProvider : DispatcherProvider {
  override val main: CoroutineDispatcher get() = Dispatchers.Main
  override val io: CoroutineDispatcher get() = Dispatchers.IO
  override val default: CoroutineDispatcher get() = Dispatchers.Default
}
