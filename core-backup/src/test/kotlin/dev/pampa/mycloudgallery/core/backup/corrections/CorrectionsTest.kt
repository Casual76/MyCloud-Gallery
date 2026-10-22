package dev.pampa.mycloudgallery.core.backup.corrections

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionFailure
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionOutcome
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionReadiness
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionRequest
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionRoute
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationCheck
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRewrite
import dev.pampa.mycloudgallery.core.backup.contract.PhoneWriteGrant
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.testing.TestConfirmations
import dev.pampa.mycloudgallery.core.backup.versions.PhoneRowRewriter
import dev.pampa.mycloudgallery.core.backup.versions.VersionWorld
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataChange
import dev.pampa.mycloudgallery.core.metadata.testing.ChangeSamples
import dev.pampa.mycloudgallery.core.metadata.testing.FakeMetadataWriter
import dev.pampa.mycloudgallery.core.metadata.write.WrittenCheck
import dev.pampa.mycloudgallery.core.metadata.write.WrittenFileCheck
import dev.pampa.mycloudgallery.core.model.CorrectionField
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Fase 5B, P8 (D19, E-L8, E-E8, E-E9, Q6 (a), L40, §2.4 "Correzioni dall'app"): le correzioni di data, luogo e rotazione
 * ([DefaultCorrections]) sul NAS finto e sul telefono finto. Lo scrittore e la rilettura sono finti qui (lo scrittore vero e la rilettura
 * vera di JPEG, PNG e WebP sono provati in core-metadata, `write/ExifMetadataWriterTest`): conta la sequenza.
 * - un media del telefono **non al sicuro** non si corregge e niente si tocca;
 * - la copia corretta che **non passa la rilettura** si scarta: telefono, NAS e catalogo come prima;
 * - riuscita: versione nuova `CORRECTED` nel catalogo, al solito percorso sul NAS (l'originale in `Versioni/`), telefono riscritto con il
 *   permesso del dialogo; per un solo-NAS lo stesso senza telefono;
 * - HEIC, RAW, video: la correzione resta nell'app, il file non si tocca.
 */
class CorrectionsTest {

  @get:Rule val folder = TemporaryFolder()

  private val share = VersionWorld.PRIVATE_SHARE
  private val versions = "Versioni/Backup/S25 di prova/2026/10"
  private val change = CorrectionRequest(ChangeSamples.newDate)

  private class Kit(val w: VersionWorld, val corrections: DefaultCorrections, val writer: FakeMetadataWriter, val rewriter: PhoneRowRewriter) {
    var verdict: WrittenCheck = WrittenCheck.VALID
  }

  private fun kit(): Kit {
    val w = VersionWorld(folder.newFolder())
    // Il tipo dai byte e' compito dello scrittore vero; qui ogni copia di lavoro e' un JPEG.
    val writer = FakeMetadataWriter(mimeOf = { "image/jpeg" })
    val rewriter = PhoneRowRewriter(w.phone)
    lateinit var kit: Kit
    val check = WrittenFileCheck { _, _, _ -> kit.verdict }
    val corrections = DefaultCorrections(
      share = w.share,
      catalog = w.catalog,
      engine = w.engine,
      journal = w.journal,
      gate = w.gate,
      clock = w.clock,
      phone = w.phone.port,
      reader = w.nas.reader,
      writer = writer,
      check = check,
      rewriter = rewriter,
      staged = w.staged,
      io = EmptyCoroutineContext,
    )
    kit = Kit(w, corrections, writer, rewriter)
    return kit
  }

  @Test
  fun unaFotoDelTelefonoAlSicuroDiventaUnaVersioneCorrettaOvunque() = runTest {
    val k = kit()
    val w = k.w
    val seed = w.seedSafe()
    val before = w.snapshot()
    val plan = k.corrections.plan(seed.mediaId, change)
    assertThat(plan.readiness).isEqualTo(CorrectionReadiness.READY)
    assertThat(plan.route).isEqualTo(CorrectionRoute.PHONE)
    val ref = w.phone.ref(seed.phoneId!!)!!
    assertThat(plan.phoneRows).containsExactly(ref)
    val confirmation = TestConfirmations.confirm(plan, w.clock.monotonic)
    // Senza il permesso del dialogo di sistema: chiesto, niente toccato.
    assertThat(k.corrections.apply(plan, confirmation, null)).isEqualTo(CorrectionOutcome.NeedsWriteGrant(listOf(ref)))
    assertThat(k.writer.changes).isEmpty()
    assertThat(w.files()).isEqualTo(before.files.getValue(share))
    // Con il permesso: fatto.
    k.rewriter.granted += seed.phoneId
    val outcome = k.corrections.apply(plan, confirmation, PhoneWriteGrant(setOf(ref))) as CorrectionOutcome.Done
    val corrected = seed.content + "\nMCG-FAKE-WRITE date\n".toByteArray(Charsets.US_ASCII)
    // Catalogo: versione CORRECTED attuale, scritta prima di riscrivere il telefono.
    assertThat(w.catalog.currentOf(seed.mediaId)).isEqualTo(outcome.version)
    assertThat(w.catalog.corrections.single().second).containsExactly(CorrectionField.DATE)
    assertThat(w.catalog.media(seed.mediaId)!!.version(outcome.version)!!.kind).isEqualTo(VersionKind.CORRECTED)
    // Telefono riscritto con la copia corretta; NAS: la correzione al solito percorso, l'originale in Versioni/.
    assertThat(w.phone.sha256(seed.phoneId)).isEqualTo(Sha256.of(corrected))
    assertThat(w.sha(share, seed.usualPath.value)).isEqualTo(Sha256.of(corrected))
    assertThat(w.sha(share, "$versions/IMG_1234 (originale).jpg")).isEqualTo(Sha256.of(seed.content))
    assertThat(w.journal.all().single().state).isEqualTo(UploadState.DONE)
    // Il file preparato non serve piu', le copie di lavoro non restano.
    assertThat(w.staged.pending()).isEmpty()
    assertThat(java.io.File(w.root, "versions").listFiles().orEmpty().filter { it.name.startsWith("work-") }).isEmpty()
    assertThat(w.violations(before, final = true)).isEmpty()
  }

  /**
   * Campo, gruppo A (D3, F36, `exif-05-gps-ora-utc.jpg`): il telefono tiene la versione di **prima** (l'originale, al sicuro in `Versioni/`) e
   * l'attuale e' una modifica che sta sul NAS. Prima la correzione passava per "solo sul NAS": "Fatto" senza il dialogo di Android, il telefono com'era e
   * la Libreria con la data di prima (la versione nuova nasceva senza metadati). Adesso la riga del telefono conta: serve il permesso del dialogo, la
   * riga si riscrive con la correzione (dai byte dell'attuale letti dal NAS) e i metadati della versione nuova si leggono subito dai byte corretti.
   */
  @Test
  fun d3_unMediaCheIlTelefonoTieneInUnaVersioneDiPrimaSiCorreggeAncheSulTelefono() = runTest {
    val k = kit()
    val w = k.w
    val seed = w.seedSafe()
    // Il PC ha modificato il file al solito percorso; l'originale e' gia' al sicuro in Versioni/ (come i fixture di F43-tel sul campo).
    val edited = w.bytes(seed.content.size + 500)
    w.knownContents += Sha256.of(edited)
    w.catalog.nasVanished(seed.mediaId, share, seed.usualPath)
    val archived = dev.pampa.mycloudgallery.core.trust.nas.SharePath.parse("$versions/IMG_1234 (originale).jpg")
    w.nas.putFile(share, archived.value, NasContent.of(seed.content))
    w.catalog.putNasCopy(seed.mediaId, seed.original, share, archived, w.statOf(share, archived), verifiedAt = w.clock.nasNow)
    w.nas.putFile(share, seed.usualPath.value, NasContent.of(edited))
    val current = w.catalog.addVersion(seed.mediaId, VersionKind.EDITED, edited.size.toLong(), Sha256.of(edited))
    w.catalog.putNasCopy(seed.mediaId, current, share, seed.usualPath, w.statOf(share, seed.usualPath), verifiedAt = w.clock.nasNow)
    val before = w.snapshot()

    val plan = k.corrections.plan(seed.mediaId, change)
    assertThat(plan.readiness).isEqualTo(CorrectionReadiness.READY)
    assertThat(plan.route).isEqualTo(CorrectionRoute.PHONE)
    val ref = w.phone.ref(seed.phoneId!!)!!
    assertThat(plan.phoneRows).containsExactly(ref)
    val confirmation = TestConfirmations.confirm(plan, w.clock.monotonic)
    // Il dialogo di Android si chiede; negato, niente si tocca (la pagina lo dice).
    assertThat(k.corrections.apply(plan, confirmation, null)).isEqualTo(CorrectionOutcome.NeedsWriteGrant(listOf(ref)))
    assertThat(w.phone.sha256(seed.phoneId)).isEqualTo(Sha256.of(seed.content))
    assertThat(w.files()).isEqualTo(before.files.getValue(share))

    k.rewriter.granted += seed.phoneId
    val outcome = k.corrections.apply(plan, confirmation, PhoneWriteGrant(setOf(ref))) as CorrectionOutcome.Done
    val corrected = edited + "\nMCG-FAKE-WRITE date\n".toByteArray(Charsets.US_ASCII)
    assertThat(w.catalog.currentOf(seed.mediaId)).isEqualTo(outcome.version)
    assertThat(w.phone.sha256(seed.phoneId)).isEqualTo(Sha256.of(corrected))
    assertThat(w.sha(share, seed.usualPath.value)).isEqualTo(Sha256.of(corrected))
    assertThat(w.sha(share, archived.value)).isEqualTo(Sha256.of(seed.content))
    // I metadati della versione nuova dai byte corretti, subito.
    assertThat(w.catalog.correctedReads).containsExactly(outcome.version to Sha256.of(corrected))
    assertThat(w.violations(before, final = true)).isEmpty()
  }

  /** Campo, gruppo A (D3): la versione di prima che il telefono tiene non e' al sicuro: niente si corregge (mai l'unica copia di qualcosa). */
  @Test
  fun d3_seLaVersioneDelTelefonoNonEAlSicuroPrimaLaMetteAlSicuro() = runTest {
    val k = kit()
    val w = k.w
    val seed = w.seedSafe()
    val edited = w.bytes(seed.content.size + 500)
    w.knownContents += Sha256.of(edited)
    w.catalog.nasVanished(seed.mediaId, share, seed.usualPath)
    w.nas.putFile(share, seed.usualPath.value, NasContent.of(edited))
    val current = w.catalog.addVersion(seed.mediaId, VersionKind.EDITED, edited.size.toLong(), Sha256.of(edited))
    w.catalog.putNasCopy(seed.mediaId, current, share, seed.usualPath, w.statOf(share, seed.usualPath), verifiedAt = w.clock.nasNow)

    assertThat(k.corrections.plan(seed.mediaId, change).readiness).isEqualTo(CorrectionReadiness.NEEDS_SAFE_FIRST)
  }

  @Test
  fun unSoloNasSiCorreggeDallaCopiaPrivataLettaInCache() = runTest {
    val k = kit()
    val w = k.w
    val seed = w.seedSafe(onPhone = false)
    val before = w.snapshot()
    val plan = k.corrections.plan(seed.mediaId, CorrectionRequest(ChangeSamples.clearLocation))
    assertThat(plan.route).isEqualTo(CorrectionRoute.NAS_ONLY)
    assertThat(plan.phoneRows).isEmpty()
    val outcome = k.corrections.apply(plan, TestConfirmations.confirm(plan, w.clock.monotonic), null)
    assertThat(outcome).isInstanceOf(CorrectionOutcome.Done::class.java)
    val corrected = seed.content + "\nMCG-FAKE-WRITE location\n".toByteArray(Charsets.US_ASCII)
    assertThat(w.sha(share, seed.usualPath.value)).isEqualTo(Sha256.of(corrected))
    assertThat(w.sha(share, "$versions/IMG_1234 (originale).jpg")).isEqualTo(Sha256.of(seed.content))
    assertThat(w.violations(before, final = true)).isEmpty()
  }

  @Test
  fun unaFotoDelTelefonoNonAlSicuroNonSiCorreggeENienteSiTocca() = runTest {
    val k = kit()
    val w = k.w
    // Sul telefono e sul NAS, ma la copia privata non e' ancora stata riletta (non verificata).
    val seed = w.seedSafe()
    w.catalog.unverify(seed.mediaId, seed.original)
    val opens = w.phone.opens
    val before = w.files()
    val plan = k.corrections.plan(seed.mediaId, change)
    assertThat(plan.readiness).isEqualTo(CorrectionReadiness.NEEDS_SAFE_FIRST)
    k.rewriter.granted += seed.phoneId!!
    val outcome = k.corrections.apply(plan, TestConfirmations.confirm(plan, w.clock.monotonic), PhoneWriteGrant(setOf(w.phone.ref(seed.phoneId)!!)))
    assertThat(outcome).isEqualTo(CorrectionOutcome.NeedsSafeFirst)
    assertThat(w.phone.opens).isEqualTo(opens)
    assertThat(k.writer.changes).isEmpty()
    assertThat(k.rewriter.replaced).isEmpty()
    assertThat(w.files()).isEqualTo(before)
    assertThat(w.catalog.corrections).isEmpty()
    assertThat(w.journal.all()).isEmpty()
    // Solo sul telefono (mai arrivata sul NAS): lo stesso.
    val (media, version) = w.catalog.addMedia(size = 1_000, fullHash = null)
    val ref = w.phone.add(900L, NasContent.of(w.bytes(1_000)))
    w.catalog.putPhoneCopy(media, version, ref, w.phone.stamp(900L)!!)
    assertThat(k.corrections.plan(media, change).readiness).isEqualTo(CorrectionReadiness.NEEDS_SAFE_FIRST)
  }

  @Test
  fun unaCopiaCorrettaRovinataSiScartaEOriginaliIntatti() = runTest {
    val k = kit()
    val w = k.w
    val seed = w.seedSafe()
    val before = w.snapshot()
    k.verdict = WrittenCheck.NOT_DECODABLE
    k.rewriter.granted += seed.phoneId!!
    val plan = k.corrections.plan(seed.mediaId, change)
    val outcome = k.corrections.apply(plan, TestConfirmations.confirm(plan, w.clock.monotonic), PhoneWriteGrant(setOf(w.phone.ref(seed.phoneId)!!)))
    assertThat(outcome).isEqualTo(CorrectionOutcome.Failed(CorrectionFailure.RESULT_NOT_VALID))
    // Il telefono, il NAS e il catalogo come prima; nessun file preparato ne' copia di lavoro resta.
    assertThat(w.phone.sha256(seed.phoneId)).isEqualTo(Sha256.of(seed.content))
    assertThat(k.rewriter.replaced).isEmpty()
    assertThat(w.files()).isEqualTo(before.files.getValue(share))
    assertThat(w.catalog.corrections).isEmpty()
    assertThat(w.catalog.currentOf(seed.mediaId)).isEqualTo(seed.original)
    assertThat(w.journal.all()).isEmpty()
    assertThat(java.io.File(w.root, "versions").listFiles().orEmpty().toList()).isEmpty()
    // Anche CHANGE_MISSING e UNREADABLE.
    for (verdict in listOf(WrittenCheck.CHANGE_MISSING, WrittenCheck.UNREADABLE)) {
      k.verdict = verdict
      val again = k.corrections.plan(seed.mediaId, change)
      assertThat(k.corrections.apply(again, TestConfirmations.confirm(again, w.clock.monotonic), PhoneWriteGrant(setOf(w.phone.ref(seed.phoneId)!!))))
        .isEqualTo(CorrectionOutcome.Failed(CorrectionFailure.RESULT_NOT_VALID))
    }
    assertThat(w.phone.sha256(seed.phoneId)).isEqualTo(Sha256.of(seed.content))
  }

  @Test
  fun heicRawEVideoRestanoCorrettiSoloNellApp() = runTest {
    val k = kit()
    val w = k.w
    for ((kind, mime) in listOf(MediaKind.PHOTO to "image/heic", MediaKind.RAW_PHOTO to "image/x-adobe-dng", MediaKind.VIDEO to "video/mp4")) {
      val seed = w.seedSafe(name = "F-${mime.substringAfter('/')}.bin", kind = kind, mime = mime)
      val before = w.files()
      val phoneBefore = w.phone.sha256(seed.phoneId!!)
      val plan = k.corrections.plan(seed.mediaId, change)
      assertThat(plan.readiness).isEqualTo(CorrectionReadiness.FORMAT_NOT_WRITABLE)
      // La conferma vale anche qui (la correzione nell'app e' un cambiamento dell'utente).
      val other = k.corrections.plan(w.seedSafe(name = "Z-${mime.substringAfter('/')}.jpg").mediaId, change)
      assertThat(k.corrections.apply(plan, TestConfirmations.confirmForOtherPlan(other, w.clock.monotonic), null))
        .isEqualTo(CorrectionOutcome.Rejected(PhoneConfirmationCheck.WrongPlan))
      assertThat(k.corrections.apply(plan, TestConfirmations.confirm(plan, w.clock.monotonic), null)).isEqualTo(CorrectionOutcome.FormatNotWritable)
      assertThat(w.catalog.inAppCorrections.last().first).isEqualTo(seed.mediaId)
      assertThat(w.files()).containsAtLeastEntriesIn(before)
      assertThat(w.phone.sha256(seed.phoneId)).isEqualTo(phoneBefore)
    }
    assertThat(k.writer.changes).isEmpty()
    assertThat(w.journal.all()).isEmpty()
  }

  @Test
  fun ilPianoCambiatoDopoIlFoglioSiRicalcola() = runTest {
    val k = kit()
    val w = k.w
    val seed = w.seedSafe()
    val plan = k.corrections.plan(seed.mediaId, change)
    // Samsung "Salva" dopo il foglio: il file non e' piu' quello del piano.
    w.editOnPhone(seed)
    k.rewriter.granted += seed.phoneId!!
    assertThat(k.corrections.apply(plan, TestConfirmations.confirm(plan, w.clock.monotonic), PhoneWriteGrant(setOf(w.phone.ref(seed.phoneId)!!))))
      .isEqualTo(CorrectionOutcome.PlanChanged)
    assertThat(k.writer.changes).isEmpty()
  }

  @Test
  fun unaRiscritturaDelTelefonoFallitaLoDiceELaCorrezioneArrivaComunqueSulNas() = runTest {
    val k = kit()
    val w = k.w
    val seed = w.seedSafe()
    k.rewriter.granted += seed.phoneId!!
    k.rewriter.failNext = PhoneRewrite.Failed("WRITE_ERROR", restored = true)
    val plan = k.corrections.plan(seed.mediaId, change)
    val outcome = k.corrections.apply(plan, TestConfirmations.confirm(plan, w.clock.monotonic), PhoneWriteGrant(setOf(w.phone.ref(seed.phoneId)!!)))
    assertThat(outcome).isEqualTo(CorrectionOutcome.Failed(CorrectionFailure.PHONE_WRITE_FAILED))
    // Il telefono ha ancora l'originale (la sua posizione resta sulla versione di prima); la correzione e' nell'app e va sul NAS.
    assertThat(w.phone.sha256(seed.phoneId)).isEqualTo(Sha256.of(seed.content))
    val corrected = seed.content + "\nMCG-FAKE-WRITE date\n".toByteArray(Charsets.US_ASCII)
    assertThat(w.sha(share, seed.usualPath.value)).isEqualTo(Sha256.of(corrected))
    assertThat(w.sha(share, "$versions/IMG_1234 (originale).jpg")).isEqualTo(Sha256.of(seed.content))
    // Non ripristinata: il caso grave e' detto a parte.
    val k2 = kit()
    val seed2 = k2.w.seedSafe()
    k2.rewriter.granted += seed2.phoneId!!
    k2.rewriter.failNext = PhoneRewrite.Failed("WRITE_ERROR", restored = false)
    val plan2 = k2.corrections.plan(seed2.mediaId, change)
    assertThat(k2.corrections.apply(plan2, TestConfirmations.confirm(plan2, k2.w.clock.monotonic), PhoneWriteGrant(setOf(k2.w.phone.ref(seed2.phoneId)!!))))
      .isEqualTo(CorrectionOutcome.Failed(CorrectionFailure.PHONE_NOT_RESTORED))
  }

  @Test
  fun areaComuneCongelamentoESolaLetturaNonSiCorreggono() = runTest {
    val k = kit()
    val w = k.w
    val common = w.seedSafe(name = "C.jpg", targetShare = VersionWorld.COMMON_SHARE)
    assertThat(k.corrections.plan(common.mediaId, change).readiness).isEqualTo(CorrectionReadiness.COMMON_AREA_NOTICE)
    val seed = w.seedSafe()
    w.gate.default = BackupGateState.FROZEN
    assertThat(k.corrections.plan(seed.mediaId, change).readiness).isEqualTo(CorrectionReadiness.FROZEN)
    w.gate.default = BackupGateState.READ_ONLY_AREA
    val ro = k.corrections.plan(seed.mediaId, change)
    assertThat(ro.readiness).isEqualTo(CorrectionReadiness.READ_ONLY_AREA)
    assertThat(k.corrections.apply(ro, TestConfirmations.confirm(ro, w.clock.monotonic), null))
      .isEqualTo(CorrectionOutcome.Unavailable(CorrectionReadiness.READ_ONLY_AREA))
    w.gate.default = BackupGateState.OPEN
    val (other, _) = w.catalog.addMedia(size = 10, fullHash = null, mine = false)
    assertThat(k.corrections.plan(other, change).readiness).isEqualTo(CorrectionReadiness.NOT_MINE)
    assertThat(k.writer.changes).isEmpty()
    assertThat(MetadataChange(orientation = null).isEmpty).isTrue()
  }
}
