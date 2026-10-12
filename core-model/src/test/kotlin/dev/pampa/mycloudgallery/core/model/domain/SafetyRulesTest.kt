package dev.pampa.mycloudgallery.core.model.domain

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.Fixtures.AREAS
import dev.pampa.mycloudgallery.core.model.Fixtures.DAY
import dev.pampa.mycloudgallery.core.model.Fixtures.ME
import dev.pampa.mycloudgallery.core.model.Fixtures.OTHER
import dev.pampa.mycloudgallery.core.model.Fixtures.T0
import dev.pampa.mycloudgallery.core.model.Fixtures.full
import dev.pampa.mycloudgallery.core.model.Fixtures.located
import dev.pampa.mycloudgallery.core.model.Fixtures.media
import dev.pampa.mycloudgallery.core.model.Fixtures.nas
import dev.pampa.mycloudgallery.core.model.Fixtures.original
import dev.pampa.mycloudgallery.core.model.Fixtures.phone
import dev.pampa.mycloudgallery.core.model.Fixtures.quick
import dev.pampa.mycloudgallery.core.model.Fixtures.single
import dev.pampa.mycloudgallery.core.model.Fixtures.stamp
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.Media
import dev.pampa.mycloudgallery.core.model.Presence
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import org.junit.Test

class SafetyRulesTest {

  private fun status(media: Media) = SafetyRules.evaluate(media, ME, AREAS).status

  /** Telefono (1) + copia sul NAS (2) nella share indicata, opzionalmente verificata. */
  private fun withNasCopy(share: String, verifiedAt: Long? = T0, phoneState: LocationState = LocationState.PRESENT): Media {
    val v = original(full(1))
    return media(listOf(v), listOf(located(1, v, phone("a.jpg", 10), phoneState), located(2, v, nas("Foto/a.jpg", share), verifiedAt = verifiedAt)))
  }

  @Test
  fun soloSulTelefonoNonEAlSicuro() {
    val media = single(full(1), phone("a.jpg", 10))
    assertThat(SafetyRules.presence(media)).isEqualTo(Presence.PHONE_ONLY)
    assertThat(status(media)).isEqualTo(SafetyStatus.NO_NAS_COPY)
  }

  @Test
  fun conUnaCopiaPrivataVerificataEAlSicuro() {
    val media = withNasCopy("alessio")
    assertThat(SafetyRules.presence(media)).isEqualTo(Presence.PHONE_AND_NAS)
    assertThat(status(media)).isEqualTo(SafetyStatus.SAFE)
  }

  @Test
  fun unaCopiaPrivataMaiRilettaNonBasta() {
    assertThat(status(withNasCopy("alessio", verifiedAt = null))).isEqualTo(SafetyStatus.PRIVATE_COPY_UNVERIFIED)

    // "Verificata" senza un hash completo della versione non ha senso: non conta.
    val v = original(quick(1))
    val noFullHash = media(listOf(v), listOf(located(1, v, phone("a.jpg", 10)), located(2, v, nas("a.jpg"), verifiedAt = T0)))
    assertThat(status(noFullHash)).isEqualTo(SafetyStatus.PRIVATE_COPY_UNVERIFIED)
  }

  /** E-B13: condiviso (spostato nell'area comune) e poi cancellato dal telefono. */
  @Test
  fun unMediaCondivisoECancellatoDalTelefonoChiedeDiRicreareLaCopiaPrivata() {
    val shared = withNasCopy("NAS")
    assertThat(status(shared)).isEqualTo(SafetyStatus.ONLY_COMMON_COPY)

    val deletedFromPhone = shared.copy(locations = shared.locations.map { if (it.isPhone) LocationRules.vanished(it, T0 + DAY) else it })
    assertThat(SafetyRules.presence(deletedFromPhone)).isEqualTo(Presence.NAS_ONLY)
    assertThat(status(deletedFromPhone)).isEqualTo(SafetyStatus.ONLY_COMMON_COPY)

    // L'app ricrea la copia privata e la verifica: di nuovo al sicuro, stesso media.
    val v = deletedFromPhone.currentVersion
    val recreated = deletedFromPhone.copy(locations = deletedFromPhone.locations + located(3, v, nas("Backup/a.jpg"), verifiedAt = T0 + DAY))
    assertThat(status(recreated)).isEqualTo(SafetyStatus.SAFE)
  }

  @Test
  fun unaShareDiAreaSconosciutaNonContaComePrivata() {
    assertThat(status(withNasCopy("musica"))).isEqualTo(SafetyStatus.ONLY_COMMON_COPY)
  }

  @Test
  fun laShareDiUnAltroAccountNonEPrivataPerMe() {
    val v = original(full(1))
    val media = media(listOf(v), listOf(located(1, v, phone("a.jpg", 10)), located(2, v, nas("a.jpg", share = "pampa", account = OTHER), verifiedAt = T0)))
    assertThat(status(media)).isEqualTo(SafetyStatus.ONLY_COMMON_COPY)
  }

  /** Una modifica non ancora salvata sul NAS rende il media non al sicuro, anche se l'originale lo e'. */
  @Test
  fun unaModificaNonAncoraSalvataNonEAlSicuro() {
    val edited = VersionRules.contentChangedAt(withNasCopy("alessio"), LocationId(1), full(2), stamp(full(2), T0 + 1), T0 + 1).media

    val report = SafetyRules.evaluate(edited, ME, AREAS)

    assertThat(report.status).isEqualTo(SafetyStatus.NO_NAS_COPY)
    assertThat(report.byVersion[edited.originalVersion.id]).isEqualTo(SafetyStatus.SAFE)
    assertThat(report.byVersion[edited.currentVersionId]).isEqualTo(SafetyStatus.NO_NAS_COPY)
  }

  /** Un originale che non esiste piu' da nessuna parte non si puo' proteggere e non blocca lo stato. */
  @Test
  fun unOriginaleSparitoOvunqueNonBloccaPerSempreLoStato() {
    val v1 = original(full(1))
    val edited = VersionRules.contentChangedAt(
      media(listOf(v1), listOf(located(1, v1, phone("a.jpg", 10)))),
      LocationId(1), full(2), stamp(full(2), T0 + 1), T0 + 1,
    ).media
    val v2 = edited.currentVersion
    val backedUp = edited.copy(locations = edited.locations + located(2, v2, nas("a.jpg"), verifiedAt = T0 + 2))

    val report = SafetyRules.evaluate(backedUp, ME, AREAS)
    assertThat(report.status).isEqualTo(SafetyStatus.SAFE)
    assertThat(report.byVersion.keys).containsExactly(v2.id)
  }

  @Test
  fun iMediaAltruiONonAttribuitiNonSonoAffareMio() {
    assertThat(status(single(full(1), nas("a.jpg", share = "NAS"), owner = null))).isEqualTo(SafetyStatus.NOT_MINE)
    assertThat(status(single(full(1), nas("a.jpg", share = "NAS"), owner = OTHER))).isEqualTo(SafetyStatus.NOT_MINE)
  }

  /** E-B4: cancellata prima del backup, nessuna copia rimasta. */
  @Test
  fun senzaNessunaCopiaIlMediaEPerso() {
    val gone = single(full(1), phone("a.jpg", 10), state = LocationState.ABSENT)
    assertThat(status(gone)).isEqualTo(SafetyStatus.LOST)
    assertThat(SafetyRules.presence(gone)).isEqualTo(Presence.NONE)
  }

  /** E-P6: una microSD tolta non rende il media perso; la copia esiste, solo non e' raggiungibile. */
  @Test
  fun unaMicroSdToltaNonEUnaPerdita() {
    val onSd = single(full(1), phone("a.jpg", 10, volume = "1a2b-3c4d"), state = LocationState.UNAVAILABLE)
    assertThat(SafetyRules.presence(onSd)).isEqualTo(Presence.NONE)
    assertThat(status(onSd)).isEqualTo(SafetyStatus.NO_NAS_COPY)
  }

  /** Rimuovere la posizione del telefono lascia il media "solo NAS", al sicuro, con la stessa identita'. */
  @Test
  fun cancellareDalTelefonoLasciaIlMediaSoloSulNasEAlSicuro() {
    val media = withNasCopy("alessio")
    val afterDelete = media.copy(locations = media.locations.filter { it.isNas })
    assertThat(afterDelete.id).isEqualTo(media.id)
    assertThat(SafetyRules.presence(afterDelete)).isEqualTo(Presence.NAS_ONLY)
    assertThat(status(afterDelete)).isEqualTo(SafetyStatus.SAFE)
  }

  // --- "Libera spazio" (5.5, E-B8, E-B14) ----------------------------------------------------------

  @Test
  fun liberaSpazioRichiedeUnaCopiaPrivataVerificataSulMomento() {
    val since = T0
    fun check(media: Media) = SafetyRules.canFreePhoneCopy(media, LocationId(1), ME, AREAS, since)

    assertThat(check(withNasCopy("alessio", verifiedAt = T0))).isEqualTo(FreeSpaceCheck.Allowed)
    assertThat(check(withNasCopy("alessio", verifiedAt = T0 - 1)))
      .isEqualTo(FreeSpaceCheck.Blocked(FreeSpaceBlock.VERIFICATION_TOO_OLD))
    // E-B8: copia non piu' integra (la verifica e' stata annullata).
    assertThat(check(withNasCopy("alessio", verifiedAt = null)))
      .isEqualTo(FreeSpaceCheck.Blocked(FreeSpaceBlock.PRIVATE_COPY_UNVERIFIED))
    // E-B14: unica copia nell'area comune, prima si crea quella privata.
    assertThat(check(withNasCopy("NAS")))
      .isEqualTo(FreeSpaceCheck.Blocked(FreeSpaceBlock.CREATE_PRIVATE_COPY_FIRST))
    assertThat(check(single(full(1), phone("a.jpg", 10))))
      .isEqualTo(FreeSpaceCheck.Blocked(FreeSpaceBlock.NO_NAS_COPY))
    assertThat(SafetyRules.canFreePhoneCopy(withNasCopy("alessio"), LocationId(2), ME, AREAS, since))
      .isEqualTo(FreeSpaceCheck.Blocked(FreeSpaceBlock.NOT_A_PRESENT_PHONE_COPY))
  }

  @Test
  fun liberaSpazioGuardaIByteDellaCopiaSulTelefonoNonLaVersioneCorrente() {
    // Sul telefono c'e' la modifica, sul NAS solo l'originale: la modifica non si puo' liberare.
    val edited = VersionRules.contentChangedAt(withNasCopy("alessio"), LocationId(1), full(2), stamp(full(2), T0 + 1), T0 + 1).media
    assertThat(SafetyRules.canFreePhoneCopy(edited, LocationId(1), ME, AREAS, T0))
      .isEqualTo(FreeSpaceCheck.Blocked(FreeSpaceBlock.NO_NAS_COPY))
  }

  @Test
  fun unaCopiaScaricataDiUnMediaAltruiSiLiberaSeIlNasNeHaUnaVerificata() {
    val v = original(full(1))
    val theirs = media(
      listOf(v),
      listOf(located(1, v, phone("a.jpg", 10, path = "Pictures/MyCloud Gallery/")), located(2, v, nas("a.jpg", share = "NAS"), verifiedAt = T0)),
      owner = null,
    )
    assertThat(SafetyRules.canFreePhoneCopy(theirs, LocationId(1), ME, AREAS, T0)).isEqualTo(FreeSpaceCheck.Allowed)
  }

  @Test
  fun unProprietarioSiAcquisisceDaUnaCopiaPrivataMaNonSiPerdeMai() {
    val unowned = single(full(1), nas("a.jpg", share = "alessio"), owner = null)
    assertThat(SafetyRules.claimedOwner(unowned, AREAS)).isEqualTo(ME)
    assertThat(SafetyRules.claimedOwner(single(full(1), nas("a.jpg", share = "NAS"), owner = null), AREAS)).isNull()
    // Gia' mio e ora solo nell'area comune (condiviso): resta mio.
    assertThat(SafetyRules.claimedOwner(single(full(1), nas("a.jpg", share = "NAS"), owner = ME), AREAS)).isEqualTo(ME)
  }

  @Test
  fun ilProprietarioDiUnMediaNuovoDipendeDalPosto() {
    assertThat(SafetyRules.ownerForNewMedia(phone("a.jpg"), ME, AREAS)).isEqualTo(ME)
    assertThat(SafetyRules.ownerForNewMedia(nas("a.jpg", share = "alessio"), ME, AREAS)).isEqualTo(ME)
    assertThat(SafetyRules.ownerForNewMedia(nas("a.jpg", share = "NAS"), ME, AREAS)).isNull()
    assertThat(SafetyRules.ownerForNewMedia(nas("a.jpg", share = "musica"), ME, AREAS)).isNull()
  }
}
