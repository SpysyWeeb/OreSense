package com.spysyweeb.oresense.client;

import com.mojang.serialization.MapCodec;
import com.spysyweeb.oresense.OreSensorItem;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import java.util.HashMap;
import java.util.Map;

/** Captures the original held-stack context before the engine defers drawing the sensor. */
public record SensorItemModel(BakedModel base, SensorRenderer renderer) implements ItemModel {
    public record Snapshot(SensorClient.Reading reading, ItemStackRenderState sample) {}

    @Override
    public void update(ItemStackRenderState state, ItemStack stack, ItemModelResolver resolver,
                       ItemDisplayContext context, ClientLevel level, LivingEntity owner, int seed) {
        SensorClient.Reading reading = SensorClient.read(stack, context);
        ItemStackRenderState sample = new ItemStackRenderState();
        // Never recurse through another sensor saved as a sample by a datapack or command.
        if (!reading.sample().isEmpty() && !(reading.sample().getItem() instanceof OreSensorItem)) {
            resolver.updateForTopItem(sample, reading.sample(), ItemDisplayContext.GUI, false, level, owner, seed);
        }
        state.newLayer().setupSpecialModel(renderer, new Snapshot(reading, sample), base);
    }

    public record Unbaked() implements ItemModel.Unbaked {
        public static final MapCodec<Unbaked> CODEC = MapCodec.unit(new Unbaked());
        private static final ResourceLocation DISPLAY = ResourceLocation.fromNamespaceAndPath("oresense", "item/ore_sensor");
        @Override public MapCodec<Unbaked> type() { return CODEC; }
        @Override public void resolveDependencies(ResolvableModel.Resolver resolver) {
            resolver.resolve(DISPLAY);
            SensorClient.layers().forEach(resolver::resolve);
        }
        @Override public ItemModel bake(ItemModel.BakingContext context) {
            Map<ResourceLocation, BakedModel> models = new HashMap<>();
            for (ResourceLocation layer : SensorClient.layers()) models.put(layer, context.bake(layer));
            return new SensorItemModel(context.bake(DISPLAY), new SensorRenderer(models));
        }
    }
}
