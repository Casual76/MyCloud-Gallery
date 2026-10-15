package dev.pampa.mycloudgallery.core.personal.rules

import dev.pampa.mycloudgallery.core.personal.contract.PersonalRules
import dev.pampa.mycloudgallery.core.personal.contract.PersonalVerdict
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.codec.TextRules
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState

// Chi puo' essere l'autore di una voce (D26, formato-nas.md §4.6): lo dice il registro accettato, mai un dato letto dal NAS.

object PersonalRulesFactory {

  /**
   * Le regole di validita' per l'account [account] all'ora del NAS [nasNowMs]:
   *
   * - **autori ammessi**: i dispositivi dello **stesso account** approvati o dismessi (un telefono sostituito che funzionava: le
   *   sue scelte restano nello stato unito degli altri);
   * - **autori revocati**: ogni dispositivo revocato o segnato come perso, di qualunque account. Una sua voce si scarta anche se
   *   la ripubblica un dispositivo approvato (formato §4.6: "nessuno, qualunque data dichiari").
   *
   * Un dispositivo di un altro account, in attesa o sconosciuto non e' fra gli ammessi: le sue voci sono
   * `AUTHOR_NOT_ALLOWED`. Il confronto fra account non distingue maiuscole (come Samba).
   */
  fun from(state: RegistryState, account: String, nasNowMs: Long): PersonalRules {
    val approved = LinkedHashSet<String>()
    val revoked = LinkedHashSet<String>()
    for (device in state.registry.devices) {
      when (device.status) {
        DeviceStatus.APPROVED, DeviceStatus.RETIRED -> if (TextRules.sameAccount(device.nasUser, account)) approved += device.id
        DeviceStatus.REVOKED, DeviceStatus.LOST -> revoked += device.id
      }
    }
    return PersonalRules(nasNowMs, approvedAuthors = approved, revokedAuthors = revoked)
  }

  /**
   * Come un rifiuto di testa o di firma ([RejectReason]) conta nel resoconto del giro: stessi nomi di [PersonalVerdict]
   * dove il motivo coincide (area comune, altro account, autore revocato), altrimenti `null` (spazzatura, rollback, formato...).
   */
  fun verdictFor(reason: RejectReason): PersonalVerdict? = when (reason) {
    RejectReason.WRONG_LOCATION -> PersonalVerdict.NOT_IN_PRIVATE_AREA
    RejectReason.NOT_OWNER -> PersonalVerdict.AUTHOR_NOT_ALLOWED
    RejectReason.SIGNER_NOT_APPROVED, RejectReason.SIGNER_RETIRED -> PersonalVerdict.AUTHOR_NOT_ALLOWED
    RejectReason.SIGNER_REVOKED -> PersonalVerdict.AUTHOR_REVOKED
    else -> null
  }
}
