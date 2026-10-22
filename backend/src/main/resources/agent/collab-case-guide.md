# Guía de Collab CASE

## Dashboard y proyectos
El Dashboard lista proyectos. Usa **Nuevo proyecto** para crear uno. OWNER puede renombrar o eliminar; EDITOR edita diagramas; VIEWER solo consulta.

## Versiones
Todo proyecto tiene `main`. En ProjectDetail usa el selector de versión. **Nueva versión** clona los diagramas y secuencias de la versión origen. Las copias son independientes. `main` no se renombra ni elimina. No existen merge, pull requests ni diff.

## Diagramas y editor
Usa **Nuevo diagrama** desde la versión activa. En el editor, la toolbar de Apollon permite crear clases. Selecciona una clase para editar nombre, atributos y métodos. Las relaciones se crean conectando clases y seleccionando el tipo apropiado.

## Multiplicidades y many-to-many
Edita las multiplicidades de ambos extremos de una asociación. Para muchos-a-muchos usa `*`, `0..*` o `1..*` en ambos extremos. No existe un botón “Crear tabla intermedia”: SQL y codegen generan automáticamente la join table. Si la relación tiene atributos, modela una clase asociativa.

## Colaboración y locks
Los usuarios conectados comparten cambios por Yjs/WebSocket. Un elemento bloqueado por otra persona no puede ser modificado hasta liberarse. Cada copia de versión tiene otro diagramId y otra sala.

## IA UML, voz e imagen
El panel IA UML ayuda a modelar clases, atributos, métodos y relaciones. Voz transcribe instrucciones e imagen extrae sugerencias. Este panel es distinto del Agente de Ayuda.

## Secuencias y exportación
**Secuencia** genera un artefacto desde un escenario. **Exportar** ofrece JSON, SQL, PlantUML y XMI 2.1 para Enterprise Architect.

## Generación
**Código** valida y descarga un backend Spring Boot y el cliente móvil generado. El proyecto generado incluye API, persistencia y sincronización móvil.

## Renombrar y eliminar
Los menús `...` permiten renombrar o eliminar según el rol. Eliminar un diagrama afecta solo a la versión actual. Eliminar un proyecto requiere escribir exactamente su nombre y elimina sus versiones y artefactos.
