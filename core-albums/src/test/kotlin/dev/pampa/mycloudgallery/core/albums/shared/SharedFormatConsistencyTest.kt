package dev.pampa.mycloudgallery.core.albums.shared

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.AlbumTitles
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumLimits
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.trust.data.AlbumDefinitionState
import dev.pampa.mycloudgallery.core.trust.data.AlbumLimits
import dev.pampa.mycloudgallery.core.trust.data.AlbumTexts
import kotlin.random.Random
import org.junit.Test

/** I contratti di P0 e il formato di P2 dicono le stesse cose: tetti, codici, regole del titolo (chi scrive = chi legge). */
class SharedFormatConsistencyTest {

  @Test
  fun tettiECodiciUguali() {
    assertThat(SharedAlbumLimits.MAX_LEVEL_CHANGES).isEqualTo(AlbumLimits.MAX_LEVEL_CHANGES)
    assertThat(SharedAlbumLimits.MAX_ITEMS).isEqualTo(AlbumLimits.MAX_ENTRIES_PER_CONTRIBUTION)
    assertThat(SharedAlbumLimits.MAX_ENTRIES_PER_SEGMENT).isEqualTo(AlbumLimits.MAX_ENTRIES_PER_SEGMENT)
    assertThat(SharedAlbumLimits.MAX_ENTRY_BYTES).isEqualTo(AlbumLimits.MAX_ENTRY_BYTES)
    assertThat(SharedAlbumLimits.MAX_PATH_BYTES).isEqualTo(AlbumLimits.MAX_PATH_BYTES)
    assertThat(AlbumTitles.MAX_CHARS).isEqualTo(AlbumLimits.MAX_TITLE_CHARS)
    assertThat(SharedState.entries.map { it.code }).containsExactlyElementsIn(AlbumDefinitionState.entries.map { it.wire }).inOrder()
    for (code in OthersAfterClose.entries) assertThat(AlbumTexts.isCode(code.code)).isTrue()
  }

  /** Ogni titolo che la UI accetta (dopo aver tolto gli spazi ai bordi) passa il codec di chi legge; quelli che il codec rifiuta, la UI li rifiuta. */
  @Test
  fun unTitoloAccettatoDallaUiPassaIlCodec() {
    val alphabet = "aZ09 .-_:/\\\"*?<>|éèàòùç€ßΩЖ中😀👨‍👩⁠​‮\u0007­﻿ ️ㅤ".let { s ->
      buildList { var i = 0; while (i < s.length) { val cp = s.codePointAt(i); add(String(Character.toChars(cp))); i += Character.charCount(cp) } }
    }
    val random = Random(7)
    repeat(20_000) {
      val title = (0 until random.nextInt(0, 70)).joinToString("") { alphabet.random(random) }
      val ui = AlbumTitles.problem(title) == null
      val codec = AlbumTexts.isTitle(AlbumTitles.normalized(title))
      assertWithMessage("titolo di ${title.length} unita'").that(codec).isEqualTo(ui)
    }
  }
}
