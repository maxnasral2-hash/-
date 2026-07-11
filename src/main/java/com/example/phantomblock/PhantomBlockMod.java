package com.example.phantomblock;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
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

    private static int ticksBetweenSteps = 2;
    private static boolean debugMessages = false;

    public static boolean phantomEnabled = false;

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("phantomblock", "phantom_category"));

    private static KeyBinding toggleKey;
    private static KeyBinding resetKey;

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

        resetKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.phantomblock.reset",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_SEMICOLON,
                CATEGORY
        ));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("phantomdelay")
                    .then(ClientCommandManager.argument("ticks", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 20))
                            .executes(ctx -> {
                                ticksBetweenSteps = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "ticks");
                                MinecraftClient.getInstance().player.sendMessage(
                                        Text.literal("Задержка между шагами: " + ticksBetweenSteps + " тик(ов)"), false);
                                return 1;
                            })));

            dispatcher.register(ClientCommandManager.literal("phantomdebug")
                    .executes(ctx -> {
                        debugMessages = !debugMessages;
                        MinecraftClient.getInstance().player.sendMessage(
                                Text.literal("Debug-сообщения: " + (debugMessages ? "ВКЛ" : "ВЫКЛ")), false);
                        return 1;
                    }));
        });

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

            while (resetKey.wasPressed()) {
                if (client.player != null && client.getNetworkHandler() != null) {
                    pendingSteps.clear();
                    swapHands(client, client.player, client.getNetworkHandler());
                    client.player.sendMessage(Text.literal("§eРучной свап рук выполнен"), true);
                }
            }

            if (waitTicks > 0) {
                waitTicks--;
            } else if (!pendingSteps.isEmpty()) {
                Runnable step = pendingSteps.poll();
                step.run();
                waitTicks = ticksBetweenSteps;
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

        pendingSteps.add(() -> {
            debug(client, "Шаг 1: F + постановка одновременно");
            swapHands(client, player, net);
            if (client.interactionManager != null) {
                client.interactionManager.interactBlock(player, Hand.OFF_HAND, hitResult);
                player.swingHand(Hand.OFF_HAND);
            }
        });

        pendingSteps.add(() -> {
            debug(client, "Шаг 2: F номер 1 после постановки");
            swapHands(client, player, net);
        });

        pendingSteps.add(() -> {
            debug(client, "Шаг 3: F номер 2 после постановки");
            swapHands(client, player, net);
        });
    }

    private void debug(MinecraftClient client, String msg) {
        if (debugMessages && client.player != null) {
            client.player.sendMessage(Text.literal("§7[phantom] " + msg), true);
        }
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
