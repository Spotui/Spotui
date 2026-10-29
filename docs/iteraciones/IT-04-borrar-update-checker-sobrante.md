# IT-04 · Borrar el `UpdateChecker` que sobra

| Campo | Valor |
|---|---|
| Tipo | Código |
| Depende de | IT-10 |
| Estado | ⏳ Pendiente |

## 1. Qué hacer
Borrar el fichero del checker descartado en IT-02 (por defecto
`app/src/main/java/com/music/spotui/data/api/UpdateChecker.kt`). Solo si ya no tiene usos.

## 2. Preguntar al usuario (antes de empezar)
- [ ] ¿Confirmas el borrado de ese fichero?
- [ ] Las preferencias `updates` que guardaba quedarán huérfanas en dispositivos ya instalados: ¿lo aceptamos?

> Respuesta del usuario: _(rellenar)_

## 3. Salida esperada
- `grep -rn "UpdateChecker" app/src` → solo referencias al checker que se queda.
- `./gradlew assembleDebug testDebugUnitTest` → `BUILD SUCCESSFUL`.
- ⚠️ **Bloqueo conocido:** `app/src/test/java/com/music/spotui/data/api/UpdateCheckerTest.kt` prueba
  `data.api.UpdateChecker.isNewer(...)`. Si se borra ese checker, el test deja de compilar.
  Lo resuelve IT-10. Si IT-10 no
  está hecha, **no empezar**.

## 4. Cambios
| Fichero | Cambio |
|---|---|
| `app/src/main/java/com/music/spotui/data/api/UpdateChecker.kt` | Eliminado |

## 5. Fuera de alcance
- Limpiar las `SharedPreferences` huérfanas.
- Adaptar tests.

## 6. Sugerencias
- Iteración previa necesaria: "Adaptar `UpdateCheckerTest` al checker que se queda" (si ese checker
  no tiene `isNewer`, primero hay que decidir si se porta la comparación numérica de versiones).

## 7. Registro
| Fecha | Estado | Resultado real | Commit |
|---|---|---|---|
