package dev.pampa.mycloudgallery.core.ai.compute

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.ai.contract.AiScope
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import kotlin.random.Random
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * D14, §6.1: **mai un'altra area privata**, come proprieta'. Famiglie casuali (2-4 account, 1-3 dispositivi ciascuno, ruoli e
 * lettura del testo a caso, media in ogni area, alcuni contenuti uguali in piu' aree); ogni dispositivo esegue tutti i suoi ambiti e
 * in piu' prova esplicitamente le aree private degli altri. Proprieta':
 * 1. nessun dispositivo tocca (NAS, catalogo, pubblicatore, scaricamento) un'area privata che non e' del suo account;
 * 2. nessuna voce di un dispositivo sta nell'indice di un'area privata d'altri, e nessuna voce fuori posto (M3);
 * 3. il lavoro arriva in fondo: ogni media di un'area con un indicizzatore ha il suo vettore pubblicato nella share.
 */
class PrivacyPropertyTest {

  @Test
  fun maiUnAltraAreaPrivata_eIlLavoroArrivaInFondo() = runTest {
    repeat(120) { seed -> family(Random(seed)) }
  }

  private suspend fun family(random: Random) {
    val nas = SimNas(unitSize = 1 + random.nextInt(6))
    val common = "test-comune"
    nas.share(common)
    val accounts = (0 until 2 + random.nextInt(3)).map { "acc$it" }
    val privates = accounts.associateWith { "test-privata-$it".also { s -> nas.share(s, it) } }
    val shared = nas.newKey()
    for (share in privates.values + common) {
      repeat(random.nextInt(8)) {
        nas.add(share, month = "202${random.nextInt(4)}-0${1 + random.nextInt(9)}", kind = if (random.nextInt(5) == 0) MediaKind.VIDEO else MediaKind.PHOTO)
      }
      if (random.nextBoolean()) nas.add(share, key = shared, concepts = "uguale")
    }
    val devices = accounts.flatMap { account ->
      (0 until 1 + random.nextInt(3)).map { i ->
        SimDevice(nas, "$account-dev$i", account, listOf(privates.getValue(account), common), indexer = random.nextInt(3) != 0, textReading = random.nextBoolean())
      }
    }
    for (device in devices.shuffled(random)) {
      device.engine.runAll(Duration.INFINITE)
      for (other in privates.filterKeys { it != device.account }.values) device.engine.run(AiScope.Share(other), Duration.INFINITE)
    }

    for (device in devices) {
      val mine = setOf(nas.key(privates.getValue(device.account)), nas.key(common))
      assertWithMessage("aree toccate da ${device.id}").that(nas.touched[device.id].orEmpty() - mine).isEmpty()
      for ((account, share) in privates) {
        if (account == device.account) continue
        assertWithMessage("voci di ${device.id} in un'area privata d'altri").that(nas.of(share).heads[device.id]).isNull()
      }
      assertWithMessage("voci fuori posto (M3)").that(device.publisher.foreign).isEqualTo(0)
    }
    for ((account, share) in privates.entries.map { it.key to it.value } + (null to common)) {
      val covered = devices.any { it.roles.indexer.value && (account == null || it.account == account) }
      if (!covered) {
        assertWithMessage("area senza indicizzatori calcolata").that(nas.of(share).heads).isEmpty()
        continue
      }
      for (key in nas.of(share).media.keys) {
        assertWithMessage("media senza vettore in un'area coperta").that(nas.doneIn(share, key, 1, AiClass.VEC)).isTrue()
      }
    }
  }
}
