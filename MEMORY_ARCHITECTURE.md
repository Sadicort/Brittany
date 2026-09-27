# Memoria colectiva y perfil conversacional

> La ampliacion de historia viva, grafo, generaciones, tendencias, viaje temporal y memoria multicanal se documenta en [LIVING_MEMORY.md](LIVING_MEMORY.md).

## Arquitectura encontrada

El proyecto existente se conserva. No se reemplazaron JDA, Ollama, la personalidad ni los comandos anteriores.

| Responsabilidad existente | Punto real de integración |
|---|---|
| Entrada de mensajes | `Main.MessageListener#onMessageReceived` |
| Identidad de usuario | `MessageReceivedEvent#getAuthor`; el ID estable de Discord es la clave |
| Comunidad y canal | `MessageReceivedEvent#getGuild` y `getChannel` |
| Generación de respuesta | `AiConversationService#respondToMention` → `OllamaClient#/api/chat` |
| Contexto inmediato | `ConversationMemoryStore`, JSON por servidor/canal |
| Almacenamiento auxiliar | `BotContextStore`, archivo `properties` |
| Configuración | entorno, `data/ollama.toml` y archivos de personalidad |
| Permisos | permisos de Discord/JDA; no existía una capa administrativa adicional |
| APIs disponibles | gateway y comandos de Discord mediante JDA; chat local de Ollama |

No había una base de datos relacional. El historial JSON existente guardaba mensajes completos y hacía una búsqueda léxica lineal por canal. Ahora queda tratado únicamente como contexto inmediato acotado; no es la memoria histórica.

## Flujo implementado

```text
mensaje
  → JDA identifica usuario, comunidad y canal
  → conversación normal continúa en AiConversationService
  → worker de memoria observa señales sin bloquear JDA
      → actualiza perfil PERSONAL del autor, si dio consentimiento
      → agrega estadísticas a una ventana pública y temporal
      → descarta contenido sensible y luego descarta el texto bruto
  → recuperación indexada
      → perfil del autor (nunca el de terceros)
      → eventos PUBLICOS relacionados
  → se agrega un bloque pequeño al prompt de Ollama
  → Ollama genera la respuesta con la personalidad existente
  → worker actualiza familiaridad y referencias históricas
```

Si la memoria falla, el error se registra y la conversación continúa. Las llamadas a Ollama y la extracción tampoco se ejecutan en el hilo de eventos de Discord.

## Almacenamiento del MVP

`data/memory` usa logs JSONL append-only, un registro por versión de entidad, con compactación. Esta decisión añade persistencia sin introducir una base de datos o servicio nuevo en el bot actual.

### Colección `collective-events`

- `id`, `guildId`, `channelId`
- `title`, `summary`, `startedAt`, `endedAt`
- `participantCount`, `messageCount`, `participantIds`
- `relevance`, `futureReferences`, `lastReferenceAt`
- `layers`: `EPISODIC`, `SOCIAL`, `CULTURAL`, `HISTORICAL`
- `tags`, `relatedMemoryIds`, `eraId`
- `privacy=PUBLIC`, `status`, `createdAt`, `updatedAt`

Índices activos: comunidad → IDs de evento y comunidad+término → IDs de evento. La recuperación no recorre el historial de mensajes.

### Colección `personal-profiles`

- clave compuesta `guildId:userId`
- controles `personalizationEnabled`, `learningEnabled`
- `interactions`, `familiarity`, `sharedHumor`
- preferencias de vocabulario cerrado con `value`, `confidence`, `explicit`, `updatedAt`
- `privacy=PERSONAL`

No guarda mensajes, secretos, temas privados ni descripciones psicológicas. Una preferencia explícita usa confianza 0.98. Las inferencias usan una media móvil y nunca pisan una preferencia explícita de alta confianza.

### Colección `eras`

- `id`, `guildId`, `name`, `description`
- intervalo temporal, estado de confirmación y eventos relacionados
- marca de propuesta automática

El MVP propone una era cuando detecta un cambio sostenido fuerte después de suficientes eventos. La propuesta no se presenta como confirmada.

### Contexto inmediato

`data/ai-memory.json` se mantiene por compatibilidad y se migra automaticamente a JSONL append-only. Con `MEMORY_UNLIMITED=true` no recorta entradas por canal. Solo una seleccion reciente y relevante llega a cada prompt; el resto continua almacenado. Con `MEMORY_UNLIMITED=false`, `AI_MAX_HISTORY_MESSAGES` vuelve a imponer un maximo.

## Creación y consolidación

Una ventana de actividad se separa después de 20 minutos sin mensajes. Para crear un evento necesita, por defecto, al menos 12 mensajes y 3 participantes. El texto sensible no genera frases, memes ni preferencias.

La relevancia determinista pondera:

- participantes: hasta 30 puntos;
- volumen: hasta 30;
- duración: hasta 20;
- señales culturales repetidas: hasta 20.

Emojis personalizados repetidos y frases públicas cortas repetidas pueden añadir la capa cultural. Coincidencia de participantes o marcadores culturales crea relaciones bidireccionales. Referencias posteriores como «otra vez» o «como antes» aumentan la puntuación, con una ventana anti-duplicados de seis horas. Al llegar a 70 se añade la capa histórica.

El nombre y resumen del MVP se crean de manera determinista para que la memoria no dependa de que Ollama esté disponible. El siguiente incremento previsto puede pedir a Ollama un nombre/resumen estructurado, pero el resultado debe seguir pasando por umbrales, privacidad y permisos deterministas antes de guardarse.

## Privacidad y permisos

La separación no depende de instrucciones al modelo:

- `PUBLIC`: únicamente eventos agregados de canales de comunidad;
- `PERSONAL`: perfil indexado por comunidad+usuario, recuperable solo para ese mismo usuario;
- `PRIVATE`: contexto inmediato del canal/DM, nunca promovido desde un DM;
- `PROHIBITED`: correo, teléfono, credenciales, direcciones/señales sensibles y contenido similar no generan memoria duradera.

Los DM jamás alimentan memoria colectiva. Los comandos de perfil responden de forma efímera. Confirmar, renombrar, fusionar, marcar irrelevante o eliminar eventos requiere propietario, Administrador o Gestionar servidor. El borrado personal y el borrado administrativo compactan inmediatamente el archivo correspondiente para quitar versiones previas, no dejan solo un tombstone lógico.

`/olvidarme confirmacion:CONFIRMAR` elimina el perfil. Con `bloquear:true` queda únicamente un control técnico de exclusión sin preferencias; evita crear memoria nueva.

## Comandos añadidos

- `/historia [tema]`, `/cronica [tema]`, `/eventos [tema]`
- `/evento id`, `/eras`, `/museo`, `/veteranos`
- `/miperfil`
- `/memoria accion:ver|activar|desactivar|aprender|noaprender`
- `/olvidarme confirmacion:CONFIRMAR [bloquear]`
- `/memadmin accion:confirmar|renombrar|editar|irrelevante|eliminar|fusionar ...`
- `/eraadmin accion:crear|renombrar|editar|confirmar|eliminar ...`

Se registran con `upsert`; no se borra ni reemplaza el catálogo de comandos existente. Las preguntas naturales mencionando al bot recuperan las mismas memorias mediante el flujo normal de IA.

## Escalado

La memoria profunda guarda ventanas comprimidas, perfiles y eventos sin un maximo logico. Opcionalmente tambien puede conservar indefinidamente el contexto inmediato en JSONL. Los logs historicos se compactan cada 5.000 escrituras y la consulta usa indices en memoria.

Cuando haya múltiples procesos o millones de memorias extraídas, `MemoryStore` es el límite de sustitución. Puede migrarse sin tocar JDA ni Ollama a PostgreSQL con estas tablas:

- `collective_memory`, índice `(guild_id, status, relevance DESC, started_at DESC)`;
- `memory_tag`, PK `(guild_id, tag, memory_id)` e índice `(guild_id, tag)`;
- `memory_participant`, PK `(memory_id, user_id)` e índice `(guild_id, user_id)`;
- `memory_relation`, PK `(source_id, relation_type, target_id)`;
- `community_era`, índice `(guild_id, started_at)`;
- `conversation_profile`, PK `(guild_id, user_id)`;
- `profile_preference`, PK `(guild_id, user_id, preference_key)`;
- opcional `memory_embedding vector`, con índice HNSW por comunidad.

La recuperación debe combinar filtro estructurado por `guild_id/privacy/status`, búsqueda de términos/FTS y, solo después, vecinos semánticos. Una cola persistente puede sustituir al executor local para extracción y consolidación. Ninguna consulta de respuesta necesita leer mensajes históricos completos.

## Configuración

```text
MEMORY_ENABLED=true
MEMORY_DIRECTORY=data/memory
MEMORY_EVENT_GAP_MINUTES=20
MEMORY_EVENT_MIN_MESSAGES=12
MEMORY_EVENT_MIN_PARTICIPANTS=3
MEMORY_UNLIMITED=true
AI_MAX_HISTORY_MESSAGES=0
MEMORY_COMMAND_SCOPE=guild
MEMORY_CLEAN_DUPLICATE_GLOBALS=true
```

Los umbrales se ajustan sin recompilar. `MEMORY_ENABLED=false` desactiva toda la capa nueva y deja intacta la conversación existente.

## Seguridad operativa

El token de Discord que estaba incrustado fue retirado del código y sustituido por un marcador en `.env.example`. Ese token ya fue expuesto en el repositorio y debe revocarse/regenerarse en Discord antes de volver a ejecutar el bot.
