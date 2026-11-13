package dev.pampa.mycloudgallery.di

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/** Completa le prove funzionali dei proprietari con i collegamenti del grafo reale. */
class AnalysisVisibilityWiringTest {
  private fun source(path: String) = File("../$path").readText()
  private val gallery get() = source("app/src/main/kotlin/dev/pampa/mycloudgallery/gallery/GalleryGraph.kt")
  private val phone get() = source("app/src/main/kotlin/dev/pampa/mycloudgallery/gallery/PhoneLibrary.kt")
  private val core get() = source("core-mediastore/src/main/kotlin/dev/pampa/mycloudgallery/core/mediastore/PhoneLibraryGraph.kt")

  @Test fun permessiDelPrimoPianoEDellaRichiestaHannoIlCallbackDiretto() {
    assertThat(gallery).contains("?.onChange { analysisVisibility.changed() }")
    assertThat(gallery).contains("access.refresh()")
  }
  @Test fun lettureInterneDelPermessoHannoLoStessoCallback() {
    assertThat(core).contains("it.onChange(visibilityChanged)")
    assertThat(phone).contains("visibilityChanged = inputs.visibilityChanged")
    assertThat(gallery).contains("visibilityChanged = { analysisVisibility.changed() }")
  }
  @Test fun osservatoreDellaSelezioneECestinoHannoLaBarriera() {
    assertThat(core).contains("visibilityChanged = contentChanged")
    assertThat(phone).contains("contentChanged = inputs.contentChanged")
    assertThat(gallery).contains("contentChanged = { analysisVisibility.contentChanged() }")
  }
  @Test fun pubblicazioneDellaSelezioneHaLoStessoCallback() {
    assertThat(core).contains("selectionChanged = visibilityChanged")
  }
  @Test fun ritiroHaLaBarrieraDiretta() {
    assertThat(gallery).contains("analysisVisibility.retire()")
  }
  @Test fun nascostiEProviderUsanoLaStessaStrada() {
    assertThat(gallery).contains("        analysisVisibility.changed()")
    assertThat(source("app/src/main/kotlin/dev/pampa/mycloudgallery/di/AiModule.kt")).contains("graph.scope, gallery.analysisVisibility)")
  }
}
