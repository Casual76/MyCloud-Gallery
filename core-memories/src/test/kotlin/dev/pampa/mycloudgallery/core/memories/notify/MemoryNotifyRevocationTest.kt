package dev.pampa.mycloudgallery.core.memories.notify

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.testing.FakeVisibility
import dev.pampa.mycloudgallery.core.memories.contract.LockScreenMode
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.NotifyPrefs
import dev.pampa.mycloudgallery.core.memories.plan.dayShots
import dev.pampa.mycloudgallery.core.memories.shelf.MemoryVisibility
import dev.pampa.mycloudgallery.core.model.MediaId
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * La revoca della notifica (docs/fase9-progetto.md §2.6 "Revoca", D19; E-NT7, E-H8): la notifica in tendina si toglie quando il suo ricordo e'
 * nascosto, scartato o rimasto vuoto, e in "Titolo e foto" quando la sua copertina e' nascosta. Sul motore vero dei ricordi.
 */
class MemoryNotifyRevocationTest {

  private val today = LocalDate.of(2026, 11, 3)
  private val rigs = ArrayList<NotifyRig>()

  @After
  fun chiudiTutto() {
    rigs.forEach { it.close() }
  }

  private fun rig(mode: LockScreenMode, photos: Int = 12, visibility: ((FakeVisibility) -> MemoryVisibility)? = null): NotifyRig =
    NotifyRig(dayShots(today.minusYears(1), photos, series = 1), today, NotifyPrefs(lockScreen = mode), visibilityOverride = visibility).also { rigs += it }

  /** Un dispositivo con la notifica gia' in tendina per il suo unico ricordo. */
  private suspend fun posted(mode: LockScreenMode, photos: Int = 12, visibility: ((FakeVisibility) -> MemoryVisibility)? = null): Pair<NotifyRig, MemoryId> {
    val rig = rig(mode, photos, visibility)
    assertThat(rig.run().run()).isInstanceOf(NotifyOutcome.Posted::class.java)
    return rig to rig.publisher.current!!.memory
  }

  private suspend fun membersOf(rig: NotifyRig, id: MemoryId): List<MediaId> = rig.engine.engine.memory(id).first()!!.memory.members

  /** Senza notifica in tendina non c'e' niente da togliere (e niente da contare nella Diagnostica). */
  @Test
  fun senzaNotificaNonFaNiente() = runTest {
    val rig = rig(LockScreenMode.GENERIC)
    assertThat(rig.revocation.revalidate()).isFalse()
    assertThat(rig.events.revoked).isEqualTo(0)
  }

  /** Il ricordo esiste ancora: la notifica resta (anche dopo molte riletture). */
  @Test
  fun unRicordoMostrabileRestaInTendina() = runTest {
    for (mode in LockScreenMode.entries) {
      val (rig, _) = posted(mode)
      repeat(3) { assertThat(rig.revocation.revalidate()).isFalse() }
      assertThat(rig.publisher.current).isNotNull()
      assertThat(rig.events.revoked).isEqualTo(0)
    }
  }

  /** "Non mostrare questo ricordo": la notifica sparisce, **anche** nella modalita' generica (E-NT7). */
  @Test
  fun unRicordoScartatoTogliLaNotificaInOgniModalita() = runTest {
    for (mode in LockScreenMode.entries) {
      val (rig, id) = posted(mode)
      rig.engine.engine.dismiss(id)
      assertThat(rig.revocation.revalidate()).isTrue()
      assertThat(rig.publisher.current).isNull()
      assertThat(rig.events.revoked).isEqualTo(1)
      // Aperta una seconda volta non c'e' piu' niente da togliere.
      assertThat(rig.revocation.revalidate()).isFalse()
    }
  }

  /** Nascosti alcuni elementi, ne restano 3 o piu': il ricordo c'e' ancora e la notifica resta; sotto 3 il ricordo esce dallo scaffale e la notifica con lui. */
  @Test
  fun ilRicordoRimastoVuotoTogliLaNotifica() = runTest {
    for (mode in LockScreenMode.entries) {
      val (rig, id) = posted(mode)
      val members = membersOf(rig, id)
      // Via nove su dodici: ne restano 3 (il minimo, D9).
      rig.hideAndPrune(members.drop(3))
      assertThat(rig.revocation.revalidate()).isFalse()
      // Ne nascondo ancora uno: sotto 3 il ricordo non c'e' piu'.
      rig.hideAndPrune(members.take(1))
      assertThat(rig.engine.engine.shelf.value.items).isEmpty()
      assertThat(rig.revocation.revalidate()).isTrue()
      assertThat(rig.publisher.current).isNull()
    }
  }

  /** "Titolo e foto": la copertina diventa nascosta (anche prima della pulizia dello scaffale): la notifica sparisce; in modalita' generica resta. */
  @Test
  fun laCopertinaNascostaTogliLaNotificaSoloInTitoloEFoto() = runTest {
    val (titled, id) = posted(LockScreenMode.TITLE_AND_PHOTO)
    val cover = titled.publisher.current!!.content.image!!
    assertThat(cover).isIn(membersOf(titled, id))
    titled.hide(setOf(cover))
    assertThat(titled.revocation.revalidate()).isTrue()
    assertThat(titled.publisher.current).isNull()
    assertThat(titled.events.revoked).isEqualTo(1)

    val (generic, genericId) = posted(LockScreenMode.GENERIC)
    generic.hide(setOf(membersOf(generic, genericId).first()))
    assertThat(generic.revocation.revalidate()).isFalse()
    assertThat(generic.publisher.current).isNotNull()
  }

  /** Un nascosto che non e' la copertina non tocca la notifica (il ricordo ha ancora abbastanza elementi). */
  @Test
  fun unNascostoQualunqueNonTogliLaNotifica() = runTest {
    val (rig, id) = posted(LockScreenMode.TITLE_AND_PHOTO)
    val cover = rig.publisher.current!!.content.image!!
    rig.hide(setOf(membersOf(rig, id).first { it != cover }))
    assertThat(rig.revocation.revalidate()).isFalse()
    assertThat(rig.publisher.current).isNotNull()
  }

  /** R17: un guasto della lettura dei nascosti **toglie** la notifica con la miniatura (fallire chiusi) e non e' un crollo. */
  @Test
  fun unGuastoTogliLaNotificaConLaMiniatura() = runTest {
    var broken = false
    val flaky: (FakeVisibility) -> MemoryVisibility = { fake ->
      object : MemoryVisibility {
        override val snapshot: StateFlow<dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot?> get() = fake.snapshot

        override suspend fun visibleOrThrow(ids: Collection<MediaId>): Set<MediaId> = if (broken) throw IOException("lettura fallita") else fake.visible(ids)

        override suspend fun hiddenOrThrow(ids: Collection<MediaId>): Set<MediaId> = if (broken) throw IOException("lettura fallita") else fake.hiddenAmong(ids)
      }
    }
    val (rig, _) = posted(LockScreenMode.TITLE_AND_PHOTO, visibility = flaky)
    broken = true
    assertThat(rig.revocation.revalidate()).isTrue()
    assertThat(rig.publisher.current).isNull()
  }

  /** `watch`: un "Non mostrare" cambia lo scaffale e la notifica sparisce da sola, senza che nessuno chiami `revalidate`. */
  @Test
  fun watchSegueLoScaffale() = runTest {
    val (rig, id) = posted(LockScreenMode.GENERIC)
    val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    try {
      rig.revocation.watch(scope)
      assertThat(rig.publisher.current).isNotNull()
      rig.engine.engine.dismiss(id)
      assertThat(rig.publisher.current).isNull()
      assertThat(rig.events.revoked).isEqualTo(1)
    } finally {
      scope.cancel()
    }
  }
}
