---
name: pampa-store-publish
description: >
  Publish Android app releases to the Pampa Store. Use this skill whenever the
  user wants to publish, release, upload, or deploy an app to the Pampa Store —
  even if they just say "pubblica l'app", "fai la release", "carica sul store",
  "nuova versione su Pampa", or similar. Also trigger when the user asks about
  versioning, changelogs, APK signing, or upload errors related to Pampa Store.
  This skill covers the full publication workflow: APK validation, version
  bumping, channel selection (stable/beta), and invoking the
  pampa_upload_release tool correctly.
---

# Pampa Store — Publish Release

Questa skill guida Claude nel pubblicare una release Android sul Pampa Store,
usando il tool MCP `pampa-store:pampa_upload_release`.

---

## Checklist Pre-Pubblicazione

Prima di invocare il tool, verifica (o chiedi all'utente) che:

1. **APK firmato** — l'APK deve essere signed con una release keystore.
   - Contiene `META-INF/MANIFEST.MF` e un certificato (`.RSA`, `.EC`, o `.DSA`)
   - Il nome file **non** contiene "unsigned"
   - Buildato con `./gradlew assembleRelease` (non `debug`)

2. **Versione crescente** — la nuova versione deve essere > della precedente.
   - Formato supportato: `MAJOR.MINOR.PATCH` (es. `1.0.1`), con suffissi opzionali `‑beta`, `‑alpha.1`, `‑rc1`
   - Formato **non** supportato: stringhe puramente non-numeriche

3. **Channel** — `stable` o `beta`. Se non specificato, chiedi all'utente.

---

## Raccolta Parametri

Se l'utente non ha fornito tutti i dati, chiedi **solo quelli mancanti** (non
chiedere cose già dette). Parametri obbligatori:

| Parametro | Note |
|-----------|------|
| `channel` | `"stable"` o `"beta"` |
| `repoRoot` | Percorso locale della repo Android (opzionale se è la repo corrente) |

Parametri opzionali ma **consigliati** da richiedere se assenti:

| Parametro | Note |
|-----------|------|
| `version` | Se omesso, il tool lo rileva automaticamente |
| `changelog` | Descrizione delle modifiche nella release |
| `appId` | Identificativo univoco nello store |
| `appName` | Nome display dell'app |
| `packageName` | Es. `com.example.myapp` |

Parametri avanzati (chiedi solo se l'utente li menziona):

- `apkPath` — usa un APK custom invece di buildare
- `skipBuild` — salta il build, cerca APK già presente in `build/outputs`
- `description`, `developer`, `category`

---

## Invocazione del Tool

Una volta raccolti i parametri, usa:

```
pampa-store:pampa_upload_release
```

con i parametri raccolti. Esempio minimo:

```json
{
  "channel": "stable",
  "version": "1.0.1",
  "changelog": "Corretti bug critici"
}
```

### Modalità di esecuzione

| Modalità | Quando usarla |
|----------|---------------|
| **Default** (build automatico) | L'utente non ha un APK pronto |
| **skipBuild** | L'APK è già buildato in `build/outputs` |
| **apkPath** | L'utente fornisce un percorso APK specifico |
| **dryRun** | L'utente vuole un'anteprima senza pubblicare davvero |

---

## Gestione Errori Comuni

### ❌ `APK is not signed`
L'APK non è firmato o la firma è corrotta.
→ Verifica `signingConfig` in `build.gradle`, poi: `./gradlew clean assembleRelease`

### ❌ `APK file is not a valid ZIP archive`
File APK corrotto o mancante.
→ Ribuildo da zero: `./gradlew assembleRelease`

### ❌ `Version 'X' is not newer than current 'X'`
La versione non è crescente.
→ Incrementa `versionCode` e `versionName` in `build.gradle`:
```gradle
versionCode 2
versionName "1.0.1"
```

### ❌ `No Android application module found`
Repo non è un progetto Android Gradle valido.
→ Verifica la presenza di `build.gradle`; per Kotlin Multiplatform specifica `--module androidApp`

### ❌ `No GitHub token found`
Token GitHub non configurato.
→ Crea token su https://github.com/settings/tokens/new (scope: `repo`), poi:
```bash
export PAMPA_GH_TOKEN="ghp_xxxxx..."
```

---

## Configurazione Signing in build.gradle

Se l'utente deve configurare la firma, mostra questo snippet:

```gradle
android {
    signingConfigs {
        release {
            storeFile file("keystore.jks")
            storePassword "your_store_password"
            keyAlias "your_key_alias"
            keyPassword "your_key_password"
        }
    }
    buildTypes {
        release {
            signingConfig signingConfigs.release
        }
    }
}
```

---

## Requisiti Sistema

- **Token GitHub**: env var `PAMPA_GH_TOKEN`, `GITHUB_TOKEN`, o `GH_TOKEN` (scope: `repo`)
- **Android SDK**: build tools >= 30, Gradle wrapper incluso nel progetto

---

## Best Practices

✅ Usa semantic versioning (`MAJOR.MINOR.PATCH`)  
✅ Scrivi changelog chiari  
✅ Usa `dryRun` per anteprima prima di pubblicare  
✅ Non cancellare release già pubblicate  
✅ Non modificare tag GitHub manualmente  
