package dev.pampa.mycloudgallery.core.family.registry

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.family.onboarding.FamilyConfigFactory
import dev.pampa.mycloudgallery.core.family.trust.TrustEngine
import dev.pampa.mycloudgallery.core.family.trust.UNEXPECTED
import dev.pampa.mycloudgallery.core.family.trust.onShare
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKeyVault
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.CommitResult
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.FamilyAuthority
import dev.pampa.mycloudgallery.core.trust.registry.FamilyConfig
import dev.pampa.mycloudgallery.core.trust.registry.LookAlike
import dev.pampa.mycloudgallery.core.trust.registry.RegistryDraft
import dev.pampa.mycloudgallery.core.trust.registry.RegistryFork
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import dev.pampa.mycloudgallery.core.trust.registry.UlaPrefix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Le teste da congelare quando l'admin dismette un dispositivo (A7, D8: "dismesso: valgono i contributi delle sue teste congelate"). Le dicono i
 * moduli che le hanno accettate su questo dispositivo (R6 della revisione di sicurezza della Fase 7: gli album condivisi, legati da :app); le
 * impronte si ordinano dalla piu' importante e il registro ne tiene al piu' [TrustLimits.MAX_FROZEN_HEADS]. Senza legame nessuna.
 */
fun interface RetireHeads {
  suspend fun headsOf(deviceId: String): List<String>

  companion object {
    val NONE: RetireHeads = RetireHeads { emptyList() }
  }
}

/** Cio' che serve a un'azione dell'admin: il registro fidato, chi sono, la chiave di famiglia aperta. */
class AdminContext(val state: RegistryState, val me: String, val authority: FamilyAuthority, val commonShare: String)

/**
 * Rami in conflitto trovati da una pubblicazione, in attesa della scelta dell'admin (schermata dedicata,
 * P7): [resolve] li unisce ([recognize] = vero) o li rifiuta, sulla **stessa** modifica.
 */
class PendingForks(internal val draft: RegistryDraft, val forks: List<RegistryFork>) {
  /** I rami cambiano la configurazione (es. `nasHost`): la schermata lo dice (revisione 1.4, domanda 10). */
  val configDiffers: Boolean get() = forks.any { it.configDiffers(draft.base.registry) }

  /** I rami annunciano una rotazione: i loro dispositivi vanno riconfigurati (reset), R2. */
  val containsRotation: Boolean get() = forks.any { it.registry.successorKey != null }
}

/**
 * L'unico punto da cui `core-family` firma e pubblica il registro (`FamilyAuthority.commit`). Ogni chiamata
 * parte da un gesto locale dell'admin (E-S11); la chiave di famiglia si apre dalla cassaforte solo qui, per
 * il tempo dell'azione (a telefono sbloccato con blocco schermo sicuro: revisione M3).
 *
 * Esiti di `commit` → [AdminActionResult]: `Published` → `Done` (e lo stato nuovo diventa quello fidato);
 * `NewerExists` → si rilegge il registro e si restituisce `NewerExists` (l'interfaccia riprova sulla base
 * nuova); `Fork` → [pendingForks] per la schermata dedicata; `Blocked` → [blockedPaths] (i file esatti da
 * cancellare dal PC, **dopo** aver cambiato la password NAS sospetta, L18); `NeedsAppUpdate`.
 */
class RegistryWriter(
  private val engine: TrustEngine,
  private val sessions: NasSessionManager,
  private val familyKeys: FamilyKeyVault,
  private val nasClock: NasClock,
) {
  private val _pendingForks = MutableStateFlow<PendingForks?>(null)
  private val _blockedPaths = MutableStateFlow<List<SharePath>>(emptyList())
  private val _unverifiedBeyond = MutableStateFlow(false)

  val pendingForks: StateFlow<PendingForks?> = _pendingForks.asStateFlow()
  val blockedPaths: StateFlow<List<SharePath>> = _blockedPaths.asStateFlow()

  /** R6: chi dice quali teste congelare alla dismissione (lo lega :app quando gli album ci sono). */
  @Volatile var retireHeads: RetireHeads = RetireHeads.NONE

  /** L'ultima pubblicazione non ha potuto controllare tutto oltre il nome usato (N2): da dire all'admin. */
  val unverifiedBeyond: StateFlow<Boolean> = _unverifiedBeyond.asStateFlow()

  /**
   * Il contesto dell'admin, o null se questo dispositivo non lo e': registro fidato caricato, dispositivo
   * approvato **e segnato admin nel registro firmato**, chiave di famiglia nella cassaforte e uguale a quella che deve firmare
   * la prossima versione.
   *
   * Il segno admin (R19 della revisione 5B) non da' il potere, che e' la chiave: toglie quello che una chiave rimasta per
   * sbaglio nella cassaforte (una distruzione fallita in silenzio, un ex admin rientrato come familiare con un id nuovo) non
   * deve poter fare, cioe' firmare la configurazione della famiglia da un dispositivo che la famiglia non conosce come admin.
   */
  suspend fun adminContext(): AdminContext? {
    if (!engine.ensureLoaded()) return null
    val state = engine.state ?: return null
    val me = try {
      engine.myDeviceId()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // R7: il Keystore non risponde, non si sa chi e' questo dispositivo: nessuna azione dell'admin (prima l'eccezione usciva).
      GalleryLog.w(TAG) { "Keystore che non risponde: niente azioni dell'admin" }
      null
    } ?: return null
    if (state.statusOf(me) != DeviceStatus.APPROVED) return null
    if (state.registry.device(me)?.admin != true) return null
    val key = try {
      familyKeys.load(state.trustedFamilyKey)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Telefono bloccato o senza blocco schermo sicuro: la cassaforte non si apre (M3).
      GalleryLog.w(TAG) { "chiave di famiglia non disponibile" }
      null
    } ?: return null
    val share = engine.profile()?.commonShare ?: state.registry.config.commonShare
    return AdminContext(state, me, FamilyAuthority(key, nasClock), share)
  }

  suspend fun commit(admin: AdminContext, draft: RegistryDraft): AdminActionResult {
    val result = try {
      sessions.onShare(admin.commonShare, Purpose.INTERACTIVE) { fs -> admin.authority.commit(fs, draft) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Una modifica non firmabile (per esempio un registro oltre il tetto): nessun byte e' stato scritto.
      GalleryLog.e(TAG, e) { "pubblicazione del registro non riuscita" }
      NasResult.Err(UNEXPECTED)
    }
    return when (result) {
      is NasResult.Err -> AdminActionResult.Failed(result.error)
      is NasResult.Ok -> when (val commit = result.value) {
        is CommitResult.Published -> {
          engine.adopt(commit.state)
          _pendingForks.value = null
          _blockedPaths.value = emptyList()
          _unverifiedBeyond.value = commit.unverifiedBeyond
          AdminActionResult.Done
        }
        CommitResult.NewerExists -> {
          engine.sync(Purpose.INTERACTIVE)
          AdminActionResult.NewerExists
        }
        is CommitResult.Fork -> {
          _pendingForks.value = PendingForks(draft, commit.forks)
          AdminActionResult.Fork
        }
        is CommitResult.Blocked -> {
          _blockedPaths.value = commit.junk
          AdminActionResult.Blocked
        }
        CommitResult.NeedsAppUpdate -> AdminActionResult.NeedsAppUpdate
      }
    }
  }

  /** Cambia lo stato di un dispositivo (non il proprio): dismesso, revocato, perso. */
  suspend fun changeStatus(deviceId: String, status: DeviceStatus): AdminActionResult {
    val admin = adminContext() ?: return AdminActionResult.NotAdmin
    val entry = admin.state.registry.device(deviceId) ?: return AdminActionResult.Failed(NasError.NotFound)
    if (deviceId == admin.me || !entry.status.canBecome(status)) return AdminActionResult.Failed(NasError.PermissionDenied)
    // La dismissione congela le teste gia' verificate su questo dispositivo (A7): R6, quelle degli album condivisi (cosi' un telefono dismesso
    // non fa sparire a tutti i suoi contributi, la definizione dell'album compresa). Le teste dell'indice e dei dati personali non le dice ancora
    // nessuno. Se chi le cerca sbaglia, la dismissione non parte (si riprova): dismettere senza di loro farebbe sparire i contributi.
    val frozen = if (status != DeviceStatus.RETIRED) {
      emptyList()
    } else {
      try {
        retireHeads.headsOf(deviceId).distinct().take(TrustLimits.MAX_FROZEN_HEADS)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.w(TAG) { "teste da congelare non lette: ${e.javaClass.simpleName}" }
        return AdminActionResult.Failed(UNEXPECTED)
      }
    }
    val draft = try {
      admin.authority.changeStatus(admin.state, deviceId, status, frozen)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return AdminActionResult.Failed(NasError.PermissionDenied)
    }
    return commit(admin, draft)
  }

  /**
   * Il nome da mostrare di un dispositivo approvato (F27, E-B7), nella voce firmata del registro: una versione nuova firmata dall'admin, come ogni
   * altra modifica, con la sua chiave e il suo account invariati (`FamilyAuthority.renameDevice`). Un nome che un altro dispositivo ha gia' (anche
   * "sembra uguale": `LookAlike`) e' rifiutato con `NasError.Exists`, uno non valido con `NasError.PermissionDenied` (la pagina lo controlla prima e non
   * lo manda); lo stesso nome di prima non firma niente.
   */
  suspend fun renameDevice(deviceId: String, name: String): AdminActionResult {
    val admin = adminContext() ?: return AdminActionResult.NotAdmin
    val entry = admin.state.registry.device(deviceId) ?: return AdminActionResult.Failed(NasError.NotFound)
    if (entry.status != DeviceStatus.APPROVED) return AdminActionResult.Failed(NasError.PermissionDenied)
    if (entry.name == name) return AdminActionResult.Done
    val key = LookAlike.key(name)
    if (admin.state.registry.devices.any { it.id != deviceId && LookAlike.key(it.name) == key }) return AdminActionResult.Failed(NasError.Exists)
    val draft = try {
      admin.authority.renameDevice(admin.state, deviceId, name)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return AdminActionResult.Failed(NasError.PermissionDenied)
    }
    return commit(admin, draft)
  }

  /**
   * Le esclusioni dell'area comune nella configurazione firmata (verifica funzionale, ON-8): il passo 5 ripreso dopo la
   * fondazione cambiava solo l'avanzamento locale, e la scansione (che usa quelle firmate) non lo vedeva. Stesse esclusioni
   * di prima: niente da firmare.
   */
  suspend fun updateCommonExclusions(excluded: Set<String>): AdminActionResult {
    val sorted = excluded.sorted()
    return amendConfig { it.copy(commonExclusions = sorted) }
  }

  /**
   * L'indirizzo del NAS nella configurazione firmata (verifica funzionale, RE-5): prima l'admin lo cambiava solo sul suo telefono e il
   * QR d'invito portava ancora l'IP vecchio. L'identita' del NAS (GUID) non cambia: l'ha gia' verificata la pagina che ha chiesto la
   * modifica, e il GUID firmato resta quello di prima.
   */
  suspend fun updateNasAddress(ip: String): AdminActionResult {
    val host = FamilyConfigFactory.hostOf(ip)
    return amendConfig { it.copy(nasHost = host) }
  }

  /**
   * I prefissi ULA di casa nella configurazione firmata (RE-5, E-R16). Come al passo 12 della fondazione: solo i /48 ULA nella forma del
   * formato, senza ripetizioni, in ordine, al massimo [TrustLimits.MAX_HOME_ULA]. Nessun prefisso valido = nessuna modifica (un invito
   * senza ULA non e' valido).
   */
  suspend fun updateHomeUla(prefixes: Set<String>): AdminActionResult {
    val canonical = prefixes.mapNotNull(UlaPrefix::canonical).distinct().sorted().take(TrustLimits.MAX_HOME_ULA)
    if (canonical.isEmpty()) return AdminActionResult.Failed(NasError.PermissionDenied)
    return amendConfig { it.copy(homeUla = canonical) }
  }

  /**
   * Una modifica alla configurazione firmata, con lo stesso controllo dell'admin di ogni azione (dispositivo approvato, chiave di
   * famiglia nella cassaforte a telefono sbloccato, M3). La stessa configurazione di prima non firma niente.
   */
  private suspend fun amendConfig(change: (FamilyConfig) -> FamilyConfig): AdminActionResult {
    val admin = adminContext() ?: return AdminActionResult.NotAdmin
    val config = admin.state.registry.config
    val draft = try {
      val changed = change(config)
      if (changed == config) return AdminActionResult.Done
      admin.authority.updateConfig(admin.state, changed)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Una configurazione che il formato non accetta (percorsi, indirizzo o tetti): niente da firmare.
      return AdminActionResult.Failed(NasError.PermissionDenied)
    }
    return commit(admin, draft)
  }

  /** La scelta dell'admin sui rami in attesa: [recognize] li unisce, altrimenti li rifiuta (poi reset). */
  suspend fun resolveForks(recognize: Boolean): AdminActionResult {
    val pending = _pendingForks.value ?: return AdminActionResult.Done
    val admin = adminContext() ?: return AdminActionResult.NotAdmin
    if (admin.state.contentHash != pending.draft.base.contentHash) {
      _pendingForks.value = null
      return AdminActionResult.NewerExists
    }
    val joined = try {
      if (recognize) admin.authority.mergeForks(pending.draft, pending.forks) else admin.authority.rejectForks(pending.draft, pending.forks)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return AdminActionResult.Fork
    }
    return commit(admin, joined)
  }

  /**
   * La versione fidata e' stata sostituita con spazzatura sul NAS (revisione 1.4, N3): l'admin ripubblica lo
   * stesso contenuto con un numero nuovo. Si fa da sola quando l'allarme compare e la cassaforte si apre.
   */
  suspend fun reissue(): AdminActionResult {
    val admin = adminContext() ?: return AdminActionResult.NotAdmin
    return commit(admin, admin.authority.reissue(admin.state))
  }

  private companion object {
    const val TAG = "RegistryWriter"
  }
}
