# Evidencia de ejecución

Fecha de verificación: 17 de octubre de 2026. Entorno: Windows 11, JDK 21,
Maven 3.10.0 y JGroups 5.0.0.Final.

La evidencia se genera con una prueba de integración real que crea tres
`JChannel` llamados Virginia, London y Bogota. No simula el transporte ni llama
directamente a los receptores. El clúster usa UDP, PING, SEQUENCER y
STATE_TRANSFER.

## Comando reproducible

```powershell
mvn -Dtest=JGroupsClusterIntegrationTest test
```

## Log obtenido

```text
GMS: address=Virginia, cluster=test-<uuid>, physical address=<ip>:<puerto>
GMS: address=London, cluster=test-<uuid>, physical address=<ip>:<puerto>
[EXPERIMENTO 1] Chat entregado a Virginia y London
[EXPERIMENTO 2] /store replicado: {framework=JGroups} == {framework=JGroups}
GMS: address=Bogota, cluster=test-<uuid>, physical address=<ip>:<puerto>
[EXPERIMENTO 3] Bogota recibió state transfer: {framework=JGroups}
[EXPERIMENTO 4] Bogota recuperó su WAL: {framework=JGroups, status=replicated}; replay duplicado ignorado

Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Se sustituyeron únicamente el UUID, la IP y los puertos efímeros por marcadores
para que el registro sea legible. Los mensajes de cada experimento son la salida
literal de `JGroupsClusterIntegrationTest`.

## Qué valida cada experimento

1. **Chat:** Virginia envía `Hola desde Virginia`; la prueba espera hasta que
   Virginia y London lo incorporan a su historial.
2. **Replicación:** Virginia envía una `StoreOperation`; la prueba compara los
   mapas durables de Virginia y London.
3. **State transfer:** Bogota entra después de la escritura, solicita el estado,
   recibe tanto chat como operaciones y participa en una escritura posterior.
4. **Recuperación y duplicados:** se reenvía el mismo UUID y se comprueba que
   cada historial siga teniendo dos operaciones. Después se cierran los canales
   y se abre nuevamente `Bogota.wal`; el mapa recuperado contiene las dos llaves.

Para una entrega con capturas, se puede ejecutar el mismo comando y capturar la
terminal completa; el resultado verificable no depende de una captura estática.
