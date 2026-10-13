package dev.pampa.mycloudgallery.core.mediastore.meta

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.PauseReason
import dev.pampa.mycloudgallery.core.mediastore.contract.RunResult
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.core.mediastore.noop.NoMetaPriority
import dev.pampa.mycloudgallery.core.mediastore.sync.InlineDispatchers
import dev.pampa.mycloudgallery.core.mediastore.sync.PhoneLibraryStats
import dev.pampa.mycloudgallery.core.mediastore.sync.PhoneSyncTestBase
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMetaPriority
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractCode
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractHints
import dev.pampa.mycloudgallery.core.metadata.contract.Extraction
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataExtractor
import dev.pampa.mycloudgallery.core.model.CaptureTime
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaMetadata
import dev.pampa.mycloudgallery.core.model.WorkStage
import dev.pampa.mycloudgallery.core.model.WorkState
import dev.pampa.mycloudgallery.core.model.WorkStatus
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** La fase META dopo il commit (§2.1, D11): prima i visibili, solo i byte della versione corrente, foto con il permesso. */
@RunWith(AndroidJUnit4::class)
class MetadataPassTest : PhoneSyncTestBase() {

  /** Un estrattore a copione che ricorda l'ordine dei file letti (solo nel test: i nomi non escono mai). */
  private class RecordingExtractor(var byName: Map<String, Extraction> = emptyMap()) : MetadataExtractor {
    val names = mutableListOf<String>()

    override suspend fun extract(bytes: MediaBytes, hints: ExtractHints): Extraction {
      names += hints.displayName
      bytes.read(0L until minOf(bytes.size, 64L))
      return byName[hints.displayName] ?: read(MediaKind.PHOTO, PhoneSyncTestBase.T0)
    }
  }

  private val extractor = RecordingExtractor()
  private val stats = PhoneLibraryStats()

  private fun pass(monotonic: () -> Long = { mono }) = DefaultMetadataPass(
    source = store, lookup = store, access = access, catalog = catalog, reads = reads, extractor = extractor,
    zone = { ROME }, clock = { now }, monotonicMs = monotonic, dispatchers = InlineDispatchers, config = config, stats = stats,
  )

  private suspend fun meta(id: MediaId): WorkState = catalog.workStates(id).first { it.stage == WorkStage.META }

  @Test
  fun scriveMetadatiETipoEContaGliEsiti() = runBlocking<Unit> {
    val motion = store.put(photo(1, taken = T0 + 3 * MINUTE, name = "MOTION.jpg"))
    val clip = store.put(video(2, taken = T0 + 2 * MINUTE))
    val broken = store.put(photo(3, taken = T0 + MINUTE, name = "ROTTA.jpg"))
    val fake = store.put(photo(4, taken = T0, name = "FALSA.jpg"))
    newSync().syncNow(SyncReason.APP_START)
    extractor.byName = mapOf(
      "MOTION.jpg" to read(MediaKind.MOTION_PHOTO, T0 + 3 * MINUTE),
      clip.displayName to read(MediaKind.VIDEO, T0 + 2 * MINUTE),
      "ROTTA.jpg" to Extraction.Corrupt(ExtractCode.DECODE_FAILED),
      "FALSA.jpg" to Extraction.NotMedia(ExtractCode.NOT_A_MEDIA_FILE),
    )

    val result = pass().run(5.minutes, NoMetaPriority)

    assertThat(result).isEqualTo(RunResult.Done)
    // Dal piu' recente.
    assertThat(extractor.names).containsExactly("MOTION.jpg", clip.displayName, "ROTTA.jpg", "FALSA.jpg").inOrder()
    val motionId = mediaAt(motion)!!
    assertThat(meta(motionId).status).isEqualTo(WorkStatus.DONE)
    assertThat(db.mediaDao().get(motionId.value)!!.kind).isEqualTo(MediaKind.MOTION_PHOTO.code)
    val version = catalog.media(motionId)!!.currentVersionId
    assertThat(db.versionDao().metadata(version.value)!!.takenAtUtc).isEqualTo(T0 + 3 * MINUTE)
    assertThat(meta(mediaAt(clip)!!).status).isEqualTo(WorkStatus.DONE)
    val brokenState = meta(mediaAt(broken)!!)
    assertThat(brokenState.status).isEqualTo(WorkStatus.FAILED)
    assertThat(brokenState.attempts).isEqualTo(1)
    assertThat(brokenState.lastError!!.value).isEqualTo("DECODE_FAILED")
    assertThat(meta(mediaAt(fake)!!).status).isEqualTo(WorkStatus.GAVE_UP)
    assertThat(stats.snapshot.value.metaDone).isEqualTo(2L)
    assertThat(stats.snapshot.value.metaFailed).isEqualTo(2L)

    // Un secondo giro non rilegge nulla (il fallito si riprova solo dopo un'ora).
    extractor.names.clear()
    assertThat(pass().run(5.minutes, NoMetaPriority)).isEqualTo(RunResult.Done)
    assertThat(extractor.names).isEmpty()
    now += 2 * 60 * MINUTE
    pass().run(5.minutes, NoMetaPriority)
    assertThat(extractor.names).containsExactly("ROTTA.jpg")
  }

  @Test
  fun primaIVisibili() = runBlocking<Unit> {
    (1L..4L).forEach { store.put(photo(it, taken = T0 + it * MINUTE)) }
    newSync().syncNow(SyncReason.APP_START)
    val oldest = mediaAt(photo(1, taken = T0 + MINUTE))!!

    pass().run(5.minutes, FakeMetaPriority(listOf(oldest)))

    assertThat(extractor.names.first()).isEqualTo("IMG_1.jpg")
    assertThat(extractor.names).hasSize(4)
  }

  @Test
  fun leFotoAspettanoIlPermessoDellaPosizioneIVideoNo() = runBlocking<Unit> {
    val still = store.put(photo(1, taken = T0))
    val clip = store.put(video(2, taken = T0 + MINUTE))
    newSync().syncNow(SyncReason.APP_START)

    setAccess(MediaAccess.FULL, mediaLocation = false)
    val result = pass().run(5.minutes, NoMetaPriority)

    assertThat(result).isEqualTo(RunResult.Paused(PauseReason.MEDIA_LOCATION_DENIED))
    assertThat(extractor.names).containsExactly(clip.displayName)
    assertThat(meta(mediaAt(still)!!).status).isEqualTo(WorkStatus.PENDING)
    assertThat(store.redactedOpenAttempts).isEqualTo(0)
    assertThat(stats.snapshot.value.metaWaitingForLocation).isEqualTo(1)
  }

  @Test
  fun unFileCambiatoDopoLaLetturaAspettaLaVersioneNuova() = runBlocking<Unit> {
    val row = store.put(photo(1, taken = T0))
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)
    val id = mediaAt(row)!!

    // Modificato fuori dall'app, prima che la sincronizzazione lo veda: META non legge byte di un'altra versione.
    store.put(edited(row))
    pass().run(5.minutes, NoMetaPriority)
    assertThat(extractor.names).isEmpty()
    assertThat(meta(id).status).isEqualTo(WorkStatus.PENDING)

    sync.syncNow(SyncReason.CONTENT_CHANGE)
    pass().run(5.minutes, NoMetaPriority)
    assertThat(extractor.names).containsExactly(row.displayName)
    assertThat(meta(id).versionId).isEqualTo(catalog.media(id)!!.currentVersionId)
  }

  @Test
  fun unIdLettoConUnAltraVersioneDelMediaStoreNonSiUsa() = runBlocking<Unit> {
    store.put(photo(1, taken = T0))
    store.put(photo(2, taken = T0 + MINUTE))
    newSync().syncNow(SyncReason.APP_START)

    // Il MediaStore si e' ricostruito con gli _ID scambiati e la sincronizzazione non e' ancora passata.
    store.bumpVersion(PRIMARY, "v2", renumber = mapOf(1L to 2L, 2L to 1L))
    pass().run(5.minutes, NoMetaPriority)
    assertThat(extractor.names).isEmpty()
  }

  @Test
  fun ilBudgetFermaMeta() = runBlocking<Unit> {
    (1L..6L).forEach { store.put(photo(it, taken = T0 + it * MINUTE)) }
    newSync().syncNow(SyncReason.APP_START)
    var clock = 0L
    val result = pass(monotonic = { clock.also { clock += MINUTE } }).run(3.minutes, NoMetaPriority)
    assertThat(result).isEqualTo(RunResult.BudgetExhausted(remaining = null))
    assertThat(extractor.names.size).isLessThan(6)
  }

  @Test
  fun senzaAccessoMetaEInPausa() = runBlocking<Unit> {
    setAccess(MediaAccess.NONE)
    assertThat(pass().run(5.minutes, NoMetaPriority)).isEqualTo(RunResult.Paused(PauseReason.NO_PERMISSION))
  }

  private companion object {
    fun read(kind: MediaKind, taken: Long) = Extraction.Read(
      metadata = MediaMetadata(
        mimeType = if (kind == MediaKind.VIDEO) "video/mp4" else "image/jpeg",
        width = 4_000,
        height = 3_000,
        captureTime = CaptureTime(taken, 120, CaptureTimeConfidence.EXACT, CaptureTimeSource.EXIF_WITH_OFFSET),
      ),
      kind = kind,
    )
  }
}
