# IT-03 · Migrar el badge de Home al `UpdateChecker` elegido

| Campo | Valor |
|---|---|
| Tipo | Código |
| Depende de | IT-09 |
| Estado | ⏳ Pendiente |

## 1. Qué hacer
Cambiar las llamadas de
`HomeScreen.kt` (~líneas 259, 263 y 278) de `com.music.spotui.data.api.UpdateChecker`
a `com.music.spotui.data.update.UpdateChecker`, adaptando `Update` → `UpdateInfo`
(`url` → `downloadUrl`). **Nada más.**

## 2. Preguntar al usuario (antes de empezar)
- [ ] ¿Confirmas que solo toco `HomeScreen.kt`?
- [ ] El checker elegido no tiene parámetro `force`: ¿el botón "comprobar" puede llamar a `check()` normal?

> Respuesta del usuario: _(rellenar)_

## 3. Salida esperada
- `./gradlew assembleDebug` → `BUILD SUCCESSFUL`.
- `grep -rn "data.api.UpdateChecker" app/src` → sin resultados.
- En la app, el badge de actualización de Home sigue apareciendo cuando hay versión nueva.

## 4. Cambios
| Fichero | Cambio |
|---|---|
| `app/src/main/java/com/music/spotui/ui/screens/HomeScreen.kt` | Cambia el tipo y las 2 llamadas al checker |

## 5. Fuera de alcance
- Borrar `data/api/UpdateChecker.kt` (→ IT-04).
- Añadir `force`/caché al checker elegido (→ sugerencia de IT-02).

## 6. Sugerencias
-

## 7. Registro
| Fecha | Estado | Resultado real | Commit |
|---|---|---|---|
