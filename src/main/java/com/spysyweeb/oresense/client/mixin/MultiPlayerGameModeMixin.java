package com.spysyweeb.oresense.client.mixin;

import com.spysyweeb.oresense.OreSensorItem;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Scanning NBT changes must not restart the block the player is mining with a sensor. */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
    @Redirect(method = "sameDestroyTarget", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;isSameItemSameTags(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean oresense$ignoreReadingChanges(ItemStack first, ItemStack second) {
        if (first.getItem() instanceof OreSensorItem && first.getItem() == second.getItem()) return true;
        return ItemStack.isSameItemSameTags(first, second);
    }
}
