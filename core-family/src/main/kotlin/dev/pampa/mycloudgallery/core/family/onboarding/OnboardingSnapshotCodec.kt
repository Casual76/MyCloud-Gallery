package dev.pampa.mycloudgallery.core.family.onboarding

import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.PreviewQuality
import dev.pampa.mycloudgallery.core.network.contract.HomeCandidate
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.trust.TrustViolation
import dev.pampa.mycloudgallery.core.trust.codec.CanonicalJson
import dev.pampa.mycloudgallery.core.trust.codec.JsonArray
import dev.pampa.mycloudgallery.core.trust.codec.JsonBool
import dev.pampa.mycloudgallery.core.trust.codec.JsonInt
import dev.pampa.mycloudgallery.core.trust.codec.JsonObject
import dev.pampa.mycloudgallery.core.trust.codec.JsonString
import dev.pampa.mycloudgallery.core.trust.codec.JsonValue
import dev.pampa.mycloudgallery.core.trust.codec.json
import dev.pampa.mycloudgallery.core.trust.codec.jsonObject

/**
 * Lo [OnboardingSnapshot] come testo: un JSON canonico (lo stesso del formato dei dati sul NAS, quindi nessuna
 * libreria in piu'). E' un file **locale**, scritto e letto solo da questa app: la lettura e' comunque
 * difensiva (un file danneggiato non fa cadere niente, vale "mai iniziato") ma non rigida: i campi in piu'
 * di una versione futura si ignorano, quelli che mancano prendono il valore di partenza.
 *
 * Nessun segreto passa di qui (vedi [OnboardingSnapshot]).
 */
internal object OnboardingSnapshotCodec {

  private const val VERSION = 1L

  fun encode(s: OnboardingSnapshot): String {
    val p = s.progress
    val obj = jsonObject(
      "v" to VERSION.json(),
      "path" to p.path?.name?.json(),
      "plan" to p.plan.names().json(),
      "cur" to p.current.name.json(),
      "done" to p.completed.names().json(),
      "skip" to p.skipped.names().json(),
      "t0" to p.startedAt?.json(),
      "t1" to s.finishedAt?.json(),
      "fin" to s.finished.json(),
      "rsm" to s.resuming.json(),
      "dfr" to s.deferrable.json(),
      "home" to s.homeConfirmed?.let(::homeToJson),
      "nas" to s.nas?.let { jsonObject("ip" to it.ip.json(), "nm" to it.name?.json(), "pt" to it.port.json(), "gd" to it.guid.json()) },
      "usr" to s.nasUser?.json(),
      "cs" to s.commonShare?.json(),
      "ps" to s.privateShare?.json(),
      "ex" to s.excluded.sorted().map { it.json() }.json(),
      "ch" to choicesToJson(s.choices),
      "fnd" to s.foundation?.let {
        jsonObject("st" to it.stage.name.json(), "fam" to it.familyId.json(), "key" to it.keyPublic.json())
      },
      "jn" to s.join?.let {
        jsonObject(
          "fam" to it.familyId.json(),
          "man" to it.manual.json(),
          "min" to it.minRegistryVersion.json(),
          "reg" to it.registered.json(),
          "at" to it.requestedAt?.json(),
        )
      },
    )
    return String(CanonicalJson.encode(obj), Charsets.UTF_8)
  }

  /** Lo snapshot scritto da [encode], oppure null se il testo non lo e' (danneggiato, di una versione sconosciuta). */
  fun decode(text: String): OnboardingSnapshot? =
    try {
      val root = CanonicalJson.parse(text.toByteArray(Charsets.UTF_8)) as? JsonObject
      if (root == null || root.long("v") != VERSION) null else read(root)
    } catch (e: TrustViolation) {
      null
    } catch (e: RuntimeException) {
      null
    }

  private fun read(root: JsonObject): OnboardingSnapshot? {
    val plan = root.array("plan")?.mapNotNull { (it as? JsonString)?.value?.let(::step) } ?: return null
    val current = root.string("cur")?.let(::step) ?: return null
    if (plan.isEmpty() || current !in plan) return null
    val progress = OnboardingProgress(
      path = root.string("path")?.let { name -> OnboardingPath.entries.firstOrNull { it.name == name } },
      plan = plan,
      current = current,
      completed = steps(root.array("done")),
      skipped = steps(root.array("skip")),
      startedAt = root.long("t0"),
    )
    val choices = root.obj("ch")?.let(::choicesFromJson) ?: OnboardingChoices()
    return OnboardingSnapshot(
      progress = progress,
      finished = root.bool("fin") ?: false,
      finishedAt = root.long("t1"),
      resuming = root.bool("rsm") ?: false,
      deferrable = root.bool("dfr") ?: false,
      homeConfirmed = root.obj("home")?.let(::homeFromJson),
      nas = root.obj("nas")?.let { n ->
        NasFacts(
          ip = n.string("ip") ?: return null,
          name = n.string("nm"),
          port = n.long("pt")?.toInt() ?: return null,
          guid = n.string("gd") ?: return null,
        )
      },
      nasUser = root.string("usr"),
      commonShare = root.string("cs"),
      privateShare = root.string("ps"),
      excluded = root.array("ex")?.mapNotNull { (it as? JsonString)?.value }?.toSet() ?: emptySet(),
      choices = choices,
      foundation = root.obj("fnd")?.let { f ->
        FoundationRecord(
          stage = FamilyKeyStage.entries.firstOrNull { it.name == f.string("st") } ?: return null,
          familyId = f.string("fam") ?: return null,
          keyPublic = f.string("key") ?: return null,
        )
      },
      join = root.obj("jn")?.let { j ->
        JoinRecord(
          familyId = j.string("fam") ?: return null,
          manual = j.bool("man") ?: false,
          minRegistryVersion = j.long("min") ?: 0L,
          registered = j.bool("reg") ?: false,
          requestedAt = j.long("at"),
        )
      },
    )
  }

  // --- pezzi ----------------------------------------------------------------------------------------

  private fun Collection<OnboardingStep>.names(): List<JsonValue> = map { it.name.json() }

  private fun step(name: String): OnboardingStep? = OnboardingStep.entries.firstOrNull { it.name == name }

  private fun steps(array: List<JsonValue>?): Set<OnboardingStep> =
    array?.mapNotNull { (it as? JsonString)?.value?.let(::step) }?.toSet() ?: emptySet()

  private fun homeToJson(h: HomeCandidate): JsonObject = jsonObject(
    "net" to h.networkId.json(),
    "ula" to h.ulaPrefixes.sorted().map { it.json() }.json(),
    "gw" to h.gatewayV4?.json(),
    "dns" to h.dnsDomain?.json(),
    "sub" to h.ipv4Subnet?.json(),
  )

  private fun homeFromJson(o: JsonObject): HomeCandidate? {
    val network = o.string("net") ?: return null
    return HomeCandidate(
      networkId = network,
      ulaPrefixes = o.array("ula")?.mapNotNull { (it as? JsonString)?.value }?.toSet() ?: emptySet(),
      gatewayV4 = o.string("gw"),
      dnsDomain = o.string("dns"),
      ipv4Subnet = o.string("sub"),
    )
  }

  private fun choicesToJson(c: OnboardingChoices): JsonObject = jsonObject(
    "cam" to c.camera.json(),
    "shot" to c.screenshots.json(),
    "idx" to c.indexer.json(),
    "prep" to c.preparer.json(),
    "pre" to c.preset.name.json(),
    "q" to c.quality.name.json(),
    "rc" to c.remoteControl.json(),
    "tn" to c.tunnelName?.json(),
    "eb" to c.externalBackupDone.json(),
    "rl" to c.routerChecklistDone.json(),
  )

  private fun choicesFromJson(o: JsonObject): OnboardingChoices {
    val d = OnboardingChoices()
    return OnboardingChoices(
      camera = o.bool("cam") ?: d.camera,
      screenshots = o.bool("shot") ?: d.screenshots,
      indexer = o.bool("idx") ?: d.indexer,
      preparer = o.bool("prep") ?: d.preparer,
      preset = Preset.entries.firstOrNull { it.name == o.string("pre") } ?: d.preset,
      quality = PreviewQuality.entries.firstOrNull { it.name == o.string("q") } ?: d.quality,
      remoteControl = o.bool("rc") ?: d.remoteControl,
      tunnelName = o.string("tn"),
      externalBackupDone = o.bool("eb") ?: d.externalBackupDone,
      routerChecklistDone = o.bool("rl") ?: d.routerChecklistDone,
    )
  }

  private fun JsonObject.string(key: String): String? = (members[key] as? JsonString)?.value

  private fun JsonObject.long(key: String): Long? = (members[key] as? JsonInt)?.value

  private fun JsonObject.bool(key: String): Boolean? = (members[key] as? JsonBool)?.value

  private fun JsonObject.array(key: String): List<JsonValue>? = (members[key] as? JsonArray)?.items

  private fun JsonObject.obj(key: String): JsonObject? = members[key] as? JsonObject
}
