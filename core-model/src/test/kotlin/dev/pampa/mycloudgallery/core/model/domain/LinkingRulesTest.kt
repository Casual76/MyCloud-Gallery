package dev.pampa.mycloudgallery.core.model.domain

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.Fixtures.T0
import dev.pampa.mycloudgallery.core.model.Fixtures.content
import dev.pampa.mycloudgallery.core.model.Fixtures.contentChangedInTheMiddle
import dev.pampa.mycloudgallery.core.model.Fixtures.full
import dev.pampa.mycloudgallery.core.model.Fixtures.located
import dev.pampa.mycloudgallery.core.model.Fixtures.media
import dev.pampa.mycloudgallery.core.model.Fixtures.nas
import dev.pampa.mycloudgallery.core.model.Fixtures.observe
import dev.pampa.mycloudgallery.core.model.Fixtures.original
import dev.pampa.mycloudgallery.core.model.Fixtures.phone
import dev.pampa.mycloudgallery.core.model.Fixtures.quick
import dev.pampa.mycloudgallery.core.model.Fixtures.single
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.Media
import dev.pampa.mycloudgallery.core.model.MediaVersion
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.VersionKind
import org.junit.Test

class LinkingRulesTest {

  private fun known(media: Media, locationId: Long = 1, capture: Long? = null) =
    KnownLocation(media, LocationId(locationId), capture)

  private fun candidate(media: Media) = ContentCandidate(media.id, media.currentVersion)

  @Test
  fun unFileMaiVistoCreaUnMediaNuovo() {
    val decision = LinkingRules.decide(observe(phone("a.jpg", 10), full(1)), LinkContext())
    assertThat(decision).isEqualTo(LinkDecision.NewMedia)
  }

  // --- Telefono: cambio di ID MediaStore (E-P12) -------------------------------------------------

  @Test
  fun stessoPostoEStessiByteConUnIdMediaStoreNuovoSonoLoStessoFile() {
    val existing = single(full(1), phone("a.jpg", mediaStoreId = 10))
    val renumbered = observe(phone("a.jpg", mediaStoreId = 1_000_000_353, storeVersion = "store-v2"), quick(1))

    val decision = LinkingRules.decide(renumbered, LinkContext(atSameKey = known(existing)))

    assertThat(decision).isEqualTo(LinkDecision.SameFile(LocationId(1), existing.id, reappeared = false, stampChanged = false))
  }

  @Test
  fun rinominatoSulTelefonoConLaStessaRigaMediaStoreEUnoSpostamento() {
    val existing = single(full(1), phone("a.jpg", mediaStoreId = 10))
    val renamed = observe(phone("Viaggio.jpg", mediaStoreId = 10, path = "Pictures/"), quick(1))

    val decision = LinkingRules.decide(renamed, LinkContext(atSameMediaStoreId = known(existing)))

    assertThat(decision).isEqualTo(LinkDecision.Moved(LocationId(1), existing.id, LinkEvidence.MEDIASTORE_ROW))
  }

  /** Dopo una ricostruzione del MediaStore gli ID ripartono: lo stesso numero puo' essere di un altro file. */
  @Test
  fun unIdMediaStoreLettoConUnAltraVersioneNonProvaNulla() {
    val existing = single(full(1), phone("a.jpg", mediaStoreId = 10))
    val otherFileSameId = observe(phone("z.jpg", mediaStoreId = 10, storeVersion = "store-v2"), full(2))

    assertThat(LinkingRules.decide(otherFileSameId, LinkContext(atSameMediaStoreId = known(existing))))
      .isEqualTo(LinkDecision.NewMedia)

    // Anche con byte compatibili l'ID non basta: decide il contenuto (qui l'hash completo).
    val sameBytes = observe(phone("z.jpg", mediaStoreId = 10, storeVersion = "store-v2"), full(1))
    val decision = LinkingRules.decide(
      sameBytes,
      LinkContext(atSameMediaStoreId = known(existing), sameContent = listOf(candidate(existing))),
    )
    assertThat(decision).isEqualTo(LinkDecision.NewLocation(existing.id, existing.currentVersionId, LinkEvidence.FULL_HASH))
  }

  // --- Telefono: modifiche e versioni --------------------------------------------------------------

  @Test
  fun modificaSulTelefonoConLaStessaRigaEUnaNuovaVersioneDelloStessoMedia() {
    val existing = single(full(1), phone("a.jpg", mediaStoreId = 10))
    val edited = observe(phone("a.jpg", mediaStoreId = 10), full(2), modifiedAt = T0 + 1_000)

    val decision = LinkingRules.decide(edited, LinkContext(atSameKey = known(existing)))

    assertThat(decision).isEqualTo(LinkDecision.ContentChanged(LocationId(1), existing.id, existing.currentVersionId))
  }

  @Test
  fun modificaSalvataComeRigaNuovaMaConLaStessaDataDiScattoEUnaNuovaVersione() {
    val existing = single(full(1), phone("a.jpg", mediaStoreId = 10))
    val edited = observe(phone("a.jpg", mediaStoreId = 11), full(2), captureTime = 1_234)

    val decision = LinkingRules.decide(edited, LinkContext(atSameKey = known(existing, capture = 1_234)))

    assertThat(decision).isInstanceOf(LinkDecision.ContentChanged::class.java)
  }

  /** Nel dubbio non si unisce: un altro file con lo stesso nome non eredita album e preferiti. */
  @Test
  fun unFileDiversoConLoStessoNomeESenzaContinuitaSostituisceLaPosizione() {
    val existing = single(full(1), phone("IMG-20261012-WA0001.jpg", mediaStoreId = 10))
    val another = observe(phone("IMG-20261012-WA0001.jpg", mediaStoreId = 11), full(2), captureTime = 9_999)

    val decision = LinkingRules.decide(another, LinkContext(atSameKey = known(existing, capture = 1_234)))

    assertThat(decision).isEqualTo(LinkDecision.Replaced(LocationId(1), existing.id, LinkDecision.NewMedia))
  }

  @Test
  fun unFileCheRiappareConByteDiversiDopoEssereSparitoNonEUnaModifica() {
    val existing = single(full(1), phone("a.jpg", mediaStoreId = 10), state = LocationState.ABSENT)
    val decision = LinkingRules.decide(observe(phone("a.jpg", mediaStoreId = 10), full(2)), LinkContext(atSameKey = known(existing)))
    assertThat(decision).isInstanceOf(LinkDecision.Replaced::class.java)
  }

  /** E-E2: "Ripristina originale" di Samsung Gallery riporta i byte dell'originale nello stesso file. */
  @Test
  fun ripristinaOriginaleDaSamsungGalleryETornaAllaVersioneNota() {
    val v1 = original(full(1))
    val v2 = MediaVersion(VersionId.random(), VersionKind.EDITED, v1.id, full(2), T0 + 1_000)
    val existing = media(listOf(v1, v2), listOf(located(1, v2, phone("a.jpg", mediaStoreId = 10))), current = v2.id)

    val decision = LinkingRules.decide(observe(phone("a.jpg", mediaStoreId = 10), quick(1)), LinkContext(atSameKey = known(existing)))

    assertThat(decision).isEqualTo(LinkDecision.RevertedToVersion(LocationId(1), existing.id, v1.id))
  }

  /** E-P11: la foto ripristinata dal cestino di Galleria torna nello stesso posto con un ID nuovo. */
  @Test
  fun fotoRipristinataDalCestinoDiGalleriaSiRicollegaAlloStessoMedia() {
    val existing = single(full(1), phone("a.jpg", mediaStoreId = 10), state = LocationState.ABSENT)

    val decision = LinkingRules.decide(observe(phone("a.jpg", mediaStoreId = 77), quick(1)), LinkContext(atSameKey = known(existing)))

    assertThat(decision).isEqualTo(LinkDecision.SameFile(LocationId(1), existing.id, reappeared = true, stampChanged = false))
  }

  @Test
  fun stessiByteConUnTimbroDiversoRestanoLoStessoFileMaLaVerificaDecade() {
    val existing = single(full(1), nas("Foto/a.jpg"), verifiedAt = T0)
    val touched = observe(nas("Foto/a.jpg"), quick(1), modifiedAt = T0 + 60_000)

    val decision = LinkingRules.decide(touched, LinkContext(atSameKey = known(existing))) as LinkDecision.SameFile

    assertThat(decision.stampChanged).isTrue()
  }

  // --- NAS -----------------------------------------------------------------------------------------

  /** E-N2 / E-E6: modificato dal PC, nello stesso percorso. */
  @Test
  fun fileModificatoDalPcSulNasEUnaNuovaVersione() {
    val existing = single(full(1), nas("Foto/a.jpg"))
    val decision = LinkingRules.decide(observe(nas("Foto/a.jpg"), quick(2), modifiedAt = T0 + 5), LinkContext(atSameKey = known(existing)))
    assertThat(decision).isEqualTo(LinkDecision.ContentChanged(LocationId(1), existing.id, existing.currentVersionId))
  }

  /** E-N7: la scansione vede il file con un'altra combinazione di maiuscole. */
  @Test
  fun maiuscoleDiverseSulNasNonCreanoUnDoppioneFantasma() {
    val existing = single(full(1), nas("Foto/IMG_0001.JPG"))
    val observed = observe(nas("foto/img_0001.jpg"), quick(1))

    val decision = LinkingRules.decide(observed, LinkContext(atSameKey = known(existing)))

    assertThat(decision).isEqualTo(LinkDecision.SameFile(LocationId(1), existing.id, reappeared = false, stampChanged = false))
  }

  /** E-L3: identici in piu' cartelle = un media solo con piu' posizioni. */
  @Test
  fun stessiByteInUnAltraCartellaConHashCompletoUgualeSonoUnaPosizioneInPiu() {
    val existing = single(full(1), nas("Foto/a.jpg"))
    val copy = observe(nas("Copia di sicurezza/a.jpg"), full(1))

    val decision = LinkingRules.decide(copy, LinkContext(sameContent = listOf(candidate(existing))))

    assertThat(decision).isEqualTo(LinkDecision.NewLocation(existing.id, existing.currentVersionId, LinkEvidence.FULL_HASH))
  }

  @Test
  fun conIlSoloHashRapidoUgualeNonSiUnisceMaSiChiedeLHashCompleto() {
    val withFull = single(full(1), nas("Foto/a.jpg"))
    val observedQuickOnly = LinkingRules.decide(
      observe(nas("Copia/a.jpg"), quick(1)),
      LinkContext(sameContent = listOf(candidate(withFull))),
    )
    assertThat(observedQuickOnly).isEqualTo(LinkDecision.NeedsFullHash(listOf(candidate(withFull)), observedHashMissing = true))

    val quickOnly = single(quick(1), nas("Foto/a.jpg"))
    val candidateQuickOnly = LinkingRules.decide(
      observe(nas("Copia/a.jpg"), full(1)),
      LinkContext(sameContent = listOf(candidate(quickOnly))),
    )
    assertThat(candidateQuickOnly).isEqualTo(LinkDecision.NeedsFullHash(listOf(candidate(quickOnly)), observedHashMissing = false))
  }

  @Test
  fun stessoHashRapidoMaHashCompletoDiversoEUnMediaDiverso() {
    val existing = single(Fingerprint.ofContent(content(1)), nas("Foto/a.jpg"))
    val lookalike = observe(nas("Altro/a.jpg"), Fingerprint.ofContent(contentChangedInTheMiddle(1)))

    val decision = LinkingRules.decide(lookalike, LinkContext(sameContent = listOf(candidate(existing))))

    assertThat(decision).isEqualTo(LinkDecision.NewMedia)
  }

  /** E-L1 / E-B5: la foto del telefono e' gia' sul NAS (es. backup fatto da un'altra installazione). */
  @Test
  fun fotoDelTelefonoGiaSulNasSiCollegaAlMediaDelNas() {
    val onNas = single(full(1), nas("Backup/S25/2026/10/a.jpg"), verifiedAt = T0)

    val decision = LinkingRules.decide(observe(phone("a.jpg", mediaStoreId = 10), full(1)), LinkContext(sameContent = listOf(candidate(onNas))))

    assertThat(decision).isEqualTo(LinkDecision.NewLocation(onNas.id, onNas.currentVersionId, LinkEvidence.FULL_HASH))
  }

  @Test
  fun piuMediaConGliStessiByteSiCollegaAlMinoreESiSegnalanoGliAltriDaUnire() {
    val a = single(full(1), nas("x/a.jpg"))
    val b = single(full(1), nas("y/a.jpg"), locationId = 2)
    val (low, high) = listOf(a, b).sortedBy { it.id }

    val decision = LinkingRules.decide(observe(nas("z/a.jpg"), full(1)), LinkContext(sameContent = listOf(candidate(high), candidate(low))))

    assertThat(decision).isEqualTo(LinkDecision.NewLocation(low.id, low.currentVersionId, LinkEvidence.FULL_HASH, listOf(high.id)))
  }

  // --- Spostamenti (E-N1) --------------------------------------------------------------------------

  /** Rinominare una cartella dal PC: stesso media, stessa versione, nessuna preparazione da rifare. */
  @Test
  fun cartellaRinominataDalPcEUnoSpostamentoNonUnMediaNuovo() {
    val gone = single(quick(1), nas("Vecchia/a.jpg"), state = LocationState.ABSENT)
    val appeared = observe(nas("Nuova/a.jpg"), quick(1), modifiedAt = T0)

    val decision = LinkingRules.decide(appeared, LinkContext(vanished = listOf(known(gone)), sameContent = listOf(candidate(gone))))

    assertThat(decision).isEqualTo(LinkDecision.Moved(LocationId(1), gone.id, LinkEvidence.MOVE_DETECTED))
  }

  @Test
  fun senzaUnIndizioIndipendenteNonEUnoSpostamento() {
    val gone = single(quick(1), nas("Vecchia/a.jpg"), state = LocationState.ABSENT)
    val appeared = observe(nas("Nuova/a.jpg"), quick(1), modifiedAt = T0 + 1)

    val decision = LinkingRules.decide(appeared, LinkContext(vanished = listOf(known(gone)), sameContent = listOf(candidate(gone))))

    assertThat(decision).isInstanceOf(LinkDecision.NeedsFullHash::class.java)
  }

  @Test
  fun spostamentoAmbiguoTraDueMediaDiversiRichiedeLHashCompleto() {
    val a = single(quick(1), nas("x/a.jpg"), state = LocationState.ABSENT)
    val b = single(quick(1), nas("y/a.jpg"), locationId = 2, state = LocationState.ABSENT)

    val decision = LinkingRules.decide(observe(nas("z/a.jpg"), quick(1)), LinkContext(vanished = listOf(known(a), known(b, 2))))

    assertThat(decision).isInstanceOf(LinkDecision.NeedsFullHash::class.java)
    assertThat((decision as LinkDecision.NeedsFullHash).candidates.map { it.mediaId }).containsExactly(a.id, b.id)
  }

  @Test
  fun spostamentoSulTelefonoConDataDiScattoUgualeAncheSeLaDataDelFileCambia() {
    val gone = single(quick(1), phone("a.jpg", mediaStoreId = 10), state = LocationState.ABSENT)
    val appeared = observe(phone("a.jpg", mediaStoreId = 500, path = "Pictures/Viaggio/"), quick(1), modifiedAt = T0 + 99, captureTime = 555)

    val decision = LinkingRules.decide(appeared, LinkContext(vanished = listOf(known(gone, capture = 555))))

    assertThat(decision).isEqualTo(LinkDecision.Moved(LocationId(1), gone.id, LinkEvidence.MOVE_DETECTED))
  }

  @Test
  fun unFileComparsoSulTelefonoNonELoSpostamentoDiUnFileSparitoDalNas() {
    val gone = single(quick(1), nas("a.jpg"), state = LocationState.ABSENT)
    val decision = LinkingRules.decide(observe(phone("a.jpg", mediaStoreId = 10), quick(1)), LinkContext(vanished = listOf(known(gone))))
    assertThat(decision).isEqualTo(LinkDecision.NewMedia)
  }

  @Test
  fun unFileArrivatoInUnPostoOccupatoOVuotoSegueLeRegoleDelPosto() {
    // Nel posto c'era un file; ne arriva un altro, che e' quello sparito da un'altra cartella.
    val occupant = single(full(1), nas("Foto/a.jpg"))
    val gone = single(quick(2), nas("Vecchia/b.jpg"), locationId = 2, state = LocationState.ABSENT)
    val arriving = observe(nas("Foto/a.jpg"), quick(2), modifiedAt = T0)

    // Sul NAS un file presente che cambia e' una modifica: la regola lo tratta cosi'.
    val decision = LinkingRules.decide(arriving, LinkContext(atSameKey = known(occupant), vanished = listOf(known(gone, 2))))
    assertThat(decision).isInstanceOf(LinkDecision.ContentChanged::class.java)

    // Se invece il posto era gia' vuoto (assente), i byte nuovi seguono il loro media.
    val emptied = single(full(1), nas("Foto/a.jpg"), state = LocationState.ABSENT)
    val replaced = LinkingRules.decide(arriving, LinkContext(atSameKey = known(emptied), vanished = listOf(known(gone, 2))))
    assertThat(replaced).isEqualTo(
      LinkDecision.Replaced(LocationId(1), emptied.id, LinkDecision.Moved(LocationId(2), gone.id, LinkEvidence.MOVE_DETECTED)),
    )
  }
}
