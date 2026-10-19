package dev.pampa.mycloudgallery.core.metadata.write

import android.media.MediaDataSource
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractHints
import dev.pampa.mycloudgallery.core.metadata.contract.Extraction
import dev.pampa.mycloudgallery.core.metadata.contract.GeoChange
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataChange
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataExtractor
import dev.pampa.mycloudgallery.core.metadata.extract.PreviewDecoder
import dev.pampa.mycloudgallery.core.model.MediaMetadata
import dev.pampa.mycloudgallery.core.model.StableCode
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.time.ZoneId
import kotlin.math.abs

// Fase 5B, P8 (docs/fase5-progetto.md §2.4 "Correzioni dall'app" passo 4, D19, §6.1 "Un editor esterno che restituisce un file ostile o
// rovinato"): una copia corretta **si rilegge e si decodifica prima di valere**. Non ci si fida dello scrittore (ne' di `ExifInterface`, ne'
// di un editor esterno): la copia si legge con l'estrattore dell'app (lo stesso che legge il telefono e il NAS) e deve dire i valori chiesti,
// poi si decodifica un'anteprima piccola con il decodificatore del sistema. Un file rovinato dalla scrittura non passa e si scarta: il file
// del telefono e quello del NAS non sono ancora stati toccati.

/** L'esito della rilettura di una copia corretta. Solo codici. */
enum class WrittenCheck(override val code: String) : StableCode {
  /** Si legge, dice i valori chiesti e si decodifica: la copia vale. */
  VALID("valid"),

  /** L'estrattore non la legge come immagine (troncata, falsa, rovinata). */
  UNREADABLE("unreadable"),

  /** Si legge, ma non dice i valori chiesti (la scrittura non e' arrivata, o ha scritto altro). */
  CHANGE_MISSING("change_missing"),

  /** I metadati tornano ma l'immagine non si decodifica: i pixel sono rovinati. */
  NOT_DECODABLE("not_decodable"),
}

/** La rilettura di una copia corretta (D19): la usano le correzioni di core-backup prima di riscrivere il telefono o caricare la versione. */
fun interface WrittenFileCheck {
  /** [file] di tipo [mime] ha la correzione [change] e si decodifica? */
  suspend fun check(file: File, mime: String, change: MetadataChange): WrittenCheck
}

/**
 * La rilettura vera: l'estrattore dell'app ([extractor], `DefaultMetadataExtractor`) e il decodificatore delle anteprime ([decoder],
 * `AndroidPreviewDecoder`, mai la bitmap intera in memoria). [zone] e' il fuso con cui leggere una data senza fuso (non serve per le
 * correzioni, che lo scrivono sempre). Non lancia: un file che non si legge e' un esito.
 */
class ExtractingFileCheck(
  private val extractor: MetadataExtractor,
  private val decoder: PreviewDecoder,
  private val zone: ZoneId,
  private val maxSide: Int = PREVIEW_SIDE,
) : WrittenFileCheck {

  override suspend fun check(file: File, mime: String, change: MetadataChange): WrittenCheck {
    val bytes = try {
      FileMediaBytes(file)
    } catch (_: IOException) {
      return WrittenCheck.UNREADABLE
    }
    return bytes.use {
      val read = extractor.extract(bytes, ExtractHints(mime = mime, displayName = file.name, relativePath = "", scanZone = zone))
        as? Extraction.Read ?: return@use WrittenCheck.UNREADABLE
      if (read.kind.isVideo) return@use WrittenCheck.UNREADABLE
      if (!carries(read.metadata, change)) return@use WrittenCheck.CHANGE_MISSING
      if (decoder.decode(bytes, maxSide) == null) return@use WrittenCheck.NOT_DECODABLE
      WrittenCheck.VALID
    }
  }

  companion object {
    /** Un'anteprima piccola basta a dire che i pixel si decodificano. */
    const val PREVIEW_SIDE: Int = 64

    /** ~1 m. */
    private const val GEO_TOLERANCE = 1e-5

    /** I metadati letti dicono la correzione [change]: data (al secondo) e fuso, luogo (tolto o messo), orientamento. */
    fun carries(metadata: MediaMetadata, change: MetadataChange): Boolean {
      change.takenAtUtcMillis?.let { utc ->
        val time = metadata.captureTime ?: return false
        if (abs(time.utcMillis - utc) >= 1_000) return false
        if (change.offsetMinutes != null && time.offsetMinutes != change.offsetMinutes) return false
      }
      when (val geo = change.geo) {
        GeoChange.Keep -> Unit
        GeoChange.Clear -> if (metadata.location != null) return false
        is GeoChange.Set -> {
          val at = metadata.location ?: return false
          if (abs(at.latitude - geo.point.latitude) >= GEO_TOLERANCE || abs(at.longitude - geo.point.longitude) >= GEO_TOLERANCE) return false
        }
      }
      change.orientation?.let { if (metadata.orientation != it) return false }
      return true
    }
  }
}

/** I byte di un file locale a intervalli (la copia corretta), per l'estrattore e il decodificatore. Si chiude dopo l'uso. */
internal class FileMediaBytes(file: File) : MediaBytes, AutoCloseable {
  private val raf = RandomAccessFile(file, "r")
  private val lock = Any()

  override val size: Long = raf.length()

  override suspend fun read(range: LongRange): ByteArray = synchronized(lock) {
    if (range.isEmpty() || range.first >= size || range.last < 0) return ByteArray(0)
    val from = maxOf(range.first, 0L)
    val to = minOf(range.last, size - 1)
    val out = ByteArray((to - from + 1).toInt())
    raf.seek(from)
    raf.readFully(out)
    out
  }

  override fun dataSource(): MediaDataSource = object : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = synchronized(lock) {
      if (position >= this@FileMediaBytes.size) return -1
      val n = minOf(size.toLong(), this@FileMediaBytes.size - position).toInt()
      raf.seek(position)
      raf.readFully(buffer, offset, n)
      n
    }

    override fun getSize(): Long = this@FileMediaBytes.size

    override fun close() = Unit
  }

  override fun close() = raf.close()
}
