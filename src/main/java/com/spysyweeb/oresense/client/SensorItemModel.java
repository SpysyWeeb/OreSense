package com.spysyweeb.oresense.client;

import com.mojang.serialization.MapCodec;
import com.spysyweeb.oresense.OreSensorItem;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.ModelRenderProperties;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Captures the held-stack reading and GUI sample before rendering is queued. */
public record SensorItemModel(ModelRenderProperties properties, SensorRenderer renderer) implements ItemModel {
    public record Snapshot(SensorClient.Reading reading, ItemStackRenderState sample) {}
    private static final Vector3f[] EXTENTS = {
        new Vector3f(0, 0, 0.46875f), new Vector3f(0, 1, 0.46875f),
        new Vector3f(1, 0, 0.46875f), new Vector3f(1, 1, 0.46875f),
        new Vector3f(0, 0, 0.56f), new Vector3f(0, 1, 0.56f),
        new Vector3f(1, 0, 0.56f), new Vector3f(1, 1, 0.56f)
    };

    @Override
    public void update(ItemStackRenderState state, ItemStack stack, ItemModelResolver resolver,
                       ItemDisplayContext context, ClientLevel level, ItemOwner owner, int seed) {
        SensorClient.Reading reading = SensorClient.read(stack, context);
        ItemStackRenderState sample = new ItemStackRenderState();
        if (!reading.sample().isEmpty() && !(reading.sample().getItem() instanceof OreSensorItem)) {
            resolver.updateForTopItem(sample, reading.sample(), ItemDisplayContext.GUI, level, owner, seed);
        }
        // The charge, sonar and needle must keep animating in the inventory item cache too.
        state.setAnimated();
        ItemStackRenderState.LayerRenderState layer = state.newLayer();
        layer.setupSpecialModel(renderer, new Snapshot(reading, sample));
        layer.setExtents(() -> EXTENTS);
        properties.applyToLayer(layer, context);
    }

    public record Unbaked() implements ItemModel.Unbaked {
        public static final MapCodec<Unbaked> CODEC = MapCodec.unit(new Unbaked());
        private static final Identifier DISPLAY = Identifier.fromNamespaceAndPath("oresense", "item/ore_sensor");
        @Override public MapCodec<Unbaked> type() { return CODEC; }
        @Override public void resolveDependencies(ResolvableModel.Resolver resolver) {
            resolver.markDependency(DISPLAY);
            SensorClient.layers().forEach(resolver::markDependency);
        }
        @Override public ItemModel bake(ItemModel.BakingContext context) {
            var baker = context.blockModelBaker();
            Map<Identifier, List<BakedQuad>> models = new HashMap<>();
            for (Identifier layer : SensorClient.layers()) {
                var model = baker.getModel(layer);
                models.put(layer, model.bakeTopGeometry(model.getTopTextureSlots(), baker,
                        BlockModelRotation.IDENTITY).getAll());
            }
            var display = baker.getModel(DISPLAY);
            return new SensorItemModel(ModelRenderProperties.fromResolvedModel(baker, display, display.getTopTextureSlots()),
                    new SensorRenderer(models));
        }
    }
}
