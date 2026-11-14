# KingdomQuest

Fabric mod for Minecraft 26.3. Found a settlement with a Royal Charter and grow it from a tent
to a citadel; builders put up every building in front of you. Design: `claude/kingdom-design.md`
in the Minecraft project.

- Build: `./gradlew build` (Java 25). CI builds every push and publishes the jar to `ci-jar`.
- `tools/`: building generators and a preview renderer (`render.py`, needs vanilla assets from
  misode/mcmeta `26.3-assets`), plus the build checklist.
