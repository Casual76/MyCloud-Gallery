package dev.pampa.mycloudgallery.core.release.codec

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.release.contract.BlockLevel
import dev.pampa.mycloudgallery.core.release.contract.ManifestLimits
import dev.pampa.mycloudgallery.core.release.contract.ModelHint
import dev.pampa.mycloudgallery.core.release.contract.ReleaseManifest
import dev.pampa.mycloudgallery.core.release.contract.ReleaseMessage
import dev.pampa.mycloudgallery.core.release.contract.Verified
import dev.pampa.mycloudgallery.core.release.contract.VersionBlock
import dev.pampa.mycloudgallery.core.release.testing.ReleaseTestSigner
import java.time.Duration
import java.time.Instant
import org.junit.Test

/**
 * Fase 10 (P9, `docs/fase10-progetto.md` §5.4, "JVM (a ogni `check.ps1`)"): **la verifica di un manifest firmato in meno di 5 ms** (busta `MCGR1`: lunghezze, JSON canonico,
 * firma ECDSA P-256 contro le chiavi fissate, scadenza, catalogo chiuso). Il manifest e' il piu' grande che il tetto ammette in pratica (blocchi e suggerimenti di modelli
 * al limite): il caso peggiore, non il caso tipico. Misura: la **mediana** di molte verifiche dopo il riscaldamento della JVM (una corsa sola puo' prendersi un GC sotto il
 * carico di `check.ps1`), con il tetto del §5.4.
 */
class SignedManifestVerifierPerformanceTest {

  private val signer = ReleaseTestSigner()
  private val verifier = SignedManifestVerifier(signer.trust())
  private val issued = Instant.parse("2026-10-01T00:00:00Z")

  private fun biggest(): ReleaseManifest {
    val hints = (1..ManifestLimits.MAX_MODELS).map { n ->
      ModelHint("model-$n", n.toString(16).padStart(64, '0'), listOf("https://huggingface.co/a/$n", "https://cdn-lfs.hf.co/a/$n"))
    }
    val blocks = (1..ManifestLimits.MAX_BLOCKS).map { n -> VersionBlock(n * 10, n * 10 + 5, BlockLevel.WRITES, ReleaseMessage.SECURITY) }
    return ReleaseManifest(1, ManifestLimits.APP_ID, 3, issued, issued.plus(Duration.ofDays(90)), 2, blocks, hints)
  }

  @Test
  fun laVerificaDiUnManifestSottoCinqueMillisecondi() {
    val sealed = signer.seal(biggest())
    assertThat(sealed.size).isAtMost(ManifestLimits.MAX_BYTES)
    assertThat(verifier.verify(sealed, ManifestLimits.APP_ID)).isInstanceOf(Verified.Ok::class.java)
    // Il riscaldamento della JVM (compilazione del codice di firma incluso): lo stesso lavoro, non misurato.
    repeat(WARMUP) { assertThat(verifier.verify(sealed, ManifestLimits.APP_ID)).isInstanceOf(Verified.Ok::class.java) }
    val times = LongArray(RUNS)
    for (run in 0 until RUNS) {
      val start = System.nanoTime()
      val result = verifier.verify(sealed, ManifestLimits.APP_ID)
      times[run] = System.nanoTime() - start
      assertThat(result).isInstanceOf(Verified.Ok::class.java)
    }
    times.sort()
    val medianMs = times[RUNS / 2] / 1_000_000.0
    val worstMs = times.last() / 1_000_000.0
    // La misura resta nel rapporto della prova (docs/decisioni.md, Fase 10 P9).
    println("verifica di un manifest di ${sealed.size} byte: mediana $medianMs ms, la peggiore $worstMs ms (tetto $LIMIT_MS ms)")
    assertWithMessage("verifica di un manifest di %s byte: mediana %s ms (la peggiore %s ms), tetto %s ms", sealed.size, medianMs, worstMs, LIMIT_MS).that(medianMs).isLessThan(LIMIT_MS)
  }

  private companion object {
    const val WARMUP = 400
    const val RUNS = 301
    const val LIMIT_MS = 5.0
  }
}
