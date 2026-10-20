package dev.pampa.mycloudgallery.core.backup.versions

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionFailure
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionOutcome
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionReadiness
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionRequest
import dev.pampa.mycloudgallery.core.backup.contract.PhoneKeptReason
import dev.pampa.mycloudgallery.core.backup.contract.PhoneWriteGrant
import dev.pampa.mycloudgallery.core.backup.contract.RestoreAvailability
import dev.pampa.mycloudgallery.core.backup.contract.RestoreOutcome
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.corrections.DefaultCorrections
import dev.pampa.mycloudgallery.core.backup.testing.TestConfirmations
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataChange
import dev.pampa.mycloudgallery.core.metadata.testing.ChangeSamples
import dev.pampa.mycloudgallery.core.metadata.testing.FakeMetadataWriter
import dev.pampa.mycloudgallery.core.metadata.write.WrittenCheck
import dev.pampa.mycloudgallery.core.metadata.write.WrittenFileCheck
import dev.pampa.mycloudgallery.core.model.ExifOrientation
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Le correzioni della revisione di sicurezza 5B (`docs/review/5b-fase5b.md`: R3, R16, R17) e della verifica funzionale (`docs/review/funzionale-5b.md`:
 * VE-1, VE-4, VE-7, VE-8) sul motore, il custode e le correzioni veri di core-backup, con il NAS e il telefono finti di [VersionWorld]. Ogni test
 * falliva con il codice di prima.
 */
class Revisione5BVersioniTest {

  @get:Rule val folder = TemporaryFolder()

  private val share = VersionWorld.PRIVATE_SHARE

  private class Kit(val w: VersionWorld, val seed: VersionWorld.Seeded, val rewriter: PhoneRowRewriter, val keeper: DefaultVersionKeeper) {
    override fun toString(): String = "Kit"
  }

  /** Una foto al sicuro, modificata sul telefono e gia' salvata come versione (v1 al solito percorso, l'originale in `Versioni/`). */
  private suspend fun savedEdit(): Kit {
    val w = VersionWorld(folder.newFolder())
    val seed = w.seedSafe()
    w.editOnPhone(seed)
    w.engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, seed.phoneId!!)))
    w.runUntilQuiet()
    val rewriter = PhoneRowRewriter(w.phone)
    return Kit(w, seed, rewriter, keeper(w, rewriter))
  }

  private fun keeper(w: VersionWorld, rewriter: PhoneRowRewriter = PhoneRowRewriter(w.phone)) =
    DefaultVersionKeeper(w.share, w.catalog, w.journal, w.engine, w.gate, w.clock, w.nas.reader, rewriter, w.staged)

  private fun corrections(w: VersionWorld, rewriter: PhoneRowRewriter = PhoneRowRewriter(w.phone)) = DefaultCorrections(
    share = w.share,
    catalog = w.catalog,
    engine = w.engine,
    journal = w.journal,
    gate = w.gate,
    clock = w.clock,
    phone = w.phone.port,
    reader = w.nas.reader,
    writer = FakeMetadataWriter(mimeOf = { "image/jpeg" }),
    check = WrittenFileCheck { _, _, _ -> WrittenCheck.VALID },
    rewriter = rewriter,
    staged = w.staged,
    io = EmptyCoroutineContext,
  )

  // --- R3: "Ripristina originale" non sovrascrive una versione che non e' al sicuro ---------------------------------------------

  /**
   * R3, lo scenario della revisione: dopo una versione gia' salvata, una seconda modifica sul telefono che il backup non ha ancora salvato.
   * Prima: il piano diceva `AVAILABLE`, lo scambio partiva e la riga del telefono si riscriveva con l'originale; la seconda modifica non era
   * piu' da nessuna parte. Adesso il ripristino si ferma e lo dice; quando il backup l'ha salvata si puo' fare.
   */
  @Test
  fun r3_unaModificaNonAncoraSalvataFermaIlRipristino() = runTest {
    val k = savedEdit()
    val w = k.w
    val v2 = w.bytes(k.seed.content.size + 2_000)
    w.editOnPhone(k.seed, v2)
    val before = w.snapshot()
    val plan = k.keeper.plan(k.seed.mediaId)
    assertThat(plan.restore).isEqualTo(RestoreAvailability.CURRENT_NOT_SAFE)
    k.rewriter.granted += k.seed.phoneId!!
    val ref = w.phone.ref(k.seed.phoneId)!!
    val outcome = k.keeper.restoreOriginal(k.seed.mediaId, TestConfirmations.confirm(plan, w.clock.monotonic), PhoneWriteGrant(setOf(ref)))
    assertThat(outcome).isEqualTo(RestoreOutcome.Unavailable(RestoreAvailability.CURRENT_NOT_SAFE))
    // Niente toccato: la seconda modifica e' ancora sul telefono, il NAS com'era, nessuno scambio.
    assertThat(w.phone.sha256(k.seed.phoneId)).isEqualTo(Sha256.of(v2))
    assertThat(w.files()).isEqualTo(before.files.getValue(share))
    assertThat(w.journal.all().none { it.kind == UploadKind.SWAP }).isTrue()
    assertThat(k.rewriter.replaced).isEmpty()
    // Il backup la mette al sicuro come versione: da li' il ripristino si puo' fare (e v2 resta in Versioni/).
    w.engine.planPhoneVersions(listOf(w.candidate(k.seed.mediaId, k.seed.phoneId)))
    w.runUntilQuiet()
    assertThat(k.keeper.plan(k.seed.mediaId).restore).isEqualTo(RestoreAvailability.AVAILABLE)
  }

  /**
   * R3, la variante del catalogo in ritardo: l'utente modifica in Samsung e torna subito nell'app, prima della lettura del telefono. Il catalogo
   * crede che la riga abbia v1 (al sicuro); il riscrittore controlla l'impronta della riga e non la sovrascrive. Lo scambio sul NAS c'e' (due
   * rinomine, nessun file perso) e il foglio dice che sul telefono resta la modifica.
   */
  @Test
  fun r3_seLaRigaECambiataDopoLUltimaLetturaNonSiSovrascrive() = runTest {
    val k = savedEdit()
    val w = k.w
    val unsaved = w.bytes(k.seed.content.size + 3_000)
    // Samsung "Salva" senza che la lettura del telefono l'abbia ancora visto.
    w.phone.rewrite(k.seed.phoneId!!, NasContent.of(unsaved), 77_000)
    w.knownContents += Sha256.of(unsaved)
    val before = w.snapshot()
    val plan = k.keeper.plan(k.seed.mediaId)
    assertThat(plan.restore).isEqualTo(RestoreAvailability.AVAILABLE)
    val confirmation = TestConfirmations.confirm(plan, w.clock.monotonic)
    val asked = k.keeper.restoreOriginal(k.seed.mediaId, confirmation, null) as RestoreOutcome.NeedsPhoneWrite
    k.rewriter.granted += k.seed.phoneId
    val outcome = k.keeper.restoreOriginal(k.seed.mediaId, confirmation, PhoneWriteGrant(asked.rows.toSet()))
    assertThat(outcome).isEqualTo(RestoreOutcome.PhoneNotRewritten(PhoneKeptReason.PHONE_CHANGED))
    // La modifica non salvata e' ancora sul telefono; sul NAS ci sono tutte le versioni di prima.
    assertThat(w.phone.sha256(k.seed.phoneId)).isEqualTo(Sha256.of(unsaved))
    assertThat(k.rewriter.replaced).isEmpty()
    assertThat(w.sha(share, k.seed.usualPath.value)).isEqualTo(Sha256.of(k.seed.content))
    assertThat(w.violations(before, final = true)).isEmpty()
  }

  /** R3 (punto 2): lo scambio di "Ripristina originale" sposta in `Versioni/` la versione **attuale**, mai un'altra al suo posto. */
  @Test
  fun r3_loScambioSpostaSoloLaVersioneAttuale() = runTest {
    val k = savedEdit()
    val w = k.w
    // Una versione nuova attuale che non e' ancora sul NAS (un editor esterno: il file preparato aspetta il suo lavoro).
    w.catalog.addVersion(k.seed.mediaId, VersionKind.EDITED, size = 90_000, fullHash = Sha256.of(w.bytes(10)))
    val media = w.catalog.media(k.seed.mediaId)!!
    assertThat(VersionPlanning.swap(media, share)).isNull()
    assertThat(k.keeper.plan(k.seed.mediaId).restore).isEqualTo(RestoreAvailability.CURRENT_NOT_SAFE)
    // Il riallineamento di E-E2 (target = l'attuale) non cambia: sposta qualunque altra versione al solito percorso.
    assertThat(VersionPlanning.realign(media, share)).isNull()
  }

  /** R3 per le correzioni: oltre alla prima riga (gia' riletta), ogni riga del telefono deve avere ancora i byte della versione al sicuro. */
  @Test
  fun r3_correzioniUnaSecondaRigaCambiataNonSiTocca() = runTest {
    val w = VersionWorld(folder.newFolder())
    val seed = w.seedSafe()
    // Una seconda copia della stessa versione sul telefono (la scheda SD), che poi cambia senza che la lettura del telefono lo veda.
    val second = w.phone.add(500, NasContent.of(seed.content), 5_000)
    w.catalog.putPhoneCopy(seed.mediaId, seed.original, second, w.phone.stamp(500)!!)
    val changed = w.bytes(seed.content.size)
    w.phone.rewrite(500, NasContent.of(changed), 6_000)
    val rewriter = PhoneRowRewriter(w.phone)
    val c = corrections(w, rewriter)
    val plan = c.plan(seed.mediaId, CorrectionRequest(ChangeSamples.newDate))
    assertThat(plan.phoneRows).hasSize(2)
    rewriter.granted += setOf(seed.phoneId!!, 500L)
    val outcome = c.apply(plan, TestConfirmations.confirm(plan, w.clock.monotonic), PhoneWriteGrant(plan.phoneRows.toSet()))
    assertThat(outcome).isEqualTo(CorrectionOutcome.Failed(CorrectionFailure.PHONE_CHANGED))
    assertThat(w.phone.sha256(500)).isEqualTo(Sha256.of(changed))
    assertThat(rewriter.replaced).containsExactly(seed.phoneId)
  }

  // --- R16: uno scambio senza conferma nasce solo da un fatto del telefono ------------------------------------------------------

  /**
   * R16: la scansione dell'area comune riconosce un ritorno a una versione nota (un familiare di un altro account ha copiato dal PC l'originale
   * sopra la modifica). L'attuale del media diventa l'originale, ma il telefono ha ancora la modifica: nessuno scambio nell'area privata.
   */
  @Test
  fun r16_unRitornoVistoDallaScansioneDelNasNonFaUnoScambio() = runTest {
    val k = savedEdit()
    val w = k.w
    val before = w.snapshot()
    w.catalog.nasReverted(k.seed.mediaId, k.seed.original)
    assertThat(w.engine.planRealign(k.seed.mediaId)).isNull()
    w.runUntilQuiet()
    assertThat(w.journal.all().none { it.kind == UploadKind.SWAP }).isTrue()
    assertThat(w.files()).isEqualTo(before.files.getValue(share))
    // Lo stesso ritorno fatto sul telefono (Samsung "Ripristina originale", E-E2) lo scambio lo fa ancora.
    w.revertOnPhone(k.seed)
    assertThat(w.engine.planRealign(k.seed.mediaId)?.kind).isEqualTo(UploadKind.SWAP)
  }

  // --- R17: la franchigia del congelamento vale per la rinomina attesa e per poco ------------------------------------------------

  /**
   * R17: prima la franchigia valeva per ogni `fileId` che le versioni avessero mai toccato. Adesso solo finche' il catalogo non ha seguito la
   * rinomina (poi un cambiamento sul posto, stesso `fileId`, e' di qualcun altro e conta) e per una settimana d'ora del NAS; senza l'ora del NAS
   * nessuna.
   */
  @Test
  fun r17_laFranchigiaValeSoloFinoAlCatalogoEPerUnaSettimana() = runTest {
    val w = VersionWorld(folder.newFolder())
    val seed = w.seedSafe()
    val untouched = w.seedSafe(name = "Altro.jpg")
    val untouchedId = w.files().getValue(untouched.usualPath.value).second!!
    // Il catalogo come lo vede la scansione arrivata fra la rinomina sul NAS e il catalogo che la segue.
    val stale = w.catalog.media(seed.mediaId)!!
    var lagging = true
    val view = object : VersionCatalog by w.catalog {
      override suspend fun media(mediaId: MediaId): VersionedMedia? = if (lagging && mediaId == seed.mediaId) stale else w.catalog.media(mediaId)
    }
    var nasNow: Long? = null
    val franchise = VersionFranchise(w.journal, catalog = { view }, nasNowMs = { nasNow ?: w.clock.nasNow })
    w.editOnPhone(seed)
    w.engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, seed.phoneId!!)))
    w.runUntilQuiet()
    val published = w.files().getValue(seed.usualPath.value).second!!
    // Il catalogo non ha ancora seguito: la versione pubblicata e' in franchigia, un file mai toccato no, l'area comune nemmeno.
    assertThat(franchise.fileIds(share)).contains(published)
    assertThat(franchise.fileIds(share)).doesNotContain(untouchedId)
    assertThat(franchise.fileIds(VersionWorld.COMMON_SHARE)).isEmpty()
    // Il catalogo ha seguito: da qui un file cambiato sul posto (un ransomware che cifra con lo stesso fileId) conta fra i "cambiati".
    lagging = false
    assertThat(franchise.fileIds(share)).isEmpty()
    // Oltre una settimana d'ora del NAS dal lavoro, nemmeno con il catalogo in ritardo.
    lagging = true
    nasNow = w.clock.nasNow!! + VersionFranchise.WINDOW.inWholeMilliseconds + 1
    assertThat(franchise.fileIds(share)).isEmpty()
    // Senza l'ora del NAS nessuna franchigia (la guardia conta tutto).
    assertThat(VersionFranchise(w.journal, catalog = { view }, nasNowMs = { null }).fileIds(share)).isEmpty()
  }

  // --- VE-1: le versioni di un video con le regole dei video ---------------------------------------------------------------------

  /**
   * VE-1: il giro delle foto guidava tutti i lavori delle versioni, anche un video da 3 GB, con le regole e il tempo delle foto. Adesso un giro
   * guida solo le versioni del suo tipo; "Proteggi subito" solo quelle che ha pianificato lui.
   */
  @Test
  fun ve1_ilGiroDiUnTipoGuidaSoloLeVersioniDiQuelTipo() = runTest {
    val w = VersionWorld(folder.newFolder())
    val photo = w.seedSafe()
    val video = w.seedSafe(name = "VID_1.mp4", kind = MediaKind.VIDEO, mime = "video/mp4")
    w.editOnPhone(photo)
    w.editOnPhone(video)
    val jobs = w.engine.planPhoneVersions(listOf(w.candidate(photo.mediaId, photo.phoneId!!), w.candidate(video.mediaId, video.phoneId!!)))
    assertThat(jobs).hasSize(2)
    val photoJob = jobs.single { it.mediaId == photo.mediaId }.id
    val videoJob = jobs.single { it.mediaId == video.mediaId }.id
    w.engine.run(1.hours, kinds = setOf(BackupKind.PHOTOS))
    assertThat(w.journal.job(photoJob)!!.state).isEqualTo(UploadState.DONE)
    assertThat(w.journal.job(videoJob)!!.state).isEqualTo(UploadState.PLANNED)
    w.engine.run(1.hours, kinds = setOf(BackupKind.VIDEOS))
    assertThat(w.journal.job(videoJob)!!.state).isEqualTo(UploadState.DONE)

    // "Proteggi subito": solo i lavori del suo giro.
    val other = w.seedSafe(name = "IMG_2.jpg")
    w.editOnPhone(photo)
    w.editOnPhone(other)
    val more = w.engine.planPhoneVersions(listOf(w.candidate(photo.mediaId, photo.phoneId), w.candidate(other.mediaId, other.phoneId!!)))
    val mine = more.first().id
    w.engine.run(1.hours, kinds = setOf(BackupKind.PHOTOS), only = setOf(mine))
    assertThat(w.journal.job(mine)!!.state).isEqualTo(UploadState.DONE)
    assertThat(w.journal.job(more.last().id)!!.state).isEqualTo(UploadState.PLANNED)
  }

  // --- MC-4: "Modifica con…" di un solo-NAS solo se la modifica puo' diventare una versione sul NAS ------------------------------

  /**
   * MC-4: dalla 5B non si scrive nell'area comune, e con D13 solo sotto la radice di prova. Un solo-NAS proprio che sta solo nell'area comune (o,
   * in debug, fuori da `mcg-campo/`) non avrebbe un lavoro `VERSION`: la modifica si accettava e restava nell'app per sempre. Adesso lo si sa
   * prima di aprire l'editor.
   */
  @Test
  fun mc4_unaModificaSiApreSoloSePuoDiventareUnaVersioneSulNas() = runTest {
    val w = VersionWorld(folder.newFolder())
    val private = w.seedSafe(onPhone = false)
    val common = w.seedSafe(name = "C.jpg", onPhone = false, targetShare = VersionWorld.COMMON_SHARE)
    assertThat(w.engine.canPlanStaged(private.mediaId)).isTrue()
    assertThat(w.engine.canPlanStaged(common.mediaId)).isFalse()
    // Con la recinzione di D13 (debug, account di prova) solo sotto la radice di prova.
    val fenced = VersionWorld(folder.newFolder(), config = VersionConfig(fenceRoot = dev.pampa.mycloudgallery.core.trust.nas.SharePath.parse("mcg-campo")))
    val outside = fenced.seedSafe(onPhone = false)
    val inside = fenced.seedSafe(name = "D.jpg", onPhone = false, folder = "mcg-campo/Backup/S25 di prova/2026/10")
    assertThat(fenced.engine.canPlanStaged(outside.mediaId)).isFalse()
    assertThat(fenced.engine.canPlanStaged(inside.mediaId)).isTrue()
  }

  // --- VE-7, VE-8, VE-4: i fogli dicono il perche', chiedono il permesso subito, non mentono sulla rotazione -----------------------

  /** VE-7: la pausa e il backup in prova hanno la loro ragione (prima "non e' ancora pronta" per tutto); un lavoro in corso e' "occupato". */
  @Test
  fun ve7_pausaProvaEOccupatoHannoLaLoroRagione() = runTest {
    val k = savedEdit()
    val w = k.w
    val c = corrections(w)
    val change = CorrectionRequest(ChangeSamples.newDate)
    w.gate.default = BackupGateState.PAUSED
    assertThat(k.keeper.plan(k.seed.mediaId).restore).isEqualTo(RestoreAvailability.PAUSED)
    assertThat(c.plan(k.seed.mediaId, change).readiness).isEqualTo(CorrectionReadiness.PAUSED)
    w.gate.default = BackupGateState.TEST_ONLY
    assertThat(k.keeper.plan(k.seed.mediaId).restore).isEqualTo(RestoreAvailability.TEST_ONLY)
    assertThat(c.plan(k.seed.mediaId, change).readiness).isEqualTo(CorrectionReadiness.TEST_ONLY)
    w.gate.default = BackupGateState.OPEN
    // Un altro lavoro dello stesso media aperto (uno scambio pianificato e non ancora fatto): "occupato", non "non pronta".
    assertThat(w.engine.planSwap(k.seed.mediaId)).isNotNull()
    assertThat(c.plan(k.seed.mediaId, change).readiness).isEqualTo(CorrectionReadiness.BUSY)
  }

  /**
   * VE-8: il permesso del dialogo si sa senza il turno del backup. `precheck` non tocca niente e dice subito `NeedsPhoneWrite`; con il permesso
   * dice "procedi" (null), e solo allora si aspetta il turno.
   */
  @Test
  fun ve8_ilPermessoDelTelefonoSiChiedePrimaDelTurno() = runTest {
    val k = savedEdit()
    val w = k.w
    val before = w.snapshot()
    val plan = k.keeper.plan(k.seed.mediaId)
    val confirmation = TestConfirmations.confirm(plan, w.clock.monotonic)
    val ref = w.phone.ref(k.seed.phoneId!!)!!
    assertThat(k.keeper.precheck(k.seed.mediaId, confirmation, null)).isEqualTo(RestoreOutcome.NeedsPhoneWrite(listOf(ref)))
    assertThat(k.keeper.precheck(k.seed.mediaId, confirmation, PhoneWriteGrant(setOf(ref)))).isNull()
    assertThat(w.journal.all().none { it.kind == UploadKind.SWAP }).isTrue()
    assertThat(w.files()).isEqualTo(before.files.getValue(share))

    val fresh = VersionWorld(folder.newFolder())
    val seed = fresh.seedSafe()
    val cc = corrections(fresh)
    val cplan = cc.plan(seed.mediaId, CorrectionRequest(ChangeSamples.newDate))
    val cconf = TestConfirmations.confirm(cplan, fresh.clock.monotonic)
    val row = fresh.phone.ref(seed.phoneId!!)!!
    assertThat(cc.precheck(cplan, cconf, null)).isEqualTo(CorrectionOutcome.NeedsWriteGrant(listOf(row)))
    assertThat(cc.precheck(cplan, cconf, PhoneWriteGrant(setOf(row)))).isNull()
    assertThat(fresh.journal.all()).isEmpty()
  }

  /**
   * VE-4: "Ruota" di un HEIC diceva "fatto" ma la foto restava storta (miniature e viewer leggono l'orientamento dai byte del file). Adesso la
   * rotazione di un formato che non si scrive non si fa e non si registra; data e luogo restano correggibili nell'app (Q6 (a)).
   */
  @Test
  fun ve4_laRotazioneDiUnHeicNonSiFaENonDiceFatto() = runTest {
    val w = VersionWorld(folder.newFolder())
    val seed = w.seedSafe(name = "IMG_1.heic", mime = "image/heic")
    val c = corrections(w)
    val rotate = c.plan(seed.mediaId, CorrectionRequest(MetadataChange(orientation = ExifOrientation.ROTATE_90)))
    assertThat(rotate.readiness).isEqualTo(CorrectionReadiness.ROTATION_NOT_WRITABLE)
    assertThat(c.apply(rotate, TestConfirmations.confirm(rotate, w.clock.monotonic), null))
      .isEqualTo(CorrectionOutcome.Unavailable(CorrectionReadiness.ROTATION_NOT_WRITABLE))
    assertThat(w.catalog.inAppCorrections).isEmpty()
    // La data si corregge nell'app come prima.
    val date = c.plan(seed.mediaId, CorrectionRequest(ChangeSamples.newDate))
    assertThat(date.readiness).isEqualTo(CorrectionReadiness.FORMAT_NOT_WRITABLE)
    assertThat(c.apply(date, TestConfirmations.confirm(date, w.clock.monotonic), null)).isEqualTo(CorrectionOutcome.FormatNotWritable)
    assertThat(w.catalog.inAppCorrections).hasSize(1)
  }
}
