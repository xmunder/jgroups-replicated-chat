# Evidencia de ejecución

Fecha de verificación: 18 de octubre de 2026. Entorno: Windows 11, JDK 21,
Maven 3.10.0 y JGroups 5.0.0.Final.

La evidencia se genera con una prueba de integración real que crea tres
`JChannel` llamados Virginia, London y Bogota. No simula el transporte ni llama
directamente a los receptores. El clúster usa UDP, PING, SEQUENCER y
STATE_TRANSFER.

## Comando reproducible

```powershell
mvn -Dtest=JGroupsClusterIntegrationTest test
```

Para volver a generar el archivo de evidencia desde PowerShell:

```powershell
mvn -Dtest=JGroupsClusterIntegrationTest test 2>&1 |
    Tee-Object -FilePath docs/evidencia/evidence.log
```

## Log obtenido

La salida original también está disponible en [`evidence.log`](evidence.log).
Esta es la salida completa transcrita del archivo, sin ocultar UUID, direcciones
ni puertos:

```text
[INFO] Scanning for projects...
[INFO]
[INFO] ----------------< com.example:jgroups-replicated-chat >-----------------
[INFO] Building JGroups replicated chat 1.0-SNAPSHOT
[INFO]   from pom.xml
[INFO] --------------------------------[ jar ]---------------------------------
[INFO] Loaded 24133 auto-discovered prefixes for remote repository central (prefixes-central.txt)
[INFO] Loaded 74 auto-discovered prefixes for remote repository apache.snapshots (prefixes-apache.snapshots.txt)
[INFO]
[INFO] --- resources:3.5.0:resources (default-resources) @ jgroups-replicated-chat ---
[INFO] Copying 1 resource from src\main\resources to target\classes
[INFO]
[INFO] --- compiler:3.13.0:compile (default-compile) @ jgroups-replicated-chat ---
[INFO] Nothing to compile - all classes are up to date.
[INFO]
[INFO] --- resources:3.5.0:testResources (default-testResources) @ jgroups-replicated-chat ---
[INFO] skip non existing resourceDirectory C:\Users\MSI\Documents\dev\AYGO\JGROUP\mi-proyecto\src\test\resources
[INFO]
[INFO] --- compiler:3.13.0:testCompile (default-testCompile) @ jgroups-replicated-chat ---
[INFO] Nothing to compile - all classes are up to date.
[INFO]
[INFO] --- surefire:3.6.0:test (default-test) @ jgroups-replicated-chat ---
[INFO] Using auto detected provider org.apache.maven.surefire.junitplatform.JUnitPlatformProvider
[INFO]
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running com.example.JGroupsClusterIntegrationTest
oct 18, 2026 12:47:00 P. M. org.jgroups.JChannel setAddress
INFO: local_addr: 54efaed6-361c-9048-4268-75d1c1bcf2a6, name: Virginia

-------------------------------------------------------------------
GMS: address=Virginia, cluster=test-203a9079-2459-4325-930a-7b32dfb35895, physical address=172.18.16.1:60745
-------------------------------------------------------------------
oct 18, 2026 12:47:03 P. M. org.jgroups.protocols.pbcast.ClientGmsImpl joinInternal
INFO: Virginia: no members discovered after 3024 ms: creating cluster as coordinator
oct 18, 2026 12:47:03 P. M. org.jgroups.JChannel setAddress
INFO: local_addr: c42acd10-c6aa-0edc-a00a-2a1517be7f73, name: London

-------------------------------------------------------------------
GMS: address=London, cluster=test-203a9079-2459-4325-930a-7b32dfb35895, physical address=172.18.16.1:61126
-------------------------------------------------------------------
[EXPERIMENTO 1] Chat entregado a Virginia y London
[EXPERIMENTO 2] /store replicado: {framework=JGroups} == {framework=JGroups}
oct 18, 2026 12:47:03 P. M. org.jgroups.JChannel setAddress
INFO: local_addr: a6ac58d0-45c1-0866-21e9-67c821f56c6f, name: Bogota

-------------------------------------------------------------------
GMS: address=Bogota, cluster=test-203a9079-2459-4325-930a-7b32dfb35895, physical address=172.18.16.1:61128
-------------------------------------------------------------------
[EXPERIMENTO 3] Bogota recibió state transfer: {framework=JGroups}
[EXPERIMENTO 4] Bogota recuperó su WAL: {framework=JGroups, status=replicated}; replay duplicado ignorado
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.150 s -- in com.example.JGroupsClusterIntegrationTest
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  6.742 s
[INFO] Finished at: 2026-10-18T12:47:04-05:00
[INFO] ------------------------------------------------------------------------
```

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
