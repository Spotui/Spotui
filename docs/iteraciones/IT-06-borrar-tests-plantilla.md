# IT-06 · Borrar los tests de plantilla

| Campo | Valor |
|---|---|
| Tipo | Código |
| Depende de | IT-01 |
| Estado | ✅ Hecha (2026-10-28) |

## 1. Qué hacer
Eliminar los dos tests generados por Android Studio que no prueban nada (`2 + 2 == 4` y el
contexto de paquete):

- `app/src/test/java/com/music/spotui/ExampleUnitTest.kt`
- `app/src/androidTest/java/com/music/spotui/ExampleInstrumentedTest.kt`

## 2. Preguntar al usuario (antes de empezar)
- [ ] ¿Confirmas el borrado? `androidTest/` quedará vacío; ¿lo mantenemos vacío para futuros tests?

> Respuesta del usuario: "Mantén la carpeta y borra los tests."

## 3. Salida esperada
- `./gradlew testDebugUnitTest` → `BUILD SUCCESSFUL`, con un test menos que en IT-01.

## 4. Cambios
| Fichero | Cambio |
|---|---|
| `ExampleUnitTest.kt` | Eliminado |
| `ExampleInstrumentedTest.kt` | Eliminado |
| `app/src/androidTest/java/com/music/spotui/.gitkeep` | Nuevo, vacío. Git no guarda carpetas vacías y es la única forma de mantener `androidTest/` como pidió el usuario |

## 5. Fuera de alcance
Escribir tests nuevos (ver sugerencia S2 del índice).

## 6. Sugerencias
-

## 7. Registro
| Fecha | Estado | Resultado real | Commit |
|---|---|---|---|
| 2026-10-28 | ✅ Hecha | `git rm` de los 2 tests + `.gitkeep` en `androidTest/java/com/music/spotui/`. `testDebugUnitTest` → `BUILD SUCCESSFUL`, **20 tests, 0 fallos** (antes 21) | `41ffbf4` |
