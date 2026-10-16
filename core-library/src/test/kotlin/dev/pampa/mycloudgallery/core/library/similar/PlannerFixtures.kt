package dev.pampa.mycloudgallery.core.library.similar

import dev.pampa.mycloudgallery.core.database.SimilarCandidate
import dev.pampa.mycloudgallery.core.database.SimilarRun
import dev.pampa.mycloudgallery.core.model.MediaGroupId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.VisualSignature
import java.util.Random

// Fase 5A, P15: fixture del pianificatore e del rilevatore senza immagini vere. Un "disegno" e' una griglia di blocchi colorati a caso
// (con struttura da vendere): la sua firma e' uguale a quella di una sua "copia" con un po' di rumore, e lontana da quella di un altro disegno.

/** Un disegno: blocchi di 8 x 8 pixel di colori scelti da [seed], 512 x 384 pixel (8 x 6 pixel per cella della firma). */
internal fun pattern(seed: Long, width: Int = 512, height: Int = 384): Raster {
  val rnd = Random(seed)
  val blocksX = (width + 7) / 8
  val blocksY = (height + 7) / 8
  val colors = IntArray(blocksX * blocksY) { rnd.nextInt(0x1000000) }
  return Raster(width, height, IntArray(width * height) { i -> colors[(i / width / 8) * blocksX + (i % width) / 8] })
}

/** La stessa immagine con un rumore di +-[amount] livelli per canale (la ricompressione). */
internal fun jitter(src: Raster, amount: Int, seed: Long): Raster {
  val rnd = Random(seed)
  val out = IntArray(src.width * src.height) { i ->
    val p = src.pixel(i)
    fun ch(shift: Int) = (((p shr shift) and 0xFF) + rnd.nextInt(amount * 2 + 1) - amount).coerceIn(0, 255)
    (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
  }
  return Raster(src.width, src.height, out)
}

internal fun signatureOf(seed: Long, noise: Int = 0, noiseSeed: Long = 0): VisualSignature =
  VisualSignatures.of(if (noise == 0) pattern(seed) else jitter(pattern(seed), noise, noiseSeed))

/** Un candidato di prova. */
internal fun candidate(
  id: MediaId = MediaId.random(),
  version: VersionId = VersionId.random(),
  kind: MediaKind = MediaKind.PHOTO,
  takenAt: Long = SECOND * 1000,
  aspect: Int = 1333,
  duration: Long? = null,
  group: MediaGroupId? = null,
): SimilarCandidate = SimilarCandidate(id, version, kind, takenAt, aspect, duration, group, signatureAlgorithm = null)

internal const val SECOND: Long = 1_760_000_000L

internal fun runOf(vararg candidates: SimilarCandidate): SimilarRun = SimilarRun(candidates.first().second, candidates.toList())

internal fun signaturesOf(vararg pairs: Pair<SimilarCandidate, VisualSignature?>): Map<VersionId, VisualSignature> =
  pairs.mapNotNull { (c, s) -> s?.let { c.versionId to it } }.toMap()
