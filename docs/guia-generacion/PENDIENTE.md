# Pendiente

- **Asociar registros muchos a muchos desde la app móvil generada.** El backend generado ya lo
  soporta (API con `productoIds` y sincronización con `productoSyncIds`, probado el 10-nov-2026),
  pero la app (`mobile-template`) solo tiene comandos para crear, listar y renombrar por nombre.
  Idea: un comando del tipo «agrega Mouse al pedido 3» que mande la relación en la mutación.
- `/api/mobile/changes` devuelve `productoIds` (ids de la base) y no `productoSyncIds`; si la app
  empieza a usar relaciones, conviene que el cambio traiga los `syncId`.
- **Checklist de pruebas manuales** de todos los requisitos del 2do parcial (pedido para el 11-nov-2026), con el formato del checklist de MirrorIA.
