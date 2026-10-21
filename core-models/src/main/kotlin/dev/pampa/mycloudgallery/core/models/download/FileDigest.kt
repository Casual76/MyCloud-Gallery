package dev.pampa.mycloudgallery.core.models.download

import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.models.contract.ModelLimits
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

// Lo SHA-256 di un file letto per intero (D16). Sempre sullo STESSO canale che poi si consegna (verifica a ogni apertura) o che si
// promuove (verifica del file finito): niente "controllo il percorso e poi lo riapro". Si interrompe fra un blocco e l'altro se
// la coroutine e' annullata.

internal object FileDigest {
  private const val HEX = "0123456789abcdef"

  /** Lo SHA-256 dei primi [size] byte di [channel] (letti per posizione); meno di [size] byte letti = l'impronta di quelli. */
  suspend fun sha256(channel: FileChannel, size: Long): Sha256 {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteBuffer.allocateDirect(ModelLimits.BLOCK_BYTES)
    var position = 0L
    while (position < size) {
      coroutineContext.ensureActive()
      buffer.clear()
      buffer.limit(minOf(buffer.capacity().toLong(), size - position).toInt())
      val n = channel.read(buffer, position)
      if (n <= 0) break
      buffer.flip()
      digest.update(buffer)
      position += n
    }
    val out = StringBuilder(64)
    for (b in digest.digest()) {
      out.append(HEX[(b.toInt() ushr 4) and 0xf]).append(HEX[b.toInt() and 0xf])
    }
    return Sha256(out.toString())
  }
}
