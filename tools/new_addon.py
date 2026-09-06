"""Generate an OniLink API 1 addon and an executable lifecycle contract check."""

from __future__ import annotations

import argparse
import re
import shutil
from pathlib import Path


def generate(destination: Path, name: str, package: str) -> None:
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]{0,63}", name):
        raise ValueError("name must be a safe addon identifier")
    if not re.fullmatch(r"[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+", package):
        raise ValueError("package must be a dotted lowercase Java package")
    destination.mkdir(parents=True, exist_ok=False)
    source = destination / "src/main/java" / package.replace(".", "/")
    source.mkdir(parents=True)
    resources = destination / "src/main/resources"
    resources.mkdir(parents=True)
    (source / "Addon.java").write_text(
        f"""package {package};

import dev.onistone.onilink.plugin.OniLinkPlugin;
import dev.onistone.onilink.plugin.PluginContext;
import dev.onistone.onilink.platform.events.OniEventType;

public final class Addon implements OniLinkPlugin {{
    private AutoCloseable subscription;
    @Override public void onEnable(PluginContext context) {{
        subscription = context.subscribe("provider", "main", OniEventType.PLAYER_AUTHENTICATED,
                event -> context.info("A player authenticated on the configured proxy."));
        context.info("Enabled using addon API " + context.apiVersion());
    }}
    @Override public void onDisable() {{
        if (subscription != null) try {{ subscription.close(); }} catch (Exception ignored) {{ }}
    }}
}}
""",
        encoding="utf-8",
    )
    (resources / "onilink-plugin.properties").write_text(
        f"name={name}\nmain={package}.Addon\napi-version=1\nversion=0.1.0\n"
        "permissions=events.subscribe\ndepends=\n",
        encoding="utf-8",
    )
    (destination / "settings.gradle.kts").write_text(
        f'rootProject.name = "{name}"\n', encoding="utf-8"
    )
    (destination / "build.gradle.kts").write_text(
        f"""plugins {{ java }}
version = "0.1.0"
java {{ toolchain {{ languageVersion.set(JavaLanguageVersion.of(21)) }} }}
val oniLinkJar = providers.environmentVariable("ONILINK_JAR").orNull
    ?: error("Set ONILINK_JAR to the built OniLink.jar")
dependencies {{ compileOnly(files(oniLinkJar)) }}
val contract by sourceSets.creating {{
    compileClasspath += sourceSets.main.get().output + files(oniLinkJar)
    runtimeClasspath += sourceSets.main.get().output + files(oniLinkJar)
}}
val addonContract by tasks.registering(JavaExec::class) {{
    dependsOn(tasks.named(contract.classesTaskName))
    classpath = contract.runtimeClasspath
    mainClass.set("{package}.AddonContract")
}}
tasks.check {{ dependsOn(addonContract) }}
""",
        encoding="utf-8",
    )
    tests = destination / "src/contract/java" / package.replace(".", "/")
    tests.mkdir(parents=True)
    (tests / "AddonContract.java").write_text(
        f"""package {package};
import dev.onistone.onilink.plugin.*;
import dev.onistone.onilink.protocol.*;
import dev.onistone.onilink.platform.events.*;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
public final class AddonContract {{
    public static void main(String[] args) throws Exception {{
        AtomicBoolean registered = new AtomicBoolean(), closed = new AtomicBoolean();
        PluginContext context = new PluginContext() {{
            public Path dataFolder() {{ return Path.of("build/contract-data"); }}
            public dev.onistone.onilink.config.ProxyConfig proxyConfig() {{ return null; }}
            public void info(String text) {{ }}
            public void addProtocolUpgrade(CanonicalProtocol a, CanonicalProtocol b, PacketTranslator t) {{ throw new AssertionError("undeclared protocol permission"); }}
            public void addTrustedListener(TrustedListenerSpec s) {{ throw new AssertionError("undeclared listener permission"); }}
            public AutoCloseable subscribe(String tenant, String proxy, OniEventType type, Consumer<OniEvent> consumer) {{
                registered.set(true); consumer.accept(OniEvent.of(type, tenant, proxy, java.util.Map.of()));
                return () -> closed.set(true);
            }}
        }};
        Addon addon = new Addon(); addon.onEnable(context); addon.onProxyReady(); addon.onDisable();
        if (!registered.get() || !closed.get()) throw new AssertionError("addon lifecycle did not release its registration");
        System.out.println("Addon API 1 lifecycle contract passed.");
    }}
}}
""",
        encoding="utf-8",
    )
    repository = Path(__file__).resolve().parents[1]
    for relative in (
        "gradlew",
        "gradlew.bat",
        "gradle/wrapper/gradle-wrapper.jar",
        "gradle/wrapper/gradle-wrapper.properties",
    ):
        origin = repository / "OniLink" / relative
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(origin, target)
    (destination / ".gitignore").write_text(
        ".gradle/\nbuild/\n*.pk8\n", encoding="utf-8"
    )
    (destination / "README.md").write_text(
        "# " + name + "\n\n"
        "Set `JAVA_HOME` to JDK 21 and `ONILINK_JAR` to an OniLink jar with addon API 1.\n"
        "Run `./gradlew check jar` (Windows: `.\\gradlew.bat check jar`).\n"
        "Copy the jar from `build/libs` into the proxy's `plugins` directory and restart.\n\n"
        "Addon permissions describe use of the provided API; trusted addons run inside the JVM\n"
        "and are not sandboxed. Declare dependencies as `Name@exact-version` separated by commas.\n"
        "Change the event tenant/proxy scope in Addon.java to your intended network.\n",
        encoding="utf-8",
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--name", required=True)
    parser.add_argument("--package", required=True)
    args = parser.parse_args()
    generate(args.directory, args.name, args.package)
    print(f"Generated {args.name} in {args.directory}")


if __name__ == "__main__":
    main()
