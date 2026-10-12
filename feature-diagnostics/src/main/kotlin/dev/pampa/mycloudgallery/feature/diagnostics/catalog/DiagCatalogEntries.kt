package dev.pampa.mycloudgallery.feature.diagnostics.catalog

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagAction
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.feature.diagnostics.R
import dev.pampa.mycloudgallery.feature.diagnostics.actions.DiagnosticsActions

/**
 * Le voci del catalogo: per ogni codice la sezione, la gravita' **tipica** (chi contribuisce puo' modularla
 * caso per caso), il titolo breve, il «cosa fare» e l'azione suggerita.
 *
 * Il «cosa fare» c'e' per ogni voce che e' un avviso o un errore (lo verifica `DiagCatalogTest`): una
 * diagnostica che dice solo «c'e' un problema» e' mezza diagnostica.
 */
internal object DiagCatalogEntries {

  val all: List<DiagEntry> = listOf(

    // --- Rete e NAS --------------------------------------------------------
    DiagEntry(DiagCodes.NET_PLACE, DiagSection.NETWORK_AND_NAS, DiagSeverity.OK, R.string.diag_net_place_title, 0, null),
    DiagEntry(DiagCodes.NET_VPN_ON_AT_HOME, DiagSection.NETWORK_AND_NAS, DiagSeverity.INFO, R.string.diag_net_vpn_on_at_home_title, R.string.diag_net_vpn_on_at_home_guide, DiagAction.OPEN_WIREGUARD),
    DiagEntry(DiagCodes.NET_ROAMING, DiagSection.NETWORK_AND_NAS, DiagSeverity.WARNING, R.string.diag_net_roaming_title, R.string.diag_net_roaming_guide, null),
    DiagEntry(DiagCodes.NET_VPN_OFF_AWAY, DiagSection.NETWORK_AND_NAS, DiagSeverity.WARNING, R.string.diag_net_vpn_off_away_title, R.string.diag_net_vpn_off_away_guide, DiagAction.OPEN_WIREGUARD),
    DiagEntry(DiagCodes.NET_UNTRUSTED, DiagSection.NETWORK_AND_NAS, DiagSeverity.WARNING, R.string.diag_net_untrusted_title, R.string.diag_net_untrusted_guide, null),
    DiagEntry(DiagCodes.NET_FOREIGN_VPN, DiagSection.NETWORK_AND_NAS, DiagSeverity.ERROR, R.string.diag_net_foreign_vpn_title, R.string.diag_net_foreign_vpn_guide, DiagAction.OPEN_WIREGUARD),
    DiagEntry(DiagCodes.NET_OFFLINE, DiagSection.NETWORK_AND_NAS, DiagSeverity.WARNING, R.string.diag_net_offline_title, R.string.diag_net_offline_guide, null),
    DiagEntry(DiagCodes.NET_CAPTIVE_PORTAL, DiagSection.NETWORK_AND_NAS, DiagSeverity.WARNING, R.string.diag_net_captive_portal_title, R.string.diag_net_captive_portal_guide, null),
    DiagEntry(DiagCodes.NET_METERED, DiagSection.NETWORK_AND_NAS, DiagSeverity.INFO, R.string.diag_net_metered_title, R.string.diag_net_metered_guide, null),
    DiagEntry(DiagCodes.NET_POOR, DiagSection.NETWORK_AND_NAS, DiagSeverity.WARNING, R.string.diag_net_poor_title, R.string.diag_net_poor_guide, null),
    DiagEntry(DiagCodes.NET_IPV4_ONLY_VPN, DiagSection.NETWORK_AND_NAS, DiagSeverity.WARNING, R.string.diag_net_ipv4_only_vpn_title, R.string.diag_net_ipv4_only_vpn_guide, null),
    DiagEntry(DiagCodes.NET_HOME_CHANGED, DiagSection.NETWORK_AND_NAS, DiagSeverity.WARNING, R.string.diag_net_home_changed_title, R.string.diag_net_home_changed_guide, null),
    DiagEntry(DiagCodes.NET_NOT_CONFIGURED, DiagSection.NETWORK_AND_NAS, DiagSeverity.INFO, R.string.diag_net_not_configured_title, R.string.diag_net_not_configured_guide, DiagAction.RESUME_ONBOARDING),
    DiagEntry(DiagCodes.NET_LOCAL_NETWORK_DENIED, DiagSection.NETWORK_AND_NAS, DiagSeverity.ERROR, R.string.diag_net_local_network_denied_title, R.string.diag_net_local_network_denied_guide, DiagAction.OPEN_APP_DETAILS),
    DiagEntry(DiagCodes.VPN_WIREGUARD_MISSING, DiagSection.NETWORK_AND_NAS, DiagSeverity.INFO, R.string.diag_vpn_wireguard_missing_title, R.string.diag_vpn_wireguard_missing_guide, null),
    DiagEntry(DiagCodes.VPN_PERMISSION_MISSING, DiagSection.NETWORK_AND_NAS, DiagSeverity.WARNING, R.string.diag_vpn_permission_missing_title, R.string.diag_vpn_permission_missing_guide, DiagAction.OPEN_WIREGUARD),
    DiagEntry(DiagCodes.VPN_NOT_CONFIGURED, DiagSection.NETWORK_AND_NAS, DiagSeverity.INFO, R.string.diag_vpn_not_configured_title, R.string.diag_vpn_not_configured_guide, DiagAction.RESUME_ONBOARDING),
    DiagEntry(DiagCodes.VPN_NOT_CONTROLLABLE, DiagSection.NETWORK_AND_NAS, DiagSeverity.WARNING, R.string.diag_vpn_not_controllable_title, R.string.diag_vpn_not_controllable_guide, DiagAction.OPEN_WIREGUARD),
    DiagEntry(DiagCodes.VPN_LOCKDOWN_SUSPECTED, DiagSection.NETWORK_AND_NAS, DiagSeverity.WARNING, R.string.diag_vpn_lockdown_suspected_title, R.string.diag_vpn_lockdown_suspected_guide, DiagAction.OPEN_WIREGUARD),
    DiagEntry(DiagCodes.WOL_NO_EFFECT, DiagSection.NETWORK_AND_NAS, DiagSeverity.INFO, R.string.diag_wol_no_effect_title, R.string.diag_wol_no_effect_guide, null),
    DiagEntry(DiagCodes.NAS_SESSION_OK, DiagSection.NETWORK_AND_NAS, DiagSeverity.OK, R.string.diag_nas_session_ok_title, 0, null),
    DiagEntry(DiagCodes.NAS_AUTH_BLOCKED, DiagSection.NETWORK_AND_NAS, DiagSeverity.ERROR, R.string.diag_nas_auth_blocked_title, R.string.diag_nas_auth_blocked_guide, DiagAction.REENTER_NAS_PASSWORD),
    DiagEntry(DiagCodes.NAS_AUTH_FAILED, DiagSection.NETWORK_AND_NAS, DiagSeverity.ERROR, R.string.diag_nas_auth_failed_title, R.string.diag_nas_auth_failed_guide, DiagAction.REENTER_NAS_PASSWORD),
    DiagEntry(DiagCodes.NAS_ACCOUNT_LOCKED, DiagSection.NETWORK_AND_NAS, DiagSeverity.ERROR, R.string.diag_nas_account_locked_title, R.string.diag_nas_account_locked_guide, DiagAction.REENTER_NAS_PASSWORD),
    DiagEntry(DiagCodes.NAS_ACCOUNT_DISABLED, DiagSection.NETWORK_AND_NAS, DiagSeverity.ERROR, R.string.diag_nas_account_disabled_title, R.string.diag_nas_account_disabled_guide, null),
    DiagEntry(DiagCodes.NAS_PASSWORD_EXPIRED, DiagSection.NETWORK_AND_NAS, DiagSeverity.ERROR, R.string.diag_nas_password_expired_title, R.string.diag_nas_password_expired_guide, DiagAction.REENTER_NAS_PASSWORD),
    DiagEntry(DiagCodes.NAS_ACCOUNT_RESTRICTED, DiagSection.NETWORK_AND_NAS, DiagSeverity.ERROR, R.string.diag_nas_account_restricted_title, R.string.diag_nas_account_restricted_guide, null),
    DiagEntry(DiagCodes.NAS_GUEST_REFUSED, DiagSection.NETWORK_AND_NAS, DiagSeverity.ERROR, R.string.diag_nas_guest_refused_title, R.string.diag_nas_guest_refused_guide, DiagAction.RUN_CONNECTION_TEST),
    DiagEntry(DiagCodes.NAS_NOT_ENCRYPTED, DiagSection.NETWORK_AND_NAS, DiagSeverity.ERROR, R.string.diag_nas_not_encrypted_title, R.string.diag_nas_not_encrypted_guide, DiagAction.RUN_CONNECTION_TEST),
    DiagEntry(DiagCodes.NAS_DIALECT_REFUSED, DiagSection.NETWORK_AND_NAS, DiagSeverity.ERROR, R.string.diag_nas_dialect_refused_title, R.string.diag_nas_dialect_refused_guide, DiagAction.RUN_CONNECTION_TEST),
    DiagEntry(DiagCodes.NAS_SIGNING_MISSING, DiagSection.NETWORK_AND_NAS, DiagSeverity.ERROR, R.string.diag_nas_signing_missing_title, R.string.diag_nas_signing_missing_guide, DiagAction.RUN_CONNECTION_TEST),
    DiagEntry(DiagCodes.NAS_IDENTITY_MISMATCH, DiagSection.NETWORK_AND_NAS, DiagSeverity.ERROR, R.string.diag_nas_identity_mismatch_title, R.string.diag_nas_identity_mismatch_guide, null),
    DiagEntry(DiagCodes.NAS_ADDRESS_CHANGED, DiagSection.NETWORK_AND_NAS, DiagSeverity.WARNING, R.string.diag_nas_address_changed_title, R.string.diag_nas_address_changed_guide, null),
    DiagEntry(DiagCodes.NAS_PERMISSION_DENIED, DiagSection.NETWORK_AND_NAS, DiagSeverity.WARNING, R.string.diag_nas_permission_denied_title, R.string.diag_nas_permission_denied_guide, null),
    DiagEntry(DiagCodes.NAS_SHARE_MISSING, DiagSection.NETWORK_AND_NAS, DiagSeverity.WARNING, R.string.diag_nas_share_missing_title, R.string.diag_nas_share_missing_guide, null),
    DiagEntry(DiagCodes.NAS_QUOTA_EXCEEDED, DiagSection.NETWORK_AND_NAS, DiagSeverity.WARNING, R.string.diag_nas_quota_exceeded_title, R.string.diag_nas_quota_exceeded_guide, null),
    DiagEntry(DiagCodes.NAS_FULL, DiagSection.NETWORK_AND_NAS, DiagSeverity.WARNING, R.string.diag_nas_full_title, R.string.diag_nas_full_guide, null),
    DiagEntry(DiagCodes.NAS_FILE_BUSY, DiagSection.NETWORK_AND_NAS, DiagSeverity.INFO, R.string.diag_nas_file_busy_title, R.string.diag_nas_file_busy_guide, null),
    DiagEntry(DiagCodes.NAS_SERVICE_DOWN, DiagSection.NETWORK_AND_NAS, DiagSeverity.WARNING, R.string.diag_nas_service_down_title, R.string.diag_nas_service_down_guide, DiagAction.RUN_CONNECTION_TEST),
    DiagEntry(DiagCodes.NAS_TIMEOUT, DiagSection.NETWORK_AND_NAS, DiagSeverity.WARNING, R.string.diag_nas_timeout_title, R.string.diag_nas_timeout_guide, DiagAction.RUN_CONNECTION_TEST),
    DiagEntry(DiagCodes.NAS_WAKING_UP, DiagSection.NETWORK_AND_NAS, DiagSeverity.INFO, R.string.diag_nas_waking_up_title, 0, null),
    DiagEntry(DiagCodes.NAS_NOT_ALLOWED_HERE, DiagSection.NETWORK_AND_NAS, DiagSeverity.WARNING, R.string.diag_nas_not_allowed_here_title, R.string.diag_nas_not_allowed_here_guide, null),
    DiagEntry(DiagCodes.NAS_STALLED, DiagSection.NETWORK_AND_NAS, DiagSeverity.WARNING, R.string.diag_nas_stalled_title, R.string.diag_nas_stalled_guide, null),
    DiagEntry(DiagCodes.NAS_CREDENTIALS_MISSING, DiagSection.NETWORK_AND_NAS, DiagSeverity.ERROR, R.string.diag_nas_credentials_missing_title, R.string.diag_nas_credentials_missing_guide, DiagAction.REENTER_NAS_PASSWORD),
    DiagEntry(DiagCodes.NAS_NOT_FOUND, DiagSection.NETWORK_AND_NAS, DiagSeverity.INFO, R.string.diag_nas_not_found_title, 0, null),
    DiagEntry(DiagCodes.NAS_SESSION_EXPIRED, DiagSection.NETWORK_AND_NAS, DiagSeverity.INFO, R.string.diag_nas_session_expired_title, 0, null),
    DiagEntry(DiagCodes.NAS_UNEXPECTED, DiagSection.NETWORK_AND_NAS, DiagSeverity.WARNING, R.string.diag_nas_unexpected_title, R.string.diag_nas_unexpected_guide, DiagAction.RUN_CONNECTION_TEST),
    DiagEntry(DiagCodes.NAS_CLOCK_SKEW, DiagSection.NETWORK_AND_NAS, DiagSeverity.INFO, R.string.diag_nas_clock_skew_title, R.string.diag_nas_clock_skew_guide, null),
    DiagEntry(DiagCodes.NAS_NO_SHARES, DiagSection.NETWORK_AND_NAS, DiagSeverity.WARNING, R.string.diag_nas_no_shares_title, R.string.diag_nas_no_shares_guide, null),
    DiagEntry(DiagCodes.NAS_COMMON_AREA_UNWRITABLE, DiagSection.NETWORK_AND_NAS, DiagSeverity.WARNING, R.string.diag_nas_common_area_unwritable_title, R.string.diag_nas_common_area_unwritable_guide, null),

    // --- Test di connessione -----------------------------------------------
    DiagEntry(DiagCodes.TEST_STAGE, DiagSection.CONNECTION_TEST, DiagSeverity.INFO, R.string.diag_test_stage_title, 0, null),
    DiagEntry(DiagCodes.TEST_PASSED, DiagSection.CONNECTION_TEST, DiagSeverity.OK, R.string.diag_test_passed_title, 0, null),
    DiagEntry(DiagCodes.TEST_FAILED, DiagSection.CONNECTION_TEST, DiagSeverity.ERROR, R.string.diag_test_failed_title, R.string.diag_test_failed_guide, DiagAction.RUN_CONNECTION_TEST),
    DiagEntry(DiagCodes.TEST_NOT_CONFIGURED, DiagSection.CONNECTION_TEST, DiagSeverity.INFO, R.string.diag_test_not_configured_title, R.string.diag_test_not_configured_guide, DiagAction.RESUME_ONBOARDING),
    DiagEntry(DiagCodes.TEST_AUTH_SKIPPED, DiagSection.CONNECTION_TEST, DiagSeverity.WARNING, R.string.diag_test_auth_skipped_title, R.string.diag_test_auth_skipped_guide, DiagAction.REENTER_NAS_PASSWORD),

    // --- Attività ----------------------------------------------------------
    DiagEntry(DiagCodes.ACT_RUNNING, DiagSection.ACTIVITIES, DiagSeverity.OK, R.string.diag_act_running_title, 0, null),
    DiagEntry(DiagCodes.ACT_LOCAL_BUFFER_ONLY, DiagSection.ACTIVITIES, DiagSeverity.INFO, R.string.diag_act_local_buffer_only_title, R.string.diag_act_local_buffer_only_guide, null),
    DiagEntry(DiagCodes.ACT_REDUCED_PACE, DiagSection.ACTIVITIES, DiagSeverity.INFO, R.string.diag_act_reduced_pace_title, 0, null),
    DiagEntry(DiagCodes.ACT_PROFILE_INACTIVE, DiagSection.ACTIVITIES, DiagSeverity.INFO, R.string.diag_act_profile_inactive_title, R.string.diag_act_profile_inactive_guide, null),
    DiagEntry(DiagCodes.ACT_ROLE_DISABLED, DiagSection.ACTIVITIES, DiagSeverity.INFO, R.string.diag_act_role_disabled_title, 0, null),
    DiagEntry(DiagCodes.ACT_DISABLED, DiagSection.ACTIVITIES, DiagSeverity.INFO, R.string.diag_act_disabled_title, 0, null),
    DiagEntry(DiagCodes.ACT_APP_NOT_VISIBLE, DiagSection.ACTIVITIES, DiagSeverity.INFO, R.string.diag_act_app_not_visible_title, 0, null),
    DiagEntry(DiagCodes.ACT_ROAMING, DiagSection.ACTIVITIES, DiagSeverity.WARNING, R.string.diag_act_roaming_title, R.string.diag_act_roaming_guide, null),
    DiagEntry(DiagCodes.ACT_OFFLINE, DiagSection.ACTIVITIES, DiagSeverity.WARNING, R.string.diag_act_offline_title, R.string.diag_act_offline_guide, null),
    DiagEntry(DiagCodes.ACT_NETWORK_NOT_TRUSTED, DiagSection.ACTIVITIES, DiagSeverity.WARNING, R.string.diag_act_network_not_trusted_title, R.string.diag_act_network_not_trusted_guide, null),
    DiagEntry(DiagCodes.ACT_POOR_NETWORK, DiagSection.ACTIVITIES, DiagSeverity.WARNING, R.string.diag_act_poor_network_title, R.string.diag_act_poor_network_guide, null),
    DiagEntry(DiagCodes.ACT_OUTSIDE_WINDOW, DiagSection.ACTIVITIES, DiagSeverity.INFO, R.string.diag_act_outside_window_title, R.string.diag_act_outside_window_guide, null),
    DiagEntry(DiagCodes.ACT_NEEDS_POWER, DiagSection.ACTIVITIES, DiagSeverity.INFO, R.string.diag_act_needs_power_title, R.string.diag_act_needs_power_guide, null),
    DiagEntry(DiagCodes.ACT_BATTERY_LOW, DiagSection.ACTIVITIES, DiagSeverity.INFO, R.string.diag_act_battery_low_title, 0, null),
    DiagEntry(DiagCodes.ACT_POWER_SAVER, DiagSection.ACTIVITIES, DiagSeverity.WARNING, R.string.diag_act_power_saver_title, R.string.diag_act_power_saver_guide, null),
    DiagEntry(DiagCodes.ACT_THERMAL, DiagSection.ACTIVITIES, DiagSeverity.WARNING, R.string.diag_act_thermal_title, R.string.diag_act_thermal_guide, null),
    DiagEntry(DiagCodes.ACT_NETWORK_NOT_ALLOWED, DiagSection.ACTIVITIES, DiagSeverity.INFO, R.string.diag_act_network_not_allowed_title, 0, null),
    DiagEntry(DiagCodes.ACT_HEAVY_NEEDS_HOME, DiagSection.ACTIVITIES, DiagSeverity.INFO, R.string.diag_act_heavy_needs_home_title, 0, null),
    DiagEntry(DiagCodes.ACT_CAP_REACHED, DiagSection.ACTIVITIES, DiagSeverity.WARNING, R.string.diag_act_cap_reached_title, R.string.diag_act_cap_reached_guide, null),
    DiagEntry(DiagCodes.ACT_ANDROID_LIMIT, DiagSection.ACTIVITIES, DiagSeverity.WARNING, R.string.diag_act_android_limit_title, R.string.diag_act_android_limit_guide, DiagAction.OPEN_BATTERY_SETTINGS),
    DiagEntry(DiagCodes.ACT_NAS_UNREACHABLE, DiagSection.ACTIVITIES, DiagSeverity.WARNING, R.string.diag_act_nas_unreachable_title, R.string.diag_act_nas_unreachable_guide, DiagAction.RUN_CONNECTION_TEST),
    DiagEntry(DiagCodes.ACT_CONFIRMATION_REQUIRED, DiagSection.ACTIVITIES, DiagSeverity.INFO, R.string.diag_act_confirmation_required_title, 0, null),
    DiagEntry(DiagCodes.ACT_JOB_STOPPED, DiagSection.ACTIVITIES, DiagSeverity.INFO, R.string.diag_act_job_stopped_title, R.string.diag_act_job_stopped_guide, null),

    // --- Ultimi esiti ed errori --------------------------------------------
    DiagEntry(DiagCodes.EVT_GENERIC, DiagSection.RECENT_EVENTS, DiagSeverity.INFO, R.string.diag_evt_generic_title, 0, null),
    DiagEntry(DiagCodes.EVT_QR_REJECTED, DiagSection.RECENT_EVENTS, DiagSeverity.WARNING, R.string.diag_evt_qr_rejected_title, R.string.diag_evt_qr_rejected_guide, null),
    DiagEntry(DiagCodes.EVT_CONFIG_SUPERSEDED, DiagSection.RECENT_EVENTS, DiagSeverity.INFO, R.string.diag_evt_config_superseded_title, R.string.diag_evt_config_superseded_guide, null),
    DiagEntry(DiagCodes.EVT_SELF_WIPE, DiagSection.RECENT_EVENTS, DiagSeverity.WARNING, R.string.diag_evt_self_wipe_title, R.string.diag_evt_self_wipe_guide, null),

    // --- Spazio ------------------------------------------------------------
    DiagEntry(DiagCodes.STORAGE_USAGE, DiagSection.STORAGE, DiagSeverity.INFO, R.string.diag_storage_usage_title, 0, null),
    DiagEntry(DiagCodes.STORAGE_PHONE_LOW, DiagSection.STORAGE, DiagSeverity.WARNING, R.string.diag_storage_phone_low_title, R.string.diag_storage_phone_low_guide, null),

    // --- Consumi del mese --------------------------------------------------
    DiagEntry(DiagCodes.TRAFFIC_MONTH, DiagSection.MONTHLY_TRAFFIC, DiagSeverity.INFO, R.string.diag_traffic_month_title, 0, null),
    DiagEntry(DiagCodes.TRAFFIC_ACTIVITY, DiagSection.MONTHLY_TRAFFIC, DiagSeverity.INFO, R.string.diag_traffic_activity_title, 0, null),
    DiagEntry(DiagCodes.TRAFFIC_ROAMING, DiagSection.MONTHLY_TRAFFIC, DiagSeverity.WARNING, R.string.diag_traffic_roaming_title, R.string.diag_traffic_roaming_guide, null),

    // --- Avvisi di Android -------------------------------------------------
    DiagEntry(DiagCodes.AND_BATTERY_UNRESTRICTED, DiagSection.ANDROID_WARNINGS, DiagSeverity.OK, R.string.diag_and_battery_unrestricted_title, 0, null),
    DiagEntry(DiagCodes.AND_BATTERY_OPTIMIZED, DiagSection.ANDROID_WARNINGS, DiagSeverity.WARNING, R.string.diag_and_battery_optimized_title, R.string.diag_and_battery_optimized_guide, DiagAction.OPEN_BATTERY_SETTINGS),
    DiagEntry(DiagCodes.AND_BUCKET_OK, DiagSection.ANDROID_WARNINGS, DiagSeverity.OK, R.string.diag_and_bucket_ok_title, 0, null),
    DiagEntry(DiagCodes.AND_BUCKET_RARE, DiagSection.ANDROID_WARNINGS, DiagSeverity.INFO, R.string.diag_and_bucket_rare_title, R.string.diag_and_bucket_rare_guide, null),
    DiagEntry(DiagCodes.AND_BUCKET_RESTRICTED, DiagSection.ANDROID_WARNINGS, DiagSeverity.WARNING, R.string.diag_and_bucket_restricted_title, R.string.diag_and_bucket_restricted_guide, DiagnosticsActions.OPEN_NEVER_SLEEPING_APPS),
    DiagEntry(DiagCodes.AND_BACKGROUND_RESTRICTED, DiagSection.ANDROID_WARNINGS, DiagSeverity.ERROR, R.string.diag_and_background_restricted_title, R.string.diag_and_background_restricted_guide, DiagAction.OPEN_BATTERY_SETTINGS),
    DiagEntry(DiagCodes.AND_HIBERNATION_ON, DiagSection.ANDROID_WARNINGS, DiagSeverity.WARNING, R.string.diag_and_hibernation_on_title, R.string.diag_and_hibernation_on_guide, DiagnosticsActions.OPEN_UNUSED_APP_SETTINGS),
    DiagEntry(DiagCodes.AND_HIBERNATION_OFF, DiagSection.ANDROID_WARNINGS, DiagSeverity.OK, R.string.diag_and_hibernation_off_title, 0, null),
    DiagEntry(DiagCodes.AND_NOTIFICATIONS_DENIED, DiagSection.ANDROID_WARNINGS, DiagSeverity.WARNING, R.string.diag_and_notifications_denied_title, R.string.diag_and_notifications_denied_guide, DiagAction.OPEN_NOTIFICATION_SETTINGS),
    DiagEntry(DiagCodes.AND_MEDIA_DENIED, DiagSection.ANDROID_WARNINGS, DiagSeverity.ERROR, R.string.diag_and_media_denied_title, R.string.diag_and_media_denied_guide, DiagAction.OPEN_APP_DETAILS),
    DiagEntry(DiagCodes.AND_MEDIA_PARTIAL, DiagSection.ANDROID_WARNINGS, DiagSeverity.WARNING, R.string.diag_and_media_partial_title, R.string.diag_and_media_partial_guide, DiagAction.OPEN_APP_DETAILS),
    DiagEntry(DiagCodes.AND_MEDIA_LOCATION_DENIED, DiagSection.ANDROID_WARNINGS, DiagSeverity.WARNING, R.string.diag_and_media_location_denied_title, R.string.diag_and_media_location_denied_guide, DiagAction.OPEN_APP_DETAILS),
    DiagEntry(DiagCodes.AND_CAMERA_DENIED, DiagSection.ANDROID_WARNINGS, DiagSeverity.INFO, R.string.diag_and_camera_denied_title, R.string.diag_and_camera_denied_guide, DiagAction.OPEN_APP_DETAILS),
    DiagEntry(DiagCodes.AND_NO_SCREEN_LOCK, DiagSection.ANDROID_WARNINGS, DiagSeverity.WARNING, R.string.diag_and_no_screen_lock_title, R.string.diag_and_no_screen_lock_guide, DiagnosticsActions.OPEN_SECURITY_SETTINGS),
    DiagEntry(DiagCodes.AND_USER_LOCKED, DiagSection.ANDROID_WARNINGS, DiagSeverity.WARNING, R.string.diag_and_user_locked_title, R.string.diag_and_user_locked_guide, null),
    DiagEntry(DiagCodes.AND_RECENT_REBOOT, DiagSection.ANDROID_WARNINGS, DiagSeverity.INFO, R.string.diag_and_recent_reboot_title, R.string.diag_and_recent_reboot_guide, null),
    DiagEntry(DiagCodes.AND_EXIT_CRASH, DiagSection.ANDROID_WARNINGS, DiagSeverity.ERROR, R.string.diag_and_exit_crash_title, R.string.diag_and_exit_crash_guide, null),
    DiagEntry(DiagCodes.AND_EXIT_ANR, DiagSection.ANDROID_WARNINGS, DiagSeverity.ERROR, R.string.diag_and_exit_anr_title, R.string.diag_and_exit_anr_guide, null),
    DiagEntry(DiagCodes.AND_EXIT_MEMORY, DiagSection.ANDROID_WARNINGS, DiagSeverity.WARNING, R.string.diag_and_exit_memory_title, R.string.diag_and_exit_memory_guide, null),
    DiagEntry(DiagCodes.AND_EXIT_FORCE_STOP, DiagSection.ANDROID_WARNINGS, DiagSeverity.WARNING, R.string.diag_and_exit_force_stop_title, R.string.diag_and_exit_force_stop_guide, null),
    DiagEntry(DiagCodes.AND_EXIT_FREEZER, DiagSection.ANDROID_WARNINGS, DiagSeverity.INFO, R.string.diag_and_exit_freezer_title, 0, null),
    DiagEntry(DiagCodes.AND_EXIT_PERMISSION_CHANGE, DiagSection.ANDROID_WARNINGS, DiagSeverity.INFO, R.string.diag_and_exit_permission_change_title, R.string.diag_and_exit_permission_change_guide, null),
    DiagEntry(DiagCodes.AND_BACKGROUND_STALLED, DiagSection.ANDROID_WARNINGS, DiagSeverity.WARNING, R.string.diag_and_background_stalled_title, R.string.diag_and_background_stalled_guide, DiagAction.OPEN_BATTERY_SETTINGS),

    // --- Fiducia -----------------------------------------------------------
    DiagEntry(DiagCodes.TRUST_APPROVED, DiagSection.TRUST, DiagSeverity.OK, R.string.diag_trust_approved_title, 0, null),
    DiagEntry(DiagCodes.TRUST_PENDING, DiagSection.TRUST, DiagSeverity.WARNING, R.string.diag_trust_pending_title, R.string.diag_trust_pending_guide, null),
    DiagEntry(DiagCodes.TRUST_RETIRED, DiagSection.TRUST, DiagSeverity.WARNING, R.string.diag_trust_retired_title, R.string.diag_trust_retired_guide, null),
    DiagEntry(DiagCodes.TRUST_REVOKED, DiagSection.TRUST, DiagSeverity.ERROR, R.string.diag_trust_revoked_title, R.string.diag_trust_revoked_guide, null),
    DiagEntry(DiagCodes.TRUST_LOST, DiagSection.TRUST, DiagSeverity.ERROR, R.string.diag_trust_lost_title, R.string.diag_trust_lost_guide, null),
    DiagEntry(DiagCodes.TRUST_NOT_CONFIGURED, DiagSection.TRUST, DiagSeverity.INFO, R.string.diag_trust_not_configured_title, 0, DiagAction.RESUME_ONBOARDING),
    DiagEntry(DiagCodes.TRUST_REGISTRY_STALE, DiagSection.TRUST, DiagSeverity.WARNING, R.string.diag_trust_registry_stale_title, R.string.diag_trust_registry_stale_guide, null),
    DiagEntry(DiagCodes.TRUST_NEWER_UNVERIFIED, DiagSection.TRUST, DiagSeverity.WARNING, R.string.diag_trust_newer_unverified_title, R.string.diag_trust_newer_unverified_guide, null),
    DiagEntry(DiagCodes.TRUST_LISTING_TRUNCATED, DiagSection.TRUST, DiagSeverity.WARNING, R.string.diag_trust_listing_truncated_title, R.string.diag_trust_listing_truncated_guide, null),
    DiagEntry(DiagCodes.TRUST_NEEDS_APP_UPDATE, DiagSection.TRUST, DiagSeverity.ERROR, R.string.diag_trust_needs_app_update_title, R.string.diag_trust_needs_app_update_guide, null),
    DiagEntry(DiagCodes.TRUST_EQUIVOCATION, DiagSection.TRUST, DiagSeverity.ERROR, R.string.diag_trust_equivocation_title, R.string.diag_trust_equivocation_guide, null),
    DiagEntry(DiagCodes.TRUST_REGISTRY_FORK, DiagSection.TRUST, DiagSeverity.ERROR, R.string.diag_trust_registry_fork_title, R.string.diag_trust_registry_fork_guide, null),
    DiagEntry(DiagCodes.TRUST_WIPE_IN_PROGRESS, DiagSection.TRUST, DiagSeverity.ERROR, R.string.diag_trust_wipe_in_progress_title, R.string.diag_trust_wipe_in_progress_guide, null),
    DiagEntry(DiagCodes.TRUST_WIPED, DiagSection.TRUST, DiagSeverity.INFO, R.string.diag_trust_wiped_title, 0, null),
    DiagEntry(DiagCodes.TRUST_REJECTIONS, DiagSection.TRUST, DiagSeverity.INFO, R.string.diag_trust_rejections_title, 0, null),
    DiagEntry(DiagCodes.TRUST_ANCHOR_WEAK, DiagSection.TRUST, DiagSeverity.WARNING, R.string.diag_trust_anchor_weak_title, R.string.diag_trust_anchor_weak_guide, null),
    DiagEntry(DiagCodes.TRUST_LOOKALIKE, DiagSection.TRUST, DiagSeverity.WARNING, R.string.diag_trust_lookalike_title, R.string.diag_trust_lookalike_guide, DiagAction.OPEN_DEVICES),
    DiagEntry(DiagCodes.TRUST_PENDING_REQUESTS, DiagSection.TRUST, DiagSeverity.WARNING, R.string.diag_trust_pending_requests_title, R.string.diag_trust_pending_requests_guide, DiagAction.OPEN_DEVICES),
    DiagEntry(DiagCodes.TRUST_NO_ADMIN, DiagSection.TRUST, DiagSeverity.ERROR, R.string.diag_trust_no_admin_title, R.string.diag_trust_no_admin_guide, null),
    DiagEntry(DiagCodes.TRUST_OLD_RECOVERY_KIT, DiagSection.TRUST, DiagSeverity.WARNING, R.string.diag_trust_old_recovery_kit_title, R.string.diag_trust_old_recovery_kit_guide, null),
    DiagEntry(DiagCodes.TRUST_LOST_NOT_SEEN, DiagSection.TRUST, DiagSeverity.WARNING, R.string.diag_trust_lost_not_seen_title, R.string.diag_trust_lost_not_seen_guide, DiagAction.OPEN_DEVICES),

    // --- Profili -----------------------------------------------------------
    DiagEntry(DiagCodes.PROFILE_PRIMARY, DiagSection.PROFILES, DiagSeverity.OK, R.string.diag_profile_primary_title, 0, null),
    DiagEntry(DiagCodes.PROFILE_SECONDARY, DiagSection.PROFILES, DiagSeverity.WARNING, R.string.diag_profile_secondary_title, R.string.diag_profile_secondary_guide, null),
    DiagEntry(DiagCodes.PROFILE_OWN_VPN, DiagSection.PROFILES, DiagSeverity.INFO, R.string.diag_profile_own_vpn_title, R.string.diag_profile_own_vpn_guide, null),
    DiagEntry(DiagCodes.PROFILE_HEARTBEAT_OK, DiagSection.PROFILES, DiagSeverity.OK, R.string.diag_profile_heartbeat_ok_title, 0, null),
    DiagEntry(DiagCodes.PROFILE_HEARTBEAT_STALE, DiagSection.PROFILES, DiagSeverity.WARNING, R.string.diag_profile_heartbeat_stale_title, R.string.diag_profile_heartbeat_stale_guide, null),

    // --- Rete e NAS --------------------------------------------------------
    DiagEntry(DiagCodes.DIAG_SECTION_UNAVAILABLE, DiagSection.NETWORK_AND_NAS, DiagSeverity.WARNING, R.string.diag_diag_section_unavailable_title, R.string.diag_diag_section_unavailable_guide, null),
  )
}
