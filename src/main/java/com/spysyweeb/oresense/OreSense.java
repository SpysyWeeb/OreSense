package com.spysyweeb.oresense;

import com.spysyweeb.oresense.menu.OreSensorMenu;
import com.spysyweeb.oresense.network.KnownSamplesPacket;
import com.spysyweeb.oresense.network.OreSenseNetwork;
import com.spysyweeb.oresense.scan.SampleAliases;
import com.spysyweeb.oresense.scan.SampleResolver;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.registries.DeferredHolder;

@Mod(OreSense.MODID)
public class OreSense {
    public static final String MODID = "oresense";

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(BuiltInRegistries.ITEM, MODID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(BuiltInRegistries.MENU, MODID);

    public static final DeferredHolder<Item, Item> ORE_SENSOR =
            ITEMS.register("ore_sensor", () -> new OreSensorItem(new Item.Properties().setId(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ITEM, net.minecraft.resources.Identifier.fromNamespaceAndPath(MODID, "ore_sensor"))).stacksTo(1)));

    public static final DeferredHolder<MenuType<?>, MenuType<OreSensorMenu>> ORE_SENSOR_MENU =
            MENUS.register("ore_sensor", () -> IMenuTypeExtension.create(OreSensorMenu::fromNetwork));

    public OreSense(IEventBus bus, ModContainer container) {
        ITEMS.register(bus);
        MENUS.register(bus);
        bus.addListener(this::addCreative);
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, Config.SPEC);
        bus.addListener(OreSenseNetwork::register);
        NeoForge.EVENT_BUS.addListener(this::reloadSamples);
        NeoForge.EVENT_BUS.addListener(this::syncSamples);
        // Spend only after other mods have had a chance to cancel the break.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, BreakBlockEvent.class, this::spendCharge);
    }

    /**
     * Samples resolve through loot tables, recipes and the sample_aliases data folder, and
     * /reload or a datapack change replaces all three. This fires as the new data starts
     * loading. It lets go of the resolver's old map early (the resolver keys its map on the
     * data it was built from, so a sample-slot click that runs while the reload is in flight
     * cannot leave a map of the old data behind) and adds the alias loader to the reload, which
     * swaps in the new aliases once they are read.
     */
    private void reloadSamples(AddServerReloadListenersEvent event) {
        SampleResolver.invalidate();
        event.addListener(net.minecraft.resources.Identifier.fromNamespaceAndPath(MODID, "sample_aliases"), new SampleAliases());
    }

    /**
     * Mining a block of a locked vein spends the sensor's charge. NeoForge posts BreakBlockEvent from
     * ServerPlayerGameMode.destroyBlock (CommonHooks.onBlockBreakEvent), on the server thread,
     * before the block is removed. A machine's fake player is a ServerPlayer too; it never
     * ticks (FakePlayer.tick is empty), so it cannot lock a sensor itself and pays only for a
     * locked sensor someone put in its inventory.
     */
    private void spendCharge(BreakBlockEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getPlayer() instanceof ServerPlayer player) || !(event.getLevel() instanceof Level level)) return;
        OreSensorItem.onBlockBroken(player, level, event.getPos());
    }

    /**
     * Tells clients which items the sample slot takes, so the slot can refuse the rest on the
     * client too instead of snapping back. NeoForge fires this for one player as they join
     * (PlayerList.placeNewPlayer, before they can open anything) and for everyone after a
     * /reload has swapped in and re-tagged the new data (PlayerList.reloadResources); in both
     * cases the joining player or full player list is selected. The first join after start builds the resolver's
     * map on the server thread, which the first scan used to do.
     */
    private void syncSamples(OnDatapackSyncEvent event) {
        KnownSamplesPacket packet = new KnownSamplesPacket(
                SampleResolver.knownSamples(event.getPlayerList().getServer().overworld()),
                !Config.INSTANCE.oresOnly.get());
        if (event.getPlayer() != null) {
            OreSenseNetwork.send(event.getPlayer(), packet);
        } else {
            for (ServerPlayer player : event.getPlayerList().getPlayers()) OreSenseNetwork.send(player, packet);
        }
    }

    /**
     * The tab is rebuilt on every server join. On a server that does not ship this mod, Forge's
     * registry sync leaves ORE_SENSOR absent for the session, so an unconditional get() throws
     * and Forge reports a mod loading error (seen in the Mine instance log on 2026-09-23).
     */
    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES && ORE_SENSOR.isBound()) {
            event.accept(ORE_SENSOR.get());
        }
    }
}
