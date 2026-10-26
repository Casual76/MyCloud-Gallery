package dev.pampa.mycloudgallery.core.albums.shared

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.AlbumTitles
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumLimits
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.AlbumDefinitionState
import dev.pampa.mycloudgallery.core.trust.data.AlbumEntry
import dev.pampa.mycloudgallery.core.trust.data.AlbumField
import dev.pampa.mycloudgallery.core.trust.data.AlbumLimits
import dev.pampa.mycloudgallery.core.trust.data.AlbumTexts
import dev.pampa.mycloudgallery.core.trust.data.AlbumValue
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
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

  /**
   * Il contributo onesto piu' grande che il formato permette (20.000 elementi con percorsi da 1.024 byte, account da 64 caratteri,
   * posizione, versione e hash completo) sta in una testa che chi legge accetta: al piu' 64 segmenti e 32 MiB in tutto. Senza questo
   * margine una testa onesta si rifiuterebbe prima di scaricare.
   */
  @Test
  fun ilContributoOnestoPiuGrandeStaInUnaTesta() {
    // Il carattere ammesso nei percorsi che pesa di piu' nel JSON (le virgolette diventano due byte, se un percorso le ammette).
    val filler = listOf("\"", "a").first { c -> runCatching { SharePath.parseMediaPath("Album condivisi/x${c}y.jpg") }.isSuccess }
    val parts = ArrayList<String>()
    var used = "Album condivisi".length
    while (AlbumLimits.MAX_PATH_BYTES - used - 1 > 0) {
      val n = minOf(200, AlbumLimits.MAX_PATH_BYTES - used - 1)
      parts += filler.repeat(n)
      used += 1 + n
    }
    val path = SharePath.parseMediaPath((listOf("Album condivisi") + parts).joinToString("/"))
    assertThat(path.value.toByteArray(Charsets.UTF_8).size).isEqualTo(AlbumLimits.MAX_PATH_BYTES)
    val account = AccountId("a".repeat(TrustLimits.MAX_NAS_ACCOUNT_CHARS))
    val author = Paper.device(account)
    val entries = (1..AlbumLimits.MAX_ENTRIES_PER_CONTRIBUTION).map { n ->
      val value = AlbumValue.Item(
        Paper.key(n),
        path,
        AlbumLimits.MAX_POSITION,
        account,
        VersionId(java.util.UUID(n.toLong(), -1L).toString()),
        Sha256(Paper.key(-n).hex),
      )
      AlbumEntry(Paper.album, AlbumField.Item(Paper.media(n)), TrustLimits.MAX_PLAUSIBLE_TIME_MILLIS, author, account, value)
    }
    val segments = AlbumSegmentPlan.plan(Paper.album, entries)
    val total = segments.sumOf { it.bytes.size.toLong() }
    assertWithMessage("$total byte in ${segments.size} segmenti").that(segments.size).isAtMost(AlbumLimits.MAX_HEAD_SEGMENTS)
    assertWithMessage("$total byte in ${segments.size} segmenti").that(total).isAtMost(AlbumLimits.MAX_CONTRIBUTION_BYTES)
    // Il margine dichiarato in `AlbumLimits.MAX_CONTRIBUTION_BYTES`: sotto i 30 MiB.
    assertWithMessage("$total byte").that(total).isLessThan(30L * 1024 * 1024)
  }
}
