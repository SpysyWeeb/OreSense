package com.spysyweeb.oresense.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.spysyweeb.oresense.ChargeMode;
import com.spysyweeb.oresense.Config;
import com.spysyweeb.oresense.LiveConfig;
import com.spysyweeb.oresense.OreSense;
import com.spysyweeb.oresense.VeinPick;
import com.spysyweeb.oresense.client.skin.Skin;
import com.spysyweeb.oresense.client.skin.Skins;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The OreSense settings (brief section 3): skin previews on top, then a scrolling list of the
 * display settings (this computer, config/oresense-client.json) and the gameplay settings (this
 * world, config/oresense.json), each a name and description on the left and its control on
 * the right, and Done / Cancel / Reset at the bottom.
 * <p>
 * Opened from Mod Menu's Config button on the title screen, and in a world from the pause menu's
 * Mods screen; closing returns to that mod list. In a world this screen does not pause the game:
 * the world, the HUD and a held sensor keep running behind it. It reads and changes settings only
 * through Config.LIVE and ClientConfig.LIVE: every control change is set in memory at once, so the
 * next frame, scan or lock uses it; Done writes the files whose values changed, Cancel and Esc
 * (and any other way the screen goes away) put the values back as they were when it opened and
 * write nothing. Gameplay rows are editable on the title screen and in single player (also when
 * open to LAN: the server is this process); on a remote server they are disabled, because only
 * the server's own file counts there. Direction in text only changes the text reading, so its
 * row is inactive while Text reading's pending value is Off (SettingRow.enabledBy).
 * <p>
 * Below 300 GUI units of height the skin tiles shrink to 48 and the rows to 40, so two full rows
 * still show at 320 x 240. The first section's title is the list's own 24-unit header; the second
 * is a row of the list (1.18 lists have one row height).
 */
public class OreSenseSettingsScreen extends Screen {
    // layout in GUI units, for a 320 x 240 minimum, centred horizontally
    private static final int TITLE_Y = 8;
    private static final int TILE_Y = 22;
    private static final int TILE_SIZE = 64;
    private static final int TILE_GAP = 16;
    private static final int TILE_NAME_Y = 90;
    private static final int LIST_TOP = 104;
    private static final int ROW_HEIGHT = 44;
    // small screens (under SMALL_HEIGHT): 48 px tiles (3x), the names right under them, the list
    // NAME_TO_LIST below the names, 40 px rows
    private static final int SMALL_HEIGHT = 300;
    private static final int SMALL_TILE_SIZE = 48;
    private static final int SMALL_ROW_HEIGHT = 40;
    private static final int NAME_GAP = 2;
    private static final int NAME_TO_LIST = 6;
    private static final int LIST_BOTTOM_GAP = 36;      // the list ends at height - 36
    private static final int HEADER_HEIGHT = 24;        // the list's own header: the first section's title
    private static final int MAX_ROW_WIDTH = 360;
    // the scroll bar sits 6 px right of the rows, 6 px wide: 14 px each side keeps it on screen
    private static final int ROW_SIDE_GAP = 28;
    private static final int SCROLLBAR_GAP = 6;
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int CONTROL_GAP = 8;           // between the description and the control
    private static final int DESCRIPTION_LINES = 3;
    private static final int BUTTON_WIDTH = 100;
    private static final int BUTTON_GAP = 8;
    private static final int BUTTON_Y_FROM_BOTTOM = 28;
    /** Each preview tile shows DORMANT, SEARCHING, LOCKED in turn, this long each. */
    private static final long PREVIEW_STEP_MS = 2500L;
    /** The sample a preview tile shows in the window while searching and locked. */
    private static final ResourceLocation PREVIEW_SAMPLE = new ResourceLocation("minecraft", "textures/item/diamond.png");
    private static final int GOLD = 0xFFFAD64A;          // gold_ingot's light gold, the dial's own case
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREY = 0xFFA0A0A0;
    private static final int TILE_BACK = 0x80000000;
    /** In a world: the panel behind the list and the buttons, dark enough that the HUD does not read through. */
    private static final int WORLD_PANEL = 0xE0000000;

    private final @Nullable Screen parent;
    private final SettingRow<String> skinRow;
    private final List<SettingRow<?>> displayRows;
    private final List<SettingRow<?>> gameplayRows;
    private final List<SkinTile> tiles = new ArrayList<>();
    /** 1.18 has no widget Tooltip; draw the hovered/focused control's text after the list's scissor. */
    private @Nullable Component tooltip;
    /** oresOnly as the screen opened: when Done changes it, the integrated server resends the sample list. */
    private final @Nullable Boolean oresOnlyAtOpen;
    private boolean remote;
    private boolean finished;
    // this size's layout (init)
    private int tileSize = TILE_SIZE;
    private int nameY = TILE_NAME_Y;
    private int listTop = LIST_TOP;
    private int rowHeight = ROW_HEIGHT;

    /** From the mod list (OreSenseModMenu): closing shows {@code parent} again. */
    public OreSenseSettingsScreen(@Nullable Screen parent) {
        super(new TranslatableComponent("oresense.settings.title"));
        this.parent = parent;

        ClientConfig display = ClientConfig.INSTANCE;
        LiveConfig displayFile = ClientConfig.LIVE;
        // an unknown id draws compass (Skins.byId): the row shows that skin and a click steps on from it
        skinRow = SettingRow.choice(displayFile, display.skin, Skins.ids(), id -> Skins.byId(id).id(),
                id -> Skins.byId(id).name(), false);
        displayRows = displayFile.isLoaded()
                ? List.of(skinRow, SettingRow.toggle(displayFile, display.pulsingLights, false))
                : List.of();

        Config game = Config.INSTANCE;
        LiveConfig gameFile = Config.LIVE;
        if (gameFile.isLoaded()) {
            SettingRow<Boolean> showActionBarRow = SettingRow.toggle(gameFile, game.showActionBar, true);
            gameplayRows = List.of(
                    SettingRow.slider(gameFile, game.horizontalRange, true),
                    SettingRow.slider(gameFile, game.verticalRange, true),
                    SettingRow.slider(gameFile, game.scanIntervalTicks, true),
                    showActionBarRow,
                    // the direction goes only into the text reading: inactive while Text reading is Off
                    SettingRow.toggle(gameFile, game.showDirection, true)
                            .enabledBy(() -> showActionBarRow.get(), showActionBarRow.name()),
                    SettingRow.toggle(gameFile, game.oresOnly, true),
                    // each mode says how it charges: oresense.settings.charging.chargeMode.desc.<mode>
                    choice(gameFile, game.chargeMode, ChargeMode.values()).describedBy(mode ->
                            new TranslatableComponent("oresense.settings.charging.chargeMode.desc." + lowerName(mode))),
                    SettingRow.slider(gameFile, game.shardsPerVein, true),
                    SettingRow.slider(gameFile, game.blocksPerShard, true),
                    SettingRow.slider(gameFile, game.veinCap, true),
                    SettingRow.slider(gameFile, game.lockGraceTicks, true),
                    choice(gameFile, game.targetPick, VeinPick.values()));
        } else {
            gameplayRows = List.of();
        }
        displayRows.forEach(SettingRow::capture);
        gameplayRows.forEach(SettingRow::capture);
        oresOnlyAtOpen = gameFile.isLoaded() ? gameFile.get(game.oresOnly) : null;
    }

    /** A gameplay enum: a button stepping through its values, named oresense.settings.enum.&lt;value&gt;. */
    private static <E extends Enum<E>> SettingRow<E> choice(LiveConfig file, Config.Value<E> value, E[] values) {
        return SettingRow.choice(file, value, Arrays.asList(values),
                e -> new TranslatableComponent("oresense.settings.enum." + lowerName(e)), true);
    }

    /** An enum value as its lang keys spell it: per_vein, closest. */
    private static String lowerName(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    /** The skin the client file chooses (an unknown id draws compass), read through LIVE. */
    private String selectedSkin() {
        return ClientConfig.LIVE.isLoaded() ? Skins.byId(skinRow.get()).id() : Skins.DEFAULT_ID;
    }

    // ---- screen ----

    @Override
    protected void init() {
        // a remote server's gameplay file counts; an integrated server uses this process's file
        remote = minecraft.getCurrentServer() != null && !minecraft.hasSingleplayerServer();
        boolean small = height < SMALL_HEIGHT;
        tileSize = small ? SMALL_TILE_SIZE : TILE_SIZE;
        nameY = small ? TILE_Y + tileSize + NAME_GAP : TILE_NAME_Y;
        listTop = small ? nameY + font.lineHeight + NAME_TO_LIST : LIST_TOP;
        rowHeight = small ? SMALL_ROW_HEIGHT : ROW_HEIGHT;

        tiles.clear();
        List<Skin> skins = Skins.all();
        int rowWidth = skins.size() * tileSize + (skins.size() - 1) * TILE_GAP;
        int x = (width - rowWidth) / 2;
        for (Skin skin : skins) {
            tiles.add(addRenderableWidget(new SkinTile(skin, x, TILE_Y, tileSize)));
            x += tileSize + TILE_GAP;
        }

        SettingsList list = new SettingsList(new TranslatableComponent("oresense.settings.section.display"));
        for (SettingRow<?> row : displayRows) list.addRow(row, false);
        list.addHeader(new TranslatableComponent(remote ? "oresense.settings.section.gameplay.remote"
                : "oresense.settings.section.gameplay"));
        for (SettingRow<?> row : gameplayRows) list.addRow(row, remote);
        addRenderableWidget(list);

        int buttonsWidth = 3 * BUTTON_WIDTH + 2 * BUTTON_GAP;
        int bx = (width - buttonsWidth) / 2;
        int by = height - BUTTON_Y_FROM_BOTTOM;
        addRenderableWidget(new Button(bx, by, BUTTON_WIDTH, 20,
                new TranslatableComponent("oresense.settings.done"), b -> done()));
        addRenderableWidget(new Button(bx + BUTTON_WIDTH + BUTTON_GAP, by, BUTTON_WIDTH, 20,
                new TranslatableComponent("oresense.settings.cancel"), b -> onClose()));
        addRenderableWidget(new Button(bx + 2 * (BUTTON_WIDTH + BUTTON_GAP), by, BUTTON_WIDTH, 20,
                new TranslatableComponent("oresense.settings.reset"), b -> reset()));
    }

    /**
     * On the title screen the vanilla dirt background (and the list's own darker panel). In a world
     * no full-screen dimming, so the world and the sensor in hand stay visible around the title and
     * the tiles; one dark panel behind the list and the buttons keeps the HUD and the action-bar
     * text from reading through them (a Screen cannot hide the HUD).
     */
    private void renderBackdrop(PoseStack graphics) {
        if (minecraft.level == null) {
            renderBackground(graphics);
            return;
        }
        GuiComponent.fill(graphics, 0, listTop - 2, width, height, WORLD_PANEL);
    }

    @Override
    public void render(PoseStack graphics, int mouseX, int mouseY, float partialTick) {
        tooltip = null;
        renderBackdrop(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        GuiComponent.drawCenteredString(graphics, font, title, width / 2, TITLE_Y, WHITE);
        String selected = selectedSkin();
        for (SkinTile tile : tiles) {
            boolean chosen = tile.skin.id().equals(selected);
            GuiComponent.drawCenteredString(graphics, font, tile.skin.name(), tile.x + tileSize / 2, nameY, chosen ? WHITE : GREY);
        }
        if (tooltip != null) renderTooltip(graphics, tooltip, mouseX, mouseY);
    }

    /** Not a pause screen: in single player the world, and a held sensor's scans, keep going. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Esc and Cancel: put everything back as it was when the screen opened, write nothing. */
    @Override
    public void onClose() {
        restore();
        close();
    }

    /** Closed some other way (a disconnect, say): that is a cancel too. */
    @Override
    public void removed() {
        restore();
        super.removed();
    }

    /**
     * Writes each changed file once; an unchanged file is not rewritten. On a remote server the
     * gameplay rows are read-only, so this computer's gameplay file stays untouched.
     */
    private void done() {
        finished = true;
        if (changed(displayRows)) ClientConfig.LIVE.save();
        if (!remote) {
            if (changed(gameplayRows)) Config.LIVE.save();
            resendSamplesIfOresOnlyChanged();
        }
        close();
    }

    private static boolean changed(List<SettingRow<?>> rows) {
        for (SettingRow<?> row : rows) {
            if (row.changed()) return true;
        }
        return false;
    }

    /**
     * The client checks the sample slot against the list the server sent it (KnownSamples), which
     * carries oresOnly. In single player the integrated server reads the changed value at once, so
     * it sends the list again on its own thread.
     */
    private void resendSamplesIfOresOnlyChanged() {
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null || !Config.LIVE.isLoaded()) return;
        if (Objects.equals(Config.LIVE.get(Config.INSTANCE.oresOnly), oresOnlyAtOpen)) return;
        server.execute(() -> OreSense.syncSamples(server));
    }

    private void restore() {
        if (finished) return;
        finished = true;
        displayRows.forEach(SettingRow::restore);
        gameplayRows.forEach(SettingRow::restore);
    }

    /** Defaults into every row this screen may edit; still pending Done like any change. */
    private void reset() {
        displayRows.forEach(SettingRow::reset);
        if (!remote) gameplayRows.forEach(SettingRow::reset);
    }

    private void close() {
        minecraft.setScreen(parent);
    }

    // ---- skin previews ----

    /**
     * One built-in skin, drawn from its own layers (Skin.compose, each layer's texture blitted over
     * the tile with its tint) at 4x in a 64 tile, 3x in a 48 one, for three readings (DORMANT with
     * shards, SEARCHING, LOCKED at the skin's preview frame with the up lamp), one after another
     * every 2.5 s, with a diamond in the window while searching and locked. No ItemStack is made and
     * the item renderer is not used, so the title-screen previews need no world or baked item model.
     * Every version draws the tiles from the same skin layers.
     * The selected skin has a gold frame; clicking (or Enter) selects; hovering names it.
     */
    private final class SkinTile extends AbstractButton {
        private final Skin skin;
        private final SensorClient.Reading[] readings;

        SkinTile(Skin skin, int x, int y, int size) {
            super(x, y, size, size, skin.name());
            this.skin = skin;
            // the readings carry the static EMPTY stack (no item, no tag); the tile draws the
            // sample itself (PREVIEW_SAMPLE)
            readings = new SensorClient.Reading[]{
                    new SensorClient.Reading(SensorClient.State.DORMANT, 0f, SensorClient.Vertical.LEVEL,
                            ItemStack.EMPTY, false, false, 0f, 40),
                    new SensorClient.Reading(SensorClient.State.SEARCHING, 0f, SensorClient.Vertical.LEVEL,
                            ItemStack.EMPTY, false, false, 0f, 40),
                    // the skin's preview-sheet needle frame and the up lamp, as on the approved sheet
                    new SensorClient.Reading(SensorClient.State.LOCKED,
                            skin.previewLockedFrame() / (float) SensorClient.NEEDLE_FRAMES,
                            SensorClient.Vertical.ABOVE, ItemStack.EMPTY, false, false, 0.6f, 40),
            };
        }

        @Override
        public void onPress() {
            if (!ClientConfig.LIVE.isLoaded()) return;
            skinRow.set(skin.id());
            skinRow.refresh();
        }

        @Override
        public void renderButton(PoseStack graphics, int mouseX, int mouseY, float partialTick) {
            int x = this.x, y = this.y, size = getWidth();
            GuiComponent.fill(graphics, x, y, x + size, y + size, TILE_BACK);
            if (skin.id().equals(selectedSkin())) {
                hLine(graphics, x - 1, x + size, y - 1, GOLD);
                hLine(graphics, x - 1, x + size, y + size, GOLD);
                vLine(graphics, x - 1, y - 1, y + size, GOLD);
                vLine(graphics, x + size, y - 1, y + size, GOLD);
            } else if (isHoveredOrFocused()) {
                hLine(graphics, x - 1, x + size, y - 1, GREY);
                hLine(graphics, x - 1, x + size, y + size, GREY);
                vLine(graphics, x - 1, y - 1, y + size, GREY);
                vLine(graphics, x + size, y - 1, y + size, GREY);
            }
            long now = Util.getMillis();
            SensorClient.Reading reading = readings[(int) ((now / PREVIEW_STEP_MS) % readings.length)];
            boolean pulsingLights = !ClientConfig.LIVE.isLoaded() || ClientConfig.LIVE.get(ClientConfig.INSTANCE.pulsingLights);
            Skin.Frame frame = new Skin.Frame(now, SensorRenderer.lockedPhase(reading, now), pulsingLights,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            // every layer stretched over the tile with its tint and alpha (glowing means nothing in a GUI)
            skin.compose(reading, frame, (layer, r, g, b, alpha, fullBright) ->
                    blitWhole(graphics, skin.texture(layer), x, y, size, r, g, b, alpha));
            if (reading.state() == SensorClient.State.SEARCHING || reading.state() == SensorClient.State.LOCKED) {
                // the sample in the window, where SensorRenderer puts it: 16 px in a 64 tile, 1:1 with the texture
                int scale = size / SensorRenderer.DIAL_PIXELS;
                blitWhole(graphics, PREVIEW_SAMPLE, x + SensorRenderer.WINDOW_X * scale,
                        y + SensorRenderer.WINDOW_Y * scale, SensorRenderer.WINDOW_SIZE * scale, 1f, 1f, 1f, 1f);
            }
            if (isHoveredOrFocused()) tooltip = skin.name();
        }

        @Override
        public void updateNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /**
     * One whole texture file, whatever its pixel size, stretched over a square at (x, y) and
     * multiplied by (r, g, b, a): u and v from 0 across a 1 x 1 region of a texture counted as 1
     * x 1, so the UVs run 0 to 1. Nearest-neighbour, like every GUI blit of a plain texture.
     * 1.18's texture-file GuiComponent.blit takes no colour: the shader colour is set around it,
     * and blending is switched on for it (a plain blit does not blend, and the sonar rings are
     * translucent).
     */
    private static void blitWhole(PoseStack graphics, ResourceLocation texture, int x, int y, int size,
                                  float r, float g, float b, float a) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(r, g, b, a);
        RenderSystem.setShaderTexture(0, texture);
        GuiComponent.blit(graphics, x, y, size, size, 0f, 0f, 1, 1, 1, 1);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }

    // ---- the list ----

    /**
     * The scrolling list, from the list top to 36 above the bottom, rows 44 high (40 on a small
     * screen) and up to 360 wide. The first section's title is the list's own header, 24 high,
     * scrolling with the rows; the second is a row. In a world it draws no background of its own:
     * the screen's panel is behind it. On the title screen it keeps the usual darker panel.
     */
    private final class SettingsList extends ContainerObjectSelectionList<SettingsList.Entry> {
        private final Component firstHeader;

        SettingsList(Component firstHeader) {
            super(OreSenseSettingsScreen.this.minecraft, OreSenseSettingsScreen.this.width,
                    OreSenseSettingsScreen.this.height, listTop,
                    Math.max(listTop, OreSenseSettingsScreen.this.height - LIST_BOTTOM_GAP), rowHeight);
            this.firstHeader = firstHeader.copy().withStyle(ChatFormatting.BOLD);
            setRenderHeader(true, HEADER_HEIGHT);
            setRenderBackground(minecraft.level == null);
            setRenderTopAndBottom(false);
        }

        void addHeader(Component text) {
            addEntry(new Header(text));
        }

        /** {@code locked}: a gameplay row on a remote server, disabled with the remote tooltip for good. */
        void addRow(SettingRow<?> row, boolean locked) {
            AbstractWidget control = row.createControl();
            if (locked) {
                control.active = false;
            }
            addEntry(new Row(row, control, locked));
        }

        @Override
        public int getRowWidth() {
            return Math.min(MAX_ROW_WIDTH, width - ROW_SIDE_GAP);
        }

        @Override
        protected int getScrollbarPosition() {
            return Math.min(getRowRight() + SCROLLBAR_GAP, width - SCROLLBAR_WIDTH);
        }

        /** The first section's header, as the list's own (shorter) header, scrolling with the rows. */
        @Override
        protected void renderHeader(PoseStack graphics, int x, int y, Tesselator tesselator) {
            GuiComponent.drawCenteredString(graphics, font, firstHeader, x + getRowWidth() / 2,
                    y + HEADER_HEIGHT - font.lineHeight - 2, WHITE);
        }

        /**
         * Rows scrolled partly out of the list are clipped, but their controls would still see the
         * mouse over the tiles or the buttons and show a hover tooltip there: they get no mouse
         * while it is outside the list.
         */
        @Override
        protected void renderList(PoseStack graphics, int left, int top, int mouseX, int mouseY, float partialTick) {
            boolean inside = isMouseOver(mouseX, mouseY);
            super.renderList(graphics, left, top, inside ? mouseX : -1, inside ? mouseY : -1, partialTick);
        }

        /**
         * Brings every row's enabled-by state (SettingRow.enabledBy) up to date, drawn or not. A row
         * outside the view is not drawn, and a value changed by an earlier key or click of the same
         * frame has not been drawn yet; focus moves, keys and clicks read the controls' active flag,
         * so the list runs this each frame before it draws and again before it handles any of them.
         * Then Tab skips a row whose needed setting is off; Enter and clicks cannot activate it.
         */
        void followEnabledBy() {
            for (Entry entry : children()) {
                if (entry instanceof Row row) row.followEnabledBy();
            }
        }

        @Override
        public void render(PoseStack graphics, int mouseX, int mouseY, float partialTick) {
            followEnabledBy();
            // 1.18 lists do not scissor their rows or header; keep them inside the list's bounds.
            double scale = minecraft.getWindow().getGuiScale();
            RenderSystem.enableScissor((int) (x0 * scale),
                    (int) (minecraft.getWindow().getHeight() - y1 * scale),
                    Math.max(0, (int) ((x1 - x0) * scale)), Math.max(0, (int) ((y1 - y0) * scale)));
            super.render(graphics, mouseX, mouseY, partialTick);
            RenderSystem.disableScissor();
        }

        @Override
        public boolean changeFocus(boolean forward) {
            followEnabledBy();
            return super.changeFocus(forward);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            followEnabledBy();
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            followEnabledBy();
            return super.mouseClicked(mouseX, mouseY, button);
        }

        abstract static class Entry extends ContainerObjectSelectionList.Entry<Entry> {
        }

        private final class Header extends Entry {
            private final Component text;

            Header(Component text) {
                this.text = text.copy().withStyle(ChatFormatting.BOLD);
            }

            @Override
            public void render(PoseStack graphics, int index, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean hovered, float partialTick) {
                GuiComponent.drawCenteredString(graphics, font, text, left + width / 2, top + height - font.lineHeight, WHITE);
            }

            @Override
            public boolean changeFocus(boolean forward) {
                return false;
            }

            @Override
            public List<? extends GuiEventListener> children() {
                return List.of();
            }

            @Override
            public List<? extends NarratableEntry> narratables() {
                return List.of(new NarratableEntry() {
                    @Override
                    public NarrationPriority narrationPriority() {
                        return NarrationPriority.HOVERED;
                    }

                    @Override
                    public void updateNarration(NarrationElementOutput output) {
                        output.add(NarratedElementType.TITLE, text);
                    }
                });
            }
        }

        private final class Row extends Entry {
            private final SettingRow<?> row;
            private final AbstractWidget control;
            private final boolean locked;
            private @Nullable Component tooltip;
            /** Inactive because the setting this row needs is off (SettingRow.enabledBy). */
            private boolean greyed;

            Row(SettingRow<?> row, AbstractWidget control, boolean locked) {
                this.row = row;
                this.control = control;
                this.locked = locked;
                tooltip = locked ? new TranslatableComponent("oresense.settings.remote.tooltip") : null;
                followEnabledBy();
            }

            /**
             * The row's enabled-by check (SettingRow.enabledBy), run by the list (SettingsList.followEnabledBy),
             * so it follows the needed setting's pending value from one frame to the next: while that is
             * off the control is inactive with the "Needs ..." tooltip. The row keeps that state itself
             * (greyed) and changes the control only when it flips, so a control is never switched on
             * here for a reason other than its own predicate. A locked (remote) row keeps its
             * state and tooltip.
             */
            void followEnabledBy() {
                if (locked) return;
                boolean off = !row.enabled();
                if (off == greyed) return;
                greyed = off;
                control.active = !off;
                tooltip = off ? row.needsText() : null;
            }

            @Override
            public void render(PoseStack graphics, int index, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean hovered, float partialTick) {
                // the control at the right, the name and description take the rest
                int textWidth = width - control.getWidth() - CONTROL_GAP;
                // 44-high rows leave a pixel above the name and a gap under it; 40-high ones fit
                // name and three lines of description exactly
                boolean roomy = rowHeight >= ROW_HEIGHT;
                font.drawShadow(graphics, row.name(), left, top + (roomy ? 1 : 0), greyed ? GREY : WHITE);
                List<FormattedCharSequence> lines = font.split(row.description(), textWidth);
                int descriptionTop = top + (roomy ? 12 : 10);
                for (int i = 0; i < Math.min(DESCRIPTION_LINES, lines.size()); i++) {
                    font.draw(graphics, lines.get(i), left, descriptionTop + i * font.lineHeight, GREY);
                }
                control.x = left + width - control.getWidth();
                control.y = top + (height - control.getHeight()) / 2;
                control.render(graphics, mouseX, mouseY, partialTick);
                if (tooltip != null && control.isHoveredOrFocused()) OreSenseSettingsScreen.this.tooltip = tooltip;
            }

            @Override
            public List<? extends GuiEventListener> children() {
                return List.of(control);
            }

            @Override
            public List<? extends NarratableEntry> narratables() {
                return List.of(control);
            }
        }
    }
}
