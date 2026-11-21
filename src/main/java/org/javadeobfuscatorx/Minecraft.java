package org.javadeobfuscatorx;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class Minecraft {
    final List<String> metadata = new ArrayList<>();
    final Set<String> signals = new TreeSet<>();
    private final Set<String> entrypoints = new TreeSet<>();
    private final Set<String> modEntrypoints = new TreeSet<>();

    Minecraft(Archive archive) throws IOException {
        for (String file : List.of("plugin.yml", "paper-plugin.yml", "bungee.yml", "velocity-plugin.json",
                "fabric.mod.json", "META-INF/mods.toml", "META-INF/neoforge.mods.toml")) {
            var entry = archive.resources.get(file);
            if (entry == null) continue;
            metadata.add(Inspection.row(file, "descriptor", switch (file) {
                case "plugin.yml" -> "Bukkit/Spigot/Paper/Purpur/Folia descriptor";
                case "paper-plugin.yml" -> "Paper plugin descriptor";
                case "bungee.yml" -> "BungeeCord descriptor";
                case "fabric.mod.json" -> "Fabric mod descriptor";
                case "META-INF/mods.toml" -> "Forge-family mod descriptor";
                case "META-INF/neoforge.mods.toml" -> "NeoForge mod descriptor";
                default -> "Velocity descriptor";
            }));
            if (file.endsWith(".toml")) {
                signals.add(file.contains("neoforge") ? "NeoForge mod metadata" : "Forge-family mod metadata");
                continue;
            }
            if (entry.getSize() > 128 * 1024) {
                metadata.add(Inspection.row(file, "warning", "Descriptor exceeds parsing budget"));
                continue;
            }
            try {
                var settings = LoadSettings.builder().setCodePointLimit(128 * 1024)
                        .setMaxAliasesForCollections(0).setAllowRecursiveKeys(false).build();
                Object data = new Load(settings).loadFromString(new String(archive.read(entry), StandardCharsets.UTF_8));
                if (data instanceof Map<?, ?> map) {
                    for (String key : List.of("name", "id", "version", "api-version", "folia-supported", "main",
                            "bootstrapper", "loader", "commands", "permissions", "dependencies", "depend",
                            "softdepend", "loadbefore", "libraries", "depends", "mixins", "accessWidener", "jars")) {
                        Object value = map.get(key);
                        if (value == null) continue;
                        if (value instanceof String name && Set.of("main", "bootstrapper", "loader").contains(key)) {
                            entrypoints.add(name.replace('.', '/'));
                        }
                        metadata.add(Inspection.row(file, key, summarize(value)));
                    }
                    if (file.equals("fabric.mod.json")) fabric(map);
                } else {
                    metadata.add(Inspection.row(file, "warning", "Expected a descriptor mapping"));
                }
            } catch (RuntimeException | StackOverflowError invalid) {
                metadata.add(Inspection.row(file, "warning", invalid.getClass().getSimpleName()));
            }
        }
    }

    private void fabric(Map<?, ?> map) {
        signals.add("Fabric mod metadata");
        if (!(map.get("entrypoints") instanceof Map<?, ?> groups)) return;
        for (var group : groups.entrySet().stream().limit(40).toList()) {
            if (!(group.getKey() instanceof String id) || !(group.getValue() instanceof List<?> entries)) continue;
            for (Object entry : entries.stream().limit(40).toList()) {
                Object value = entry instanceof Map<?, ?> options ? options.get("value") : entry;
                if (!(value instanceof String name) || name.length() > 1024) continue;
                metadata.add(Inspection.row("fabric.mod.json", "entrypoint " + id, name));
                String declaringClass = name.split("::", 2)[0];
                if (javax.lang.model.SourceVersion.isName(declaringClass)) modEntrypoints.add(declaringClass.replace('.', '/'));
            }
        }
    }

    private static String summarize(Object value) {
        if (value instanceof Map<?, ?> map) return scalars(map.keySet().stream().limit(40).toList());
        if (value instanceof List<?> list) {
            return scalars(list.stream().limit(40).map(item -> item instanceof Map<?, ?> map
                    && map.get("id") instanceof String id ? id : item).toList());
        }
        return scalar(value);
    }

    private static String scalars(List<?> values) {
        return String.join(", ", values.stream().map(Minecraft::scalar).toList());
    }

    private static String scalar(Object value) {
        if (value instanceof String text) return text.substring(0, Math.min(text.length(), 300));
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        return "(structured data)";
    }

    String role(ClassNode node) {
        if (modEntrypoints.contains(node.name) || node.interfaces.stream().anyMatch(i -> i.startsWith("net/fabricmc/api/")
                && i.endsWith("ModInitializer"))) return "Mod";
        if (entrypoints.contains(node.name) || "org/bukkit/plugin/java/JavaPlugin".equals(node.superName)
                || "net/md_5/bungee/api/plugin/Plugin".equals(node.superName)
                || node.visibleAnnotations != null && node.visibleAnnotations.stream().anyMatch(
                        annotation -> annotation.desc.equals("Lcom/velocitypowered/api/plugin/Plugin;"))) return "Plugin";
        if (node.interfaces.contains("org/bukkit/event/Listener")) return "Listener";
        if (node.interfaces.contains("org/bukkit/command/CommandExecutor")
                || node.interfaces.contains("org/bukkit/command/TabExecutor")) return "Command";
        if ("java/lang/RuntimeException".equals(node.superName)
                || "java/lang/Exception".equals(node.superName)) return "ApplicationException";
        return "Type";
    }

    void inspectClass(ClassNode node) {
        if (node.visibleAnnotations != null) {
            for (var annotation : node.visibleAnnotations) {
                if (annotation.desc.equals("Lnet/minecraftforge/fml/common/Mod;")) {
                    modEntrypoints.add(node.name);
                    signals.add("Forge @Mod annotation");
                } else if (annotation.desc.equals("Lnet/neoforged/fml/common/Mod;")) {
                    modEntrypoints.add(node.name);
                    signals.add("NeoForge @Mod annotation");
                }
            }
        }
        String role = role(node);
        if (Set.of("Plugin", "Listener", "Command", "Mod").contains(role)) {
            signals.add(role + " API class");
        }
        if (node.visibleAnnotations != null && node.visibleAnnotations.stream().anyMatch(
                annotation -> annotation.desc.equals("Lcom/velocitypowered/api/plugin/Plugin;"))) {
            entrypoints.add(node.name);
            signals.add("Velocity @Plugin annotation");
        }
        for (var method : node.methods) {
            if (method.visibleAnnotations == null) continue;
            for (var annotation : method.visibleAnnotations) {
                switch (annotation.desc) {
                    case "Lorg/bukkit/event/EventHandler;" -> signals.add("Bukkit @EventHandler");
                    case "Lnet/md_5/bungee/event/EventHandler;" -> signals.add("BungeeCord @EventHandler");
                    case "Lcom/velocitypowered/api/event/Subscribe;" -> signals.add("Velocity @Subscribe");
                    default -> { }
                }
            }
        }
    }

    void inspectCall(MethodInsnNode call) {
        String owner = call.owner;
        if (owner.startsWith("org/bukkit/scheduler/")) signals.add("Bukkit scheduler");
        if (owner.startsWith("io/papermc/paper/threadedregions/") || owner.startsWith("org/bukkit/")
                && Set.of("getRegionScheduler", "getGlobalRegionScheduler", "getAsyncScheduler", "getScheduler")
                    .contains(call.name) && call.desc.contains("threadedregions/")) signals.add("Folia region scheduler");
        if (owner.startsWith("net/minecraft/") || owner.startsWith("org/bukkit/craftbukkit/")) signals.add("NMS/CraftBukkit reference");
        if (owner.startsWith("com/github/retrooper/packetevents/") || owner.startsWith("io/github/retrooper/packetevents/")) {
            signals.add("PacketEvents reference");
        }
        if (owner.startsWith("org/purpurmc/")) signals.add("Purpur API reference");
        if (owner.startsWith("io/papermc/paper/")) signals.add("Paper API reference");
        if (owner.startsWith("com/velocitypowered/api/")) signals.add("Velocity API reference");
        if (owner.startsWith("net/md_5/bungee/api/")) signals.add("BungeeCord API reference");
        if ((owner.startsWith("org/bukkit/") || owner.startsWith("net/md_5/bungee/api/"))
                && call.name.equals("hasPermission")) signals.add("Permission check");
    }

    void finish(Set<String> classes) {
        for (String entrypoint : entrypoints) {
            metadata.add(Inspection.row("entrypoint", entrypoint, classes.contains(entrypoint) ? "present" : "missing in selected view"));
        }
        for (String entrypoint : modEntrypoints) {
            metadata.add(Inspection.row("mod entrypoint", entrypoint, classes.contains(entrypoint) ? "present" : "missing in selected view"));
        }
        for (String name : classes) {
            if (name.startsWith("com/github/retrooper/packetevents/") || name.contains("/com/github/retrooper/packetevents/")) {
                signals.add("Bundled PacketEvents classes");
            }
            if (name.startsWith("org/objectweb/asm/") || name.startsWith("com/google/common/")
                    || name.startsWith("org/snakeyaml/") || name.startsWith("com/google/gson/")) {
                signals.add("Bundled third-party package: " + name.substring(0, name.lastIndexOf('/')));
                if (signals.size() >= 100) break;
            }
        }
    }
}
