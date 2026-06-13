# 🚀 Guide de mise en ligne et compilation GitHub

## Étape 1 — Créer le dépôt GitHub

1. Aller sur [github.com/new](https://github.com/new)
2. Nom du dépôt : `kighmu-vpn`
3. Visibilité : **Private** (recommandé)
4. **Ne pas** initialiser avec README
5. Cliquer **Create repository**

---

## Étape 2 — Pousser le code

```bash
cd KIGHMU-VPN

git init
git add .
git commit -m "Initial commit: KIGHMU VPN full source"
git branch -M main
git remote add origin https://github.com/VOTRE_USERNAME/kighmu-vpn.git
git push -u origin main
```

---

## Étape 3 — Configurer la signature APK (optionnel mais recommandé)

### 3a. Générer un keystore

```bash
# Sur votre machine locale (nécessite Java/keytool)
bash scripts/generate_keystore.sh
```

Le script affiche 4 valeurs à copier.

### 3b. Ajouter les secrets GitHub

Aller dans : **Dépôt → Settings → Secrets and variables → Actions → New repository secret**

| Nom du secret | Valeur |
|---------------|--------|
| `KEYSTORE_BASE64` | (valeur base64 du fichier .keystore) |
| `KEY_ALIAS` | `kighmu_key` |
| `KEYSTORE_PASSWORD` | (mot de passe keystore) |
| `KEY_PASSWORD` | (mot de passe clé) |

> ⚠️ Sans ces secrets, l'APK sera compilé mais **non signé** (unsigned).
> L'APK unsigned peut quand même être installé sur un appareil Android via ADB.

---

## Étape 4 — Lancer le build

### Option A : Build automatique (push)
Le build se lance automatiquement à chaque `git push` sur `main`.

### Option B : Build manuel
1. Aller dans **Actions** sur GitHub
2. Cliquer **Build KIGHMU VPN**
3. Cliquer **Run workflow** → **Run workflow**

---

## Étape 5 — Télécharger l'APK

1. Aller dans **Actions** → dernier build terminé
2. Descendre vers **Artifacts**
3. Télécharger :
   - `KIGHMU-VPN-release` → APK signé (si keystore configuré)
   - `KIGHMU-VPN-debug` → APK debug (toujours disponible)

---

## Étape 6 — Créer une Release officielle

```bash
# Tag de version
git tag v1.0.0
git push origin v1.0.0
```

→ Crée automatiquement une Release GitHub avec les APKs attachés.

---

## Structure du build GitHub Actions

```
Push / Tag
    │
    ▼
┌─────────────────────────────────────────┐
│ 1. Checkout code                        │
│ 2. Setup Java 17 + Go 1.22 + NDK r26d  │
│ 3. Build Xray-core (arm64/arm/x86_64)  │ ← Go cross-compile
│ 4. Build Hysteria2 (arm64/arm/x86_64)  │ ← Go cross-compile
│ 5. Build Debug APK (Gradle)            │
│ 6. Build Release APK (Gradle + CMake)  │
│ 7. Sign APK (si secrets configurés)    │
│ 8. Upload Artifacts                    │
│ 9. Create Release (si tag v*)          │
└─────────────────────────────────────────┘
```

---

## Notes importantes

### Binaires Xray et Hysteria
- Les binaires sont **compilés pendant le CI** via Go
- Si la compilation Go échoue (réseau, version), l'app utilise un **proxy SOCKS5 de secours** intégré en Kotlin
- Pour pré-compiler manuellement : `bash scripts/build_xray.sh && bash scripts/build_hysteria.sh`

### Temps de build estimé
| Étape | Durée |
|-------|-------|
| Setup | ~2 min |
| Xray build (3 ABIs) | ~8 min |
| Hysteria build (3 ABIs) | ~5 min |
| Gradle + NDK | ~6 min |
| **Total** | ~20 min |

### Dépendances réseau
Le premier build télécharge : Gradle 8.4, Android SDK, NDK r26d, dépendances Maven (~500MB). Les builds suivants utilisent le cache GitHub (~3-5 min).

---

## Installer l'APK sur Android

```bash
# Via ADB (debug USB)
adb install KIGHMU-VPN-v1.0.0-debug.apk

# Ou transférer le fichier APK sur l'appareil et l'ouvrir
```

Activer : **Paramètres → Sécurité → Sources inconnues** (ou **Installer des applications inconnues**)
