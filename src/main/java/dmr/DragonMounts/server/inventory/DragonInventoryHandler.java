package dmr.DragonMounts.server.inventory;

import dmr.DragonMounts.DMR;
import dmr.DragonMounts.common.capability.types.NBTInterface;
import dmr.DragonMounts.network.packets.ClearDragonInventoryPacket;
import dmr.DragonMounts.network.packets.RequestDragonInventoryPacket;
import dmr.DragonMounts.server.entity.TameableDragonEntity;
import dmr.DragonMounts.server.worlddata.DragonWorldData;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerListener;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = DMR.MOD_ID)
public class DragonInventoryHandler {

    // This is a client-side cache of dragon inventories which will be overwritten
    // by the server
    // whenever it changes or starts tracking a dragon
    public static Map<UUID, DragonInventory> clientSideInventories = new HashMap<>();

    @SubscribeEvent
    public static void startTracking(PlayerEvent.StartTracking startTracking) {
        if (startTracking.getEntity().level.isClientSide()) {
            return;
        }

        if (startTracking.getTarget() instanceof TameableDragonEntity dragon) {
            var dragonInventory = getOrCreateInventory(dragon);
            PacketDistributor.sendToPlayer(
                    (ServerPlayer) startTracking.getEntity(),
                    new RequestDragonInventoryPacket(dragon.getDragonUUID(), dragonInventory.writeNBT()));
        }
    }

    @SubscribeEvent
    public static void stopTracking(PlayerEvent.StopTracking stopTracking) {
        if (stopTracking.getEntity().level.isClientSide()) {
            return;
        }

        if (stopTracking.getTarget() instanceof TameableDragonEntity dragon) {
            PacketDistributor.sendToPlayer(
                    (ServerPlayer) stopTracking.getEntity(), new ClearDragonInventoryPacket(dragon.getDragonUUID()));
        }
    }

    public static DragonInventory getOrCreateInventory(TameableDragonEntity dragon) {
        return getOrCreateInventory(dragon.level, dragon.getDragonUUID());
    }

    public static DragonInventory getOrCreateInventory(Level level, UUID uuid) {
        if (uuid == null) return new DragonInventory(level);

        if (level == null) {
            throw new NullPointerException("Cannot get dragon inventory for null level. UUID: " + uuid);
        }

        if (level.isClientSide()) {
            clientSideInventories.computeIfAbsent(uuid, id -> new DragonInventory(level));
            return clientSideInventories.get(uuid);
        }

        // Wave 2 (advisor Q8): ALL server-side dragon-inventory access goes through a
        // single GLOBAL store — the overworld's DragonWorldData. The old per-dimension
        // stores plus two manual hand-off blocks were the #98 split-brain: equipment
        // flags travelled in entity NBT while contents sat in whichever dimension's
        // store last ran a hand-off (neither called setDirty). Entries written by older
        // versions are migrated lazily: on a global-store miss, the entity's current
        // level's store is checked and that single entry is moved up.
        // (dragonHistory/deadDragons intentionally stay per-dimension — community.2.)
        var globalLevel = ((ServerLevel) level).getServer().overworld();
        DragonWorldData globalData = DragonWorldData.getInstance(globalLevel);

        DragonInventory inventory = globalData.dragonInventories.get(uuid);

        if (inventory == null) {
            // Wave 2 follow-up: scan EVERY dimension's local store, not just the dragon's
            // CURRENT level. The old check (`level != globalLevel`) only looked at the
            // dragon's current dimension, so a dragon that had already returned to the
            // overworld (level == globalLevel) with its legacy inventory still stranded in
            // the Nether/End's store skipped migration entirely and fell straight to
            // "create new inventory" below — silently and irrecoverably losing the
            // saddle/armor/chest contents (the exact #98 signature: equipment flags on the
            // entity, contents lost in the wrong dimension's SavedData). A stray entry can
            // be left in ANY dimension by the pre-Wave-2 hand-off code or a third-party
            // teleport (Waystones) that bypassed changeDimension.
            for (var candidateLevel : ((ServerLevel) level).getServer().getAllLevels()) {
                if (candidateLevel == globalLevel) {
                    continue; // already checked above
                }

                DragonWorldData localData = DragonWorldData.getInstance(candidateLevel);
                var migrated = localData.dragonInventories.remove(uuid);
                if (migrated != null) {
                    DMR.LOGGER.info(
                            "Migrating dragon inventory {} from stranded per-dimension store {} to the global"
                                    + " (overworld) store",
                            uuid,
                            candidateLevel.dimension().location());
                    globalData.dragonInventories.put(uuid, migrated);
                    globalData.setDirty();
                    localData.setDirty();
                    inventory = migrated;
                    break;
                }
            }
        }

        if (inventory == null) {
            DMR.LOGGER.debug("Creating new dragon inventory for {}", uuid);
            inventory = new DragonInventory(level);
            globalData.dragonInventories.put(uuid, inventory);
            globalData.setDirty();
        }

        return inventory;
    }

    public static class DragonInventory implements NBTInterface, ContainerListener {

        @Getter
        @Setter
        boolean isDirty = false;

        public DragonInventory(Level level) {
            this.registryAccess = level.registryAccess();
            this.inventory.addListener(this);
        }

        public DragonInventory(HolderLookup.Provider registryAccess) {
            this.registryAccess = registryAccess;
            this.inventory.addListener(this);
        }

        public static final int SADDLE_SLOT = 0;
        public static final int ARMOR_SLOT = 1;
        public static final int CHEST_SLOT = 2;
        public static final int INVENTORY_SIZE = 9 * 3;

        private final HolderLookup.Provider registryAccess;
        public SimpleContainer inventory = new SimpleContainer(getInventorySize());

        public int getInventorySize() {
            return 3 + DragonInventory.INVENTORY_SIZE;
        }

        @Override
        public CompoundTag writeNBT() {
            CompoundTag compound = new CompoundTag();
            ListTag listtag = new ListTag();
            for (int i = 0; i < this.inventory.getContainerSize(); i++) {
                ItemStack itemstack = this.inventory.getItem(i);
                if (!itemstack.isEmpty()) {
                    CompoundTag compoundtag = new CompoundTag();
                    compoundtag.putByte("Slot", (byte) (i));
                    listtag.add(itemstack.save(registryAccess, compoundtag));
                }
            }

            compound.put("Items", listtag);
            return compound;
        }

        @Override
        public void readNBT(CompoundTag compound) {
            ListTag listtag = new ListTag();
            if (compound.contains("Items")) {
                listtag = compound.getList("Items", 10);
            }

            for (int i = 0; i < listtag.size(); i++) {
                CompoundTag compoundtag = listtag.getCompound(i);
                int j = compoundtag.getByte("Slot") & 255;
                if (j < this.inventory.getContainerSize()) {
                    this.inventory.setItem(
                            j, ItemStack.parse(registryAccess, compoundtag).orElse(ItemStack.EMPTY));
                }
            }
        }

        @Override
        public void containerChanged(Container container) {
            setDirty(true);
        }
    }
}
