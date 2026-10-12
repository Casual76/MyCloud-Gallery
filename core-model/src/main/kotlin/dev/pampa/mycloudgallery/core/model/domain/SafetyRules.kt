package dev.pampa.mycloudgallery.core.model.domain

import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.Media
import dev.pampa.mycloudgallery.core.model.MediaLocation
import dev.pampa.mycloudgallery.core.model.MediaVersion
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.NasAreaLookup
import dev.pampa.mycloudgallery.core.model.Presence
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.model.VersionId

/** Lo stato "al sicuro" di un media, complessivo e per versione da proteggere. */
data class SafetyReport(
  val status: SafetyStatus,
  val byVersion: Map<VersionId, SafetyStatus>,
) {
  val isSafe: Boolean get() = status == SafetyStatus.SAFE
}

/** Perche' "Libera spazio" non puo' togliere una copia dal telefono. */
enum class FreeSpaceBlock {
  /** La posizione non e' una copia del telefono presente. */
  NOT_A_PRESENT_PHONE_COPY,

  /** Sul NAS non c'e' una copia di quei byte: serve prima il backup. */
  NO_NAS_COPY,

  /** C'e' solo una copia nell'area comune: prima si crea la copia privata, poi si libera (E-B14). */
  CREATE_PRIVATE_COPY_FIRST,

  /** La copia privata non e' mai stata riletta: va verificata. */
  PRIVATE_COPY_UNVERIFIED,

  /** La verifica e' piu' vecchia di quanto richiesto: va rifatta "sul momento" (5.5). */
  VERIFICATION_TOO_OLD,
}

sealed interface FreeSpaceCheck {
  data object Allowed : FreeSpaceCheck

  data class Blocked(val reason: FreeSpaceBlock) : FreeSpaceCheck
}

/**
 * "Al sicuro" e presenza (piano §2, 4.4, 5.3, 5.5; E-B13, E-B14, E-L3).
 *
 * **Regola**: un media e' al sicuro solo se ogni versione da proteggere ha una copia nell'**area
 * privata del proprietario**, presente e **verificata** (rilettura con SHA-256 uguale). Una copia
 * nell'area comune non basta: altri familiari possono cancellarla e il RAID 1 copierebbe il danno.
 * Una share di area sconosciuta conta come non privata: nel dubbio non si dichiara "al sicuro".
 *
 * **Versioni da proteggere**: l'originale e la corrente (E-E1: le intermedie sono opzionali), tra
 * quelle di cui esiste ancora almeno una copia. Una versione che non esiste piu' da nessuna parte non
 * si puo' proteggere e non deve bloccare per sempre lo stato del media.
 */
object SafetyRules {

  /** "Solo telefono / solo NAS / entrambi": contano solo le posizioni presenti. */
  fun presence(media: Media): Presence {
    val present = media.locations.filter { it.isPresent }
    val phone = present.any { it.isPhone }
    val nas = present.any { it.isNas }
    return when {
      phone && nas -> Presence.PHONE_AND_NAS
      phone -> Presence.PHONE_ONLY
      nas -> Presence.NAS_ONLY
      else -> Presence.NONE
    }
  }

  /**
   * Di chi e' un media appena scoperto. Telefono: di chi usa il dispositivo. NAS: del proprietario
   * dell'area privata; nell'area comune non si sa (lo dira' l'indice condiviso della Fase 4) e senza
   * proprietario non si creano copie private di file altrui (5.3: "mai leggere o spostare file privati
   * altrui").
   */
  fun ownerForNewMedia(location: Location, me: AccountId, areas: NasAreaLookup): AccountId? = when (location) {
    is Location.Phone -> me
    is Location.Nas -> if (areas.areaOf(location.account, location.shareKey) == NasArea.PRIVATE) location.account else null
  }

  /**
   * Il proprietario di un media dopo un cambio di posizioni o di configurazione. Un media senza
   * proprietario diventa di chi ne ha una copia presente nella propria area privata (es. l'admin ha
   * appena dichiarato privata una share). Un proprietario **non si perde mai da solo**: un media
   * spostato nell'area comune resta di chi l'ha condiviso, che deve continuare a tenerlo al sicuro.
   */
  fun claimedOwner(media: Media, areas: NasAreaLookup): AccountId? {
    if (media.owner != null) return media.owner
    return media.locations
      .filter { it.isPresent }
      .mapNotNull { it.location as? Location.Nas }
      .filter { areas.areaOf(it.account, it.shareKey) == NasArea.PRIVATE }
      .minByOrNull { it.account.value }
      ?.account
  }

  fun protectedVersions(media: Media): Set<VersionId> = setOf(media.originalVersion.id, media.currentVersionId)

  fun evaluate(media: Media, me: AccountId, areas: NasAreaLookup): SafetyReport {
    val owner = media.owner
    if (owner == null || owner != me) return SafetyReport(SafetyStatus.NOT_MINE, emptyMap())

    val existing = media.locations.filter { it.state.exists }
    if (existing.isEmpty()) return SafetyReport(SafetyStatus.LOST, emptyMap())

    val existingVersions = existing.map { it.versionId }.toSet()
    val toProtect = protectedVersions(media).filter { it in existingVersions }.ifEmpty { existingVersions.toList() }
    val byVersion = toProtect.associateWith { versionSafety(media, media.version(it), owner, areas) }
    return SafetyReport(byVersion.values.maxBy { it.severity }, byVersion)
  }

  /**
   * Se "Libera spazio" puo' togliere la copia del telefono [phoneLocationId] (5.5). Serve una copia
   * sul NAS degli **stessi byte**, verificata non prima di [verifiedSinceMillis] ("verifica sul
   * momento"): per un media proprio nell'area privata, per uno altrui (es. scaricato dall'area comune)
   * ovunque sul NAS. Mai dedotto da altro che dalla verifica: una copia corrotta o mancante non passa.
   */
  fun canFreePhoneCopy(
    media: Media,
    phoneLocationId: LocationId,
    me: AccountId,
    areas: NasAreaLookup,
    verifiedSinceMillis: Long,
  ): FreeSpaceCheck {
    val location = media.locations.firstOrNull { it.id == phoneLocationId }
    if (location == null || !location.isPhone || !location.isPresent) {
      return FreeSpaceCheck.Blocked(FreeSpaceBlock.NOT_A_PRESENT_PHONE_COPY)
    }
    val version = media.version(location.versionId)
    val nasCopies = media.locationsOf(version.id).filter { it.isNas && it.isPresent }
    val mine = media.owner == me
    val eligible = if (mine) nasCopies.filter { isPrivateOf(it, me, areas) } else nasCopies
    val verified = eligible.filter { isVerified(it, version) }
    return when {
      verified.any { it.verifiedAtMillis!! >= verifiedSinceMillis } -> FreeSpaceCheck.Allowed
      verified.isNotEmpty() -> FreeSpaceCheck.Blocked(FreeSpaceBlock.VERIFICATION_TOO_OLD)
      eligible.isNotEmpty() -> FreeSpaceCheck.Blocked(FreeSpaceBlock.PRIVATE_COPY_UNVERIFIED)
      nasCopies.isNotEmpty() -> FreeSpaceCheck.Blocked(FreeSpaceBlock.CREATE_PRIVATE_COPY_FIRST)
      else -> FreeSpaceCheck.Blocked(FreeSpaceBlock.NO_NAS_COPY)
    }
  }

  private fun versionSafety(media: Media, version: MediaVersion, owner: AccountId, areas: NasAreaLookup): SafetyStatus {
    val nasCopies = media.locationsOf(version.id).filter { it.isNas && it.state.exists }
    val privateCopies = nasCopies.filter { isPrivateOf(it, owner, areas) }
    return when {
      privateCopies.any { it.isPresent && isVerified(it, version) } -> SafetyStatus.SAFE
      privateCopies.isNotEmpty() -> SafetyStatus.PRIVATE_COPY_UNVERIFIED
      nasCopies.isNotEmpty() -> SafetyStatus.ONLY_COMMON_COPY
      else -> SafetyStatus.NO_NAS_COPY
    }
  }

  private fun isPrivateOf(location: MediaLocation, owner: AccountId, areas: NasAreaLookup): Boolean {
    val nas = location.location as? Location.Nas ?: return false
    return nas.account == owner && areas.areaOf(nas.account, nas.shareKey) == NasArea.PRIVATE
  }

  /** Verificata = riletta con SHA-256 uguale a quello della versione (che quindi deve essere noto). */
  private fun isVerified(location: MediaLocation, version: MediaVersion): Boolean =
    location.verifiedAtMillis != null && version.fingerprint.fullHash != null
}
