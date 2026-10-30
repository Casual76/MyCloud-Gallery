package dev.pampa.mycloudgallery.core.map.policy

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.map.contract.MapHost
import dev.pampa.mycloudgallery.core.map.contract.MapNetState
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.MapRequestFacts
import dev.pampa.mycloudgallery.core.map.contract.MapRequestGate
import dev.pampa.mycloudgallery.core.map.contract.MapVerdict
import dev.pampa.mycloudgallery.core.map.contract.RefuseReason
import dev.pampa.mycloudgallery.core.map.contract.RegionDownloadScope
import dev.pampa.mycloudgallery.core.map.contract.TileKey
import dev.pampa.mycloudgallery.core.map.contract.TilePolicyContract
import dev.pampa.mycloudgallery.core.map.testing.ReferenceTilePolicy
import java.util.Random
import org.junit.Test

/** La suite di contratto sul cancello vero (Fase 9, P1, §4.4 "Fatto quando"): 100.000 richieste casuali nella proprieta' della suite. */
class TilePolicyContractTest : TilePolicyContract() {
  override fun gate(): MapRequestGate = TilePolicy

  override val iterations: Int = 100_000
}

/**
 * Le proprieta' di P1 oltre la suite (`docs/fase9-progetto.md` §4.4 riga P1, §5.5): 100.000 richieste ostili (host simili, porte, schemi, percorsi
 * ostili, `z` oltre 14, `x`/`y` fuori limite, indirizzi di redirect, mappa spenta, roaming, dati mobili, tessere coperte, scaricamenti) controllate
 * da un **oracolo scritto a parte** (le regole del §2.8 rilette da zero, senza le funzioni del cancello): nessuna richiesta lasciata passare fuori
 * dalle regole. In piu' il confronto con il riferimento di P0 su ogni richiesta e i casi che una regola sbagliata farebbe passare.
 */
class TilePolicyTest {

  private val version = "20261022_001001_pt"

  private fun state(
    online: Boolean = true,
    network: MapNetwork = MapNetwork.WIFI,
    mobileAllowed: Boolean = false,
    covered: (TileKey) -> Boolean = { false },
    currentVersion: String? = version,
    download: RegionDownloadScope? = null,
  ) = MapNetState(online, network, mobileAllowed, covered, currentVersion, download)

  private fun tile(path: String) = MapRequestFacts("GET", "https", MapHost.TILES, 443, path, null)

  @Test
  fun proprietaCentomilaRichiesteOstiliControllateDaUnOracoloAParte() {
    val random = Random(20261030)
    var allowed = 0
    var refused = 0
    repeat(100_000) {
      val req = HostileRequests.next(random)
      val s = HostileRequests.state(random)
      when (val verdict = TilePolicy.decide(req, s)) {
        is MapVerdict.Allow -> {
          allowed++
          val expected = Oracle.allowedPath(req, s)
          assertWithMessage("lasciata passare fuori dalle regole: ${HostileRequests.describe(req, s)}").that(expected).isNotNull()
          assertWithMessage(HostileRequests.describe(req, s)).that(verdict.path).isEqualTo(expected)
        }
        MapVerdict.VersionFirst -> assertWithMessage(HostileRequests.describe(req, s)).that(Oracle.wouldNeedVersion(req, s)).isTrue()
        is MapVerdict.Refuse -> {
          refused++
          assertWithMessage("rifiutata ma ammessa dalle regole: ${HostileRequests.describe(req, s)}").that(Oracle.allowedPath(req, s)).isNull()
        }
      }
    }
    assertThat(allowed).isGreaterThan(1_000)
    assertThat(refused).isGreaterThan(50_000)
  }

  @Test
  fun coincideConIlRiferimentoSuOgniRichiesta() {
    val random = Random(7)
    val reference = ReferenceTilePolicy()
    repeat(100_000) {
      val req = HostileRequests.next(random)
      val s = HostileRequests.state(random)
      assertWithMessage(HostileRequests.describe(req, s)).that(TilePolicy.decide(req, s)).isEqualTo(reference.decide(req, s))
    }
  }

  /** I percorsi che un'espressione regolare o un `toInt()` troppo buoni lascerebbero passare. */
  @Test
  fun percorsiQuasiGiusti() {
    val almost = listOf(
      "/planet/v/1/0/0.pbf?", "/planet/v/1/+0/0.pbf", "/planet/v/1/0/ 0.pbf", "/planet/v/1/0/0 .pbf", "/planet/v/١/0/0.pbf",
      "/planet/v/1/０/0.pbf", "/planet/v/1/0/0.PBF", "/planet//v/1/0/0.pbf", "/planet/v//1/0/0.pbf", "/planet/v/1/0/0/0.pbf",
      "/planet/v/1/0.pbf", "/planet/v/14/16383/16384.pbf", "/planet/v/14/16384/1.pbf", "/planet/v/15/0/0.pbf", "/planet/v/100/0/0.pbf",
      "/planet/v/1/0x1/0.pbf", "/planet/v/1/1e0/0.pbf", "/planet/v/%31/0/0.pbf", "/planet/v/1/0/0.pbf%2F..", "/planet/v/1/./0/0.pbf",
      "/Planet/v/1/0/0.pbf", "/planet/V/1/0/0.pbf", "/planet/latest/1/0/0.pbf", "/planet/$version/1/0/0.pbf", "/planet/latest",
      "/planet/$version", "/planet.json", "/planet/", "/planet/v/", "/planet/v/1/0/", "//planet", " /planet", "/planet ",
    )
    for (path in almost) assertWithMessage(path).that(TilePolicy.decide(tile(path), state())).isEqualTo(MapVerdict.Refuse(RefuseReason.PATH))
    assertThat(TilePolicy.decide(tile("/planet/v/14/16383/16383.pbf"), state())).isEqualTo(MapVerdict.Allow("/planet/$version/14/16383/16383.pbf"))
    assertThat(TilePolicy.stableTile("/planet/v/14/16383/0.pbf")).isEqualTo(TileKey(14, 16383, 0))
    assertThat(TilePolicy.stableTile("/planet/v/0/0/0.pbf")).isEqualTo(TileKey(0, 0, 0))
  }

  /** Gli host che somigliano a quello vero (punto finale, maiuscole, sottodomini, IP, caratteri simili, porte, schemi, metodi). */
  @Test
  fun hostSimiliPorteSchemiEMetodi() {
    val good = tile("/planet/v/5/1/1.pbf")
    val bad = listOf(
      good.copy(host = "tiles.openfreemap.org."), good.copy(host = "Tiles.openfreemap.org"), good.copy(host = "tiles.openfreemap.org.example"),
      good.copy(host = "tiles-openfreemap.org"), good.copy(host = "tiles.openfreemap.or"), good.copy(host = "tiIes.openfreemap.org"),
      good.copy(host = "tiles.openfreemap.org​"), good.copy(host = "xn--tiles-openfreemap-org"), good.copy(host = "104.21.0.1"),
      good.copy(host = "[::1]"), good.copy(host = "localhost"), good.copy(host = " tiles.openfreemap.org"), good.copy(host = ""),
      good.copy(port = 0), good.copy(port = 444), good.copy(port = -1), good.copy(port = 65_979),
      good.copy(scheme = "HTTPS"), good.copy(scheme = "wss"), good.copy(scheme = "ftp"), good.copy(scheme = ""),
      good.copy(method = "PUT"), good.copy(method = "OPTIONS"), good.copy(method = "GET "), good.copy(method = "CONNECT"),
      good.copy(userInfo = ":"), good.copy(fragment = "x"),
    )
    for (req in bad) assertWithMessage("${req.host}|${req.port}|${req.scheme}|${req.method}").that(TilePolicy.decide(req, state())).isEqualTo(MapVerdict.Refuse(RefuseReason.HOST))
    assertThat(TilePolicy.decide(good.copy(userInfo = ""), state())).isInstanceOf(MapVerdict.Allow::class.java)
  }

  /** E-MAP1, E-MAP2, E-R3: ogni stato che vieta, per ogni tessera e per il TileJSON. */
  @Test
  fun gliStatiCheVietano() {
    val requests = listOf(tile("/planet"), tile("/planet/v/0/0/0.pbf"), tile("/planet/v/14/8703/5949.pbf"))
    for (req in requests) {
      assertThat(TilePolicy.decide(req, state(online = false))).isEqualTo(MapVerdict.Refuse(RefuseReason.OFF))
      assertThat(TilePolicy.decide(req, state(online = false, network = MapNetwork.ROAMING))).isEqualTo(MapVerdict.Refuse(RefuseReason.OFF))
      for (net in listOf(MapNetwork.ROAMING, MapNetwork.NONE)) {
        assertThat(TilePolicy.decide(req, state(network = net, mobileAllowed = true))).isEqualTo(MapVerdict.Refuse(RefuseReason.NETWORK))
      }
      assertThat(TilePolicy.decide(req, state(network = MapNetwork.MOBILE))).isEqualTo(MapVerdict.Refuse(RefuseReason.NETWORK))
    }
    // Una regione completa copre ogni livello che ha: nessuna sua tessera esce, nemmeno con la versione da scoprire.
    val covered = state(covered = { true }, currentVersion = null)
    assertThat(TilePolicy.decide(tile("/planet/v/9/271/186.pbf"), covered)).isEqualTo(MapVerdict.Refuse(RefuseReason.COVERED))
    assertThat(TilePolicy.decide(tile("/planet"), covered)).isEqualTo(MapVerdict.Allow("/planet"))
  }

  /** D26: lo scaricamento confermato apre solo le sue tessere; fuori dalla regione valgono le regole di sempre. */
  @Test
  fun loScaricamentoApreSoloLaSuaRegione() {
    val scope = RegionDownloadScope({ it.z >= 6 && it.x in 34..35 }, mobileConfirmed = false)
    val off = state(online = false, download = scope)
    assertThat(TilePolicy.decide(tile("/planet/v/6/34/23.pbf"), off)).isEqualTo(MapVerdict.Allow("/planet/$version/6/34/23.pbf"))
    assertThat(TilePolicy.decide(tile("/planet/v/6/36/23.pbf"), off)).isEqualTo(MapVerdict.Refuse(RefuseReason.OFF))
    val onCovered = state(covered = { true }, download = scope)
    assertThat(TilePolicy.decide(tile("/planet/v/6/35/23.pbf"), onCovered)).isInstanceOf(MapVerdict.Allow::class.java)
    assertThat(TilePolicy.decide(tile("/planet/v/6/33/23.pbf"), onCovered)).isEqualTo(MapVerdict.Refuse(RefuseReason.COVERED))
    assertThat(TilePolicy.decide(tile("/planet/v/6/34/23.pbf"), state(network = MapNetwork.ROAMING, download = scope))).isEqualTo(MapVerdict.Refuse(RefuseReason.NETWORK))
  }

  /** La funzione e' pura: stessa entrata, stessa uscita, anche chiamata da molti thread insieme. */
  @Test
  fun eUnaFunzionePura() {
    val req = tile("/planet/v/12/2175/1487.pbf")
    val s = state()
    val first = TilePolicy.decide(req, s)
    val different = java.util.concurrent.atomic.AtomicInteger()
    val threads = (1..8).map { Thread { repeat(10_000) { if (TilePolicy.decide(req, s) != first) different.incrementAndGet() } }.also { it.start() } }
    threads.forEach { it.join() }
    assertThat(different.get()).isEqualTo(0)
    assertThat(first).isEqualTo(MapVerdict.Allow("/planet/$version/12/2175/1487.pbf"))
  }
}
