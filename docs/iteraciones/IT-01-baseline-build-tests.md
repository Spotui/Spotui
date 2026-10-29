# IT-01 · Comprobar que compila y pasan los tests

| Campo | Valor |
|---|---|
| Tipo | Verificación (no modifica código) |
| Depende de | — |
| Estado | ✅ Hecha (2026-10-28) |

## 1. Qué hacer
Ejecutar desde la raíz del repo:

```bash
./gradlew assembleDebug testDebugUnitTest
```

y anotar el resultado como línea base para todas las iteraciones siguientes.

## 2. Preguntar al usuario (antes de empezar)
- [ ] ¿Puedo lanzar Gradle? (descarga dependencias y tarda varios minutos la primera vez)
- [ ] ¿Hay un `local.properties` con `sdk.dir` o un `ANDROID_HOME` válido en esta máquina?

> Respuesta del usuario: _(rellenar)_

## 3. Salida esperada
- `BUILD SUCCESSFUL`.
- APK en `app/build/outputs/apk/debug/app-debug.apk`.
- Informe de tests en `*/build/reports/tests/testDebugUnitTest/index.html` con todos en verde.

## 4. Cambios
Ninguno. Solo se generan artefactos en `build/` (ignorados por git).

## 5. Fuera de alcance
- Arreglar errores de compilación o tests rojos → si aparecen, se apuntan como iteraciones nuevas.
- Tests instrumentados (`connectedAndroidTest`), necesitan dispositivo.

## 6. Sugerencias
- Si falla por SDK/JDK: nueva iteración "Documentar requisitos de entorno en CONTEXTO.md".

## 7. Registro
| Fecha | Estado | Resultado real | Commit |
|---|---|---|---|
| 2026-10-28 | ✅ Hecha | `BUILD SUCCESSFUL in 4m 39s`, 88 tareas. APK debug de 20 MB. 21 tests unitarios, 0 fallos (ver detalle) | — (sin cambios de código) |

**Respuesta del usuario:** "Lánzalo, pero si vamos a probar el código mejor en Android Studio, lo probaré a mano. Si no has modificado ningún archivo es una versión que funciona, está probado."

**Cómo se lanzó** (sin crear `local.properties`):
```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk ./gradlew assembleDebug testDebugUnitTest
```
El Java por defecto de la máquina es el 25, y Gradle 8.6 no lo soporta: hay que usar JDK 17 (o el JBR de `/opt/android-studio/jbr`).

**Tests:** LyricsMatch 4 · UpdateChecker 1 · ExampleUnitTest 1 · TidalMatch 4 · SoulseekPick 5 · PlayerResponseFormat 3 · YouTubeClient 3.
`SpotifyAuthTotpTest` (módulo `:spotify`) **no se ejecutó**: ese módulo no tiene la tarea `testDebugUnitTest`.

**Avisos (no bloquean):** D8 avisa varias veces de "error parsing kotlin metadata" en clases de `:spotify`, porque el R8 de AGP 8.4.2 es más antiguo que Kotlin 2.2.0. Además hay 10 warnings del compilador Kotlin (`w:`).

**Sugerencias que salen de aquí:**
- Iteración nueva: ejecutar los tests de `:spotify` (`./gradlew :spotify:test`).
- Backlog: alinear la versión de AGP con Kotlin 2.2 para quitar los avisos de D8 (encaja en S5).
