package dev.pampa.mycloudgallery.core.trust.registry

import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.envelope.Envelope
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/** Un registro firmato, pronto da pubblicare (solo la fondazione: le altre versioni passano da [FamilyAuthority.commit]). */
class SignedRegistry(val registry: FamilyRegistry, bytes: ByteArray) {
  private val data = bytes.copyOf()
  val bytes: ByteArray get() = data.copyOf()
}

/**
 * Una modifica del registro preparata dall'admin a partire da [base], non ancora numerata ne' firmata: il
 * numero lo sceglie [FamilyAuthority.commit] (il primo nome libero).
 */
class RegistryDraft internal constructor(
  val base: RegistryState,
  internal val content: FamilyRegistry,
  /** Rami che l'admin ha scelto di unire: i loro file si scavalcano senza contarli come spazzatura. */
  internal val mergedForks: Set<String> = emptySet(),
)

sealed interface CommitResult {
  /** Pubblicata. [state] e' il nuovo registro fidato; [skipped] i nomi occupati da file non validi scavalcati (Diagnostica). */
  class Published(val state: RegistryState, val skipped: List<SharePath>) : CommitResult

  /** Sul NAS c'e' gia' una versione piu' nuova valida (un altro admin): risincronizzare e rifare la modifica. */
  data object NewerExists : CommitResult

  /** Sul NAS c'e' un registro di un'app piu' nuova: questa app non firma finche' non si aggiorna (E-I3). */
  data object NeedsAppUpdate : CommitResult

  /**
   * Un ramo diverso firmato con la chiave di famiglia (un admin che ha lavorato su una base vecchia, oppure
   * la chiave e' stata rubata). L'admin lo guarda: se lo riconosce lo unisce ([FamilyAuthority.mergeFork]),
   * altrimenti reimposta la famiglia.
   */
  class Fork(val path: SharePath, val fork: RegistryFork) : CommitResult

  /**
   * Troppi nomi consecutivi occupati da file non validi ([TrustLimits.MAX_JUNK_RUN]): la revoca non si
   * puo' pubblicare finche' quei file (elenco esatto in [junk]) non vengono cancellati dal PC, dopo aver
   * cambiato la password NAS dell'account che li scrive.
   */
  class Blocked(val junk: List<SharePath>) : CommitResult
}

/**
 * Le operazioni dell'admin sul registro (`docs/formato-nas.md` §4.4–4.6): ognuna prepara una modifica
 * ([RegistryDraft]) che [commit] numera, firma con la chiave di famiglia e pubblica con `FILE_CREATE`.
 * Nessuna modifica parte da dati letti: approvare, revocare, dismettere, unire un ramo sono azioni locali
 * dell'admin.
 */
class FamilyAuthority(private val familyKey: FamilyKey, private val clock: NasClock) {

  /** Il primo registro della famiglia: l'admin che la fonda e' il primo dispositivo approvato. */
  fun found(familyId: String, config: FamilyConfig, founder: RegistrationRequest): SignedRegistry {
    require(founder.familyId == familyId) { "La richiesta del fondatore e' di un'altra famiglia." }
    val now = clock.nowMillis()
    return sign(FamilyRegistry(familyId, 1, now, config, listOf(entryOf(founder, admin = true, now))))
  }

  /**
   * Approva un dispositivo che ha chiesto di entrare. [proof]: il codice digitato dall'admin leggendolo sul
   * dispositivo nuovo, o la sua chiave scansionata. [firstSeenAt]: quando l'app dell'admin ha visto la
   * richiesta la prima volta (ora del NAS, annotata in locale); oltre 15 minuti la richiesta va rifatta.
   */
  fun approve(
    state: RegistryState,
    request: RegistrationRequest,
    proof: ApprovalProof,
    firstSeenAt: Long,
    admin: Boolean = false,
  ): RegistryDraft {
    val current = authorized(state)
    require(request.familyId == current.familyId) { "Richiesta di un'altra famiglia." }
    require(current.device(request.deviceId) == null) { "Dispositivo gia' presente nel registro." }
    require(proof.proves(request)) { "Il codice non corrisponde al dispositivo: non approvare." }
    require(ApprovalWindow.isOpen(firstSeenAt, clock.nowMillis())) { "Richiesta troppo vecchia: il dispositivo deve rifarla." }
    val devices = (current.devices + entryOf(request, admin, clock.nowMillis())).sortedBy { it.id }
    return RegistryDraft(state, current.copy(devices = devices))
  }

  /**
   * Cambia lo stato di un dispositivo: dismesso ([DeviceStatus.RETIRED], con le teste che restano valide),
   * revocato, perso. Non si torna mai indietro ([DeviceStatus.canBecome]).
   */
  fun changeStatus(
    state: RegistryState,
    deviceId: String,
    status: DeviceStatus,
    frozenHeads: List<String> = emptyList(),
  ): RegistryDraft {
    val current = authorized(state)
    val entry = requireNotNull(current.device(deviceId)) { "Dispositivo assente dal registro." }
    require(entry.status.canBecome(status)) { "Cambio di stato non ammesso." }
    require(status == DeviceStatus.RETIRED || frozenHeads.isEmpty()) { "Solo un dispositivo dismesso conserva dati." }
    val updated = entry.copy(status = status, since = clock.nowMillis(), frozenHeads = frozenHeads.distinct().sorted())
    return RegistryDraft(state, current.copy(devices = current.devices.map { if (it.id == deviceId) updated else it }))
  }

  fun updateConfig(state: RegistryState, config: FamilyConfig): RegistryDraft =
    RegistryDraft(state, authorized(state).copy(config = config))

  /**
   * Rotazione: la versione pubblicata da questo draft, firmata dalla chiave attuale, annuncia [next]; da li'
   * in poi firma solo [next] (con un nuovo [FamilyAuthority]). Non e' una difesa da una chiave gia' rubata:
   * per quella c'e' il reset. Dopo la rotazione si genera un kit nuovo, con un codice nuovo.
   */
  fun rotateTo(state: RegistryState, next: FamilyKey): RegistryDraft {
    val current = authorized(state)
    require(next.publicKey != familyKey.publicKey) { "La chiave nuova deve essere diversa." }
    return RegistryDraft(state, current.copy(successorKey = next.publicKey))
  }

  /**
   * Unisce un ramo che l'admin ha riconosciuto (tipicamente il secondo dispositivo admin che ha lavorato su
   * una base vecchia): ogni dispositivo con lo stato piu' severo dei due rami. La versione che ne esce e'
   * accettata dai dispositivi di entrambi i rami, e le revoche di nessuno dei due vanno perse.
   */
  fun mergeFork(state: RegistryState, fork: RegistryFork): RegistryDraft {
    val current = authorized(state)
    require(fork.registry.successorKey == null) { "Il ramo contiene una rotazione: serve il reset della famiglia." }
    return RegistryDraft(state, current.joinWith(fork.registry), setOf(fork.contentHash))
  }

  /**
   * Numera, firma e pubblica [draft]: il numero e' il primo nome libero dopo la base, letto per nome
   * (`v+1`, `v+2`, …) e non dall'elenco. I nomi occupati da spazzatura si scavalcano (al massimo
   * [TrustLimits.MAX_JUNK_RUN] di fila) e si riportano; un registro valido piu' nuovo o un ramo diverso
   * fermano la pubblicazione. Le versioni possono quindi avere buchi.
   */
  fun commit(commonShare: NasFileSystem, draft: RegistryDraft): CommitResult {
    authorized(draft.base)
    val base = draft.base
    val anchor = base.toAnchor()
    val skipped = ArrayList<SharePath>()
    var version = base.registry.version + 1
    var collisions = 0
    while (skipped.size <= TrustLimits.MAX_JUNK_RUN && version <= TrustLimits.MAX_VERSION) {
      val path = NasLayout.registryFile(version)
      when (val read = commonShare.read(path, Envelope.maxFileBytes(DocType.FAMILY_REGISTRY))) {
        NasRead.NotFound -> {
          val issuedAt = maxOf(clock.nowMillis(), base.registry.issuedAt)
          val signed = sign(draft.content.copy(version = version, issuedAt = issuedAt))
          when (commonShare.publishExclusive(path, signed.bytes)) {
            PublishResult.CREATED -> {
              val state = (RegistryVerifier.accept(signed.bytes, base, anchor) as Verdict.Accepted).value
              return CommitResult.Published(state, skipped)
            }
            // Qualcuno ha scritto quel nome un istante prima: lo si rilegge (al massimo due volte).
            PublishResult.ALREADY_EXISTS -> if (++collisions > 2) {
              skipped.add(path)
              version++
            }
          }
        }
        NasRead.TooLarge -> {
          skipped.add(path)
          version++
        }
        is NasRead.Data -> when (val verdict = RegistryVerifier.accept(read.bytes, base, anchor)) {
          is Verdict.Accepted -> return CommitResult.NewerExists
          is Verdict.Rejected -> {
            if (verdict.reason == RejectReason.NEWER_FORMAT) return CommitResult.NeedsAppUpdate
            val fork = if (verdict.reason == RejectReason.REGRESSION) RegistryVerifier.readFork(read.bytes, base) else null
            when {
              fork is Verdict.Accepted && fork.value.contentHash in draft.mergedForks -> Unit // ramo gia' unito: si scavalca
              fork is Verdict.Accepted -> return CommitResult.Fork(path, fork.value)
              else -> skipped.add(path)
            }
            version++
          }
        }
      }
    }
    return CommitResult.Blocked(skipped)
  }

  private fun authorized(state: RegistryState): FamilyRegistry {
    check(state.trustedFamilyKey == familyKey.publicKey) { "Questa chiave non e' (piu') quella di famiglia." }
    // Dopo una rotazione l'annuncio non si ripete: le versioni nuove non lo portano.
    return state.registry.copy(successorKey = null)
  }

  private fun entryOf(request: RegistrationRequest, admin: Boolean, now: Long) = DeviceEntry(
    id = request.deviceId,
    key = request.key,
    name = request.name,
    model = request.model,
    nasUser = request.nasUser,
    admin = admin,
    status = DeviceStatus.APPROVED,
    since = now,
  )

  private fun sign(registry: FamilyRegistry): SignedRegistry =
    SignedRegistry(registry, Envelope.seal(DocType.FAMILY_REGISTRY, registry.toJson(), familyKey))

  companion object {
    /** Pubblica la fondazione (versione 1). [PublishResult.ALREADY_EXISTS]: qui c'e' gia' una famiglia. */
    fun publishFounding(commonShare: NasFileSystem, signed: SignedRegistry): PublishResult {
      require(signed.registry.version == 1L) { "Solo la fondazione si pubblica cosi'." }
      return commonShare.publishExclusive(NasLayout.registryFile(1), signed.bytes)
    }
  }
}
