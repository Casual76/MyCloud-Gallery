# Le regole R8 che viaggiano con core-common (gradle/android-conventions.gradle le applica a chi dipende dal modulo).
#
# Fase 5, D12 (docs/fase5-progetto.md §0.1): "backup solo sulle share di prova" e' una protezione che deve stare nell'APK di release
# anche prima che il backup sia legato in :app (P7). Senza questa regola R8 toglierebbe il campo e la decisione (nessuno li legge
# ancora) e tools/check.ps1 non potrebbe dire che la release li ha: il passo "D12 nella release" li cerca nel dex e in mapping.txt.
-keepclassmembers class dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags {
    public boolean getBackupTestSharesOnly();
    public boolean backupMayWriteTo(java.lang.String);
}
