package dev.pampa.mycloudgallery.core.backup.versions

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.HashRecord
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.contract.VerifiedCopy
import dev.pampa.mycloudgallery.core.backup.testing.FakePhoneBytes
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.ops.NasSpaceInfo
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Fase 5B, P8: i casi limite del caricamento di una versione, gli stessi della 5A applicati al lavoro `VERSION`: il disco o la quota pieni a
 * meta' (E-R9), il file del telefono riscritto mentre lo si legge (E-B15) o cresciuto senza che il MediaStore lo sappia (R6), il MediaStore
 * ricostruito con gli `_ID` rinumerati (E-P12) e un errore inatteso prima e dopo lo spostamento (R1: parcheggiato solo se niente si e' mosso).
 */
class VersionEdgeCasesTest {

  @get:Rule val folder = TemporaryFolder()

  private val share = VersionWorld.PRIVATE_SHARE
  private val archived = "Versioni/Backup/S25 di prova/2026/10/IMG_1234 (originale).jpg"

  private suspend fun VersionWorld.editedAndPlanned(): Pair<VersionWorld.Seeded, Sha256> {
    val seed = seedSafe()
    editOnPhone(seed)
    engine.planPhoneVersions(listOf(candidate(seed.mediaId, seed.phoneId!!)))
    return seed to phone.sha256(seed.phoneId)!!
  }

  @Test
  fun e_r9_ilNasPienoAMetaFermaIlGiroToglieIlTemporaneoEPoiRiprende() = runTest {
    val w = VersionWorld(folder.newFolder())
    val (seed, v1) = w.editedAndPlanned()
    val before = w.snapshot()
    w.nas.spaceOf = { NasSpaceInfo(totalBytes = 1L shl 30, freeBytes = 20_000, callerFreeBytes = 20_000) }
    val full = w.engine.run(1.hours)
    assertThat(full.stoppedBy).isEqualTo(RunStop.NAS)
    assertThat(full.problem).isEqualTo(BackupProblemCode.NAS_FULL)
    val job = w.journal.all().single()
    assertThat(job.state).isEqualTo(UploadState.UPLOADING)
    assertThat(job.resumeAt).isEqualTo(0)
    assertThat(w.nas.exists(share, job.temp!!.value)).isFalse()
    assertThat(w.sha(share, seed.usualPath.value)).isEqualTo(Sha256.of(seed.content))
    w.nas.spaceOf = { NasSpaceInfo(totalBytes = 1L shl 40, freeBytes = 1L shl 39, callerFreeBytes = 1L shl 39) }
    w.runUntilQuiet()
    assertThat(w.sha(share, seed.usualPath.value)).isEqualTo(v1)
    assertThat(w.violations(before, final = true)).isEmpty()
  }

  @Test
  fun e_b15_ilFileDelTelefonoRiscrittoMentreSiLeggeNonDiventaUnaVersione() = runTest {
    val w = VersionWorld(folder.newFolder())
    val seed = w.seedSafe()
    w.editOnPhone(seed)
    w.engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, seed.phoneId!!)))
    val before = w.snapshot()
    // Samsung "Salva" una seconda volta mentre il backup legge la prima modifica.
    val second = w.bytes(75_000)
    w.knownContents += Sha256.of(second)
    w.phone.duringRead(seed.phoneId, afterBytes = 20_000, FakePhoneBytes.MidRead.Rewritten(NasContent.of(second), 9_999))
    w.engine.run(1.hours)
    assertThat(w.journal.all().single().state).isEqualTo(UploadState.GONE)
    assertThat(w.files()).isEqualTo(before.files.getValue(share))
    // La lettura del telefono registra la seconda modifica: e' lei la versione nuova, con il suo lavoro.
    w.catalog.phoneChanged(seed.mediaId, w.phone.ref(seed.phoneId)!!, w.phone.stamp(seed.phoneId)!!, second.size.toLong(), null)
    w.engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, seed.phoneId)))
    w.runUntilQuiet()
    assertThat(w.sha(share, seed.usualPath.value)).isEqualTo(Sha256.of(second))
    assertThat(w.sha(share, archived)).isEqualTo(Sha256.of(seed.content))
    assertThat(w.violations(before, final = true)).isEmpty()
  }

  @Test
  fun r6_unFileCresciutoSenzaCheIlMediaStoreLoSappiaNonEUnaVersione() = runTest {
    val w = VersionWorld(folder.newFolder())
    val (seed, _) = w.editedAndPlanned()
    val before = w.files()
    // Il file e' piu' lungo di quanto dice la riga (un'app che scrive in piu' sessioni): i byte letti sarebbero un prefisso.
    w.phone.growSilently(seed.phoneId!!, NasContent.of(w.bytes(90_000)))
    w.engine.run(1.hours)
    assertThat(w.journal.all().single().state).isEqualTo(UploadState.GONE)
    assertThat(w.files()).isEqualTo(before)
  }

  @Test
  fun e_p12_ilMediaStoreRinumeratoSiRitrovaDalCatalogo() = runTest {
    val w = VersionWorld(folder.newFolder())
    val (seed, v1) = w.editedAndPlanned()
    // Il MediaStore si ricostruisce: la riga cambia _ID e versione; la lettura del telefono la ritrova per impronta.
    w.phone.rebuild("v2", mapOf(seed.phoneId!! to 4_242L))
    assertThat(w.engine.run(1.hours).waiting).isEqualTo(1)
    w.catalog.phoneRenumbered(seed.mediaId, seed.phoneId, w.phone.ref(4_242L)!!)
    w.runUntilQuiet()
    assertThat(w.journal.all().single().state).isEqualTo(UploadState.DONE)
    assertThat(w.sha(share, seed.usualPath.value)).isEqualTo(v1)
  }

  /** R1: un errore inatteso prima di spostare parcheggia il lavoro; dopo lo lascia aperto e il lavoro finisce al giro dopo. */
  @Test
  fun r1_unErroreInattesoParcheggiaSoloPrimaDiSpostare() = runTest {
    val w = VersionWorld(folder.newFolder())
    val (seed, _) = w.editedAndPlanned()
    var failHash = true
    val broken = object : VersionCatalog by w.catalog {
      override suspend fun recordFullHash(mediaId: MediaId, versionId: VersionId, sha256: Sha256): HashRecord {
        if (failHash) error("guasto inatteso")
        return w.catalog.recordFullHash(mediaId, versionId, sha256)
      }
    }
    w.newEngine(catalog = broken).run(1.hours)
    val parked = w.journal.all().single()
    assertThat(parked.state).isEqualTo(UploadState.FAILED)
    assertThat(parked.problem).isEqualTo(BackupProblemCode.UNEXPECTED)
    assertThat(w.catalog.problems[seed.mediaId]).isEqualTo(BackupProblemCode.UNEXPECTED)
    assertThat(w.journal.citedTemps()).isEmpty()

    // Dopo lo spostamento: il catalogo che si rompe al collegamento non fa fallire il lavoro (il solito percorso resterebbe vuoto).
    failHash = false
    val w2 = VersionWorld(folder.newFolder())
    val (seed2, v2) = w2.editedAndPlanned()
    var failAttach = true
    val brokenAttach = object : VersionCatalog by w2.catalog {
      override suspend fun attachVerifiedCopy(copy: VerifiedCopy): Boolean {
        if (failAttach) error("guasto inatteso")
        return w2.catalog.attachVerifiedCopy(copy)
      }
    }
    w2.newEngine(catalog = brokenAttach).run(1.hours)
    val open = w2.journal.all().single()
    assertThat(open.state).isEqualTo(UploadState.PUBLISHED)
    assertThat(w2.sha(share, seed2.usualPath.value)).isEqualTo(v2)
    failAttach = false
    w2.runUntilQuiet()
    assertThat(w2.journal.all().single().state).isEqualTo(UploadState.DONE)
  }
}
