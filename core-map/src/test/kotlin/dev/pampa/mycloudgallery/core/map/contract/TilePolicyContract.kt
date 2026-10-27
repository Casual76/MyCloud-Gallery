package dev.pampa.mycloudgallery.core.map.contract

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.map.noop.RefuseAllRequests
import dev.pampa.mycloudgallery.core.map.testing.ReferenceTilePolicy
import java.util.Random
import org.junit.Test

/**
 * La suite di contratto del cancello di rete della mappa (Fase 9, P0; `docs/fase9-progetto.md` D22-D24, D26, §2.8, §5.5, E-MAP1-E-MAP4, E-R3;
 * risposta Q1 (c)). Gira sul riferimento (`ReferenceTilePolicy`) e, da P1, su `TilePolicy` (con [iterations] = 100.000 nel suo test). La regola
 * che conta e' una: **nessuna richiesta esce fuori dalle regole**. Le prove a casi ne fissano anche i motivi.
 */
abstract class TilePolicyContract {

  protected abstract fun gate(): MapRequestGate

  /** Quante richieste casuali nella prova di proprieta' (P1: 100.000). */
  protected open val iterations: Int = 20_000

  private fun tile(z: Int, x: Int, y: Int) = MapRequestFacts("GET", "https", MapHost.TILES, 443, "/planet/v/$z/$x/$y.pbf", null)
  private val tileJson = MapRequestFacts("GET", "https", MapHost.TILES, 443, "/planet", null)

  private fun state(
    online: Boolean = true,
    network: MapNetwork = MapNetwork.WIFI,
    mobileAllowed: Boolean = false,
    covered: (TileKey) -> Boolean = { false },
    version: String? = "20261020_001001_pt",
    download: RegionDownloadScope? = null,
  ) = MapNetState(online, network, mobileAllowed, covered, version, download)

  private fun decide(req: MapRequestFacts, s: MapNetState = state()) = gate().decide(req, s)

  @Test
  fun unaTesseraAmmessaEsceSullaVersioneCorrente() {
    assertThat(decide(tile(14, 8_703, 5_949))).isEqualTo(MapVerdict.Allow("/planet/20261020_001001_pt/14/8703/5949.pbf"))
    assertThat(decide(tileJson)).isEqualTo(MapVerdict.Allow("/planet"))
    assertThat(decide(tile(0, 0, 0))).isEqualTo(MapVerdict.Allow("/planet/20261020_001001_pt/0/0/0.pbf"))
  }

  /** E-MAP1: a mappa spenta niente, nemmeno il TileJSON. */
  @Test
  fun aMappaSpentaNiente() {
    assertThat(decide(tile(5, 1, 1), state(online = false))).isEqualTo(MapVerdict.Refuse(RefuseReason.OFF))
    assertThat(decide(tileJson, state(online = false))).isEqualTo(MapVerdict.Refuse(RefuseReason.OFF))
  }

  /** E-MAP2, E-R3, E-R13: dati mobili con "Solo sul Wi-Fi", roaming, nessuna rete: niente. */
  @Test
  fun laReteDecide() {
    assertThat(decide(tile(5, 1, 1), state(network = MapNetwork.MOBILE))).isEqualTo(MapVerdict.Refuse(RefuseReason.NETWORK))
    assertThat(decide(tile(5, 1, 1), state(network = MapNetwork.MOBILE, mobileAllowed = true))).isInstanceOf(MapVerdict.Allow::class.java)
    assertThat(decide(tile(5, 1, 1), state(network = MapNetwork.ROAMING, mobileAllowed = true))).isEqualTo(MapVerdict.Refuse(RefuseReason.NETWORK))
    assertThat(decide(tile(5, 1, 1), state(network = MapNetwork.NONE))).isEqualTo(MapVerdict.Refuse(RefuseReason.NETWORK))
  }

  /** D22: un solo host, solo GET HTTPS sulla 443, niente utente, niente frammento. */
  @Test
  fun unSoloHost() {
    val good = tile(5, 1, 1)
    for (bad in listOf(
      good.copy(method = "POST"), good.copy(method = "get"), good.copy(scheme = "http"), good.copy(host = "tiles.openfreemap.org."),
      good.copy(host = "TILES.openfreemap.org"), good.copy(host = "tiles.openfreemap.org.evil.example"), good.copy(host = "openfreemap.org"),
      good.copy(host = "xtiles.openfreemap.org"), good.copy(port = 8443), good.copy(port = 80), good.copy(userInfo = "a:b"), good.copy(fragment = ""),
    )) {
      assertWithMessage("$bad").that(decide(bad)).isEqualTo(MapVerdict.Refuse(RefuseReason.HOST))
    }
  }

  /** D22: solo il TileJSON e le tessere dell'URL stabile fino a z14, forma canonica, nessuna query. */
  @Test
  fun soloIPercorsiAmmessi() {
    val bad = listOf(
      "/planet/v/15/1/1.pbf", "/planet/v/14/16384/0.pbf", "/planet/v/2/4/0.pbf", "/planet/v/01/0/0.pbf", "/planet/v/1/00/0.pbf",
      "/planet/v/1/0/0.pbf/", "/planet/v/1/0/0.png", "/planet/20261020/1/0/0.pbf", "/planet/v/../1/0/0.pbf", "/planet/v/1/-1/0.pbf",
      "/styles/positron", "/fonts/Noto/0-255.pbf", "/", "", "/planet/", "/PLANET", "/planet/v/1/0/0.pbf%00", "/planet/v/99999999999/0/0.pbf",
    )
    for (path in bad) assertWithMessage(path).that(decide(tile(1, 0, 0).copy(path = path))).isEqualTo(MapVerdict.Refuse(RefuseReason.PATH))
    assertThat(decide(tile(1, 0, 0).copy(query = "key=x"))).isEqualTo(MapVerdict.Refuse(RefuseReason.PATH))
    assertThat(decide(tileJson.copy(query = "v=2"))).isEqualTo(MapVerdict.Refuse(RefuseReason.PATH))
  }

  /** D26: una tessera di una regione completa non si chiede mai online. */
  @Test
  fun leTessereCoperteNonEscono() {
    val covered = state(covered = { it.z == 14 && it.x == 8_703 })
    assertThat(decide(tile(14, 8_703, 5_949), covered)).isEqualTo(MapVerdict.Refuse(RefuseReason.COVERED))
    assertThat(decide(tile(14, 8_704, 5_949), covered)).isInstanceOf(MapVerdict.Allow::class.java)
  }

  /** D22, E-MAP13: senza una versione nota prima il TileJSON; una versione ostile non entra in un percorso. */
  @Test
  fun laVersioneDelServer() {
    assertThat(decide(tile(3, 1, 1), state(version = null))).isEqualTo(MapVerdict.VersionFirst)
    for (hostile in listOf("../x", "a/b", "", "..", ".", "a b", "x".repeat(65), "v?x=1", "v#x", "ü")) {
      assertWithMessage(hostile).that(decide(tile(3, 1, 1), state(version = hostile))).isEqualTo(MapVerdict.VersionFirst)
    }
  }

  /** D26: con la conferma di uno scaricamento le tessere della regione passano anche a mappa spenta e se coperte; i dati mobili solo confermati. */
  @Test
  fun loScaricamentoConfermato() {
    val inRegion: (TileKey) -> Boolean = { it.z <= 14 && it.x % 2 == 0 }
    val wifi = state(online = false, covered = { true }, download = RegionDownloadScope(inRegion, mobileConfirmed = false))
    assertThat(decide(tile(10, 2, 3), wifi)).isInstanceOf(MapVerdict.Allow::class.java)
    assertThat(decide(tile(10, 3, 3), wifi)).isEqualTo(MapVerdict.Refuse(RefuseReason.OFF))
    assertThat(decide(tileJson, wifi)).isEqualTo(MapVerdict.Allow("/planet"))
    val mobile = state(online = false, network = MapNetwork.MOBILE, download = RegionDownloadScope(inRegion, mobileConfirmed = false))
    assertThat(decide(tile(10, 2, 3), mobile)).isEqualTo(MapVerdict.Refuse(RefuseReason.NETWORK))
    val confirmed = state(online = false, network = MapNetwork.MOBILE, download = RegionDownloadScope(inRegion, mobileConfirmed = true))
    assertThat(decide(tile(10, 2, 3), confirmed)).isInstanceOf(MapVerdict.Allow::class.java)
    val roaming = state(network = MapNetwork.ROAMING, download = RegionDownloadScope(inRegion, mobileConfirmed = true))
    assertThat(decide(tile(10, 2, 3), roaming)).isEqualTo(MapVerdict.Refuse(RefuseReason.NETWORK))
  }

  /** La prova che conta (§5.5): richieste e stati a caso, ostili; ogni `Allow` rispetta **tutte** le regole. Mai un'eccezione. */
  @Test
  fun proprietaNessunaRichiestaEsceFuoriDalleRegole() {
    val random = Random(17)
    val hosts = listOf(MapHost.TILES, "tiles.openfreemap.org.", "TILES.OPENFREEMAP.ORG", "tiles.openfreemap.org.example", "openfreemap.org", "127.0.0.1", "")
    val versions = listOf("20261020_001001_pt", null, "../..", "v1", "a/b", "x".repeat(70))
    var allowed = 0
    repeat(iterations) {
      val z = random.nextInt(18) - 1
      val side = if (z in 0..30) 1L shl z else 1L
      val x = if (random.nextInt(5) == 0) side + random.nextInt(3) else (random.nextLong() and Long.MAX_VALUE) % side
      val y = if (random.nextInt(5) == 0) -1L else (random.nextLong() and Long.MAX_VALUE) % side
      val path = when (random.nextInt(8)) {
        0 -> "/planet"
        1 -> "/planet/v/$z/$x/$y.pbf/../../x"
        2 -> "/planet/v/0$z/$x/$y.pbf"
        else -> "/planet/v/$z/$x/$y.pbf"
      }
      val req = MapRequestFacts(
        method = if (random.nextInt(10) == 0) "HEAD" else "GET",
        scheme = if (random.nextInt(10) == 0) "http" else "https",
        host = if (random.nextInt(4) == 0) hosts[random.nextInt(hosts.size)] else MapHost.TILES,
        port = if (random.nextInt(10) == 0) 8443 else 443,
        path = path,
        query = if (random.nextInt(10) == 0) "x=1" else null,
        userInfo = if (random.nextInt(20) == 0) "u" else null,
        fragment = if (random.nextInt(20) == 0) "f" else null,
      )
      val coveredSet = random.nextInt(3) == 0
      val scope = if (random.nextInt(6) == 0) RegionDownloadScope({ it.x % 2 == 0 }, random.nextBoolean()) else null
      val s = MapNetState(
        online = random.nextBoolean(),
        network = MapNetwork.entries[random.nextInt(MapNetwork.entries.size)],
        mobileAllowed = random.nextBoolean(),
        covered = { coveredSet && it.y % 2 == 0 },
        currentVersion = versions[random.nextInt(versions.size)],
        download = scope,
      )
      val verdict = gate().decide(req, s)
      if (verdict is MapVerdict.Allow) {
        allowed++
        assertWithMessage("$req").that(req.method).isEqualTo("GET")
        assertThat(req.scheme).isEqualTo("https")
        assertThat(req.host).isEqualTo(MapHost.TILES)
        assertThat(req.port).isEqualTo(443)
        assertThat(req.query.isNullOrEmpty() && req.userInfo.isNullOrEmpty() && req.fragment == null).isTrue()
        assertThat(s.network == MapNetwork.ROAMING || s.network == MapNetwork.NONE).isFalse()
        if (req.path == MapHost.TILEJSON_PATH) {
          assertThat(verdict.path).isEqualTo("/planet")
          assertThat(s.online || s.download != null).isTrue()
          if (s.network == MapNetwork.MOBILE) assertThat(if (s.download != null) s.download!!.mobileConfirmed || s.mobileAllowed && s.online else s.mobileAllowed).isTrue()
        } else {
          val t = ReferenceTilePolicy.parseTile(req.path)
          assertWithMessage(req.path).that(t).isNotNull()
          t!!
          assertThat(t.z).isAtMost(MapHost.MAX_ZOOM)
          val inScope = s.download?.includes?.invoke(t) == true
          assertThat(s.online || inScope).isTrue()
          if (!inScope) assertThat(s.covered(t)).isFalse()
          if (s.network == MapNetwork.MOBILE) assertThat(if (inScope) s.download!!.mobileConfirmed else s.mobileAllowed).isTrue()
          assertThat(MapHost.VERSION.matches(s.currentVersion ?: "")).isTrue()
          assertThat(verdict.path).isEqualTo(MapHost.versionedPath(s.currentVersion!!, t))
        }
      }
    }
    assertThat(allowed).isGreaterThan(iterations / 100)
  }

  /** Il null-object della release rifiuta tutto (finche' P1 non scrive `TilePolicy`). */
  @Test
  fun ilNullObjectRifiutaTutto() {
    assertThat(RefuseAllRequests.decide(tile(1, 0, 0), state())).isEqualTo(MapVerdict.Refuse(RefuseReason.OFF))
    assertThat(RefuseAllRequests.decide(tileJson, state())).isEqualTo(MapVerdict.Refuse(RefuseReason.OFF))
  }
}

/** La suite sul riferimento. */
class ReferenceTilePolicyContractTest : TilePolicyContract() {
  override fun gate(): MapRequestGate = ReferenceTilePolicy()
}
