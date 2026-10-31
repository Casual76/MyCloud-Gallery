package dev.pampa.mycloudgallery.core.release.testing

import dev.pampa.mycloudgallery.core.release.contract.BlockLevel
import dev.pampa.mycloudgallery.core.release.contract.ManifestLimits
import dev.pampa.mycloudgallery.core.release.contract.ModelHint
import dev.pampa.mycloudgallery.core.release.contract.ReleaseManifest
import dev.pampa.mycloudgallery.core.release.contract.ReleaseMessage
import dev.pampa.mycloudgallery.core.release.contract.VersionBlock
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/**
 * Manifest casuali ma validi (§5.2): blocchi `WRITES` e `ALL` su intervalli di `versionCode` vicini a quelli delle prove, versione minima a
 * volte, indirizzi aggiuntivi per due modelli finti, scadenze fra 1 e 180 giorni. Deterministici dal seme. Per le storie casuali della suite
 * `ReleaseStateContract` e delle proprieta' di P1.
 */
class SyntheticManifests(seed: Long) {
  private val random = Random(seed)

  /** Un manifest con [seq], emesso a [issued]. */
  fun next(seq: Long, issued: Instant, app: String = ManifestLimits.APP_ID): ReleaseManifest {
    val blocks = List(random.nextInt(4)) {
      val from = 1 + random.nextInt(12)
      VersionBlock(from, from + random.nextInt(4), BlockLevel.entries.random(random), ReleaseMessage.entries.random(random))
    }
    val models = if (random.nextBoolean()) emptyList() else listOf(
      ModelHint(MODEL_A, SHA_A, listOf("https://huggingface.co/a/${random.nextInt(100)}")),
      ModelHint(MODEL_B, SHA_B, listOf("https://cdn-lfs.hf.co/b/${random.nextInt(100)}", "https://huggingface.co/b/x")),
    ).take(1 + random.nextInt(2))
    return ReleaseManifest(
      schema = ManifestLimits.SCHEMA, app = app, seq = seq, issued = issued,
      expires = issued.plus(Duration.ofDays(1L + random.nextInt(180))),
      minVersionCode = if (random.nextInt(3) == 0) 1 + random.nextInt(12) else null,
      blocks = blocks, models = models,
    )
  }

  companion object {
    const val MODEL_A: String = "model-a"
    const val MODEL_B: String = "model-b"
    const val SHA_A: String = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    const val SHA_B: String = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  }
}
