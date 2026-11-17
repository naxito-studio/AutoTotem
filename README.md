# FrameLite

Mod de cliente para Fabric con auto-tótem, pensado para **no morir por ghosting** (el tótem aparece
en la offhand en el cliente pero el servidor todavía no lo tiene).

## Cómo popea ahora

Hay dos caminos, en este orden:

1. **Tótem en la hotbar (sin abrir nada)**: se cambia al slot del tótem, se hace el swap de offhand
   (el mismo paquete que la tecla F) y se vuelve al slot en el que estabas. Todo en el mismo tick,
   con los paquetes en orden (`UpdateSelectedSlot` → `SWAP_ITEM_WITH_OFFHAND` → `UpdateSelectedSlot`).
   Al no abrir inventario no hay ventana de desincronización visual.
2. **Tótem en el inventario principal**: se abre el inventario, el cursor aparece en el centro del
   área de 3x9 y se mueve en **espiral** (una vuelta completa) hasta el tótem, barriendo las tres
   líneas en vez de ir en línea recta. Al llegar se hace el swap y el inventario se cierra solo.

Detalles anti-ghosting:

- **Reintento automático** si el servidor rechaza el clic y revierte la predicción.
- **Espera de confirmación en la hotbar**: después de mandar el swap no se reenvía nada hasta que el
  servidor confirma la offhand (si se reenviara antes, se desharía el swap). Si no confirma en
  `HOTBAR_PENDING_TIMEOUT_MS`, se reintenta.
- **Relleno de la offhand vacía**: en cuanto se consume un tótem y la offhand queda vacía se vuelve
  a equipar.
- **Margen de vida**: se fuerza el tótem a partir de 3 corazones (6 HP) en lugar de 1, para aguantar
  un golpe durante el viaje de red.

### Diferencia entre las dos versiones

| | 1.21.11 | 1.19.2 |
| --- | --- | --- |
| Hotbar primero (sin GUI) | Sí | Sí |
| Inventario con espiral y cierre automático | Sí | Sí |
| Java | 21 | 17 |

Las dos versiones comparten la misma lógica. La única diferencia de código es cómo se lee el slot
seleccionado (`getSelectedSlot()` en 1.21.11, campo `selectedSlot` en 1.19.2).

> Constantes ajustables en `AutoTotemFeature`: `FORCE_HEALTH`, `SPIRAL_MIN_MS`/`SPIRAL_MAX_MS`,
> `KEEP_OPEN_MS` y `HOTBAR_PENDING_TIMEOUT_MS`.

## Jars

Compilados y listos para usar en `dist/`:

| Versión de Minecraft | Archivo |
| --- | --- |
| 1.21.11 | `FrameLite-1.1.0-mc1.21.11.jar` |
| 1.19.2 | `FrameLite-1.1.0-mc1.19.2.jar` |

Requiere Fabric Loader y Fabric API. Es un mod **solo cliente**: no hace falta en el servidor.

## Compilar

1.21.11 (raíz del repo):

```bash
./gradlew build
```

1.19.2 (proyecto aparte, Java 17):

```bash
cd versions/1.19.2
./gradlew build
```

Los jars quedan en `build/libs/`.

## Licencia

CC0-1.0.
