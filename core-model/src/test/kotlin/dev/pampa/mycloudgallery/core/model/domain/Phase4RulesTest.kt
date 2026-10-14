package dev.pampa.mycloudgallery.core.model.domain

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.Fixtures.ME
import dev.pampa.mycloudgallery.core.model.Fixtures.full
import dev.pampa.mycloudgallery.core.model.Fixtures.located
import dev.pampa.mycloudgallery.core.model.Fixtures.media
import dev.pampa.mycloudgallery.core.model.Fixtures.nas
import dev.pampa.mycloudgallery.core.model.Fixtures.observe
import dev.pampa.mycloudgallery.core.model.Fixtures.original
import dev.pampa.mycloudgallery.core.model.Fixtures.phone
import dev.pampa.mycloudgallery.core.model.Fixtures.quick
import dev.pampa.mycloudgallery.core.model.Fixtures.single
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.Presence
import dev.pampa.mycloudgallery.core.model.PresenceBadge
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.WorkStage
import org.junit.Test

/** Le regole aggiunte dalla Fase 4 (P2): presenza nella cella, spostamenti per `FileId`, adozione delle identita' (D7). */
class Phase4RulesTest {

  private val minorMedia = MediaId("00000000-0000-4000-8000-000000000001")
  private val minorVersion = VersionId("00000000-0000-4000-8000-000000000002")
  private val majorMedia = MediaId("ffffffff-ffff-4fff-bfff-ffffffffffff")
  private val majorVersion = VersionId("ffffffff-ffff-4fff-bfff-fffffffffffe")

  private fun nasWithId(path: String, fileId: Long?, share: String = "alessio") = Location.Nas(ME, share, NasPath.of(path), fileId)

  // --- Presenza ------------------------------------------------------------------------------------------------------

  @Test
  fun iBitDiPresenzaVengonoDalleSolePosizioniPresenti() {
    val v = original(full(1))
    fun flags(vararg locations: Pair<Location, LocationState>, safety: SafetyStatus = SafetyStatus.NO_NAS_COPY, pd: Boolean = false): Int {
      val m = media(listOf(v), locations.mapIndexed { i, (l, s) -> located(i + 1L, v, l, s) })
      return TimelineRules.presenceFlags(m, safety, pd)
    }
    assertThat(flags(phone("a.jpg") to LocationState.PRESENT)).isEqualTo(CellFlags.ON_PHONE)
    assertThat(flags(nas("a.jpg") to LocationState.PRESENT)).isEqualTo(CellFlags.ON_NAS)
    assertThat(flags(phone("a.jpg") to LocationState.PRESENT, nas("a.jpg") to LocationState.PRESENT)).isEqualTo(CellFlags.ON_PHONE or CellFlags.ON_NAS)
    assertThat(flags(phone("a.jpg") to LocationState.ABSENT, nas("a.jpg") to LocationState.UNAVAILABLE)).isEqualTo(0)
    assertThat(flags(nas("a.jpg") to LocationState.PRESENT, safety = SafetyStatus.SAFE)).isEqualTo(CellFlags.ON_NAS or CellFlags.SAFE)
    assertThat(flags(phone("a.jpg") to LocationState.PRESENT, pd = true)).isEqualTo(CellFlags.ON_PHONE or CellFlags.TO_VERIFY)
    assertThat(PresenceBadge.of(flags(phone("a.jpg") to LocationState.PRESENT, pd = true))).isEqualTo(PresenceBadge.TO_VERIFY)
  }

  /** La migrazione 2 -> 3 calcola i bit dai derivati salvati: la stessa risposta del calcolo dal grafo. */
  @Test
  fun iBitDaiDerivatiSalvatiCoincidonoConQuelliDalGrafo() {
    val v = original(full(1))
    val cases = listOf(
      listOf(phone("a.jpg") to LocationState.PRESENT),
      listOf(nas("a.jpg") to LocationState.PRESENT),
      listOf(phone("a.jpg") to LocationState.PRESENT, nas("a.jpg") to LocationState.PRESENT),
      listOf(phone("a.jpg") to LocationState.ABSENT),
    )
    for (locations in cases) {
      val m = media(listOf(v), locations.mapIndexed { i, (l, s) -> located(i + 1L, v, l, s) })
      for (safety in SafetyStatus.entries) {
        assertThat(TimelineRules.presenceFlags(SafetyRules.presence(m), safety, false)).isEqualTo(TimelineRules.presenceFlags(m, safety, false))
      }
    }
    assertThat(TimelineRules.presenceFlags(Presence.NONE, SafetyStatus.LOST, false)).isEqualTo(0)
  }

  // --- E-N1: spostamento riconosciuto dal FileId ---------------------------------------------------------------------

  @Test
  fun unoSpostamentoSulNasSiRiconosceDalloStessoFileIdNellaStessaShare() {
    val gone = single(quick(1), nasWithId("Foto/A.jpg", 42), state = LocationState.ABSENT)
    val known = KnownLocation(gone, gone.locations.single().id)
    fun decide(location: Location.Nas) =
      LinkingRules.decide(observe(location, quick(1), modifiedAt = 99), LinkContext(sameContent = emptyList(), vanished = listOf(known)))

    assertThat(decide(nasWithId("Archivio/A.jpg", 42))).isInstanceOf(LinkDecision.Moved::class.java)
    // Senza FileId, o con un FileId diverso, o in un'altra share (altro volume): nessuna prova, nessuno spostamento.
    assertThat(decide(nasWithId("Archivio/A.jpg", null))).isEqualTo(LinkDecision.NewMedia)
    assertThat(decide(nasWithId("Archivio/A.jpg", 43))).isEqualTo(LinkDecision.NewMedia)
    assertThat(decide(nasWithId("Archivio/A.jpg", 42, share = "NAS"))).isEqualTo(LinkDecision.NewMedia)
  }

  @Test
  fun laChiaveDellaCartellaDelNasCoincideConQuellaDelleSuePosizioni() {
    val account = AccountId.of("alessio")
    val file = Location.Nas(account, "Alessio", NasPath.of("Foto/2024/IMG_1.JPG"))
    assertThat(Location.Nas.folderKey(account, "ALESSIO", NasPath.of("foto/2024"))).isEqualTo(file.folderKey)
    assertThat(Location.Nas.folderKey(account, "alessio", null)).isEqualTo(Location.Nas(account, "alessio", NasPath.of("a.jpg")).folderKey)
    assertThat(Location.Nas.shareKeyOf("NAS")).isEqualTo(Location.Nas(account, "nas", NasPath.of("a.jpg")).shareKey)
  }

  // --- D7: adozione delle identita' ----------------------------------------------------------------------------------

  private fun nasOnly(seed: Int = 1, id: MediaId = MediaId.random()) = single(full(seed), nas("Foto/A.jpg"), id = id)

  private fun decide(local: dev.pampa.mycloudgallery.core.model.Media, remote: RemoteIdentity, here: dev.pampa.mycloudgallery.core.model.Media? = null, owner: dev.pampa.mycloudgallery.core.model.Media? = null) =
    IdentityRules.decide(local, local.locations.first().id, remote, here, owner)

  @Test
  fun siAdottanoGliIdRemotiMinoriESiTengonoIMinoriLocali() {
    val local = nasOnly()
    assertThat(decide(local, RemoteIdentity(minorMedia, minorVersion, full(1)))).isEqualTo(AdoptionDecision.Rename(minorMedia, minorVersion))
    assertThat(decide(local, RemoteIdentity(majorMedia, majorVersion, full(1)))).isEqualTo(AdoptionDecision.KeepLocal)
    assertThat(decide(local, RemoteIdentity(minorMedia, majorVersion, full(1)))).isEqualTo(AdoptionDecision.Rename(minorMedia, null))
    // Media gia' lo stesso: solo la versione, se la remota e' minore.
    assertThat(decide(local, RemoteIdentity(local.id, minorVersion, full(1)), here = local)).isEqualTo(AdoptionDecision.Rename(null, minorVersion))
    assertThat(decide(local, RemoteIdentity(local.id, local.currentVersionId, full(1)), here = local, owner = local))
      .isEqualTo(AdoptionDecision.AlreadyShared)
    // Basta l'impronta rapida per dire "stesso contenuto" del posto (il record non porta sempre l'hash completo).
    assertThat(decide(local, RemoteIdentity(minorMedia, minorVersion, quick(1)))).isEqualTo(AdoptionDecision.Rename(minorMedia, minorVersion))
  }

  @Test
  fun unMediaConCopieSulTelefonoTieneLaSuaIdentita() {
    val v = original(full(1))
    val local = media(listOf(v), listOf(located(1, v, nas("Foto/A.jpg")), located(2, v, phone("A.jpg"))))
    assertThat(decide(local, RemoteIdentity(minorMedia, minorVersion, full(1)))).isEqualTo(AdoptionDecision.KeepLocal)
  }

  @Test
  fun unIdRemotoLegatoQuiAUnAltroContenutoERifiutato() {
    val local = nasOnly(seed = 1)
    val mine = single(full(2), phone("B.jpg"))
    // L'id del media remoto e' una mia foto con altri byte.
    assertThat(decide(local, RemoteIdentity(mine.id, minorVersion, full(1)), here = mine))
      .isEqualTo(AdoptionDecision.Refuse(AdoptRefusal.ID_BOUND_TO_OTHER_CONTENT))
    // L'id della versione remota e' una versione di una mia foto con altri byte.
    assertThat(decide(local, RemoteIdentity(minorMedia, mine.currentVersionId, full(1)), owner = mine))
      .isEqualTo(AdoptionDecision.Refuse(AdoptRefusal.ID_BOUND_TO_OTHER_CONTENT))
    // Il record non descrive i byte del posto.
    assertThat(decide(local, RemoteIdentity(minorMedia, minorVersion, full(3)))).isEqualTo(AdoptionDecision.Refuse(AdoptRefusal.CONTENT_MISMATCH))
    assertThat(AdoptRefusal.entries.filter { it.isConflict })
      .containsExactly(AdoptRefusal.ID_BOUND_TO_OTHER_CONTENT, AdoptRefusal.ID_BOUND_ELSEWHERE)
  }

  @Test
  fun unIdRemotoDiUnAltroMediaConGliStessiByteChiedeLUnione() {
    val local = nasOnly(seed = 1)
    val other = single(full(1), nas("Copia/A.jpg", share = "NAS"), locationId = 2)
    assertThat(decide(local, RemoteIdentity(other.id, minorVersion, full(1)), here = other)).isEqualTo(AdoptionDecision.MergeWith(other.id))
    // La versione remota sta qui in un terzo media: incoerente, rifiutata.
    val third = single(full(1), nas("Terza/A.jpg"), locationId = 3)
    assertThat(decide(local, RemoteIdentity(other.id, third.currentVersionId, full(1)), here = other, owner = third))
      .isEqualTo(AdoptionDecision.Refuse(AdoptRefusal.ID_BOUND_ELSEWHERE))
  }

  @Test
  fun leFasiNuoveNonSonoFraQuelleIniziali() {
    assertThat(WorkRules.initialStages(dev.pampa.mycloudgallery.core.model.MediaKind.VIDEO)).containsNoneOf(WorkStage.HQ, WorkStage.FULL_HASH, WorkStage.FACES)
    assertThat(WorkStage.HQ.code).isEqualTo("hq")
    assertThat(WorkStage.FULL_HASH.code).isEqualTo("full_hash")
  }
}
