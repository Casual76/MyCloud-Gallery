package dev.pampa.mycloudgallery.core.trust

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class TrustFormatTest {

  @Test
  fun laVersioneCorrenteSiLegge_quellaFuturaOSenzaSensoNo() {
    assertThat(TrustFormat.isReadable(TrustFormat.CURRENT_VERSION)).isTrue()
    assertThat(TrustFormat.isReadable(TrustFormat.CURRENT_VERSION + 1)).isFalse()
    assertThat(TrustFormat.isReadable(0)).isFalse()
    assertThat(TrustFormat.isReadable(-1)).isFalse()
  }

  @Test
  fun intestazioneFaIlGiroDiJson() {
    val header = FormatHeader(TrustFormat.CURRENT_VERSION)

    val text = Json.encodeToString(FormatHeader.serializer(), header)

    assertThat(text).isEqualTo("""{"formatVersion":1}""")
    assertThat(Json.decodeFromString(FormatHeader.serializer(), text)).isEqualTo(header)
  }
}
