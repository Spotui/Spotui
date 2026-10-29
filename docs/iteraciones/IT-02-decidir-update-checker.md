# IT-02 · Decidir qué `UpdateChecker` se queda

| Campo | Valor |
|---|---|
| Tipo | Decisión (no modifica código) |
| Depende de | IT-01 |
| Estado | ✅ Hecha (2026-10-28) |

## 1. Qué hacer
Presentar al usuario las dos implementaciones duplicadas y apuntar cuál se mantiene.

| | `data/api/UpdateChecker.kt` | `data/update/UpdateChecker.kt` |
|---|---|---|
| Endpoint | `releases/latest` | `releases/tags/Release` (tag rodante) |
| Lo usa | `HomeScreen.kt:259-278` (badge, botón "comprobar") | `UpdatePrompt.kt` (diálogo al arrancar, desde `MainActivity:65`) |
| Extras | Caché de 6 h, funciona offline con último resultado | "No volver a mostrar" por *fingerprint* de build, enlace al APK |
| Tests | `UpdateCheckerTest` cubre su `isNewer()` | Sin tests |

## 2. Preguntar al usuario (antes de empezar)
- [ ] ¿Las releases se publican con el tag rodante `Release` o como "latest"?
- [ ] ¿Cuál se queda? (por defecto se recomienda `data/update`, porque identifica la build exacta)
- [ ] ¿Quieres conservar la caché de 6 h del otro? (si sí → iteración aparte, no aquí)

> Respuesta del usuario: _(rellenar)_

## 3. Salida esperada
Decisión escrita en el Registro. IT-03 e IT-04 se ajustan a esa decisión.

## 4. Cambios
Solo este fichero (y, si cambia la dirección, IT-03/IT-04).

## 5. Fuera de alcance
Tocar código.

## 6. Sugerencias
- Si se quiere la caché de 6 h en el checker elegido → iteración nueva "Añadir caché al UpdateChecker".

## 7. Registro
| Fecha | Estado | Resultado real | Commit |
|---|---|---|---|
| 2026-10-28 | ✅ Hecha | Se queda `data/update/UpdateChecker.kt`, apuntado al endpoint estándar `releases/latest` | — (sin cambios de código) |

**Respuesta del usuario:** "Haz lo que sea el estándar o lo recomendado."

**Comprobación** (GET de solo lectura a la API pública de GitHub, 28-10-2026):
| Endpoint | Resultado |
|---|---|
| `releases/latest` | `tag_name: v1.6`, `name: Spotui 1.6` (11-10-2026) |
| `releases/tags/Release` | `name: spotui 1.2.1` (06-07-2026), **congelado** |
| Últimas releases | `v1.6`, `v1.5`, `v1.4`, `v1.3` → tags versionados (el estándar de GitHub) |

**Conclusión:**
- `data/update` (el diálogo del arranque) consulta el tag `Release`, que se quedó en la 1.2.1. **Desde la 1.3 no avisa nunca.** Es un bug real.
- `data/api` (el badge de Home) usa `releases/latest` + `tag_name`, que funciona.

**Decisión:** el estándar es `releases/latest` con tags `vX.Y`. Se mantiene `data/update`, porque tiene mejor experiencia (diálogo al arrancar, enlace directo al APK, "no volver a mostrar" por release), pero cambiándolo al endpoint estándar. La cadena queda así:
1. **IT-09**: apuntar `data/update` a `releases/latest` (arregla el diálogo).
2. **IT-03**: migrar el badge de Home a `data/update`.
3. **IT-10**: adaptar `UpdateCheckerTest` al checker que se queda.
4. **IT-04**: borrar `data/api/UpdateChecker.kt`.

Lo que se pierde de `data/api`: la caché de 6 h y el `force`. Queda como sugerencia, no como parte de esta cadena.

**Actualización (28-10-2026):** el usuario usará su fork **`Nonit0/Spotui`** (`origin`), no `Spotui/Spotui`. La decisión se mantiene (`data/update` + `releases/latest`), pero el destino es su repo. Ver IT-09 (aplazada hasta que haya una release en el fork).
