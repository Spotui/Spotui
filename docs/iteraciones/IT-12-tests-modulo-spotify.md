# IT-12 · Ejecutar los tests del módulo `:spotify`

| Campo | Valor |
|---|---|
| Tipo | Verificación (no modifica código) |
| Depende de | IT-01 |
| Estado | ✅ Hecha (2026-10-28) |

## 1. Qué hacer
Ejecutar el único test del módulo `:spotify` (`SpotifyAuthTotpTest`), que en IT-01 no se ejecutó
porque `:spotify` es un módulo JVM (`kotlin("jvm")`) y no tiene la tarea `testDebugUnitTest`:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk ./gradlew :spotify:test
```

## 2. Preguntar al usuario (antes de empezar)
- [x] ¿Lo ejecuto?

> Respuesta del usuario: "Sí, hazlo y regístralo."

## 3. Salida esperada
- `BUILD SUCCESSFUL` y `SpotifyAuthTotpTest` en verde (`spotify/build/test-results/test/*.xml`).

## 4. Cambios
Ninguno (solo `build/`, que git ignora).

## 5. Fuera de alcance
Arreglar el test si falla (→ iteración nueva). Añadir `:spotify:test` a un comando común.

## 6. Sugerencias
- Si pasa: apuntar en `CONTEXTO.md` el comando completo de tests (`testDebugUnitTest :spotify:test`).

## 7. Registro
| Fecha | Estado | Resultado real | Commit |
|---|---|---|---|
| 2026-10-28 | ✅ Hecha | `:spotify:test` → `BUILD SUCCESSFUL in 3s`. `SpotifyAuthTotpTest`: **2 tests, 0 fallos** | — (sin cambios de código) |

Total del proyecto: **22 tests** (20 de `testDebugUnitTest` + 2 de `:spotify:test`), todos en verde. Comando apuntado en `CONTEXTO.md` §6.
