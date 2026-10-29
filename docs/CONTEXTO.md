# Contexto de Spotui

> Documento de referencia para cualquier persona o agente que vaya a trabajar en el repo.
> Repo de trabajo: **`Nonit0/Spotui`** (fork de `Spotui/Spotui`, todavía sin releases propias).
> Estado descrito: rama `main`, commit `2502236` (versión **1.6**, `versionCode 16`), 28-10-2026.

## 1. Qué es

Spotui es un **clon de Spotify para Android** hecho con **Kotlin + Jetpack Compose**.
Se conecta a la cuenta real de Spotify del usuario para obtener **metadatos** (playlists,
álbumes, artistas, letras, recomendaciones, biblioteca, likes), pero **el audio no sale de
Spotify**: se resuelve en tiempo de reproducción desde otras fuentes (Deezer, servidores
lossless comunitarios, Soulseek, YouTube Music o archivos locales).

Proyecto educativo (ver `README.md`). Deriva de Neptune (UI original), Meld (capa
Spotify + YouTube), SpotiFLAC (FLAC) y SimpMusic (crossfade).

## 2. Stack técnico

| Pieza | Tecnología |
|---|---|
| Lenguaje | Kotlin 2.2.0, JVM 17 |
| UI | Jetpack Compose (BOM 2024.06), Material 3, Navigation Compose |
| DI | Hilt 2.57.1 (KSP) |
| Reproducción | Media3 / ExoPlayer 1.3.1 (+ DASH, UI, Session) |
| Red | OkHttp 4.12, `HttpURLConnection`, kotlinx.serialization |
| Imágenes | Glide Compose, Palette |
| Persistencia | **Solo `SharedPreferences`** (18 ficheros `*Pref.kt`), sin Room ni DataStore |
| Build | Gradle KTS, AGP 8.4.2, `compileSdk/targetSdk 34`, `minSdk 26`, `isMinifyEnabled = false` |

## 3. Módulos

```
:app        → la aplicación (com.music.spotui) + utilidades YouTube portadas (com.metrolist.music.utils)
:spotify    → cliente web de Spotify (Spotify.kt, SpotifyAuth, SpotifyCanvas, SpotiFlac)
:innertube  → cliente InnerTube de YouTube/YouTube Music (+ partes de NewPipe extractor)
```

## 4. Cómo funciona

### 4.1 Arranque y onboarding (`ui/navigation/MyNavHost.kt`)
1. Si no hay cookie `sp_dc` de Spotify → pantalla **Login** (`SpotifyLoginScreen`, WebView).
2. Si no hay fuente musical elegida → **MusicSource** (YouTube Music o Deezer).
3. Si eligió YouTube Music y no ha iniciado sesión en YouTube → **MusicSource** otra vez.
4. Si no → **Home**. Se restaura la última pista en el mini‑player (pausada).

### 4.2 Capa de datos
`ViewModel` → `AppRepository` → `Api` (`data/api/Api.kt`) → módulo `:spotify`.
`Api` devuelve `Flow<Response<...>>` y cachea el feed de Home en memoria del proceso.
Cada canción lleva en `SongsModel.url` una **clave de reproducción** (`spotify:track:<id>` +
"título artista"), no una URL de stream.

### 4.3 Reproducción (`di/SongPlayer.kt`, ~1800 líneas, `object` singleton)
`resolveStreamUrl()` recorre una cadena de fuentes en este orden:

1. **Archivo local** (`content://` / `file://`) o **alternativa** fijada por el usuario.
2. **Descarga offline** si la pista está descargada.
3. Caché de streams ya resueltos (respetando la fuente elegida).
4. **Soulseek** cacheado (FLAC ya bajado).
5. **Deezer** (si hay ARL): FLAC/MP3 320 se usa; MP3 de cuenta free se "guarda" como plan B.
6. **Lossless** (`lossless/`): SpotiFLAC/Tidal/Qobuz/Amazon por ISRC vía proxies comunitarios.
7. **Soulseek** P2P en segundo plano (solo como *upgrade*, puede tardar ~1 min).
8. Deezer MP3 guardado → **YouTube** como último recurso (con scoring título/artista/duración/explicit).

Extras: *quick start then upgrade* (empieza con algo rápido y cambia a FLAC sin cortes),
prefetch de vecinas de la cola, caché de medios de ExoPlayer, crossfade (`audio/`),
badge de fuente y calidad en pantalla.

### 4.4 Servicio y sistema
`PlaybackService` (MediaLibraryService de Media3) da notificación, pantalla de bloqueo y
**Android Auto**. `SpotifyWebPlayer` (reproductor web de Spotify en WebView) existe pero está
**desactivado** (`webPlayerEnabled = false`, comentado como "roto").

### 4.5 Funcionalidades visibles
Home, Buscar, Biblioteca, Álbum, Artista, Playlist (crear/añadir/quitar, sincronizado), Podcasts
(Show), Cola (con *swipe to queue*), Me gusta, Historial, Descargas (incl. FLAC remuxado),
Archivos locales, Letras sincronizadas con traducción, Canvas (vídeo en bucle), Ajustes
(calidad por Wi‑Fi/datos, crossfade, fuentes), aviso de actualización desde GitHub.

## 5. Estado actual (diagnóstico)

**Fortalezas**
- Cadena de fuentes muy completa y con *fallbacks* razonados y comentados.
- Comentarios de código abundantes que explican el *porqué*.
- Firma de release fija vía `keystore.properties` (fuera de git).

**Deuda / riesgos detectados**
| # | Hallazgo | Dónde |
|---|---|---|
| D1 | **Dos `UpdateChecker` distintos** y ambos en uso, con endpoints distintos. El de `data/update` consulta el tag fijo `Release`, congelado en 1.2.1 → **el diálogo de actualización no avisa desde la 1.3** (comprobado 28-10-2026) | `data/api/UpdateChecker.kt` (usado en `HomeScreen`), `data/update/UpdateChecker.kt` (usado en `UpdatePrompt`) |
| D2 | `SongPlayer.kt` es un *god object* (resolución, crossfade, swap, caché). La comparación de canciones ya está en `TrackMatcher.kt` (IT-14): 1810 → 1602 líneas | `di/SongPlayer.kt` |
| D3 | ~~`runBlocking` en generación de PoToken~~ → corregido en IT-11 (ahora `suspend`; los tiempos límite de 15/45 s ya pueden cortar una WebView colgada) | `metrolist/music/utils/potoken/PoTokenGenerator.kt:33` |
| D4 | ~~Typo en nombre de fichero `LibraryScreeen.kt`~~ → corregido en IT-05 | `ui/screens/` |
| D5 | ~~Tests de plantilla sin valor~~ (borrados en IT-06). La cobertura sigue siendo baja: 20 tests unitarios en `:app` + `:innertube` | `app/src/test`, `app/src/androidTest` |
| D6 | ~~Corregido en IT-07~~ — `README.md` no mencionaba Deezer, Soulseek, archivos locales ni el onboarding de fuentes | `README.md` |
| D7 | Código del reproductor web de Spotify desactivado pero presente | `di/SpotifyWebPlayer.kt`, `ui/notification/WebMediaPlayer.kt` |
| D8 | Release sin minificar (`isMinifyEnabled = false`) → APK grande | `app/build.gradle.kts` |
| D9 | Dependencias con versiones de 2024 (Compose BOM, Media3 1.3.1, coroutines 1.7.3) | `app/build.gradle.kts` |
| D10 | Credenciales en `SharedPreferences` sin cifrar. Spotify, Deezer y SpotiFLAC ya están excluidas del backup; **la cookie de YouTube y Soulseek no** (→ IT-16) | `data/api/SpotifySession.kt`, `data/preferences/*` |

Estas deudas alimentan el plan de iteraciones en [`iteraciones/README.md`](iteraciones/README.md).

## 6. Compilar y probar en esta máquina

El Java por defecto es el 25 y Gradle 8.6 no lo soporta. Usa JDK 17 (o el JBR de Android Studio):

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk
./gradlew assembleDebug                       # APK en app/build/outputs/apk/debug/
./gradlew testDebugUnitTest :spotify:test     # 22 tests unitarios (IT-01 + IT-12)
```
