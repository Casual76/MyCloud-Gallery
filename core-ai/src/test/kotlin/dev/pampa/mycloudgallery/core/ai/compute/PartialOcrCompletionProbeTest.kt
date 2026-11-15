package dev.pampa.mycloudgallery.core.ai.compute

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.AiScope
import dev.pampa.mycloudgallery.core.ai.contract.OcrImage
import dev.pampa.mycloudgallery.core.ai.contract.OcrResult
import dev.pampa.mycloudgallery.core.ai.contract.TextReader
import dev.pampa.mycloudgallery.core.metadata.testing.ByteArrayMediaBytes
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOpen
import dev.pampa.mycloudgallery.core.prepare.contract.PrepareSource
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Controlled failure injected into the real compute engine; no physical-device or incident claim. */
class PartialOcrCompletionProbeTest {
  @Test fun aSavedVectorWithFailedOcrMustNotPublishACompletedMediaEvent() = runTest {
    val family = TestFamily()
    val device = family.anna(reading = true)
    val key = family.nas.newKey()
    device.phoneMedia[key] = MediaKind.PHOTO to "receipt"
    val asked = linkedSetOf(key)
    var failOcr = true
    val reader = object : TextReader {
      override suspend fun read(image: OcrImage): OcrResult {
        if (failOcr) throw AiEngineException(AiEngineError.OUT_OF_MEMORY)
        return OcrResult(emptyList(), dense = false)
      }
    }
    val parts = AiComputeParts(
      roles = device.roles,
      units = device.units,
      priority = NoPriority,
      candidates = device.candidates,
      buffer = device.buffer,
      fetcher = device.fetcher,
      doneByOthers = SimDone(family.nas, device.id),
      publisher = device.publisher,
      results = device.results,
      images = device.images,
      reader = { reader },
      inputs = SimInputs(),
      urgent = object : AiUrgentKeys {
        override fun urgent(): Set<ContentKey> = asked.toSet()
        override fun done(key: ContentKey) { asked.remove(key) }
      },
      preparer = device.preparer,
      phone = AiPhoneMedia { _, kind ->
        PrepOpen.Ready(PrepareSource.Bytes(ByteArrayMediaBytes("receipt".toByteArray()), kind, null)) {}
      },
    )
    val engine = AiComputeEngine(parts)
    assertThat(AiBacklog(device.roles, device.candidates).count().items).isEqualTo(1L)
    engine.run(AiScope.PhoneOnly, Duration.INFINITE)
    val partialSaved = device.results.saved(key, device.recipe.version)
    val partialPending = AiBacklog(device.roles, device.candidates).count().items
    val partialAnalyzed = engine.live.value.analyzed
    val partialShownAsRecent = key in engine.live.value.recent
    val partialRequestRetained = key in asked
    assertThat(partialSaved).contains(AiClass.VEC)
    assertThat(partialSaved).doesNotContain(AiClass.OCR)
    assertThat(partialPending).isEqualTo(1L)
    // Recovery must reuse the durable vector and finish just the missing OCR.
    failOcr = false
    engine.run(AiScope.PhoneOnly, Duration.INFINITE)
    assertThat(device.results.saved(key, device.recipe.version)).containsAtLeast(AiClass.VEC, AiClass.OCR)
    assertThat(device.embedded).isEqualTo(1)
    assertThat(AiBacklog(device.roles, device.candidates).count().items).isEqualTo(0L)
    println("partialPending=$partialPending partialAnalyzed=$partialAnalyzed partialShownAsRecent=$partialShownAsRecent partialRequestRetained=$partialRequestRetained totalAnalyzed=${engine.live.value.analyzed}")
    assertThat(partialAnalyzed).isEqualTo(0L)
    assertThat(partialShownAsRecent).isFalse()
    assertThat(partialRequestRetained).isTrue()
    assertThat(asked).isEmpty()
    assertThat(engine.live.value.analyzed).isEqualTo(1L)
  }
}
