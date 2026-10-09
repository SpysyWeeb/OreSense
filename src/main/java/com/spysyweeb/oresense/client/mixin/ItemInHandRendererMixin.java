package com.spysyweeb.oresense.client.mixin;

import com.spysyweeb.oresense.OreSensorItem;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.FirstPersonHandsAndItems;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A new sensor reading updates the held stack without replaying its equip animation. */
@Mixin(FirstPersonHandsAndItems.class)
public abstract class ItemInHandRendererMixin {
    @Unique private int oresense$lastSelectedSlot = -1;
    @Shadow private boolean shouldInstantlyReplaceVisibleItem(ItemStack oldStack, ItemStack newStack, LocalPlayer player) { throw new AssertionError(); }

    @Redirect(method = "tick", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/player/FirstPersonHandsAndItems;shouldInstantlyReplaceVisibleItem(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/player/LocalPlayer;)Z"))
    private boolean oresense$keepMainHandReading(FirstPersonHandsAndItems renderer, ItemStack oldStack, ItemStack newStack, LocalPlayer player) {
        if (oldStack.getItem() instanceof OreSensorItem && oldStack.getItem() == newStack.getItem()) {
            return player.getInventory().getSelectedSlot() == oresense$lastSelectedSlot;
        }
        return shouldInstantlyReplaceVisibleItem(oldStack, newStack, player);
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", ordinal = 1,
            target = "Lnet/minecraft/client/player/FirstPersonHandsAndItems;shouldInstantlyReplaceVisibleItem(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/player/LocalPlayer;)Z"))
    private boolean oresense$keepOffHandReading(FirstPersonHandsAndItems renderer, ItemStack oldStack, ItemStack newStack, LocalPlayer player) {
        if (oldStack.getItem() instanceof OreSensorItem && oldStack.getItem() == newStack.getItem()) return true;
        return shouldInstantlyReplaceVisibleItem(oldStack, newStack, player);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void oresense$rememberSelectedSlot(LocalPlayer player, CallbackInfo ci) {
        oresense$lastSelectedSlot = player.getInventory().getSelectedSlot();
    }
}
