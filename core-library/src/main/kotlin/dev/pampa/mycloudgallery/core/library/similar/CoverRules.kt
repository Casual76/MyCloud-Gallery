package dev.pampa.mycloudgallery.core.library.similar

import dev.pampa.mycloudgallery.core.model.MediaId

// Fase 5A, P15 (docs/fase5-progetto.md §2.12, "Quale si vede"): la copertina di un gruppo di copie simili, regola pura.

/**
 * Cio' che serve a scegliere la copertina di una copia. Nessun nome di file.
 *
 * @property pixels larghezza per altezza (risoluzione per un video); 0 se non nota (perde contro chi la conosce).
 * @property bytes il peso del file.
 * @property format il formato in forma canonica ([CoverRules.formatOf]); due copie dello stesso formato si confrontano per compressione.
 * @property bitrate il bitrate di un video (bit al secondo), o null.
 * @property cameraOriginal la copia viene dal telefono o ha i dati del produttore intatti (§2.12 regola 3).
 */
class CoverFacts(
  val id: MediaId,
  val pixels: Long,
  val bytes: Long,
  val format: String,
  val bitrate: Long?,
  val cameraOriginal: Boolean,
  val isVideo: Boolean,
) {
  /** Compressione: byte per pixel di una foto, bitrate di un video (piu' alto = meno compressione). Null se non si sa. */
  val density: Double?
    get() = if (isVideo) bitrate?.toDouble() else if (pixels > 0) bytes.toDouble() / pixels else null

  override fun toString(): String = "CoverFacts($pixels px, $bytes B, original=$cameraOriginal)"
}

/**
 * La regola della copertina, nell'ordine del §2.12:
 * 1. **piu' pixel** (video: risoluzione);
 * 2. a parita' di formato, **meno compressione**: piu' byte per pixel (video: bitrate piu' alto);
 * 3. a formati diversi, **l'originale della fotocamera**: una conversione non puo' avere piu' dettaglio della sua sorgente;
 * 4. il file piu' grande; poi l'identita' minore (stabile).
 *
 * Il confronto "a parita' di formato" non e' un ordine totale fra tutte le coppie (A batte B per compressione, B batte C per
 * originale, C batte A per peso: un giro), quindi non si ordina: si **sceglie** in tre passi, sempre uguali su ogni dispositivo.
 * Prima restano le copie con piu' pixel; poi il **formato vincente** (quello che ha l'originale della fotocamera, o, se ce ne sono
 * piu' d'uno, quello con l'originale piu' grande; senza originali, quello del file piu' grande); infine, dentro quel formato, la
 * copia meno compressa. La scelta manuale dell'utente ("Mostra questa come principale") vince su tutto: la applica chi chiama
 * ([choose] non la conosce).
 */
object CoverRules {

  /** La copertina fra [copies] (almeno una). */
  fun choose(copies: List<CoverFacts>): MediaId {
    require(copies.isNotEmpty()) { "Un gruppo ha almeno una copia." }
    // 1. Piu' pixel.
    val maxPixels = copies.maxOf { it.pixels }
    val sharp = copies.filter { it.pixels == maxPixels }
    if (sharp.size == 1) return sharp.single().id

    // 3. Il formato vincente (quando i formati sono diversi).
    val formats = sharp.map { it.format }.distinct()
    val winnerFormat = if (formats.size == 1) {
      formats.single()
    } else {
      val originals = sharp.filter { it.cameraOriginal }
      val pool = originals.ifEmpty { sharp }
      // Il file piu' grande fra gli originali (o fra tutti, senza originali), poi l'identita' minore.
      pool.minWith(compareByDescending<CoverFacts> { it.bytes }.thenBy { it.id.value }).format
    }

    // 2. Dentro il formato vincente, meno compressione; 4. il file piu' grande; poi l'identita' minore.
    val sameFormat = sharp.filter { it.format == winnerFormat }
    return sameFormat.minWith(
      compareByDescending<CoverFacts> { it.density ?: -1.0 }.thenByDescending { it.bytes }.thenBy { it.id.value },
    ).id
  }

  /**
   * Il formato in forma canonica dal tipo MIME: minuscolo, senza parametri, con i sinonimi riuniti (`image/jpg` = `image/jpeg`,
   * `image/heif` = `image/heic`). Un tipo ignoto vale "?" (due ignoti si confrontano come stesso formato).
   */
  fun formatOf(mime: String?): String {
    val base = mime?.substringBefore(';')?.trim()?.lowercase() ?: return UNKNOWN_FORMAT
    return when (base) {
      "", "application/octet-stream" -> UNKNOWN_FORMAT
      "image/jpg", "image/pjpeg" -> "image/jpeg"
      "image/heif", "image/heic-sequence", "image/heif-sequence" -> "image/heic"
      "video/x-m4v", "video/quicktime" -> "video/mp4"
      else -> base
    }
  }

  const val UNKNOWN_FORMAT: String = "?"
}
