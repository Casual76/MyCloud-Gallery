package dev.pampa.mycloudgallery.core.faces.compute

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.AreaKind
import dev.pampa.mycloudgallery.core.faces.contract.FacesScope
import dev.pampa.mycloudgallery.core.faces.contract.FacesStop
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.model.ContentKey
import java.util.Locale
import java.util.Random
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * MAI L'AREA PRIVATA DI UN ALTRO ACCOUNT (D11, D14, D16, §4.4 P4 "proprieta'"), su 120 famiglie a caso: da due a quattro account, uno-tre
 * dispositivi ognuno (indicizzatori o no), qualcuno con la recinzione, aree private e area comune con media a caso. Ogni dispositivo riceve ambiti a
 * caso, validi e no (l'area privata di un altro, la propria come "comune", la comune come "privata", maiuscole, vuoti). Le proprieta':
 * 1. un ambito che non e' un'area del proprio account **non muove nessuna porta** (ne' catalogo, ne' ingressi, ne' motori, ne' NAS, ne' database);
 * 2. sul NAS niente di un account entra nella generazione di un altro, ne' una prenotazione, ne' un corpo, ne' una scrittura;
 * 3. ogni account analizza ogni suo media una volta sola per account (le prenotazioni dividono il lavoro, la replica lo porta agli altri dispositivi);
 * 4. con la recinzione niente sul NAS e ognuno fa per se'.
 */
class PrivacyPropertyTest {
  private val forever = SimWorld.FOREVER

  private class Family(val seed: Int) {
    val rnd = Random(seed.toLong() * 7_919 + 13)
    val w = SimWorld()
    val accounts = (1..(2 + rnd.nextInt(3))).map { w.nas.account("u$it") }
    val fenced = accounts.associateWith { rnd.nextInt(5) == 0 }
    val devices = ArrayList<SimDevice>()
    val keysOf = HashMap<String, MutableSet<ContentKey>>()
    val privateKeysOf = HashMap<String, MutableSet<ContentKey>>()

    init {
      for (acc in accounts) {
        val own = keysOf.getOrPut(acc.name) { LinkedHashSet() }
        val priv = privateKeysOf.getOrPut(acc.name) { LinkedHashSet() }
        repeat(2 + rnd.nextInt(6)) {
          val key = w.nas.add(acc.privateShare, faces = faces(), month = "2024-0${1 + rnd.nextInt(3)}")
          own += key
          priv += key
        }
        repeat(1 + rnd.nextInt(3)) {
          devices += w.device(acc, "${acc.name}-d$it", indexer = rnd.nextInt(5) != 0, here = if (fenced.getValue(acc)) HereState.LOCAL_ONLY else HereState.ACTIVE)
        }
      }
      repeat(rnd.nextInt(8)) {
        val key = w.nas.add(w.nas.commonShare, faces = faces(), month = "2024-0${1 + rnd.nextInt(3)}")
        accounts.forEach { keysOf.getValue(it.name) += key }
      }
    }

    fun faces() = (0..rnd.nextInt(3)).map { Faces.big(1 + rnd.nextInt(9), x = 0.05f + it * 0.3f) }

    fun accountOf(device: SimDevice) = device.account

    fun scopes(device: SimDevice): List<FacesScope> {
      val others = accounts.filter { it != device.account }
      return listOf(
        device.area(AreaKind.PRIVATE),
        device.area(AreaKind.COMMON),
        FacesScope.PhoneOnly,
        FacesScope.Area(device.account.privateShare.uppercase(Locale.ROOT), AreaKind.PRIVATE),
        FacesScope.Area(others.pick(rnd).privateShare, AreaKind.PRIVATE),
        FacesScope.Area(others.pick(rnd).privateShare, AreaKind.COMMON),
        FacesScope.Area(device.account.privateShare, AreaKind.COMMON),
        FacesScope.Area(w.nas.commonShare, AreaKind.PRIVATE),
        FacesScope.Area("", AreaKind.PRIVATE),
        FacesScope.Area("..", AreaKind.COMMON),
      )
    }

    fun valid(scope: FacesScope, device: SimDevice): Boolean = when (scope) {
      FacesScope.PhoneOnly -> true
      is FacesScope.Area -> when (scope.kind) {
        AreaKind.PRIVATE -> scope.share.lowercase(Locale.ROOT) == device.account.privateShare
        AreaKind.COMMON -> scope.share.lowercase(Locale.ROOT) == w.nas.commonShare
      }
    }
  }

  @Test
  fun ambitiDiAltriAccountNonMuovonoNiente_su120Famiglie() = runTest {
    for (seed in 1..120) {
      val f = Family(seed)
      // Fase 1: ambiti a caso; la replica (P3) prima di ogni tappa, come un giro di sincronizzazione.
      repeat(6) {
        for (device in f.devices) {
          val scope = f.scopes(device).pick(f.rnd)
          if (f.valid(scope, device)) {
            device.replicate()
            device.engine.run(scope, forever)
          } else {
            val before = device.activity()
            val foreign = device.engine.counters.foreignScopes.get()
            val result = device.engine.run(scope, forever)
            assertWithMessage("famiglia $seed: un ambito non suo ha mosso delle porte").that(device.activity()).isEqualTo(before)
            assertThat(device.engine.counters.foreignScopes.get()).isEqualTo(foreign + 1)
            assertThat(result.stop).isEqualTo(FacesStop.DONE)
            assertThat(result.analyzed + result.faces).isEqualTo(0)
          }
        }
      }
      // Fase 2: ogni dispositivo fa i suoi ambiti veri fino in fondo.
      repeat(2) {
        for (device in f.devices) {
          device.replicate()
          device.engine.runAll(forever)
        }
      }
      check(f, seed)
    }
  }

  private suspend fun check(f: Family, seed: Int) {
    val generations = f.accounts.associate { it.generation to it.name }
    // 2. Sul NAS niente passa da una generazione all'altra.
    for ((generation, bodies) in f.w.nas.published) {
      val account = generations.getValue(generation)
      for ((device, byKey) in bodies) {
        assertWithMessage("famiglia $seed: il dispositivo $device scrive nella generazione di $account").that(device).startsWith("$account-")
        assertThat(f.keysOf.getValue(account)).containsAtLeastElementsIn(byKey.keys)
      }
    }
    for ((resource, holder) in f.w.nas.leases) {
      assertThat(holder.first).startsWith("${generations.getValue(resource.first)}-")
    }
    for ((device, generation) in f.w.nas.writes) assertThat(device).startsWith("${generations.getValue(generation)}-")

    for (device in f.devices) {
      // Nessun dispositivo ha un corpo di un media che esiste solo nell'area privata di un altro account.
      val foreignPrivate = f.accounts.filter { it != device.account }.flatMap { f.privateKeysOf.getValue(it.name) }
      assertThat(device.rawArchive.winners(foreignPrivate)).isEmpty()
    }

    for (acc in f.accounts) {
      val mine = f.devices.filter { it.account == acc }
      val indexers = mine.filter { it.requirements.indexers.thisDevice }
      val keys = f.keysOf.getValue(acc.name)
      if (f.fenced.getValue(acc)) {
        // 4. Recinzione: niente sul NAS (ne' prenotazioni ne' corpi) e ognuno fa per se'.
        assertThat(f.w.nas.published[acc.generation].orEmpty()).isEmpty()
        assertThat(f.w.nas.leases.keys.filter { it.first == acc.generation }).isEmpty()
        for (device in indexers) {
          val done = device.archive.perKey
          assertThat(done.keys).containsExactlyElementsIn(keys)
          assertThat(done.values.toSet().ifEmpty { setOf(1) }).containsExactly(1)
        }
      } else if (indexers.isNotEmpty()) {
        // 3. Una volta per account: la somma dei salvataggi dei dispositivi dell'account e' uno per media.
        val total = HashMap<ContentKey, Int>()
        mine.forEach { d -> d.archive.perKey.forEach { (k, n) -> total[k] = (total[k] ?: 0) + n } }
        assertWithMessage("famiglia $seed: ${acc.name} non ha analizzato tutti i suoi media").that(total.keys).containsExactlyElementsIn(keys)
        assertWithMessage("famiglia $seed: un media e' stato analizzato piu' volte dallo stesso account").that(total.values.toSet().ifEmpty { setOf(1) }).containsExactly(1)
        // Tutti i dispositivi dell'account, indicizzatori o no, lo sanno (la replica).
        for (device in mine) {
          device.replicate()
          assertThat(device.analyzed()).containsAtLeastElementsIn(keys)
        }
      } else {
        // Nessun indicizzatore: nessuno calcola, niente sul NAS.
        assertThat(mine.all { it.archive.saves == 0 }).isTrue()
        assertThat(f.w.nas.published[acc.generation].orEmpty()).isEmpty()
      }
    }
  }
}

/** Un elemento a caso, con il generatore del test (deterministico per famiglia). */
private fun <T> List<T>.pick(rnd: Random): T = this[rnd.nextInt(size)]
