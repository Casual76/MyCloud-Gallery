package dev.pampa.mycloudgallery.nas

import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasRandomFile
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Il lettore del NAS di "Scarica sul telefono" (R8 della revisione 4A; D20, §2.6 "con l'impronta"): la copia si lega alla
 * versione del catalogo solo se i byte sono quelli di quella versione.
 *
 * - **All'apertura** il timbro del file (dimensione, data, `fileId` se noto) deve essere quello del catalogo: un file cambiato
 *   sul posto dopo l'ultima verifica (dal PC, o da un dispositivo compromesso, anche con la stessa dimensione) non si scarica
 *   finche' la scansione non lo rivede. Per un file nato solo dall'indice il catalogo ha il timbro del record: se il file vero
 *   e' diverso (un record con una dimensione piccola per un file enorme) non si scarica, quindi il consenso sui dati mobili,
 *   chiesto con la dimensione del catalogo, resta quello giusto.
 * - **Alla fine** testa e coda dei byte consegnati devono dare l'impronta rapida della versione (`qh1`) e, se il catalogo
 *   conosce l'hash completo, lo SHA-256 deve essere quello. Se no la lettura fallisce e il file non compare (il
 *   `PhoneMediaWriter` toglie la riga sospesa).
 *
 * Un file che non torna si presenta come [NasError.NotFound] (il media visto non c'e' piu' cosi'): "non trovato" in "Scarica",
 * da riprovare dopo la prossima verifica. Gli altri usi del lettore (viewer, video) non passano di qui.
 */
class CatalogCheckedReader(
  private val delegate: NasMediaReader,
  private val expected: suspend (share: String, path: SharePath) -> CatalogExpectation?,
) : NasMediaReader by delegate {

  constructor(delegate: NasMediaReader, locations: CatalogNasLocations) : this(delegate, { share, path -> locations.expected(share, path) })

  private val pending: MutableMap<NasRandomFile, CatalogExpectation> = Collections.synchronizedMap(IdentityHashMap())

  override suspend fun open(share: String, path: SharePath, purpose: Purpose): NasResult<NasRandomFile> {
    val result = delegate.open(share, path, purpose)
    val file = (result as? NasResult.Ok)?.value ?: return result
    val want = try {
      expected(share, path)
    } catch (e: kotlinx.coroutines.CancellationException) {
      file.close()
      throw e
    } catch (e: Exception) {
      null
    }
    if (want == null || !file.stamp.sameAs(want.stamp) || file.size != want.stamp.size) {
      file.close()
      return NasResult.Err(NasError.NotFound)
    }
    pending[file] = want
    return result
  }

  override suspend fun readAll(file: NasRandomFile, sink: (ByteArray, Int) -> Unit, progress: ((Long) -> Unit)?): NasResult<Sha256> {
    val want = pending.remove(file) ?: return delegate.readAll(file, sink, progress)
    val capture = HeadTail(file.size)
    val result = delegate.readAll(
      file,
      { buffer, length ->
        capture.accept(buffer, length)
        sink(buffer, length)
      },
      progress,
    )
    if (result !is NasResult.Ok) return result
    if (capture.quickHash() != want.quickHash) return NasResult.Err(NasError.NotFound)
    if (want.fullHash != null && want.fullHash != result.value) return NasResult.Err(NasError.NotFound)
    return result
  }

  /** Testa e coda (64 KiB l'una, o tutto fino a 128 KiB) dei byte consegnati in ordine, per l'impronta rapida `qh1`. */
  private class HeadTail(private val size: Long) {
    private val regions = QuickHashAlgorithm.regions(size)
    private val head = ByteArray(regions.getOrNull(0)?.let { (it.last - it.first + 1).toInt() } ?: 0)
    private val tail = ByteArray(regions.getOrNull(1)?.let { (it.last - it.first + 1).toInt() } ?: 0)
    private val tailStart = regions.getOrNull(1)?.first ?: Long.MAX_VALUE
    private var offset = 0L
    private var seen = 0L

    fun accept(buffer: ByteArray, length: Int) {
      copyInto(head, 0L, buffer, length)
      if (tail.isNotEmpty()) copyInto(tail, tailStart, buffer, length)
      offset += length
      seen += length
    }

    /** Copia la parte di questo blocco ([offset], [length]) che cade in [target], che comincia a [start] del file. */
    private fun copyInto(target: ByteArray, start: Long, buffer: ByteArray, length: Int) {
      val from = maxOf(offset, start)
      val to = minOf(offset + length, start + target.size)
      if (from >= to) return
      System.arraycopy(buffer, (from - offset).toInt(), target, (from - start).toInt(), (to - from).toInt())
    }

    fun quickHash() = if (seen != size) null else QuickHashAlgorithm.compute(size, head, tail)
  }
}
