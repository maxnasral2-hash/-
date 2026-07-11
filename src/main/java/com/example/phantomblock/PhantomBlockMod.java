package com.example.phantomblock;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.Deque;

public class PhantomBlockMod implements ClientModInitializer {

    private static final int TICKS_BETWEEN_STEPS = 0; // поставь 1-2 если не срабатывает стабильно

    public static boolean phantomEnabled = false;

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("phantomblock", "phantom_category"));

    private static KeyBinding toggleKey;

    private final Deque<Runnable> pendingSteps = new ArrayDeque<>();
    private int waitTicks = 0;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.phantomblock.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_APOSTROPHE,
                CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                phantomEnabled = !phantomEnabled;
                if (client.player != null) {
                    client.player.sendMessage(
                            Text.literal("Phantom-block режим: " + (phantomEnabled ? "§aВКЛ" : "§cВЫКЛ")),
                            true
                    );
                }
            }

            if (waitTicks > 0) {
                waitTicks--;
            } else if (!pendingSteps.isEmpty()) {
                Runnable step = pendingSteps.poll();
                step.run();
                waitTicks = TICKS_BETWEEN_STEPS;
            }
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!phantomEnabled) return ActionResult.PASS;
            if (!world.isClient()) return ActionResult.PASS;
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;

            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.interactionManager == null) return ActionResult.PASS;

            ItemStack heldItem = client.player.getStackInHand(Hand.MAIN_HAND);
            if (!(heldItem.getItem() instanceof net.minecraft.item.BlockItem)) {
                return ActionResult.PASS;
            }

            if (!pendingSteps.isEmpty()) {
                return ActionResult.FAIL;
            }

            queuePhantomSequence(client, hitResult);
            return ActionResult.FAIL;
        });
    }

    private void queuePhantomSequence(MinecraftClient client, BlockHitResult hitResult) {
        ClientPlayerEntity player = client.player;
        ClientPlayNetworkHandler net = client.getNetworkHandler();
        if (player == null || net == null) return;

        pendingSteps.add(() -> swapHands(client, player, net));

        pendingSteps.add(() -> {
            if (client.interactionManager != null) {
                client.interactionManager.interactBlock(player, Hand.OFF_HAND, hitResult);
                player.swingHand(Hand.OFF_HAND);
            }
        });

        pendingSteps.add(() -> swapHands(client, player, net));
        pendingSteps.add(() -> swapHands(client, player, net));
    }

    private void swapHands(MinecraftClient client, ClientPlayerEntity player, ClientPlayNetworkHandler net) {
        ItemStack main = player.getStackInHand(Hand.MAIN_HAND);
        ItemStack off = player.getStackInHand(Hand.OFF_HAND);
        player.setStackInHand(Hand.MAIN_HAND, off);
        player.setStackInHand(Hand.OFF_HAND, main);

        net.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                BlockPos.ORIGIN,
                Direction.DOWN
        ));
    }
}
