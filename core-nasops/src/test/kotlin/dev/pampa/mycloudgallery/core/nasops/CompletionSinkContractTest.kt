package dev.pampa.mycloudgallery.core.nasops

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nasops.contract.MoveCompletionSink
import dev.pampa.mycloudgallery.core.nasops.contract.NasFileRef
import dev.pampa.mycloudgallery.core.nasops.contract.OpId
import dev.pampa.mycloudgallery.core.nasops.noop.NoMoveCompletionSink
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Fase 7 (P0, D29): la firma della porta di completamento. La chiamata nel motore (dopo `PUBLISHED`, prima di `SOURCE_RECORDED`, ritentata
 * finche' non riesce) e' di P4; qui il null-object (il motore come oggi) e la forma degli argomenti, che non finiscono nei log.
 */
class CompletionSinkContractTest {

  @Test
  fun ilNullObjectNonFaNienteENonFallisce() = runBlocking<Unit> {
    val source = NasFileRef("test", SharePath.of("Foto", "a.jpg"))
    val dest = NasFileRef("test-comune", SharePath.of("Album condivisi", "Oslo", "a.jpg"))
    NoMoveCompletionSink.published(OpId(TrustIds.random()), 0, source, dest, NasStamp(10, 1_760_000_000_000L), Sha256.of(ByteArray(3)))
    assertThat(source.toString()).doesNotContain("Foto")
    assertThat(dest.toString()).doesNotContain("Oslo")
  }

  /** Una porta che ricorda: due chiamate uguali (ripresa) sono la stessa cosa per chi la lega (idempotenza, D29). */
  @Test
  fun unaRipresaRichiamaLaPortaConGliStessiArgomenti() = runBlocking<Unit> {
    val seen = mutableSetOf<Pair<NasFileRef, NasFileRef>>()
    val sink = MoveCompletionSink { _, _, source, dest, _, _ -> seen += source to dest }
    val op = OpId(TrustIds.random())
    val source = NasFileRef("test", SharePath.of("Foto", "a.jpg"))
    val dest = NasFileRef("test-comune", SharePath.of("Album condivisi", "Oslo", "a.jpg"))
    repeat(2) { sink.published(op, 3, source, dest, NasStamp(10, 1_760_000_000_000L), Sha256.of(ByteArray(3))) }
    assertThat(seen).hasSize(1)
  }
}
