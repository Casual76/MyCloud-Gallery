package dev.pampa.mycloudgallery.search

import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.MediaText
import dev.pampa.mycloudgallery.core.search.contract.NormBox
import dev.pampa.mycloudgallery.core.search.contract.TextLayout
import dev.pampa.mycloudgallery.core.search.contract.TextLine
import dev.pampa.mycloudgallery.core.search.contract.TextWord
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.AiLimits
import dev.pampa.mycloudgallery.core.trust.data.AiSegments
import dev.pampa.mycloudgallery.core.trust.data.OcrBody
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Le RIGHE del testo letto per il testo dal vivo (D43, P12): l'indice locale di P6 tiene solo il testo normalizzato (per l'FTS4), non
 * riquadri, inclinazione ed estremi delle parole. Qui, per chiave di contenuto, il corpo `ocr` cosi' com'e' (codificato e riletto con i
 * controlli di chi legge, `AiSegments`): lo scrivono il calcolo di questo dispositivo e la replica delle teste `ai` (lo stesso corpo
 * che vince nell'indice), lo leggono il viewer e l'eventuale "Documenti" (D34).
 *
 * - Un file per chiave (`<hex>.ocr`), scritto in un temporaneo e rinominato; un corpo che non si rilegge e' come assente.
 * - Mai il testo nei log (D40). La cartella sta sotto `noBackupFilesDir/search/`: l'azzeramento la toglie con l'indice.
 * - [has] distingue "mai letto" (null) da "letto, nessuna riga" (D42, E-V9): il secondo e' un corpo con zero righe.
 */
class OcrLayoutStore(private val dir: File) {

  suspend fun put(key: ContentKey, body: OcrBody) = withContext(Dispatchers.IO) {
    val bytes = AiSegments.bodyOrNull(body) ?: return@withContext
    val target = fileOf(key)
    val tmp = File(dir, target.name + ".tmp")
    try {
      dir.mkdirs()
      tmp.outputStream().use { out ->
        out.write(bytes)
        out.fd.sync()
      }
      if (!tmp.renameTo(target)) {
        target.delete()
        if (!tmp.renameTo(target)) tmp.delete()
      }
    } catch (e: IOException) {
      tmp.delete()
    }
  }

  suspend fun get(key: ContentKey): OcrBody? = withContext(Dispatchers.IO) {
    val file = fileOf(key)
    if (!file.isFile || file.length() > MAX_BYTES) return@withContext null
    val bytes = try {
      file.readBytes()
    } catch (e: IOException) {
      return@withContext null
    }
    AiSegments.decodeBody(AiClass.OCR, bytes).acceptedOrNull() as? OcrBody
  }

  suspend fun remove(keys: Collection<ContentKey>) = withContext(Dispatchers.IO) {
    keys.forEach { fileOf(it).delete() }
  }

  fun clear() {
    dir.listFiles()?.forEach { it.delete() }
  }

  private fun fileOf(key: ContentKey) = File(dir, "${key.hex}.ocr")

  private companion object {
    const val MAX_BYTES = 128L * 1024
  }
}

/**
 * Il testo letto di un media per il viewer (contratto `MediaText`, P0): la cella del catalogo dice le sue chiavi di contenuto (la
 * versione corrente prima, poi le precedenti: E-A10), la prima con un corpo `ocr` vale. Null = mai letto; zero righe = letto, niente.
 */
class StoredMediaText(
  private val catalog: suspend () -> MediaCatalog?,
  private val layouts: OcrLayoutStore,
) : MediaText {
  override suspend fun layout(media: MediaId): TextLayout? {
    val cat = catalog() ?: return null
    val cell = cat.cellContentKeysOf(listOf(media)).firstOrNull { it.mediaId == media } ?: return null
    for (key in cell.own) {
      val body = layouts.get(key) ?: continue
      return toLayout(body)
    }
    return null
  }

  companion object {
    private const val UNIT = AiLimits.UNIT.toFloat()

    /** Le righe del formato (`ShortBox` e `WordSpan` in 1/65535) come righe del contratto (0..1). */
    fun toLayout(body: OcrBody): TextLayout = TextLayout(
      body.lines.map { line ->
        TextLine(
          box = NormBox(line.box.x0 / UNIT, line.box.y0 / UNIT, line.box.x1 / UNIT, line.box.y1 / UNIT),
          angleDegrees = line.angle,
          words = line.words.map { TextWord(it.start / UNIT, it.end / UNIT) },
          text = line.text,
        )
      },
    )
  }
}
