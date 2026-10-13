package dev.pampa.mycloudgallery.core.metadata.testing

import android.media.MediaDataSource
import dev.pampa.mycloudgallery.core.metadata.contract.ExifDetails
import dev.pampa.mycloudgallery.core.metadata.contract.ExifDetailsReader
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractCode
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractHints
import dev.pampa.mycloudgallery.core.metadata.contract.Extraction
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataExtractor
import dev.pampa.mycloudgallery.core.metadata.contract.VideoFacts
import dev.pampa.mycloudgallery.core.metadata.contract.VideoProbe

// I fake di core-metadata (D23): in src/main, cosi' i moduli a valle (core-mediastore, feature-*) li riusano nei
// loro test senza cicli. Mai nella release: tools/check.ps1 fallisce se una classe di un package `testing`
// compare nel dex dell'APK di release.

/**
 * Un [MediaBytes] su un `ByteArray`: la sorgente dei test su JVM. Si comporta come un file: intervalli oltre la fine
 * si accorciano, intervalli vuoti danno un array vuoto. Conta le letture per i test che vogliono provare che
 * l'estrattore guarda solo testa e coda (E-P4: "nessuna lettura a file interi").
 */
class ByteArrayMediaBytes(private val bytes: ByteArray) : MediaBytes {

  /** Quanti byte sono stati letti in totale. */
  var bytesRead: Long = 0
    private set

  /** Quante letture. */
  var reads: Int = 0
    private set

  override val size: Long get() = bytes.size.toLong()

  override suspend fun read(range: LongRange): ByteArray {
    val chunk = slice(range)
    reads++
    bytesRead += chunk.size
    return chunk
  }

  override fun dataSource(): MediaDataSource = object : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
      if (position >= bytes.size) return -1
      val n = minOf(size.toLong(), bytes.size - position).toInt()
      System.arraycopy(bytes, position.toInt(), buffer, offset, n)
      return n
    }

    override fun getSize(): Long = bytes.size.toLong()

    override fun close() = Unit
  }

  private fun slice(range: LongRange): ByteArray {
    if (range.isEmpty() || range.first >= bytes.size) return ByteArray(0)
    val from = maxOf(range.first, 0L).toInt()
    val to = minOf(range.last, bytes.size - 1L).toInt()
    return bytes.copyOfRange(from, to + 1)
  }
}

/**
 * Un estrattore a copione: per ogni nome di file restituisce l'esito scelto dal test; per gli altri
 * [default] (un file che non e' un media). Registra le chiamate, senza i nomi nel `toString`.
 */
class FakeMetadataExtractor(
  private val byName: MutableMap<String, Extraction> = mutableMapOf(),
  var default: Extraction = Extraction.NotMedia(ExtractCode.NOT_A_MEDIA_FILE),
) : MetadataExtractor {

  /** Quante estrazioni sono state chieste. */
  var calls: Int = 0
    private set

  fun script(displayName: String, extraction: Extraction) {
    byName[displayName] = extraction
  }

  override suspend fun extract(bytes: MediaBytes, hints: ExtractHints): Extraction {
    calls++
    return byName[hints.displayName] ?: default
  }
}

/** Dettagli EXIF scelti dal test (uno solo per tutti i file). */
class FakeExifDetailsReader(var details: ExifDetails = ExifDetails()) : ExifDetailsReader {
  override suspend fun exifDetails(bytes: MediaBytes): ExifDetails = details
}

/** Una sonda video che risponde sempre [facts] (o null). */
class FakeVideoProbe(var facts: VideoFacts? = null) : VideoProbe {
  override fun probe(bytes: MediaBytes): VideoFacts? = facts
}
