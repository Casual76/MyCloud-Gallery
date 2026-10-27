package dev.pampa.mycloudgallery.core.memories

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.LockScreenMode
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.memories.contract.MusicChoice
import dev.pampa.mycloudgallery.core.memories.contract.NotifyLedger
import dev.pampa.mycloudgallery.core.memories.contract.NotifyPrefs
import dev.pampa.mycloudgallery.core.memories.contract.NotifyTexts
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReason
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReport
import dev.pampa.mycloudgallery.core.memories.contract.ShelfEmptyReason
import dev.pampa.mycloudgallery.core.memories.contract.ShelfState
import dev.pampa.mycloudgallery.core.memories.contract.ShelfStoreContract
import dev.pampa.mycloudgallery.core.memories.noop.NeverNotify
import dev.pampa.mycloudgallery.core.memories.noop.NoMemories
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryClips
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryPeople
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryPixels
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryTrips
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryVectors
import dev.pampa.mycloudgallery.core.memories.noop.NoMusicLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Fase 9 (P0): i null-object rispondono "niente" e non scrivono niente; i ricordi di quel tipo tacciono, il resto funziona. */
class NullObjectsTest {

  @Test
  fun nessunRicordo() = runTest {
    assertThat(NoMemories.shelf.value.items).isEmpty()
    assertThat(NoMemories.shelf.value.state).isEqualTo(ShelfState.Empty(ShelfEmptyReason.NOT_ENOUGH_PHOTOS))
    assertThat(NoMemories.refresh(RefreshReason.NIGHTLY, 60.seconds)).isEqualTo(RefreshReport.NOTHING)
    assertThat(NoMemories.prune()).isEqualTo(RefreshReport.NOTHING)
    assertThat(NoMemories.memory(MemoryIds.of(MemoryKey.OnThisDay(LocalDate.of(2025, 1, 1)))).first()).isNull()
    assertThat(NoMemories.peopleMemories.value).isTrue()
  }

  @Test
  fun leParteNonDannoNiente() = runTest {
    val ids = listOf(MediaId.random())
    assertThat(NoMemoryTrips.past(LocalDate.of(2026, 1, 1), 5.seconds)).isEmpty()
    assertThat(NoMemoryPeople.available.value).isFalse()
    assertThat(NoMemoryPeople.named()).isEmpty()
    assertThat(NoMemoryPeople.peopleIn(ids)).isEmpty()
    assertThat(NoMemoryPeople.resolve(PersonId.random())).isNull()
    assertThat(NoMemoryVectors.vectors(ids)).isEmpty()
    assertThat(NoMemoryPixels.probe(ids, 1.seconds)).isEmpty()
    assertThat(NoMemoryClips.clip(ids.first())).isNull()
    assertThat(NoMusicLibrary.install("qualunque")).isFalse()
    assertThat(NoMusicLibrary.open(MusicChoice.None)).isNull()
  }

  @Test
  fun maiUnaNotifica() {
    val now = LocalDateTime.of(2026, 11, 3, 19, 30)
    assertThat(NeverNotify.nextSlot(now, NotifyPrefs())).isNull()
    assertThat(NeverNotify.choose(emptyList(), NotifyPrefs(), NotifyLedger(), now.toLocalDate(), openedToday = false)).isNull()
    val content = NeverNotify.content(ShelfStoreContract.sampleMemory(), LockScreenMode.TITLE_AND_PHOTO)
    assertThat(content.image).isNull()
    assertThat(content.title.key).isIn(NotifyTexts.GENERIC)
  }
}
