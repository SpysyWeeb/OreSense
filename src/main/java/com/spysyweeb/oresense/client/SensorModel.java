package com.spysyweeb.oresense.client;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.client.resources.model.BuiltInModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.client.resources.model.UnbakedModel;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IUnbakedGeometry;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** Keeps the animated layers in the same resource-reload generation as the sensor model. */
public final class SensorModel implements IUnbakedGeometry<SensorModel> {
    @Override
    public void resolveDependencies(UnbakedModel.Resolver resolver, IGeometryBakingContext context) {
        SensorClient.layers().forEach(layer -> resolver.resolve(layer.id()));
    }

    @Override
    public BakedModel bake(IGeometryBakingContext context, ModelBaker baker,
                           Function<Material, TextureAtlasSprite> sprites, ModelState state, java.util.List<net.minecraft.client.renderer.block.model.ItemOverride> overrides) {
        Map<ModelResourceLocation, BakedModel> layers = new HashMap<>();
        for (ModelResourceLocation layer : SensorClient.layers()) {
            layers.put(layer, baker.bake(layer.id(), BlockModelRotation.X0_Y0));
        }
        return new Baked(context, sprites.apply(context.getMaterial("particle")), Map.copyOf(layers));
    }

    public static final class Baked extends BuiltInModel {
        private final Map<ModelResourceLocation, BakedModel> layers;

        private Baked(IGeometryBakingContext context, TextureAtlasSprite particle,
                      Map<ModelResourceLocation, BakedModel> layers) {
            super(context.getTransforms(), particle, context.useBlockLight());
            this.layers = layers;
        }

        public BakedModel getModel(ModelResourceLocation layer) {
            return layers.get(layer);
        }
    }
}
