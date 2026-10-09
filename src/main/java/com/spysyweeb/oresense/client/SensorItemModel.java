package com.spysyweeb.oresense.client;

import com.mojang.serialization.MapCodec;
import com.spysyweeb.oresense.OreSensorItem;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.ModelRenderProperties;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BlockModelRotation;
import java.util.List;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import java.util.HashMap;
import java.util.Map;

/** Captures the original held-stack context before the engine defers drawing the sensor. */
public record SensorItemModel(ModelRenderProperties properties, SensorRenderer renderer) implements ItemModel {
    public record Snapshot(SensorClient.Reading reading, ItemStackRenderState sample) {}

    @Override
    public void update(ItemStackRenderState state, ItemStack stack, ItemModelResolver resolver,
                       ItemDisplayContext context, ClientLevel level, LivingEntity owner, int seed) {
        state.setAnimated();
        SensorClient.Reading reading = SensorClient.read(stack, context);
        ItemStackRenderState sample = new ItemStackRenderState();
        // Never recurse through another sensor saved as a sample by a datapack or command.
        if (!reading.sample().isEmpty() && !(reading.sample().getItem() instanceof OreSensorItem)) {
            resolver.updateForTopItem(sample, reading.sample(), ItemDisplayContext.GUI, level, owner, seed);
        }
        ItemStackRenderState.LayerRenderState layer = state.newLayer();
        layer.setupSpecialModel(renderer, new Snapshot(reading, sample));
        properties.applyToLayer(layer, context);
        layer.setExtents(() -> {
            java.util.Set<org.joml.Vector3f> extents = new java.util.HashSet<>();
            renderer.getExtents(extents);
            return extents.toArray(org.joml.Vector3f[]::new);
        });
    }

    public record Unbaked() implements ItemModel.Unbaked {
        public static final MapCodec<Unbaked> CODEC = MapCodec.unit(new Unbaked());
        private static final ResourceLocation DISPLAY = ResourceLocation.fromNamespaceAndPath("oresense", "item/ore_sensor");
        @Override public MapCodec<Unbaked> type() { return CODEC; }
        @Override public void resolveDependencies(ResolvableModel.Resolver resolver) {
            resolver.markDependency(DISPLAY);
            SensorClient.layers().forEach(resolver::markDependency);
        }
        @Override public ItemModel bake(ItemModel.BakingContext context) {
            Map<ResourceLocation, List<BakedQuad>> models = new HashMap<>();
            var baker = context.blockModelBaker();
            for (ResourceLocation layer : SensorClient.layers()) {
                var model = baker.getModel(layer);
                models.put(layer, model.bakeTopGeometry(model.getTopTextureSlots(), baker, BlockModelRotation.X0_Y0).getAll());
            }
            var display = baker.getModel(DISPLAY);
            return new SensorItemModel(ModelRenderProperties.fromResolvedModel(baker, display, display.getTopTextureSlots()), new SensorRenderer(models));
        }
    }
}
