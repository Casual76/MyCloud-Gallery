package dev.pampa.mycloudgallery.utility

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.PhoneBytes
import dev.pampa.mycloudgallery.core.backup.contract.PhoneDenied
import dev.pampa.mycloudgallery.core.backup.contract.PhoneLookup
import dev.pampa.mycloudgallery.core.backup.contract.PhoneOpen
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.library.duplicates.AlbumMembership
import dev.pampa.mycloudgallery.core.library.duplicates.PhoneTrashRef
import dev.pampa.mycloudgallery.core.library.visibility.CatalogHiddenMask
import dev.pampa.mycloudgallery.core.library.visibility.CatalogVisibleMedia
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.WorkStage
import dev.pampa.mycloudgallery.core.model.WorkStatus
import dev.pampa.mycloudgallery.hidden.WorldSpec
import dev.pampa.mycloudgallery.hidden.HiddenWorldBuilder
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Revisione di Fase 7, pacchetto C, in `:app`: **R18** il controllo delle copie del telefono sulla porta vera del MediaStore, e **DP-5** la manutenzione
 * notturna che non ritira dalla coda l'hash completo quando i dati personali non sono pronti.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class UtilityRevisionCTest {

  private val context: Context get() = ApplicationProvider.getApplicationContext()

  // --- R18: il timbro delle copie del telefono -------------------------------------------------------------------------------------

  private fun copy(id: Long, size: Long = 3_000, modifiedAt: Long = 1_000, version: String = "store-v1") =
    PhoneTrashRef(MediaRef.Phone("external_primary", id, "image/jpeg"), FileStamp(size, modifiedAt, generation = 7), version)

  /** Un telefono che risponde per `_ID` con quello che gli si dice. */
  private class Phone(val answers: Map<Long, PhoneLookup>, val failing: Boolean = false) : PhoneBytes {
    override suspend fun lookup(ref: PhoneRef): PhoneLookup {
      check(!failing) { "MediaStore occupato" }
      return answers[ref.mediaStoreId] ?: PhoneLookup.Gone
    }

    override suspend fun open(ref: PhoneRef): PhoneOpen = PhoneOpen.Gone
  }

  @Test
  fun r18_UnaCopiaSovrascrittaDopoLaScansione_StessoIdByteNuovi_NonVaNelCestino() = runBlocking<Unit> {
    val same = copy(1)
    val overwritten = copy(2)
    val gone = copy(3)
    val stale = copy(4)
    val denied = copy(5)
    val phone = Phone(
      mapOf(
        1L to PhoneLookup.Present(FileStamp(3_000, 1_000, generation = 7)),
        // Stesso `_ID`, un'altra dimensione e un'altra data: un file nuovo al posto di quello visto dalla scansione.
        2L to PhoneLookup.Present(FileStamp(4_500, 9_000, generation = 12)),
        4L to PhoneLookup.Stale,
        5L to PhoneLookup.Denied(PhoneDenied.NOT_SELECTED),
      ),
    )

    val kept = MediaStorePhoneCopyCheck(phone).unchanged(listOf(same, overwritten, gone, stale, denied))

    assertThat(kept.map { it.ref.mediaStoreId }).containsExactly(1L)
  }

  @Test
  fun r18_SeIlTelefonoNonRisponde_NessunaCopiaPassa() = runBlocking<Unit> {
    val kept = MediaStorePhoneCopyCheck(Phone(emptyMap(), failing = true)).unchanged(listOf(copy(1), copy(2)))

    assertThat(kept).isEmpty()
  }

  @Test
  fun r18_LaVersioneDelMediaStoreCheValevaAlPianoSiRiusaPerIlControllo() = runBlocking<Unit> {
    var asked: PhoneRef? = null
    val phone = object : PhoneBytes {
      override suspend fun lookup(ref: PhoneRef): PhoneLookup {
        asked = ref
        return PhoneLookup.Present(FileStamp(3_000, 1_000, generation = 7))
      }

      override suspend fun open(ref: PhoneRef): PhoneOpen = PhoneOpen.Gone
    }

    MediaStorePhoneCopyCheck(phone).unchanged(listOf(copy(1, version = "store-v9")))

    // L'`_ID` vale solo con la versione del MediaStore con cui e' stato letto: la porta del telefono ne dice "ricostruito" se e' cambiata.
    assertThat(asked!!.mediaStoreVersion).isEqualTo("store-v9")
    assertThat(asked!!.mediaStoreId).isEqualTo(1L)
  }

  // --- DP-5: la manutenzione notturna con i dati personali non pronti ---------------------------------------------------------------

  private suspend fun graph(world: dev.pampa.mycloudgallery.hidden.BuiltWorld, fullHashOn: () -> Boolean?): UtilityGraph =
    UtilityGraph(
      catalog = { world.catalog },
      visible = CatalogVisibleMedia(world.db, MutableStateFlow(world.snapshot)),
      hidden = CatalogHiddenMask(world.db),
      albums = AlbumMembership { emptySet() },
      signatures = { null },
      fullHashOn = fullHashOn,
    )

  private suspend fun fullHashPending(world: dev.pampa.mycloudgallery.hidden.BuiltWorld, media: dev.pampa.mycloudgallery.core.model.MediaId): Boolean =
    world.catalog.workStates(media).any { it.stage == WorkStage.FULL_HASH && it.status == WorkStatus.PENDING }

  @Test
  fun dp5_ConIDatiPersonaliNonPronti_LaNotteNonRitiraLeRigheDelloUtente() = runBlocking<Unit> {
    val world = HiddenWorldBuilder.build(context, WorldSpec.nothingHidden())
    try {
      val index = world.spec.media.first { it.kind == MediaKind.VIDEO && it.onNas }.index
      val video = world.ids[index]
      world.catalog.requestWork(listOf(video), WorkStage.FULL_HASH)
      assertThat(fullHashPending(world, video)).isTrue()

      // "Non pronto" (null) non e' "spento": la riga che l'utente ha chiesto resta in coda.
      graph(world) { null }.nightly(30.seconds)
      assertThat(fullHashPending(world, video)).isTrue()

      // Spento davvero: l'interruttore ritira le righe che aveva messo (comportamento di prima, invariato).
      graph(world) { false }.nightly(30.seconds)
      assertThat(fullHashPending(world, video)).isFalse()
    } finally {
      world.close()
    }
  }
}
