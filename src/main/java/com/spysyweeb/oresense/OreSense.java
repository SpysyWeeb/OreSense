package com.spysyweeb.oresense;

import com.spysyweeb.oresense.menu.OreSensorMenu;
import com.spysyweeb.oresense.network.KnownSamplesPacket;
import com.spysyweeb.oresense.network.OreSenseNetwork;
import com.spysyweeb.oresense.scan.SampleAliases;
import com.spysyweeb.oresense.scan.SampleResolver;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.listener.Priority;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
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

    /**
     * The sample sits in the sensor's CONTAINER component, so vanilla would add its own contents
     * line ("1 x Diamond") under the tooltip's "Tuned to"; the item's default TOOLTIP_DISPLAY hides
     * it. Default components are code on both sides and never sent, so 1.0.3 peers are unaffected.
     */
    public static final RegistryObject<Item> ORE_SENSOR =
            ITEMS.register("ore_sensor", () -> new OreSensorItem(new Item.Properties().setId(ITEMS.key("ore_sensor")).stacksTo(1)
                    .component(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT.withHidden(DataComponents.CONTAINER, true))));

    public static final RegistryObject<MenuType<OreSensorMenu>> ORE_SENSOR_MENU =
            MENUS.register("ore_sensor", () -> IForgeMenuType.create(OreSensorMenu::fromNetwork));

    public OreSense(FMLJavaModLoadingContext context) {
        var bus = context.getModBusGroup();
        ITEMS.register(bus);
        MENUS.register(bus);
        BuildCreativeModeTabContentsEvent.BUS.addListener(this::addCreative);
        context.registerConfig(
                net.minecraftforge.fml.config.ModConfig.Type.COMMON, Config.SPEC);
        OreSenseNetwork.register();
        net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> {
                    com.spysyweeb.oresense.client.SensorClient.init(context);
                    com.spysyweeb.oresense.client.SensorClient.initialize(context.getModBusGroup());
                });
        AddReloadListenerEvent.BUS.addListener(this::reloadSamples);
        OnDatapackSyncEvent.BUS.addListener(this::sendSamplesOnDatapackSync);
        // LOWEST runs before removal; skip a denial already present when this listener runs.
        // A later listener or removal check can still refuse the break.
        BlockEvent.BreakEvent.BUS.addListener(Priority.LOWEST, this::spendCharge);
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
     * before the block is removed. This hook does not confirm a successful block removal.
     */
    private void spendCharge(BlockEvent.BreakEvent event) {
        // LOWEST runs before removal; skip a denial already present when this listener runs.
        // A later listener or removal check can still refuse the break.
        if (event.getResult().isDenied()) return;
        if (event.getLevel().isClientSide()) return;
        if (!(event.getPlayer() instanceof ServerPlayer player) || !(event.getLevel() instanceof Level level)) return;
        OreSensorItem.onBlockBroken(player, level, event.getPos());
    }

    /**
     * Tells clients which items the sample slot takes, so the slot can refuse the rest on the
     * client too instead of snapping back. Forge fires this for one player as they join
     * (PlayerList.placeNewPlayer, before they can open anything) and for everyone after a
     * /reload has swapped in and re-tagged the new data (PlayerList.reloadResources); in both
     * cases getPlayers() is the right list. The first join after start builds the resolver's
     * map on the server thread, which the first scan used to do.
     */
    private void sendSamplesOnDatapackSync(OnDatapackSyncEvent event) {
        KnownSamplesPacket packet = samples(event.getPlayerList().getServer());
        for (ServerPlayer player : event.getPlayers()) {
            OreSenseNetwork.send(player, packet);
        }
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
