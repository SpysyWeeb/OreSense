package com.spysyweeb.oresense;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.HolderLookup;
import net.minecraft.SharedConstants;
import com.mojang.serialization.Dynamic;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.List;

/** Copy-on-write storage: component values must never be mutated in place. */
public final class SensorData {
    private SensorData() {}

    public static CompoundTag read(ItemStack sensor) {
        return sensor.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    public static void write(ItemStack sensor, CompoundTag data) {
        if (data.isEmpty()) sensor.remove(DataComponents.CUSTOM_DATA);
        else sensor.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    }

    public static ItemStack sample(ItemStack sensor) {
        return sensor.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyOne();
    }

    public static void sample(ItemStack sensor, ItemStack sample) {
        if (sample.isEmpty()) sensor.remove(DataComponents.CONTAINER);
        else sensor.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(sample.copyWithCount(1))));
    }

    /** Vanilla migrates our old outer tag to custom_data, but cannot see the nested sample stack. */
    public static void migrateSample(ItemStack sensor, HolderLookup.Provider registries) {
        CustomData stored = sensor.get(DataComponents.CUSTOM_DATA);
        if (stored == null || !stored.contains(OreSensorItem.SAMPLE_TAG)) return;
        CompoundTag data = stored.copyTag();
        if (!sensor.has(DataComponents.CONTAINER)) {
            CompoundTag legacy = data.getCompound(OreSensorItem.SAMPLE_TAG);
            Dynamic<Tag> fixed = DataFixers.getDataFixer().update(References.ITEM_STACK,
                    new Dynamic<>(NbtOps.INSTANCE, legacy), 3465,
                    SharedConstants.getCurrentVersion().getDataVersion().getVersion());
            ItemStack sample = ItemStack.parseOptional(registries, (CompoundTag) fixed.getValue());
            if (sample.isEmpty()) return; // retain unresolvable legacy data instead of discarding it
            sample(sensor, sample);
        }
        data.remove(OreSensorItem.SAMPLE_TAG);
        write(sensor, data);
    }
}
