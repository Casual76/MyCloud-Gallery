package dev.pampa.mycloudgallery.core.ai.compute

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiCandidate
import dev.pampa.mycloudgallery.core.ai.contract.AiCandidates
import dev.pampa.mycloudgallery.core.ai.contract.AiLive
import dev.pampa.mycloudgallery.core.ai.contract.AiScope
import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.testing.ProcessDeath
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ConveyorTruthTest {
  @Test fun missingPreviewsNeverClaimThatAnImageIsBeingAnalyzed() = runTest {
    val family = TestFamily()
    family.nas.add(TestFamily.COMMON, concepts = "not ready")
    family.nas.of(TestFamily.COMMON).previews.clear()
    val phone = family.anna()
    val states = ArrayList<AiStatus>()
    val watching = launch(Dispatchers.Unconfined) { phone.engine.status.collect { states += it } }
    val result = phone.engine.run(AiScope.Share(TestFamily.COMMON), Duration.INFINITE)
    watching.cancel()
    assertThat(result.waitingForPreparation).isGreaterThan(0)
    assertThat(phone.embedded).isEqualTo(0)
    assertThat(states.filterIsInstance<AiStatus.Running>()).isEmpty()
  }

  @Test fun publicationOfAnAlreadySavedResultNeverPretendsToAnalyzeItAgain() = runTest {
    val family = TestFamily()
    val key = family.nas.add(TestFamily.COMMON, concepts = "gia salvata")
    val phone = family.anna()
    phone.dieAfterSave = 1
    try { phone.engine.run(AiScope.Share(TestFamily.COMMON), Duration.INFINITE) } catch (_: ProcessDeath) { }
    assertThat(phone.embedded).isEqualTo(1)
    assertThat(phone.engine.live.value.current).isNull()
    phone.restart()
    val frames = ArrayList<AiLive>()
    val watching = launch(Dispatchers.Unconfined) { phone.engine.live.collect { frames += it } }
    phone.engine.run(AiScope.Share(TestFamily.COMMON), Duration.INFINITE)
    watching.cancel()
    assertThat(phone.embedded).isEqualTo(1)
    assertThat(family.published(TestFamily.COMMON, phone.id)).contains(key)
    assertThat(frames.mapNotNull { it.current }).isEmpty()
    assertThat(phone.engine.live.value.analyzed).isEqualTo(0L)
  }

  @Test fun cancellationClearsThePhotoBeingAnalyzed() = runTest {
    val family = TestFamily()
    val key = family.nas.add(TestFamily.COMMON, concepts = "interruzione")
    val phone = family.anna()
    phone.images.onImage = {
      assertThat(phone.engine.live.value.current).isEqualTo(key)
      throw CancellationException("interruzione della prova")
    }
    try { phone.engine.run(AiScope.Share(TestFamily.COMMON), Duration.INFINITE) } catch (_: CancellationException) { }
    assertThat(phone.engine.live.value.current).isNull()
    assertThat(phone.engine.live.value.next).isEmpty()
    assertThat(phone.engine.live.value.recent).isEmpty()
  }

  @Test fun aPhotoWithOnlyTextLeftIsStillOnePendingPhoto() = runTest {
    val family = TestFamily()
    val phone = family.anna(reading = true)
    val key = family.nas.newKey()
    val candidates = object : AiCandidates {
      override suspend fun forShare(share: String, recipe: Int, limit: Int) = emptyList<AiCandidate>()
      override suspend fun phoneOnly(recipe: Int, limit: Int) = listOf(AiCandidate(key, MediaKind.PHOTO, MediaSource.CAMERA, setOf(AiClass.OCR)))
    }
    val work = AiBacklog(phone.roles, candidates).count()
    assertThat(work.items).isEqualTo(1L)
    assertThat(work.photos).isEqualTo(0L)
    assertThat(work.textReads).isEqualTo(1L)
  }
}
