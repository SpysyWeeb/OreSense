package com.spysyweeb.oresense.client;

import com.spysyweeb.oresense.OreSense;
import com.spysyweeb.oresense.menu.OreSensorMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

public class OreSensorScreen extends AbstractContainerScreen<OreSensorMenu> {
    // the hopper layout with only the charge and sample slots (tools/gen_textures.py), on the
    // 256x256 canvas that the short blit form below assumes
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(OreSense.MODID, "textures/gui/ore_sensor.png");
    // each slot's name, left of it in vanilla's label style (AbstractContainerScreen.renderLabels
    // draws the title and "Inventory" in 0x404040 without a shadow)
    private static final Component CHARGES_LABEL = Component.translatable("oresense.gui.charges");
    private static final Component SAMPLE_LABEL = Component.translatable("oresense.gui.sample");
    private static final int LABEL_COLOUR = 0x404040;
    private static final int LABEL_GAP = 4;       // px from a label's right end to its slot's frame

    public OreSensorScreen(OreSensorMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 176;
        this.imageHeight = 133;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;
        g.blit(net.minecraft.client.renderer.RenderType::guiTextured, TEXTURE, x, y, 0, 0, imageWidth, imageHeight, 256, 256);
    }

    /** Runs with the pose already moved to the panel's top-left, like the title. */
    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY);   // the title and "Inventory"
        drawSlotLabel(g, CHARGES_LABEL, menu.getSlot(OreSensorMenu.CHARGE_SLOT));
        drawSlotLabel(g, SAMPLE_LABEL, menu.getSlot(OreSensorMenu.SAMPLE_SLOT));
    }

    /**
     * Right-aligned to end LABEL_GAP px left of the slot's frame (which starts 1 px left of the
     * item), on the item's middle row: the charges label ends at x 47, the sample's at 135.
     */
    private void drawSlotLabel(GuiGraphics g, Component label, Slot slot) {
        int right = slot.x - 1 - LABEL_GAP;
        g.drawString(font, label, right - font.width(label), slot.y + 4, LABEL_COLOUR, false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }
}
