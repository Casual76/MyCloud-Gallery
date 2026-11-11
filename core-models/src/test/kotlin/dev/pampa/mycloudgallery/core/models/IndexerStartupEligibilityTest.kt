package dev.pampa.mycloudgallery.core.models

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.models.contract.*
import dev.pampa.mycloudgallery.core.models.support.ModelRig
import kotlinx.coroutines.runBlocking
import org.junit.Test

class IndexerStartupEligibilityTest {
  @Test fun rimozioneRestaSalvataFraDueProcessi() = runBlocking {
    val first = ModelRig()
    first.manager.remove(first.spec.id)
    repeat(2) {
      val next = ModelRig(dir = first.dir, bytes = first.bytes, spec = first.spec)
      next.models.refreshOnce()
      assertThat(next.models.needsRequest(first.spec.id, ModelTrigger.RULES)).isFalse()
      assertThat(next.models.needsRequest(first.spec.id, ModelTrigger.ROLE_CHANGED)).isTrue()
    }
  }
  @Test fun tuttiProntiNonRichiedonoNienteAncheAlSecondoAvvio() = runBlocking {
    val first = ModelRig()
    first.finalFile.writeBytes(first.bytes)
    repeat(2) {
      val next = ModelRig(dir = first.dir, bytes = first.bytes, spec = first.spec)
      next.models.refreshOnce()
      assertThat(next.models.needsRequest(first.spec.id, ModelTrigger.RULES)).isFalse()
    }
  }
}
