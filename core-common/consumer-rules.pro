# Le regole R8 che viaggiano con core-common (gradle/android-conventions.gradle le applica a chi dipende dal modulo).
#
# Fase 5, D12 (docs/fase5-progetto.md §0.1): "backup solo sulle share di prova" e' una protezione che deve stare nell'APK di release
# anche prima che il backup sia legato in :app (P7). Senza questa regola R8 toglierebbe il campo e la decisione (nessuno li legge
# ancora) e tools/check.ps1 non potrebbe dire che la release li ha: il passo "D12 nella release" li cerca nel dex e in mapping.txt.
#
# R2 (revisione 5A): in release un account di prova non fa scrivere il backup in nessuna share (D13, la recinzione del telefono, c'e' solo
# in debug). Il cancello chiama `backupMayWriteAs` oltre a `backupMayWriteTo`: tools/check.ps1 lo prova nel bytecode della release.
-keepclassmembers class dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags {
    public boolean getBackupTestSharesOnly();
    public boolean backupMayWriteTo(java.lang.String);
    public boolean getBackupBlocksTestAccounts();
    public boolean backupMayWriteAs(java.lang.String);
}

# Fase 7, D30 (docs/fase7-progetto.md §0.1): "album condivisi solo sulle share di prova" e' una protezione che deve stare nell'APK di
# release anche prima che il cancello degli album sia legato in :app (P13). Come D12: tools/check.ps1 la cerca nel dex e in mapping.txt.
-keepclassmembers class dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags {
    public boolean getSharedAlbumsTestSharesOnly();
    public boolean sharedAlbumsMayUse(java.lang.String);
}

# Fase 8, D34 (docs/fase8-progetto.md §0.1, risposta Q5 (a)): "dati dei volti sul NAS solo sulle share di prova" e' una protezione che deve
# stare nell'APK di release anche prima che il cancello dei volti sia legato in :app (P13). Come D30: tools/check.ps1 la cerca nel dex e
# in mapping.txt.
-keepclassmembers class dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags {
    public boolean getFacesSyncTestSharesOnly();
    public boolean facesSyncMayUse(java.lang.String);
}
