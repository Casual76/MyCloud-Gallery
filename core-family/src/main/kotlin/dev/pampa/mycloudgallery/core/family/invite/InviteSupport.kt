package dev.pampa.mycloudgallery.core.family.invite

import dev.pampa.mycloudgallery.core.family.contract.FamilyInvite
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.TrustViolation
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.codec.TextRules
import dev.pampa.mycloudgallery.core.trust.crypto.EcPublicKey
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.registry.ShareNames
import dev.pampa.mycloudgallery.core.trust.registry.UlaPrefix as TrustUlaPrefix
import dev.pampa.mycloudgallery.core.trust.registry.TrustAnchor

/** Un campo non valido: la violazione che i codec convertono in [dev.pampa.mycloudgallery.core.trust.Verdict.Rejected]. */
internal fun invalidField(message: String): Nothing = throw TrustViolation(RejectReason.INVALID_FIELD, message)

internal fun requireField(condition: Boolean, message: String) {
  if (!condition) invalidField(message)
}

/**
 * I prefissi ULA /48 di casa, in forma testuale (`fdd1:2345:6789::/48`), ridotti a una forma di confronto: lo
 * stesso prefisso scritto in due modi (`fdd1:2345:6789::/48`, `FDD1:2345:6789:0:0:0:0:0/48`) e' lo stesso
 * prefisso. La forma e' quella del formato dei dati (`core-trust`, [TrustUlaPrefix]): `fdxx:xxxx:xxxx::/48`.
 * Solo l'ULA e' un indizio forte di "casa" (E-S10); il QR d'invito porta gli stessi prefissi che l'admin ha
 * firmato nella configurazione di famiglia.
 */
object HomeUla {

  /** La forma canonica di [text], oppure null se non e' un prefisso ULA /48 ben formato. */
  fun canonicalOf(text: String): String? = TrustUlaPrefix.canonical(text)

  /** Vero se almeno un prefisso di [a] coincide con uno di [b]. I prefissi non validi non contano mai. */
  fun overlap(a: Collection<String>, b: Collection<String>): Boolean {
    val left = a.mapNotNull(::canonicalOf).toSet()
    return b.any { canonicalOf(it) in left }
  }
}

/** Le regole di forma di un invito, uguali in lettura ([InviteCodec.decode]) e in scrittura ([InviteCodec.encode]). */
internal object InviteRules {

  const val MAX_ULA = 4
  const val MAX_GUID_CHARS = 64
  const val MAX_PRESET_CHARS = 16
  const val MAX_NAME_CHARS = 64

  private val GUID = Regex("^[0-9A-Za-z:-]{1,$MAX_GUID_CHARS}$")
  private val PRESET = Regex("^[A-Z_]{1,$MAX_PRESET_CHARS}$")

  /** L'account `admin` del NAS non si usa mai nell'app (docs/sicurezza.md §8): un invito che lo nomina e' ostile. */
  fun isAdminAccount(user: String): Boolean = user.trim().equals("admin", ignoreCase = true)

  /**
   * Controlla un invito del QR (strutturalmente e nei contenuti). Lancia [TrustViolation] se qualcosa non va.
   * Con [manual] vale la forma dell'ingresso senza QR: GUID e ULA mancano (li sceglie la rete di casa e
   * l'utente, non l'admin) e la scadenza non c'e'. La versione minima e' 0, oppure quella del QR "stato della famiglia"
   * scansionato da un dispositivo approvato (M7): alzarla e' sempre lecito (un rollback in meno), abbassarla sotto 0 no.
   */
  fun check(invite: FamilyInvite, manual: Boolean = false): FamilyInvite {
    requireField(TrustIds.isValid(invite.familyId), "invito: id di famiglia")
    requireField(Base32.normalizeHumanInput(invite.familyKeyFingerprint) == invite.familyKeyFingerprint, "invito: impronta non canonica")
    requireField(invite.minRegistryVersion in 0..TrustLimits.MAX_VERSION, "invito: versione minima")
    requireField(
      TrustAnchor.create(invite.familyId, invite.familyKeyFingerprint, invite.minRegistryVersion) != null &&
        invite.familyKeyFingerprint.length == EcPublicKey.FINGERPRINT_CHARS,
      "invito: ancora di fiducia",
    )
    requireField(invite.nasIp == invite.nasIp.trim() && NasAddress.parseOrNull(invite.nasIp) != null, "invito: indirizzo del NAS")
    invite.nasName?.let { TextRules.displayText(it, MAX_NAME_CHARS) }
    if (manual) {
      requireField(invite.nasGuid.isEmpty() && invite.homeUla.isEmpty(), "invito manuale: GUID o ULA presenti")
    } else {
      requireField(GUID.matches(invite.nasGuid), "invito: GUID del NAS")
      requireField(invite.homeUla.size in 1..MAX_ULA, "invito: prefissi ULA")
      // Come nella configurazione firmata: una sola scrittura per prefisso (canonica, senza ripetizioni).
      requireField(invite.homeUla.all { TrustUlaPrefix.isCanonical(it) }, "invito: prefisso ULA non valido")
      requireField(invite.homeUla.toSet().size == invite.homeUla.size, "invito: prefissi ULA ripetuti")
      requireField(
        invite.expiresAtNas in TrustLimits.MIN_PLAUSIBLE_TIME_MILLIS..TrustLimits.MAX_PLAUSIBLE_TIME_MILLIS,
        "invito: scadenza",
      )
    }
    ShareNames.check(invite.commonShare)
    ShareNames.check(invite.privateShare)
    // Revisione 2, F8: l'area privata e' quella in cui scrive solo il titolare (segnalazioni "perso", kit): non puo'
    // essere l'area comune, dove scrivono tutti.
    requireField(!invite.privateShare.equals(invite.commonShare, ignoreCase = true), "invito: area privata uguale all'area comune")
    TextRules.nasAccount(invite.nasUser)
    requireField(!isAdminAccount(invite.nasUser), "invito: account admin")
    TextRules.displayText(invite.deviceName, MAX_NAME_CHARS)
    invite.presetName?.let { requireField(PRESET.matches(it), "invito: preset") }
    return invite
  }
}
