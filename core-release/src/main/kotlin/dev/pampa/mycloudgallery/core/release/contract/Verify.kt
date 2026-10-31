package dev.pampa.mycloudgallery.core.release.contract

import dev.pampa.mycloudgallery.core.model.StableCode

// LA VERIFICA (docs/fase10-progetto.md §1.2 "Verifica", "Chiavi", §2.8, D29, D31; C440). Contratto di P0. Il verificatore vero (codec canonico,
// tetti, `SHA256withECDSA` di `java.security`, le chiavi fissate K1 e K2, piu' KT solo in debug e nei test) e' di P1 e **si costruisce solo in
// core-release** (Phase10ArchitectureTest): nessun altro modulo legge i campi del manifest se non attraverso `ReleaseStatus` ed
// `ExtraModelSources`.

/** Perche' una busta e' stata rifiutata **per intero** (D29, E-REL2): contato in Diagnostica (`RELEASE_MANIFEST_REJECTED`), resta l'ultimo valido. */
enum class RejectReason(override val code: String) : StableCode {
  /** Oltre [ManifestLimits.MAX_BYTES] (o un tetto interno superato). */
  TOO_LARGE("too_large"),

  /** Intestazione, lunghezze, JSON non canonico o campi di tipo sbagliato. */
  MALFORMED("malformed"),

  /** Firmata con una chiave che l'app non conosce (anche KT su una release). */
  UNKNOWN_KEY("unknown_key"),

  /** Firma non valida. */
  SIGNATURE("signature"),

  /** Per un'altra app. */
  WRONG_APP("wrong_app"),

  /** Uno schema che l'app non sa leggere. */
  SCHEMA("schema"),

  /** Un campo sconosciuto marcato `critical`. */
  CRITICAL_FIELD("critical_field"),
}

/** L'esito della verifica: un manifest intatto e firmato da una chiave fissata, o un rifiuto. */
sealed interface Verified {
  data class Ok(val manifest: ReleaseManifest, val keyId: Int) : Verified

  data class Rejected(val reason: RejectReason) : Verified
}

/**
 * Il verificatore (D29). [verify] guarda la busta intera: tetti, forma, chiave, firma, app ([app] e' l'id di base, [ManifestLimits.APP_ID],
 * anche nella build debug con il suffisso). Non lancia mai: tutto cio' che non e' un `Ok` e' un `Rejected` con il motivo. La firma e'
 * pubblica: il confronto in tempo costante non serve.
 */
fun interface ManifestVerifier {
  fun verify(bytes: ByteArray, app: String): Verified
}

/**
 * Una chiave pubblica fissata nell'app (D31): [id] il `keyId` della busta, [spki] la chiave pubblica P-256 in X.509 SubjectPublicKeyInfo,
 * [fingerprint] lo SHA-256 dello [spki] in esadecimale (scritto anche in `docs/sicurezza.md`: un test di P1 confronta codice e documento).
 * Si costruisce solo in `core-release/.../keys/` e nei `testing/` (Phase10ArchitectureTest).
 */
class PinnedKey(val id: Int, spki: ByteArray, val fingerprint: String) {
  private val bytes: ByteArray = spki.copyOf()

  init {
    require(id in 0..255) { "Id di chiave non valido." }
    require(FINGERPRINT.matches(fingerprint)) { "Impronta non valida." }
  }

  val spki: ByteArray get() = bytes.copyOf()

  override fun equals(other: Any?): Boolean = other is PinnedKey && other.id == id && other.bytes.contentEquals(bytes)

  override fun hashCode(): Int = 31 * id + bytes.contentHashCode()

  override fun toString(): String = "PinnedKey[$id, ${fingerprint.take(12)}]"

  private companion object {
    val FINGERPRINT = Regex("^[0-9a-f]{64}$")
  }
}

/**
 * Gli id delle chiavi (D31): [K1] attiva, [K2] di riserva (mai usata finche' K1 e' buona; dopo una rotazione l'aggiornamento dell'app porta K2
 * attiva e K3 di riserva); [KT] la chiave **di prova**, fidata solo nella build debug e nei test, **mai** in un APK di release (`tools/check.ps1`).
 */
object ReleaseKeyIds {
  const val K1: Int = 1
  const val K2: Int = 2
  const val KT: Int = 250
}
