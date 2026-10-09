package com.spysyweeb.oresense.menu;

import com.spysyweeb.oresense.OreSense;
import com.spysyweeb.oresense.OreSensorItem;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;

public class OreSensorMenu extends AbstractContainerMenu {
    // Our tag includes vanilla amethyst and optional common/modded equivalents.
    private static final TagKey<Item> AMETHYST_TAG = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(OreSense.MODID, "amethyst"));
    /**
     * Menu slots: the sample, the charges, then inventory 9..35 and hotbar 0..8. On screen the
     * charges sit on the left and the sample on the right, each with a label (OreSensorScreen);
     * the indices keep their order, so the slot arithmetic below does not care where they are.
     */
    public static final int SAMPLE_SLOT = 0;
    public static final int CHARGE_SLOT = 1;
    private static final int INVENTORY_START = 2;
    private static final int HOTBAR_START = INVENTORY_START + 27;

    private final Player player;
    private final InteractionHand hand;
    private final ItemStack sensor;
    /** Menu slot holding the sensor: hotbar slot i is menu slot 29 + i. The off hand has none (-1). */
    private final int sensorSlot;
    private final Container sample = new SimpleContainer(1);
    /**
     * A view of the sensor's Charges as a stack of shards. The shards live in the sensor's NBT
     * at all times: the slot is filled from it on open and writes every change straight back,
     * so closing the screen, however that happens, has nothing left in the slot to lose.
     */
    private final Container charges = new SimpleContainer(1);

    public static final StreamCodec<RegistryFriendlyByteBuf, InteractionHand> HAND_CODEC =
            StreamCodec.of((buf, hand) -> buf.writeEnum(hand), buf -> buf.readEnum(InteractionHand.class));

    public OreSensorMenu(int id, Inventory inv, InteractionHand hand) {
        super(OreSense.ORE_SENSOR_MENU, id);
        this.player = inv.player;
        this.hand = hand;
        this.sensor = inv.player.getItemInHand(hand);
        this.sensorSlot = hand == InteractionHand.MAIN_HAND ? HOTBAR_START + inv.getSelectedSlot() : -1;
        this.sample.setItem(0, OreSensorItem.getSample(sensor));
        int stored = OreSensorItem.getCharges(sensor);
        this.charges.setItem(0, stored > 0 ? new ItemStack(Items.AMETHYST_SHARD, stored) : ItemStack.EMPTY);

        addSlot(new Slot(sample, 0, 140, 20) {
            @Override public boolean mayPlace(ItemStack stack) { return OreSensorItem.isValidSample(player.level(), stack); }
            @Override public int getMaxStackSize() { return 1; }
            @Override public void setChanged() { super.setChanged(); save(); }
        });
        addSlot(new Slot(charges, 0, 52, 20) {
            @Override public boolean mayPlace(ItemStack stack) { return isCharge(stack); }
            @Override public int getMaxStackSize() { return OreSensorItem.MAX_CHARGES; }
            @Override public void setChanged() { super.setChanged(); saveCharges(); }
        });

        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                addSlot(new Slot(inv, col + row * 9 + 9, 8 + col * 18, 51 + row * 18));
        for (int col = 0; col < 9; col++)
            addSlot(new Slot(inv, col, 8 + col * 18, 109));
    }

    private static boolean isCharge(ItemStack stack) {
        return stack.is(AMETHYST_TAG);
    }

    private void save() {
        OreSensorItem.setSample(sensor, sample.getItem(0));
    }

    /** Only amethyst can be in the slot (mayPlace), and each one is a charge. */
    private void saveCharges() {
        ItemStack shards = charges.getItem(0);
        OreSensorItem.setCharges(sensor, isCharge(shards) ? shards.getCount() : 0);
    }

    /**
     * Refuse every click aimed at the sensor's own hotbar slot (pick up, throw, quick-move, or a
     * number/offhand key pressed over it). Matched by slot index, not stack identity: the
     * client's stack object is replaced on every slot re-sync. A key swap aimed at the sample
     * or charge slot names that slot, not this one; their mayPlace refuses the sensor there.
     */
    @Override
    public void clicked(int slotId, int dragType, ClickType clickType, Player p) {
        if (sensorSlot >= 0 && slotId == sensorSlot) return;
        super.clicked(slotId, dragType, clickType, p);
        // Slot.tryRemove writes through Container.removeItem and only calls setChanged when the
        // slot empties, so taking half a stack or dropping one shard would leave the stored
        // count stale until the screen closes. Resync both slots after every click instead.
        save();
        saveCharges();
    }

    /**
     * Shift-click: the sample and the charges go back to the inventory; from the inventory,
     * amethyst goes to the charge slot and any other valid sample to the sample slot. Amethyst
     * never falls through to the sample slot when the charge slot is full: the click loop in
     * AbstractContainerMenu repeats this while items are left, so a fall-through would tune the
     * sensor to a shard whenever a stack overfilled the charges.
     */
    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();

        if (index == SAMPLE_SLOT || index == CHARGE_SLOT) {         // sensor -> inventory
            if (!moveItemStackTo(stack, INVENTORY_START, slots.size(), true)) return ItemStack.EMPTY;
        } else if (isCharge(stack)) {                                 // inventory -> charges
            if (!moveItemStackTo(stack, CHARGE_SLOT, CHARGE_SLOT + 1, false)) return ItemStack.EMPTY;
        } else {                                                      // inventory -> sample
            if (!OreSensorItem.isValidSample(player.level(), stack)) return ItemStack.EMPTY;
            if (!moveItemStackTo(stack, SAMPLE_SLOT, SAMPLE_SLOT + 1, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        save();
        saveCharges();
        return copy;
    }

    @Override
    public void removed(Player p) {
        save();
        saveCharges();
        super.removed(p);
        // hand the sample back if the player closes with something in the slot we could not store
        if (!p.level().isClientSide && !sample.getItem(0).isEmpty() && OreSensorItem.getSample(sensor).isEmpty()) {
            p.drop(sample.getItem(0), false);
        }
    }

    @Override
    public boolean stillValid(Player p) {
        return p.getItemInHand(hand) == sensor && !sensor.isEmpty();
    }
}
