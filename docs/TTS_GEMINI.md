# TTS con actores (Gemini 3.8)

Motor de voz opcional del lector: `gemini-3.8-flash-tts` (calidad) o `gemini-3.8-flash-lite-tts`
(barato/rápido). Se elige en *Ajustes del lector → Voz → Motor de voz*. Usa la misma clave de API
de Gemini que la traducción con IA.

## Flujo por capítulo
1. **Troceado** (`DialogueChunker`): narración / diálogo (`[ ]`, `« »`, `“ ”`, `「 」`, rayas `—`),
   con las posiciones en el texto para subrayar/saltar en el lector.
2. **Identificación de hablantes** (`GeminiSpeakerAnnotator`, modelo `gemini-3.8-flash`): para cada
   trozo devuelve hablante, emoción (solo si el texto la muestra, también en la narración
   siguiente), pausa antes/después y género de personajes nuevos. **No reescribe el texto.**
   Guion resultante (`ScriptBuilder.render`):
   ```
   Narrador: A la espera del momento oportuno...
   Subaru: [<short pause>Oye, Flop-san, ¿me podrías hacer un favor? ...]
   Flop (surprised): [¡Oh, vaya! ¡Nunca había oído hablar de eso!]
   ```
3. **Actores** (`TtsActors`): cada hablante nuevo se registra con una voz sugerida (misma voz en
   todos los capítulos). Se editan en la pestaña *Actores* de la ficha (junto a *Sustituir*) y
   desde el lector.
4. **Audio** (`GeminiTtsPlayer`): una petición por turno con su voz y `speech_metadata.style`.
   El nombre del hablante **nunca** va en el texto. Las pausas: `<short pause>`/`<long pause>`
   dentro de la frase; las del principio/final y entre turnos son silencio local que se **suma** al
   ritmo natural (`ScriptBuilder`).

## Cómo se empieza a leer
1. Abre un capítulo en el lector → ajustes (engranaje) → pestaña **Voz** → **Motor de voz** → elige
   *Gemini 3.8 Flash TTS* o *Flash-Lite*. La clave de Gemini se escribe en la pestaña de traducción con IA.
2. Cierra el panel y pulsa el **botón del altavoz** de la barra inferior (el mismo que el TTS de siempre).
   La primera vez tarda unos segundos en "Identificando a los hablantes…".
3. Ajusta las voces en **Actores** (ficha de la novela o botón *Actores* de la pestaña Voz).

## Cuota y modo capítulo por actores (por defecto)
La cuota gratuita de Gemini 3.8 Flash TTS es **3 peticiones/minuto, 10.000 tokens/minuto y 10
peticiones/día** (AI Studio → Límites de frecuencia). Una petición por frase es imposible con eso, y
agrupar frases vecinas tampoco basta (30-60 por capítulo). Por eso:

* **Un capítulo = unas pocas peticiones enormes** (`RolePacker`): todas las frases de cada voz se
  juntan en una petición (hasta ~480 s de audio, lo que cabe en los 16.384 tokens de salida de la
  API) y el audio se recoloca en el orden del texto. Dos voces prediseñadas pueden compartir
  petición (límite de la API), así que los personajes cortos van juntos. Un capítulo típico
  (narrador + 2 personajes) son ~4 peticiones, no ~200.
* **Se prepara antes de sonar:** al empezar el capítulo se generan sus peticiones y la lectura
  arranca cuando está listo (puede tardar unos minutos). *Actores → Generar el capítulo entero antes
  de leer* desactivado = modo ventanas (tandas vecinas, empieza antes, gasta mucho más).
* **Cortar el audio** (`SilenceSplitter`): la API devuelve un audio sin marcas de tiempo, así que se
  separa por los silencios que dejan las dos `<long pause>` puestas entre turnos. Primero se
  toman los K silencios más largos si destacan claramente; si no, se elige por posición esperada.
  Se verifica que cada trozo dure algo coherente con su texto; si no, esa petición no se da por buena.
* **Rondas adaptativas:** la primera ronda es UNA petición de prueba; si se corta bien se piden las
  demás en paralelo; si falla, se reduce el tamaño a la mitad y se reempaqueta solo lo pendiente.
  Tras 2 fallos se para (no se sigue gastando cuota) y, si queda cuota de sobra, se hace frase a
  frase; si no, la lectura se detiene con un aviso.
* **Plan B de una sola voz:** varios turnos de una voz se piden con `speech_config` en array; si la
  API responde 400 se reintenta con la forma de dos hablantes (el segundo no se usa). Las voces de
  la Biblioteca/Voice design solo admiten la primera forma.
* **Cuota:** TODAS las peticiones de esta función (audio e identificación de hablantes) pasan por un único
  punto, `TtsQuota.send`: limitador compartido de **3 por minuto** (ventana deslizante de 60 s + margen,
  ajustable en *Actores → Cuota…*), así que en total nunca se supera aunque el 429 lo dé otro modelo.
  Un 429 de frecuencia llena la ventana (la siguiente petición espera un minuto entero) y avisa con un
  toast; un 429 de cuota diaria es fatal y no se reintenta; los 5xx y fallos de red se reintentan con
  espera corta. El mensaje de error dice qué cuota y qué modelo (p. ej.
  `GenerateRequestsPerMinutePerProjectPerModel-FreeTier (gemini-3.8-flash-tts)`). Si no se puede
  identificar a los hablantes, la lectura se detiene en vez de gastar audio con voces genéricas.
  Contador local de peticiones de hoy, aviso ANTES de empezar un capítulo si no caben. El contador es
  local: el día de Google termina a medianoche hora del Pacífico.
* La identificación de hablantes usa ventanas de 150 trozos (1-2 peticiones de texto por capítulo,
  con su propia cuota) y se cachea.

## Audio guardado
* Caché por frase: `cache/gemini_tts/<novela>/`, se borra a los **3 días** (al abrir la app y al leer).
* *Actores → Guardar audio* activado: al terminar un capítulo se escribe
  `<ruta de descarga>/TTS/<novela>/NNN - Título.wav` (+ `.txt` con el guion), junto a los epub.
  Esos archivos no caducan.

## Límites conocidos
* La API admite 2 voces por petición, por eso es una petición por turno (decenas de personajes).
* Solo hay 30 voces prediseñadas; para series grandes usa la Biblioteca ampliada o Voice design.
* El tono (*pitch*) del lector no se aplica; la velocidad sí (reproducción).
