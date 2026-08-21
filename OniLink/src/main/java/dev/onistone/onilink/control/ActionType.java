package dev.onistone.onilink.control;

/** The semantic, allowlisted OniControl action catalog. Raw packet and memory operations are absent. */
public enum ActionType {
    SEND_MESSAGE(ExecutionPlane.CLIENT_ONLY, ControlRole.OPERATOR, false),
    SEND_TITLE(ExecutionPlane.CLIENT_ONLY, ControlRole.OPERATOR, false),
    SEND_SUBTITLE(ExecutionPlane.CLIENT_ONLY, ControlRole.OPERATOR, false),
    SEND_ACTIONBAR(ExecutionPlane.CLIENT_ONLY, ControlRole.OPERATOR, false),
    SEND_TOAST(ExecutionPlane.CLIENT_ONLY, ControlRole.OPERATOR, false),
    SHOW_FORM(ExecutionPlane.CLIENT_ONLY, ControlRole.OPERATOR, false),
    CLOSE_FORM(ExecutionPlane.CLIENT_ONLY, ControlRole.OPERATOR, false),
    SHOW_OVERLAY(ExecutionPlane.CLIENT_ONLY, ControlRole.OPERATOR, false),
    HIDE_OVERLAY(ExecutionPlane.CLIENT_ONLY, ControlRole.OPERATOR, false),
    SCREEN_FADE(ExecutionPlane.CLIENT_ONLY, ControlRole.OPERATOR, false),
    PLAY_SOUND(ExecutionPlane.CLIENT_ONLY, ControlRole.OPERATOR, false),
    STOP_SOUND(ExecutionPlane.CLIENT_ONLY, ControlRole.OPERATOR, false),
    SPAWN_PARTICLE(ExecutionPlane.CLIENT_ONLY, ControlRole.OPERATOR, false),
    SET_PRIVATE_WEATHER(ExecutionPlane.CLIENT_ONLY, ControlRole.OPERATOR, false),
    CLEAR_PRIVATE_WEATHER(ExecutionPlane.CLIENT_ONLY, ControlRole.OPERATOR, false),
    SET_HUD_VISIBILITY(ExecutionPlane.CLIENT_ONLY, ControlRole.OPERATOR, false),
    RESET_HUD_VISIBILITY(ExecutionPlane.CLIENT_ONLY, ControlRole.OPERATOR, false),
    SET_CAMERA(ExecutionPlane.CLIENT_ONLY, ControlRole.OPERATOR, false),
    CLEAR_CAMERA(ExecutionPlane.CLIENT_ONLY, ControlRole.OPERATOR, false),
    START_CAMERA_SEQUENCE(ExecutionPlane.CLIENT_ONLY, ControlRole.OPERATOR, false),
    STOP_CAMERA_SEQUENCE(ExecutionPlane.CLIENT_ONLY, ControlRole.OPERATOR, false),
    CREATE_BOSSBAR(ExecutionPlane.CLIENT_ONLY, ControlRole.OPERATOR, false),
    UPDATE_BOSSBAR(ExecutionPlane.CLIENT_ONLY, ControlRole.OPERATOR, false),
    REMOVE_BOSSBAR(ExecutionPlane.CLIENT_ONLY, ControlRole.OPERATOR, false),
    START_PACKET_TRACE(ExecutionPlane.CLIENT_ONLY, ControlRole.OPERATOR, false),
    STOP_PACKET_TRACE(ExecutionPlane.CLIENT_ONLY, ControlRole.OPERATOR, false),
    KICK_PLAYER(ExecutionPlane.CLIENT_ONLY, ControlRole.OPERATOR, true),
    TRANSFER_PLAYER(ExecutionPlane.CLIENT_ONLY, ControlRole.OPERATOR, true),
    ADD_VIRTUAL_COMMAND(ExecutionPlane.VIRTUALIZED, ControlRole.OPERATOR, false),
    REMOVE_VIRTUAL_COMMAND(ExecutionPlane.VIRTUALIZED, ControlRole.OPERATOR, false),
    CLEAR_VIRTUAL_COMMANDS(ExecutionPlane.VIRTUALIZED, ControlRole.ADMIN, true),

    PING(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.VIEWER, false),
    GET_CAPABILITIES(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.VIEWER, false),
    GET_BACKEND_HEALTH(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.VIEWER, false),
    GET_ONLINE_PLAYERS(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.VIEWER, false),
    GET_PLAYER_STATE(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.VIEWER, false),
    PREPARE_DRAIN(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    CLOSE_PLAYER_CONTAINERS(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    SAVE_WORLD(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    GET_PLAYER_POSITION(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.VIEWER, false),
    GET_PLAYER_INVENTORY(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, false),
    GET_PLAYER_EFFECTS(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, false),
    GET_PLAYER_SCORE(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, false),
    GET_PLAYER_PERMISSIONS(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, false),
    TELEPORT(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    CHANGE_DIMENSION(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    SET_SPAWN_POINT(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    GIVE_ITEM(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    REMOVE_ITEM(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    SET_INVENTORY_SLOT(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    SET_ARMOR_SLOT(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    SET_OFFHAND_SLOT(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    CLEAR_INVENTORY(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    REPLACE_INVENTORY(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    DAMAGE_ITEM(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    REPAIR_ITEM(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    SET_HEALTH(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    HEAL(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    DAMAGE(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    SET_HUNGER(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    SET_SATURATION(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    SET_EXPERIENCE(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    ADD_EXPERIENCE(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    SET_LEVEL(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    SET_GAMEMODE(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    SET_ABILITY(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    ADD_EFFECT(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    REMOVE_EFFECT(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    CLEAR_EFFECTS(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    ADD_TAG(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    REMOVE_TAG(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    SET_SCORE(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    ADD_SCORE(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    REMOVE_SCORE(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    SET_PERMISSION(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    SET_BLOCK(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    FILL_BLOCK_REGION(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    REPLACE_BLOCK_REGION(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    SPAWN_ENTITY(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    REMOVE_ENTITY(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    MOUNT_ENTITY(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),
    DISMOUNT_ENTITY(ExecutionPlane.BACKEND_AUTHORITATIVE, ControlRole.ADMIN, true),

    OPEN_VIRTUAL_INVENTORY(ExecutionPlane.VIRTUALIZED, ControlRole.OPERATOR, false),
    CLOSE_VIRTUAL_INVENTORY(ExecutionPlane.VIRTUALIZED, ControlRole.OPERATOR, false),
    SPAWN_PRIVATE_ENTITY(ExecutionPlane.VIRTUALIZED, ControlRole.ADMIN, false),
    UPDATE_PRIVATE_ENTITY(ExecutionPlane.VIRTUALIZED, ControlRole.ADMIN, false),
    MOVE_PRIVATE_ENTITY(ExecutionPlane.VIRTUALIZED, ControlRole.ADMIN, false),
    SET_PRIVATE_ENTITY_METADATA(ExecutionPlane.VIRTUALIZED, ControlRole.ADMIN, false),
    SET_PRIVATE_ENTITY_EQUIPMENT(ExecutionPlane.VIRTUALIZED, ControlRole.ADMIN, false),
    REMOVE_PRIVATE_ENTITY(ExecutionPlane.VIRTUALIZED, ControlRole.ADMIN, false),
    CLEAR_PRIVATE_ENTITIES(ExecutionPlane.VIRTUALIZED, ControlRole.ADMIN, true),
    SPAWN_PRIVATE_NPC(ExecutionPlane.VIRTUALIZED, ControlRole.ADMIN, false),
    SPAWN_PRIVATE_HOLOGRAM(ExecutionPlane.VIRTUALIZED, ControlRole.ADMIN, false),
    SET_FAKE_BLOCK(ExecutionPlane.VIRTUALIZED, ControlRole.ADMIN, false),
    SET_FAKE_BLOCK_REGION(ExecutionPlane.VIRTUALIZED, ControlRole.ADMIN, false),
    RESTORE_FAKE_BLOCK(ExecutionPlane.VIRTUALIZED, ControlRole.ADMIN, false),
    RESTORE_FAKE_BLOCK_REGION(ExecutionPlane.VIRTUALIZED, ControlRole.ADMIN, false),
    CLEAR_FAKE_BLOCKS(ExecutionPlane.VIRTUALIZED, ControlRole.ADMIN, true),
    HIDE_ENTITY(ExecutionPlane.VIRTUALIZED, ControlRole.ADMIN, false),
    SHOW_ENTITY(ExecutionPlane.VIRTUALIZED, ControlRole.ADMIN, false),
    REPLACE_ENTITY_VISUAL(ExecutionPlane.VIRTUALIZED, ControlRole.ADMIN, false),
    RESET_ENTITY_VISUAL(ExecutionPlane.VIRTUALIZED, ControlRole.ADMIN, false),
    SET_PRIVATE_COSMETIC(ExecutionPlane.VIRTUALIZED, ControlRole.ADMIN, false),
    CLEAR_PRIVATE_COSMETIC(ExecutionPlane.VIRTUALIZED, ControlRole.ADMIN, false),
    CREATE_PRIVATE_WORLD_BORDER(ExecutionPlane.VIRTUALIZED, ControlRole.ADMIN, false),
    UPDATE_PRIVATE_WORLD_BORDER(ExecutionPlane.VIRTUALIZED, ControlRole.ADMIN, false),
    REMOVE_PRIVATE_WORLD_BORDER(ExecutionPlane.VIRTUALIZED, ControlRole.ADMIN, false),
    START_SCENE(ExecutionPlane.VIRTUALIZED, ControlRole.OPERATOR, false),
    STOP_SCENE(ExecutionPlane.VIRTUALIZED, ControlRole.OPERATOR, false);

    private final ExecutionPlane executionPlane;
    private final ControlRole minimumRole;
    private final boolean destructive;

    ActionType(ExecutionPlane executionPlane, ControlRole minimumRole, boolean destructive) {
        this.executionPlane = executionPlane;
        this.minimumRole = minimumRole;
        this.destructive = destructive;
    }

    public ExecutionPlane executionPlane() {
        return executionPlane;
    }

    public ControlRole minimumRole() {
        return minimumRole;
    }

    public boolean destructive() {
        return destructive;
    }
}
