# **MyCloud Gallery**

**Documento di Progetto — Visione Finale**

*v1.0 • Aprile 2026*

## **1\. Panoramica del Progetto**

MyCloud Gallery è un'applicazione mobile Android progettata per la gestione, visualizzazione e organizzazione dei contenuti multimediali (foto e video) archiviati su un NAS WD MyCloud EX2 Ultra. L'app vuole offrire un'esperienza paragonabile alle gallerie native di sistema — come Google Photos o Samsung Gallery — ma con pieno controllo sui propri dati, senza abbonamenti e senza dipendenza da cloud di terze parti.

| Specifica | Dettaglio |
| :---- | :---- |
| **Nome progetto** | MyCloud Gallery |
| **Piattaforma primaria** | Android (target: Samsung Galaxy S25) |
| **Framework** | Kotlin 2.1.0 \+ Jetpack Compose |
| **Design system** | Material 3 Expressive |
| **Lingua UI** | Italiano |
| **NAS target** | WD MyCloud EX2 Ultra |

## **2\. Obiettivi e Principi di Design**

### **2.1 Obiettivi principali**

* Fornire un'esperienza di galleria fluida e moderna per i contenuti archiviati sul NAS di famiglia.  
* Automatizzare completamente la sincronizzazione: l'utente non deve fare nulla manualmente.  
* Indicizzazione intelligente con AI on-device per ricerca semantica avanzata.  
* Supporto multi-utente con gestione dei permessi e condivisione familiare.  
* Privacy totale: tutta l'elaborazione AI avviene on-device, nessun dato inviato a servizi cloud.

### **2.2 Principi UX**

* UI pulita e minimalista, ispirata a Samsung Gallery e Google Photos.  
* Performance fluide anche con decine di migliaia di file (obiettivo: 10.000+ elementi).  
* Feedback visivo immediato per sync, caricamento e analisi AI.  
* Dark mode / Light mode in base al tema di sistema.  
* Automazione trasparente: i processi in background non disturbano l'utente.

## **3\. Architettura e Connettività**

### **3.1 Stack tecnologico attuale**

| Componente | Tecnologia |
| :---- | :---- |
| **Framework UI** | Jetpack Compose con Material 3 Expressive |
| **Protocollo Locale** | **SMB (SMBJ)** — Scelto per la velocità estrema di scansione e streaming |
| **Protocollo Remoto** | WebDAV / WD Relay (OkHttp/Retrofit) |
| **Caricamento Immagini** | **Coil 3** con fetcher SMB personalizzato |
| **AI on-device** | ML Kit / TFLite (face, oggetti, scene, OCR) |
| **Database locale** | **Room** (indice metadati e cache) |
| **Background Tasks** | WorkManager (SyncWorker, IndexingWorker) |

### **3.2 Strategia di Connessione Ibrida**

L'app utilizza una strategia a due protocolli per garantire prestazioni e accessibilità:

1. **SMB (Local):** Utilizzato quando l'app rileva il NAS nella rete locale. Implementa **sessioni persistenti** e **streaming diretto** degli inputstream per caricare le immagini senza saturare la memoria.
2. **WebDAV / Relay WD (Remote):** Utilizzato per l'accesso fuori casa. L'app rileva automaticamente la modalità di rete (LOCAL/RELAY/OFFLINE) tramite il `NetworkDetector`.

## **4\. Autenticazione e Utenti**

* **Modello multi-utente:** Ogni membro accede con le proprie credenziali WD My Cloud.
* **Discovery Automatico:** Dopo il login, il NAS viene rilevato automaticamente tramite WD REST API.
* **Sicurezza:** Credenziali salvate nell'Android Keystore.
* **Struttura:** Accesso automatico alla cartella `Public` e alla cartella personale dell'utente (protetta).

## **5\. Galleria e Visualizzazione**

### **5.1 Vista principale**

* Divisione temporale (Oggi, Ieri, Questa settimana, Mesi precedenti).
* **Paging 3 Integration:** Caricamento infinito e reattivo dei media dal database locale.
* **Controlli Griglia:** Pinch-to-zoom fluido per cambiare il numero di colonne (da 2 a 5).

### **5.2 Visualizzazione e Streaming**

* **Anti-OOM Streaming:** Le foto originali e i video vengono aperti tramite stream SMB/WebDAV reali. Questo evita il caricamento di file da 5-10MB in memoria RAM, prevenendo crash.
* **Caching:** Thumbnail memorizzate localmente per navigazione istantanea.

## **6\. Ricerca e Indicizzazione AI**

### **6.1 Motore di ricerca semantico**

Ricerca avanzata per:
* Data, Luogo (mappa/EXIF), Persone (Face Recognition).
* Oggetti ("cane", "auto"), Scene ("tramonto", "mare").
* **OCR:** Testo all'interno delle immagini.
* Metadati tecnici (Modello camera, ISO, ecc.).

### **6.2 Elaborazione on-device**

Tutta l'analisi AI avviene quando il telefono è sotto carica, garantendo che nessun dato privato lasci mai il dispositivo.

## **7\. Vista Mappa e Geotagging**

Visualizzazione interattiva dei media su mappa tramite cluster dinamici basati sulle coordinate GPS estratte dagli EXIF.

## **8\. Condivisione e Album**

* **Album Manuali e Automatici:** Supporto per album creati dall'utente e album "Preferiti" auto-generati.
* **Condivisione Familiare:** Possibilità di condividere album con diversi livelli di permesso (Sola lettura, Editor).

## **9\. Stato Attuale del Progetto (Aprile 2026)**

### **9.1 Funzionalità Completate e Verificate**
* **Core Sync:** Sincronizzazione SMB completata con successo (testato con **9210 file** scansionati in < 2 secondi).
* **Network Detection:** Rilevamento automatico della modalità LOCAL/RELAY.
* **Performance:** Implementate le sessioni SMB persistenti che hanno risolto i problemi di lentezza e timeout.
* **Display:** Griglia dinamica funzionante con caricamento delle immagini tramite Coil 3 e fetcher SMB custom.
* **Diagnostica:** Pannello nelle impostazioni che mostra l'esito dettagliato dell'ultima sync.

### **9.2 Funzionalità in Fase di Affinamento**
* Estrazione automatica EXIF e etichettatura AI (IndexingWorker).
* Raffinamento del visualizzatore video fullscreen.

## **10\. Fuori dallo Scope (Definitivo)**

* Editing foto/video distruttivo.
* Supporto per NAS non WD MyCloud.
* Localizzazione in lingue diverse dall'italiano.
* Notifiche push native (usati solo badge in-app).

*Ultimo aggiornamento: 11 Aprile 2026 • Stato: Core Funzionale Verificato*
