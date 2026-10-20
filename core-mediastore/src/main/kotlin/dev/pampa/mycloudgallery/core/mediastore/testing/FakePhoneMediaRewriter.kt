package dev.pampa.mycloudgallery.core.mediastore.testing

import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaRewriter
import dev.pampa.mycloudgallery.core.mediastore.contract.RewriteCodes
import dev.pampa.mycloudgallery.core.mediastore.contract.RewriteResult
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.Sha256
import java.io.File

/**
 * Fase 5B (P0-B, docs/fase5-progetto.md §5.2): il riscrittore del telefono finto, sopra [FakeMediaStore]. Si comporta come il contratto di
 * [PhoneMediaRewriter] (e come l'adattatore vero): senza il permesso di `writeRequest` per l'URI che apre (la collezione del tipo della riga,
 * VE-2) non scrive ([RewriteResult.NotGranted]), un file che non ha lo SHA-256 atteso non si scrive, una riga sparita e' [RewriteResult.Gone],
 * una riga che non ha l'impronta attesa non si tocca ([RewriteResult.RowChanged], R3; con i byte nuovi gia' sulla riga e' fatta), e un guasto
 * programmato lascia la riga come era o, nel caso grave ([failNext] con `restored = false`), a meta'. Dopo una scrittura riuscita la riga ha gli stessi `_ID` e volume, byte,
 * dimensione e data di modifica nuovi (generazione che sale): la lettura del telefono la vede come una modifica del file (`ContentChanged`).
 *
 * @param nowMs la data di modifica che prende la riga riscritta (un orologio finto: mai quello del telefono).
 */
class FakePhoneMediaRewriter(
  private val store: FakeMediaStore,
  private val nowMs: () -> Long = { DEFAULT_NOW_MS },
) : PhoneMediaRewriter {

  /** Le riscritture riuscite, nell'ordine: (volume, `_ID`). */
  val replaced: MutableList<Pair<String, Long>> = ArrayList()

  /** Se valorizzato, la prossima riscrittura (con permesso e file giusto) finisce cosi' (una volta sola). Con `restored = false` la riga resta a meta'. */
  var failNext: RewriteResult.Failed? = null

  /** Se vero, la prossima riscrittura scrive byte sbagliati: la rilettura non torna e i byte di prima si rimettono (`HASH_MISMATCH`, ripristinato). */
  var corruptNext: Boolean = false

  override suspend fun replace(ref: MediaRef.Phone, file: File, expectedSha256: Sha256, currentSha256: Sha256): RewriteResult {
    val bytes = try {
      file.readBytes()
    } catch (_: java.io.IOException) {
      return RewriteResult.SourceMismatch
    }
    if (Sha256.of(bytes) != expectedSha256) return RewriteResult.SourceMismatch
    val before = store.bytesOf(ref.volume, ref.mediaStoreId) ?: return RewriteResult.Gone
    // R3, nell'ordine dell'adattatore vero (la copia di sicurezza si legge prima di aprire in scrittura): la riga deve avere i byte della
    // versione al sicuro; con quelli nuovi gia' sopra e' fatta (risposta persa).
    val now = Sha256.of(before)
    if (now == expectedSha256) return RewriteResult.Replaced(expectedSha256, bytes.size.toLong())
    if (now != currentSha256) return RewriteResult.RowChanged
    if (!store.hasWriteGrant(ref.volume, ref.mediaStoreId)) return RewriteResult.NotGranted

    failNext?.let { failure ->
      failNext = null
      // Il caso grave: la scrittura e' cominciata e non e' tornata indietro. Il telefono ha la prima meta' del file nuovo.
      if (!failure.restored) store.rewrite(ref.volume, ref.mediaStoreId, bytes.copyOf(bytes.size / 2), nowMs())
      return failure
    }
    if (corruptNext) {
      corruptNext = false
      // Scritti byte sbagliati, riletti, non tornano: i byte di prima si rimettono (la riga cambia due volte, come sul telefono vero).
      store.rewrite(ref.volume, ref.mediaStoreId, bytes.copyOf().also { if (it.isNotEmpty()) it[it.size / 2] = (it[it.size / 2].toInt() xor 0x5A).toByte() }, nowMs())
      store.rewrite(ref.volume, ref.mediaStoreId, before, nowMs())
      return RewriteResult.Failed(RewriteCodes.HASH_MISMATCH, restored = true)
    }
    store.rewrite(ref.volume, ref.mediaStoreId, bytes, nowMs())
    replaced += ref.volume to ref.mediaStoreId
    return RewriteResult.Replaced(expectedSha256, bytes.size.toLong())
  }

  companion object {
    /** 2026-10-19T00:00:00Z: una data di modifica plausibile per le righe riscritte. */
    const val DEFAULT_NOW_MS: Long = 1_792_368_000_000L
  }
}
