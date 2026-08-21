package dev.onistone.onilink.packet;

import dev.onistone.onilink.control.ActionType;
import dev.onistone.onilink.control.ControlJson;
import dev.onistone.onilink.control.ValidatedActionPayload;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.data.HudElement;
import org.cloudburstmc.protocol.bedrock.data.HudVisibility;
import org.cloudburstmc.protocol.bedrock.data.LevelEvent;
import org.cloudburstmc.protocol.bedrock.data.ParticleType;
import org.cloudburstmc.protocol.bedrock.data.camera.CameraFadeInstruction;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BossEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.CameraInstructionPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundCloseFormPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.ModalFormRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlaySoundPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetHudPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetTitlePacket;
import org.cloudburstmc.protocol.bedrock.packet.StopSoundPacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.cloudburstmc.protocol.bedrock.packet.ToastRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.TransferPacket;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Constructs semantic packets against the actual destination codec and proves that they encode
 * before a caller may send them. Numeric packet IDs are deliberately absent from this API.
 */
public final class OniPacketFactory {
    public static final int DEFAULT_MAX_ENCODED_BYTES = 256 * 1024;
    private static final Set<ActionType> CLIENT_ACTIONS = Set.of(
            ActionType.SEND_MESSAGE, ActionType.SEND_TITLE, ActionType.SEND_SUBTITLE,
            ActionType.SEND_ACTIONBAR, ActionType.SEND_TOAST, ActionType.SHOW_FORM,
            ActionType.CLOSE_FORM, ActionType.SHOW_OVERLAY, ActionType.HIDE_OVERLAY,
            ActionType.SCREEN_FADE, ActionType.PLAY_SOUND,
            ActionType.STOP_SOUND, ActionType.SPAWN_PARTICLE, ActionType.SET_PRIVATE_WEATHER,
            ActionType.CLEAR_PRIVATE_WEATHER, ActionType.SET_HUD_VISIBILITY,
            ActionType.RESET_HUD_VISIBILITY, ActionType.CLEAR_CAMERA,
            ActionType.CREATE_BOSSBAR, ActionType.UPDATE_BOSSBAR, ActionType.REMOVE_BOSSBAR,
            ActionType.TRANSFER_PLAYER);

    private final int maximumEncodedBytes;
    private final PacketBuildMetrics metrics = new PacketBuildMetrics();

    public OniPacketFactory() {
        this(DEFAULT_MAX_ENCODED_BYTES);
    }

    public OniPacketFactory(int maximumEncodedBytes) {
        if (maximumEncodedBytes < 1_024 || maximumEncodedBytes > 4 * 1024 * 1024) {
            throw new IllegalArgumentException("maximum encoded packet size must be 1024..4194304");
        }
        this.maximumEncodedBytes = maximumEncodedBytes;
    }

    public PacketBuildMetrics metrics() {
        return metrics;
    }

    public boolean hasReviewedClientImplementation(ActionType action) {
        return CLIENT_ACTIONS.contains(action)
                || action == ActionType.START_PACKET_TRACE
                || action == ActionType.STOP_PACKET_TRACE
                || action == ActionType.KICK_PLAYER;
    }

    public PacketBuildResult buildClientbound(
            BedrockCodec clientCodec,
            ActionType action,
            ValidatedActionPayload payload,
            Vector3f fallbackPosition,
            long syntheticEntityId,
            int generatedFormId
    ) {
        return build(clientCodec, action, payload, fallbackPosition, syntheticEntityId, generatedFormId, true);
    }

    public PacketBuildResult buildBackendBound(
            BedrockCodec backendCodec,
            ActionType action,
            ValidatedActionPayload payload,
            Vector3f fallbackPosition,
            long syntheticEntityId,
            int generatedFormId
    ) {
        return build(backendCodec, action, payload, fallbackPosition, syntheticEntityId, generatedFormId, false);
    }

    private PacketBuildResult build(
            BedrockCodec codec,
            ActionType action,
            ValidatedActionPayload payload,
            Vector3f fallbackPosition,
            long syntheticEntityId,
            int generatedFormId,
            boolean clientbound
    ) {
        PacketBuildResult result;
        try {
            if (codec == null || action == null || payload == null) {
                result = PacketBuildResult.rejected("codec, action, and payload are required");
            } else if (!clientbound && CLIENT_ACTIONS.contains(action)) {
                result = PacketBuildResult.unsupported(action + " is a client-only semantic action");
            } else {
                List<BedrockPacket> packets = create(action, new Values(payload.values()),
                        fallbackPosition == null ? Vector3f.ZERO : fallbackPosition,
                        syntheticEntityId, generatedFormId);
                result = packets == null
                        ? PacketBuildResult.unsupported(action + " has no reviewed packet implementation")
                        : dryEncode(codec, packets);
            }
        } catch (IllegalArgumentException exception) {
            result = PacketBuildResult.rejected(exception.getMessage());
        } catch (RuntimeException exception) {
            result = new PacketBuildResult(PacketBuildResult.Status.ENCODE_FAILED, List.of(), 0,
                    "packet construction failed: " + exception.getClass().getSimpleName());
        }
        metrics.mark(result.status());
        return result;
    }

    private PacketBuildResult dryEncode(BedrockCodec codec, List<BedrockPacket> packets) {
        int total = 0;
        for (BedrockPacket packet : packets) {
            if (codec.getPacketDefinition(packet.getClass()) == null) {
                return PacketBuildResult.unsupported(packet.getClass().getSimpleName()
                        + " is absent from protocol " + codec.getProtocolVersion());
            }
            ByteBuf buffer = Unpooled.buffer();
            try {
                codec.tryEncode(codec.createHelper(), buffer, packet);
                total = Math.addExact(total, buffer.readableBytes());
                if (total > maximumEncodedBytes) {
                    return PacketBuildResult.rejected("encoded packet output exceeds " + maximumEncodedBytes + " bytes");
                }
            } catch (Exception exception) {
                return new PacketBuildResult(PacketBuildResult.Status.ENCODE_FAILED, List.of(), 0,
                        packet.getClass().getSimpleName() + " cannot encode for protocol "
                                + codec.getProtocolVersion() + ": " + safeMessage(exception));
            } finally {
                buffer.release();
            }
        }
        return new PacketBuildResult(PacketBuildResult.Status.SUPPORTED, packets, total, "");
    }

    private static List<BedrockPacket> create(
            ActionType action, Values values, Vector3f fallbackPosition, long entityId, int formId
    ) {
        return switch (action) {
            case SEND_MESSAGE -> List.of(text(TextPacket.Type.SYSTEM, values.text("message", 4_096)));
            case SEND_ACTIONBAR -> List.of(title(SetTitlePacket.Type.ACTIONBAR, values.text("message", 4_096), values));
            case SEND_TITLE -> List.of(title(SetTitlePacket.Type.TITLE, values.text("title", 4_096), values));
            case SEND_SUBTITLE -> List.of(title(SetTitlePacket.Type.SUBTITLE, values.text("subtitle", 4_096), values));
            case SEND_TOAST -> List.of(toast(values));
            case SHOW_FORM -> List.of(form(values, formId));
            case CLOSE_FORM -> List.of(new ClientboundCloseFormPacket());
            case SHOW_OVERLAY -> List.of(title(SetTitlePacket.Type.ACTIONBAR,
                    values.text("content", 4_096), values));
            case HIDE_OVERLAY -> List.of(title(SetTitlePacket.Type.CLEAR, "", values));
            case SCREEN_FADE -> List.of(fade(values));
            case PLAY_SOUND -> List.of(playSound(values, fallbackPosition));
            case STOP_SOUND -> List.of(stopSound(values));
            case SPAWN_PARTICLE -> List.of(particle(values, fallbackPosition));
            case SET_PRIVATE_WEATHER -> List.of(weather(values, true, fallbackPosition));
            case CLEAR_PRIVATE_WEATHER -> List.of(weather(values, false, fallbackPosition));
            case SET_HUD_VISIBILITY -> List.of(hud(values, false));
            case RESET_HUD_VISIBILITY -> List.of(hud(values, true));
            case CLEAR_CAMERA -> List.of(clearCamera());
            case CREATE_BOSSBAR -> List.of(boss(values, entityId, BossEventPacket.Action.CREATE));
            case UPDATE_BOSSBAR -> List.of(boss(values, entityId, BossEventPacket.Action.UPDATE_NAME),
                    boss(values, entityId, BossEventPacket.Action.UPDATE_PERCENTAGE),
                    boss(values, entityId, BossEventPacket.Action.UPDATE_STYLE));
            case REMOVE_BOSSBAR -> List.of(boss(values, entityId, BossEventPacket.Action.REMOVE));
            case TRANSFER_PLAYER -> List.of(transfer(values));
            default -> null;
        };
    }

    private static TextPacket text(TextPacket.Type type, String message) {
        TextPacket packet = new TextPacket();
        packet.setType(type);
        packet.setSourceName("");
        packet.setMessage(message);
        packet.setNeedsTranslation(false);
        packet.setXuid("");
        packet.setFilteredMessage(message);
        return packet;
    }

    private static SetTitlePacket title(SetTitlePacket.Type type, String message, Values values) {
        SetTitlePacket packet = new SetTitlePacket();
        packet.setType(type);
        packet.setText(message);
        packet.setFilteredTitleText(message);
        packet.setFadeInTime(values.integer("fadeInTicks", 10, 0, 20 * 60));
        packet.setStayTime(values.integer("stayTicks", 70, 0, 20 * 60 * 60));
        packet.setFadeOutTime(values.integer("fadeOutTicks", 20, 0, 20 * 60));
        packet.setXuid("");
        packet.setPlatformOnlineId("");
        return packet;
    }

    private static ToastRequestPacket toast(Values values) {
        ToastRequestPacket packet = new ToastRequestPacket();
        packet.setTitle(values.text("title", 512));
        packet.setContent(values.text("content", 2_048));
        return packet;
    }

    private static ModalFormRequestPacket form(Values values, int formId) {
        if (formId < 1) throw new IllegalArgumentException("generated form ID must be positive");
        String type = values.optionalText("type", 32, "form").toLowerCase(Locale.ROOT);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("type", switch (type) {
            case "form", "modal", "custom_form" -> type;
            default -> throw new IllegalArgumentException("form type must be form, modal, or custom_form");
        });
        document.put("title", values.text("title", 512));
        switch (type) {
            case "form" -> {
                document.put("content", values.optionalText("content", 4_096, ""));
                List<Object> buttons = values.list("buttons", 1, 100);
                List<Map<String, Object>> safeButtons = new ArrayList<>();
                for (Object item : buttons) {
                    Map<String, Object> button = Values.object(item, "button");
                    safeButtons.add(Map.of("text", Values.string(button.get("text"), "button text", 256)));
                }
                document.put("buttons", safeButtons);
            }
            case "modal" -> {
                document.put("content", values.text("content", 4_096));
                document.put("button1", values.text("confirmText", 256));
                document.put("button2", values.text("cancelText", 256));
            }
            case "custom_form" -> document.put("content", values.typedFormElements());
            default -> throw new IllegalStateException("validated form type changed");
        }
        String json = ControlJson.encode(document);
        if (json.length() > 64 * 1024) throw new IllegalArgumentException("form JSON exceeds 65536 characters");
        ModalFormRequestPacket packet = new ModalFormRequestPacket();
        packet.setFormId(formId);
        packet.setFormData(json);
        return packet;
    }

    private static CameraInstructionPacket fade(Values values) {
        float in = values.decimal("fadeInSeconds", 0.5f, 0f, 30f);
        float wait = values.decimal("holdSeconds", 0f, 0f, 300f);
        float out = values.decimal("fadeOutSeconds", 0.5f, 0f, 30f);
        int red = values.integer("red", 0, 0, 255);
        int green = values.integer("green", 0, 0, 255);
        int blue = values.integer("blue", 0, 0, 255);
        CameraInstructionPacket packet = new CameraInstructionPacket();
        packet.setFadeInstruction(new CameraFadeInstruction(
                new CameraFadeInstruction.TimeData(in, wait, out), new Color(red, green, blue)));
        return packet;
    }

    private static PlaySoundPacket playSound(Values values, Vector3f fallback) {
        PlaySoundPacket packet = new PlaySoundPacket();
        packet.setSound(values.namespaced("sound", 128));
        packet.setPosition(values.position(fallback));
        packet.setVolume(values.decimal("volume", 1f, 0f, 16f));
        packet.setPitch(values.decimal("pitch", 1f, 0.01f, 16f));
        packet.setLoopCount(values.integer("loopCount", 0, 0, 100));
        return packet;
    }

    private static StopSoundPacket stopSound(Values values) {
        StopSoundPacket packet = new StopSoundPacket();
        boolean all = values.bool("all", false);
        packet.setStoppingAllSound(all);
        packet.setSoundName(all ? "" : values.namespaced("sound", 128));
        packet.setStopMusicLegacy(values.bool("stopMusic", false));
        return packet;
    }

    private static LevelEventPacket particle(Values values, Vector3f fallback) {
        ParticleType type;
        try {
            type = ParticleType.valueOf(values.text("particle", 64).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("particle is not in the reviewed runtime particle mapping");
        }
        LevelEventPacket packet = new LevelEventPacket();
        packet.setType(type);
        packet.setPosition(values.position(fallback));
        packet.setData(values.integer("data", 0, Integer.MIN_VALUE, Integer.MAX_VALUE));
        return packet;
    }

    private static LevelEventPacket weather(Values values, boolean set, Vector3f fallback) {
        String weather = values.optionalText("weather", 16, "rain").toLowerCase(Locale.ROOT);
        LevelEventPacket packet = new LevelEventPacket();
        packet.setType(switch (weather) {
            case "rain" -> set ? LevelEvent.START_RAINING : LevelEvent.STOP_RAINING;
            case "thunder" -> set ? LevelEvent.START_THUNDERSTORM : LevelEvent.STOP_THUNDERSTORM;
            default -> throw new IllegalArgumentException("weather must be rain or thunder");
        });
        packet.setPosition(fallback);
        packet.setData(set ? values.integer("strength", 65_535, 0, 65_535) : 0);
        return packet;
    }

    private static SetHudPacket hud(Values values, boolean reset) {
        SetHudPacket packet = new SetHudPacket();
        List<Object> elements = values.list("elements", reset ? 0 : 1, HudElement.values().length);
        if (elements.isEmpty()) {
            packet.getElements().addAll(List.of(HudElement.values()));
        } else {
            for (Object element : elements) {
                try {
                    packet.getElements().add(HudElement.valueOf(
                            Values.string(element, "HUD element", 64).toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("unknown HUD element");
                }
            }
        }
        packet.setVisibility(reset ? HudVisibility.RESET : HudVisibility.HIDE);
        return packet;
    }

    private static CameraInstructionPacket clearCamera() {
        CameraInstructionPacket packet = new CameraInstructionPacket();
        packet.setClear(true);
        return packet;
    }

    private static BossEventPacket boss(Values values, long entityId, BossEventPacket.Action action) {
        if (entityId == 0) throw new IllegalArgumentException("a non-zero synthetic boss entity ID is required");
        BossEventPacket packet = new BossEventPacket();
        packet.setBossUniqueEntityId(entityId);
        packet.setPlayerUniqueEntityId(entityId);
        packet.setAction(action);
        packet.setTitle(values.optionalText("title", 512, ""));
        packet.setFilteredTitle(values.optionalText("title", 512, ""));
        packet.setHealthPercentage(values.decimal("progress", 1f, 0f, 1f));
        packet.setDarkenSky(values.bool("darkenSky", false) ? 1 : 0);
        packet.setColor(values.integer("color", 0, 0, 7));
        packet.setOverlay(values.integer("overlay", 0, 0, 2));
        return packet;
    }

    private static TransferPacket transfer(Values values) {
        TransferPacket packet = new TransferPacket();
        packet.setAddress(values.host("host"));
        packet.setPort(values.integer("port", -1, 1, 65_535));
        packet.setReloadWorld(values.bool("reloadWorld", false));
        return packet;
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        return message.replaceAll("(?i)(token|secret|authorization|jwt)=[^ ,}]+", "$1=<redacted>");
    }

    private static final class Values {
        private final Map<String, Object> values;

        private Values(Map<String, Object> values) {
            this.values = values;
        }

        private String text(String key, int maximum) {
            return string(values.get(key), key, maximum);
        }

        private String optionalText(String key, int maximum, String fallback) {
            Object value = values.get(key);
            return value == null ? fallback : string(value, key, maximum);
        }

        private String namespaced(String key, int maximum) {
            String value = text(key, maximum);
            if (!value.matches("[a-z0-9_.:-]+")) throw new IllegalArgumentException(key + " is not a safe runtime identifier");
            return value;
        }

        private String host(String key) {
            String value = text(key, 253);
            if (!value.matches("[A-Za-z0-9.:-]+") || value.startsWith("-") || value.contains("..")) {
                throw new IllegalArgumentException(key + " is not a valid host or literal address");
            }
            return value;
        }

        private boolean bool(String key, boolean fallback) {
            Object value = values.get(key);
            if (value == null) return fallback;
            if (!(value instanceof Boolean bool)) throw new IllegalArgumentException(key + " must be boolean");
            return bool;
        }

        private int integer(String key, int fallback, int minimum, int maximum) {
            Object value = values.get(key);
            if (value == null) {
                if (fallback < minimum) throw new IllegalArgumentException(key + " is required");
                return fallback;
            }
            if (!(value instanceof Number number)) throw new IllegalArgumentException(key + " must be numeric");
            double decimal = number.doubleValue();
            int result = number.intValue();
            if (!Double.isFinite(decimal) || decimal != result || result < minimum || result > maximum) {
                throw new IllegalArgumentException(key + " must be an integer in " + minimum + ".." + maximum);
            }
            return result;
        }

        private float decimal(String key, float fallback, float minimum, float maximum) {
            Object value = values.get(key);
            if (value == null) return fallback;
            if (!(value instanceof Number number)) throw new IllegalArgumentException(key + " must be numeric");
            float result = number.floatValue();
            if (!Float.isFinite(result) || result < minimum || result > maximum) {
                throw new IllegalArgumentException(key + " must be in " + minimum + ".." + maximum);
            }
            return result;
        }

        private List<Object> list(String key, int minimum, int maximum) {
            Object value = values.get(key);
            if (value == null && minimum == 0) return List.of();
            if (!(value instanceof List<?> list) || list.size() < minimum || list.size() > maximum) {
                throw new IllegalArgumentException(key + " must contain " + minimum + ".." + maximum + " items");
            }
            return List.copyOf(list);
        }

        private Vector3f position(Vector3f fallback) {
            Object value = values.get("position");
            if (value == null) return fallback;
            Map<String, Object> position = object(value, "position");
            return Vector3f.from(coordinate(position, "x"), coordinate(position, "y"), coordinate(position, "z"));
        }

        private List<Map<String, Object>> typedFormElements() {
            List<Object> source = list("elements", 1, 100);
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : source) {
                Map<String, Object> element = object(item, "form element");
                String type = string(element.get("type"), "form element type", 32);
                String text = string(element.get("text"), "form element text", 512);
                result.add(switch (type) {
                    case "label" -> Map.of("type", "label", "text", text);
                    case "input" -> Map.of("type", "input", "text", text,
                            "placeholder", optionalString(element.get("placeholder"), 256, ""),
                            "default", optionalString(element.get("default"), 512, ""));
                    case "toggle" -> Map.of("type", "toggle", "text", text,
                            "default", element.get("default") instanceof Boolean bool && bool);
                    default -> throw new IllegalArgumentException("unsupported custom form element " + type);
                });
            }
            return result;
        }

        private static float coordinate(Map<String, Object> object, String key) {
            Object value = object.get(key);
            if (!(value instanceof Number number) || !Float.isFinite(number.floatValue())
                    || Math.abs(number.floatValue()) > 30_000_000f) {
                throw new IllegalArgumentException("position." + key + " must be a finite world coordinate");
            }
            return number.floatValue();
        }

        private static Map<String, Object> object(Object value, String label) {
            if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException(label + " must be an object");
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException(label + " keys must be strings");
                result.put(key, entry.getValue());
            }
            return Map.copyOf(result);
        }

        private static String string(Object value, String label, int maximum) {
            if (!(value instanceof String string)) throw new IllegalArgumentException(label + " must be text");
            String clean = string.strip();
            if (clean.isEmpty() || clean.length() > maximum || clean.indexOf('\0') >= 0) {
                throw new IllegalArgumentException(label + " must contain 1.." + maximum + " safe characters");
            }
            return clean;
        }

        private static String optionalString(Object value, int maximum, String fallback) {
            if (value == null) return fallback;
            return string(value, "form value", maximum);
        }
    }
}
