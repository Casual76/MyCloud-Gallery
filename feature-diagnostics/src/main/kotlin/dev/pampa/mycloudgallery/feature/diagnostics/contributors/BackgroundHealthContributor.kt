package dev.pampa.mycloudgallery.feature.diagnostics.contributors

import android.app.KeyguardManager
import android.content.Context
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagText
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.rules.platform.health.BackgroundHealth
import dev.pampa.mycloudgallery.core.rules.platform.health.BackgroundHealthReader
import dev.pampa.mycloudgallery.core.rules.platform.health.ExitReasonKind
import dev.pampa.mycloudgallery.core.rules.platform.health.HealthPermission
import dev.pampa.mycloudgallery.core.rules.platform.health.PermissionGrant
import dev.pampa.mycloudgallery.core.rules.platform.health.StandbyBucket
import dev.pampa.mycloudgallery.core.rules.platform.health.UnusedAppRestrictions
import dev.pampa.mycloudgallery.feature.diagnostics.R
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * L'ultimo battito del lavoro in background di questo profilo (ora del dispositivo, ms dall'epoca), o null se
 * ancora non c'e'. Lo fornisce `:app` (P9: l'ultimo `TrustSyncWorker` riuscito o, piu' avanti, l'ultimo successo
 * di un'attivita'): la Diagnostica lo incrocia con i segnali di sistema (docs/android.md 3.3, punto 2).
 */
fun interface HeartbeatSource {
  suspend fun lastBeatAt(): Long?
}

/** Se il telefono ha un blocco schermo (PIN, sequenza, impronta): senza, chi lo trova usa l'app (E-S3). */
fun interface DeviceSecuritySource {
  fun hasScreenLock(): Boolean?
}

/**
 * Il blocco schermo vero: `KeyguardManager.isDeviceSecure` (PIN, sequenza, password o biometria impostati), senza
 * permessi. Null se Android non risponde: l'avviso sul blocco mancante non si inventa.
 */
class AndroidDeviceSecuritySource(context: Context) : DeviceSecuritySource {

  private val app: Context = context.applicationContext ?: context

  override fun hasScreenLock(): Boolean? = try {
    app.getSystemService(KeyguardManager::class.java)?.isDeviceSecure
  } catch (_: RuntimeException) {
    null
  }
}

/**
 * «Avvisi di Android» e «Profili» (docs/fase2-progetto.md §2.6, righe P8): cio' che Android sta facendo al lavoro
 * dell'app, letto da [BackgroundHealthReader] e tradotto in voci con la loro azione.
 *
 * **Un solo contributore per le due sezioni**, perche' partono dalla stessa lettura (sei servizi di sistema: farla
 * due volte per ogni aggiornamento sarebbe uno spreco). Le voci portano ciascuna la propria sezione.
 *
 * **Niente supposizioni.** Un dato che il sistema non ha dato (`null`, `UNKNOWN`) non genera ne' un avviso ne' un
 * «tutto bene»: la voce manca. «Pausa se inutilizzata» non disponibile su questo telefono non e' un problema.
 *
 * @param nowMillis l'ora del dispositivo (ms dall'epoca): per l'eta' delle uscite del processo e dell'ultimo battito.
 * @param uptimeMillis i millisecondi dall'ultimo avvio (`SystemClock.elapsedRealtime`): un riavvio recente spiega
 *   i ritardi (E-P13, E-P4).
 */
class BackgroundHealthContributor(
  private val reader: BackgroundHealthReader,
  private val security: DeviceSecuritySource? = null,
  private val heartbeat: HeartbeatSource? = null,
  private val nowMillis: () -> Long = System::currentTimeMillis,
  private val uptimeMillis: () -> Long = { Long.MAX_VALUE },
) : DiagnosticsContributor {

  override val section: DiagSection = DiagSection.ANDROID_WARNINGS

  override suspend fun collect(): List<DiagItem> {
    val health = reader.read()
    val now = nowMillis()
    val beatAgeMs = heartbeat?.lastBeatAt()?.let { (now - it).coerceAtLeast(0L) }
    return buildList {
      addAll(batteryItems(health))
      addAll(permissionItems(health))
      addAll(systemItems(health))
      addAll(exitItems(health, now))
      addAll(stalledItems(health, beatAgeMs))
      addAll(profileItems(health, beatAgeMs))
    }
  }

  // --- Batteria, bucket, limitazione, ibernazione ------------------------------------------------------------

  private fun batteryItems(health: BackgroundHealth): List<DiagItem> = buildList {
    when (health.ignoringBatteryOpt) {
      true -> add(item(DiagCodes.AND_BATTERY_UNRESTRICTED, DiagSeverity.OK, "ignoringBatteryOpt" to ExportValue.Flag(true)))
      false -> add(item(DiagCodes.AND_BATTERY_OPTIMIZED, DiagSeverity.WARNING, "ignoringBatteryOpt" to ExportValue.Flag(false)))
      null -> Unit
    }
    val bucket = ExportValue.Enum.of(health.standbyBucket)
    when (health.standbyBucket) {
      StandbyBucket.ACTIVE, StandbyBucket.WORKING_SET, StandbyBucket.FREQUENT ->
        add(item(DiagCodes.AND_BUCKET_OK, DiagSeverity.OK, "standbyBucket" to bucket))
      StandbyBucket.RARE -> add(item(DiagCodes.AND_BUCKET_RARE, DiagSeverity.INFO, "standbyBucket" to bucket))
      StandbyBucket.RESTRICTED -> add(item(DiagCodes.AND_BUCKET_RESTRICTED, DiagSeverity.WARNING, "standbyBucket" to bucket))
      StandbyBucket.UNKNOWN -> Unit
    }
    if (health.backgroundRestricted == true) {
      add(item(DiagCodes.AND_BACKGROUND_RESTRICTED, DiagSeverity.ERROR, "backgroundRestricted" to ExportValue.Flag(true)))
    }
    val hibernation = ExportValue.Enum.of(health.unusedAppRestrictions)
    when (health.unusedAppRestrictions) {
      UnusedAppRestrictions.ENABLED -> add(item(DiagCodes.AND_HIBERNATION_ON, DiagSeverity.WARNING, "unusedAppRestrictions" to hibernation))
      UnusedAppRestrictions.DISABLED -> add(item(DiagCodes.AND_HIBERNATION_OFF, DiagSeverity.OK, "unusedAppRestrictions" to hibernation))
      UnusedAppRestrictions.NOT_AVAILABLE, UnusedAppRestrictions.UNKNOWN -> Unit
    }
    if (security?.hasScreenLock() == false) {
      add(item(DiagCodes.AND_NO_SCREEN_LOCK, DiagSeverity.WARNING, "screenLock" to ExportValue.Flag(false)))
    }
  }

  // --- Permessi ------------------------------------------------------------------------------------------------

  private fun permissionItems(health: BackgroundHealth): List<DiagItem> = buildList {
    fun grant(permission: HealthPermission) = health.permissions[permission] ?: PermissionGrant.NOT_APPLICABLE

    if (grant(HealthPermission.NOTIFICATIONS) == PermissionGrant.DENIED) {
      add(item(DiagCodes.AND_NOTIFICATIONS_DENIED, DiagSeverity.WARNING, "notifications" to ExportValue.Flag(false)))
    }

    // Foto e video. Con «Foto selezionate» (Android 14) il sistema concede VISUAL_USER_SELECTED e nega i due
    // READ_MEDIA_*: e' la modalita' limitata (E-P1), non un rifiuto. Il resto e' un permesso negato o ritirato
    // dal sistema (E-P15): backup e indicizzazione in pausa, mai file «cancellati».
    val images = grant(HealthPermission.MEDIA_IMAGES)
    val video = grant(HealthPermission.MEDIA_VIDEO)
    val partial = grant(HealthPermission.MEDIA_PARTIAL)
    val mediaDenied = images == PermissionGrant.DENIED || video == PermissionGrant.DENIED
    if (mediaDenied) {
      val values = arrayOf<Pair<String, ExportValue>>(
        "mediaImages" to ExportValue.Enum.of(images),
        "mediaVideo" to ExportValue.Enum.of(video),
        "mediaPartial" to ExportValue.Enum.of(partial),
      )
      if (partial == PermissionGrant.GRANTED) {
        add(item(DiagCodes.AND_MEDIA_PARTIAL, DiagSeverity.WARNING, *values))
      } else {
        add(item(DiagCodes.AND_MEDIA_DENIED, DiagSeverity.ERROR, *values))
      }
    }
    if (grant(HealthPermission.MEDIA_LOCATION) == PermissionGrant.DENIED) {
      add(item(DiagCodes.AND_MEDIA_LOCATION_DENIED, DiagSeverity.WARNING, "mediaLocation" to ExportValue.Flag(false)))
    }
    if (grant(HealthPermission.CAMERA) == PermissionGrant.DENIED) {
      add(item(DiagCodes.AND_CAMERA_DENIED, DiagSeverity.INFO, "camera" to ExportValue.Flag(false)))
    }
  }

  // --- Sblocco e riavvii -------------------------------------------------------------------------------------------

  private fun systemItems(health: BackgroundHealth): List<DiagItem> = buildList {
    if (!health.userUnlocked) {
      add(item(DiagCodes.AND_USER_LOCKED, DiagSeverity.WARNING, "userUnlocked" to ExportValue.Flag(false)))
    }
    val uptime = uptimeMillis()
    if (uptime in 0 until RECENT_REBOOT_WINDOW.inWholeMilliseconds) {
      val values = mutableListOf<Pair<String, ExportValue>>("uptime" to ExportValue.Dur(uptime))
      health.bootCount?.let { values += "bootCount" to ExportValue.Num(it.toLong()) }
      add(
        item(
          DiagCodes.AND_RECENT_REBOOT,
          DiagSeverity.INFO,
          *values.toTypedArray(),
          detail = DiagText(R.string.diagnostics_uptime_hours, listOf(uptime / HOUR_MS)),
        ),
      )
    }
  }

  // --- Uscite del processo -----------------------------------------------------------------------------------------

  private fun exitItems(health: BackgroundHealth, now: Long): List<DiagItem> {
    val recent = health.lastExitReasons.filter { now - it.atMillis in 0..EXIT_WINDOW.inWholeMilliseconds }
    // Una voce per ogni tipo notevole, con quante volte e quanto tempo fa l'ultima. SIGNALED, DEPENDENCY_DIED,
    // EXIT_SELF, USER_STOPPED e il resto non dicono nulla che l'utente possa correggere: non si mostrano.
    return EXIT_GROUPS.mapNotNull { (code, severity, kinds) ->
      val matching = recent.filter { it.reason in kinds }
      if (matching.isEmpty()) return@mapNotNull null
      val lastAge = matching.minOf { now - it.atMillis }.coerceAtLeast(0L)
      item(
        code,
        severity,
        "exitCount" to ExportValue.Num(matching.size.toLong()),
        "lastExitAge" to ExportValue.Dur(lastAge),
        detail = DiagText(R.string.diagnostics_exit_count, listOf(matching.size)),
      )
    }
  }

  // --- Battito e profili ----------------------------------------------------------------------------------------------

  /** Il lavoro in background fermo da giorni su un profilo principale (E-P15). */
  private fun stalledItems(health: BackgroundHealth, beatAgeMs: Long?): List<DiagItem> {
    if (beatAgeMs == null || !health.isSystemUser || beatAgeMs <= PRIMARY_STALE_AFTER.inWholeMilliseconds) return emptyList()
    return listOf(
      item(
        DiagCodes.AND_BACKGROUND_STALLED,
        DiagSeverity.WARNING,
        "heartbeatAge" to ExportValue.Dur(beatAgeMs),
        detail = DiagText(R.string.diagnostics_heartbeat_hours, listOf(beatAgeMs / HOUR_MS)),
      ),
    )
  }

  private fun profileItems(health: BackgroundHealth, beatAgeMs: Long?): List<DiagItem> = buildList {
    val system = ExportValue.Flag(health.isSystemUser)
    if (health.isSystemUser) {
      add(item(DiagCodes.PROFILE_PRIMARY, DiagSeverity.OK, "isSystemUser" to system, section = DiagSection.PROFILES))
    } else {
      add(item(DiagCodes.PROFILE_SECONDARY, DiagSeverity.WARNING, "isSystemUser" to system, section = DiagSection.PROFILES))
      add(item(DiagCodes.PROFILE_OWN_VPN, DiagSeverity.INFO, "isSystemUser" to system, section = DiagSection.PROFILES))
    }
    if (beatAgeMs != null) {
      val limit = if (health.isSystemUser) PRIMARY_STALE_AFTER else SECONDARY_STALE_AFTER
      val stale = beatAgeMs > limit.inWholeMilliseconds
      add(
        item(
          if (stale) DiagCodes.PROFILE_HEARTBEAT_STALE else DiagCodes.PROFILE_HEARTBEAT_OK,
          if (stale) DiagSeverity.WARNING else DiagSeverity.OK,
          "heartbeatAge" to ExportValue.Dur(beatAgeMs),
          detail = DiagText(R.string.diagnostics_heartbeat_hours, listOf(beatAgeMs / HOUR_MS)),
          section = DiagSection.PROFILES,
        ),
      )
    }
  }

  // --- Costruzione di una voce -------------------------------------------------------------------------------------------

  /**
   * L'azione non si imposta qui: la propone il catalogo ([DiagCatalog.actionFor]), cosi' una voce ha sempre lo stesso
   * «cosa fare» a prescindere da chi la produce.
   */
  private fun item(
    code: DiagCode,
    severity: DiagSeverity,
    vararg values: Pair<String, ExportValue>,
    detail: DiagText? = null,
    section: DiagSection = DiagSection.ANDROID_WARNINGS,
  ): DiagItem = DiagItem(
    code = code,
    section = section,
    severity = severity,
    titleRes = DiagCatalog.titleOf(code),
    detail = detail,
    export = values.toMap(),
  )

  private companion object {
    const val HOUR_MS = 3_600_000L

    /** Un riavvio entro un giorno spiega ancora i ritardi (E-P13). */
    val RECENT_REBOOT_WINDOW: Duration = 24.hours

    /** Le uscite piu' vecchie non dicono piu' nulla su come va adesso. */
    val EXIT_WINDOW: Duration = 14.days

    /** Android considera «sacrificabile» un utente in background da 26 ore (docs/android.md 6.2). */
    val SECONDARY_STALE_AFTER: Duration = 26.hours

    /** Un profilo principale puo' stare giorni senza lavorare solo se qualcosa lo ferma (E-P15). */
    val PRIMARY_STALE_AFTER: Duration = 72.hours

    private data class ExitGroup(val code: DiagCode, val severity: DiagSeverity, val kinds: Set<ExitReasonKind>)

    private val EXIT_GROUPS = listOf(
      ExitGroup(
        DiagCodes.AND_EXIT_CRASH,
        DiagSeverity.ERROR,
        setOf(ExitReasonKind.CRASH, ExitReasonKind.CRASH_NATIVE, ExitReasonKind.INITIALIZATION_FAILURE),
      ),
      ExitGroup(DiagCodes.AND_EXIT_ANR, DiagSeverity.ERROR, setOf(ExitReasonKind.ANR)),
      ExitGroup(
        DiagCodes.AND_EXIT_MEMORY,
        DiagSeverity.WARNING,
        setOf(ExitReasonKind.LOW_MEMORY, ExitReasonKind.EXCESSIVE_RESOURCE_USAGE),
      ),
      ExitGroup(DiagCodes.AND_EXIT_FORCE_STOP, DiagSeverity.WARNING, setOf(ExitReasonKind.USER_REQUESTED)),
      ExitGroup(DiagCodes.AND_EXIT_FREEZER, DiagSeverity.INFO, setOf(ExitReasonKind.FREEZER)),
      ExitGroup(DiagCodes.AND_EXIT_PERMISSION_CHANGE, DiagSeverity.INFO, setOf(ExitReasonKind.PERMISSION_CHANGE)),
    )
  }
}
