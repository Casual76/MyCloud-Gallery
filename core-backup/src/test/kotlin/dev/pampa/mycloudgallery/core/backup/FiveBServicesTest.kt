package dev.pampa.mycloudgallery.core.backup

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionFailure
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionOutcome
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionReadiness
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionRequest
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionRoute
import dev.pampa.mycloudgallery.core.backup.contract.DestinationArea
import dev.pampa.mycloudgallery.core.backup.contract.EditBegin
import dev.pampa.mycloudgallery.core.backup.contract.EditOutcome
import dev.pampa.mycloudgallery.core.backup.contract.EditSessionRules
import dev.pampa.mycloudgallery.core.backup.contract.EditTarget
import dev.pampa.mycloudgallery.core.backup.contract.EditUnavailable
import dev.pampa.mycloudgallery.core.backup.contract.IncomingLimits
import dev.pampa.mycloudgallery.core.backup.contract.IncomingOrigin
import dev.pampa.mycloudgallery.core.backup.contract.IncomingReject
import dev.pampa.mycloudgallery.core.backup.contract.IncomingSaveOutcome
import dev.pampa.mycloudgallery.core.backup.contract.IncomingSavePlan
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStream
import dev.pampa.mycloudgallery.core.backup.contract.NasDestination
import dev.pampa.mycloudgallery.core.backup.contract.NasVersionPlan
import dev.pampa.mycloudgallery.core.backup.contract.PendingTask
import dev.pampa.mycloudgallery.core.backup.contract.PendingTaskKind
import dev.pampa.mycloudgallery.core.backup.contract.PendingTaskState
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationCheck
import dev.pampa.mycloudgallery.core.backup.contract.PhoneKeptReason
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRewrite
import dev.pampa.mycloudgallery.core.backup.contract.PhoneWriteGrant
import dev.pampa.mycloudgallery.core.backup.contract.RestoreAvailability
import dev.pampa.mycloudgallery.core.backup.contract.RestoreOutcome
import dev.pampa.mycloudgallery.core.backup.contract.VersionArea
import dev.pampa.mycloudgallery.core.backup.contract.VersionEntry
import dev.pampa.mycloudgallery.core.backup.contract.VersionPlace
import dev.pampa.mycloudgallery.core.backup.contract.VersionRole
import dev.pampa.mycloudgallery.core.backup.noop.NoCorrections
import dev.pampa.mycloudgallery.core.backup.noop.NoEditSessions
import dev.pampa.mycloudgallery.core.backup.noop.NoIncomingUploads
import dev.pampa.mycloudgallery.core.backup.noop.NoPendingTasks
import dev.pampa.mycloudgallery.core.backup.noop.NoPhoneRewriter
import dev.pampa.mycloudgallery.core.backup.noop.NoVersionKeeper
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.core.backup.testing.FakePhoneRewriter
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryEditSessions
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryIncomingUploads
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryPendingTasks
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryVersionKeeper
import dev.pampa.mycloudgallery.core.backup.testing.ScriptedCorrections
import dev.pampa.mycloudgallery.core.backup.testing.TestConfirmations
import dev.pampa.mycloudgallery.core.backup.testing.uuidOf
import dev.pampa.mycloudgallery.core.metadata.contract.GeoChange
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataChange
import dev.pampa.mycloudgallery.core.model.CorrectionField
import dev.pampa.mycloudgallery.core.model.ExifOrientation
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Fase 5B (P0-B, §1.2, D17-D20, D24, D25): i contratti di `VersionKeeper`, `Corrections`, `PendingTasks`, `IncomingUploads`, `EditSessions` e
 * della porta `PhoneRewriter`, sui fake di riferimento e sui null-object. Controllano che i fake facciano rispettare le regole che P8-P11
 * dovranno far rispettare al codice vero (conferma contro il piano di adesso, permesso di scrittura, tetti, "mai niente da soli"), e che i
 * null-object falliscano chiusi.
 */
class FiveBServicesTest {

  @get:Rule val temp = TemporaryFolder()

  private val clock = FakeBackupClock()
  private val media = MediaId(uuidOf(1))
  private val original = VersionId(uuidOf(11))
  private val edited = VersionId(uuidOf(12))

  // --- Versioni --------------------------------------------------------------------------------------------------------------------

  private fun versionPlan(restore: RestoreAvailability = RestoreAvailability.AVAILABLE, extra: List<VersionEntry> = emptyList()) = NasVersionPlan(
    mediaId = media,
    versions = listOf(
      VersionEntry(original, VersionRole.ORIGINAL, isCurrent = false, size = 4_000, savedAtNasMillis = 1_000, places = setOf(VersionPlace.NAS_VERSIONS_FOLDER), safe = true),
      VersionEntry(edited, VersionRole.EDITED, isCurrent = true, size = 3_500, savedAtNasMillis = 2_000, places = setOf(VersionPlace.PHONE, VersionPlace.NAS_USUAL_PATH), safe = true),
    ) + extra,
    area = VersionArea.OWN_PRIVATE,
    restore = restore,
  )

  @Test
  fun ilPianoDelleVersioniDistingueOriginaleAttualeEIntermedie() {
    val middle = VersionEntry(VersionId(uuidOf(13)), VersionRole.EDITED, isCurrent = false, size = 3_700, savedAtNasMillis = 1_500, places = setOf(VersionPlace.NAS_VERSIONS_FOLDER), safe = true)
    val plan = versionPlan(extra = listOf(middle))
    assertThat(plan.original!!.versionId).isEqualTo(original)
    assertThat(plan.current!!.versionId).isEqualTo(edited)
    assertThat(plan.intermediates.map { it.versionId }).containsExactly(middle.versionId)
    assertThat(plan.requiredConfirmations).isEqualTo(1)
    // Una versione nuova arrivata dopo il foglio cambia l'impronta: la conferma data per il piano di prima non vale piu'.
    assertThat(versionPlan().hash).isNotEqualTo(plan.hash)
    assertThat(versionPlan().hash).isEqualTo(versionPlan().hash)
    assertThat(plan.toString()).doesNotContain(original.value)
  }

  @Test
  fun ripristinaOriginaleControllaLaConfermaControIlPianoDiAdessoELoScambia() = runTest {
    val keeper = InMemoryVersionKeeper(clock)
    keeper.setPlan(versionPlan())
    val sheetPlan = keeper.plan(media)
    val confirmation = TestConfirmations.confirm(sheetPlan, clock.monotonicMs())

    // Nel frattempo il PC salva una versione in piu': il piano di adesso non e' quello del foglio.
    val extra = VersionEntry(VersionId(uuidOf(13)), VersionRole.EDITED, false, 3_700, 1_500, setOf(VersionPlace.NAS_VERSIONS_FOLDER), true)
    keeper.setPlan(versionPlan(extra = listOf(extra)))
    assertThat(keeper.restoreOriginal(media, confirmation)).isEqualTo(RestoreOutcome.Rejected(PhoneConfirmationCheck.WrongPlan))
    assertThat(keeper.restored).isEmpty()

    keeper.setPlan(versionPlan())
    assertThat(keeper.restoreOriginal(media, confirmation)).isEqualTo(RestoreOutcome.Queued)
    // Dopo lo scambio l'originale e' l'attuale (al solito percorso) e quella di prima e' in Versioni/.
    val after = keeper.plan(media)
    assertThat(after.current!!.versionId).isEqualTo(original)
    assertThat(after.current!!.places).contains(VersionPlace.NAS_USUAL_PATH)
    assertThat(after.versions.first { it.versionId == edited }.places).contains(VersionPlace.NAS_VERSIONS_FOLDER)
    assertThat(after.restore).isEqualTo(RestoreAvailability.ALREADY_ORIGINAL)
  }

  @Test
  fun seLaCopiaDelTelefonoEModificataServeIlPermessoDiScritturaPerQuelleRighe() = runTest {
    val keeper = InMemoryVersionKeeper(clock)
    keeper.setPlan(versionPlan())
    val row = PhoneRef("external_primary", 42, "v1")
    keeper.phoneRowsToRewrite[media] = listOf(row)
    val confirmation = TestConfirmations.confirm(keeper.plan(media), clock.monotonicMs())

    assertThat(keeper.restoreOriginal(media, confirmation)).isEqualTo(RestoreOutcome.NeedsPhoneWrite(listOf(row)))
    assertThat(keeper.restored).isEmpty()
    assertThat(keeper.restoreOriginal(media, confirmation, PhoneWriteGrant(setOf(row)))).isEqualTo(RestoreOutcome.Queued)
  }

  /**
   * P14, il contratto di `PhoneWriteGrant` (lo stesso nel finto e nel custode vero): un permesso che **non copre** le righe vuol dire che
   * l'utente ha detto no al dialogo. Lo scambio sul NAS si fa, il telefono tiene la modifica, e la risposta lo dice.
   */
  @Test
  fun unPermessoCheNonCopreLeRigheEUnDialogoNegato() = runTest {
    val keeper = InMemoryVersionKeeper(clock)
    keeper.setPlan(versionPlan())
    val row = PhoneRef("external_primary", 42, "v1")
    keeper.phoneRowsToRewrite[media] = listOf(row)
    val confirmation = TestConfirmations.confirm(keeper.plan(media), clock.monotonicMs())
    assertThat(keeper.restoreOriginal(media, confirmation, PhoneWriteGrant(emptySet())))
      .isEqualTo(RestoreOutcome.PhoneNotRewritten(PhoneKeptReason.PERMISSION_DENIED))
    assertThat(keeper.restored).containsExactly(media)
  }

  @Test
  fun ilRipristinoNonDisponibileONonConfermatoNonFaNiente() = runTest {
    val keeper = InMemoryVersionKeeper(clock)
    assertThat(keeper.plan(media).restore).isEqualTo(RestoreAvailability.ONLY_ONE_VERSION)
    keeper.setPlan(versionPlan(restore = RestoreAvailability.ORIGINAL_NOT_SAFE))
    val confirmation = TestConfirmations.confirm(keeper.plan(media), clock.monotonicMs())
    assertThat(keeper.restoreOriginal(media, confirmation)).isEqualTo(RestoreOutcome.Unavailable(RestoreAvailability.ORIGINAL_NOT_SAFE))
    keeper.setPlan(versionPlan())
    // Conferma scaduta.
    val old = TestConfirmations.confirm(keeper.plan(media), clock.monotonicMs())
    clock.advance(11 * 60 * 1000L)
    assertThat(keeper.restoreOriginal(media, old)).isEqualTo(RestoreOutcome.Rejected(PhoneConfirmationCheck.Expired))
    assertThat(NoVersionKeeper.restoreOriginal(media, old)).isEqualTo(RestoreOutcome.Unavailable(RestoreAvailability.NOT_AVAILABLE))
    assertThat(NoVersionKeeper.plan(media).versions).isEmpty()
  }

  // --- Correzioni ------------------------------------------------------------------------------------------------------------------

  private val request = CorrectionRequest(MetadataChange(takenAtUtcMillis = 1_700_000_000_000, offsetMinutes = 60, geo = GeoChange.Set(GeoPoint(45.4, 9.2))))

  @Test
  fun unaCorrezioneCambiaAlmenoUnCampoEIlPianoNeFirmaIValori() = runTest {
    assertThrows(IllegalArgumentException::class.java) { CorrectionRequest(MetadataChange()) }
    assertThat(request.fields).containsExactly(CorrectionField.DATE, CorrectionField.LOCATION)
    val corrections = ScriptedCorrections(clock)
    val a = corrections.plan(media, request)
    val b = corrections.plan(media, CorrectionRequest(MetadataChange(orientation = ExifOrientation.ROTATE_90)))
    val c = corrections.plan(media, CorrectionRequest(MetadataChange(takenAtUtcMillis = 1_700_000_000_001, offsetMinutes = 60, geo = GeoChange.Set(GeoPoint(45.4, 9.2)))))
    assertThat(a.hash).isNotEqualTo(b.hash)
    assertThat(a.hash).isNotEqualTo(c.hash)
    assertThat(corrections.plan(media, request).hash).isEqualTo(a.hash)
    // Il luogo e la data sono dell'utente: la riga di log dice solo i campi.
    assertThat(a.toString()).doesNotContain("45.4")
  }

  @Test
  fun unMediaDelTelefonoSiCorreggeSoloSeAlSicuroConIlPermessoDiScrittura() = runTest {
    val corrections = ScriptedCorrections(clock)
    val row = PhoneRef("external_primary", 7, "v1")
    corrections.phoneRows = listOf(row)

    // Non al sicuro: prima il backup.
    corrections.readiness = CorrectionReadiness.NEEDS_SAFE_FIRST
    var plan = corrections.plan(media, request)
    var confirmation = TestConfirmations.confirm(plan, clock.monotonicMs())
    assertThat(corrections.apply(plan, confirmation, null)).isEqualTo(CorrectionOutcome.NeedsSafeFirst)

    // Al sicuro ma senza il permesso del dialogo di sistema: nessuna scrittura, la UI chiede il permesso per quelle righe.
    corrections.readiness = CorrectionReadiness.READY
    plan = corrections.plan(media, request)
    confirmation = TestConfirmations.confirm(plan, clock.monotonicMs())
    assertThat(corrections.apply(plan, confirmation, null)).isEqualTo(CorrectionOutcome.NeedsWriteGrant(listOf(row)))
    assertThat(corrections.apply(plan, confirmation, PhoneWriteGrant(emptySet()))).isEqualTo(CorrectionOutcome.NeedsWriteGrant(listOf(row)))
    assertThat(corrections.applied).isEmpty()

    val done = corrections.apply(plan, confirmation, PhoneWriteGrant(setOf(row))) as CorrectionOutcome.Done
    assertThat(done.version).isNotNull()
    assertThat(corrections.applied).hasSize(1)
  }

  @Test
  fun leAltreStradeDelleCorrezioni() = runTest {
    val corrections = ScriptedCorrections(clock)
    // Un formato non scrivibile (Q6) lascia il file com'e'.
    corrections.readiness = CorrectionReadiness.FORMAT_NOT_WRITABLE
    var plan = corrections.plan(media, request)
    assertThat(corrections.apply(plan, TestConfirmations.confirm(plan, clock.monotonicMs()), null)).isEqualTo(CorrectionOutcome.FormatNotWritable)

    corrections.readiness = CorrectionReadiness.READY
    plan = corrections.plan(media, request)
    val confirmation = TestConfirmations.confirm(plan, clock.monotonicMs())
    // Il file cambia dopo il foglio (Samsung "Salva", il PC): il piano non e' piu' quello.
    corrections.baseVersion = VersionId(uuidOf(99))
    assertThat(corrections.apply(plan, confirmation, null)).isEqualTo(CorrectionOutcome.PlanChanged)

    // Un'area comune senza il foglio, una conferma scaduta, un guasto.
    corrections.readiness = CorrectionReadiness.COMMON_AREA_NOTICE
    plan = corrections.plan(media, request)
    assertThat(corrections.apply(plan, TestConfirmations.confirm(plan, clock.monotonicMs()), null))
      .isEqualTo(CorrectionOutcome.Unavailable(CorrectionReadiness.COMMON_AREA_NOTICE))
    corrections.readiness = CorrectionReadiness.READY
    corrections.route = CorrectionRoute.NAS_ONLY
    plan = corrections.plan(media, request)
    val old = TestConfirmations.confirm(plan, clock.monotonicMs())
    clock.advance(11 * 60 * 1000L)
    assertThat(corrections.apply(plan, old, null)).isEqualTo(CorrectionOutcome.Rejected(PhoneConfirmationCheck.Expired))
    val fresh = TestConfirmations.confirm(plan, clock.monotonicMs())
    corrections.failNext = CorrectionFailure.PHONE_NOT_RESTORED
    assertThat(corrections.apply(plan, fresh, null)).isEqualTo(CorrectionOutcome.Failed(CorrectionFailure.PHONE_NOT_RESTORED))
    assertThat(corrections.apply(plan, fresh, null)).isInstanceOf(CorrectionOutcome.Done::class.java)

    assertThat(NoCorrections.apply(plan, fresh, null)).isEqualTo(CorrectionOutcome.Unavailable(CorrectionReadiness.NOT_AVAILABLE))
  }

  @Test
  fun laRiscritturaDelTelefonoFintaControllaPermessoFileERiga() = runTest {
    val rewriter = FakePhoneRewriter()
    val ref = PhoneRef("external_primary", 5, "v1")
    val bytes = byteArrayOf(1, 2, 3, 4)
    val file: File = temp.newFile().apply { writeBytes(bytes) }
    val sha = Sha256.of(bytes)
    val before = Sha256.of(byteArrayOf(7))
    assertThat(rewriter.replace(ref, "image/jpeg", file, sha, before)).isEqualTo(PhoneRewrite.NotGranted)
    rewriter.granted += ref
    assertThat(rewriter.replace(ref, "image/jpeg", file, Sha256.of(byteArrayOf(9)), before)).isEqualTo(PhoneRewrite.SourceMismatch)
    // R3: una riga con una versione che non e' quella al sicuro non si tocca.
    rewriter.rowContents[ref] = Sha256.of(byteArrayOf(8))
    assertThat(rewriter.replace(ref, "image/jpeg", file, sha, before)).isEqualTo(PhoneRewrite.RowChanged)
    assertThat(rewriter.replaced).isEmpty()
    rewriter.rowContents[ref] = before
    assertThat(rewriter.replace(ref, "image/jpeg", file, sha, before)).isEqualTo(PhoneRewrite.Replaced(sha, 4))
    rewriter.gone += ref
    assertThat(rewriter.replace(ref, "image/jpeg", file, sha, before)).isEqualTo(PhoneRewrite.Gone)
    assertThat(NoPhoneRewriter.replace(ref, null, file, sha, before)).isEqualTo(PhoneRewrite.NotGranted)
  }

  // --- Attivita' in sospeso --------------------------------------------------------------------------------------------------------

  @Test
  fun leAttivitaInSospesoSonoDecisioniDellUtenteENonCancellanoNiente() = runTest {
    val tasks = InMemoryPendingTasks()
    val first = tasks.add { id -> PendingTask(id, PendingTaskKind.EDITED_VIDEO_ORIGINAL, PendingTaskState.OPEN, media, original, 2_000_000_000, 5_000) }
    val second = tasks.add { id -> PendingTask(id, PendingTaskKind.EDITED_VIDEO_ORIGINAL, PendingTaskState.OPEN, MediaId(uuidOf(2)), VersionId(uuidOf(21)), 900, 3_000) }
    // "Da decidere": solo le aperte, dalla piu' vecchia.
    assertThat(tasks.tasks.value.map { it.id }).containsExactly(second.id, first.id).inOrder()
    tasks.dismiss(first.id) // "Tienilo"
    assertThat(tasks.tasks.value.map { it.id }).containsExactly(second.id)
    tasks.complete(second.id) // l'originale e' andato nel cestino del NAS dal foglio di feature-nas
    assertThat(tasks.tasks.value).isEmpty()
    assertThat(tasks.everything.map { it.state }).containsExactly(PendingTaskState.KEPT, PendingTaskState.DONE).inOrder()
    assertThat(first.toString()).doesNotContain(media.value)
    assertThat(NoPendingTasks.tasks.value).isEmpty()
  }

  // --- Arrivi ----------------------------------------------------------------------------------------------------------------------

  private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16, 'J'.code.toByte(), 'F'.code.toByte(), 1, 2, 3, 4, 5, 6, 7, 8)

  private fun content(bytes: ByteArray, name: String? = "IMG_1.jpg", declared: Long? = bytes.size.toLong()) =
    IncomingStream(IncomingOrigin.CONTENT_OF_ANOTHER_APP, name, "image/jpeg", declared) { ByteArrayInputStream(bytes) }

  /** Un flusso che non si deve aprire: se lo si apre il test fallisce (un URI ostile non si legge). */
  private fun mustNotOpen(origin: IncomingOrigin) = IncomingStream(origin, "x.jpg", "image/jpeg", 10) { throw AssertionError("Un flusso rifiutato non si apre.") }

  private val destination = NasDestination("test", SharePath.of("Backup", "Ricevute"), DestinationArea.PRIVATE)

  @Test
  fun iFlussiInArrivoSiRifiutanoPrimaDiLeggerliEIlTipoEDeiByte() = runTest {
    val uploads = InMemoryIncomingUploads(clock)
    val fake = "questo non e' un jpeg".toByteArray()
    val stage = uploads.stage(
      listOf(
        content(jpeg),
        content(fake, name = "falsa.jpg"),
        mustNotOpen(IncomingOrigin.FILE_SCHEME),
        mustNotOpen(IncomingOrigin.OWN_PROVIDER),
        content(jpeg, declared = IncomingLimits.MAX_FILE_BYTES + 1),
        IncomingStream(IncomingOrigin.CONTENT_OF_ANOTHER_APP, "rotto", null, null) { throw IOException("flusso interrotto") },
      ),
    )
    assertThat(stage.accepted.map { it.mime }).containsExactly("image/jpeg")
    assertThat(stage.rejected).containsExactly(
      IncomingReject.NOT_MEDIA, 1,
      IncomingReject.FILE_SCHEME, 1,
      IncomingReject.OWN_PROVIDER, 1,
      IncomingReject.TOO_LARGE, 1,
      IncomingReject.UNREADABLE, 1,
    )
    // I nomi locali derivano dall'hash: mai il nome che l'altra app ha dichiarato.
    val item = stage.accepted.single()
    assertThat(item.name).isEqualTo(item.sha256.hex.take(32))
    assertThat(item.name).doesNotContain("IMG_1")
  }

  @Test
  fun iTettiDiUnaCondivisioneValgonoPrimaDiScrivere() = runTest {
    val uploads = InMemoryIncomingUploads(clock)
    val many = (1..IncomingLimits.MAX_ITEMS + 3).map { n -> content(jpeg + byteArrayOf(n.toByte(), (n shr 8).toByte()), name = "f$n") }
    val stage = uploads.stage(many)
    assertThat(stage.accepted).hasSize(IncomingLimits.MAX_ITEMS)
    assertThat(stage.rejected).containsExactly(IncomingReject.TOO_MANY, 3)
    // Meno di 1 GiB libero dopo la copia: rifiutato.
    uploads.freeBytes = IncomingLimits.MIN_FREE_AFTER_BYTES + 5
    assertThat(uploads.stage(listOf(content(jpeg))).rejected).containsExactly(IncomingReject.NO_SPACE, 1)
  }

  @Test
  fun salvareVuoleLaConfermaPerQuestoPianoEUnaCartellaAmmessa() = runTest {
    val uploads = InMemoryIncomingUploads(clock)
    val stage = uploads.stage(listOf(content(jpeg)))
    val plan = IncomingSavePlan(stage, destination)
    val otherPlan = IncomingSavePlan(stage, NasDestination("test-comune", SharePath.of("Famiglia"), DestinationArea.COMMON))
    assertThat(plan.needsCommonNotice).isFalse()
    assertThat(otherPlan.needsCommonNotice).isTrue()
    assertThat(plan.hash).isNotEqualTo(otherPlan.hash)

    // Una conferma data per un'altra cartella non vale: ricevere una condivisione non avvia niente da solo (E-S11).
    assertThat(uploads.save(stage, destination, TestConfirmations.confirm(otherPlan, clock.monotonicMs())))
      .isEqualTo(IncomingSaveOutcome.Rejected(PhoneConfirmationCheck.WrongPlan))
    assertThat(uploads.saved).isEmpty()

    uploads.readOnlyShares += "test"
    assertThat(uploads.save(stage, destination, TestConfirmations.confirm(plan, clock.monotonicMs()))).isEqualTo(IncomingSaveOutcome.DestinationNotAllowed)
    uploads.readOnlyShares.clear()
    assertThat(uploads.save(stage, destination, TestConfirmations.confirm(plan, clock.monotonicMs()))).isEqualTo(IncomingSaveOutcome.Queued(1, userInitiated = false))
    assertThat(uploads.saved.single().first).isEqualTo(destination)
    // La condivisione e' stata consumata: salvare di nuovo non crea doppioni.
    assertThat(uploads.save(stage, destination, TestConfirmations.confirm(plan, clock.monotonicMs()))).isEqualTo(IncomingSaveOutcome.StageGone)

    val another = uploads.stage(listOf(content(jpeg + byteArrayOf(9))))
    uploads.discard(another.id)
    assertThat(uploads.discarded).containsExactly(another.id)
    assertThat(uploads.save(another, destination, TestConfirmations.confirm(IncomingSavePlan(another, destination), clock.monotonicMs())))
      .isEqualTo(IncomingSaveOutcome.StageGone)
  }

  @Test
  fun senzaIlLegameVeroGliArriviSiRifiutanoSenzaAprireIFlussi() = runTest {
    val stage = NoIncomingUploads.stage(listOf(mustNotOpen(IncomingOrigin.CONTENT_OF_ANOTHER_APP), mustNotOpen(IncomingOrigin.CONTENT_OF_ANOTHER_APP)))
    assertThat(stage.accepted).isEmpty()
    assertThat(stage.rejected).containsExactly(IncomingReject.NOT_AVAILABLE, 2)
    val confirmation = TestConfirmations.confirm(IncomingSavePlan(stage, destination), clock.monotonicMs())
    assertThat(NoIncomingUploads.save(stage, destination, confirmation)).isEqualTo(IncomingSaveOutcome.Unavailable)
  }

  // --- Sessioni di modifica --------------------------------------------------------------------------------------------------------

  @Test
  fun laSessioneDiModificaDuraTrentaMinutiDiOraDelNas() = runTest {
    val sessions = InMemoryEditSessions(clock)
    val row = PhoneRef("external_primary", 3, "v1")
    sessions.phoneRows[media] = row
    val session = (sessions.begin(media, EditTarget.PHONE_GALLERY) as EditBegin.Started).session
    assertThat(session.phone).isEqualTo(row)
    assertThat(session.cacheName).isNull()
    assertThat(session.expiresAtNasMillis - session.startedAtNasMillis).isEqualTo(EditSessionRules.WINDOW_MS)

    // Nessun cambiamento: niente. Poi l'editor salva: una versione dello stesso media (D18) e la sessione si chiude.
    assertThat(sessions.settle(session)).isEqualTo(EditOutcome.Unchanged)
    sessions.outcomeOf(session.id, EditOutcome.NewVersion(edited))
    assertThat(sessions.settle(session)).isEqualTo(EditOutcome.NewVersion(edited))
    assertThat(sessions.all.single().state.terminal).isTrue()

    // Una sessione scaduta non attribuisce piu' i cambiamenti (E-E5).
    val late = (sessions.begin(media, EditTarget.PHONE_GALLERY) as EditBegin.Started).session
    sessions.outcomeOf(late.id, EditOutcome.NewVersion(edited))
    clock.advance(EditSessionRules.WINDOW_MS + 1)
    assertThat(sessions.settle(late)).isEqualTo(EditOutcome.Expired)
    assertThat(30.minutes.inWholeMilliseconds).isEqualTo(EditSessionRules.WINDOW_MS)
  }

  @Test
  fun laCopiaInCachePerUnSoloNasHaUnNomeOpacoEUnNasSpentoEUnErroreSubito() = runTest {
    val sessions = InMemoryEditSessions(clock)
    val cached = (sessions.begin(media, EditTarget.NAS_CACHE) as EditBegin.Started).session
    assertThat(cached.cacheName).startsWith("edit-")
    assertThat(cached.phone).isNull()
    sessions.unavailable[MediaId(uuidOf(5))] = EditUnavailable.NAS_UNREACHABLE
    assertThat(sessions.begin(MediaId(uuidOf(5)), EditTarget.NAS_CACHE)).isEqualTo(EditBegin.Unavailable(EditUnavailable.NAS_UNREACHABLE))
    // Senza un'ora del NAS ancorata una sessione non si apre: non c'e' un "adesso" su cui far scadere.
    val noNasTime = InMemoryEditSessions(FakeBackupClock(nasNow = null))
    assertThat(noNasTime.begin(media, EditTarget.NAS_CACHE)).isEqualTo(EditBegin.Unavailable(EditUnavailable.NAS_UNREACHABLE))
    assertThat(NoEditSessions.begin(media, EditTarget.PHONE_GALLERY)).isEqualTo(EditBegin.Unavailable(EditUnavailable.NOT_AVAILABLE))
    assertThat(NoEditSessions.settle(cached)).isEqualTo(EditOutcome.Unreadable)
  }
}
