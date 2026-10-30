package dev.pampa.mycloudgallery.core.map.policy

import dev.pampa.mycloudgallery.core.map.contract.MapNetState
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.MapRequestFacts
import dev.pampa.mycloudgallery.core.map.contract.RegionDownloadScope
import dev.pampa.mycloudgallery.core.map.contract.TileKey
import java.util.Random

/**
 * Richieste e stati casuali e ostili per le proprieta' del cancello (P1, §5.5). Le richieste somigliano a quelle vere quanto basta perche' molte
 * passino (una prova che rifiuta tutto non prova niente) e sbagliano in tutti i modi che il §2.8 elenca: host simili, porte, schemi e metodi,
 * percorsi ostili, livelli oltre 14, `x` e `y` fuori dai limiti, query, utenti e frammenti, indirizzi di un redirect verso altri host.
 */
internal object HostileRequests {

  private const val HOST = "tiles.openfreemap.org"

  private val hosts = listOf(
    HOST, "tiles.openfreemap.org.", "TILES.OPENFREEMAP.ORG", "tiles.openfreemap.org.example", "openfreemap.org", "a.tiles.openfreemap.org",
    "tiles.openfreemap.com", "tiles.0penfreemap.org", "127.0.0.1", "[::1]", "", "tiles.openfreemap.org:443", "evil.example",
  )
  private val schemes = listOf("https", "http", "HTTPS", "wss", "file", "asset")
  private val methods = listOf("GET", "HEAD", "POST", "get", "OPTIONS")
  private val ports = listOf(443, 80, 8443, 444, 0, -1)
  private val versions = listOf("20261022_001001_pt", null, "v1", "latest", "../..", "a/b", "", "x".repeat(70), "20261022 001", "ver%2F")

  fun next(random: Random): MapRequestFacts {
    val z = random.nextInt(19) - 2
    val side = if (z in 0..30) 1L shl z else 1L
    val x = when (random.nextInt(8)) {
      0 -> side + random.nextInt(4)
      1 -> -1L - random.nextInt(3)
      else -> (random.nextLong() and Long.MAX_VALUE) % side
    }
    val y = when (random.nextInt(8)) {
      0 -> side
      1 -> Long.MAX_VALUE
      else -> (random.nextLong() and Long.MAX_VALUE) % side
    }
    val path = when (random.nextInt(14)) {
      0 -> "/planet"
      1 -> "/planet/v/$z/$x/$y.pbf/../../x"
      2 -> "/planet/v/0$z/$x/$y.pbf"
      3 -> "/planet/v/$z/$x/$y.png"
      4 -> "/planet/latest/$z/$x/$y.pbf"
      5 -> "/planet/v/$z/$x/$y.pbf%00"
      6 -> "/styles/positron"
      7 -> "/planet/v/$z/$x.pbf"
      8 -> "/planet/v/$z/$x/$y.pbf".uppercase()
      9 -> "//planet/v/$z/$x/$y.pbf"
      else -> "/planet/v/$z/$x/$y.pbf"
    }
    return MapRequestFacts(
      method = if (random.nextInt(8) == 0) methods[random.nextInt(methods.size)] else "GET",
      scheme = if (random.nextInt(8) == 0) schemes[random.nextInt(schemes.size)] else "https",
      host = if (random.nextInt(4) == 0) hosts[random.nextInt(hosts.size)] else HOST,
      port = if (random.nextInt(8) == 0) ports[random.nextInt(ports.size)] else 443,
      path = path,
      query = when (random.nextInt(14)) {
        0 -> "x=1"
        1 -> ""
        else -> null
      },
      userInfo = when (random.nextInt(24)) {
        0 -> "u:p"
        1 -> ""
        else -> null
      },
      fragment = when (random.nextInt(24)) {
        0 -> "f"
        1 -> ""
        else -> null
      },
    )
  }

  fun state(random: Random): MapNetState {
    val coveredParity = random.nextInt(3)
    val scope = if (random.nextInt(5) == 0) RegionDownloadScope({ it.x % 3 == 0 }, random.nextBoolean()) else null
    return MapNetState(
      online = random.nextBoolean(),
      network = MapNetwork.entries[random.nextInt(MapNetwork.entries.size)],
      mobileAllowed = random.nextBoolean(),
      covered = { t: TileKey -> coveredParity != 0 && (t.y + coveredParity) % 2 == 0 },
      currentVersion = if (random.nextBoolean()) versions[0] else versions[random.nextInt(versions.size)],
      download = scope,
    )
  }

  /** Per i messaggi dei test: i fatti per intero (qui sono sintetici, non zone vere). */
  fun describe(req: MapRequestFacts, s: MapNetState): String =
    "${req.method} ${req.scheme}://${req.userInfo?.let { "$it@" } ?: ""}${req.host}:${req.port}${req.path}" +
      "${req.query?.let { "?$it" } ?: ""}${req.fragment?.let { "#$it" } ?: ""} [$s, v=${s.currentVersion}, download=${s.download != null}]"
}

/**
 * Le regole del §2.8 rilette da zero, **senza** le funzioni del cancello ne' le espressioni regolari del contratto: il percorso che una richiesta
 * puo' avere sul server se le regole la ammettono, o null. Una tessera e' ammessa solo se il suo percorso, riscritto con i numeri letti, torna
 * identico (cosi' zeri davanti, segni, cifre non ASCII e qualunque altra forma non passano).
 */
internal object Oracle {

  private const val VERSION_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.-"

  fun allowedPath(req: MapRequestFacts, s: MapNetState): String? {
    val tileOrJson = target(req) ?: return null
    val tile = tileOrJson.first
    val inDownload = s.download != null && (tile == null || s.download!!.includes(tile))
    if (!inDownload && !s.online) return null
    val mobileOk = if (inDownload) s.download!!.mobileConfirmed else s.mobileAllowed
    if (s.network == MapNetwork.ROAMING || s.network == MapNetwork.NONE) return null
    if (s.network == MapNetwork.MOBILE && !mobileOk) return null
    if (tile == null) return "/planet"
    if (!inDownload && s.covered(tile)) return null
    val v = s.currentVersion ?: return null
    if (!validVersion(v)) return null
    return "/planet/$v/${tile.z}/${tile.x}/${tile.y}.pbf"
  }

  /** Una tessera ammessa da tutte le regole tranne la versione, che manca o non e' valida. */
  fun wouldNeedVersion(req: MapRequestFacts, s: MapNetState): Boolean {
    if (target(req)?.first == null) return false
    if (allowedPath(req, s) != null) return false
    val known = MapNetState(s.online, s.network, s.mobileAllowed, s.covered, "v0", s.download)
    return allowedPath(req, known) != null && !(s.currentVersion?.let(::validVersion) ?: false)
  }

  /** (tessera, null) per una tessera, (null, Unit) per il TileJSON, null se la richiesta non e' ne' l'una ne' l'altro sull'unico host. */
  private fun target(req: MapRequestFacts): Pair<TileKey?, Unit>? {
    if (req.method != "GET" || req.scheme != "https" || req.host != "tiles.openfreemap.org" || req.port != 443) return null
    if (!req.userInfo.isNullOrEmpty() || req.fragment != null || !req.query.isNullOrEmpty()) return null
    if (req.path == "/planet") return null to Unit
    val parts = req.path.split('/')
    if (parts.size != 6 || parts[0] != "" || parts[1] != "planet" || parts[2] != "v" || !parts[5].endsWith(".pbf")) return null
    val z = parts[3].toIntOrNull() ?: return null
    val x = parts[4].toIntOrNull() ?: return null
    val y = parts[5].removeSuffix(".pbf").toIntOrNull() ?: return null
    if (z !in 0..14 || x !in 0 until (1 shl z) || y !in 0 until (1 shl z)) return null
    if (req.path != "/planet/v/$z/$x/$y.pbf") return null
    return TileKey(z, x, y) to Unit
  }

  private fun validVersion(v: String): Boolean = v.length in 1..64 && v.all { it in VERSION_CHARS } && v != "." && v != ".."
}
