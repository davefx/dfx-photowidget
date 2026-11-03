# DFX Photo Widget

Widget de escritorio para Android (10 o superior) que muestra fotos de una o varias carpetas,
o un conjunto de fotos elegidas a mano, con un objetivo por encima de todo: **no gastar batería**.
En concreto: **si el escritorio no está a la vista, no se trabaja.**

## Qué hace

- Muestra una foto fija, varias fotos elegidas o todas las fotos de una o varias carpetas
  (con o sin subcarpetas).
- Cambia de foto cada 30 s, 1, 2, 5, 15 o 30 min, 1, 3, 6 o 12 h, una vez al día, o solo al tocar.
- Tocar el widget pasa a la siguiente foto.
- Aspecto: recortar para llenar o foto completa, cuatro radios de esquina, marco fino, medio o
  grueso en blanco, negro, gris o crema.
- Recorre las fotos en orden aleatorio y no repite ninguna hasta haberlas mostrado todas.
- En Android 12 o superior se reconfigura con una pulsación larga sobre el widget. En Android 10 y 11,
  desde el icono de la app.

## Cómo consigue no gastar

El widget es un `AdapterViewFlipper` que dibuja el launcher. La app nunca le «empuja» fotos:
**el launcher se las pide** a `SlideshowService` en el momento de dibujarlas, y solo dibuja
cuando el escritorio está a la vista. Todo el trabajo caro (recorrer carpetas, decodificar)
ocurre ahí y en ningún otro sitio.

- **Intervalos de 5 min o menos:** el propio launcher pasa las fotos (`autoStart`). El
  `AdapterViewFlipper` se para solo con la pantalla apagada, en la pantalla de bloqueo o con otra
  app delante. La app no tiene temporizador, alarma ni servicio.
- **Intervalos de 15 min o más:** una alarma `RTC` inexacta (**nunca** `RTC_WAKEUP`) que solo
  apunta qué foto toca, con una llamada `partiallyUpdateAppWidget`. No decodifica ni lee
  disco. La foto se prepara cuando el launcher vuelve a dibujar el widget. Con el teléfono
  dormido la alarma ni siquiera se entrega: espera a que se despierte por otro motivo.

| Problema típico de otros widgets | Qué hace este |
|---|---|
| Despierta el teléfono para cambiar de foto | Nunca usa alarmas despertadoras. `updatePeriodMillis="0"`. |
| Prepara fotos que nadie ve | Solo se decodifica cuando el launcher pide la foto para dibujarla. |
| Servicios, WorkManager, wakelocks | Ninguno. El único servicio es el que el launcher enlaza para pedir fotos. |
| Sincronización con la nube | Sin permiso de red. Solo fotos locales. |
| Decodificar la foto a 12-50 MP | `ImageDecoder` con tamaño objetivo: submuestrea directamente al tamaño del widget, con un máximo de unos 2 MB por foto en memoria. |
| Recorrer las carpetas a menudo | Índice barajado en disco. Se reconstruye como mucho una vez al día o al completar una vuelta, y siempre con el escritorio a la vista. |
| Esquinas y marco dibujados en cada frame | Se pintan una sola vez dentro del bitmap. |
| Una sola foto | No se programa ninguna alarma. |
| Ahorro de batería activo | Por defecto, los cambios por alarma se pausan. |

Permisos: solo `RECEIVE_BOOT_COMPLETED`, para reprogramar la alarma tras reiniciar. El acceso a
las fotos va por el Storage Access Framework y el selector de fotos, que conceden permiso solo
sobre lo que eliges. Al borrar un widget se liberan los permisos que ya no usa ningún otro.

## Compilar

```sh
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

La release se firma con la clave de depuración para instalarla directamente. Para distribuirla,
cambia `signingConfig` en `app/build.gradle.kts`.

## Pruebas

Dos niveles, ambos sobre un dispositivo o emulador conectado:

```sh
# 1. Tests instrumentados (lógica, sin interfaz). Usan un proveedor de fotos falso que solo
#    existe en la variante debug.
./gradlew connectedDebugAndroidTest

# 2. Extremo a extremo con la release: añade el widget, lo configura con los selectores del
#    sistema y mide cuántas fotos prepara en cada situación. Unos 10 minutos.
./gradlew assembleRelease
python3 tools/e2e.py -s emulator-5554
```

Los tests instrumentados cubren el índice (subcarpetas, límite de profundidad, carpetas ocultas,
GIF, duplicados, barajado), la decodificación (tamaño, recorte, nunca ampliar, esquinas y marco),
las alarmas reales del sistema (nunca despertadoras, intervalo correcto, ninguna con una sola foto)
y la lógica de cambio de foto (ahorro de batería, vuelta completa, toques). También comprueban
que con el índice al día no se recorre ninguna carpeta.

El extremo a extremo comprueba que con otra app delante o con la pantalla apagada se preparan
0 fotos, que el toque y la alarma cambian la foto, que todo sobrevive a un reinicio y que
batterystats no registra wakelocks ni alarmas despertadoras. Necesita Pillow (`pip install pillow`).

## Estructura

- `PhotoWidgetProvider.kt`: recibe los eventos (toque, alarma, arranque, cambio de tamaño, borrado).
- `WidgetUpdater.kt`: decide qué foto toca y monta el widget. Nunca decodifica.
- `SlideshowService.kt`: entrega al launcher la foto que pide. Es el único sitio donde se trabaja.
- `Scheduler.kt`: alarma no despertadora.
- `PhotoIndex.kt`: índice barajado de fotos en disco.
- `Renderer.kt`: decodificación al tamaño justo, recorte, esquinas y marco.
- `ConfigActivity.kt`: configuración.
- `WidgetConfig.kt` / `Grants.kt`: persistencia y permisos.

## Licencia

Copyright © 2026 David Marín Carreño (DaveFX).

Este programa es software libre: puedes redistribuirlo y modificarlo bajo los términos de la
[Licencia Pública General de GNU, versión 3](LICENSE) o (a tu elección) cualquier versión posterior,
tal como la publica la Free Software Foundation. Se distribuye SIN NINGUNA GARANTÍA.
