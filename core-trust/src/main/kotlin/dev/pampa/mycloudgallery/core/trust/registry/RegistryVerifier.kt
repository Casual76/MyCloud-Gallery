package dev.pampa.mycloudgallery.core.trust.registry

import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.crypto.EcPublicKey
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.ensure
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.envelope.Envelope
import dev.pampa.mycloudgallery.core.trust.verdictOf
import dev.pampa.mycloudgallery.core.trust.violation

/**
 * Il punto di partenza della fiducia per un dispositivo nuovo (`docs/formato-nas.md` §4.6): arriva dal QR
 * dell'admin (o e' confermato a mano) e non dal NAS.
 *
 * @property familyKeyFingerprint impronta della chiave di famiglia (26 caratteri base32).
 * @property minRegistryVersion il registro piu' vecchio accettabile: il QR porta la versione corrente,
 * cosi' un dispositivo nuovo non si fa rifilare un registro vecchio (rollback).
 */
class TrustAnchor private constructor(
  val familyId: String,
  val familyKeyFingerprint: String,
  val minRegistryVersion: Long,
) {
  companion object {
    /** Da QR o da inserimento manuale (tollerante su maiuscole, spazi e trattini). Null se non valido. */
    fun create(familyId: String, familyKeyFingerprint: String, minRegistryVersion: Long): TrustAnchor? {
      val id = Base32.normalizeHumanInput(familyId)
      val fingerprint = Base32.normalizeHumanInput(familyKeyFingerprint)
      if (!TrustIds.isValid(id)) return null
      if (fingerprint.length != EcPublicKey.FINGERPRINT_CHARS || !fingerprint.all { it in BASE32_CHARS }) return null
      if (minRegistryVersion < 0) return null
      return TrustAnchor(id, fingerprint, minRegistryVersion)
    }

    private const val BASE32_CHARS = "0123456789abcdefghjkmnpqrstvwxyz"
  }
}

/**
 * L'ultimo registro accettato da questo dispositivo, da conservare in locale (spazio privato dell'app):
 * e' la memoria dell'anti-rollback. Si salva [signedBytes] e si ricarica con [RegistryVerifier.restore].
 */
class RegistryState internal constructor(
  val registry: FamilyRegistry,
  signedBytes: ByteArray,
  /** Impronta dei byte firmati (non della firma): l'identita' di questa versione (revisione 1.4, A3). */
  val contentHash: String,
  /** La chiave di famiglia che ha firmato questa versione. */
  val signerKey: EcPublicKey,
) {
  private val bytes = signedBytes.copyOf()

  val signedBytes: ByteArray get() = bytes.copyOf()

  /** La chiave che deve firmare la versione successiva: quella annunciata dalla rotazione, se c'e'. */
  val trustedFamilyKey: EcPublicKey get() = registry.successorKey ?: signerKey

  fun statusOf(deviceId: String): DeviceStatus? = registry.device(deviceId)?.status

  /** Solo un dispositivo approvato scrive dati che gli altri accettano; gli altri restano in lettura (E-S14). */
  fun canWrite(deviceId: String): Boolean = statusOf(deviceId) == DeviceStatus.APPROVED

  /** Il registro dice che QUESTO dispositivo e' perso: deve distruggere chiave e dati locali (Fase 2.4). */
  fun mustSelfWipe(deviceId: String): Boolean = statusOf(deviceId) == DeviceStatus.LOST

  /**
   * L'ancora da mostrare in un QR (dispositivo nuovo, recupero dell'admin): id della famiglia, impronta della
   * chiave che ha firmato QUESTA versione e questa versione come minimo. Funziona anche subito dopo una
   * rotazione (revisione 1.4, B4): il dispositivo nuovo accetta l'annuncio e poi segue il successore.
   */
  fun toAnchor(): TrustAnchor = TrustAnchor.create(registry.familyId, signerKey.fingerprint, registry.version)!!
}

/** Un ramo del registro firmato dalla famiglia ma incompatibile con quello fidato: da mostrare all'admin. */
class RegistryFork(val registry: FamilyRegistry, val contentHash: String) {
  /** I dispositivi che esistono solo in questo ramo: quelli da riconoscere prima di unirlo. */
  fun devicesOnlyHere(current: FamilyRegistry): List<DeviceEntry> = registry.devices.filter { current.device(it.id) == null }
}

/**
 * Accetta un registro di famiglia solo se (in quest'ordine): la busta e' integra, la chiave che firma e'
 * quella attesa (ancora del QR, oppure quella del registro gia' accettato o il suo successore), la firma
 * e' valida, il payload e' canonico e ben formato, la famiglia e' la stessa, la versione e' piu' alta
 * dell'ultima vista (o, per un dispositivo nuovo, almeno quella del QR) e il contenuto non "torna indietro"
 * rispetto a quello gia' fidato ([FamilyRegistry.checkNoRegressionFrom]).
 */
object RegistryVerifier {

  fun accept(bytes: ByteArray, current: RegistryState?, anchor: TrustAnchor): Verdict<RegistryState> = verdictOf {
    val envelope = Envelope.open(bytes, DocType.FAMILY_REGISTRY)
    // Lo stesso contenuto firmato (anche con un'altra firma valida) e' la stessa versione.
    if (current != null && envelope.contentHash == current.contentHash) return@verdictOf current
    if (current != null) {
      ensure(envelope.signer == current.trustedFamilyKey, RejectReason.SIGNER_NOT_APPROVED, "registro: chiave di famiglia inattesa")
    } else {
      ensure(envelope.signer.fingerprint == anchor.familyKeyFingerprint, RejectReason.SIGNER_NOT_APPROVED, "registro: chiave di famiglia inattesa")
    }
    val registry = FamilyRegistry.read(envelope.payloadJson())
    ensure(registry.familyId == (current?.registry?.familyId ?: anchor.familyId), RejectReason.WRONG_FAMILY, "registro: altra famiglia")
    ensure(registry.successorKey != envelope.signer, RejectReason.INVALID_FIELD, "registro: successore uguale")
    if (current == null) {
      ensure(registry.version >= anchor.minRegistryVersion, RejectReason.ROLLBACK, "registro: piu' vecchio del QR")
    } else {
      if (registry.version < current.registry.version) violation(RejectReason.ROLLBACK, "registro: versione piu' vecchia")
      if (registry.version == current.registry.version) violation(RejectReason.EQUIVOCATION, "registro: due contenuti con la stessa versione")
      registry.checkNoRegressionFrom(current.registry)
    }
    RegistryState(registry, bytes, envelope.contentHash, envelope.signer)
  }

  /**
   * Un ramo diverso firmato dalla famiglia (rifiutato per [RejectReason.EQUIVOCATION] o
   * [RejectReason.REGRESSION]): lo si legge senza accettarlo, per mostrarlo all'admin che decide se unirlo
   * ([FamilyAuthority.mergeFork]) o, se non lo riconosce, reimpostare la famiglia (chiave rubata).
   */
  fun readFork(bytes: ByteArray, current: RegistryState): Verdict<RegistryFork> = verdictOf {
    val envelope = Envelope.open(bytes, DocType.FAMILY_REGISTRY)
    ensure(envelope.signer == current.trustedFamilyKey || envelope.signer == current.signerKey, RejectReason.SIGNER_NOT_APPROVED, "ramo: chiave inattesa")
    val registry = FamilyRegistry.read(envelope.payloadJson())
    ensure(registry.familyId == current.registry.familyId, RejectReason.WRONG_FAMILY, "ramo: altra famiglia")
    RegistryFork(registry, envelope.contentHash)
  }

  /**
   * Ricarica lo stato salvato in locale. [trustedSignerFingerprint] e' l'impronta salvata insieme ai byte
   * (`RegistryState.signerKey.fingerprint`): un file locale scambiato non passa.
   */
  fun restore(signedBytes: ByteArray, trustedSignerFingerprint: String): Verdict<RegistryState> = verdictOf {
    val envelope = Envelope.open(signedBytes, DocType.FAMILY_REGISTRY)
    ensure(envelope.signer.fingerprint == trustedSignerFingerprint, RejectReason.SIGNER_NOT_APPROVED, "registro locale: chiave inattesa")
    RegistryState(FamilyRegistry.read(envelope.payloadJson()), signedBytes, envelope.contentHash, envelope.signer)
  }
}
