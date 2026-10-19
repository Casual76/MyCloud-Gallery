package dev.pampa.mycloudgallery.core.family.invite

import dev.pampa.mycloudgallery.core.family.contract.FamilyInvite
import dev.pampa.mycloudgallery.core.family.contract.QrPayloads
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.TrustViolation
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.codec.TextRules

/**
 * La scadenza di un invito (§2.2): 24 ore secondo l'ora del NAS. Il QR non ha un orologio, quindi la scadenza
 * si giudica a parte, con l'ora del NAS letta alla NEGOTIATE (`NegotiationFacts.timeOffsetMs`): l'ora del
 * telefono puo' essere sbagliata e non decide mai (E-P9).
 */
object InviteExpiry {

  /** Quanto puo' durare al massimo un invito (24 h) e lo scarto d'orologio tollerato oltre quel limite. */
  const val MAX_VALIDITY_MILLIS: Long = 24L * 60 * 60 * 1000
  const val SKEW_MILLIS: Long = 5L * 60 * 1000

  /** Il controllo "soffice" che si fa alla scansione, con l'ora del telefono: tollera un orologio sfasato. */
  const val SCAN_TOLERANCE_MILLIS: Long = 30L * 60 * 1000

  enum class Status {
    VALID,

    /** La scadenza e' passata (ora del NAS). */
    EXPIRED,

    /** La scadenza e' piu' lontana di quanto un invito possa durare: non l'ha scritto la nostra app. */
    TOO_FAR,
  }

  fun status(invite: FamilyInvite, nasNowMillis: Long): Status = when {
    nasNowMillis > invite.expiresAtNas -> Status.EXPIRED
    invite.expiresAtNas - nasNowMillis > MAX_VALIDITY_MILLIS + SKEW_MILLIS -> Status.TOO_FAR
    else -> Status.VALID
  }
}

/** Che cosa e' un testo letto da un QR, senza ancora giudicarlo. */
enum class QrKind { INVITE, DEVICE_KEY, FAMILY_STATE, WIREGUARD_SECRET, UNKNOWN }

object QrClassifier {

  fun kindOf(text: String): QrKind = when {
    QrPayloads.looksLikeWireGuardConfig(text) -> QrKind.WIREGUARD_SECRET
    text.startsWith(QrPayloads.INVITE_PREFIX) -> QrKind.INVITE
    text.startsWith(QrPayloads.DEVICE_KEY_PREFIX) -> QrKind.DEVICE_KEY
    text.startsWith(QrPayloads.FAMILY_STATE_PREFIX) -> QrKind.FAMILY_STATE
    else -> QrKind.UNKNOWN
  }
}

/** Perche' uno scanner rifiuta un QR d'invito (corrisponde uno a uno a `ScanRejection` di `core-qr`). */
enum class InviteScanProblem { NOT_OURS, WIREGUARD_SECRET, MALFORMED, EXPIRED, OTHER_FAMILY }

sealed interface InviteScan {
  data class Valid(val invite: FamilyInvite) : InviteScan

  data class Rejected(val problem: InviteScanProblem) : InviteScan
}

/**
 * Il giudizio sul testo di un QR d'invito mentre lo scanner lo legge (`QrScanner.accept`): e' la logica che
 * decide il messaggio sotto il mirino. Il controllo della scadenza qui e' soffice (ora del telefono, con
 * [InviteExpiry.SCAN_TOLERANCE_MILLIS] di tolleranza); quello vero lo fa [dev.pampa.mycloudgallery.core.family.contract.JoinFlow]
 * con l'ora del NAS.
 */
object InviteScanCheck {

  /**
   * @param expectedFamilyId se il dispositivo e' gia' legato a una famiglia e si aspetta quella (per esempio
   *   un ritorno nello stesso invito), un invito di un'altra famiglia e' [InviteScanProblem.OTHER_FAMILY] (E-O5).
   *   Null per un dispositivo nuovo: non ha ancora una famiglia con cui confrontare.
   */
  fun check(text: String, deviceNowMillis: Long, expectedFamilyId: String? = null): InviteScan =
    when (QrClassifier.kindOf(text)) {
      QrKind.WIREGUARD_SECRET -> InviteScan.Rejected(InviteScanProblem.WIREGUARD_SECRET)
      QrKind.INVITE -> when (val verdict = InviteCodec.decode(text)) {
        is Verdict.Rejected -> InviteScan.Rejected(InviteScanProblem.MALFORMED)
        is Verdict.Accepted -> {
          val invite = verdict.value
          when {
            expectedFamilyId != null && invite.familyId != expectedFamilyId -> InviteScan.Rejected(InviteScanProblem.OTHER_FAMILY)
            deviceNowMillis > invite.expiresAtNas + InviteExpiry.SCAN_TOLERANCE_MILLIS -> InviteScan.Rejected(InviteScanProblem.EXPIRED)
            else -> InviteScan.Valid(invite)
          }
        }
      }
      // Un QR dell'app ma di un altro tipo (chiave, stato) non e' un invito: per questo scanner e' "non nostro".
      QrKind.DEVICE_KEY, QrKind.FAMILY_STATE, QrKind.UNKNOWN -> InviteScan.Rejected(InviteScanProblem.NOT_OURS)
    }
}

/**
 * L'ingresso senza QR (§2.2, ultima riga): indirizzo, utente, id della famiglia e impronta **digitati** leggendoli
 * dalla schermata dell'admin, mai ricavati dal NAS (B9: premere "Conferma" su un valore letto dal NAS non verifica
 * niente), e le due aree (il registro sta nell'area comune: senza, l'invito non c'e'). La versione minima del registro
 * vale 0 e la Diagnostica avvisa finche' il dispositivo non vede la versione corrente; con il QR "stato della famiglia"
 * ([minRegistryVersion]) parte gia' da quella. Id e impronta si leggono come li scrive una persona
 * (`Base32.normalizeHumanInput`: spazi, trattini, maiuscole, O per 0, I e L per 1): e' l'unico punto in cui il testo
 * digitato diventa un invito (verifica funzionale, ON-1: la pagina toglieva solo gli spazi).
 *
 * Il risultato e' un [FamilyInvite] "manuale": senza GUID (si impara dalla NEGOTIATE sul Wi-Fi che l'utente ha
 * confermato come casa), senza ULA e senza scadenza. [isManual] lo riconosce.
 */
object ManualInvite {

  /** La scadenza simbolica di un invito manuale: non scade (non esiste un QR da fotografare). */
  const val NO_EXPIRY: Long = Long.MAX_VALUE

  fun create(
    familyId: String,
    familyKeyFingerprint: String,
    nasIp: String,
    nasUser: String,
    commonShare: String,
    privateShare: String,
    deviceName: String,
    minRegistryVersion: Long = 0L,
  ): Verdict<FamilyInvite> =
    try {
      Verdict.Accepted(
        InviteRules.check(
          FamilyInvite(
            familyId = Base32.normalizeHumanInput(familyId),
            familyKeyFingerprint = Base32.normalizeHumanInput(familyKeyFingerprint),
            minRegistryVersion = minRegistryVersion,
            nasIp = nasIp.trim(),
            nasName = null,
            nasGuid = "",
            homeUla = emptyList(),
            commonShare = commonShare,
            privateShare = privateShare,
            nasUser = nasUser.trim().also { TextRules.nasAccount(it) },
            deviceName = deviceName,
            presetName = null,
            expiresAtNas = NO_EXPIRY,
          ),
          manual = true,
        ),
      )
    } catch (v: TrustViolation) {
      Verdict.Rejected(v.reason)
    } catch (e: RuntimeException) {
      Verdict.Rejected(RejectReason.MALFORMED)
    }
}

/** Vero per un invito creato da [ManualInvite]: nessun GUID e nessun ULA da confrontare. */
val FamilyInvite.isManual: Boolean get() = nasGuid.isEmpty() && homeUla.isEmpty()
