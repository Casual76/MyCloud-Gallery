package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.contract.SearchOrderSetting
import dev.pampa.mycloudgallery.core.personal.settings.PersonalMemories
import dev.pampa.mycloudgallery.core.personal.settings.SyncedFullHashSettings
import dev.pampa.mycloudgallery.core.personal.settings.SyncedMemoriesSettings
import dev.pampa.mycloudgallery.core.personal.settings.SyncedMotionSettings
import dev.pampa.mycloudgallery.core.personal.settings.SyncedPersonalSettings
import dev.pampa.mycloudgallery.core.personal.settings.SyncedSearchSettings
import dev.pampa.mycloudgallery.core.personal.store.LedgerPersonalStore
import dev.pampa.mycloudgallery.core.personal.store.SimilarCopiesChoices
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryPersonalLedger
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * K3b, SOL-10 ("ogni altra impostazione che passa dal registro personale"): con la memoria piena `FilePersonalLedger.save` lancia `IOException`, e una impostazione scritta da un
 * `viewModelScope` chiudeva l'app. Ora la scrittura non lancia, **l'impostazione resta com'era** (lo store e' tutto o niente: `effectiveEntries` ha lo stato di prima, e l'interruttore
 * torna dov'era), e a memoria libera la stessa scelta riesce. Come `QuietWrites` per le preferenze su DataStore (RG-B).
 */
class SettingsFullStorageTest {

  private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
  private val ledger = InMemoryPersonalLedger()
  private val store = LedgerPersonalStore(Base32.encode(ByteArray(16) { 1 }), { NAS_NOW }, ledger)

  @After
  fun close() = scope.cancel()

  @Test
  fun lAnimazioneDelleFotoInMovimentoRestaAccesaENonFaCadereNiente() = runTest {
    val motion = SyncedMotionSettings(store, scope)
    ledger.full = true
    motion.setMotionAnimation(false)
    assertThat(motion.motionAnimation.value).isTrue()
    ledger.full = false
    motion.setMotionAnimation(false)
    assertThat(motion.motionAnimation.value).isFalse()
  }

  @Test
  fun leAltreImpostazioniSemplicidiConvergonoAlloStessoModo() = runTest {
    val fullHash = SyncedFullHashSettings(store, scope)
    val search = SyncedSearchSettings(store, scope)
    val personal = SyncedPersonalSettings(store, scope)
    val memories = SyncedMemoriesSettings(store, scope)
    ledger.full = true

    fullHash.setFullHashNightly(true)
    search.setSearchOrder(SearchOrderSetting.RELEVANT)
    personal.setShowAlbumCovers(true)
    personal.setPrivateAreaExclusions(setOf(SharePath.of("Foto", "Vecchie")))
    memories.setPeopleMemories(false)

    assertThat(fullHash.fullHashNightly.value).isFalse()
    assertThat(search.searchOrder.value).isEqualTo(SearchOrderSetting.RECENT)
    assertThat(personal.showAlbumCovers.value).isFalse()
    assertThat(personal.privateAreaExclusions.value).isEmpty()
    assertThat(store.effectiveEntries.value).isEmpty()

    // A memoria libera le stesse scelte riescono.
    ledger.full = false
    fullHash.setFullHashNightly(true)
    search.setSearchOrder(SearchOrderSetting.RELEVANT)
    personal.setShowAlbumCovers(true)
    personal.setPrivateAreaExclusions(setOf(SharePath.of("Foto", "Vecchie")))
    assertThat(fullHash.fullHashNightly.value).isTrue()
    assertThat(search.searchOrder.value).isEqualTo(SearchOrderSetting.RELEVANT)
    assertThat(personal.showAlbumCovers.value).isTrue()
    assertThat(personal.privateAreaExclusions.value).containsExactly(SharePath.of("Foto", "Vecchie"))
  }

  @Test
  fun leCartelleEscluseSiSalvanoTutteONessuna() = runTest {
    val personal = SyncedPersonalSettings(store, scope)
    val one = SharePath.of("Foto", "Uno")
    val two = SharePath.of("Foto", "Due")
    personal.setPrivateAreaExclusions(setOf(one))
    ledger.full = true
    personal.setPrivateAreaExclusions(setOf(two, SharePath.of("Foto", "Tre")))
    // Non ne resta una a meta': c'e' ancora l'elenco di prima, intero.
    assertThat(personal.privateAreaExclusions.value).containsExactly(one)
    assertThat(ledger.snapshot.entries.keys.count { it.field.contains("exclu", ignoreCase = true) }).isEqualTo(1)
  }

  @Test
  fun iRicordiNonSiSegnanoNeSiAzzeranoAMetaConLaMemoriaPiena() = runTest {
    val memories = SyncedMemoriesSettings(store, scope)
    val seen = PersonalMemories(store)
    val id = Base32.encode(ByteArray(16) { 7 })
    ledger.full = true
    seen.markSeen(id, 20_261_109)
    seen.dismiss(id)
    assertThat(seen.seenDay(id)).isNull()
    assertThat(seen.isDismissed(id)).isFalse()
    assertThat(memories.dropAllPeopleChoices()).isEqualTo(0)
  }

  @Test
  fun leScelteSulleCopieSimiliNonSiScrivonoAMetaEnonAvvisanoChiSincronizza() = runTest {
    val a = MediaId.random()
    val b = MediaId.random()
    val c = MediaId.random()
    var saved = 0
    val choices = SimilarCopiesChoices(store, onSaved = { saved++ })
    choices.saveCover(a, listOf(a, b, c))
    assertThat(saved).isEqualTo(1)

    ledger.full = true
    choices.saveCover(b, listOf(a, b, c))
    choices.saveSeparation(a, listOf(b, c))
    // Nessuna scelta nuova e nessun "ho scritto qualcosa": la principale e' ancora `a`.
    assertThat(saved).isEqualTo(1)
    assertThat(choices.chosenCover(listOf(a, b, c))).isEqualTo(a)
    assertThat(choices.separatedPairs(listOf(a, b, c))).isEmpty()

    // A memoria libera: la principale cambia tutta insieme (la nuova accesa e la vecchia spenta) e chi sincronizza e' avvisato.
    ledger.full = false
    choices.saveCover(b, listOf(a, b, c))
    assertThat(saved).isEqualTo(2)
    assertThat(choices.chosenCover(listOf(a, b, c))).isEqualTo(b)
  }

  @Test
  fun laCancellazioneNonSiInghiotte() = runTest {
    val motion = SyncedMotionSettings(
      object : dev.pampa.mycloudgallery.core.personal.contract.PersonalStore by store {
        override suspend fun set(
          key: dev.pampa.mycloudgallery.core.personal.contract.PersonalKey,
          value: dev.pampa.mycloudgallery.core.personal.contract.PersonalValue,
          fingerprint: dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint?,
        ) = throw CancellationException("annullata")
      },
      scope,
    )
    var cancelled = false
    try {
      motion.setMotionAnimation(false)
    } catch (_: CancellationException) {
      cancelled = true
    }
    assertThat(cancelled).isTrue()
  }
}
