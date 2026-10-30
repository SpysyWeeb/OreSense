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
import com.spysyweeb.oresense.client.SensorClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod(OreSense.MODID)
public class OreSense {
    public static final String MODID = "oresense";

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, MODID);

    public static final RegistryObject<Item> ORE_SENSOR =
            ITEMS.register("ore_sensor", () -> new OreSensorItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<MenuType<OreSensorMenu>> ORE_SENSOR_MENU =
            MENUS.register("ore_sensor", () -> IForgeMenuType.create(OreSensorMenu::fromNetwork));

    public OreSense() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(bus);
        MENUS.register(bus);
        bus.addListener(this::addCreative);
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(
                net.minecraftforge.fml.config.ModConfig.Type.COMMON, Config.SPEC);
        // the client config file and the mod list's Config button; SensorClient is never loaded on a server
        if (FMLEnvironment.dist == Dist.CLIENT) SensorClient.init();
        OreSenseNetwork.register();
        MinecraftForge.EVENT_BUS.addListener(this::reloadSamples);
        MinecraftForge.EVENT_BUS.addListener(this::sendSamplesOnDatapackSync);
        // LOWEST, without receiving events already cancelled by earlier listeners.
        // BreakEvent precedes removal; later listeners or removal checks can still refuse it.
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, BlockEvent.BreakEvent.class, this::spendCharge);
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
     * Mining a block of a locked vein spends the sensor's charge. Forge posts BreakEvent from
     * ServerPlayerGameMode.destroyBlock (ForgeHooks.onBlockBreakEvent), on the server thread,
     * before the block is removed. A machine's fake player is a ServerPlayer too; it never
     * ticks (FakePlayer.tick is empty), so it cannot lock a sensor itself and pays only for a
     * locked sensor someone put in its inventory.
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
     * first join builds the resolver's map on the server thread. This explicit split also
     * supports builds without OnDatapackSyncEvent.getPlayers().
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
     * The tab can be rebuilt after joining a server. Add the sensor only while its registry
     * entry is present, so an absent entry is never dereferenced.
     */
    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES && ORE_SENSOR.isPresent()) {
            event.accept(ORE_SENSOR.get());
        }
    }
}
