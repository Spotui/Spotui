# IT-16 · Sacar del backup de Google las credenciales que aún se copian

| Campo | Valor |
|---|---|
| Tipo | Código |
| Depende de | — |
| Estado | ⏳ Pendiente |

## 1. Qué hacer
Hallazgo al trocear S6 (cifrar credenciales): la app **ya excluye** de las copias de seguridad
`spotify_session.xml`, `Deezer.xml` y `SpotiflacSession.xml` (`res/xml/backup_rules.xml` y
`data_extraction_rules.xml`). Pero se copian dos que no deberían:

| Fichero de prefs | Qué contiene | Problema |
|---|---|---|
| `settings_prefs.xml` | **Cookie de sesión de YouTube** (`youtube_cookie`, `visitor_data`, `data_sync_id`) junto a los ajustes | Va al backup en la nube de Google |
| `Soulseek.xml` | Usuario y contraseña de Soulseek (cuenta creada automáticamente por la app) | Va al backup |

Cambio único: mover las claves `youtube_*` de `SettingsPref.kt` a un fichero propio `youtube_session`
y añadir `youtube_session.xml` y `Soulseek.xml` a las exclusiones de los dos XML (igual que los otros tres).

## 2. Preguntar al usuario (antes de empezar)
- [ ] Al mover la cookie, quien ya tenga sesión de YouTube **tendrá que volver a iniciarla una vez**.
      ¿Lo aceptas (más simple) o copio el valor antiguo al nuevo fichero al arrancar (unas líneas más)?
- [ ] Al excluir `Soulseek.xml`, tras restaurar un backup la app creará otra cuenta de Soulseek automáticamente. ¿Ok?

> Respuesta del usuario: _(rellenar)_

## 3. Salida esperada
- `assembleDebug testDebugUnitTest` → `BUILD SUCCESSFUL`.
- `grep -n "youtube_" data/preferences/SettingsPref.kt` → solo las funciones, usando el fichero nuevo.
- Prueba manual: iniciar sesión en YouTube, cerrar y abrir la app → sigue con sesión.

## 4. Cambios
| Fichero | Cambio |
|---|---|
| `data/preferences/SettingsPref.kt` | Claves `youtube_*` en `youtube_session` |
| `res/xml/backup_rules.xml` | +2 `<exclude>` |
| `res/xml/data_extraction_rules.xml` | +4 `<exclude>` (cloud + device-transfer) |

## 5. Fuera de alcance
Cifrar nada. Ver nota en el índice: con esto, **S6 (cifrado) se descarta** — los ficheros son privados de la app,
ya no salen del dispositivo, y `androidx.security:security-crypto` (EncryptedSharedPreferences) está deprecado.

## 6. Sugerencias
-

## 7. Registro
| Fecha | Estado | Resultado real | Commit |
|---|---|---|---|
