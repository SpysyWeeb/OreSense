package com.spysyweeb.oresense;

import com.spysyweeb.oresense.menu.OreSensorMenu;
import com.spysyweeb.oresense.network.KnownSamplesPacket;
import com.spysyweeb.oresense.network.OreSenseNetwork;
import com.spysyweeb.oresense.scan.SampleAliases;
import com.spysyweeb.oresense.scan.SampleResolver;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTabs;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.world.item.Item;

public class OreSense implements ModInitializer {
    public static final String MODID = "oresense";

    public static final Item ORE_SENSOR = Registry.register(BuiltInRegistries.ITEM,
            new ResourceLocation(MODID, "ore_sensor"), new OreSensorItem(
                    new Item.Properties().stacksTo(1)));

    public static final MenuType<OreSensorMenu> ORE_SENSOR_MENU =
            Registry.register(BuiltInRegistries.MENU, new ResourceLocation(MODID, "ore_sensor"),
                    new ExtendedScreenHandlerType<>(OreSensorMenu::new, OreSensorMenu.HAND_CODEC));

    @Override
    public void onInitialize() {
        Config.load();
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> entries.accept(ORE_SENSOR));
        OreSenseNetwork.register();
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new SampleAliases());
        ServerLifecycleEvents.START_DATA_PACK_RELOAD.register((server, resources) -> SampleResolver.invalidate());
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            if (success) {
                SampleResolver.invalidate();
                KnownSamplesPacket packet = samples(server);
                for (ServerPlayer player : server.getPlayerList().getPlayers()) OreSenseNetwork.send(player, packet);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> SampleResolver.invalidate());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                OreSenseNetwork.send(handler.player, samples(server)));
        // AFTER runs only after a successful break, so protection mods and cancelled breaks
        // cannot consume a charge. The saved lock still contains the just-removed position.
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayer serverPlayer) OreSensorItem.onBlockBroken(serverPlayer, level, pos);
        });
    }

    private static KnownSamplesPacket samples(MinecraftServer server) {
        return new KnownSamplesPacket(SampleResolver.knownSamples(server.overworld()),
                !Config.INSTANCE.oresOnly.get());
    }
}
