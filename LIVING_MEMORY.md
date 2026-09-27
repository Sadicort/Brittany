# Historia viva de Brittany

## Modelo mental

La memoria duradera ya no es un historial de chat. El texto bruto solo vive en el contexto inmediato acotado y el archivo historico conserva una compresion progresiva:

```text
mensaje observado
  -> ConversationDigest (ventana de un canal)
  -> SessionDigest (sesion condensada)
  -> CollectiveMemory (evento, posiblemente multicanal)
  -> Era
  -> historia viva generada al consultar
```

Cada evento conserva participantes, fechas, temas, sesiones, relevancia, referencias posteriores y sus fuentes. Al encontrar el mismo tema cerca en el tiempo en canales distintos, Brittany fusiona las ventanas en un acontecimiento multicanal sin guardar los mensajes completos.

Al cerrar una sesion, Ollama recibe una copia efimera y acotada para extraer JSON estructurado: titulo, resumen, etiquetas, interpretacion y hechos `sujeto -> predicado -> objeto`. Solo se aceptan hechos completos con confianza minima, cuyo sujeto y objeto puedan comprobarse en la fuente; luego se deduplican antes de persistir. La confianza declarada por el modelo no basta y el texto de entrada no se escribe en la memoria historica. Si Ollama falla, permanece el resumen estadistico determinista y el bot sigue funcionando. Puede desactivarse con `MEMORY_SEMANTIC_EXTRACTION=false`.

## Grafo y procedencia

`CollectiveMemory.relations` representa aristas tipadas (`RELATED_TO`, `GENERATED`, `CAUSED`, `MENTIONED_AFTER`, `PRECEDED`, `MERGED_FROM`). Cada arista incluye confianza y evidencia. Las relaciones antiguas por ID siguen siendo compatibles.

`sources` conserva por canal:

- ID y nombre del canal;
- primer y ultimo instante observados;
- cantidad agregada de mensajes.

Esto permite explicar de donde surgio un recuerdo y volver a evaluar sus permisos en cada lectura.

## Seguridad transversal

Recibir un mensaje autoriza observarlo en ese instante, pero no autoriza revelarlo para siempre. Antes de recuperar un evento se calcula la interseccion de canales que:

1. el bot puede ver actualmente;
2. la persona que consulta puede ver actualmente.

Un evento multicanal solo se muestra si todos sus canales fuente pertenecen a esa interseccion. Si se revoca un permiso, el recuerdo deja de recuperarse de inmediato. Los DM no alimentan la memoria colectiva y los patrones sensibles siguen excluidos.

Para continuar conversaciones entre canales sin esperar a que cierre un evento, existe ademas un puente efimero en RAM: conserva como maximo 200 fragmentos por comunidad durante dos horas, nunca incluye contenido sensible y aplica la misma interseccion de permisos. Este puente desaparece al reiniciar; no sustituye a la memoria comprimida.

## Cultura, usuarios y tiempo

- Los perfiles personales incluyen primera/ultima actividad, canales, temas, hitos y generacion; solo `/mievolucion` y `/miperfil` los muestran de forma efimera al propio usuario.
- Las generaciones pueden nacer alrededor de un evento de gran impacto y muestran miembros observados y activos durante los ultimos 90 dias. Siguen siendo propuestas hasta confirmacion humana.
- Frases cortas y emojis pasan por un filtro en memoria. Solo se persisten al llegar a cinco usos, evitando almacenar ruido ilimitado.
- Los estados culturales son `EMERGING`, `POPULAR`, `HISTORICAL`, `FADING` y `LOST`.
- `/viajar` reconstruye una fotografia historica desde evidencia disponible y etiqueta sus cifras como observadas, no como totales exactos de Discord.
- La identidad actual usa una ventana movil de 90 dias.

## Hechos e interpretaciones

Los eventos separan `factualSummary` de `interpretation` e `interpretationConfidence`. Las respuestas y el contexto enviado a Ollama marcan las interpretaciones expresamente. Brittany debe reconocer ausencia de evidencia en lugar de completar huecos.

## Comandos

Los comandos anteriores se conservan y se agregan:

- `/viajar momento:2025-08`
- `/tendencias`
- `/perdidos`
- `/identidad`
- `/generaciones`
- `/mievolucion` (efimero y personal)
- `/origen frase:eso fue calculado`
- `/memestado` (diagnostico efimero de eventos, ventanas y umbrales)

`/historia`, `/cronica`, `/eventos`, `/evento`, `/museo`, `/eras` y `/veteranos` ahora filtran por las fuentes autorizadas de la persona que consulta. `/historia` organiza el resultado en capitulos usando eras confirmadas o etapas cronologicas provisionales.

## Registro y diagnostico de comandos

El alcance predeterminado es `MEMORY_COMMAND_SCOPE=guild`: registra los comandos directamente en cada servidor al arrancar y tambien cuando el bot entra a un servidor nuevo. Esto evita la demora de propagacion de los comandos globales. Con `MEMORY_CLEAN_DUPLICATE_GLOBALS=true`, al iniciar se eliminan exclusivamente las copias globales cuyos nombres pertenecen a este modulo; asi Discord no muestra una copia global y otra del servidor. `global` conserva el registro global y `both` habilita ambos deliberadamente.

Cada interaccion se reconoce inmediatamente con `deferReply`; una excepcion ya no deja el mensaje generico "la aplicacion no respondio". Los errores de registro y respuesta incluyen el comando y servidor en la consola. Si Discord rechaza el registro, hay que volver a invitar el bot con el scope `applications.commands`.

La configuracion se lee con prioridad entorno, propiedad Java y archivo `.env`. `.env` esta ignorado por Git. No existe ningun token predeterminado dentro del codigo.

## Persistencia

Ademas de los archivos existentes en `data/memory`, se crean:

- `cultural-trends.jsonl`
- `generations.jsonl`

Todos son logs JSONL versionados y compactables. En un apagado normal se realiza un `flush` atomico para no perder contadores que aun no alcanzaron el siguiente lote de persistencia.

`MEMORY_UNLIMITED=true` evita recortar mensajes, hechos, etiquetas, relaciones y candidatos culturales por cantidad. El antiguo `data/ai-memory.json` se migra automaticamente desde el objeto JSON anterior a registros JSONL append-only, aunque mantiene el nombre para compatibilidad. La historia almacenada puede crecer sin un maximo logico; el limite real pasa a ser el espacio disponible en disco.

La recuperacion por consulta sigue seleccionando solo el contexto relevante que cabe en Ollama y las respuestas se paginan/resumen para respetar los 2.000 caracteres de Discord. Estos limites de transporte no eliminan nada de lo almacenado.

## Limites deliberados

- "Primera aparicion" significa primera aparicion observada desde que la memoria esta activa; no afirma ser la primera de toda la vida del servidor.
- Miembros, actividad y canales de un viaje temporal son minimos observados, no el total administrativo de Discord.
- Las eras, fronteras generacionales e interpretaciones automaticas se presentan como propuestas hasta confirmacion humana.
- Para millones de entidades o varios procesos, `MemoryStore` sigue siendo el punto de sustitucion por PostgreSQL/pgvector sin cambiar JDA ni el servicio de IA.
