package dev.pampa.mycloudgallery.debugtools.probes

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.contract.RewriteCodes
import dev.pampa.mycloudgallery.core.mediastore.contract.RewriteResult
import dev.pampa.mycloudgallery.core.model.Sha256
import org.junit.Test

/** Fase 5B (P0-B): i calcoli puri delle prove guidate (T-09, T-14, T-16), senza Android. */
class FieldProbeMathTest {

  private fun row(
    id: Long,
    size: Long = 1_000,
    modified: Long = 1_760_000_000L,
    trashed: Boolean = false,
    expires: Long? = null,
    video: Boolean = false,
    original: String? = null,
  ) = ProbeRow(
    id = id, isVideo = video, mime = if (video) "video/mp4" else "image/jpeg", size = size, dateModifiedSec = modified, dateTakenMs = 5_000L + id,
    generationModified = id, isTrashed = trashed, dateExpiresSec = expires, relativePath = "DCIM/mcg-prova/", originalDocumentId = original,
    documentId = null, instanceId = null,
  )

  @Test
  fun ilConfrontoDistingueSparitaRipristinoConLoStessoIdEConUnAltro() {
    val baseline = listOf(row(1), row(2, size = 2_000), row(3, size = 3_000), row(4, size = 4_000))

    // Samsung cestina la 2: la riga sparisce dal MediaStore.
    var diff = FieldProbeMath.diff(baseline, listOf(row(1), row(3, size = 3_000), row(4, size = 4_000)))
    assertThat(diff).isEqualTo(FieldProbeMath.Diff(missing = 1, new = 0, trashed = 0, sameIdPresent = 3, otherIdBack = 0))

    // Ripristino dalla Galleria con lo stesso _ID: tutto com'era.
    diff = FieldProbeMath.diff(baseline, baseline)
    assertThat(diff).isEqualTo(FieldProbeMath.Diff(missing = 0, new = 0, trashed = 0, sameIdPresent = 4, otherIdBack = 0))

    // Ripristino con un _ID nuovo: la riga 2 manca, ne compare una nuova con la stessa impronta.
    diff = FieldProbeMath.diff(baseline, listOf(row(1), row(3, size = 3_000), row(4, size = 4_000), row(77, size = 2_000)))
    assertThat(diff).isEqualTo(FieldProbeMath.Diff(missing = 1, new = 1, trashed = 0, sameIdPresent = 3, otherIdBack = 1))

    // Cestino di sistema (createTrashRequest): la riga c'e' ma e' cestinata.
    diff = FieldProbeMath.diff(baseline, listOf(row(1), row(2, size = 2_000, trashed = true), row(3, size = 3_000), row(4, size = 4_000)))
    assertThat(diff).isEqualTo(FieldProbeMath.Diff(missing = 0, new = 0, trashed = 1, sameIdPresent = 3, otherIdBack = 0))

    // "Salva" in Samsung Gallery: stessa riga, byte e data nuovi: non e' piu' "lo stesso file".
    diff = FieldProbeMath.diff(baseline, listOf(row(1), row(2, size = 2_500, modified = 1_760_000_999L), row(3, size = 3_000), row(4, size = 4_000)))
    assertThat(diff.sameIdPresent).isEqualTo(3)
    assertThat(diff.missing).isEqualTo(0)
  }

  @Test
  fun unaRigaNuovaNonSiAbbinaDueVolteAUnaSolaSparita() {
    val baseline = listOf(row(1, size = 500))
    // Una sparita, due nuove con la stessa impronta: solo una e' "tornata".
    val diff = FieldProbeMath.diff(baseline, listOf(row(8, size = 500), row(9, size = 500)))
    assertThat(diff.otherIdBack).isEqualTo(1)
    assertThat(diff.new).isEqualTo(2)
  }

  @Test
  fun laScadenzaDelCestinoSiMisuraInGiorni() {
    val now = 1_760_000_000L
    val thirtyDays = 30 * 86_400L
    val rows = listOf(
      row(1, trashed = true, expires = now + thirtyDays),
      row(2, trashed = true, expires = now + thirtyDays - 3_600), // meno di 30 giorni: 29
      row(3, trashed = true, expires = null),
      row(4, trashed = false, expires = now + 999 * 86_400L), // non cestinata: non conta
    )
    assertThat(FieldProbeMath.expiresDays(rows, now)).isEqualTo(29L to 30L)
    assertThat(FieldProbeMath.expiresDays(listOf(row(5)), now)).isNull()
  }

  @Test
  fun siScelgonoLeProimeRigheVisibiliInOrdineDiId() {
    val rows = listOf(row(5), row(2, video = true), row(9, trashed = true), row(1), row(7))
    assertThat(FieldProbeMath.pick(rows, 3).map { it.id }).containsExactly(1L, 2L, 5L).inOrder()
    assertThat(FieldProbeMath.pick(rows, 10, photosOnly = true).map { it.id }).containsExactly(1L, 5L, 7L).inOrder()
    assertThat(FieldProbeMath.pick(emptyList(), 3)).isEmpty()
  }

  @Test
  fun iCodiciNumericiDelResoconto() {
    assertThat(FieldProbeMath.mimeCode("image/*")).isEqualTo(1L)
    assertThat(FieldProbeMath.mimeCode("video/*")).isEqualTo(2L)
    assertThat(FieldProbeMath.mimeCode("*/*")).isEqualTo(3L)
    assertThat(FieldProbeMath.mimeCode("application/pdf")).isEqualTo(9L)
    assertThat(FieldProbeMath.mimeCode(null)).isEqualTo(0L)

    val sha = Sha256("0".repeat(64))
    assertThat(FieldProbeMath.rewriteCode(RewriteResult.Replaced(sha, 1))).isEqualTo(0L)
    assertThat(FieldProbeMath.rewriteCode(RewriteResult.NotGranted)).isEqualTo(1L)
    assertThat(FieldProbeMath.rewriteCode(RewriteResult.Gone)).isEqualTo(2L)
    assertThat(FieldProbeMath.rewriteCode(RewriteResult.SourceMismatch)).isEqualTo(3L)
    assertThat(FieldProbeMath.rewriteCode(RewriteResult.Failed(RewriteCodes.WRITE_ERROR, restored = true))).isEqualTo(4L)
    assertThat(FieldProbeMath.rewriteCode(RewriteResult.Failed(RewriteCodes.WRITE_ERROR, restored = false))).isEqualTo(5L)
  }

  @Test
  fun leColonneDiT16SiContanoSoloSeValorizzate() {
    val rows = listOf(row(1, original = "abc"), row(2, original = ""), row(3, original = null))
    assertThat(FieldProbeMath.countWith(rows) { it.originalDocumentId }).isEqualTo(1)
    assertThat(FieldProbeMath.countWith(rows) { it.documentId }).isEqualTo(0)
  }
}
