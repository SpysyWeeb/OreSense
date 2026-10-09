package com.spysyweeb.oresense.client.mixin;

import com.spysyweeb.oresense.OreSensorItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A new sensor reading updates the held stack without replaying its equip animation. */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @Shadow @Final private Minecraft minecraft;
    @Unique private int oresense$lastSelectedSlot = -1;
    @Shadow private boolean shouldInstantlyReplaceVisibleItem(ItemStack oldStack, ItemStack newStack) { throw new AssertionError(); }

    @Redirect(method = "tick", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;shouldInstantlyReplaceVisibleItem(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean oresense$keepMainHandReading(ItemInHandRenderer renderer, ItemStack oldStack, ItemStack newStack) {
        if (oldStack.getItem() instanceof OreSensorItem && oldStack.getItem() == newStack.getItem()) {
            return minecraft.player.getInventory().selected == oresense$lastSelectedSlot;
        }
        return shouldInstantlyReplaceVisibleItem(oldStack, newStack);
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", ordinal = 1,
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;shouldInstantlyReplaceVisibleItem(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean oresense$keepOffHandReading(ItemInHandRenderer renderer, ItemStack oldStack, ItemStack newStack) {
        if (oldStack.getItem() instanceof OreSensorItem && oldStack.getItem() == newStack.getItem()) return true;
        return shouldInstantlyReplaceVisibleItem(oldStack, newStack);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void oresense$rememberSelectedSlot(CallbackInfo ci) {
        oresense$lastSelectedSlot = minecraft.player.getInventory().selected;
    }
}
