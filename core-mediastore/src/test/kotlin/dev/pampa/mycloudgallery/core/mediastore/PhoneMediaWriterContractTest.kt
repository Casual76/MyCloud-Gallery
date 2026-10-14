package dev.pampa.mycloudgallery.core.mediastore

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneSaveSource
import dev.pampa.mycloudgallery.core.mediastore.contract.SaveRequest
import dev.pampa.mycloudgallery.core.mediastore.contract.SaveResult
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaWriter
import dev.pampa.mycloudgallery.core.model.Sha256
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** "Scarica sul telefono" (Fase 4, P0): il contratto che P8 implementa e P11 usa (D20, E-L2). */
class PhoneMediaWriterContractTest {

  private val content = ByteArray(5000) { (it % 251).toByte() }
  private val source = PhoneSaveSource { sink -> content.asList().chunked(1024).forEach { sink(it.toByteArray(), it.size) } }

  @Test
  fun laPosizioneSiCollegaPrimaCheIlFileSiaVisibile() = runTest {
    val writer = FakePhoneMediaWriter()
    var visibleAtLink: Boolean? = null

    val result = writer.saveToPictures(SaveRequest("a.jpg", "image/jpeg", content.size.toLong()), source, Sha256.of(content)) { pending ->
      visibleAtLink = "a.jpg" in writer.visible
      assertThat(pending.sha256).isEqualTo(Sha256.of(content))
    }

    assertThat(result).isInstanceOf(SaveResult.Saved::class.java)
    assertThat(visibleAtLink).isFalse()
    assertThat(writer.visible.getValue("a.jpg")).isEqualTo(content)
    val second = writer.saveToPictures(SaveRequest("a.jpg", "image/jpeg", content.size.toLong()), source) as SaveResult.Saved
    assertThat(second.copy.location.displayName).isEqualTo("a (2).jpg")
  }

  @Test
  fun unaSorgenteInterrottaOUnHashDiversoNonLascianoFileVisibili() = runTest {
    val writer = FakePhoneMediaWriter()
    val broken = PhoneSaveSource { sink ->
      sink(ByteArray(10), 10)
      throw IOException("NAS sparito")
    }
    assertThat(writer.saveToPictures(SaveRequest("b.jpg", "image/jpeg", 100), broken)).isEqualTo(SaveResult.Failed("SOURCE_FAILED"))
    assertThat(writer.saveToPictures(SaveRequest("c.jpg", "image/jpeg", 5000), source, Sha256.of(ByteArray(1))))
      .isEqualTo(SaveResult.Failed("HASH_MISMATCH"))
    assertThat(writer.visible).isEmpty()
    assertThat(writer.pendingLeft).isEqualTo(0)
    assertThat(SaveRequest("segreto.jpg", "image/jpeg", 1).toString()).doesNotContain("segreto")
  }
}
