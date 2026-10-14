package dev.pampa.mycloudgallery.core.prepare.photo

import android.graphics.Bitmap
import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.core.prepare.contract.PrepFailCode
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOutput
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass

/** Le immagini ricavate da una bitmap dritta e il segnaposto: la parte comune a foto e video (la copertina). */
internal class Derived(val images: List<PrepOutput.Image>, val thumbHash: ThumbHash)

internal object ImageDerivation {

  /**
   * Le uscite d'immagine di [wanted] dalla bitmap dritta [decoded], dalla piu' grande alla piu' piccola, ognuna ridotta
   * dalla precedente (un solo passaggio sull'originale). [decoded] e le intermedie si liberano nel registro; [sizes] dice la
   * taglia di ogni classe ([OutputPlan.sizeFor]). Un'uscita che non entra nel tetto del blob e' `TOO_LARGE` (ma non
   * succede: la qualita' scende finche' entra).
   */
  fun derive(
    decoded: Bitmap,
    wanted: List<SegmentClass>,
    sizes: Map<SegmentClass, Pair<Int, Int>>,
    ledger: BitmapLedger,
  ): Derived {
    val byAreaDescending = wanted.sortedByDescending { sizes.getValue(it).first.toLong() * sizes.getValue(it).second }
    val images = HashMap<SegmentClass, PrepOutput.Image>()
    var current = decoded
    for (cls in byAreaDescending) {
      val (w, h) = within(sizes.getValue(cls), current)
      val scaled = ImageOps.scaleTo(current, w, h, ledger)
      if (scaled !== current) ledger.free(current)
      current = scaled
      // Il JPEG non ha trasparenza: l'analisi si compone sul bianco; il WebP la conserva.
      val flat = if (cls == SegmentClass.ANALYSIS) ImageOps.flattenOnWhite(current, ledger) else current
      val quality = OutputPlan.qualityOf(cls)
      val encoded = if (cls == SegmentClass.ANALYSIS) {
        ImageOps.encodeJpeg(flat, quality, TrustLimits.MAX_BLOB_BYTES)
      } else {
        ImageOps.encodeWebp(flat, quality, TrustLimits.MAX_BLOB_BYTES)
      }
      if (flat !== current) ledger.free(flat)
      if (encoded == null) throw abort(PrepFailCode.TOO_LARGE)
      images[cls] = PrepOutput.Image(cls, encoded, current.width, current.height)
    }
    val thumbHash = ImageOps.thumbHashOf(current, ledger)
    return Derived(OutputPlan.IMAGE_ORDER.mapNotNull { images[it] }, thumbHash)
  }

  /**
   * La taglia pianificata, ma mai piu' grande di [from]: se il tetto di pixel ha costretto la decodifica a una taglia piu'
   * piccola dell'HQ (memoria scarsa, `DecodePlanner`), l'HQ esce a quella taglia e non si ingrandisce una bitmap gia' ridotta.
   * La proporzione e' quella di [from].
   */
  private fun within(planned: Pair<Int, Int>, from: Bitmap): Pair<Int, Int> {
    val (w, h) = planned
    if (w <= from.width && h <= from.height) return planned
    val scale = minOf(from.width.toDouble() / w, from.height.toDouble() / h)
    return maxOf(1, Math.round(w * scale).toInt()).coerceAtMost(from.width) to maxOf(1, Math.round(h * scale).toInt()).coerceAtMost(from.height)
  }
}
