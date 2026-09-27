# IA conversacional con Ollama

El bot responde cuando alguien lo menciona, por ejemplo:

```text
@DISCRIMINE ¿qué hablamos ayer?
```

Los mensajes de cada canal se guardan en `data/ai-memory.json` y sobreviven a reinicios. Con `MEMORY_UNLIMITED=true` no se recortan por cantidad. El archivo se migra automaticamente a un log JSONL append-only; la memoria duradera y transversal se guarda ademas como eventos, hechos, relaciones, eras y preferencias extraidas.

La voz de Brittany se carga automáticamente desde `src/main/java/personalizacion`. `Principalpers.txt` contiene las reglas principales, `DirectricesBrittany.txt` contiene las directrices complementarias de emoción y emojis, y `personalizacion1.txt` a `personalizacion4.txt` se usan como ejemplos de estilo en cada consulta a Ollama. Esto es contexto de instrucciones (no un entrenamiento permanente del modelo), por lo que los cambios en esos archivos se aplican al reiniciar el bot.

La identidad de Brittany es una regla central del programa y no se guarda en `data/ai-memory.json`: siempre se presenta como `Soy Brittany la Community Manager oficial de DISCRIMINE.` y no revela información sobre código, creadores, compañías o tecnología interna. Las preguntas directas sobre esos temas reciben una respuesta protegida.

## Preparación

1. Inicia Ollama y descarga el modelo:

   ```powershell
   ollama serve
   ollama pull llama3.2:latest
   ```

2. Configura el token del bot. En PowerShell, para la sesión actual:

   ```powershell
   $env:DISCRIMINE_BOT_TOKEN = "TU_TOKEN_NUEVO"
   ```

3. La configuración incluida en `data/ollama.toml` ya usa:

   ```toml
   [ollama]
   host = "http://127.0.0.1:11434"
   model = "llama3.2:latest"
   ```

   Las variables `OLLAMA_HOST` y `OLLAMA_MODEL` tienen prioridad si existen.

4. Ejecuta el proyecto con Maven desde la raíz:

   ```powershell
   mvn clean package
   java -cp "target/classes;..." discriminebot.Main
   ```

   El comando exacto para ejecutar el JAR depende de cómo se gestione el classpath en tu IDE. IntelliJ puede resolver las dependencias del `pom.xml` automáticamente.

## Variables opcionales

> Memoria nueva: el historial inmediato no se recorta cuando `MEMORY_UNLIMITED=true`. Los eventos publicos extraidos, relaciones, eras y perfiles personales se guardan separadamente en `data/memory`. Consulta [MEMORY_ARCHITECTURE.md](MEMORY_ARCHITECTURE.md) para privacidad, comandos, configuracion y escalado.

Variables adicionales: `MEMORY_ENABLED`, `MEMORY_DIRECTORY`, `MEMORY_EVENT_GAP_MINUTES`, `MEMORY_EVENT_MIN_MESSAGES` y `MEMORY_EVENT_MIN_PARTICIPANTS`.

- `OLLAMA_TIMEOUT_SECONDS`: tiempo máximo de una respuesta, por defecto `120`.
- `AI_MAX_HISTORY_MESSAGES`: limite por canal cuando `MEMORY_UNLIMITED=false`; `0` significa ilimitado.
- `MEMORY_UNLIMITED`: `true` por defecto; desactiva recortes por cantidad en la persistencia.
- `AI_MEMORY_FILE`: ruta del archivo de memoria, por defecto `data/ai-memory.json`.
- `AI_SYSTEM_PROMPT`: personalidad e instrucciones adicionales del bot.
- `AI_PERSONALITY_DIR`: carpeta alternativa donde se encuentran `Principalpers.txt` y los ejemplos `.txt`.

El token que estaba escrito en el código debe revocarse y regenerarse en el portal de Discord, porque un token expuesto deja de ser seguro. El programa ahora solo lee `DISCRIMINE_BOT_TOKEN`.
