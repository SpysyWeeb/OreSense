package com.spysyweeb.oresense;

import com.spysyweeb.oresense.menu.OreSensorMenu;
import com.spysyweeb.oresense.network.KnownSamplesPacket;
import com.spysyweeb.oresense.network.OreSenseNetwork;
import com.spysyweeb.oresense.scan.SampleAliases;
import com.spysyweeb.oresense.scan.SampleResolver;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.registries.DeferredHolder;
import com.spysyweeb.oresense.client.SensorClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(OreSense.MODID)
public class OreSense {
    public static final String MODID = "oresense";

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(BuiltInRegistries.ITEM, MODID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(BuiltInRegistries.MENU, MODID);

    public static final DeferredHolder<Item, Item> ORE_SENSOR =
            ITEMS.register("ore_sensor", () -> new OreSensorItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<MenuType<?>, MenuType<OreSensorMenu>> ORE_SENSOR_MENU =
            MENUS.register("ore_sensor", () -> IMenuTypeExtension.create(OreSensorMenu::fromNetwork));

    public OreSense(IEventBus bus) {
        ITEMS.register(bus);
        MENUS.register(bus);
        bus.addListener(this::addCreative);
        ModLoadingContext.get().registerConfig(
                net.neoforged.fml.config.ModConfig.Type.COMMON, Config.SPEC);
        // the client config file and the mod list's Config button; SensorClient is never loaded on a server
        if (FMLEnvironment.dist == Dist.CLIENT) SensorClient.init();
        bus.addListener(OreSenseNetwork::register);
        NeoForge.EVENT_BUS.addListener(this::reloadSamples);
        NeoForge.EVENT_BUS.addListener(this::sendSamplesOnDatapackSync);
        // LOWEST, without receiving events already cancelled by earlier listeners.
        // BreakEvent precedes removal; later listeners or removal checks can still refuse it.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, BlockEvent.BreakEvent.class, this::spendCharge);
    }

    /**
     * Samples resolve through loot tables, recipes and the sample_aliases data folder, and
     * /reload or a datapack change replaces all three. This fires as the new data starts
     * loading. It lets go of the resolver's old map early (the resolver keys its map on the
     * data it was built from, so a sample-slot click that runs while the reload is in flight
     * cannot leave a map of the old data behind) and adds the alias loader to the reload, which
     * swaps in the new aliases once they are read.
     */
    private void reloadSamples(AddReloadListenerEvent event) {
        SampleResolver.invalidate();
        event.addListener(new SampleAliases());
    }

    /**
     * Mining a block of a locked vein spends the sensor's charge. NeoForge posts
     * BlockEvent.BreakEvent from ServerPlayerGameMode.destroyBlock (CommonHooks.onBlockBreakEvent),
     * on the server thread, before the block is removed. NeoForge's FakePlayer is also a
     * ServerPlayer and its tick override is empty; this listener charges locked sensors it carries.
     */
    private void spendCharge(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getPlayer() instanceof ServerPlayer player) || !(event.getLevel() instanceof Level level)) return;
        OreSensorItem.onBlockBroken(player, level, event.getPos());
    }

    /**
     * Tells clients which items the sample slot accepts, so invalid samples are refused locally
     * instead of snapping back. OnDatapackSyncEvent supplies getPlayer() for a joining player;
     * on a data pack reload it supplies null, so the packet is sent to the full player list. The
     * first join builds the resolver's map on the server thread.
     */
    private void sendSamplesOnDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) {
            OreSenseNetwork.send(event.getPlayer(), samples(event.getPlayerList().getServer()));
            return;
        }
        syncSamples(event.getPlayerList().getServer());
    }

    /**
     * Sends every player the server's sample list again: after a /reload (above), and when the
     * settings screen changes oresOnly in a single-player world (on the server thread).
     */
    public static void syncSamples(MinecraftServer server) {
        KnownSamplesPacket packet = samples(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) OreSenseNetwork.send(player, packet);
    }

    private static KnownSamplesPacket samples(MinecraftServer server) {
        return new KnownSamplesPacket(SampleResolver.knownSamples(server.overworld()),
                !Config.INSTANCE.oresOnly.get());
    }

    /**
     * Adds the sensor to the tools tab only while its DeferredHolder is bound; get() throws
     * for an unbound holder.
     */
    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES && ORE_SENSOR.isBound()) {
            event.accept(ORE_SENSOR.get());
        }
    }
}
