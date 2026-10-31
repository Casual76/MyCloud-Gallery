package dev.pampa.mycloudgallery.core.models.security

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.models.contract.EnsureResult
import dev.pampa.mycloudgallery.core.models.contract.ModelSource
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.models.contract.PinnedUrl
import dev.pampa.mycloudgallery.core.models.source.CatalogExtraModelSources
import dev.pampa.mycloudgallery.core.models.support.ModelRig
import dev.pampa.mycloudgallery.core.models.support.TestModels
import dev.pampa.mycloudgallery.core.models.support.assertModelsDirInvariant
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Fase 10 P11, voce **S7a** della revisione finale (docs/fase10-progetto.md §2.11; E-A2, E-S16, C451): "un modello manomesso viene
 * rifiutato". La prova pratica come sulla famiglia di prova, sulla JVM: sullo **scaffale dei modelli** del NAS una copia con il nome giusto e
 * la dimensione giusta ma **byte diversi** (un solo byte, in quattro posti, e un file intero diverso), e un **indirizzo aggiuntivo del
 * manifest** che porta a un file diverso. Ogni sorgente ostile e' rifiutata e contata (`AI_MODEL_MISMATCH`), il suo parziale tolto, si passa
 * alla sorgente dopo; nessun file non verificato entra in `models/`. Le mutazioni del banco delle interruzioni sono `ModelChaosMutationTest`.
 */
class S7aTamperedModelTest {

  @Test
  fun copieManomesseSulloScaffaleEIndirizziVersoFileDiversiNonEntranoMai() = runBlocking {
    val bytes = TestModels.bytes()
    val testSpec = TestModels.spec(bytes)
    val extra = listOf("https://mirror.huggingface.co/s7a/altro", "https://cdn-lfs.hf.co/s7a/troncato")
    var tampered = 0
    var cases = 0
    for (position in listOf(0, bytes.size / 3, bytes.size / 2, bytes.size - 1)) {
      cases++
      val rig = ModelRig(bytes = bytes, spec = testSpec, extraSources = CatalogExtraModelSources({ _, _ -> extra }, listOf(testSpec)))
      // Lo scaffale: nome e dimensione giusti, un byte diverso. L'indirizzo fissato non risponde. Gli aggiuntivi: un file diverso intero e
      // uno troncato. Nessuna sorgente buona: niente deve entrare.
      rig.nasCopy(content = TestModels.flipped(bytes, position))
      rig.fake.serve(ModelSource.Https(PinnedUrl.of(extra[0])!!), TestModels.bytes(seed = 99))
      rig.fake.serve(ModelSource.Https(PinnedUrl.of(extra[1])!!), bytes.copyOf(bytes.size - 1))
      val result = rig.manager.ensure(testSpec.id, ModelTrigger.USER_NOW, 10.minutes)
      assertWithMessage("byte cambiato in $position").that(result).isInstanceOf(EnsureResult.Failed::class.java)
      assertThat(rig.finalFile.exists()).isFalse()
      assertThat(rig.stateOfModel()).isNotInstanceOf(ModelState.Ready::class.java)
      assertThat(File(rig.dir, "partial").listFiles().orEmpty().filter { it.length() > 0 }).isEmpty()
      assertWithMessage("scaffale e indirizzi aggiuntivi rifiutati e contati").that(rig.counters.mismatch).isAtLeast(2)
      tampered += rig.counters.mismatch.toInt()
      assertModelsDirInvariant(rig.dir, listOf(testSpec))

      // Poi una sorgente buona (un indirizzo aggiuntivo che ora porta il file giusto): solo quella entra, con l'impronta del catalogo.
      rig.fake.serve(ModelSource.Https(PinnedUrl.of(extra[1])!!), bytes)
      assertThat(rig.manager.ensure(testSpec.id, ModelTrigger.USER_NOW, 10.minutes)).isEqualTo(EnsureResult.Ready)
      assertThat(rig.finalFile.readBytes()).isEqualTo(bytes)
      assertModelsDirInvariant(rig.dir, listOf(testSpec))
    }
    println("MCG-SEC S7a casi=$cases sorgenti_manomesse_rifiutate=$tampered file_non_verificati_accettati=0")
  }
}
