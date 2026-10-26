package dev.pampa.mycloudgallery.search

import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.MediaText
import dev.pampa.mycloudgallery.core.search.contract.NormBox
import dev.pampa.mycloudgallery.core.search.contract.TextLayout
import dev.pampa.mycloudgallery.core.search.contract.TextLine
import dev.pampa.mycloudgallery.core.search.contract.TextWord
import dev.pampa.mycloudgallery.core.search.index.LocalAiIndex
import dev.pampa.mycloudgallery.core.trust.data.AiLimits
import dev.pampa.mycloudgallery.core.trust.data.OcrBody

/**
 * Il testo letto di un media per il viewer (contratto `MediaText`, P0; D43): il catalogo dice le chiavi di contenuto del media (la versione
 * corrente prima, poi le precedenti: E-A10), la prima con un testo che **vince nell'indice locale** vale. Null = mai letto; zero righe =
 * letto, niente.
 *
 * Revisione A:
 * - R4, VT-2: le righe vengono dall'indice ([LocalAiIndex.textBody]), con la stessa scelta della ricerca (prima la lettura di questo
 *   dispositivo, poi la ricetta nota piu' alta): non piu' l'ultimo corpo arrivato. Un testo ritirato, o di un dispositivo revocato, esce;
 * - VT-1, D29: le chiavi si leggono anche per i nascosti e i membri di un gruppo ([MediaCatalog.mediaContentKeysOf]): nel viewer dei
 *   Nascosti il testo dal vivo funziona. Nessun'altra superficie usa questa lettura.
 */
class StoredMediaText(
  private val catalog: suspend () -> MediaCatalog?,
  private val index: LocalAiIndex,
) : MediaText {
  override suspend fun layout(media: MediaId): TextLayout? {
    val cat = catalog() ?: return null
    val cell = cat.mediaContentKeysOf(listOf(media)).firstOrNull { it.mediaId == media } ?: return null
    for (key in cell.own) {
      val body = index.textBody(key) ?: continue
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
