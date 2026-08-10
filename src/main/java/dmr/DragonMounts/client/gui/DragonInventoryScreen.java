package dmr.DragonMounts.client.gui;

import dmr.DragonMounts.DMR;
import dmr.DragonMounts.config.ServerConfig;
import dmr.DragonMounts.network.packets.DragonStatePacket;
import dmr.DragonMounts.server.container.DragonContainerMenu;
import dmr.DragonMounts.server.entity.TameableDragonEntity;
import dmr.DragonMounts.server.inventory.DragonInventoryHandler.DragonInventory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.gui.widget.ExtendedButton;
import net.neoforged.neoforge.network.PacketDistributor;

@OnlyIn(Dist.CLIENT)
public class DragonInventoryScreen extends AbstractContainerScreen<DragonContainerMenu> {
    private static final ResourceLocation INVENTORY_LOCATION = DMR.id("textures/gui/dragon_inventory.png");
    private static final ResourceLocation STATS_LOCATION = DMR.id("textures/gui/dragon_inventory_stats.png");

    private final TameableDragonEntity dragon;

    /** The mouse x-position recorded during the last rendered frame. */
    private float xMouse;

    /** The mouse y-position recorded during the last rendered frame. */
    private float yMouse;

    public DragonInventoryScreen(DragonContainerMenu pMenu, Inventory pPlayerInventory, Component component) {
        super(pMenu, pPlayerInventory, component);
        this.dragon = pMenu.dragon;
        this.imageWidth = 269;
        this.imageHeight = 204;

        this.titleLabelX = 101;

        this.inventoryLabelX = 101;
        this.inventoryLabelY = 112;
    }

    @Override
    protected void init() {
        super.init();
        int xSize = 269;
        int ySize = 204;
        leftPos = (width - xSize) / 2;
        topPos = (height - ySize) / 2;

        addRenderableWidget(new ModeButton(leftPos + 242 - 18 * 2, topPos + 17, "dmr.inventory.sit", p_214087_1_ -> {
            PacketDistributor.sendToServer(new DragonStatePacket(dragon.getId(), 0));
        }));

        addRenderableWidget(new ModeButton(leftPos + 242 - 18, topPos + 17, "dmr.inventory.follow", p_214087_1_ -> {
            PacketDistributor.sendToServer(new DragonStatePacket(dragon.getId(), 1));
        }));

        addRenderableWidget(new ModeButton(leftPos + 242, topPos + 17, "dmr.inventory.wander", p_214087_1_ -> {
            PacketDistributor.sendToServer(new DragonStatePacket(dragon.getId(), 2));
        }));

        for (int i = 0; i < 6; i++) {
            var hasAbility = dragon.getBreed().getAbilities().size() > i;
            var ability = hasAbility ? dragon.getBreed().getAbilities().get(i) : null;
            var btn = new DragonAbilityButton(leftPos + 10 + (i % 3 * 29), topPos + 143 + ((i / 3) * 29), ability);
            addRenderableWidget(btn);
        }

        if (ServerConfig.ENABLE_RANDOM_STATS) {
            addRenderableWidget(new StatButton(
                    66,
                    "dmr.inventory.stats.max_hp",
                    ResourceLocation.withDefaultNamespace("textures/gui/sprites/hud/heart/full.png"),
                    1,
                    dragon.getEntityData().get(TameableDragonEntity.healthAttribute),
                    ServerConfig.LOWER_MAX_HEALTH,
                    ServerConfig.UPPER_MAX_HEALTH));
            addRenderableWidget(new StatButton(
                    81,
                    "dmr.inventory.stats.damage",
                    ResourceLocation.withDefaultNamespace("textures/mob_effect/strength.png"),
                    2,
                    dragon.getEntityData().get(TameableDragonEntity.damageAttribute),
                    ServerConfig.LOWER_DAMAGE,
                    ServerConfig.UPPER_DAMAGE));
            addRenderableWidget(new StatButton(
                    96,
                    "dmr.inventory.stats.speed",
                    ResourceLocation.withDefaultNamespace("textures/mob_effect/speed.png"),
                    3,
                    dragon.getEntityData().get(TameableDragonEntity.speedAttribute),
                    ServerConfig.LOWER_SPEED,
                    ServerConfig.UPPER_SPEED));
            addRenderableWidget(new StatButton(
                    111,
                    "dmr.inventory.stats.scale",
                    null,
                    4,
                    dragon.getEntityData().get(TameableDragonEntity.maxScaleAttribute),
                    0,
                    1));
        }
    }

    class StatButton extends ExtendedButton {
        private final String title;
        private final ResourceLocation sprite;
        private final int barNum;
        private final double value;
        private final double actual;
        private final double minValue;
        private final double maxValue;

        public StatButton(
                int y,
                String title,
                ResourceLocation sprite,
                int barNum,
                double value,
                double minValue,
                double maxValue) {
            super(leftPos + 10, topPos + y, 80, 12, Component.empty(), (btn) -> {});
            this.title = title;
            this.sprite = sprite;
            this.barNum = barNum;
            this.value = value;
            var actual = (value * (maxValue - minValue)) + minValue;
            this.actual = (int) (actual * 10) / 10d;
            this.maxValue = (int) (maxValue * 10) / 10d;
            this.minValue = (int) (minValue * 10) / 10d;

            this.setTooltip(Tooltip.create(Component.translatable(
                    title + ".tooltip",
                    (this.actual > 0 ? "§a+" + this.actual : "§c" + this.actual) + "§r",
                    this.minValue,
                    this.maxValue)));
        }

        @Override
        public boolean isActive() {
            return ServerConfig.ENABLE_RANDOM_STATS;
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            // Wave 7 (upstream bug, verified byte-identical to v1.9.2 / introduced
            // upstream c6e67f8): this used to push a fake pose.translate(0, 0, 100) and
            // rely on GUI depth-testing to keep the bar (and its text) drawn "in front
            // of" the stats-panel backdrop, which was drawn LAST in render() — a
            // fragile hack that silently breaks whenever depth testing happens to be
            // off during widget render (a shader/optimization mod, or any
            // ContainerScreenEvent.Render.Background handler that leaves it disabled),
            // and never actually worked for the label/value TEXT below (GuiGraphics
            // buffers text and flushes it without preserving that Z push, so text was
            // covered even on a stock client). The backdrop now draws in renderBg(),
            // UNDER these widgets by normal paint order — see DragonInventoryScreen's
            // renderBg() — so no Z trickery is needed here at all.
            var pose = graphics.pose();

            int x = getX();
            int y = this.getY();
            int barHeight = barNum * 5;
            int textX = getX();

            if (sprite != null) {
                textX += 8;
                int spriteSize = 6;
                graphics.blit(sprite, x + 1, y - 1, 0, 0, spriteSize, spriteSize, spriteSize, spriteSize);
            }

            pose.pushPose();
            pose.scale(0.5f, 0.5f, 0.5f);
            pose.translate(textX, y, 0);
            graphics.drawString(Minecraft.getInstance().font, Component.translatable(title), textX, y, 0xffffff, false);
            pose.popPose();

            pose.pushPose();
            var valueString =
                    (this.actual > 0 ? "§a+" + this.actual : "§c" + this.actual) + "§r / §3" + maxValue + "§r";
            var length = Minecraft.getInstance().font.width(valueString);
            var posX = getX() + (width - (length / 2));
            pose.scale(0.5f, 0.5f, 0.5f);
            pose.translate(posX, y, 0);
            graphics.drawString(Minecraft.getInstance().font, Component.literal(valueString), posX, y, 0xffffff, false);
            pose.popPose();

            // Wave 7: dragon_inventory_stats.png is actually 89x30, not 80x30 — the
            // wrong textureWidth stretched the full 89px-wide texture to fit an 80px
            // destination (squished bar, tick mark visibly off its authored column).
            // Destination/sample width stays 80 (the widget is only 80px wide, and the
            // 89px-wide backdrop at leftPos+6..95 doesn't have room for a 89px-wide bar
            // starting at leftPos+10 anyway: 10+89=99 > 95) — only the texture-size
            // args change, so the sample region is now genuinely 80-of-89 texture
            // pixels at a 1:1 pixel mapping (tick column at texture x=20 lands at
            // widget x=20, exactly where it was authored), leaving the rightmost 9px
            // of the texture unused rather than the whole thing squeezed.
            graphics.blit(STATS_LOCATION, x, y + 6, 0, 0, 80, 5, 89, 30);
            graphics.blit(STATS_LOCATION, x, y + 6, 0, barHeight, (int) (80d * value), 5, 89, 30);
        }
    }

    public static class ModeButton extends ExtendedButton {
        public ModeButton(int xPos, int yPos, String text, Button.OnPress handler) {
            super(
                    xPos,
                    yPos,
                    18,
                    18,
                    Component.literal(Component.translatable(text).getString(1)),
                    handler);
            this.setTooltip(Tooltip.create(Component.translatable(text)));
        }
    }

    private static final ResourceLocation TITLE_BOX_SPRITE =
            ResourceLocation.withDefaultNamespace("advancements/title_box");

    public void render(GuiGraphics graphics, int pMouseX, int pMouseY, float pPartialTick) {
        this.renderBackground(graphics, pMouseX, pMouseY, pPartialTick);
        this.xMouse = (float) pMouseX;
        this.yMouse = (float) pMouseY;
        super.render(graphics, pMouseX, pMouseY, pPartialTick);
        this.renderTooltip(graphics, pMouseX, pMouseY);

        // Intentionally still drawn here, LAST — this scrim is meant to sit on top of
        // everything (widgets, labels, tooltip) when the dragon has no chest, unlike
        // the stats-panel backdrop above (moved to renderBg(), Wave 7).
        if (!dragon.hasChest()) {
            graphics.fill(
                    leftPos + 101,
                    topPos + 52,
                    leftPos + 101 + 162,
                    topPos + 52 + 54,
                    FastColor.ARGB32.color(200, 0x5B5B5B));
        }
    }

    protected void renderLabels(GuiGraphics pGuiGraphics, int pMouseX, int pMouseY) {
        pGuiGraphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, 4210752, false);
        pGuiGraphics.drawString(this.font, Component.translatable("dmr.inventory.abilities"), 8, 131, 0xffffff, false);

        if (ServerConfig.ENABLE_RANDOM_STATS) {
            pGuiGraphics.drawString(this.font, Component.translatable("dmr.inventory.stats"), 8, 53, 0xffffff, false);
        }

        pGuiGraphics.drawString(
                this.font,
                dragon.getInventory().getItem(DragonInventory.CHEST_SLOT).is(Items.ENDER_CHEST)
                        ? Component.translatable("container.enderchest")
                        : Component.translatable("dmr.inventory.chest"),
                this.inventoryLabelX,
                42,
                4210752,
                false);

        pGuiGraphics.drawString(
                this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, 4210752, false);
    }

    protected void renderBg(GuiGraphics pGuiGraphics, float pPartialTick, int pMouseX, int pMouseY) {
        pGuiGraphics.blit(dragon.getBreed().getInventoryTexture(), leftPos + 3, topPos + 3, 0, 0, 96, 198, 16, 16);
        pGuiGraphics.fill(
                leftPos + 3, topPos + 3, leftPos + 3 + 96, topPos + 3 + 200, FastColor.ARGB32.color(100, 0x5B5B5B));
        pGuiGraphics.blit(INVENTORY_LOCATION, leftPos, topPos, 0, 0, 269, 204, 512, 512);

        // Wave 7 (upstream bug, verified byte-identical to v1.9.2 / introduced upstream
        // c6e67f8): the stats-panel backdrop used to be drawn LAST, in render(), after
        // the StatButton widgets (and their label/value text) had already been drawn —
        // geometry fully overlaps all 4 bars, and only a fragile GUI-depth-test Z-trick
        // in StatButton.renderWidget (now removed) kept the BARS visible; the TEXT was
        // never protected by it and stayed hidden even on a stock client. renderBg()
        // runs before any widget is drawn, so drawing it here puts the backdrop UNDER
        // the widgets by normal paint order instead — no Z trickery needed.
        if (ServerConfig.ENABLE_RANDOM_STATS) {
            pGuiGraphics.blitSprite(TITLE_BOX_SPRITE, leftPos + 6, topPos + 49 + 10, 89, 70);
        }

        InventoryScreen.renderEntityInInventoryFollowsMouse(
                pGuiGraphics,
                leftPos + 7,
                topPos + 7,
                leftPos + 7 + 87,
                topPos + 7 + 42,
                7,
                1,
                this.xMouse,
                this.yMouse,
                this.dragon);
    }
}
