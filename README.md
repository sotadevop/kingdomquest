# KingdomQuest

Fabric mod for Minecraft 26.3. Found a settlement with a Royal Charter and grow it from a tent
to a citadel; builders put up every building in front of you. Design: `claude/kingdom-design.md`
in the Minecraft project.

- Build: `./gradlew build` (Java 25). CI builds every push and publishes the jar to `ci-jar`.
- `tools/buildings.py` generates every building template (JSON under `src/main/resources/kingdomquest/buildings`),
  including the road network, which is routed around the buildings. Run it after any change and commit the result.
- `tools/check.py` puts a whole settlement together at every tier and style and tests it the way a player meets it:
  overlaps between tracks, blocks hanging on nothing, roads through buildings, doors without a road, and a walk
  simulation (can you reach every door, bed, store and room without jumping?). CI runs it on every push.
- `tools/preview.py` renders any part of an assembled settlement (`KQ_FETCH=1` downloads the vanilla assets it needs
  from misode/mcmeta `26.3-assets` into `ref/`), `tools/render.py` is the renderer behind it.
  misode/mcmeta `26.3-assets`), plus the build checklist.
