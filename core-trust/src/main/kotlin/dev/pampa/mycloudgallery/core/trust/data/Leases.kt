package dev.pampa.mycloudgallery.core.trust.data

import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.codec.Fields
import dev.pampa.mycloudgallery.core.trust.codec.TextRules
import dev.pampa.mycloudgallery.core.trust.codec.json
import dev.pampa.mycloudgallery.core.trust.codec.jsonObject
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.ensure
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.envelope.Envelope
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.DeviceTrust
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import dev.pampa.mycloudgallery.core.trust.registry.ShareNames
import dev.pampa.mycloudgallery.core.trust.verdictOf
import dev.pampa.mycloudgallery.core.trust.violation

/**
 * Una prenotazione (`docs/formato-nas.md` §6.2): "sto lavorando su [resource] fino a acquiredAt +
 * durationMillis (ora del NAS)". Serve a non fare due volte lo stesso lavoro (E-I1); non protegge nulla:
 * se due dispositivi lavorano insieme, il risultato resta coerente.
 *
 * [durationMillis] = 0 vuol dire "rilasciata".
 */
data class Lease(
  val familyId: String,
  val deviceId: String,
  val share: String,
  val resource: String,
  val generation: Long,
  val acquiredAt: Long,
  val durationMillis: Long,
) {
  init {
    ensure(TrustIds.isValid(familyId) && TrustIds.isValid(deviceId), RejectReason.INVALID_FIELD, "prenotazione: id")
    ShareNames.check(share)
    Leases.checkResource(resource)
    ensure(generation in 1..TrustLimits.MAX_VERSION, RejectReason.INVALID_FIELD, "prenotazione: generazione")
    ensure(durationMillis >= 0, RejectReason.INVALID_FIELD, "prenotazione: durata negativa")
  }

  internal fun sign(signer: Signer): ByteArray = Envelope.seal(
    DocType.LEASE,
    jsonObject(
      "family" to familyId.json(),
      "device" to deviceId.json(),
      "share" to share.json(),
      "resource" to resource.json(),
      "generation" to generation.json(),
      "acquiredAt" to acquiredAt.json(),
      "durationMillis" to durationMillis.json(),
    ),
    signer,
  )
}

/**
 * Dove stanno le prenotazioni (§5.6, §6.2). [Shared] = `leases/` della share (il lavoro delle Fasi 4 e 6); [Faces] = `faces/<G>/leases/`
 * dell'area privata (Fase 8, A5, D16), sotto la generazione: "Disattiva e cancella" le toglie con la cartella. Le risorse dei volti
 * (`faces/<G>/...`, `NasLayout.facesWorkResource` e `facesClusterResource`) stanno solo sotto la loro generazione, le altre mai: i percorsi
 * li costruisce sempre `NasLayout`, mai un dato letto.
 */
sealed interface LeaseRoot {
  /** `<radice>/<impronta della risorsa>/`. */
  fun dir(resource: String): SharePath

  /** Vero se [resource] puo' stare sotto questa radice. */
  fun admits(resource: String): Boolean

  fun shardDir(resource: String, shard: Long): SharePath = dir(resource).child(NasLayout.versionName(shard))

  fun file(resource: String, generation: Long): SharePath =
    shardDir(resource, NasLayout.leaseShardOf(generation)).child(NasLayout.versionName(generation))

  data object Shared : LeaseRoot {
    override fun dir(resource: String): SharePath = NasLayout.leaseDir(resource)

    override fun admits(resource: String): Boolean = !resource.startsWith(FACES_RESOURCE_PREFIX)
  }

  data class Faces(val generation: FaceGeneration) : LeaseRoot {
    override fun dir(resource: String): SharePath = NasLayout.facesLeaseDir(generation, resource)

    override fun admits(resource: String): Boolean = resource.startsWith("$FACES_RESOURCE_PREFIX${generation.base32}/")

    override fun toString(): String = "LeaseRoot.Faces"
  }
}

/** Il prefisso delle risorse dei volti (`NasLayout.facesWorkResource`, `facesClusterResource`). */
private const val FACES_RESOURCE_PREFIX = "faces/"

/** Lo stato di una risorsa secondo la generazione piu' alta trovata. */
sealed interface LeaseStatus {
  data object Free : LeaseStatus

  data class HeldByMe(val generation: Long, val until: Long) : LeaseStatus

  data class HeldByOther(val holderDeviceId: String, val until: Long) : LeaseStatus
}

/**
 * Validita' di una prenotazione, solo con l'ora del NAS (E-P9) e con il tetto (E-I16): una durata oltre
 * [TrustLimits.MAX_LEASE_MILLIS] o un inizio nel futuro oltre lo scarto ammesso rendono la prenotazione
 * IGNORATA (la risorsa e' libera), non accorciata.
 */
object LeasePolicy {
  fun evaluate(lease: Lease?, nasNow: Long, me: String): LeaseStatus {
    if (lease == null) return LeaseStatus.Free
    if (lease.durationMillis <= 0 || lease.durationMillis > TrustLimits.MAX_LEASE_MILLIS) return LeaseStatus.Free
    if (lease.acquiredAt > nasNow + TrustLimits.MAX_FUTURE_SKEW_MILLIS) return LeaseStatus.Free
    val until = lease.acquiredAt + lease.durationMillis
    if (nasNow >= until) return LeaseStatus.Free
    return if (lease.deviceId == me) LeaseStatus.HeldByMe(lease.generation, until) else LeaseStatus.HeldByOther(lease.deviceId, until)
  }
}

sealed interface AcquireResult {
  data class Acquired(val generation: Long, val until: Long) : AcquireResult

  data class Busy(val holderDeviceId: String, val until: Long) : AcquireResult

  /** Un altro dispositivo ha pubblicato la stessa generazione un istante prima: si riprova piu' tardi. */
  data object Lost : AcquireResult

  /** Cartella anomala (troppe generazioni o numeri al limite): niente prenotazione, si va avanti senza. */
  data object Contended : AcquireResult
}

/**
 * Prenotazioni sul NAS con generazioni numerate: `leases/<hash risorsa>/<gruppo>/<generazione>`, ognuna
 * pubblicata con `FILE_CREATE`. Prendere una risorsa libera = pubblicare la generazione successiva alla piu'
 * alta; se il nome esiste gia', ha vinto un altro. Nessun file altrui viene mai cancellato o sovrascritto.
 *
 * Le generazioni stanno in gruppi di [TrustLimits.LEASE_SHARD_SIZE]: si elencano i gruppi e poi solo il piu'
 * alto, cosi' nessuna cartella cresce senza limite e una risorsa usata migliaia di volte resta prenotabile
 * (revisione 1.4, M6: con una cartella sola, dopo 257 usi restava solo `Contended`).
 */
class LeaseManager(
  private val share: NasFileSystem,
  private val signer: Signer,
  private val clock: NasClock,
  /** Fase 8 (A5, D16): la radice delle prenotazioni; quelle dei volti stanno sotto la loro generazione ([LeaseRoot.Faces]). */
  private val root: LeaseRoot = LeaseRoot.Shared,
) {
  private val me: String = TrustIds.deviceIdOf(signer.publicKey)

  /** Lo stato della risorsa e la generazione piu' alta trovata (0 se nessuna); null se le cartelle sono anomale. */
  fun inspect(registry: RegistryState, resource: String): Pair<LeaseStatus, Long>? {
    Leases.checkResource(resource)
    require(root.admits(resource)) { "Risorsa fuori dalla sua radice delle prenotazioni." }
    val shards = share.list(root.dir(resource), TrustLimits.MAX_LIST_LEASE_SHARDS)
    if (shards.truncated) return null
    val shard = shards.names.mapNotNull(NasLayout::parseVersionName).maxOrNull() ?: return LeaseStatus.Free to 0L
    val names = share.list(root.shardDir(resource, shard), TrustLimits.MAX_LIST_LEASE_GENERATIONS)
    if (names.truncated) return null
    val highest = names.names.mapNotNull(NasLayout::parseVersionName).filter { NasLayout.leaseShardOf(it) == shard }.maxOrNull()
      // Gruppo vuoto o pieno di spazzatura: la prossima generazione e' la prima del gruppo.
      ?: return LeaseStatus.Free to (shard - 1) * TrustLimits.LEASE_SHARD_SIZE
    val lease = read(registry, resource, highest).let { (it as? Verdict.Accepted)?.value }
    return LeasePolicy.evaluate(lease, clock.nowMillis(), me) to highest
  }

  fun tryAcquire(registry: RegistryState, resource: String, durationMillis: Long): AcquireResult {
    require(durationMillis in 1..TrustLimits.MAX_LEASE_MILLIS) { "Durata fuori tetto." }
    check(registry.canWrite(me)) { "Un dispositivo non approvato non prenota." }
    val (status, highest) = inspect(registry, resource) ?: return AcquireResult.Contended
    if (status is LeaseStatus.HeldByOther) return AcquireResult.Busy(status.holderDeviceId, status.until)
    return publish(registry, resource, highest, durationMillis)
  }

  /** Rilascia pubblicando una generazione con durata 0, se la prenotazione e' ancora nostra. */
  fun release(registry: RegistryState, resource: String) {
    val (status, highest) = inspect(registry, resource) ?: return
    if (status is LeaseStatus.HeldByMe && status.generation == highest) publish(registry, resource, highest, 0)
  }

  private fun publish(registry: RegistryState, resource: String, highest: Long, durationMillis: Long): AcquireResult {
    if (highest >= TrustLimits.MAX_VERSION) return AcquireResult.Contended
    val generation = highest + 1
    val now = clock.nowMillis()
    val lease = Lease(registry.registry.familyId, me, share.shareName, resource, generation, now, durationMillis)
    return when (share.publishExclusive(root.file(resource, generation), lease.sign(signer))) {
      PublishResult.CREATED -> AcquireResult.Acquired(generation, now + durationMillis)
      PublishResult.ALREADY_EXISTS -> AcquireResult.Lost
    }
  }

  private fun read(registry: RegistryState, resource: String, generation: Long): Verdict<Lease> {
    val bytes = when (val read = share.read(root.file(resource, generation), Envelope.maxFileBytes(DocType.LEASE))) {
      is NasRead.Data -> read.bytes
      NasRead.NotFound -> return Verdict.Rejected(RejectReason.MALFORMED)
      NasRead.TooLarge -> return Verdict.Rejected(RejectReason.TOO_LARGE)
    }
    return Leases.verify(bytes, registry, share.shareName, resource, generation)
  }

}

object Leases {
  internal fun checkResource(resource: String) {
    ensure(resource.isNotEmpty() && resource.length <= TrustLimits.MAX_RESOURCE_KEY_CHARS, RejectReason.INVALID_FIELD, "prenotazione: risorsa")
    ensure(!TextRules.hasForbidden(resource), RejectReason.INVALID_FIELD, "prenotazione: risorsa")
  }

  /**
   * Verifica una prenotazione letta in `leases/<hash di resource>/<generation>`: firmatario approvato,
   * famiglia, share, risorsa e generazione uguali al posto in cui si trova (una prenotazione copiata
   * altrove non vale).
   */
  fun verify(bytes: ByteArray, registry: RegistryState, share: String, resource: String, generation: Long): Verdict<Lease> = verdictOf {
    val trust = DeviceTrust(registry)
    val signed = trust.open(bytes, DocType.LEASE)
    val lease = Fields.read(signed.envelope.payloadJson()) { f ->
      trust.checkCommon(f, signed)
      Lease(
        familyId = registry.registry.familyId,
        deviceId = signed.signer.id,
        share = f.string("share", TrustLimits.MAX_SHARE_NAME_CHARS),
        resource = f.string("resource", TrustLimits.MAX_RESOURCE_KEY_CHARS),
        generation = f.long("generation", 1..TrustLimits.MAX_VERSION),
        acquiredAt = f.time("acquiredAt"),
        durationMillis = f.long("durationMillis", 0..Long.MAX_VALUE),
      )
    }
    ensure(
      lease.share == share && lease.resource == resource && lease.generation == generation,
      RejectReason.WRONG_LOCATION,
      "prenotazione: non corrisponde al posto in cui si trova",
    )
    lease
  }

  /**
   * Come [verify], per chi trova la prenotazione **dal posto** (`leases/<impronta della risorsa>/<gruppo>/<generazione>`) e non
   * conosce la risorsa in chiaro: la risorsa si legge dal contenuto firmato e deve avere esattamente l'impronta [resourceName]
   * della cartella. Aggiunta della Fase 4B (P12, pulizia dei propri file, M6): nessuna decisione qui, solo la verifica.
   */
  fun verifyAt(bytes: ByteArray, registry: RegistryState, share: String, resourceName: String, generation: Long): Verdict<Lease> = verdictOf {
    val trust = DeviceTrust(registry)
    val signed = trust.open(bytes, DocType.LEASE)
    val resource = Fields.read(signed.envelope.payloadJson()) { f ->
      f.string("family", TrustIds.LENGTH)
      f.string("device", TrustIds.LENGTH)
      f.string("share", TrustLimits.MAX_SHARE_NAME_CHARS)
      f.long("generation", 1..TrustLimits.MAX_VERSION)
      f.time("acquiredAt")
      f.long("durationMillis", 0..Long.MAX_VALUE)
      f.string("resource", TrustLimits.MAX_RESOURCE_KEY_CHARS)
    }
    ensure(NasLayout.resourceName(resource) == resourceName, RejectReason.WRONG_LOCATION, "prenotazione: risorsa di un'altra cartella")
    when (val verdict = verify(bytes, registry, share, resource, generation)) {
      is Verdict.Accepted -> verdict.value
      is Verdict.Rejected -> violation(verdict.reason, "prenotazione rifiutata")
    }
  }
}
