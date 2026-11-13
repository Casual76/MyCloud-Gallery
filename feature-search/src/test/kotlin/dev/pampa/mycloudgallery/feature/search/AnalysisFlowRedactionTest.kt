package dev.pampa.mycloudgallery.feature.search

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.search.contract.AnalysisFlow
import dev.pampa.mycloudgallery.feature.search.contract.AnalysisThumb
import java.util.UUID
import org.junit.Test

/**
 * XS3-5 (Pacchetto XF, giro 3): `AnalysisFlow` e `AnalysisThumb.Shown` stampano **soli tipi e conteggi** (come `AiLive.toString`), mai l'id del media, la versione o il
 * riferimento al telefono o al NAS: un dump, un'eccezione o un log che le stampa non porta nessun dato di un media. Prova con **valori sentinella** nei campi.
 * Mutazione: togliere i due `toString` (tornano quelli delle `data class`, che stampano tutto).
 */
class AnalysisFlowRedactionTest {

  private val sentinelId = MediaId(UUID(0x5E57E5E5L, 0x1D1D1D1DL).toString())
  private val sentinelVersion = 4_242_424_242L
  private val sentinelLocation = 7_777_777L
  private val sentinelContent = 0x0BADC0FFEEL

  private fun shown() = AnalysisThumb.Shown(
    sentinelId,
    ThumbRequest(ThumbKey(sentinelId, sentinelVersion, ThumbBucket.M), MediaRef.Nas(sentinelLocation, sentinelContent, "image/jpeg")),
    thumbHash = null,
    video = true,
  )

  private fun assertNoSentinel(text: String) {
    assertThat(text).doesNotContain(sentinelId.value)
    assertThat(text).doesNotContain(sentinelId.value.substring(0, 8))
    assertThat(text).doesNotContain(sentinelVersion.toString())
    assertThat(text).doesNotContain(sentinelLocation.toString())
    assertThat(text).doesNotContain(sentinelContent.toString())
    assertThat(text).doesNotContain(sentinelContent.toString(16))
    assertThat(text).doesNotContain("image/jpeg")
    assertThat(text).doesNotContain("Nas(")
  }

  @Test
  fun unaAnteprimaMostrataStampaSoloIlTipo() {
    val text = shown().toString()
    assertNoSentinel(text)
    assertThat(text).isEqualTo("AnalysisThumb.Shown(video=true)")
    assertThat(AnalysisThumb.Quiet.toString()).isEqualTo("Quiet")
  }

  @Test
  fun ilNastroStampaSoloConteggi() {
    val flow = AnalysisFlow(
      current = shown(), recent = listOf(shown(), AnalysisThumb.Quiet), next = listOf(AnalysisThumb.Quiet),
      visibilityGeneration = 3, finished = 12,
    )
    val text = flow.toString()
    assertNoSentinel(text)
    assertThat(text).isEqualTo("AnalysisFlow(ora=si, fatte=2, prossime=1, mostrate=2, generazione=3, finiti=12)")
    assertThat(AnalysisFlow.EMPTY.toString()).isEqualTo("AnalysisFlow(ora=no, fatte=0, prossime=0, mostrate=0, generazione=0, finiti=0)")
  }
}
