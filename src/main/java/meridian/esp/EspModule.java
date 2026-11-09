package meridian.esp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.api.settings.SettingsSpec;
import meridian.core.api.Block;
import meridian.core.api.BlockPos;
import meridian.core.api.Box;
import meridian.core.api.DebugRender;
import meridian.core.api.EntityTracker;
import meridian.core.api.SelectionBus;
import meridian.core.api.Vec3;
import meridian.core.api.World;
import org.slf4j.Logger;

/**
 * meridian-esp — through-wall ESP for entities and blocks. Pure Layer-2,
 * built on top of {@code meridian-core}.
 *
 * <p>Boxes are drawn through meridian-core's {@link DebugRender#worldBox} —
 * the editor's trigger-volume display channel, depth-test-bypassed by design,
 * so the outlines stay visible through walls. No {@code meridian.protocol}
 * import; a Hytale protocol update cannot break the module.
 *
 * <h2>Two trackers</h2>
 *
 * <ul>
 *   <li><b>Entity ESP</b> — boxes every tracked entity within {@code radius}
 *       blocks of the local player (the player itself excluded), each by the
 *       hitbox the server gave its model. The box follows the entity by
 *       re-positioning on every tick (100 ms).</li>
 *   <li><b>Block ESP</b> — asks core once a second for the blocks within
 *       {@code radius} of the player whose type name contains the configured
 *       filter, nearest first, and boxes them. Core searches only what it was
 *       sent, so a huge radius costs nothing past the client's view distance,
 *       and finds a door or a bed once, at the cell it stands on.</li>
 * </ul>
 *
 * <h2>UI live-lists</h2>
 *
 * <p>Each section surfaces a {@code Nearest …} live-list of up to 100 rows
 * sorted by distance — what the box pass would draw, even with the toggle
 * off. Clicking a row publishes the target through {@link SelectionBus} so
 * another module (interaction-test's X/Y/Z fields) can act on it.
 *
 * <h2>Limits</h2>
 *
 * <p>The trigger-volume render the client uses tints occluded surfaces grey;
 * we set {@code groupColor} to keep the wireframe edges in the configured
 * salad-green, but the fill behind walls stays grey — that's a client-side
 * shader constant, not something a packet field controls.
 */
public class EspModule implements ProxyModule {

    private static final String USAGE = "blocks <name-contains> [radius] | entities [radius] [players]";

    /** Entity overlay refresh period — entities move, re-position each tick. */
    private static final Duration ENTITY_REFRESH = Duration.ofMillis(100);
    /** Block overlay refresh period — blocks are static, scan less often. */
    private static final Duration BLOCK_REFRESH = Duration.ofMillis(1000);
    /** Translucency of every ESP box. */
    private static final float OPACITY = 0.5f;
    /** Box colour — salad green. */
    private static final float BOX_R = 0.6f;
    private static final float BOX_G = 1.0f;
    private static final float BOX_B = 0.3f;
    /**
     * Entity box footprint / height, blocks, for an entity the server has given no hitbox (no
     * model sent yet, or one without a box): a rough humanoid.
     */
    private static final double ENT_W = 0.9;
    private static final double ENT_H = 2.0;
    /** The most blocks one scan boxes - the nearest of them. */
    private static final int BLOCK_LIMIT = 1024;
    /** Hard cap on rows surfaced to the UI live-lists — the lists exist to
     *  show the nearest few targets, not to dump every tracked object. */
    private static final int LIST_LIMIT = 100;

    private Logger log;
    private EntityTracker entities;
    private DebugRender debug;
    private World world;
    private SelectionBus selectionBus;

    // Live settings — each is mirrored from a SettingsSpec callback.
    private volatile boolean entityEnabled;
    private volatile boolean entityPlayersOnly;
    private volatile int entityRadius = 64;
    /** Max nearest entities to draw boxes for (the render budget). */
    private volatile int entityLimit = 128;
    private volatile boolean blockEnabled;
    private volatile String blockName = "";
    private volatile int blockRadius = 16;

    // Entity counts published by entityTick for the UI live-text.
    private volatile int entitiesInRange;
    private volatile int entitiesDrawn;

    // Trigger-volume ids placed in the last frame — for clean removal of
    // stale boxes when an entity vanishes or a block scrolls out of range.
    private Set<String> entityIds = Set.of();
    private Set<String> blockIds = Set.of();

    // UI-facing snapshots — pre-formatted rows of the nearest LIST_LIMIT
    // entities / blocks plus the parallel payload list used by row clicks.
    // Both the text and the payloads must be observed at the same snapshot
    // moment, so they live in one immutable record published via a single
    // volatile reference.
    private record EntitySnapshot(List<String> rows, int[] ids) {
        static final EntitySnapshot EMPTY = new EntitySnapshot(List.of(), new int[0]);
    }
    private record BlockSnapshot(List<String> rows, List<BlockPos> positions) {
        static final BlockSnapshot EMPTY = new BlockSnapshot(List.of(), List.of());
    }
    private volatile EntitySnapshot entitySnapshot = EntitySnapshot.EMPTY;
    private volatile BlockSnapshot blockSnapshot = BlockSnapshot.EMPTY;

    @Override
    public void onEnable(ModuleContext ctx) {
        this.log = ctx.getLogger();
        this.entities = ctx.services().require(EntityTracker.class);
        this.debug = ctx.services().require(DebugRender.class);
        this.world = ctx.services().require(World.class);
        // Soft dependency — keep working with an older core that lacks the bus.
        this.selectionBus = ctx.services().get(SelectionBus.class).orElse(null);

        // Toggles always start off — ESP is a deliberate action, not something
        // to silently resume after a restart. Radii and the block-name filter
        // are tuning the user wants back: persisted.
        ctx.registerSettings(SettingsSpec.builder()
                .section("Entity ESP", SettingsSpec.builder()
                        .bool("entityEnabled", "Enabled", false, v -> entityEnabled = v)
                        // Restrict the box/list pass to real players (entities
                        // the server sent a skin for) — hides mobs, props, etc.
                        .bool("entityPlayersOnly", "Players only", false,
                                v -> entityPlayersOnly = v)
                        // Beyond the client's view distance the server stops
                        // sending entity updates, so a huge radius costs us
                        // nothing — there's just nothing further to box.
                        .int_("entityRadius", "Radius", 1, 1024, 128, v -> entityRadius = v)
                        // Render budget: how many of the nearest in-range
                        // entities actually get a box. Caps client load on
                        // crowded servers where hundreds are in view.
                        .int_("entityLimit", "Render limit (nearest)", 1, 1024, 128,
                                v -> entityLimit = v)
                        .liveText("Entities — total / in range / drawn", this::entityCounts)
                        .liveList("Nearest entities (top " + LIST_LIMIT + ", click to share)",
                                () -> entitySnapshot.rows(),
                                this::onEntityRowClicked)
                        .build())
                .section("Block ESP", SettingsSpec.builder()
                        .bool("blockEnabled", "Enabled", false, v -> blockEnabled = v)
                        .string("blockName", "Block name (contains)", "",
                                v -> blockName = v == null ? "" : v)
                        // Beyond the client's view distance no chunks are loaded
                        // and the scan finds nothing — pick a value that covers
                        // what you can actually see.
                        .int_("blockRadius", "Radius", 1, 512, 16, v -> blockRadius = v)
                        .liveList("Nearest blocks (top " + LIST_LIMIT + ", click to share)",
                                () -> blockSnapshot.rows(),
                                this::onBlockRowClicked)
                        .build())
                .persistent("entityPlayersOnly", "entityRadius", "entityLimit",
                        "blockName", "blockRadius")
                .build());

        // What the lists would show, on the proxy's command channel: esp <conn> blocks|entities ...
        try {
            ctx.registerCommand("esp", USAGE, (args, json) -> command(args));
        } catch (NoSuchMethodError olderProxy) {
            // no command channel there
        }

        ctx.scheduler().scheduleAtFixedRate(this::entityTick, ENTITY_REFRESH, ENTITY_REFRESH);
        ctx.scheduler().scheduleAtFixedRate(this::blockTick, BLOCK_REFRESH, BLOCK_REFRESH);
        log.info("meridian-esp enabled — through-wall ESP via DebugRender.worldBox");
    }

    @Override
    public void onDisable() {
        entityEnabled = false;
        blockEnabled = false;
        if (debug != null && debug.available()) {
            for (String id : entityIds) debug.clearWorldBox(id);
            for (String id : blockIds) debug.clearWorldBox(id);
        }
        entityIds = Set.of();
        blockIds = Set.of();
        entitySnapshot = EntitySnapshot.EMPTY;
        blockSnapshot = BlockSnapshot.EMPTY;
    }

    /** Live-list click → publish the entity id to the cross-module bus. */
    private void onEntityRowClicked(int rowIndex) {
        if (selectionBus == null) return;
        EntitySnapshot s = entitySnapshot;  // one volatile read — text and ids match
        if (rowIndex < 0 || rowIndex >= s.ids().length) return;
        int id = s.ids()[rowIndex];
        selectionBus.publishEntity(id);
        log.info("meridian-esp: shared entity #{} via SelectionBus", id);
    }

    /** Live-list click → publish the block position to the cross-module bus. */
    private void onBlockRowClicked(int rowIndex) {
        if (selectionBus == null) return;
        BlockSnapshot s = blockSnapshot;
        if (rowIndex < 0 || rowIndex >= s.positions().size()) return;
        BlockPos p = s.positions().get(rowIndex);
        selectionBus.publishBlock(p);
        log.info("meridian-esp: shared block ({},{},{}) via SelectionBus",
                p.x(), p.y(), p.z());
    }

    /** An entity in range, with its squared distance from the player. */
    private record EntityHit(int id, Vec3 pos, double distSq) {}

    /** A matched block, with its squared distance from the player (to the block's centre). */
    private record BlockHit(int x, int y, int z, String name, double distSq) {}

    /**
     * Re-positions a through-wall box on every tracked entity within the
     * configured radius and refreshes the UI live-list snapshot. Boxes are
     * keyed by entity id so an {@code AddOrUpdateTriggerVolumeDisplay} with
     * the same key moves an existing box rather than spawning a new one.
     *
     * <p>The whole pass — list build + box draw — is gated by the Entity ESP
     * toggle: when it's off, both the list and the boxes go silent.
     */
    private void entityTick() {
        if (!entityEnabled) {
            // Clear stale boxes and list, then early-exit. No scanning while off.
            if (!entityIds.isEmpty()) {
                removeAll(entityIds);
                entityIds = Set.of();
            }
            if (!entitySnapshot.rows().isEmpty()) entitySnapshot = EntitySnapshot.EMPTY;
            entitiesInRange = 0;
            entitiesDrawn = 0;
            return;
        }
        Optional<Vec3> playerPos = entities.localPosition();
        if (playerPos.isEmpty()) {
            // No anchor yet — leave existing boxes and list in place until we
            // know where the player is.
            return;
        }
        // Every in-range entity once — we need it both for the sorted list and
        // for the box pass.
        List<EntityHit> hits = entitiesNear(playerPos.get(), entityRadius, entityPlayersOnly);

        // Render budget: draw the nearest `entityLimit` only.
        entitiesInRange = hits.size();
        int drawCount = Math.min(hits.size(), entityLimit);

        // UI snapshot: pre-formatted rows + the parallel id array clicks use.
        int rowCount = Math.min(hits.size(), LIST_LIMIT);
        List<String> rows = new ArrayList<>(rowCount);
        int[] ids = new int[rowCount];
        for (int i = 0; i < rowCount; i++) {
            EntityHit h = hits.get(i);
            rows.add(entityRow(h));
            ids[i] = h.id;
        }
        entitySnapshot = new EntitySnapshot(List.copyOf(rows), ids);

        // Box pass — needs a live client session. List already published above
        // so the UI updates even before the session binds.
        if (debug == null || !debug.available()) {
            entitiesDrawn = 0;
            if (!entityIds.isEmpty()) {
                removeAll(entityIds);
                entityIds = Set.of();
            }
            return;
        }
        Set<String> seen = new HashSet<>();
        // Draw only the nearest `drawCount` — `hits` is sorted by distance.
        for (int i = 0; i < drawCount; i++) {
            EntityHit h = hits.get(i);
            String vid = "esp_ent_" + h.id;
            seen.add(vid);
            Box b = entityBox(h);
            Vec3 c = b.center();
            debug.worldBox(vid, c.x(), c.y(), c.z(), b.width(), b.height(), b.depth(),
                    BOX_R, BOX_G, BOX_B, OPACITY);
        }
        for (String old : entityIds) {
            if (!seen.contains(old)) debug.clearWorldBox(old);
        }
        entityIds = seen;
        entitiesDrawn = drawCount;
    }

    /**
     * The entities within {@code radius} of the player at {@code pp}, nearest first - the local
     * player left out, and in players-only mode the mobs, props and projectiles.
     */
    private List<EntityHit> entitiesNear(Vec3 pp, int radius, boolean playersOnly) {
        List<EntityHit> hits = new ArrayList<>();
        for (int id : entities.nearby(radius, playersOnly)) {
            Optional<Vec3> pos = entities.positionOf(id);
            if (pos.isEmpty()) {
                continue;               // gone since the list was made
            }
            hits.add(new EntityHit(id, pos.get(), pos.get().distanceSqTo(pp)));
        }
        return hits;
    }

    /** An entity's row in the list. */
    private String entityRow(EntityHit h) {
        return String.format(Locale.ROOT,
                "#%-8d d=%6.1f  (%7.1f, %6.1f, %7.1f)  %s",
                h.id, Math.sqrt(h.distSq),
                h.pos.x(), h.pos.y(), h.pos.z(), entities.labelOf(h.id));
    }

    /**
     * The box drawn on an entity: the hitbox the server gave its model, else - no model sent yet,
     * or one without a box - a rough humanoid standing on the entity's feet.
     */
    private Box entityBox(EntityHit h) {
        return entities.boundsOf(h.id).orElseGet(() -> new Box(
                new Vec3(h.pos.x() - ENT_W / 2, h.pos.y(), h.pos.z() - ENT_W / 2),
                new Vec3(h.pos.x() + ENT_W / 2, h.pos.y() + ENT_H, h.pos.z() + ENT_W / 2)));
    }

    /** Live-text line: total tracked vs. how many are in range and drawn. */
    private String entityCounts() {
        return String.format(Locale.ROOT, "Total: %d   |   in range: %d   |   drawn: %d",
                entities.trackedCount(), entitiesInRange, entitiesDrawn);
    }

    /**
     * Finds the matching blocks around the player through core, nearest first,
     * updates the UI live-list, and boxes them.
     *
     * <p>The whole pass — search + list build + box draw — is gated by the Block
     * ESP toggle. When it's off, or with an empty name filter, both the list
     * and the boxes go silent and the search is skipped.
     */
    private void blockTick() {
        String needle = blockName;
        if (!blockEnabled || needle == null || needle.isBlank()) {
            if (!blockSnapshot.rows().isEmpty()) blockSnapshot = BlockSnapshot.EMPTY;
            if (!blockIds.isEmpty()) {
                removeAll(blockIds);
                blockIds = Set.of();
            }
            return;
        }
        Optional<Vec3> playerPos = entities.localPosition();
        if (playerPos.isEmpty()) {
            return;
        }
        // Same hits drive both the sorted UI list and the box pass.
        List<BlockHit> hits = blocksNear(playerPos.get(), needle, blockRadius);
        blockSnapshot = blockSnapshotOf(hits);

        // Box pass — needs a live client session. List already published above.
        if (debug == null || !debug.available()) {
            if (!blockIds.isEmpty()) {
                removeAll(blockIds);
                blockIds = Set.of();
            }
            return;
        }
        Set<String> seen = new HashSet<>();
        for (BlockHit h : hits) {
            String vid = "esp_blk_" + h.x + "_" + h.y + "_" + h.z;
            seen.add(vid);
            // Static blocks: only push a new box when it first appears.
            if (!blockIds.contains(vid)) {
                debug.worldBox(vid,
                        h.x + 0.5, h.y + 0.5, h.z + 0.5,
                        1.0, 1.0, 1.0,
                        BOX_R, BOX_G, BOX_B, OPACITY);
            }
        }
        for (String old : blockIds) {
            if (!seen.contains(old)) debug.clearWorldBox(old);
        }
        blockIds = seen;
    }

    /**
     * Every block within {@code radius} of the feet at {@code p} whose type name contains
     * {@code needle} (case counts), nearest first, at most {@link #BLOCK_LIMIT}.
     */
    private List<BlockHit> blocksNear(Vec3 p, String needle, int radius) {
        List<BlockHit> hits = new ArrayList<>();
        for (Block block : world.find(p, radius,
                t -> t.name() != null && t.name().contains(needle), BLOCK_LIMIT)) {
            double cx = block.x() + 0.5 - p.x();
            double cy = block.y() + 0.5 - p.y();
            double cz = block.z() + 0.5 - p.z();
            hits.add(new BlockHit(block.x(), block.y(), block.z(),
                    block.type().name(), cx * cx + cy * cy + cz * cz));
        }
        return hits;
    }

    /**
     * The list's rows for the nearest {@link #LIST_LIMIT} blocks — the type-name column padded to
     * the widest match (capped) so the distance/coordinate columns line up — with the parallel
     * positions list row clicks resolve through.
     */
    private static BlockSnapshot blockSnapshotOf(List<BlockHit> hits) {
        int rowCount = Math.min(hits.size(), LIST_LIMIT);
        int nameWidth = 0;
        for (int i = 0; i < rowCount; i++) {
            int len = hits.get(i).name.length();
            if (len > nameWidth) nameWidth = len;
        }
        if (nameWidth > 32) nameWidth = 32;
        List<String> rows = new ArrayList<>(rowCount);
        List<BlockPos> positions = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            BlockHit h = hits.get(i);
            rows.add(String.format(Locale.ROOT,
                    "%-" + nameWidth + "s  d=%6.1f  (%6d, %4d, %6d)",
                    h.name, Math.sqrt(h.distSq), h.x, h.y, h.z));
            positions.add(new BlockPos(h.x, h.y, h.z));
        }
        return new BlockSnapshot(List.copyOf(rows), List.copyOf(positions));
    }

    /**
     * {@code blocks <name> [radius]} and {@code entities [radius] [players]}: what the lists would
     * show around the player now, with each entity's box - whether or not ESP is on.
     */
    private CompletableFuture<?> command(List<String> args) {
        if (args.isEmpty()) {
            throw new IllegalArgumentException(USAGE);
        }
        Vec3 p = entities.localPosition().orElseThrow(() -> new IllegalStateException("no position yet"));
        switch (args.get(0).toLowerCase(Locale.ROOT)) {
            case "blocks" -> {
                if (args.size() < 2) {
                    throw new IllegalArgumentException(USAGE);
                }
                int radius = args.size() > 2 ? Integer.parseInt(args.get(2)) : blockRadius;
                List<BlockHit> hits = blocksNear(p, args.get(1), radius);
                BlockSnapshot s = blockSnapshotOf(hits);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("found", hits.size());
                out.put("rows", s.rows());
                out.put("positions", s.positions());
                return CompletableFuture.completedFuture(out);
            }
            case "entities" -> {
                int radius = args.size() > 1 ? Integer.parseInt(args.get(1)) : entityRadius;
                boolean players = args.size() > 2 && args.get(2).equalsIgnoreCase("players");
                List<Map<String, Object>> out = new ArrayList<>();
                for (EntityHit h : entitiesNear(p, radius, players)) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("id", h.id);
                    e.put("row", entityRow(h));
                    e.put("box", entityBox(h));
                    out.add(e);
                }
                return CompletableFuture.completedFuture(out);
            }
            default -> throw new IllegalArgumentException(USAGE);
        }
    }

    private void removeAll(Set<String> ids) {
        if (ids.isEmpty() || debug == null || !debug.available()) {
            return;
        }
        for (String id : ids) {
            debug.clearWorldBox(id);
        }
    }
}
