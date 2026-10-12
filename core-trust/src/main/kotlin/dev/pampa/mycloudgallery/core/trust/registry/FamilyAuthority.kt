package dev.pampa.mycloudgallery.core.trust.registry

import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.envelope.Envelope
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult

/** Un registro firmato, pronto da pubblicare. */
class SignedRegistry(val registry: FamilyRegistry, bytes: ByteArray) {
  private val data = bytes.copyOf()
  val bytes: ByteArray get() = data.copyOf()
}

/**
 * Le operazioni dell'admin sul registro (`docs/formato-nas.md` §4.4): ognuna produce la versione
 * successiva, firmata con la chiave di famiglia. Nessuna operazione modifica il registro sul NAS: si
 * pubblica una versione NUOVA con [publish]; se un altro admin ha gia' usato quel numero, si risincronizza
 * e si ripete.
 *
 * Tutte partono da un'azione locale dell'admin (approvare, revocare, dismettere): nessuna da dati letti.
 */
class FamilyAuthority(private val familyKey: FamilyKey, private val clock: NasClock) {

  /** Il primo registro della famiglia: l'admin che la fonda e' il primo dispositivo approvato. */
  fun found(familyId: String, config: FamilyConfig, founder: RegistrationRequest): SignedRegistry {
    require(founder.familyId == familyId) { "La richiesta del fondatore e' di un'altra famiglia." }
    val now = clock.nowMillis()
    return sign(FamilyRegistry(familyId, 1, now, config, listOf(entryOf(founder, admin = true, now))))
  }

  /** Approva un dispositivo che ha chiesto di entrare (dopo il confronto del codice di approvazione). */
  fun approve(state: RegistryState, request: RegistrationRequest, admin: Boolean = false): SignedRegistry {
    val current = authorized(state)
    require(request.familyId == current.familyId) { "Richiesta di un'altra famiglia." }
    require(current.device(request.deviceId) == null) { "Dispositivo gia' presente nel registro." }
    val now = clock.nowMillis()
    val devices = (current.devices + entryOf(request, admin, now)).sortedBy { it.id }
    return sign(current.copy(version = current.version + 1, issuedAt = now, devices = devices))
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
  ): SignedRegistry {
    val current = authorized(state)
    val entry = requireNotNull(current.device(deviceId)) { "Dispositivo assente dal registro." }
    require(entry.status.canBecome(status)) { "Cambio di stato non ammesso." }
    require(status == DeviceStatus.RETIRED || frozenHeads.isEmpty()) { "Solo un dispositivo dismesso conserva dati." }
    val now = clock.nowMillis()
    val updated = entry.copy(status = status, since = now, frozenHeads = frozenHeads.distinct().sorted())
    val devices = current.devices.map { if (it.id == deviceId) updated else it }
    return sign(current.copy(version = current.version + 1, issuedAt = now, devices = devices))
  }

  fun updateConfig(state: RegistryState, config: FamilyConfig): SignedRegistry {
    val current = authorized(state)
    val now = clock.nowMillis()
    return sign(current.copy(version = current.version + 1, issuedAt = now, config = config))
  }

  /**
   * Rotazione: l'ultima versione firmata dalla chiave attuale annuncia [next]; da li' in poi firma solo
   * [next] (con un nuovo [FamilyAuthority]). La vecchia chiave smette di valere per tutti i dispositivi
   * che hanno visto l'annuncio. Non e' una difesa da una chiave gia' rubata: per quella c'e' il reset.
   */
  fun rotateTo(state: RegistryState, next: FamilyKey): SignedRegistry {
    val current = authorized(state)
    require(next.publicKey != familyKey.publicKey) { "La chiave nuova deve essere diversa." }
    val now = clock.nowMillis()
    return sign(current.copy(version = current.version + 1, issuedAt = now, successorKey = next.publicKey))
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
    /**
     * Pubblica una versione con `FILE_CREATE`: due admin non possono scrivere la stessa versione;
     * [PublishResult.ALREADY_EXISTS] vuol dire "risincronizza e ripeti".
     */
    fun publish(commonShare: NasFileSystem, signed: SignedRegistry): PublishResult =
      commonShare.publishExclusive(NasLayout.registryFile(signed.registry.version), signed.bytes)
  }
}
